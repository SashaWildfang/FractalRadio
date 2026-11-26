/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.session;

import com.sasha.radio.song.Note;
import com.sasha.radio.song.Playback;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays notes on its own thread at their exact time (about 2 ms resolution), instead of rounding every
 * note to the server's 50 ms ticks. Rounding made any song whose tempo doesn't divide 20 evenly (most
 * of them: 6.25, 8.5, 7.5 ticks/s...) play with an uneven, stuttering rhythm.
 *
 * <p>The main thread decides what plays and who hears it, and publishes an immutable snapshot every
 * server tick; this thread only reads snapshots and sends sound packets.
 *
 * <p>Bedrock players (through Geyser) are different: Geyser sends packets to them in batches about every
 * 50 ms, so notes sent at their exact times land in whichever batch is next, chords get split across two
 * batches and big batches make the Bedrock client drop sounds. For them, notes are collected for 50 ms and
 * sent together: one chord, one batch. Notes that repeat in a batch are merged, only the loudest
 * {@link #BEDROCK_MAX_NOTES} play, and all play at the player (no stereo placement).
 */
final class AudioEngine implements Runnable {

    private static final long RESOLUTION_NANOS = 2_000_000L;
    /** How far (in blocks) a fully panned note is placed to the side of the listener. */
    private static final double STEREO_DISTANCE = 2.0;
    /** How long notes for a Bedrock player are collected before they are sent together. */
    private static long BEDROCK_WINDOW_NANOS = 50_000_000L;
    /** The most notes a Bedrock player hears at once. */
    static int BEDROCK_MAX_NOTES = 8;

    /** One listener as of the last server tick. */
    record Listener(Player player, float volume, World world, double x, double y, double z, double rightX, double rightZ,
                    boolean bedrock) {
        static Listener of(Player player, float volume) {
            Location eye = player.getEyeLocation();
            double yaw = Math.toRadians(eye.getYaw());
            return new Listener(player, volume, eye.getWorld(), eye.getX(), eye.getY(), eye.getZ(), -Math.cos(yaw), -Math.sin(yaw),
                    isBedrock(player.getUniqueId()));
        }
    }

    /** Notes waiting to be sent to one Bedrock player. */
    private static final class Pending {
        final List<Note> notes = new ArrayList<>();
        long since;
    }

    private static Object floodgate;
    private static Method isFloodgatePlayer;
    private static boolean lookedForFloodgate;

    /** Floodgate's own answer when it is installed; otherwise Floodgate's UUID form (top 64 bits zero). */
    static synchronized boolean isBedrock(UUID id) {
        if (!lookedForFloodgate) {
            lookedForFloodgate = true;
            try {
                Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgate = type.getMethod("getInstance").invoke(null);
                isFloodgatePlayer = type.getMethod("isFloodgatePlayer", UUID.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                floodgate = null;
            }
        }
        if (floodgate != null) {
            try {
                return (boolean) isFloodgatePlayer.invoke(floodgate, id);
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        return id.getMostSignificantBits() == 0;
    }

    record Stream(Playback playback, List<Listener> listeners) {
    }

    private record Snapshot(List<Stream> streams, SoundCategory category, boolean stereo) {
    }

    private final Logger logger;
    private volatile Snapshot snapshot = new Snapshot(List.of(), SoundCategory.RECORDS, true);
    private volatile boolean running;
    private Thread thread;
    private boolean warned;

    AudioEngine(Logger logger) {
        this.logger = logger;
        // bedrock.batch-ms and bedrock.max-notes in config.yml (read when the plugin starts).
        try {
            var plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("FractalRadio");
            if (plugin == null) plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("Radio");
            if (plugin != null) {
                var config = plugin.getConfig();
                BEDROCK_WINDOW_NANOS = Math.max(10, Math.min(500, config.getInt("bedrock.batch-ms", 50))) * 1_000_000L;
                BEDROCK_MAX_NOTES = Math.max(1, Math.min(32, config.getInt("bedrock.max-notes", 8)));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // defaults
        }
    }

    void start() {
        running = true;
        thread = new Thread(this, "Radio Audio");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    void stop() {
        running = false;
        snapshot = new Snapshot(List.of(), SoundCategory.RECORDS, true);
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Called by the main thread every server tick. */
    void publish(List<Stream> streams, SoundCategory category, boolean stereo) {
        snapshot = new Snapshot(List.copyOf(streams), category, stereo);
    }

    @Override
    public void run() {
        List<Note> due = new ArrayList<>();
        Map<Player, Pending> bedrock = new HashMap<>();
        while (running) {
            Snapshot current = snapshot;
            long now = System.nanoTime();
            for (Stream stream : current.streams) {
                due.clear();
                stream.playback.drain(now, due);
                if (due.isEmpty()) continue;
                for (Listener listener : stream.listeners) {
                    if (!listener.bedrock) {
                        play(listener, due, current);
                        continue;
                    }
                    Pending pending = bedrock.computeIfAbsent(listener.player, p -> new Pending());
                    if (pending.notes.isEmpty()) pending.since = now;
                    for (Note note : due) {
                        float volume = note.volume() * listener.volume;
                        if (volume >= 0.005f) pending.notes.add(new Note(note.sound(), volume, note.pitch(), 0));
                    }
                }
            }
            if (!bedrock.isEmpty()) {
                var it = bedrock.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Player, Pending> entry = it.next();
                    Pending pending = entry.getValue();
                    if (pending.notes.isEmpty()) {
                        it.remove();
                    } else if (now - pending.since >= BEDROCK_WINDOW_NANOS) {
                        playBedrock(entry.getKey(), batch(pending.notes), current);
                        pending.notes.clear();
                    }
                }
            }
            LockSupport.parkNanos(RESOLUTION_NANOS);
        }
    }

    private record Same(String sound, int pitch) {
    }

    /** Merges notes with the same sound and pitch, then keeps the loudest {@link #BEDROCK_MAX_NOTES}. */
    static List<Note> batch(List<Note> notes) {
        Map<Same, Note> merged = new LinkedHashMap<>();
        for (Note note : notes) {
            merged.merge(new Same(note.sound(), Math.round(note.pitch() * 1000)), note,
                    (a, b) -> new Note(a.sound(), Math.min(1f, a.volume() + b.volume()), a.pitch(), 0));
        }
        List<Note> out = new ArrayList<>(merged.values());
        if (out.size() > BEDROCK_MAX_NOTES) {
            out.sort(Comparator.comparingDouble(Note::volume).reversed());
            out = out.subList(0, BEDROCK_MAX_NOTES);
        }
        return out;
    }

    private void playBedrock(Player player, List<Note> notes, Snapshot current) {
        try {
            if (!player.isOnline()) return;
            for (Note note : notes) player.playSound(player, note.sound(), current.category, note.volume(), note.pitch());
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                logger.log(Level.WARNING, "Could not play a note (further errors are hidden)", e);
            }
        }
    }

    private void play(Listener listener, List<Note> notes, Snapshot current) {
        try {
            Player player = listener.player;
            if (!player.isOnline()) return;
            for (Note note : notes) {
                float volume = note.volume() * listener.volume;
                if (volume < 0.005f) continue;
                if (current.stereo && note.pan() != 0) {
                    double offset = note.pan() * STEREO_DISTANCE;
                    Location at = new Location(listener.world, listener.x + listener.rightX * offset, listener.y,
                            listener.z + listener.rightZ * offset);
                    player.playSound(at, note.sound(), current.category, volume, note.pitch());
                } else {
                    player.playSound(player, note.sound(), current.category, volume, note.pitch());
                }
            }
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                logger.log(Level.WARNING, "Could not play a note (further errors are hidden)", e);
            }
        }
    }
}

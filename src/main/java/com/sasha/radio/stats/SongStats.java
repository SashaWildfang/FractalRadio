/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.sasha.radio.song.Song;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * How often each song is played or skipped, server-wide. Kept in stats.json in the plugin folder.
 *
 * <ul>
 *   <li>A <b>play</b> is a song someone listened to at least halfway (or to the end).</li>
 *   <li>A <b>skip</b> is a song someone skipped or replaced before halfway.</li>
 *   <li><b>Trending</b> scores recent plays, each day's plays counting half as much every
 *       {@value #HALF_LIFE_DAYS} days, minus half a point per recent skip.</li>
 * </ul>
 *
 * <p>Main thread only, except {@link #save()}'s file write.
 */
public final class SongStats {

    private static final double HALF_LIFE_DAYS = 3.0;
    /** Days of history kept for trending. */
    private static final int KEEP_DAYS = 30;
    /** How many songs get the "trending" badge. */
    private static final int TRENDING_BADGES = 10;
    private static final long TRENDING_CACHE_MILLIS = 60_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Clock clock;
    private Map<String, Entry> songs = new HashMap<>();
    private boolean dirty;

    private Map<String, Double> trending = Map.of();
    private Set<String> trendingTop = Set.of();
    private long trendingAt = Long.MIN_VALUE;

    public SongStats(Path file, Logger logger) {
        this(file, logger, Clock.systemDefaultZone());
    }

    public SongStats(Path file, Logger logger, Clock clock) {
        this.file = file;
        this.logger = logger;
        this.clock = clock;
    }

    /** One song's counts. Days are epoch days (local time) → plays or skips that day. */
    static final class Entry {
        int plays;
        int skips;
        Map<Long, Integer> playDays = new TreeMap<>();
        Map<Long, Integer> skipDays = new TreeMap<>();
    }

    private record Stored(Map<String, Entry> songs) {
    }

    // ---------------------------------------------------------------- recording

    public void played(Song song) {
        Entry entry = entry(song.id());
        entry.plays++;
        entry.playDays.merge(today(), 1, Integer::sum);
        changed();
    }

    public void skipped(Song song) {
        Entry entry = entry(song.id());
        entry.skips++;
        entry.skipDays.merge(today(), 1, Integer::sum);
        changed();
    }

    // ---------------------------------------------------------------- reading

    public int plays(Song song) {
        Entry entry = songs.get(song.id());
        return entry == null ? 0 : entry.plays;
    }

    public int skips(Song song) {
        Entry entry = songs.get(song.id());
        return entry == null ? 0 : entry.skips;
    }

    /** Trending score; 0 for songs nobody played lately. */
    public double trending(Song song) {
        return trendingScores().getOrDefault(song.id(), 0.0);
    }

    /** True for the few songs trending the most right now. */
    public boolean isTrending(Song song) {
        trendingScores();
        return trendingTop.contains(song.id());
    }

    private Map<String, Double> trendingScores() {
        long now = clock.millis();
        if (trendingAt != Long.MIN_VALUE && now - trendingAt < TRENDING_CACHE_MILLIS) return trending;
        long today = today();
        Map<String, Double> scores = new HashMap<>();
        songs.forEach((id, entry) -> {
            double score = decayed(entry.playDays, today) - 0.5 * decayed(entry.skipDays, today);
            if (score > 0) scores.put(id, score);
        });
        trending = scores;
        trendingTop = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(TRENDING_BADGES)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
        trendingAt = now;
        return trending;
    }

    private static double decayed(Map<Long, Integer> days, long today) {
        double total = 0;
        for (Map.Entry<Long, Integer> day : days.entrySet()) {
            long age = Math.max(0, today - day.getKey());
            total += day.getValue() * Math.pow(0.5, age / HALF_LIFE_DAYS);
        }
        return total;
    }

    // ---------------------------------------------------------------- storage

    public void load() {
        if (!Files.exists(file)) return;
        try {
            Stored stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
            songs = new HashMap<>();
            if (stored != null && stored.songs() != null) {
                stored.songs().forEach((id, entry) -> {
                    if (entry == null) return;
                    if (entry.playDays == null) entry.playDays = new TreeMap<>();
                    else entry.playDays = new TreeMap<>(entry.playDays);
                    if (entry.skipDays == null) entry.skipDays = new TreeMap<>();
                    else entry.skipDays = new TreeMap<>(entry.skipDays);
                    songs.put(id, entry);
                });
            }
            prune();
            trendingAt = Long.MIN_VALUE;
        } catch (IOException | JsonParseException e) {
            logger.warning("Could not read " + file.getFileName() + " (play counts start from zero): " + e.getMessage());
        }
    }

    /**
     * Returns a task that writes the stats if they changed since the last save, or null if nothing changed.
     * The snapshot is taken now (main thread); the returned task can run on any thread.
     */
    public Runnable save() {
        if (!dirty) return null;
        dirty = false;
        prune();
        String json = GSON.toJson(new Stored(new TreeMap<>(songs)));
        return () -> {
            try {
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, json, StandardCharsets.UTF_8);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                logger.warning("Could not save " + file.getFileName() + ": " + e.getMessage());
            }
        };
    }

    /** Drops daily history older than {@value #KEEP_DAYS} days. Total counts are kept forever. */
    private void prune() {
        long oldest = today() - KEEP_DAYS;
        for (Entry entry : songs.values()) {
            entry.playDays.keySet().removeIf(day -> day < oldest);
            entry.skipDays.keySet().removeIf(day -> day < oldest);
        }
    }

    private Entry entry(String id) {
        return songs.computeIfAbsent(id, key -> new Entry());
    }

    private void changed() {
        dirty = true;
        trendingAt = Long.MIN_VALUE;
    }

    private long today() {
        return LocalDate.now(clock).toEpochDay();
    }
}

/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.display;

import com.sasha.radio.Radio;
import com.sasha.radio.config.BarMode;
import com.sasha.radio.config.Messages;
import com.sasha.radio.config.RadioConfig;
import com.sasha.radio.session.RadioSession;
import com.sasha.radio.session.SessionManager;
import com.sasha.radio.song.Playback;
import com.sasha.radio.song.Song;
import com.sasha.radio.util.Format;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shows the current song as a boss bar or action bar line, per each player's choice. */
public final class NowPlayingBar {

    private final Radio plugin;
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    /** Each player's little equalizer: how tall each bar is (0-7), nudged up and down every update. */
    private final Map<UUID, int[]> equalizers = new HashMap<>();
    private static final String LEVELS = "\u2581\u2582\u2583\u2584\u2585\u2586\u2587\u2588";
    private static final String[] EQ_COLOURS = {"#8E2DE2", "#A44BEB", "#BA68F2", "#D07AF9", "#E58BFF"};
    private final java.util.Random random = new java.util.Random();

    public NowPlayingBar(Radio plugin) {
        this.plugin = plugin;
    }

    /** Runs every 10 ticks. */
    public void update() {
        for (Player player : Bukkit.getOnlinePlayers()) update(player);
    }

    private void update(Player player) {
        RadioConfig config = plugin.radioConfig();
        SessionManager sessions = plugin.sessions();
        RadioSession own = sessions.session(player);
        RadioSession audible = sessions.audibleSession(player);
        BarMode mode = own.settings().bar(config);
        Song song = audible.current();

        boolean show = mode != BarMode.OFF && song != null && config.worldEnabled(player.getWorld());
        if (!show || mode != BarMode.BOSSBAR) hideBossBar(player);
        if (!show) return;

        Messages m = plugin.messages();
        Playback playback = audible.playback();
        UUID host = sessions.hostOf(player.getUniqueId());
        Component source = host == null ? Component.empty()
                : m.component("bar.source", Messages.arg("name", sessions.hostName(host)));
        String key = mode == BarMode.BOSSBAR
                ? (audible.isPaused() ? "bar.boss-paused" : "bar.boss-playing")
                : (audible.isPaused() ? "bar.paused" : "bar.playing");
        Component eq = equalizer(player.getUniqueId(), audible.isPaused());
        Component text = m.component(key, TagResolver.resolver(
                Format.song(song),
                Messages.arg("elapsed", Format.duration(playback.elapsedSeconds())),
                Messages.arg("bar", m.progressBar(playback.progress())),
                Messages.arg("source", source),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("eq", eq),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("eq-mirror", mirrored(eq))));

        if (mode == BarMode.ACTIONBAR) {
            player.sendActionBar(text);
            return;
        }
        float progress = (float) Math.max(0, Math.min(1, playback.progress()));
        BossBar bar = bossBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(text, progress, config.barColor(), config.barOverlay());
            bossBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(text);
            bar.progress(progress);
            bar.color(config.barColor());
            bar.overlay(config.barOverlay());
        }
    }

    /**
     * A five-bar equalizer in the brand colours that bounces while a song plays (each bar moves up or
     * down a little every update) and lies flat while paused. Used as <eq> in the bar text.
     */
    private Component equalizer(UUID id, boolean paused) {
        int[] levels = equalizers.computeIfAbsent(id, k -> new int[] {3, 5, 2, 6, 4});
        var out = Component.text();
        for (int i = 0; i < levels.length; i++) {
            if (paused) levels[i] = 0;
            else levels[i] = Math.max(1, Math.min(7, levels[i] + random.nextInt(5) - 2 + (levels[i] < 2 ? 2 : 0)));
            out.append(Component.text(LEVELS.charAt(levels[i]),
                    net.kyori.adventure.text.format.TextColor.fromHexString(EQ_COLOURS[i])));
        }
        return out.build();
    }

    /** The same equalizer the other way round (for the right-hand side). */
    private static Component mirrored(Component eq) {
        List<Component> bars = new java.util.ArrayList<>(eq.children());
        java.util.Collections.reverse(bars);
        return Component.text().append(bars).build();
    }

    private void hideBossBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
        equalizers.remove(player.getUniqueId());
    }

    public void remove(Player player) {
        hideBossBar(player);
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) hideBossBar(player);
        bossBars.clear();
    }
}

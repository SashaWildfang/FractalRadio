/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.stats;

import com.sasha.radio.song.Song;
import com.sasha.radio.song.TestSongs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SongStatsTest {

    private static final Logger LOGGER = Logger.getLogger("test");
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @TempDir
    Path folder;

    private SongStats stats(Instant at) {
        return new SongStats(folder.resolve("stats.json"), LOGGER, Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test
    void countsSurviveSaveAndLoad() {
        Song song = TestSongs.song("Artist - Song", 120);
        SongStats stats = stats(NOW);
        stats.played(song);
        stats.played(song);
        stats.skipped(song);
        stats.save().run();
        assertNull(stats.save(), "nothing changed, so nothing to save");

        SongStats loaded = stats(NOW);
        loaded.load();
        assertEquals(2, loaded.plays(song));
        assertEquals(1, loaded.skips(song));
        assertTrue(loaded.trending(song) > 0);
    }

    @Test
    void recentPlaysTrendAboveOldOnes() {
        Song old = TestSongs.song("Old", 120);
        Song fresh = TestSongs.song("Fresh", 120);

        SongStats earlier = stats(NOW.minus(10, ChronoUnit.DAYS));
        for (int i = 0; i < 5; i++) earlier.played(old);
        earlier.save().run();

        SongStats stats = stats(NOW);
        stats.load();
        stats.played(fresh);
        stats.played(fresh);

        assertEquals(5, stats.plays(old));
        assertTrue(stats.trending(fresh) > stats.trending(old), "two plays today beat five ten days ago");
        assertTrue(stats.isTrending(fresh));
    }

    @Test
    void skipsPullASongDown() {
        Song song = TestSongs.song("Skipped", 120);
        SongStats stats = stats(NOW);
        stats.played(song);
        stats.skipped(song);
        stats.skipped(song);
        assertEquals(0, stats.trending(song));
        assertFalse(stats.isTrending(song));
    }
}

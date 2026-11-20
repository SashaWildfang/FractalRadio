/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.gui;

import com.sasha.radio.song.Song;
import com.sasha.radio.stats.SongStats;

import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;

/** Song order in the GUI. Labels come from messages.yml (gui.sort-modes). */
public enum SortMode {
    NAME_AZ(Comparator.comparing(SortMode::title)),
    NAME_ZA(Comparator.comparing(SortMode::title).reversed()),
    ARTIST_AZ(Comparator.comparing(SortMode::artist).thenComparing(SortMode::title)),
    ARTIST_ZA(Comparator.comparing(SortMode::artist).reversed().thenComparing(SortMode::title)),
    SHORTEST(Comparator.comparingInt(Song::durationSeconds).thenComparing(SortMode::title)),
    LONGEST(Comparator.comparingInt(Song::durationSeconds).reversed().thenComparing(SortMode::title)),
    /** Most plays of all time on this server. */
    MOST_PLAYED(stats -> Comparator.comparingInt(stats::plays).reversed().thenComparing(SortMode::title)),
    /** Played the most lately, recent days counting more. */
    TRENDING(stats -> Comparator.comparingDouble(stats::trending).reversed()
            .thenComparing(Comparator.comparingInt(stats::plays).reversed())
            .thenComparing(SortMode::title));

    private final Function<SongStats, Comparator<Song>> comparator;

    SortMode(Comparator<Song> comparator) {
        this(stats -> comparator);
    }

    SortMode(Function<SongStats, Comparator<Song>> comparator) {
        this.comparator = comparator;
    }

    public Comparator<Song> comparator(SongStats stats) {
        return comparator.apply(stats);
    }

    public SortMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static SortMode parse(String name) {
        if (name != null) {
            for (SortMode mode : values()) {
                if (mode.name().equals(name)) return mode;
            }
        }
        return NAME_AZ;
    }

    private static String title(Song song) {
        return song.title().toLowerCase(Locale.ROOT);
    }

    private static String artist(Song song) {
        return song.author().toLowerCase(Locale.ROOT);
    }
}

/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.song;

/** Songs for tests in other packages (Song's constructor is package-private). */
public final class TestSongs {

    private TestSongs() {
    }

    /** A silent song of the given length in seconds, at 10 ticks per second. */
    public static Song song(String id, int seconds) {
        return new Song(id, "Test", "", 10f, new Note[seconds * 10][]);
    }
}

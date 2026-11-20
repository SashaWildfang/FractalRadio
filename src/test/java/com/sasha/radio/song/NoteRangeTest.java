/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.song;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoteRangeTest {

    private static final NoteRange EXTENDED = new NoteRange(true, false, NoteRange.DEFAULT_PATTERN);
    private static final NoteRange ALL_PACK = new NoteRange(true, true, NoteRange.DEFAULT_PATTERN);

    @Test
    void allNotesUsesThePackEvenInRange() {
        NoteRange.Resolved note = ALL_PACK.resolve("harp", 45);
        assertEquals("radio:note.harp_0", note.sound());
        assertEquals(1.0f, note.pitch(), 1e-6);
        assertEquals("radio:note.harp_1", ALL_PACK.resolve("harp", 69).sound());
    }

    @Test
    void vanillaKeysUseTheVanillaSound() {
        NoteRange.Resolved note = EXTENDED.resolve("harp", 45);
        assertEquals("minecraft:block.note_block.harp", note.sound());
        assertEquals(1.0f, note.pitch(), 1e-6);
        assertEquals(2.0f, EXTENDED.resolve("harp", 57).pitch(), 1e-6);
        assertEquals(0.5f, EXTENDED.resolve("harp", 33).pitch(), 1e-6);
    }

    @Test
    void withoutThePackNotesMoveByOctavesIntoRange() {
        NoteRange.Resolved high = NoteRange.VANILLA.resolve("bell", 69);
        assertEquals("minecraft:block.note_block.bell", high.sound());
        assertEquals(2.0f, high.pitch(), 1e-6); // key 69 moves down an octave to 57
    }

    @Test
    void outOfRangeKeysUseTheShiftedSounds() {
        // Key 69 is two octaves above 45: the +1 sound (vanilla pitched up 24 keys) at pitch 1.0.
        NoteRange.Resolved high = EXTENDED.resolve("harp", 69);
        assertEquals("radio:note.harp_1", high.sound());
        assertEquals(1.0f, high.pitch(), 1e-6);

        NoteRange.Resolved low = EXTENDED.resolve("bass", 21);
        assertEquals("radio:note.bass_-1", low.sound());
        assertEquals(1.0f, low.pitch(), 1e-6);

        NoteRange.Resolved veryLow = EXTENDED.resolve("harp", 0);
        assertEquals("radio:note.harp_-2", veryLow.sound());
        assertEquals((float) Math.pow(2, (0 + 48 - 45) / 12.0), veryLow.pitch(), 1e-6);
    }

    @Test
    void keysPastTheOutermostSoundsAreFolded() {
        NoteRange.Resolved note = EXTENDED.resolve("harp", 110);
        assertEquals("radio:note.harp_2", note.sound());
        assertEquals((float) Math.pow(2, (98 - 48 - 45) / 12.0), note.pitch(), 1e-6);
    }
}

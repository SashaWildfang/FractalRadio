/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.song;

import java.util.Locale;

/**
 * Turns an NBS key into the sound and pitch the client plays.
 *
 * <p>A vanilla note block sound only covers two octaves (NBS keys 33-57, F#3-F#5): the client clamps pitch
 * to 0.5-2.0. Without a resource pack, notes outside that range are moved by whole octaves into it, which
 * keeps them in key but can make a melody jump up or down.
 *
 * <p>With {@code extended} on, the resource pack supplies copies of each instrument shifted by two octaves
 * at a time (the same layout Note Block Studio and NoteBlockAPI use), so every note plays at its real
 * pitch from key -15 to key 104:
 * <pre>
 *   octave -2: keys -15..8    octave -1: keys 9..32    octave 0 (vanilla): keys 33..57
 *   octave  1: keys 58..80    octave  2: keys 81..104
 * </pre>
 *
 * <p>With {@code allNotes} on, notes in the normal range also use the pack's sound (octave 0), so a pack
 * can replace the instruments' sound for the radio only (e.g. softened versions) without changing real
 * note blocks.
 *
 * @param extended     whether the resource pack's shifted sounds are available
 * @param allNotes     whether in-range notes also use the pack's sound ({@code {octave}} = 0)
 * @param soundPattern sound name for pack notes; {@code {instrument}} and {@code {octave}} are filled in
 */
public record NoteRange(boolean extended, boolean allNotes, String soundPattern) {

    public static final NoteRange VANILLA = new NoteRange(false, false, "");
    public static final String DEFAULT_PATTERN = "radio:note.{instrument}_{octave}";

    static final int MIN_KEY = 33;
    static final int MAX_KEY = 57;
    private static final int CENTER_KEY = 45;
    private static final int MAX_OCTAVE = 2;

    /** A resolved note: the sound to play and its client pitch (0.5-2.0). */
    record Resolved(String sound, float pitch) {
    }

    /**
     * @param instrument the vanilla instrument name, e.g. "harp" or "iron_xylophone"
     * @param key        the NBS key including fine pitch (cents / 100)
     */
    Resolved resolve(String instrument, float key) {
        int octave = 0;
        if (extended && (key < MIN_KEY || key > MAX_KEY)) {
            octave = (int) Math.floor((key - MIN_KEY) / 24f);
            octave = Math.max(-MAX_OCTAVE, Math.min(MAX_OCTAVE, octave));
        }
        // Keep the key inside the chosen sound's two octaves (only moves notes beyond the outermost sounds).
        float low = MIN_KEY + 24 * octave;
        float high = MAX_KEY + 24 * octave;
        while (key < low) key += 12;
        while (key > high) key -= 12;

        float pitch = (float) Math.pow(2.0, (key - 24 * octave - CENTER_KEY) / 12.0);
        String sound = octave == 0 && !allNotes
                ? "minecraft:block.note_block." + instrument
                : soundPattern.replace("{instrument}", instrument).replace("{octave}", Integer.toString(octave))
                .toLowerCase(Locale.ROOT);
        return new Resolved(sound, pitch);
    }
}

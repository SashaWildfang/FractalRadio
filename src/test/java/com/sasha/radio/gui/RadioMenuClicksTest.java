/*
 * Radio - built by Sasha Wildfang for Fractal Network.
 */
package com.sasha.radio.gui;

import com.sasha.radio.song.Song;
import com.sasha.radio.song.TestSongs;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every menu item reacts to exactly the clicks its description (messages.yml hints) offers, and no others. */
class RadioMenuClicksTest {

    private static final Song SONG = TestSongs.song("Artist - Song", 120);
    private static final Set<ClickType> LEFT = EnumSet.of(ClickType.LEFT);

    private static void assertClicks(int slot, Object entry, boolean queueView, Set<ClickType> expected) {
        for (ClickType click : ClickType.values()) {
            if (!click.isMouseClick()) continue; // the menu ignores keyboard clicks before this point
            assertEquals(expected.contains(click), RadioMenu.accepts(slot, entry, queueView, click),
                    "slot " + slot + " (" + entry + ", queue view " + queueView + ") with " + click);
        }
    }

    @Test
    void leftClickOnlyButtons() {
        for (int slot : new int[]{RadioMenu.CATEGORIES, RadioMenu.FAVORITES, RadioMenu.STATION, RadioMenu.SORT,
                RadioMenu.PAGE_BACK, RadioMenu.PAGE_NEXT, RadioMenu.SHUFFLE, RadioMenu.PREVIOUS, RadioMenu.PLAY,
                RadioMenu.SKIP, RadioMenu.REPEAT, RadioMenu.BAR}) {
            assertClicks(slot, null, false, LEFT);
            assertClicks(slot, null, true, LEFT);
        }
    }

    @Test
    void searchTypesOrClears() {
        assertClicks(RadioMenu.SEARCH, null, false, EnumSet.of(ClickType.LEFT, ClickType.RIGHT));
    }

    @Test
    void nowPlayingOnlyFavorites() {
        assertClicks(RadioMenu.NOW_PLAYING, null, false, EnumSet.of(ClickType.SHIFT_LEFT));
    }

    @Test
    void upNextButtonClearsOnlyInsideTheList() {
        assertClicks(RadioMenu.QUEUE, null, false, LEFT);
        assertClicks(RadioMenu.QUEUE, null, true, EnumSet.of(ClickType.LEFT, ClickType.RIGHT));
    }

    @Test
    void volumeSteps() {
        assertClicks(RadioMenu.VOLUME, null, false,
                EnumSet.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT));
    }

    @Test
    void gridEntries() {
        int grid = 10;
        assertClicks(grid, SONG, false, EnumSet.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT));
        assertClicks(grid, 0, true, LEFT);            // a song in Up next: Left-click removes it
        assertClicks(grid, "Pop", false, LEFT);       // a genre: Left-click browses
    }
}

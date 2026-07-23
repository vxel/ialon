/**
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.delaunois.ialon.ui.IalonTheme;
import org.junit.jupiter.api.Test;

/**
 * Guards the card grid's one hard promise : the columns never reach into the popup's right button column.
 *
 * <p>They used to, because the grid sized its columns from a formula restating how a card was built
 * ({@code 26 vw + 4.8 vh} = content + border + mat + padding). When the card became a nine-patch plate,
 * whose margin scales with the screen, the formula under-counted by ~42 px a card and the second column
 * slid 120 px under the buttons. The fix inverts the dependency — the band is divided between the columns
 * and a card takes what it is given — so these assertions are about that division holding.
 *
 * @author Cedric de Launois
 */
class CardGridTest {

    /** Screen sizes the game actually runs at, plus two awkward ones. */
    private static final float[][] SCREENS = {
            {1280, 720}, {1448, 738}, {1520, 720}, {2400, 1080}, {2560, 1440}, {960, 540}, {800, 600},
    };

    @Test
    void columnsAlwaysFitTheBand() {
        for (float[] screen : SCREENS) {
            float band = band(screen[0], screen[1]);
            float vw = screen[0] / 100f;
            int columns = CardGrid.columnCount(band, vw);
            float cardWidth = CardGrid.cardWidth(band, columns);
            assertTrue(columns >= 1, "at least one column is always paved");
            assertTrue(columns * cardWidth <= band + 0.001f,
                    columns + " columns of " + cardWidth + " px overflow the " + band + " px band"
                            + " at " + screen[0] + "x" + screen[1]);
        }
    }

    @Test
    void aCardStillHasRoomForItsContentOnceThePlateTakesItsShare() {
        for (float[] screen : SCREENS) {
            float band = band(screen[0], screen[1]);
            float vh = screen[1] / 100f;
            int columns = CardGrid.columnCount(band, screen[0] / 100f);
            float cardWidth = CardGrid.cardWidth(band, columns);
            // Same subtraction the states do in createCard.
            float contentWidth = cardWidth - 2 * (0.8f * vh)
                    - IalonTheme.Frame.PLATE_GOLD.horizontalChrome(screen[1]);
            assertTrue(contentWidth > 0,
                    "the plate's chrome leaves no content width at " + screen[0] + "x" + screen[1]);
            assertTrue(contentWidth > cardWidth * 0.5f,
                    "the chrome eats more than half the card at " + screen[0] + "x" + screen[1]
                            + " (content " + contentWidth + " of " + cardWidth + ")");
        }
    }

    /**
     * A card's state is which plate it wears, so the three plates must cost the exact same chrome : were
     * one of them sliced differently, selecting a card would resize it, shift its neighbours, and put the
     * columns back on the collision course the rest of this class guards against.
     */
    @Test
    void theThreeCardPlatesCostTheSameChrome() {
        for (float[] screen : SCREENS) {
            float gold = IalonTheme.Frame.PLATE_GOLD.horizontalChrome(screen[1]);
            assertEquals(gold, IalonTheme.Frame.PLATE_BRONZE.horizontalChrome(screen[1]),
                    "the bronze plate insets its content differently at " + screen[0] + "x" + screen[1]);
            assertEquals(gold, IalonTheme.Frame.PLATE_SILVER.horizontalChrome(screen[1]),
                    "the silver plate insets its content differently at " + screen[0] + "x" + screen[1]);
            assertEquals(IalonTheme.Frame.PLATE_GOLD.minimumSize(screen[1]),
                    IalonTheme.Frame.PLATE_BRONZE.minimumSize(screen[1]),
                    "the bronze plate's patch is not the gold one's size");
            assertEquals(IalonTheme.Frame.PLATE_GOLD.minimumSize(screen[1]),
                    IalonTheme.Frame.PLATE_SILVER.minimumSize(screen[1]),
                    "the silver plate's patch is not the gold one's size");
        }
    }

    @Test
    void aBandTooNarrowForOneCardStillPavesOneColumn() {
        assertEquals(1, CardGrid.columnCount(10f, 14.48f), "never zero columns, whatever the band");
        assertEquals(1, CardGrid.columnCount(0f, 14.48f));
    }

    /**
     * The space the grid has to live in : from the right of the top-left Back button to the left of the
     * button column, mirroring {@code WorldMenuState.rebuildGrid}. The Back button's width is taken as
     * 10 vw, which is about what its plated text measures.
     */
    private static float band(float width, float height) {
        float vw = width / 100f;
        float gridLeftX = height / 24f + 10 * vw + 3 * vw;
        return (width - 30 * vw) - gridLeftX - 2 * vw;
    }
}

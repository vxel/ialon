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

package org.delaunois.ialon.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jme3.math.Vector3f;

import org.junit.jupiter.api.Test;

/**
 * Guards the two things about the nine-patch chrome that are easy to break and hard to see : that it
 * stays a constant fraction of the screen, and that no frame is ever asked to back a widget smaller
 * than its own artwork.
 *
 * <p>The second one is not a theoretical concern. A {@code TbtQuad}'s mesh is built at
 * {@code image * imageScale} and its folds simply cross over on anything smaller, which renders the
 * background inside-out — in practice the plate silently vanishes. These are pure arithmetic checks,
 * so they need no display and no jME context.
 *
 * @author Cedric de Launois
 */
class IalonThemeTest {

    /** The smallest screen the game is expected to run on (Android phones, landscape). */
    private static final float MIN_SCREEN_HEIGHT = 600f;

    @Test
    void imageScaleIsOneAtTheReferenceHeight() {
        assertEquals(1f, IalonTheme.imageScale(IalonTheme.REFERENCE_HEIGHT), 1e-6f);
    }

    @Test
    void chromeKeepsAConstantFractionOfTheScreen() {
        // The point of scaling by the screen height : a frame's corner must always be the same share of
        // the screen, whatever the resolution. That is what makes the border look identical at 720p and
        // at 4K instead of shrinking away.
        float referenceShare = IalonTheme.Frame.POPUP.minimumSize(IalonTheme.REFERENCE_HEIGHT).y
                / IalonTheme.REFERENCE_HEIGHT;
        for (float screenHeight : new float[] {600f, 720f, 1080f, 1440f, 2160f}) {
            float shareOfScreen = IalonTheme.Frame.POPUP.minimumSize(screenHeight).y / screenHeight;
            assertEquals(referenceShare, shareOfScreen, 1e-5f,
                    "chrome thickness drifted at " + screenHeight + "p");
        }
    }

    @Test
    void everyFrameFitsTheWidgetsItBacksOnTheSmallestSupportedScreen() {
        for (IalonTheme.Frame frame : IalonTheme.Frame.values()) {
            assertFits(frame);
        }
    }

    private void assertFits(IalonTheme.Frame frame) {
        Vector3f minimum = frame.minimumSize(MIN_SCREEN_HEIGHT);
        float vh = MIN_SCREEN_HEIGHT / 100f;
        // The widget each frame is used on, at its smallest (see the factories in IalonTheme and the
        // dialog / card sizes in WorldMenuState and CreationLibraryState).
        float widgetHeight;
        float widgetWidth;
        switch (frame) {
            case POPUP:
                // Full screen, except the minimap popup which is min(w, h) * 0.75.
                widgetHeight = MIN_SCREEN_HEIGHT * 0.75f;
                widgetWidth = MIN_SCREEN_HEIGHT * 0.75f;
                break;
            case PLATE_GOLD:
            case PLATE_BRONZE:
            case PLATE_SILVER:
                // A grid card, whichever metal marks its state : 17 vh of preview + 5.5 vh of caption.
                widgetHeight = 22.5f * vh;
                widgetWidth = 26f * vh;   // 26 vw, and vw >= vh on a landscape screen
                break;
            case TITLE:
                // A popup's title signboard : TITLE_HEIGHT_VH tall, and never narrower than the
                // TITLE_WIDTH_VW floor titlePlateWidth applies. Its own patch is deliberately baked a
                // little under that height, so this is the frame with the least headroom of the lot.
                widgetHeight = 10f * vh;
                widgetWidth = 30f * vh;   // 30 vw, and vw >= vh on a landscape screen
                break;
            default:
                // A text button, and the floor enforceMinimumSize applies to the text-sized ones.
                widgetHeight = 8f * vh;
                widgetWidth = 25f * vh;
                break;
        }
        assertTrue(minimum.x <= widgetWidth,
                frame + " is " + minimum.x + " px wide but backs widgets only " + widgetWidth + " px wide");
        assertTrue(minimum.y <= widgetHeight,
                frame + " is " + minimum.y + " px tall but backs widgets only " + widgetHeight + " px tall");
    }

    @Test
    void theButtonPlateIsSmallEnoughForATextSizedHeaderButton() {
        // Back / Close hug their text, so they are the tightest fit in the whole UI : their height is the
        // font size (4 vh) plus the plate's own vertical margin. enforceMinimumSize() is what saves them,
        // and this asserts the floor it applies is not itself absurd — at most a tenth of the screen.
        Vector3f minimum = IalonTheme.Frame.BUTTON.minimumSize(MIN_SCREEN_HEIGHT);
        assertTrue(minimum.y <= MIN_SCREEN_HEIGHT * 0.1f,
                "the button plate forces header buttons to " + minimum.y + " px, which is too tall");
    }
}

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

import static org.delaunois.ialon.Ialon.IALON_STYLE;

import com.jme3.asset.AssetManager;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.component.InsetsComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.event.CursorButtonEvent;
import com.simsilica.lemur.event.CursorMotionEvent;
import com.simsilica.lemur.event.DefaultCursorListener;

import org.delaunois.ialon.ui.IalonTheme;
import org.delaunois.ialon.ui.UiHelper;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared building blocks for the scrollable, tappable card grids used by both {@link WorldMenuState}
 * and {@link CreationLibraryState} : the visual card (a framed preview + caption) and the
 * drag-to-scroll / tap-to-select cursor listener. The listener is decoupled from its host through the
 * {@link ScrollableGrid} callback interface, so the same behaviour drives either grid.
 *
 * @author Cedric de Launois
 */
@Slf4j
final class CardGrid {

    private CardGrid() {
    }

    /**
     * The narrowest a card may get before the grid drops a column, in vw. Below this a world preview stops
     * being readable, so it is better to show fewer, bigger cards.
     */
    private static final float MIN_CARD_WIDTH_VW = 22f;

    /**
     * How many columns of cards fit in the space left of the popup's button column.
     *
     * <p>The cards then <b>share that band evenly</b> ({@link #cardWidth}) instead of each taking a fixed
     * width, which is what makes an overlap impossible by construction : whatever the chrome around a card
     * costs, {@code cols * cardWidth} is the band, never more. The previous scheme did the opposite — it
     * estimated a card's width from a formula restating how a card was built ({@code 26 vw + 4.8 vh}) — and
     * when the card became a nine-patch plate, whose margin scales with the screen, the estimate
     * under-counted by 42 px a card and the second column slid 120 px under the buttons.
     *
     * @param bandWidth the horizontal space between the grid's left edge and the button column
     * @param vw        one percent of the screen width, in GUI pixels
     * @return the column count, at least one
     */
    static int columnCount(float bandWidth, float vw) {
        return Math.max(1, (int) (bandWidth / (MIN_CARD_WIDTH_VW * vw)));
    }

    /**
     * The width one card gets : the band divided between the columns.
     *
     * @param bandWidth the horizontal space between the grid's left edge and the button column
     * @param columns   the column count from {@link #columnCount}
     * @return the card width in GUI pixels
     */
    static float cardWidth(float bandWidth, int columns) {
        return bandWidth / columns;
    }

    /**
     * Paves cards left-to-right then top-to-bottom. Lemur has no wrapping layout, hence the manual
     * {@code row = i / cols, col = i % cols}.
     *
     * <p>Verifies against the cards as actually built, rather than trusting the arithmetic that sized
     * them : the whole class of bug this replaces was a computed width drifting from the real one.
     *
     * @param cards     the cards, already built at {@link #cardWidth}
     * @param columns   the column count from {@link #columnCount}
     * @param bandWidth the space the grid must stay within
     * @return the grid container
     */
    static Container paveGrid(List<Container> cards, int columns, float bandWidth) {
        if (!cards.isEmpty()) {
            float measured = cards.get(0).getPreferredSize().x;
            if (measured * columns > bandWidth + 1f) {
                log.warn("Card grid overflows : {} columns of {} px measured need {} px, band is {} px",
                        columns, measured, measured * columns, bandWidth);
            }
        }
        Container grid = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        for (int i = 0; i < cards.size(); i++) {
            grid.addChild(cards.get(i), i / columns, i % columns);
        }
        return grid;
    }

    /**
     * How far a card grid may scroll : the overflow of its height beyond the visible band, plus a bottom
     * margin so the last row does not end up flush with the screen edge.
     *
     * <p>The margin is the same {@link UiHelper#screenMargin} {@link ScrollableColumn} adds at the bottom
     * of its own scroll range, which is what makes a scrolled-to-the-end grid and a scrolled-to-the-end
     * settings column leave the same breathing room under their last row.
     *
     * @param gridHeight   the grid's measured height
     * @param screenHeight the screen height in GUI pixels
     * @param gridTopBase  the grid's unscrolled top offset (negative : the popup's origin is top-left)
     * @return the scroll range in GUI pixels, zero when the grid fits
     */
    static float maxScroll(float gridHeight, float screenHeight, float gridTopBase) {
        float visibleBand = screenHeight + gridTopBase;
        return Math.max(0, gridHeight + UiHelper.screenMargin(screenHeight) - visibleBand);
    }

    /**
     * A host owning vertically scrollable content : the card grids of {@link WorldMenuState} /
     * {@link CreationLibraryState}, and the settings columns of {@link ScrollableColumn}. Implemented so
     * the shared {@link ScrollListener} can drive their scroll offset (and, for a grid, card selection).
     *
     * <p>All of them scroll a top-down offset in {@code [0, maxScroll]} applied by {@code applyScroll()},
     * so this is also where that shape is mapped onto the axis-agnostic {@link ScrollInertia.Target} — once,
     * rather than in each of the three hosts.
     */
    interface ScrollableGrid extends ScrollInertia.Target {
        float getMaxScroll();

        /** {@code true} when the grid is the visible view (not, e.g., an overlaid form). */
        boolean isGridVisible();

        float getScrollOffset();

        void setScrollOffset(float offset);

        void applyScroll();

        float getScreenHeight();

        /** Selects the tapped card (no-op if already selected). */
        void selectCard(String cardId);

        /**
         * The list's fling animator. Owned by the host and shared by all its listeners, so a press on any
         * card stops the glide launched from another one — see {@link ScrollInertia}.
         */
        ScrollInertia getInertia();

        @Override
        default float getScrollPosition() {
            return getScrollOffset();
        }

        @Override
        default void setScrollPosition(float position) {
            setScrollOffset(position);
            applyScroll();
        }

        @Override
        default float getMinScrollPosition() {
            return 0;
        }

        @Override
        default float getMaxScrollPosition() {
            return getMaxScroll();
        }

        @Override
        default boolean isScrollActive() {
            return isGridVisible() && getMaxScroll() > 0;
        }

        @Override
        default float getScrollExtent() {
            return getScreenHeight();
        }
    }

    /**
     * Builds a card : a riveted metal plate holding the preview image (row 0) and the caption (row 1).
     * Returns it for the caller to add a listener to and place in the grid.
     *
     * <p>The plate is a nine-patch, and its border margin is what pads the content inside it. That
     * replaces what used to be three nested containers — a coloured frame around a black mat around an
     * inset content box, each revealing a slice of its parent's background to fake a border.
     *
     * <p>Which plate a card wears is what marks its state — {@code PLATE_GOLD} for the selected one,
     * {@code PLATE_BRONZE} / {@code PLATE_SILVER} for the rest ; the three are the same engraving in
     * three metals, so nothing else about the card changes with its state. That replaces a colour
     * multiply over the one gold plate, whose "current" and "selected" tints happened to be the very
     * same white and so never told the two states apart.
     *
     * @param assetManager   the asset manager
     * @param screenHeight   the screen height in GUI pixels (the plate's chrome is scaled from it)
     * @param plateFrame     which plate the card wears : its state (see {@link IalonTheme.Frame})
     * @param halfGap        half the gap between adjacent cards
     * @param contentW       the preview and caption width
     * @param previewH       the preview height
     * @param captionH       the caption height
     * @param previewTex     the preview image, or {@code null} for none
     * @param captionText    the caption
     * @param captionFontSize the caption font size
     * @return the card
     */
    static Container buildCard(AssetManager assetManager, float screenHeight, IalonTheme.Frame plateFrame,
                               float halfGap, float contentW, float previewH, float captionH,
                               Texture previewTex, String captionText, float captionFontSize) {
        Container card = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        card.setBackground(IalonTheme.frame(assetManager, plateFrame, screenHeight));
        card.setInsetsComponent(new InsetsComponent(halfGap, halfGap, halfGap, halfGap));

        Panel preview = new Panel();
        preview.setPreferredSize(new Vector3f(contentW, previewH, 0));
        if (previewTex != null) {
            preview.setBackground(new QuadBackgroundComponent(previewTex));
        }
        card.addChild(preview, 0, 0);

        Label caption = new Label(captionText, IALON_STYLE);
        caption.setFontSize(captionFontSize);
        caption.setColor(IalonTheme.PARCHMENT);
        caption.setPreferredSize(new Vector3f(contentW, captionH, 0));
        card.addChild(caption, 1, 0);

        return card;
    }

    /**
     * Drag-to-scroll + tap-to-select on a card (or, with a {@code null} cardId, on the gaps between
     * cards : scroll only). Uses Lemur's cursor events (like DragHandler) rather than the legacy
     * MouseListener, whose move events are not reliably delivered during a touch drag.
     *
     * <p>It only reports the gesture — press, drag deltas, release — to the host's {@link ScrollInertia},
     * which is what moves the list, springs its elastic ends and flings it. A near-stationary press counts as
     * a tap and selects the card instead.
     */
    static class ScrollListener extends DefaultCursorListener {

        private final ScrollableGrid grid;
        private final String cardId; // null = background catcher (scroll only, no selection)
        private boolean capturing;
        private float lastY;
        private float dragAccum;

        ScrollListener(ScrollableGrid grid, String cardId) {
            this.grid = grid;
            this.cardId = cardId;
        }

        @Override
        public void cursorButtonEvent(CursorButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
            if (event.isPressed()) {
                capturing = true;
                lastY = event.getY();
                dragAccum = 0;
                grid.getInertia().press();
            } else {
                if (capturing && cardId != null && dragAccum < grid.getScreenHeight() * 0.02f) {
                    grid.selectCard(cardId);
                }
                if (capturing) {
                    grid.getInertia().release();
                }
                capturing = false;
            }
        }

        @Override
        public void cursorMoved(CursorMotionEvent event, Spatial target, Spatial capture) {
            // No scroll when there is no overflow, or when the grid isn't the current view (create form).
            if (grid.getMaxScroll() <= 0 || !grid.isGridVisible()) {
                return;
            }
            // Mouse wheel : one notch scrolls a fraction of the screen (wheel down reveals lower rows).
            int wheel = event.getScrollDelta();
            if (wheel != 0) {
                // A notch takes over from whatever was in flight (and settles any elastic stretch) rather
                // than fighting it frame by frame. A wheel has no gesture to stretch against, so it clamps.
                grid.getInertia().stop();
                float step = grid.getScreenHeight() * 0.001f;
                grid.setScrollOffset(Math.max(0, Math.min(grid.getScrollOffset() - wheel * step, grid.getMaxScroll())));
                grid.applyScroll();
                event.setConsumed();
                return;
            }
            if (!capturing) {
                return;
            }
            // Drag : content follows the finger (moving up, y increases, reveals lower rows). The inertia is
            // what moves it — it owns the position, so that the elastic ends and the fling that follows are
            // the same code for every list.
            float y = event.getY();
            float dy = y - lastY;
            lastY = y;
            dragAccum += Math.abs(dy);
            grid.getInertia().drag(dy);
        }
    }
}

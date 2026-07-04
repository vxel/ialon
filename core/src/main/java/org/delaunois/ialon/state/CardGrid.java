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

import com.jme3.math.ColorRGBA;
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

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.ui.UiHelper;

/**
 * Shared building blocks for the scrollable, tappable card grids used by both {@link WorldMenuState}
 * and {@link CreationLibraryState} : the visual card (a framed preview + caption) and the
 * drag-to-scroll / tap-to-select cursor listener. The listener is decoupled from its host through the
 * {@link ScrollableGrid} callback interface, so the same behaviour drives either grid.
 *
 * @author Cedric de Launois
 */
final class CardGrid {

    private CardGrid() {
    }

    /**
     * A host owning a vertically scrollable grid of cards. Implemented by the app states that display
     * such a grid so the shared {@link ScrollListener} can drive their scroll offset and card selection.
     */
    interface ScrollableGrid {
        float getMaxScroll();

        /** {@code true} when the grid is the visible view (not, e.g., an overlaid form). */
        boolean isGridVisible();

        float getScrollOffset();

        void setScrollOffset(float offset);

        void applyScroll();

        float getScreenHeight();

        /** Selects the tapped card (no-op if already selected). */
        void selectCard(String cardId);
    }

    /**
     * Builds a card : a coloured frame around a black mat around a content box holding the preview image
     * (row 0) and the caption (row 1). The inset on each child reveals its parent's background, so the
     * frame colour shows as a border and the mat shows as an inner border. Returns the frame (the caller
     * attaches its listener and adds it to the grid).
     */
    static Container buildCard(IalonConfig config, ColorRGBA frameColor, float border, float pad,
                               float halfGap, float contentW, float previewH, float captionH,
                               Texture previewTex, String captionText, float captionFontSize) {
        Container frame = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        UiHelper.addBackground(frame, frameColor, config);
        frame.setInsetsComponent(new InsetsComponent(halfGap, halfGap, halfGap, halfGap));

        Container mat = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        UiHelper.addBackground(mat, ColorRGBA.Black, config);
        mat.setInsetsComponent(new InsetsComponent(border, border, border, border));

        Container contentBox = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        contentBox.setInsetsComponent(new InsetsComponent(pad, pad, pad, pad));

        Panel preview = new Panel();
        preview.setPreferredSize(new Vector3f(contentW, previewH, 0));
        if (previewTex != null) {
            preview.setBackground(new QuadBackgroundComponent(previewTex));
        }
        contentBox.addChild(preview, 0, 0);

        Label caption = new Label(captionText, IALON_STYLE);
        caption.setFontSize(captionFontSize);
        caption.setColor(ColorRGBA.White);
        caption.setPreferredSize(new Vector3f(contentW, captionH, 0));
        contentBox.addChild(caption, 1, 0);

        mat.addChild(contentBox);
        frame.addChild(mat);
        return frame;
    }

    /**
     * Drag-to-scroll + tap-to-select on a card (or, with a {@code null} cardId, on the gaps between
     * cards : scroll only). Uses Lemur's cursor events (like DragHandler) rather than the legacy
     * MouseListener, whose move events are not reliably delivered during a touch drag. A press captures
     * the pointer ; vertical cursor motion scrolls the grid ; on release, a near-stationary press counts
     * as a tap and selects.
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
            } else {
                if (capturing && cardId != null && dragAccum < grid.getScreenHeight() * 0.02f) {
                    grid.selectCard(cardId);
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
                float step = grid.getScreenHeight() * 0.001f;
                grid.setScrollOffset(Math.max(0, Math.min(grid.getScrollOffset() - wheel * step, grid.getMaxScroll())));
                grid.applyScroll();
                event.setConsumed();
                return;
            }
            if (!capturing) {
                return;
            }
            // Drag : content follows the finger (moving up, y increases, reveals lower rows).
            float y = event.getY();
            float dy = y - lastY;
            lastY = y;
            dragAccum += Math.abs(dy);
            grid.setScrollOffset(Math.max(0, Math.min(grid.getScrollOffset() + dy, grid.getMaxScroll())));
            grid.applyScroll();
        }
    }
}

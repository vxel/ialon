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

import com.jme3.input.event.MouseButtonEvent;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.event.CursorEventControl;
import com.simsilica.lemur.event.DefaultMouseListener;

import org.delaunois.ialon.ui.IalonTheme;
import org.delaunois.ialon.ui.UiHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The form shown inside a full-screen popup : a fixed top margin clearing the popup's title signboard,
 * then a single column of rows (settings sliders, toggles, buttons...), centred horizontally and
 * <b>scrollable vertically</b>.
 *
 * <p>Shared by {@link SettingsState} (global settings) and {@link WorldMenuState} (the "New world" form)
 * so both look and behave the same, in particular <b>after a resize</b> : the rows keep the pixel size
 * they were built at, so shrinking the window pushes the last ones off the bottom edge. Without
 * scrolling they are simply unreachable — which is the whole reason this exists.
 *
 * <p>Two implementation details are load-bearing :
 *
 * <ul>
 * <li>The column hangs under a plain {@link Node} rather than being added to the popup's own layout.
 *     That is what lets its Y be driven by the scroll offset — and it is also the only way it ever gets
 *     sized : a Container parented straight to another Container counts as a laid-out child, so
 *     {@code GuiControl.revalidate()} bails out and nothing sizes it (it stays at zero, and a
 *     zero-sized {@code Slider} throws "Size cannot be negative").</li>
 * <li>Rows scrolled past the top or bottom edge fall outside the GUI camera and are clipped by the
 *     window, so no explicit clipping is needed — the same trick as the worlds grid.</li>
 * </ul>
 *
 * @author Cedric de Launois
 */
class ScrollableColumn implements CardGrid.ScrollableGrid {

    private final Node popup;
    private final Camera cam;
    private final Node view;
    private final Container content;

    private final ScrollInertia inertia = new ScrollInertia(this);

    private int nextRow;
    private float leftX;
    private float scrollOffset;
    private float maxScroll;

    /**
     * Builds the column and wires its scrolling. The column is not attached yet : call {@link #attach()}
     * (once, for a permanent form) or on each view switch.
     *
     * @param popup the full-screen popup panel the column lives in
     * @param cam   the GUI camera, for the screen size
     */
    ScrollableColumn(Node popup, Camera cam) {
        this.popup = popup;
        this.cam = cam;
        this.content = new Container(
                new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        // Consume clicks on the column's own gaps so they don't reach the game behind the popup.
        content.addMouseListener(new IgnoreMouseClickListener());
        // Drags and wheel notches over the rows scroll the column : a label or a spacer has no listener
        // of its own, so Lemur walks up to this container (PickEventSession.findHitTarget), while the
        // sliders, deeper in the hierarchy, keep catching their own drags.
        CursorEventControl.addListenersToSpatial(content, new CardGrid.ScrollListener(this, null));
        // The popup spans the whole screen and sits behind the column, so it catches drags and wheel
        // notches in the margins on either side. Listeners accumulate on a spatial, so this coexists with
        // whatever else already scrolls on the popup (the worlds grid, in WorldMenuState).
        CursorEventControl.addListenersToSpatial(popup, new CardGrid.ScrollListener(this, null));

        this.view = new Node("scrollableColumn");
        this.view.attachChild(content);
    }

    /** The container holding the rows, for callers that add to it directly (e.g. a widget's own row). */
    Container getContent() {
        return content;
    }

    /** Shows the column in its popup, scrolled back to the top. */
    void attach() {
        if (view.getParent() == null) {
            popup.attachChild(view);
        }
        inertia.stop();
        scrollOffset = 0;
        layout();
    }

    /** Hides the column (used by popups that swap between several views). */
    void detach() {
        inertia.stop();
        view.removeFromParent();
    }

    boolean isAttached() {
        return view.getParent() != null;
    }

    /** Empties the column, so a form can be rebuilt from scratch. */
    void clear() {
        inertia.stop();
        List<Spatial> children = new ArrayList<>(content.getChildren());
        children.forEach(content::detachChild);
        nextRow = 0;
        scrollOffset = 0;
    }

    /**
     * Reserves the room the popup's title takes, at the top of the column.
     *
     * <p>The column does not draw the title itself : it belongs to the popup's chrome, which is laid OVER
     * the popup (see {@link IalonTheme#applyChromeOver}). So this is not merely a cosmetic gap — a first
     * row placed any higher would be painted over by the signboard. The card grids reserve the same
     * offset, which is what lines all the popups up.
     */
    void addHeaderSpace() {
        addSpacer(IalonTheme.contentTopOffset(cam.getHeight()));
    }

    /** Appends a row at the bottom of the column. */
    void addRow(Panel row) {
        content.addChild(row, nextRow++, 0);
    }

    /** Appends an empty row of the given height, as a vertical gap. */
    void addSpacer(float height) {
        Panel spacer = new Panel();
        spacer.setBackground(null);
        spacer.setPreferredSize(new Vector3f(1, height, 0));
        addRow(spacer);
    }

    /** Re-centres and re-measures for the current screen size. */
    void layout() {
        layout(cam.getWidth(), cam.getHeight());
    }

    /**
     * Centres the column horizontally and recomputes how far it may scroll : the overflow of its height
     * beyond the screen, plus a bottom margin so the last row does not end up flush with the screen edge.
     * When everything fits ({@code maxScroll == 0}) the column stays put and scrolling is a no-op.
     *
     * @param width  the screen width in GUI pixels
     * @param height the screen height in GUI pixels
     */
    void layout(float width, float height) {
        Vector3f size = content.getPreferredSize();
        leftX = Math.max(0, (width - size.x) / 2f);
        maxScroll = Math.max(0, size.y + UiHelper.screenMargin(height) - height);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        applyScroll();
    }

    /** Slides the column vertically by the current scroll offset. */
    @Override
    public void applyScroll() {
        // z = 2 : above the popup's veil, below the corner buttons (z = 10) and the chrome.
        content.setLocalTranslation(leftX, scrollOffset, 2);
    }

    @Override
    public float getMaxScroll() {
        return maxScroll;
    }

    /** The column only scrolls while it is the view on screen (a popup may show another one instead). */
    @Override
    public boolean isGridVisible() {
        return isAttached();
    }

    @Override
    public float getScrollOffset() {
        return scrollOffset;
    }

    @Override
    public void setScrollOffset(float offset) {
        this.scrollOffset = offset;
    }

    @Override
    public float getScreenHeight() {
        return cam.getHeight();
    }

    /** Unreachable : both scroll listeners are built with a {@code null} card id (scroll only). */
    @Override
    public void selectCard(String cardId) {
        // no selectable card in a settings column
    }

    @Override
    public ScrollInertia getInertia() {
        return inertia;
    }

    /** Consumes clicks without closing the popup : closing is explicit, via the Close button. */
    private static class IgnoreMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
        }
    }
}

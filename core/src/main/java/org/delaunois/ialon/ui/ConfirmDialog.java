/**
 * Ialon, a block construction game
 * Copyright (C) 2022 Cédric de Launois
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.delaunois.ialon.ui;

import static org.delaunois.ialon.Ialon.IALON_STYLE;

import com.jme3.app.SimpleApplication;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.event.DefaultMouseListener;

import org.delaunois.ialon.IalonConfig;

/**
 * Building blocks for a small modal dialog laid over whatever is already on screen : a full-screen
 * click-swallowing veil, a centred box, and its title / buttons.
 *
 * <p>Deliberately a set of pieces rather than one {@code show(title, onConfirm)} call : the dialogs
 * that use it differ in their body (a plain confirmation, a checkbox grid), and only the veil, the box
 * and the attach-then-size dance are actually common. Those three are exactly the parts that are easy
 * to get subtly wrong — see {@link #show}.</p>
 *
 * @author Cedric de Launois
 */
public final class ConfirmDialog {

    private ConfirmDialog() {
        // Static helpers only.
    }

    /**
     * A full-screen invisible container, swallowing clicks so whatever is below stays inert while
     * showing through around the dialog.
     *
     * <p>Positioned at the origin of its parent, which is what {@link #show} expects : a popup panel
     * already anchored to the top-left of the screen. Use {@link #showOverScreen} instead when there is
     * no such parent.</p>
     */
    public static Container createModalOverlay(IalonConfig config) {
        Container overlay = new Container(IALON_STYLE);
        overlay.setLocalTranslation(0, 0, 100);
        IalonTheme.applyClearVeil(overlay, config);
        overlay.addMouseListener(new IgnoreMouseClickListener());
        return overlay;
    }

    /** The dialog box itself : a vertical stack (title, then body / button row). */
    public static Container createDialogBox(SimpleApplication app) {
        return IalonTheme.dialogBox(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None),
                app.getAssetManager(), app.getCamera().getHeight());
    }

    public static Label title(SimpleApplication app, String text) {
        return IalonTheme.dialogTitle(text, app.getCamera().getWidth(), app.getCamera().getHeight());
    }

    public static Button button(SimpleApplication app, String text) {
        return IalonTheme.dialogButton(text, app.getAssetManager(),
                app.getCamera().getWidth(), app.getCamera().getHeight());
    }

    /**
     * Attaches the dialog to the overlay, the overlay to {@code parent}, and only then forces the
     * overlay to full-screen size.
     *
     * <p>The order matters : sizing before the dialog is in place lets the layout shrink the overlay to
     * its content, which jams the dialog top-left and leaves the veil spanning only the box — so clicks
     * outside it reach the UI the veil is meant to keep inert.</p>
     */
    public static void show(SimpleApplication app, Node parent, Container overlay, Container dialog) {
        overlay.addChild(dialog);
        parent.attachChild(overlay);
        Vector3f screen = new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0);
        overlay.setPreferredSize(screen);
        overlay.setSize(screen);
    }

    /**
     * Same as {@link #show}, for a dialog laid straight over the game rather than inside an existing
     * popup : it attaches to the GUI node and anchors the overlay itself.
     *
     * <p>The anchoring is the whole point. A Lemur container is positioned by its <b>top-left</b>
     * corner, while the GUI node's origin is the <b>bottom</b> left of the screen — so the (0, 0) that
     * is correct inside a popup panel drops a screen-tall overlay entirely below the viewport, taking
     * the dialog with it. The screen height is the Y that puts it back.</p>
     */
    public static void showOverScreen(SimpleApplication app, Container overlay, Container dialog) {
        overlay.setLocalTranslation(0, app.getCamera().getHeight(), 100);
        show(app, app.getGuiNode(), overlay, dialog);
    }

    private static class IgnoreMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
        }
    }
}

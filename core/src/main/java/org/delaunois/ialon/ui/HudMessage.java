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

import static org.delaunois.ialon.Ialon.IALON_STYLE;

import com.jme3.app.SimpleApplication;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Label;

/**
 * The one-line status message the editing modes write over the game : "Select the height", "Chunk reset",
 * and so on. A centred label just under the top row of control buttons, either shown until replaced or
 * for a few seconds.
 *
 * <p>Not an AppState nor a widget : the modes that use it are already AppStates, and what they share is
 * only this label, its placement and the countdown that takes it away — three things easy to get subtly
 * different from one mode to the next (and they were).</p>
 *
 * @author Cedric de Launois
 */
public class HudMessage {

    private final SimpleApplication app;
    private final Label label;

    // Seconds left before the message goes ; 0 = nothing showing, negative = shown until replaced.
    private float timeLeft;

    public HudMessage(SimpleApplication app) {
        this.app = app;
        this.label = new Label("", IALON_STYLE);
        this.label.setColor(ColorRGBA.White);
        this.label.setTextHAlignment(HAlignment.Center);
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());
    }

    /** Shows a message until {@link #hide()} or the next one replaces it. */
    public void show(String text) {
        show(text, 0f);
    }

    /**
     * Shows a message.
     *
     * @param text    what to say
     * @param seconds how long it stays up ; {@code 0} keeps it until hidden or replaced
     */
    public void show(String text, float seconds) {
        label.setText(text);
        timeLeft = seconds > 0 ? seconds : -1f;
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());
        if (label.getParent() == null) {
            app.getGuiNode().attachChild(label);
        }
    }

    /** Takes the message off screen right away. */
    public void hide() {
        timeLeft = 0f;
        label.removeFromParent();
    }

    /** Counts a timed message down. Call from the owner's {@code update}. */
    public void update(float tpf) {
        if (timeLeft > 0) {
            timeLeft -= tpf;
            if (timeLeft <= 0) {
                hide();
            }
        }
    }

    /**
     * Centres the label horizontally, just below the top row of buttons (the settings gear / fly / action),
     * so it never overlaps them. Call on a resolution change.
     */
    public void layout(int width, int height) {
        float vh = height / 100f;
        float topButtonsBottom = height - UiHelper.screenMargin(height) - height / 12f;
        label.setFontSize(4 * vh);
        Vector3f size = label.getPreferredSize();
        label.setLocalTranslation((width - size.x) / 2f, topButtonsBottom - 2 * vh, 10);
    }
}

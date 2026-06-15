/*
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

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.VAlignment;

import java.util.function.Consumer;

import static org.delaunois.ialon.Ialon.IALON_STYLE;

/**
 * A settings row with a title and an "On"/"Off" toggle button, the boolean counterpart of
 * {@link SettingsValue}. A full-width button (rather than a checkbox) makes the whole control an
 * obvious click target whatever its state. The change callback is invoked on each user click.
 *
 * @author Cedric de Launois
 */
public class SettingsToggle {

    private final String title;
    private final Camera cam;
    private final Consumer<Boolean> onChange;
    private final String onText;
    private final String offText;
    private boolean checked;
    private Button button;

    public SettingsToggle(String title, Camera cam, boolean value, Consumer<Boolean> onChange) {
        this(title, cam, value, onChange, "On", "Off");
    }

    public SettingsToggle(String title, Camera cam, boolean value, Consumer<Boolean> onChange,
                          String onText, String offText) {
        this.title = title;
        this.cam = cam;
        this.checked = value;
        this.onChange = onChange;
        this.onText = onText;
        this.offText = offText;
    }

    public void addToContainer(Container container, int position) {
        float vw = cam.getWidth() / 100f;
        float vh = cam.getHeight() / 100f;

        Label titleLabel = new Label(title);
        titleLabel.setFontSize(4 * vh);
        titleLabel.setPreferredSize(new Vector3f(20 * vw, 8 * vh, 0));
        titleLabel.setTextVAlignment(VAlignment.Center);
        container.addChild(titleLabel, position, 0);

        button = new Button(label(checked), IALON_STYLE);
        button.setFontSize(4 * vh);
        button.setPreferredSize(new Vector3f(50 * vw, 8 * vh, 0));
        // Same horizontal inset as the sliders above (see SettingsValue) so the controls line up.
        button.setInsets(new Insets3f(0, vw, 0, vw));
        button.setTextHAlignment(HAlignment.Left);
        button.setTextVAlignment(VAlignment.Center);
        button.addClickCommands(source -> toggle());
        container.addChild(button, position, 1);
    }

    private void toggle() {
        checked = !checked;
        refreshText();
        onChange.accept(checked);
    }

    private void refreshText() {
        if (button != null) {
            button.setText(label(checked));
        }
    }

    private String label(boolean value) {
        return value ? onText : offText;
    }

    public void update() {
        // Button-driven : nothing to poll. Kept for symmetry with SettingsValue.
    }

    public boolean getValue() {
        return checked;
    }

    /**
     * Programmatically changes the toggle state (e.g. to enforce a dependency between two settings),
     * updating the button label. Does not fire the change callback : the caller drives the dependent
     * state itself.
     */
    public void setValue(boolean value) {
        checked = value;
        refreshText();
    }

}

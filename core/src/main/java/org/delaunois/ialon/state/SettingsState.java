/*
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

package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.component.DynamicInsetsComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.ui.UiHelper;
import org.delaunois.ialon.ui.UiHelper.IconButton;

import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.Ialon.IALON_STYLE;

@Slf4j
public class SettingsState extends BaseAppState implements ActionListener {

    private static final String ACTION_SWITCH_MOUSELOCK = "switch-mouselock";
    private static final float SCREEN_MARGIN = 30;
    private static final float SPACING = 10;

    private SimpleApplication app;
    private IconButton buttonSettings;
    private int buttonSize;
    private boolean isMouseLocked = false;
    private Node popup;

    private final IalonConfig config;
    private SettingsValue gridSize;
    private SettingsValue ambientIntensity;
    private SettingsValue sunIntensity;

    public SettingsState(IalonConfig config) {
        this.config = config;
    }

    @Override
    public void initialize(Application app) {
        this.app = (SimpleApplication) app;
        buttonSize = app.getCamera().getHeight() / 12;
        buttonSettings = createSettingsButton();
        popup = createPopup();

        if (!app.getInputManager().hasMapping(ACTION_SWITCH_MOUSELOCK)) {
            app.getInputManager().addMapping(ACTION_SWITCH_MOUSELOCK, new KeyTrigger(KeyInput.KEY_BACK));
        }
        app.getInputManager().addListener(this, ACTION_SWITCH_MOUSELOCK);
    }

    private IconButton createSettingsButton() {
        IconButton iconButton = UiHelper.createTextureButton(config,
                "gear.png",
                buttonSize,
                app.getCamera().getWidth() / 2f + buttonSize + SPACING,
                app.getCamera().getHeight() - SCREEN_MARGIN
        );
        iconButton.background.addMouseListener(new TogglePopupMouseClickListener());
        return iconButton;
    }

    private Node createPopup() {
        Container settingsPopup = new Container(IALON_STYLE);
        settingsPopup.setLocalTranslation(0, app.getCamera().getHeight(), 100);
        settingsPopup.setName("settingsPopup");
        settingsPopup.setPreferredSize(new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0));
        UiHelper.addBackground(settingsPopup, new ColorRGBA(0f, 0f, 0f, 0.8f));
        settingsPopup.addMouseListener(new TogglePopupMouseClickListener());

        Container container = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        container.setInsetsComponent(new DynamicInsetsComponent(10, 50, 50, 50));

        gridSize = new SettingsValue("Render distance", app.getCamera(),
                config.getGridRadiusMin(), config.getGridRadiusMax(), config.getGridRadius(),
                v -> String.valueOf(v.intValue())
        );

        ambientIntensity = new SettingsValue("Ambient Light", app.getCamera(),
                0.0f, 2.0f, config.getAmbiantIntensity(),
                v -> {
                    config.setAmbiantIntensity((float)ambientIntensity.getValue());
                    return String.format(Locale.ENGLISH, "%.2f", v);
                }
        );

        sunIntensity = new SettingsValue("Sun Light", app.getCamera(),
                0.0f, 2.0f, config.getSunIntensity(),
                v -> {
                    config.setSunIntensity((float)sunIntensity.getValue());
                    return String.format(Locale.ENGLISH, "%.2f", v);
                }
        );

        gridSize.addToContainer(container, 0);
        ambientIntensity.addToContainer(container, 1);
        sunIntensity.addToContainer(container, 2);

        settingsPopup.addChild(container);
        return settingsPopup;
    }

    public void togglePopup() {
        if (popup.getParent() == null) {
            showPopup();
        } else {
            hidePopup();
        }
    }

    public void showPopup() {
        if (popup.getParent() == null) {
            app.getGuiNode().attachChild(popup);
            app.getStateManager().getState(PlayerState.class).setTouchEnabled(false);
        }
    }

    public void hidePopup() {
        if (popup.getParent() != null) {
            popup.removeFromParent();
            app.getStateManager().getState(PlayerState.class).setTouchEnabled(true);
        }
        saveConfig();
    }

    public void saveConfig() {
        int radius = (int) gridSize.getValue();
        config.setGridRadius(radius);
        int size = radius * 2 + 1;
        BlocksConfig.getInstance().setGrid(new Vec3i(size, config.getGridHeight() * 2 + 1, size));
        if (app != null) {
            app.getStateManager().getState(ChunkPagerState.class).getChunkPager().updateGridSize();
        }

        config.setAmbiantIntensity((float)ambientIntensity.getValue());
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        if (buttonSettings.background.getParent() == null) {
            app.getGuiNode().attachChild(buttonSettings.background);
            app.getGuiNode().attachChild(buttonSettings.icon);
        }
    }

    @Override
    protected void onDisable() {
        if (buttonSettings.background.getParent() != null) {
            app.getGuiNode().detachChild(buttonSettings.background);
            app.getGuiNode().detachChild(buttonSettings.icon);
        }
    }

    public void resize() {
        log.info("Resizing {}", this.getClass().getSimpleName());
        buttonSettings.background.setLocalTranslation(app.getCamera().getWidth() / 2f + buttonSize + SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
        buttonSettings.icon.setLocalTranslation(app.getCamera().getWidth() / 2f + buttonSize + SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_SWITCH_MOUSELOCK.equals(name) && isPressed) {
            setEnabled(isMouseLocked);
            isMouseLocked = !isMouseLocked;
        }
    }

    @Override
    public void update(float tpf) {
        gridSize.update();
        ambientIntensity.update();
        sunIntensity.update();
    }

    private class TogglePopupMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
            if (event.isPressed()) {
                togglePopup();
            }
        }
    }

}

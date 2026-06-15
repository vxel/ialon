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

import static org.delaunois.ialon.Ialon.IALON_STYLE;

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
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.component.DynamicInsetsComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.ui.UiHelper;
import org.delaunois.ialon.ui.UiHelper.IconButton;

import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SettingsState extends BaseAppState implements ActionListener, Resizable {

    private static final String ACTION_SWITCH_MOUSELOCK = "switch-mouselock";
    private static final float SPACING = 10;

    private SimpleApplication app;
    private IconButton buttonSettings;
    private int buttonSize;
    private boolean isMouseLocked = false;
    private Node popup;

    private final IalonConfig config;
    private static final int FAR_TREE_MAX_DISTANCE = 1000;

    private SettingsValue gridSize;
    private SettingsValue ambientIntensity;
    private SettingsValue sunIntensity;
    private SettingsValue farTreeDistance;
    private SettingsToggle farTerrain;
    private SettingsToggle showFps;
    private SettingsToggle showPosition;
    private SettingsToggle maxFramerate;

    public SettingsState(IalonConfig config) {
        this.config = config;
    }

    @Override
    public void initialize(Application app) {
        this.app = (SimpleApplication) app;
        buttonSize = app.getCamera().getHeight() / 12;
        buttonSettings = createSettingsButton();
        popup = createPopup();
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());

        if (!app.getInputManager().hasMapping(ACTION_SWITCH_MOUSELOCK)) {
            app.getInputManager().addMapping(ACTION_SWITCH_MOUSELOCK, new KeyTrigger(KeyInput.KEY_BACK));
        }
        app.getInputManager().addListener(this, ACTION_SWITCH_MOUSELOCK);

        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).register(this);
        }
    }

    private IconButton createSettingsButton() {
        IconButton iconButton = UiHelper.createTextureButton(config, "gear.png", buttonSize, 0, 0);
        iconButton.background.addMouseListener(new TogglePopupMouseClickListener());
        return iconButton;
    }

    private Node createPopup() {
        Container settingsPopup = new Container(IALON_STYLE);
        settingsPopup.setLocalTranslation(0, app.getCamera().getHeight(), 100);
        settingsPopup.setName("settingsPopup");
        settingsPopup.setPreferredSize(new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0));
        UiHelper.addBackground(settingsPopup, new ColorRGBA(0f, 0f, 0f, 0.8f));
        // Consume background clicks (so they don't reach the game) but do NOT close the popup :
        // closing is done only via the Close button.
        settingsPopup.addMouseListener(new IgnoreMouseClickListener());

        float vw = app.getCamera().getWidth() / 100f;
        float vh = app.getCamera().getHeight() / 100f;

        // The whole block (settings rows + Close button) is centered on screen by this wrapper.
        Container content = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        content.setInsetsComponent(new DynamicInsetsComponent(10, 50, 50, 50));
        content.addMouseListener(new IgnoreMouseClickListener());

        Container container = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);

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

        farTerrain = new SettingsToggle("Far terrain", app.getCamera(),
                config.isFarTerrain(), this::setFarTerrainEnabled);

        // Far-trees distance slider : 0 disables the far trees entirely ; any positive value is the world
        // radius of the billboard ring (all trees within it are shown, no count limit). Applied on close,
        // like the render distance. The label reads "Off" at 0.
        farTreeDistance = new SettingsValue("Far trees", app.getCamera(),
                0, FAR_TREE_MAX_DISTANCE, config.isFarTree() ? config.getFarTreeDistance() : 0,
                v -> v.intValue() == 0 ? "Off" : String.valueOf(v.intValue()));

        showFps = new SettingsToggle("Show FPS", app.getCamera(),
                config.isShowFps(), this::setShowFpsEnabled);

        showPosition = new SettingsToggle("Show position", app.getCamera(),
                config.isShowPosition(), this::setShowPositionEnabled);

        maxFramerate = new SettingsToggle("Max FPS", app.getCamera(),
                config.getMaxFramerate() >= 120, this::setHighFramerate, "120", "60");

        gridSize.addToContainer(container, 0);
        ambientIntensity.addToContainer(container, 1);
        sunIntensity.addToContainer(container, 2);
        farTerrain.addToContainer(container, 3);
        farTreeDistance.addToContainer(container, 4);
        showFps.addToContainer(container, 5);
        showPosition.addToContainer(container, 6);
        maxFramerate.addToContainer(container, 7);

        content.addChild(container, 0, 0);

        // Empty spacer row : vertical margin between the settings and the Close button. The default
        // Lemur panel background is cleared so it stays invisible.
        Panel spacer = new Panel();
        spacer.setBackground(null);
        spacer.setPreferredSize(new Vector3f(1, 6 * vh, 0));
        content.addChild(spacer, 1, 0);

        // Close button : its cell spans the full settings width (the widest column 0 child), and the
        // DynamicInsetsComponent centers the button within that cell, i.e. horizontally on screen.
        Button close = new Button("Close", IALON_STYLE);
        close.setFontSize(4 * vh);
        close.setPreferredSize(new Vector3f(25 * vw, 8 * vh, 0));
        close.setTextHAlignment(HAlignment.Center);
        close.setTextVAlignment(VAlignment.Center);
        close.setInsetsComponent(new DynamicInsetsComponent(0.5f, 0.5f, 0.5f, 0.5f));
        close.addClickCommands(source -> hidePopup());
        content.addChild(close, 2, 0);

        settingsPopup.addChild(content);
        return settingsPopup;
    }

    /**
     * Enables or disables the distant-horizon rendering ({@link FarTerrainState}), attaching it on demand.
     * Far trees cannot exist without the far terrain to stand on, so disabling it also turns them off
     * (the far-trees slider is reset to 0).
     */
    private void setFarTerrainEnabled(boolean enabled) {
        config.setFarTerrain(enabled);
        FarTerrainState state = app.getStateManager().getState(FarTerrainState.class);
        if (state == null) {
            if (enabled) {
                app.getStateManager().attach(IalonInitializer.setupFarTerrain(config));
            }
        } else {
            state.setEnabled(enabled);
        }
        if (!enabled && config.isFarTree()) {
            farTreeDistance.setValue(0);
            disableFarTrees();
        }
    }

    /**
     * Applies the far-trees distance slider value (called on popup close). 0 disables the far trees ; any
     * positive value is the world radius of the billboard ring -- all trees within it are shown (no count
     * limit, {@code farTreeMaxCount = 0}). A positive radius also turns the far terrain on (trees need
     * their ground).
     */
    private void applyFarTrees(float distance) {
        if (distance <= 0) {
            disableFarTrees();
            return;
        }
        config.setFarTree(true);
        config.setFarTreeDistance(distance);
        config.setFarTreeMaxCount(0); // no cap : render every tree within the radius
        if (!config.isFarTerrain()) {
            farTerrain.setValue(true);
            setFarTerrainEnabled(true);
        }
        FarTreeState state = app.getStateManager().getState(FarTreeState.class);
        if (state == null) {
            app.getStateManager().attach(IalonInitializer.setupFarTree(config));
        } else {
            state.setEnabled(true);
            state.requestRebuild();
        }
    }

    private void disableFarTrees() {
        config.setFarTree(false);
        FarTreeState state = app.getStateManager().getState(FarTreeState.class);
        if (state != null) {
            state.setEnabled(false);
        }
    }

    /** Shows or hides the on-screen FPS counter ({@link StatsAppState}). */
    private void setShowFpsEnabled(boolean enabled) {
        config.setShowFps(enabled);
        StatsAppState stats = app.getStateManager().getState(StatsAppState.class);
        if (stats != null) {
            stats.setDisplayFps(enabled);
        }
    }

    /** Shows or hides the on-screen world-position readout ({@link StatsAppState}). */
    private void setShowPositionEnabled(boolean enabled) {
        config.setShowPosition(enabled);
        StatsAppState stats = app.getStateManager().getState(StatsAppState.class);
        if (stats != null) {
            stats.setDisplayPosition(enabled);
        }
    }

    /** Caps the frame rate at 120 (high) or 60 (battery-saving on mobile), applying it live. */
    private void setHighFramerate(boolean high) {
        int fps = high ? IalonConfig.FPS_LIMIT_DESKTOP : 60;
        config.setMaxFramerate(fps);
        ScreenState screenState = app.getStateManager().getState(ScreenState.class);
        if (screenState != null) {
            screenState.setFrameRate(fps);
        }
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
        boolean radiusChanged = radius != config.getGridRadius();
        config.setGridRadius(radius);
        int size = radius * 2 + 1;
        BlocksConfig.getInstance().setGrid(new Vec3i(size, config.getGridHeight() * 2 + 1, size));
        if (app != null) {
            app.getStateManager().getState(ChunkPagerState.class).getChunkPager().updateGridSize();
        }

        config.setAmbiantIntensity((float)ambientIntensity.getValue());

        applyFarTrees((float) farTreeDistance.getValue());

        if (radiusChanged) {
            // The far terrain's inner-radius discard and the enclosure box both scale with the render
            // distance ; the far trees ring starts just beyond the loaded chunks too.
            FarTerrainState farTerrainState = app.getStateManager().getState(FarTerrainState.class);
            if (farTerrainState != null) {
                farTerrainState.onRenderDistanceChanged();
            }
            FarTreeState farTreeState = app.getStateManager().getState(FarTreeState.class);
            if (farTreeState != null) {
                farTreeState.requestRebuild();
            }
        }
    }

    @Override
    protected void cleanup(Application app) {
        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).unregister(this);
        }
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

    @Override
    public void onResize(int width, int height) {
        layout(width, height);
    }

    /** Recomputes the gear-button size for the new height and repositions it (and the full-screen popup). */
    private void layout(int width, int height) {
        buttonSize = height / 12;
        float margin = UiHelper.screenMargin(height);
        UiHelper.resizeTextureButton(buttonSettings, buttonSize, width / 2f + buttonSize + SPACING, height - margin);
        ((Container) popup).setPreferredSize(new Vector3f(width, height, 0));
        popup.setLocalTranslation(0, height, 100);
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
        farTreeDistance.update();
        farTerrain.update();
        showFps.update();
        showPosition.update();
        maxFramerate.update();
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

    private class IgnoreMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
        }
    }


}

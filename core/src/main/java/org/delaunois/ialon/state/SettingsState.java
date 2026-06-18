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
import com.jme3.app.DebugKeysAppState;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
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
import com.simsilica.lemur.Label;
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

import java.util.Locale;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SettingsState extends BaseAppState implements ActionListener, Resizable {

    private static final String ACTION_SWITCH_MOUSELOCK = "switch-mouselock";

    private SimpleApplication app;
    private boolean isMouseLocked = false;
    private Node popup;
    private Button cornerBack;
    private Button cornerClose;

    private final IalonConfig config;
    private static final int FAR_TREE_MAX_DISTANCE = 1000;

    private SettingsValue gridSize;
    private SettingsValue ambientIntensity;
    private SettingsValue sunIntensity;
    private SettingsValue farTreeDistance;
    private SettingsToggle farTerrain;
    private SettingsToggle showFps;
    private SettingsToggle showPosition;
    private SettingsToggle minimap;
    private SettingsToggle maxFramerate;
    private SettingsToggle devMode;

    public SettingsState(IalonConfig config) {
        this.config = config;
    }

    @Override
    public void initialize(Application app) {
        this.app = (SimpleApplication) app;
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

    private Node createPopup() {
        Container settingsPopup = new Container(IALON_STYLE);
        settingsPopup.setLocalTranslation(0, app.getCamera().getHeight(), 100);
        settingsPopup.setName("settingsPopup");
        settingsPopup.setPreferredSize(new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0));
        UiHelper.addBackground(settingsPopup, new ColorRGBA(0f, 0f, 0f, 0.95f), config);
        // Consume background clicks (so they don't reach the game) but do NOT close the popup :
        // closing is done only via the Close button.
        settingsPopup.addMouseListener(new IgnoreMouseClickListener());

        float vh = app.getCamera().getHeight() / 100f;

        // The whole block is centered horizontally and anchored to a fixed top margin (top inset 0, all
        // vertical slack pushed to the bottom) so the title sits at the same height regardless of how much
        // content there is — matching the world-menu popup. A fixed top spacer (added as row 0) provides
        // that top margin.
        Container content = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);
        content.setInsetsComponent(new DynamicInsetsComponent(0, 50, 50, 50));
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

        minimap = new SettingsToggle("Minimap", app.getCamera(),
                config.isShowMinimap(), this::setMinimapEnabled);

        maxFramerate = new SettingsToggle("Max FPS", app.getCamera(),
                config.getMaxFramerate() >= 120, this::setHighFramerate, "120", "60");

        devMode = new SettingsToggle("Debug mode", app.getCamera(),
                config.isDevMode(), this::setDevModeEnabled);

        gridSize.addToContainer(container, 0);
        ambientIntensity.addToContainer(container, 1);
        sunIntensity.addToContainer(container, 2);
        farTerrain.addToContainer(container, 3);
        farTreeDistance.addToContainer(container, 4);
        showFps.addToContainer(container, 5);
        showPosition.addToContainer(container, 6);
        minimap.addToContainer(container, 7);
        maxFramerate.addToContainer(container, 8);
        devMode.addToContainer(container, 9);

        // Fixed top margin (same as the screen-edge margin used by the top buttons) so the title sits at a
        // constant height.
        Panel topSpacer = new Panel();
        topSpacer.setBackground(null);
        topSpacer.setPreferredSize(new Vector3f(1, UiHelper.screenMargin(app.getCamera().getHeight()), 0));
        content.addChild(topSpacer, 0, 0);

        // Title, centered horizontally within its cell (the widest column 0 child) by the
        // DynamicInsetsComponent, with a small spacer row below it.
        Label title = new Label("Global Settings", IALON_STYLE);
        title.setFontSize(4 * vh);
        title.setColor(ColorRGBA.White);
        title.setTextHAlignment(HAlignment.Center);
        title.setInsetsComponent(new DynamicInsetsComponent(0, 0.5f, 0, 0.5f));
        content.addChild(title, 1, 0);

        Panel titleSpacer = new Panel();
        titleSpacer.setBackground(null);
        titleSpacer.setPreferredSize(new Vector3f(1, 3 * vh, 0));
        content.addChild(titleSpacer, 2, 0);

        content.addChild(container, 3, 0);

        settingsPopup.addChild(content);

        // Header buttons at the popup's top corners, level with the title (positioned by layout()) :
        // Back (left) returns to the worlds menu, Close (right) returns to the game. No bottom buttons.
        cornerBack = new Button("Back", IALON_STYLE);
        cornerBack.setTextVAlignment(VAlignment.Center);
        cornerBack.addClickCommands(source -> backToWorldMenu());
        settingsPopup.attachChild(cornerBack);

        cornerClose = new Button("Close", IALON_STYLE);
        cornerClose.setTextVAlignment(VAlignment.Center);
        cornerClose.addClickCommands(source -> hidePopup());
        settingsPopup.attachChild(cornerClose);

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

    /**
     * Shows or hides the top-down far-terrain minimap ({@link MinimapState}), attaching it on demand. The
     * minimap reuses the far terrain's heightmap when present, else samples the generator itself, so it
     * works whether or not the far terrain is enabled.
     */
    private void setMinimapEnabled(boolean enabled) {
        config.setShowMinimap(enabled);
        MinimapState state = app.getStateManager().getState(MinimapState.class);
        if (state == null) {
            if (enabled) {
                app.getStateManager().attach(IalonInitializer.setupMinimap(config));
            }
        } else {
            state.setEnabled(enabled);
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

    /**
     * Toggles developer/debug mode live : attaches the debug overlays + hotkeys (axes, debug HUD, jME
     * debug keys, wireframe toggle) and the detailed stat view when on, detaches them when off. Mirrors the
     * startup-time attachment in {@link org.delaunois.ialon.Ialon}. Runtime-only : not persisted, so it
     * defaults back to off on restart.
     */
    private void setDevModeEnabled(boolean enabled) {
        config.setDevMode(enabled);
        StatsAppState stats = app.getStateManager().getState(StatsAppState.class);
        if (stats != null) {
            stats.setDisplayStatView(enabled);
        }
        if (enabled) {
            if (app.getStateManager().getState(IalonDebugState.class) == null) {
                app.getStateManager().attach(new AxesDebugState());
                app.getStateManager().attach(new IalonDebugState(config));
                app.getStateManager().attach(new DebugKeysAppState());
                app.getStateManager().attach(new WireframeState());
            }
        } else {
            detachState(AxesDebugState.class);
            detachState(IalonDebugState.class);
            detachState(DebugKeysAppState.class);
            detachState(WireframeState.class);
        }
    }

    private <T extends AppState> void detachState(Class<T> type) {
        T state = app.getStateManager().getState(type);
        if (state != null) {
            app.getStateManager().detach(state);
        }
    }

    public void showPopup() {
        if (popup.getParent() == null) {
            app.getGuiNode().attachChild(popup);
            app.getStateManager().getState(PlayerState.class).setTouchEnabled(false);
        }
    }

    /** Closes the settings popup and reopens the worlds menu (owned by {@link WorldMenuState}). */
    private void backToWorldMenu() {
        hidePopup();
        Optional.ofNullable(app.getStateManager().getState(WorldMenuState.class))
                .ifPresent(WorldMenuState::showPopup);
    }

    public void hidePopup() {
        if (popup.getParent() != null) {
            popup.removeFromParent();
            app.getStateManager().getState(PlayerState.class).setTouchEnabled(true);
        }
        saveConfig();
    }

    public void saveConfig() {
        applyRenderDistance((int) gridSize.getValue());

        config.setAmbiantIntensity((float)ambientIntensity.getValue());

        applyFarTrees((float) farTreeDistance.getValue());
    }

    /**
     * Applies a render-distance ({@code gridRadius}) change live : updates the config (clamped to its
     * min/max), resizes the chunk grid and re-pages, and rebuilds the far terrain / far trees that scale
     * with the render distance. Reused by the settings popup and by the {@link MemoryGuardState} when it
     * steps the render distance down under memory pressure. The slider widget is kept in sync so the UI
     * reflects the (possibly clamped) value.
     */
    public void applyRenderDistance(int radius) {
        boolean radiusChanged = radius != config.getGridRadius();
        config.setGridRadius(radius);
        int actual = config.getGridRadius(); // after clamp to [gridRadiusMin, gridRadiusMax]
        int size = actual * 2 + 1;
        BlocksConfig.getInstance().setGrid(new Vec3i(size, config.getGridHeight() * 2 + 1, size));
        if (app != null) {
            ChunkPagerState pagerState = app.getStateManager().getState(ChunkPagerState.class);
            if (pagerState != null) {
                pagerState.getChunkPager().updateGridSize();
            }
        }

        if (radiusChanged) {
            // The far terrain's inner-radius discard and the enclosure box both scale with the render
            // distance ; the far trees ring starts just beyond the loaded chunks too.
            FarTerrainState farTerrainState = app == null ? null : app.getStateManager().getState(FarTerrainState.class);
            if (farTerrainState != null) {
                farTerrainState.onRenderDistanceChanged();
            }
            FarTreeState farTreeState = app == null ? null : app.getStateManager().getState(FarTreeState.class);
            if (farTreeState != null) {
                farTreeState.requestRebuild();
            }
        }

        if (gridSize != null) {
            gridSize.setValue(actual);
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
        // No own button anymore : the popup is opened from WorldMenuState's "Settings" button.
    }

    @Override
    protected void onDisable() {
        // No own button anymore.
    }

    @Override
    public void onResize(int width, int height) {
        layout(width, height);
    }

    /** Re-fits the full-screen popup to the new screen size and repositions the corner header buttons. */
    private void layout(int width, int height) {
        ((Container) popup).setPreferredSize(new Vector3f(width, height, 0));
        popup.setLocalTranslation(0, height, 100);
        float vh = height / 100f;
        sizeCornerButton(cornerBack, vh);
        sizeCornerButton(cornerClose, vh);
        UiHelper.placeCornerButtons(cornerBack, cornerClose, width, height);
    }

    /**
     * Sizes a corner header button : font only, no fixed width, so the button hugs its text. That lets
     * {@link UiHelper#placeCornerButtons} sit it flush in the corner (its real width is then known), so
     * Back hugs the left edge and Close hugs the right edge at the same margin.
     */
    private void sizeCornerButton(Button button, float vh) {
        button.setFontSize(4 * vh);
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
        minimap.update();
        maxFramerate.update();
        devMode.update();
    }

    private class IgnoreMouseClickListener extends DefaultMouseListener {
        @Override
        public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
            event.setConsumed();
        }
    }


}

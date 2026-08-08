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
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.ui.IalonTheme;
import org.delaunois.ialon.ui.UiHelper;

import java.util.Locale;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SettingsState extends BaseAppState implements ActionListener, Resizable, Popup {

    private static final String ACTION_SWITCH_MOUSELOCK = "switch-mouselock";

    /** Shown on the popup's title signboard (part of the chrome — see {@link IalonTheme#applyChromeOver}). */
    private static final String TITLE = "Global Settings";

    private SimpleApplication app;
    private boolean isMouseLocked = false;
    private Node popup;
    private Button cornerBack;
    private Button cornerClose;

    // The settings themselves : a centred, swipe/wheel-scrollable column. Scrollable because on a short
    // screen (or after the window is shrunk) the last rows overflow the bottom edge and would otherwise
    // be unreachable.
    private ScrollableColumn column;

    private final IalonConfig config;
    private static final int FAR_TREE_MAX_DISTANCE = 1000;

    private SettingsValue gridSize;
    private SettingsValue ambientIntensity;
    private SettingsValue sunIntensity;
    private SettingsValue farTreeDistance;
    private SettingsValue soundVolume;
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
        // Veil in the background layer, steampunk chrome in the border layer (both re-applied by layout()).
        IalonTheme.applyVeil(settingsPopup, app.getAssetManager(), config);
        // Consume background clicks (so they don't reach the game) but do NOT close the popup :
        // closing is done only via the Close button.
        settingsPopup.addMouseListener(new IgnoreMouseClickListener());

        // The settings themselves : a centred, vertically scrollable column (see ScrollableColumn — the
        // "New world" form is built the same way, so both behave identically, notably after a resize).
        column = new ScrollableColumn(settingsPopup, app.getCamera());

        Container container = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None), IALON_STYLE);

        gridSize = new SettingsValue("Render distance", app.getCamera(),
                config.getGridRadiusMin(), config.getGridRadiusMax(), config.getGridRadius(),
                v -> String.valueOf(v.intValue())
        );

        ambientIntensity = new SettingsValue("Ambient Light", app.getCamera(),
                0.0f, 2.0f, config.getAmbiantIntensity(),
                v -> {
                    config.setAmbiantIntensity((float)ambientIntensity.getValue());
                    return String.format(Locale.ENGLISH, "%.0f", v*100);
                }
        );

        sunIntensity = new SettingsValue("Sun Light", app.getCamera(),
                0.0f, 2.0f, config.getSunIntensity(),
                v -> {
                    config.setSunIntensity((float)sunIntensity.getValue());
                    return String.format(Locale.ENGLISH, "%.0f", v*100);
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

        // Master gain, applied live on the jME Listener so the slider is audible while dragging it.
        // Covers every sound, not just the wildlife. Reads "Off" at 0.
        soundVolume = new SettingsValue("Sound", app.getCamera(),
                0.0f, 1.0f, config.getSoundVolume(),
                v -> {
                    config.setSoundVolume(v.floatValue());
                    IalonInitializer.applyMasterVolume(app, v.floatValue());
                    return v == 0 ? "Off" : String.format(Locale.ENGLISH, "%.0f", v * 100);
                }
        );

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
        soundVolume.addToContainer(container, 5);
        showFps.addToContainer(container, 6);
        showPosition.addToContainer(container, 7);
        minimap.addToContainer(container, 8);
        maxFramerate.addToContainer(container, 9);
        devMode.addToContainer(container, 10);

        column.addHeaderSpace();
        column.addRow(container);
        column.attach();

        // Header buttons at the popup's top corners, level with the title (positioned by layout()) :
        // Back (left) returns to the worlds menu, Close (right) returns to the game. No bottom buttons.
        cornerBack = IalonTheme.cornerButton("Back", app.getAssetManager(), app.getCamera().getHeight());
        cornerBack.addClickCommands(source -> backToWorldMenu());
        settingsPopup.attachChild(cornerBack);

        cornerClose = IalonTheme.cornerButton("Close", app.getAssetManager(), app.getCamera().getHeight());
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
        FarTerrainState state = state(FarTerrainState.class);
        if (state == null) {
            if (enabled) {
                attach(IalonInitializer.setupFarTerrain(config));
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
        FarTreeState state = state(FarTreeState.class);
        if (state == null) {
            attach(IalonInitializer.setupFarTree(config));
        } else {
            state.setEnabled(true);
            state.requestRebuild();
        }
    }

    private void disableFarTrees() {
        config.setFarTree(false);
        FarTreeState state = state(FarTreeState.class);
        if (state != null) {
            state.setEnabled(false);
        }
    }

    /**
     * The attached state of the given type, or {@code null} when there is none — including when this
     * state itself has not been initialized yet, so {@code app} is still null. That case is real :
     * {@link MemoryGuardState} drives {@link #applyRenderDistance(int)} from the render loop and does not
     * wait for the settings popup to be built, and the far-terrain / far-trees appliers are reached from
     * there.
     */
    private <T extends AppState> T state(Class<T> type) {
        return app == null ? null : app.getStateManager().getState(type);
    }

    /** Attaches a state, or does nothing when this state is not initialized yet (see {@link #state}). */
    private void attach(AppState state) {
        if (app != null) {
            app.getStateManager().attach(state);
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
     * startup-time attachment in {@link org.delaunois.ialon.Ialon}.
     *
     * <p>Persisted with the other game settings ({@code save/game.yml}), so the choice survives a restart.
     * Each launcher still decides what it means on a save that has never stored it — the desktop harness
     * starts in debug mode, Android does not — but that is a default now, applied BEFORE the load, and no
     * longer an override applied after it.
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
            }
        } else {
            detachState(AxesDebugState.class);
            detachState(IalonDebugState.class);
            detachState(DebugKeysAppState.class);
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
            // Always reopen at the top, so the title and the first settings are what shows up.
            column.attach();
            app.getGuiNode().attachChild(popup);
            // The chrome is a sibling of the popup, so it can only be placed once the popup is attached.
            IalonTheme.applyChromeOver(popup, app.getAssetManager(),
                    app.getCamera().getWidth(), app.getCamera().getHeight(), TITLE);
            setPlayerTouchEnabled(false);
        }
    }

    /** Blocks / restores the in-game touch controls while the popup is up (absent in UI previews). */
    private void setPlayerTouchEnabled(boolean enabled) {
        Optional.ofNullable(app.getStateManager().getState(PlayerState.class))
                .ifPresent(ps -> ps.setTouchEnabled(enabled));
    }

    @Override
    public boolean isPopupOpen() {
        return popup != null && popup.getParent() != null;
    }

    /** Full screen, opaque veil : nothing of the world shows through, so it need not be drawn. */
    @Override
    public boolean hidesWorld() {
        return true;
    }

    @Override
    public void closePopup() {
        hidePopup();
    }

    /** Closes the settings popup and reopens the worlds menu (owned by {@link WorldMenuState}). */
    private void backToWorldMenu() {
        hidePopup();
        Optional.ofNullable(app.getStateManager().getState(WorldMenuState.class))
                .ifPresent(WorldMenuState::showPopup);
    }

    public void hidePopup() {
        if (popup.getParent() != null) {
            IalonTheme.removeChromeFrom((Node) popup.getParent());
            popup.removeFromParent();
            setPlayerTouchEnabled(true);
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
        ChunkPagerState pagerState = state(ChunkPagerState.class);
        if (pagerState != null) {
            pagerState.getChunkPager().updateGridSize();
        }

        if (radiusChanged) {
            // The far terrain's inner-radius discard and the enclosure box both scale with the render
            // distance ; the far trees ring starts just beyond the loaded chunks too.
            FarTerrainState farTerrainState = state(FarTerrainState.class);
            if (farTerrainState != null) {
                farTerrainState.onRenderDistanceChanged();
            }
            FarTreeState farTreeState = state(FarTreeState.class);
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

    /**
     * Re-fits the full-screen popup to the new screen size, rebuilds its chrome and repositions the corner
     * header buttons. The chrome has to be rebuilt, not just repositioned : a nine-patch bakes its scale
     * into its mesh, so its thickness would otherwise stay frozen in the old resolution's pixels.
     */
    private void layout(int width, int height) {
        ((Container) popup).setPreferredSize(new Vector3f(width, height, 0));
        popup.setLocalTranslation(0, height, 100);
        IalonTheme.applyChromeOver(popup, app.getAssetManager(), width, height, TITLE);
        sizeCornerButton(cornerBack, height);
        sizeCornerButton(cornerClose, height);
        UiHelper.placeCornerButtons(cornerBack, cornerClose, width, height);
        column.layout(width, height);
    }

    /**
     * Restyles a corner header button : no fixed width, so it hugs its text. That lets
     * {@link UiHelper#placeCornerButtons} sit it flush in the corner (its real width is then known), so
     * Back hugs the left edge and Close hugs the right edge at the same margin.
     */
    private void sizeCornerButton(Button button, int height) {
        IalonTheme.restyleCornerButton(button, app.getAssetManager(), height);
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
        soundVolume.update();
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

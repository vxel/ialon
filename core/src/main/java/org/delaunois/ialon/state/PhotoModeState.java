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

import static org.delaunois.ialon.Ialon.IALON_STYLE;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.event.DefaultMouseListener;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.ui.UiHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * "Photo Mode" : hides every UI element (control buttons, block selector + history, crosshair, world
 * menu gear button, FPS overlay and the block placement/removal wireframe) so the player can take a
 * clean screenshot of the world alone. The mode is exited automatically on the next touch/click.
 *
 * <p>The mode is entered via {@link #enter()} (called by the "Photo Mode" button of
 * {@link WorldMenuState}). It disables the UI-owning AppStates (reusing their existing
 * {@code onDisable} which detaches their nodes) and asks {@link PlayerState} to hide the crosshair +
 * placeholder. A full-screen, transparent Lemur panel attached to the GUI node then catches (and
 * <i>consumes</i>) the next click/touch to exit : Lemur runs as a {@code RawInputListener}, so a
 * consumed event is not redispatched to the raw input mappings — the exit tap therefore triggers
 * neither a block edit nor a camera rotation.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class PhotoModeState extends BaseAppState implements Resizable {

    // AppStates disabled while in photo mode (those owning always-visible UI).
    private static final Class<?>[] UI_STATES = {
            ButtonManagerState.class,
            BlockSliderSelectionState.class,
            WorldMenuState.class,
            TimeFactorState.class,
            IalonDebugState.class,
            StatsAppState.class
    };

    private SimpleApplication app;
    private Container catcher;
    private boolean active;
    // States we actually disabled (were enabled before entering) : only those are re-enabled on exit.
    private final List<AppState> disabledStates = new ArrayList<>();

    private final IalonConfig config;

    public PhotoModeState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).register(this);
        }
    }

    /** Enters photo mode : hides all UI and starts listening for the exit tap. No-op if already active. */
    public void enter() {
        if (active) {
            return;
        }
        active = true;

        disabledStates.clear();
        for (Class<?> stateClass : UI_STATES) {
            @SuppressWarnings("unchecked")
            AppState state = app.getStateManager().getState((Class<AppState>) stateClass);
            if (state != null && state.isEnabled()) {
                state.setEnabled(false);
                disabledStates.add(state);
            }
        }

        Optional.ofNullable(app.getStateManager().getState(PlayerState.class))
                .ifPresent(ps -> ps.setHudHidden(true));

        attachCatcher();
    }

    /** Exits photo mode : restores every hidden element. No-op if not active. */
    public void exit() {
        if (!active) {
            return;
        }
        active = false;

        if (catcher != null) {
            catcher.removeFromParent();
            catcher = null;
        }

        disabledStates.forEach(state -> state.setEnabled(true));
        disabledStates.clear();

        Optional.ofNullable(app.getStateManager().getState(PlayerState.class))
                .ifPresent(ps -> ps.setHudHidden(false));
    }

    /**
     * Builds and attaches the full-screen transparent catcher. Its quad is invisible (alpha 0, so it
     * does not show on the screenshot) but still pickable by Lemur, so the first click/touch is
     * consumed here and exits the mode.
     */
    private void attachCatcher() {
        catcher = new Container(IALON_STYLE);
        catcher.setName("photoModeCatcher");
        catcher.setLocalTranslation(0, app.getCamera().getHeight(), 100);
        catcher.setPreferredSize(new Vector3f(app.getCamera().getWidth(), app.getCamera().getHeight(), 0));
        UiHelper.addBackground(catcher, new ColorRGBA(0f, 0f, 0f, 0f), config);
        catcher.addMouseListener(new DefaultMouseListener() {
            @Override
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                // React on press (DOWN) : the click that opened photo mode already emitted its DOWN
                // before this listener was attached, so there is no immediate self-exit.
                if (event.isPressed()) {
                    app.enqueue(PhotoModeState.this::exit);
                }
            }
        });
        app.getGuiNode().attachChild(catcher);
    }

    @Override
    public void onResize(int width, int height) {
        if (active && catcher != null) {
            catcher.setLocalTranslation(0, height, 100);
            catcher.setPreferredSize(new Vector3f(width, height, 0));
        }
    }

    @Override
    protected void onEnable() {
        // Nothing to do : the mode is driven explicitly via enter() / exit().
    }

    @Override
    protected void onDisable() {
        exit();
    }

    @Override
    protected void cleanup(Application application) {
        exit();
        if (application.getStateManager().getState(ScreenState.class) != null) {
            application.getStateManager().getState(ScreenState.class).unregister(this);
        }
    }
}

package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.system.AppSettings;
import com.simsilica.lemur.GuiGlobals;

import org.delaunois.ialon.IalonConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Handles screen resize (Key F2) and mouse locking
 * @author Cedric de Launois
 */
@Slf4j
public class ScreenState extends BaseAppState implements ActionListener {

    private static final String ACTION_TOGGLE_FULLSCREEN = "toggle-fullscreen";
    private static final String ACTION_SWITCH_MOUSELOCK = "switch-mouselock";

    private final AppSettings settings;
    private int camHeight;
    private int camWidth;
    private Application app;
    private boolean checkResize = false;
    private boolean mouselocked = false;
    private final IalonConfig config;

    public ScreenState(AppSettings settings, IalonConfig config) {
        this.settings = settings;
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        this.app = app;
        camHeight = app.getCamera().getHeight();
        camWidth = app.getCamera().getWidth();

        app.getInputManager().addMapping(ACTION_TOGGLE_FULLSCREEN, new KeyTrigger(KeyInput.KEY_F2));
        if (!app.getInputManager().hasMapping(ACTION_SWITCH_MOUSELOCK)) {
            app.getInputManager().addMapping(ACTION_SWITCH_MOUSELOCK, new KeyTrigger(KeyInput.KEY_BACK));
        }
        app.getInputManager().addListener(this, ACTION_TOGGLE_FULLSCREEN, ACTION_SWITCH_MOUSELOCK);
    }

    public void checkResize() {
        checkResize = true;
    }

    @Override
    public void update(float tpf) {
        if (checkResize && (app.getCamera().getWidth() != camWidth || app.getCamera().getHeight() != camHeight)) {

            TimeFactorState timeFactorState = app.getStateManager().getState(TimeFactorState.class);
            if (timeFactorState != null) {
                timeFactorState.resize();
            }
            BlockSliderSelectionState blockSliderSelectionState = app.getStateManager().getState(BlockSliderSelectionState.class);
            if (blockSliderSelectionState != null) {
                blockSliderSelectionState.resize();
            }
            PlayerState playerState = app.getStateManager().getState(PlayerState.class);
            if (playerState != null) {
                playerState.resize();
            }

            checkResize = false;
            camWidth = app.getCamera().getWidth();
            camHeight = app.getCamera().getHeight();
        }
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        // Nothing to do
    }

    @Override
    protected void onDisable() {
        // Nothing to do
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_SWITCH_MOUSELOCK.equals(name) && isPressed) {
            log.info("Switching mouse lock to {}", !mouselocked);
            switchMouseLock();

        } else if (ACTION_TOGGLE_FULLSCREEN.equals(name) && isPressed) {
            log.info("Toggle fullscreen");
            if (settings.isFullscreen()) {
                settings.setResolution(config.getScreenWidth(), config.getScreenHeight());
                settings.setFullscreen(false);
            } else {
                settings.setResolution(-1, -1);
                settings.setFullscreen(true);
            }
            app.restart();
        }
    }

    private void switchMouseLock() {
        mouselocked = !mouselocked;
        GuiGlobals.getInstance().setCursorEventsEnabled(!mouselocked);
        app.getInputManager().setCursorVisible(!mouselocked);
    }

}

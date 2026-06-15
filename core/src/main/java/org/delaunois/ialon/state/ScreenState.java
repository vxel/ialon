package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.system.AppSettings;
import com.simsilica.lemur.GuiGlobals;

import org.delaunois.ialon.IalonConfig;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.extern.slf4j.Slf4j;

/**
 * Handles screen resize, fullscreen toggle (Key F2) and mouse locking.
 * <p>
 * This state is the single owner of the resolution-change flow : {@link Resizable} states
 * register themselves here during their {@code initialize()} and are re-laid-out together
 * whenever the framebuffer is reshaped. The reshape notification is delivered by
 * {@code Ialon.reshape(...)} (the native jME callback, fired on window resize and on the
 * context restart triggered by the fullscreen toggle) which calls {@link #onReshape(int, int)}.
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ScreenState extends BaseAppState implements ActionListener {

    private static final String ACTION_TOGGLE_FULLSCREEN = "toggle-fullscreen";
    private static final String ACTION_SWITCH_MOUSELOCK = "switch-mouselock";

    private final AppSettings settings;
    private final IalonConfig config;
    // Registered GUI states to re-layout on a resolution change. CopyOnWrite : registration happens
    // during state initialization while onReshape may iterate from the render thread.
    private final List<Resizable> resizables = new CopyOnWriteArrayList<>();

    private Application app;
    private boolean mouselocked = false;
    // Last dispatched size : reshape may fire repeatedly with an unchanged size (startup, focus/move
    // events, wasResized polling). Re-laying-out only on a real change avoids needlessly rebuilding
    // GUI (e.g. tearing down the open block menu mid-scroll).
    private int lastWidth = -1;
    private int lastHeight = -1;

    public ScreenState(AppSettings settings, IalonConfig config) {
        this.settings = settings;
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        this.app = app;

        app.getInputManager().addMapping(ACTION_TOGGLE_FULLSCREEN, new KeyTrigger(KeyInput.KEY_F2));
        if (!app.getInputManager().hasMapping(ACTION_SWITCH_MOUSELOCK)) {
            app.getInputManager().addMapping(ACTION_SWITCH_MOUSELOCK, new KeyTrigger(KeyInput.KEY_BACK));
        }
        app.getInputManager().addListener(this, ACTION_TOGGLE_FULLSCREEN, ACTION_SWITCH_MOUSELOCK);
    }

    /**
     * Registers a state to be laid out on every resolution change. Safe to call before this state
     * is initialized (states register from their own {@code initialize()}).
     */
    public void register(Resizable resizable) {
        if (resizable != null && !resizables.contains(resizable)) {
            resizables.add(resizable);
        }
    }

    public void unregister(Resizable resizable) {
        resizables.remove(resizable);
    }

    /**
     * Changes the frame-rate cap and applies it live. jME only reads {@code AppSettings.frameRate} when
     * the context is (re)created, so the new cap is applied by restarting the context (same mechanism as
     * the fullscreen toggle).
     */
    public void setFrameRate(int fps) {
        if (settings.getFrameRate() == fps) {
            return;
        }
        log.info("Setting frame rate to {}", fps);
        settings.setFrameRate(fps);
        app.restart();
    }

    /**
     * Re-lays-out every registered {@link Resizable} for the new screen size. Called by
     * {@code Ialon.reshape(...)} on the render thread.
     *
     * @param width  the new GUI width
     * @param height the new GUI height
     */
    public void onReshape(int width, int height) {
        if (width == lastWidth && height == lastHeight) {
            return;
        }
        lastWidth = width;
        lastHeight = height;
        log.info("Reshaping GUI to {}x{}", width, height);
        for (Resizable resizable : resizables) {
            try {
                resizable.onResize(width, height);
            } catch (RuntimeException e) {
                log.warn("Resize failed for {} : {}", resizable.getClass().getSimpleName(), e.getMessage(), e);
            }
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

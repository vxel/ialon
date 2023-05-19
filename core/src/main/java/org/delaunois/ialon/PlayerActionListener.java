package org.delaunois.ialon;

import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

import org.delaunois.ialon.state.PlayerState;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PlayerActionListener implements ActionListener, AnalogListener {

    private static final String ACTION_LEFT = "left";
    private static final String ACTION_RIGHT = "right";
    private static final String ACTION_FORWARD = "forward";
    private static final String ACTION_BACKWARD = "backward";
    private static final String ACTION_JUMP = "jump";
    private static final String ACTION_FIRE = "fire";
    private static final String ACTION_FLY = "fly";
    private static final String ACTION_FLY_UP = "fly_up";
    private static final String ACTION_FLY_DOWN = "fly_down";

    private static final String ACTION_LOOK_LEFT = "look-left";
    private static final String ACTION_LOOK_RIGHT = "look-right";
    private static final String ACTION_LOOK_UP = "look-up";
    private static final String ACTION_LOOK_DOWN = "look-down";

    private static final String ACTION_ADD_BLOCK = "add-block";
    private static final String ACTION_REMOVE_BLOCK = "remove-block";
    private static final String ACTION_DEBUG_CHUNK = "debug-chunk";
    private static final String ACTION_SWITCH_MOUSELOCK = "switch-mouselock";

    private static final String[] ACTIONS = new String[]{
            ACTION_LEFT,
            ACTION_RIGHT,
            ACTION_FORWARD,
            ACTION_BACKWARD,
            ACTION_JUMP,
            ACTION_FIRE,
            ACTION_FLY,
            ACTION_FLY_UP,
            ACTION_FLY_DOWN,
            ACTION_LOOK_LEFT,
            ACTION_LOOK_RIGHT,
            ACTION_LOOK_UP,
            ACTION_LOOK_DOWN,
            ACTION_ADD_BLOCK,
            ACTION_REMOVE_BLOCK,
            ACTION_DEBUG_CHUNK,
            ACTION_SWITCH_MOUSELOCK
    };

    private final CameraHelper cameraHelper;
    private final PlayerState playerState;
    private final InputManager inputManager;
    private final Camera camera;
    private boolean isMouselocked = false;
    private IalonConfig config;

    public PlayerActionListener(PlayerState playerState, IalonConfig config) {
        this.config = config;
        this.playerState = playerState;
        this.inputManager = playerState.getApplication().getInputManager();
        this.camera = playerState.getCamera();
        this.cameraHelper = new CameraHelper();
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (log.isDebugEnabled()) {
            log.debug("Action {} isPressed {}", name, isPressed);
        }

        switch (name) {
            case ACTION_ADD_BLOCK:
                if (isMouselocked) {
                    playerState.getPlayerActionButtons().actionAddBlock(isPressed);
                }
                break;
            case ACTION_REMOVE_BLOCK:
                if (isMouselocked) {
                    playerState.getPlayerActionButtons().actionRemoveBlock(isPressed);
                }
                break;
            case ACTION_LEFT:
                playerState.getPlayerActionButtons().actionLeft(isPressed);
                break;
            case ACTION_RIGHT:
                playerState.getPlayerActionButtons().actionRight(isPressed);
                break;
            case ACTION_FORWARD:
                playerState.getPlayerActionButtons().actionForward(isPressed);
                break;
            case ACTION_BACKWARD:
                playerState.getPlayerActionButtons().actionBackward(isPressed);
                break;
            case ACTION_FLY_UP:
                playerState.actionFlyUp(isPressed);
                break;
            case ACTION_FLY_DOWN:
                playerState.actionFlyDown(isPressed);
                break;
            case ACTION_JUMP:
                playerState.getPlayerActionButtons().actionJump(isPressed);
                break;
            case ACTION_FIRE:
                playerState.actionFireBall(isPressed);
                break;
            case ACTION_FLY:
                playerState.getPlayerActionButtons().actionToggleFly(isPressed);
                break;
            case ACTION_DEBUG_CHUNK:
                actionDebugChunks(isPressed);
                break;
            case ACTION_SWITCH_MOUSELOCK:
                actionSwitchMouseLock(isPressed);
                break;
            case ACTION_LOOK_LEFT:
            case ACTION_LOOK_RIGHT:
            case ACTION_LOOK_UP:
            case ACTION_LOOK_DOWN:
                // Handled by onAnalog()
                break;
            default:
                log.warn("Unrecognized action {}", name);
        }
    }

    /**
     * Callback to notify this controller of an analog input event.
     *
     * @param name  name of the input event
     * @param value value of the axis (from 0 to 1)
     * @param tpf   time per frame (in seconds)
     */
    @Override
    public void onAnalog(String name, float value, float tpf) {
        if (isMouselocked) {
            if (ACTION_LOOK_LEFT.equals(name)) {
                cameraHelper.rotate(camera, config.getRotationSpeed() * value, Vector3f.UNIT_Y);

            } else if (ACTION_LOOK_RIGHT.equals(name)) {
                cameraHelper.rotate(camera, -config.getRotationSpeed() * value, Vector3f.UNIT_Y);

            } else if (ACTION_LOOK_UP.equals(name)) {
                cameraHelper.rotate(camera, -config.getRotationSpeed() * value, camera.getLeft());

            } else if (ACTION_LOOK_DOWN.equals(name)) {
                cameraHelper.rotate(camera, config.getRotationSpeed() * value, camera.getLeft());
            }
        }
    }

    public void addKeyMappings() {
        inputManager.addMapping(ACTION_LOOK_LEFT, new MouseAxisTrigger(MouseInput.AXIS_X, true));
        inputManager.addMapping(ACTION_LOOK_RIGHT, new MouseAxisTrigger(MouseInput.AXIS_X, false));
        inputManager.addMapping(ACTION_LOOK_UP, new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        inputManager.addMapping(ACTION_LOOK_DOWN, new MouseAxisTrigger(MouseInput.AXIS_Y, true));

        inputManager.addMapping(ACTION_ADD_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_LEFT), new KeyTrigger(KeyInput.KEY_RETURN));
        inputManager.addMapping(ACTION_REMOVE_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_RIGHT), new KeyTrigger(KeyInput.KEY_DELETE));
        inputManager.addMapping(ACTION_LEFT, new KeyTrigger(KeyInput.KEY_Q));
        inputManager.addMapping(ACTION_RIGHT, new KeyTrigger(KeyInput.KEY_D));
        inputManager.addMapping(ACTION_FORWARD, new KeyTrigger(KeyInput.KEY_Z));
        inputManager.addMapping(ACTION_BACKWARD, new KeyTrigger(KeyInput.KEY_S));
        inputManager.addMapping(ACTION_JUMP, new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping(ACTION_FIRE, new KeyTrigger(KeyInput.KEY_LCONTROL));

        inputManager.addMapping(ACTION_FLY, new KeyTrigger(KeyInput.KEY_F));
        inputManager.addMapping(ACTION_FLY_UP, new KeyTrigger(KeyInput.KEY_UP));
        inputManager.addMapping(ACTION_FLY_DOWN, new KeyTrigger(KeyInput.KEY_DOWN));
        inputManager.addMapping(ACTION_DEBUG_CHUNK, new KeyTrigger(KeyInput.KEY_C));

        if (!inputManager.hasMapping(ACTION_SWITCH_MOUSELOCK)) {
            inputManager.addMapping(ACTION_SWITCH_MOUSELOCK, new KeyTrigger(KeyInput.KEY_BACK));
        }

        inputManager.addListener(this, ACTIONS);
    }

    public void deleteKeyMappings() {
        for (String action : ACTIONS) {
            inputManager.deleteMapping(action);
        }
        inputManager.removeListener(this);
    }

    public void actionSwitchMouseLock(boolean isPressed) {
        if (isPressed) {
            isMouselocked = !isMouselocked;
        }
        if (isMouselocked) {
            playerState.getPlayerActionButtons().hideControlButtons();
        } else {
            playerState.getPlayerActionButtons().showControlButtons();
        }
    }

    public void actionDebugChunks(boolean isPressed) {
        if (isPressed) {
            config.setDebugChunks(!config.isDebugChunks());
        }
    }

}

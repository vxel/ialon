package org.delaunois.ialon;

import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.TouchInput;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.TouchTrigger;

public class IalonKeyMapping {

    public static final String ACTION_LEFT = "left";
    public static final String ACTION_RIGHT = "right";
    public static final String ACTION_FORWARD = "forward";
    public static final String ACTION_BACKWARD = "backward";
    public static final String ACTION_JUMP = "jump";
    public static final String ACTION_FIRE = "fire";
    public static final String ACTION_FLY = "fly";
    public static final String ACTION_FLY_UP = "fly_up";
    public static final String ACTION_FLY_DOWN = "fly_down";

    public static final String ACTION_LOOK_LEFT = "look-left";
    public static final String ACTION_LOOK_RIGHT = "look-right";
    public static final String ACTION_LOOK_UP = "look-up";
    public static final String ACTION_LOOK_DOWN = "look-down";

    public static final String ACTION_TOGGLE_TIME_RUN = "toggle-time-run";
    public static final String ACTION_ADD_BLOCK = "add-block";
    public static final String ACTION_REMOVE_BLOCK = "remove-block";
    public static final String ACTION_DEBUG_CHUNK = "debug-chunk";
    public static final String ACTION_SWITCH_MOUSELOCK = "switch-mouselock";

    public static final String TOUCH = "touch";

    private IalonKeyMapping() {
        // Prevent instanciation
    }

    public static void setup(InputManager inputManager) {
        inputManager.addMapping(ACTION_LOOK_LEFT, new MouseAxisTrigger(MouseInput.AXIS_X, true));
        inputManager.addMapping(ACTION_LOOK_RIGHT, new MouseAxisTrigger(MouseInput.AXIS_X, false));
        inputManager.addMapping(ACTION_LOOK_UP, new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        inputManager.addMapping(ACTION_LOOK_DOWN, new MouseAxisTrigger(MouseInput.AXIS_Y, true));

        inputManager.addMapping(ACTION_TOGGLE_TIME_RUN, new KeyTrigger(KeyInput.KEY_P));
        inputManager.addMapping(ACTION_ADD_BLOCK, new KeyTrigger(KeyInput.KEY_RETURN));
        inputManager.addMapping(ACTION_REMOVE_BLOCK, new KeyTrigger(KeyInput.KEY_DELETE));
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
        inputManager.addMapping(ACTION_SWITCH_MOUSELOCK, new KeyTrigger(KeyInput.KEY_BACK));

        inputManager.addMapping(TOUCH, new TouchTrigger(TouchInput.ALL));
    }


}

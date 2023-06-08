package org.delaunois.ialon.control;

import com.jme3.input.InputManager;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.TouchListener;
import com.jme3.input.event.TouchEvent;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;

import org.delaunois.ialon.RotationHelper;
import org.delaunois.ialon.IalonConfig;

import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.IalonKeyMapping.ACTION_LOOK_DOWN;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_LOOK_LEFT;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_LOOK_RIGHT;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_LOOK_UP;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_RESET_CAM;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_SWITCH_MOUSELOCK;
import static org.delaunois.ialon.IalonKeyMapping.TOUCH;

@Slf4j
public class PlayerHeadDirectionControl extends AbstractControl implements ActionListener, AnalogListener, TouchListener {

    private static final String[] ACTIONS = new String[]{
            ACTION_SWITCH_MOUSELOCK,
            ACTION_LOOK_LEFT,
            ACTION_LOOK_RIGHT,
            ACTION_LOOK_UP,
            ACTION_LOOK_DOWN,
            ACTION_RESET_CAM,
            TOUCH
    };

    private boolean isMouselocked = false;

    private final IalonConfig config;
    private final InputManager inputManager;
    private final RotationHelper rotationHelper;
    private final Vector3f camDir = new Vector3f();
    private final Vector3f up = new Vector3f();
    private final Vector3f left = new Vector3f();
    private final Vector3f dir = new Vector3f();
    private final float miny;
    private final float maxy;
    private final float minx;
    private final float maxx;
    private final Camera camera;

    public PlayerHeadDirectionControl(IalonConfig config, InputManager inputManager, Camera camera) {
        this.config = config;
        this.inputManager = inputManager;
        this.rotationHelper = new RotationHelper();
        config.getInputActionManager().addListener(this, ACTIONS);
        this.miny = 130;
        this.maxy = camera.getHeight() - 250f;
        this.minx = 0;
        this.maxx = camera.getWidth() - 200f;
        this.camera = camera;
    }

    @Override
    protected void controlUpdate(float tpf) {
        camera.getDirection(camDir);
        camDir.normalizeLocal();
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }

    public Camera getCamera() {
        return camera;
    }

    public Vector3f getCamDir() {
        return camDir;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);

        if (enabled) {
            inputManager.addListener(this, ACTIONS);

        } else {
            inputManager.removeListener(this);
        }
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_SWITCH_MOUSELOCK.equals(name) && isPressed) {
            isMouselocked = !isMouselocked;
        } else if (ACTION_RESET_CAM.equals(name) && !isPressed) {
            getSpatial().getLocalRotation().loadIdentity();
            camera.getRotation().loadIdentity();
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
            getUp(spatial, up);
            getLeft(spatial, left);
            getDirection(spatial, dir);

            if (ACTION_LOOK_LEFT.equals(name)) {
                rotationHelper.rotate(spatial, config.getRotationSpeed() * value, Vector3f.UNIT_Y, up, left, dir);

            } else if (ACTION_LOOK_RIGHT.equals(name)) {
                rotationHelper.rotate(spatial, -config.getRotationSpeed() * value, Vector3f.UNIT_Y, up, left, dir);

            } else if (ACTION_LOOK_UP.equals(name)) {
                rotationHelper.rotate(spatial, -config.getRotationSpeed() * value, left, up, left, dir);

            } else if (ACTION_LOOK_DOWN.equals(name)) {
                rotationHelper.rotate(spatial, config.getRotationSpeed() * value, left, up, left, dir);
            }
        }
    }

    private void getUp(Spatial spatial, Vector3f store) {
        spatial.getLocalRotation().getRotationColumn(1, store);
    }

    private void getLeft(Spatial spatial, Vector3f store) {
        spatial.getLocalRotation().getRotationColumn(0, store);
    }

    private void getDirection(Spatial spatial, Vector3f store) {
        spatial.getLocalRotation().getRotationColumn(2, store);
    }

    @Override
    public void onTouch(String name, TouchEvent event, float tpf) {
        if (event.getType() == TouchEvent.Type.MOVE) {
            if (event.getY() > miny && event.getY() < maxy
                    && event.getX() > minx && event.getX() < maxx) {
                getUp(spatial, up);
                getLeft(spatial, left);
                getDirection(spatial, dir);
                rotationHelper.rotate(spatial, -event.getDeltaX() * config.getRotationSpeed() / 400, Vector3f.UNIT_Y, up, left, dir);

                getUp(spatial, up);
                getLeft(spatial, left);
                getDirection(spatial, dir);
                rotationHelper.rotate(spatial, -event.getDeltaY() * config.getRotationSpeed() / 400, left, up, left, dir);
            }
            event.setConsumed();
        }
    }

}

package org.delaunois.ialon.control;

import com.jme3.input.controls.ActionListener;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;

import org.delaunois.ialon.IalonConfig;

import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.IalonKeyMapping.ACTION_BACKWARD;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_FLY_DOWN;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_FLY_UP;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_FORWARD;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_JUMP;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_LEFT;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_RIGHT;

@Slf4j
public class PlayerFlyControl extends AbstractControl implements ActionListener {

    private PlayerControl playerControl = null;
    private final IalonConfig config;

    private boolean left = false;
    private boolean right = false;
    private boolean forward = false;
    private boolean backward = false;
    private boolean up = false;
    private boolean down = false;
    private boolean jumpAndForward = false;
    private boolean jumpAndBackward = false;
    private boolean jump = false;
    private final Camera camera;
    private final Vector3f camDir = new Vector3f();
    private final Vector3f camLeft = new Vector3f();
    private final Vector3f move = new Vector3f();

    public static final String[] ACTIONS = new String[]{
            ACTION_LEFT,
            ACTION_RIGHT,
            ACTION_FORWARD,
            ACTION_BACKWARD,
            ACTION_FLY_UP,
            ACTION_FLY_DOWN,
            ACTION_JUMP
    };

    public PlayerFlyControl(IalonConfig config, Camera camera) {
        this.config = config;
        this.camera = camera;
    }

    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        playerControl = spatial.getControl(PlayerControl.class);
        assert (playerControl != null);
        this.setEnabled(config.isPlayerStartFly());
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (spatial != null) {
            camera.getDirection(camDir);
            camera.getLeft(camLeft);
            move.set(playerControl.getWalkDirection());
            updateFlyMove(move);
            playerControl.setWalkDirection(move);
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }

    private void updateFlyMove(Vector3f move) {
        if (left) {
            move.addLocal(camLeft.x, 0, camLeft.z);
        }
        if (right) {
            move.addLocal(-camLeft.x, 0, -camLeft.z);
        }
        if (forward && !jumpAndForward) {
            move.addLocal(camDir.x, 0, camDir.z);
        }
        if (backward && !jumpAndBackward) {
            move.addLocal(-camDir.x, 0, -camDir.z);
        }
        if ((jumpAndForward || up) && spatial.getWorldTranslation().y <= config.getMaxy()) {
            move.addLocal(0, 1, 0);
        }
        if (jumpAndBackward || down) {
            move.addLocal(0, -1, 0);
        }
        move.normalizeLocal().multLocal(config.getPlayerFlySpeed());
    }

    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);

        config.setPlayerStartFly(enabled);
        if (playerControl == null) {
            return;
        }

        move.zero();
        if (enabled) {
            log.info("Flying");
            config.getInputActionManager().addListener(this, ACTIONS);
            playerControl.setFallSpeed(0);
        } else {
            log.info("Not Flying");
            config.getInputActionManager().removeListener(this);
            if (playerControl.isUnderWater()) {
                playerControl.setFallSpeed(config.getWaterGravity());
            } else {
                playerControl.setFallSpeed(config.getGroundGravity());
            }
        }
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (log.isDebugEnabled()) {
            log.debug("Action {} isPressed {}", name, isPressed);
        }

        switch (name) {
            case ACTION_LEFT:
                left = isPressed;
                break;
            case ACTION_RIGHT:
                right = isPressed;
                break;
            case ACTION_FORWARD:
                forward = isPressed;
                jumpAndForward = jump && forward;
                break;
            case ACTION_BACKWARD:
                backward = isPressed;
                jumpAndBackward = jump && backward;
                break;
            case ACTION_FLY_UP:
                up = isPressed;
                break;
            case ACTION_FLY_DOWN:
                down = isPressed;
                break;
            case ACTION_JUMP:
                jump = isPressed;
                jumpAndForward = jump && forward;
                jumpAndBackward = jump && backward;
                break;
            default:
        }
    }

}

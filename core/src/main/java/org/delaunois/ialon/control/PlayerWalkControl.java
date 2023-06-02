package org.delaunois.ialon.control;

import com.jme3.input.controls.ActionListener;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;
import com.rvandoosselaer.blocks.Block;

import org.delaunois.ialon.IalonConfig;

import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.IalonKeyMapping.ACTION_BACKWARD;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_FORWARD;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_JUMP;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_LEFT;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_RIGHT;

@Slf4j
public class PlayerWalkControl extends AbstractControl implements ActionListener {

    private PlayerControl playerControl = null;
    private final IalonConfig config;

    private boolean left = false;
    private boolean right = false;
    private boolean forward = false;
    private boolean backward = false;

    private final Camera camera;
    private final Vector3f camDir = new Vector3f();
    private final Vector3f camLeft = new Vector3f();
    private final Vector3f move = new Vector3f();

    public static final String[] ACTIONS = new String[]{
            ACTION_LEFT,
            ACTION_RIGHT,
            ACTION_FORWARD,
            ACTION_BACKWARD,
            ACTION_JUMP
    };

    public PlayerWalkControl(IalonConfig config, Camera camera) {
        this.config = config;
        this.camera = camera;
    }

    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        playerControl = spatial.getControl(PlayerControl.class);
        assert(playerControl != null);
        this.setEnabled(!config.isPlayerStartFly());
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (spatial != null) {
            camera.getDirection(camDir);
            camera.getLeft(camLeft);

            if (playerControl.isOnScale()) {
                playerControl.setFallSpeed(0);
            }

            if (playerControl.isUnderWater()) {
                // In water, we don't need to climb stairs, just swim ;-)
                // Setting step height to a low value prevents a bug in bullet
                // that makes the character fall with a different speed below
                // the stepHeight. This bug is noticeable especially under water.
                playerControl.getCharacter().setStepHeight(0.03f);
                playerControl.setFallSpeed(config.getWaterGravity());
                playerControl.setJumpSpeed(config.getWaterJumpSpeed());
            }

            move.set(playerControl.getWalkDirection());
            updateWalkMove(move);
            playerControl.setWalkDirection(move);
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }

    private void updateWalkMove(Vector3f move) {
        if (left) {
            move.addLocal(camLeft.x, camLeft.y, camLeft.z);
        }
        if (right) {
            move.addLocal(-camLeft.x, -camLeft.y, -camLeft.z);
        }
        if (forward) {
            move.addLocal(camDir.x, camDir.y, camDir.z);
        }
        if (backward) {
            move.addLocal(-camDir.x, -camDir.y, -camDir.z);
        }
        move.normalizeLocal().multLocal(config.getPlayerMoveSpeed());
    }

    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        move.zero();
        if (playerControl == null) {
            return;
        }

        if (enabled) {
            log.info("Walking");
            config.getInputActionManager().addListener(this, ACTIONS);
            playerControl.getCharacter().setStepHeight(config.getPlayerStepHeight());
            playerControl.setFallSpeed(config.getGroundGravity());
            playerControl.setJumpSpeed(config.getJumpSpeed());

        } else {
            log.info("Not Walking");
            config.getInputActionManager().removeListener(this);
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
                break;
            case ACTION_BACKWARD:
                backward = isPressed;
                break;
            case ACTION_JUMP:
                jump();
                break;
            default:
        }
    }

    /**
     * Make the player jump
     */
    public void jump() {
        // Do not jump if there is a block above the player, unless it is water
        Block above = playerControl.getWorldManager().getBlock(camera.getLocation().add(0, 1, 0));
        if (above != null && above.getLiquidLevel() < 0) {
            return;
        }

        // Do not jump if the player is not on the ground, unless he is in water
        Block block = playerControl.getBlock();
        if (!playerControl.onGround() && (block == null || block.getLiquidLevel() != Block.LIQUID_FULL)) {
            return;
        }

        // Adjust jump strength according to space available above the player
        Block aboveAbove = playerControl.getWorldManager().getBlock(camera.getLocation().add(0, 2, 0));
        if (aboveAbove == null) {
            playerControl.jump();
        } else {
            float availableJumpSpace = ((int)camera.getLocation().y) + 2 - camera.getLocation().y;
            // Hack to avoid bug when jumping and touching a block above
            // Still does not work when being on half-blocks
            playerControl.setJumpSpeed(config.getJumpSpeed() * availableJumpSpace * 0.4f);
            playerControl.jump();
            playerControl.setJumpSpeed(config.getJumpSpeed());
        }
    }

}

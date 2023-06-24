package org.delaunois.ialon.control;

import com.jme3.input.controls.ActionListener;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
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

    private PlayerCharacterControl playerCharacterControl = null;
    private final IalonConfig config;

    private boolean left = false;
    private boolean right = false;
    private boolean forward = false;
    private boolean backward = false;
    private Spatial head;

    private final Vector3f camDir = new Vector3f();
    private final Vector3f camLeft = new Vector3f();
    private final Vector3f move = new Vector3f();

    private static final String[] ACTIONS = new String[]{
            ACTION_LEFT,
            ACTION_RIGHT,
            ACTION_FORWARD,
            ACTION_BACKWARD,
            ACTION_JUMP
    };

    public PlayerWalkControl(IalonConfig config) {
        this.config = config;
    }

    @Override
    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        playerCharacterControl = spatial.getControl(PlayerCharacterControl.class);
        Spatial body = ((Node) spatial).getChild(0);
        head = ((Node) body).getChild(0);
        assert playerCharacterControl != null;
        assert head != null;
        this.setEnabled(!config.isPlayerStartFly());
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (spatial != null) {
            head.getWorldRotation().getRotationColumn(2, camDir);
            head.getWorldRotation().getRotationColumn(0, camLeft);

            move.set(playerCharacterControl.getWalkDirection());
            updateWalkMove(move);
            playerCharacterControl.setWalkDirection(move);
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

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        move.zero();
        if (playerCharacterControl == null) {
            return;
        }

        if (enabled) {
            log.info("Walking");
            config.getInputActionManager().addListener(this, ACTIONS);
            playerCharacterControl.getCharacter().setStepHeight(config.getPlayerStepHeight());
            playerCharacterControl.setFallSpeed(config.getGroundGravity());
            playerCharacterControl.setJumpSpeed(config.getJumpSpeed());

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
        Block above = playerCharacterControl.getWorldManager().getBlock(head.getWorldTranslation().add(0, 1, 0));
        if (above != null && above.getLiquidLevel() < 0) {
            return;
        }

        // Do not jump if the player is not on the ground, unless he is in water
        Block block = playerCharacterControl.getBlock();
        if (!playerCharacterControl.onGround() && (block == null || block.getLiquidLevel() != Block.LIQUID_FULL)) {
            return;
        }

        // Adjust jump strength according to space available above the player
        Block aboveAbove = playerCharacterControl.getWorldManager().getBlock(head.getWorldTranslation().add(0, 2, 0));
        if (aboveAbove == null) {
            playerCharacterControl.jump();
        } else {
            float availableJumpSpace = ((int)head.getWorldTranslation().y) + 2 - head.getWorldTranslation().y;
            // Hack to avoid bug when jumping and touching a block above
            // Still does not work when being on half-blocks
            playerCharacterControl.setJumpSpeed(config.getJumpSpeed() * availableJumpSpace * 0.4f);
            playerCharacterControl.jump();
            playerCharacterControl.setJumpSpeed(config.getJumpSpeed());
        }
    }

}

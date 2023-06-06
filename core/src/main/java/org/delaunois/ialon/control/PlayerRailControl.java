package org.delaunois.ialon.control;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.ShapeIds;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.WorldManager;

import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.TypeIds.RAIL;
import static com.rvandoosselaer.blocks.TypeIds.RAIL_CURVED;
import static com.rvandoosselaer.blocks.TypeIds.RAIL_SLOPE;

@Slf4j
public class PlayerRailControl extends AbstractControl {

    private static final Vector3f SOUTH = new Vector3f(0, 0, 1);
    private static final Vector3f EAST = new Vector3f(1, 0, 0);
    private static final Vector3f SE = new Vector3f(1, 0, 1);
    private static final Vector3f NE = new Vector3f(1, 0, -1);
    private static final Vector3f WP_NORTH = new Vector3f(0, 0, -0.5f);
    private static final Vector3f WP_SOUTH = new Vector3f(0, 0, 0.5f);
    private static final Vector3f WP_WEST = new Vector3f(-0.5f, 0, 0);
    private static final Vector3f WP_EAST = new Vector3f(0.5f, 0, 0);

    private float speed = 0;
    private float acceleration = 0;
    private Spatial body;
    private Spatial head;

    private final Vector3f oldPlayerBlockCenterLocation = new Vector3f();
    private final Vector3f playerBlockCenterLocation = new Vector3f();
    private final Vector3f playerBlockBelowCenterLocation = new Vector3f();
    private final Vector3f newRailDirection = new Vector3f();
    private final Quaternion tmpQuaternion = new Quaternion();
    private final float[] angles = new float[3];
    private final Vector3f move = new Vector3f();
    private final Vector3f currentDirection = new Vector3f();
    private final Vector3f railDirection = new Vector3f();
    private final Vector3f headDir = new Vector3f();
    private final Vector3f playerLocation = new Vector3f();

    private final IalonConfig config;
    private final WorldManager worldManager;

    private PlayerCharacterControl playerCharacterControl = null;

    public PlayerRailControl(IalonConfig config, WorldManager worldManager) {
        this.config = config;
        this.worldManager = worldManager;
    }

    @Override
    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        playerCharacterControl = spatial.getControl(PlayerCharacterControl.class);
        body = ((Node) spatial).getChild(0);
        head = ((Node) body).getChild(0);
        assert playerCharacterControl != null;
        assert body != null;
        assert head != null;
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (spatial == null) {
            return;
        }

        if (playerCharacterControl.getBlock() != null || !Vector3f.ZERO.equals(railDirection)) {
            playerLocation.set(spatial.getWorldTranslation());
            playerBlockCenterLocation.set(playerCharacterControl.getPlayerBlockCenterLocation());
            oldPlayerBlockCenterLocation.set(playerCharacterControl.getOldPlayerBlockCenterLocation());

            currentDirection.set(playerCharacterControl.getWalkDirection()).setY(0).normalizeLocal();
            move.zero();
            updateRailMove(currentDirection, playerCharacterControl.getBlock(), tpf);

            if (move.lengthSquared() > 0) {
                playerCharacterControl.setWalkDirection(move);
            }
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }

    private void updateRailMove(Vector3f currentDirection, Block block, float tpf) {
        if (block != null) {
            if (RAIL_CURVED.equals(block.getType())) {
                updateRailCurvedMove(currentDirection, block, tpf);
            } else if (block.getType().startsWith(RAIL)) {
                // RAIL or RAIL_SLOPE
                updateRailStraightMove(currentDirection, block, tpf);
            } else if (!railDirection.equals(Vector3f.ZERO)) {
                log.info("No more rail block below");
                stop();
            }

        } else {
            // Not inside a rail block but moving on a rail :
            // check if the block below is a rail slope
            playerBlockBelowCenterLocation.set(playerBlockCenterLocation).addLocal(0, -1, 0);
            Block blockBelow = worldManager.getBlock(playerBlockBelowCenterLocation);
            if (blockBelow != null && RAIL_SLOPE.equals(blockBelow.getType())) {
                updateRailStraightMove(currentDirection, blockBelow, tpf);
            } else {
                log.info("No more rail block below");
                stop();
            }
        }
    }

    private void updateRailStraightMove(Vector3f currentDirection, Block block, float tpf) {
        if (railDirection.equals(Vector3f.ZERO)) {
            alignBodyToHead();
            // No current direction, infer from current move or head direction
            if (currentDirection.lengthSquared() > 0.0f) {
                log.info("Infer new direction from move {}", currentDirection);
                updateRailDirection(computeRailStraightDirection(block, currentDirection));
            } else {
                head.getLocalRotation().getRotationColumn(2, headDir).setY(0).normalizeLocal();
                log.info("Infer new direction from head direction {}", headDir);
                updateRailDirection(computeRailStraightDirection(block, headDir));
            }

        } else if (!oldPlayerBlockCenterLocation.equals(playerBlockCenterLocation)) {
            // Compute new way point
            updateRailDirection(computeRailStraightDirection(block, railDirection));
        }

        updateMoveAndRotation(tpf);
        updateSpeed(tpf);
    }

    private void updateRailCurvedMove(Vector3f currentDirection, Block block, float tpf) {
        if (railDirection.equals(Vector3f.ZERO)) {
            alignBodyToHead();
            // No current direction, infer from current move or head direction
            if (currentDirection.lengthSquared() > 0.0f) {
                log.info("Infer new direction from move {}", currentDirection);
                updateRailDirection(computeRailCurvedDirection(block, currentDirection));
            } else {
                head.getLocalRotation().getRotationColumn(2, headDir).setY(0).normalizeLocal();
                log.info("Infer new direction from head direction {}", headDir);
                updateRailDirection(computeRailCurvedDirection(block, headDir));
            }

        } else if (!oldPlayerBlockCenterLocation.equals(playerBlockCenterLocation)) {
            // Compute new way point
            updateRailDirection(computeRailCurvedDirection(block, railDirection));
        }

        updateMoveAndRotation(tpf);
    }

    private void updateRailDirection(Vector3f direction) {
        acceleration = -direction.y * 2;
        railDirection.set(direction);
        railDirection.addLocal(playerBlockCenterLocation);
        railDirection.subtractLocal(playerLocation);
        railDirection.normalizeLocal();
    }

    private Vector3f computeRailStraightDirection(Block block, Vector3f currentDirection) {
        float dotSouth = currentDirection.dot(SOUTH);
        float dotEast = currentDirection.dot(EAST);
        switch (block.getShape()) {
            case ShapeIds.WEDGE_NORTH:
                newRailDirection.set(0, dotSouth > 0 ? 0.5f : -0.5f, 0.5f * sign(dotSouth));
                break;
            case ShapeIds.WEDGE_SOUTH:
                newRailDirection.set(0, dotSouth < 0 ? 0.5f : -0.5f, 0.5f * sign(dotSouth));
                break;
            case ShapeIds.SQUARE_HS:
                newRailDirection.set(0, 0, 0.5f * sign(dotSouth));
                break;
            case ShapeIds.WEDGE_WEST:
                newRailDirection.set(0.5f * sign(dotEast), dotEast > 0 ? 0.5f : -0.5f, 0);
                break;
            case ShapeIds.WEDGE_EAST:
                newRailDirection.set(0.5f * sign(dotEast), dotEast < 0 ? 0.5f : -0.5f, 0);
                break;
            case ShapeIds.SQUARE_HE:
                newRailDirection.set(0.5f * sign(dotEast), 0, 0);
                break;
            default:
                // Illegal block
                break;
        }
        log.info("Computed new rail direction {}", newRailDirection);
        return newRailDirection;
    }

    private Vector3f computeRailCurvedDirection(Block block, Vector3f currentDirection) {
        float dotSE = currentDirection.dot(SE);
        float dotNE = currentDirection.dot(NE);
        switch (block.getShape()) {
            case ShapeIds.SQUARE_HW:
                // case \ HW:¨|, possible waypoints : S or W
                newRailDirection.set(dotSE > 0 ? WP_SOUTH : WP_WEST);
                break;
            case ShapeIds.SQUARE_HE: // ok
                // case \ HE:|_, possible waypoints : N or E
                newRailDirection.set(dotSE > 0 ? WP_EAST : WP_NORTH);
                break;
            case ShapeIds.SQUARE_HS: // ok
                // case / HS:_|,  possible waypoints : W or N
                newRailDirection.set(dotNE > 0 ? WP_NORTH : WP_WEST);
                break;
            case ShapeIds.SQUARE_HN: // ok
                // case / HN:|¨, possible waypoints : S or E
                newRailDirection.set(dotNE > 0 ? WP_EAST : WP_SOUTH);
                break;
            default:
                // Illegal curve shape
                break;
        }
        log.info("Computed new rail direction {}", newRailDirection);
        return newRailDirection;
    }

    private int sign(Float f) {
        if (f < 0) {
            return -1;
        } else {
            return 1;
        }
    }

    private void updateMoveAndRotation(float tpf) {
        updateRotation(railDirection, tpf);
        move.set(railDirection.setY(0)).normalizeLocal().multLocal(speed);
    }

    /**
     * Update the rail move and camera direction.
     * The camera direction is interpolated to smoothly align to the the rail direction,
     * keeping the pitch.
     *
     * @param direction the target rail direction
     * @param tpf the time per frame
     */
    private void updateRotation(Vector3f direction, float tpf) {
        // noinspection SuspiciousNameCombination
        float yaw = FastMath.atan2(direction.x, direction.z);

        // Get existing pitch, yaw, roll
        body.getLocalRotation().toAngles(angles);

        // Keep existing pitch, no roll (never!), specify target yaw
        Quaternion targetRotation = tmpQuaternion.fromAngles(angles[0], yaw, 0);

        // Interpolate to the target rotation
        body.getLocalRotation().slerp(targetRotation, config.getRotationSpeedRail() * tpf);
    }


    public void stop() {
        log.info("Stop rail move");
        speed = config.getPlayerRailSpeed();
        acceleration = 0;
        newRailDirection.set(0, 0, 0);
        railDirection.set(0, 0, 0);
        alignBodyToHead();
    }

    private void alignBodyToHead() {
        Vector3f dir = new Vector3f();
        head.getWorldRotation().getRotationColumn(2, dir);
        head.getWorldRotation().toAngles(angles);

        // Keep existing body yaw, no pitch, no roll
        // noinspection SuspiciousNameCombination
        tmpQuaternion.fromAngles(0, FastMath.atan2(dir.x, dir.z), 0);
        body.setLocalRotation(tmpQuaternion);

        // Keep existing head pitch, no yaw, no roll
        tmpQuaternion.fromAngles(angles[0], 0, 0);
        head.setLocalRotation(tmpQuaternion);
    }

    private void updateSpeed(float tpf) {
        if (acceleration == 0 && speed < config.getPlayerRailSpeed()) {
            acceleration = config.getPlayerRailFriction() + 0.1f;
        }

        speed *= (1 + (acceleration - config.getPlayerRailFriction()) * tpf);
        if (speed < config.getPlayerRailSpeed() * 0.5f) {
            // Min speed = rail speed / 2
            speed = config.getPlayerRailSpeed() * 0.5f;
        } else if (speed > config.getPlayerRailSpeed() * 4f) {
            // Max speed = rail speed * 4
            speed = config.getPlayerRailSpeed() * 4f;
        }

        log.info("Speed:" + speed + " accel:" + acceleration);
    }

}

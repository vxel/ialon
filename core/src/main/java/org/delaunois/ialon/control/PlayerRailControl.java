package org.delaunois.ialon.control;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
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
    private static final float SPEED_FACTOR_SLOPE = FastMath.sqrt(0.5f);

    private float speed = 0;
    private float speedFactor = 1f;
    private float acceleration = 0;

    private final Vector3f oldPlayerBlockCenterLocation = new Vector3f();
    private final Vector3f playerBlockCenterLocation = new Vector3f();
    private final Vector3f playerBlockBelowCenterLocation = new Vector3f();
    private final Vector3f waypoint = new Vector3f();
    private final Quaternion tmpQuaternion = new Quaternion();
    private final float[] angles = new float[3];
    private final Vector3f move = new Vector3f();
    private final Vector3f railDirection = new Vector3f();
    private final Vector3f camDir = new Vector3f();
    private final Vector3f playerLocation = new Vector3f();

    private final IalonConfig config;
    private final WorldManager worldManager;
    private final Camera camera;

    private PlayerCharacterControl playerCharacterControl = null;

    public PlayerRailControl(IalonConfig config, WorldManager worldManager, Camera camera) {
        this.config = config;
        this.worldManager = worldManager;
        this.camera = camera;

    }

    @Override
    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        playerCharacterControl = spatial.getControl(PlayerCharacterControl.class);
        assert(playerCharacterControl != null);
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (spatial != null) {
            camera.getDirection(camDir);
            playerLocation.set(spatial.getWorldTranslation());
            playerBlockCenterLocation.set(playerCharacterControl.getPlayerBlockCenterLocation());
            oldPlayerBlockCenterLocation.set(playerCharacterControl.getOldPlayerBlockCenterLocation());

            move.zero();
            updateRailMove(move, playerCharacterControl.getBlock(), tpf);

            move.addLocal(playerCharacterControl.getWalkDirection());
            playerCharacterControl.setWalkDirection(move);
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }

    private void updateRailMove(Vector3f move, Block block, float tpf) {
        if (block != null) {
            if (RAIL_CURVED.equals(block.getType())) {
                updateRailCurvedMove(move, block, tpf);
                move.normalizeLocal().multLocal(speed * speedFactor);
                return;
            } else if (block.getType().startsWith(RAIL)) {
                // RAIL or RAIL_SLOPE
                updateRailStraightMove(move, block, tpf);
                move.normalizeLocal().multLocal(speed * speedFactor);
                return;
            }
        }

        if (!Vector3f.ZERO.equals(railDirection)) {
            // Not inside a rail block but moving on a rail :
            // check if the block below is a rail slope
            playerBlockBelowCenterLocation.set(playerBlockCenterLocation).addLocal(0, -1, 0);
            Block blockBelow = worldManager.getBlock(playerBlockBelowCenterLocation);
            if (blockBelow != null && RAIL_SLOPE.equals(blockBelow.getType())) {
                updateRailStraightMove(move, blockBelow, tpf);
                move.normalizeLocal().multLocal(speed * speedFactor);
                return;
            } else {
                log.info("No more rail block below");
            }
            // Other cases : stop rail move
            stop();
        }

    }

    public void stop() {
        log.info("Stop rail move");
        speed = config.getPlayerRailSpeed();
        speedFactor = 1;
        acceleration = 0;
        waypoint.set(0, 0, 0);
        railDirection.set(0, 0, 0);
    }

    private void updateRailStraightMove(Vector3f move, Block block, float tpf) {
        if (railDirection.equals(Vector3f.ZERO)) {
            // No current direction, infer from current move or camera direction
            if (move.lengthSquared() > 0.0f) {
                log.info("Getting rail direction from move {}", move);
                computeRailStraightWaypoint(block, move);
            } else {
                log.info("Getting rail direction from camera {}", camDir);
                computeRailStraightWaypoint(block, camDir);
            }
            railDirection.set(waypoint).subtractLocal(playerLocation).setY(0).normalizeLocal();

        } else if (!oldPlayerBlockCenterLocation.equals(playerBlockCenterLocation)) {
            // Compute new way point
            computeRailStraightWaypoint(block, railDirection);
            railDirection.set(waypoint).subtractLocal(playerLocation).setY(0).normalizeLocal();
        }

        // All cases : update speed and direction
        updateSpeed(tpf);
        updateRailMoveDirection(railDirection, tpf);
    }

    private void updateRailCurvedMove(Vector3f move, Block block, float tpf) {
        if (railDirection.equals(Vector3f.ZERO)) {
            // No current direction, infer from current move or camera direction
            if (move.lengthSquared() > 0.0f) {
                log.info("Getting rail direction from move {}", move);
                computeRailCurvedWaypoint(block, move);
            } else {
                log.info("Getting rail direction from camera {}", camDir);
                computeRailCurvedWaypoint(block, camDir);
            }
            railDirection.set(waypoint).subtractLocal(playerLocation).setY(0).normalizeLocal();

        } else if (!oldPlayerBlockCenterLocation.equals(playerBlockCenterLocation)) {
            // Compute new way point
            computeRailCurvedWaypoint(block, railDirection);
            railDirection.set(waypoint).subtractLocal(playerLocation).setY(0).normalizeLocal();
        }

        updateRailMoveDirection(railDirection, tpf);
    }

    /**
     * Update the rail move and camera direction.
     * The camera direction is interpolated to smoothly align to the the rail direction,
     * keeping the pitch.
     *
     * @param direction the target rail direction
     * @param tpf the time per frame
     */
    private void updateRailMoveDirection(Vector3f direction, float tpf) {
        // noinspection SuspiciousNameCombination
        float yaw = FastMath.atan2(railDirection.x, railDirection.z);

        // Get existing pitch, yaw, roll
        camera.getRotation().toAngles(angles);

        // Keep existing pitch, no roll (never!), specify target yaw
        Quaternion targetRotation = tmpQuaternion.fromAngles(angles[0], yaw, 0);

        // Interpolate to the target rotation
        camera.getRotation().slerp(targetRotation, config.getRotationSpeedRail() * tpf);
        move.addLocal(direction);
    }

    private void computeRailStraightWaypoint(Block block, Vector3f direction) {
        float dotSouth = direction.dot(SOUTH);
        float dotEast = direction.dot(EAST);
        switch (block.getShape()) {
            case ShapeIds.WEDGE_NORTH:
                speedFactor = SPEED_FACTOR_SLOPE;
                acceleration = dotSouth > 0 ? -1f : 1f;
                waypoint.set(playerBlockCenterLocation.x, playerLocation.y, playerBlockCenterLocation.z + 0.5f * Math.signum(dotSouth));
                break;
            case ShapeIds.WEDGE_SOUTH:
                speedFactor = SPEED_FACTOR_SLOPE;
                acceleration = dotSouth < 0 ? -1f : 1f;
                waypoint.set(playerBlockCenterLocation.x, playerLocation.y, playerBlockCenterLocation.z + 0.5f * Math.signum(dotSouth));
                break;
            case ShapeIds.SQUARE_HS:
                speedFactor = 1;
                acceleration = 0;
                waypoint.set(playerBlockCenterLocation.x, playerLocation.y, playerBlockCenterLocation.z + 0.5f * Math.signum(dotSouth));
                break;
            case ShapeIds.WEDGE_WEST:
                speedFactor = SPEED_FACTOR_SLOPE;
                acceleration = dotEast > 0 ? -1f : 1f;
                waypoint.set(playerBlockCenterLocation.x + 0.5f * Math.signum(dotEast), playerLocation.y, playerBlockCenterLocation.z);
                break;
            case ShapeIds.WEDGE_EAST:
                speedFactor = SPEED_FACTOR_SLOPE;
                acceleration = dotEast < 0 ? -1f : 1f;
                waypoint.set(playerBlockCenterLocation.x + 0.5f * Math.signum(dotEast), playerLocation.y, playerBlockCenterLocation.z);
                break;
            case ShapeIds.SQUARE_HE:
                speedFactor = 1;
                acceleration = 0;
                waypoint.set(playerBlockCenterLocation.x + 0.5f * Math.signum(dotEast), playerLocation.y, playerBlockCenterLocation.z);
                break;
            default:
                // Illegal block
                break;
        }
        log.info("Computed new rail straight waypoint {}", waypoint);
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

        if (log.isDebugEnabled()) {
            log.debug("Speed:" + speed + " accel:" + acceleration + " factor:" + speedFactor);
        }
    }

    private void computeRailCurvedWaypoint(Block block, Vector3f direction) {
        waypoint.set(playerBlockCenterLocation);
        switch (block.getShape()) {
            case ShapeIds.SQUARE_HW:
                // case \ HW:¨|, possible waypoints : S or W
                if (direction.dot(SE) > 0) {
                    waypoint.addLocal(WP_SOUTH);
                } else {
                    waypoint.addLocal(WP_WEST);
                }
                break;
            case ShapeIds.SQUARE_HE: // ok
                // case \ HE:|_, possible waypoints : N or E
                if (direction.dot(SE) > 0) {
                    waypoint.addLocal(WP_EAST);
                } else {
                    waypoint.addLocal(WP_NORTH);
                }
                break;
            case ShapeIds.SQUARE_HS: // ok
                // case / HS:_|,  possible waypoints : W or N
                if (direction.dot(NE) > 0) {
                    waypoint.addLocal(WP_NORTH);
                } else {
                    waypoint.addLocal(WP_WEST);
                }
                break;
            case ShapeIds.SQUARE_HN: // ok
                // case / HN:|¨, possible waypoints : S or E
                if (direction.dot(NE) > 0) {
                    waypoint.addLocal(WP_EAST);
                } else {
                    waypoint.addLocal(WP_SOUTH);
                }
                break;
            default:
                // Illegal curve shape
                break;
        }
        log.info("Computed new rail curve waypoint {}", waypoint);
    }

}

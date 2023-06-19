package org.delaunois.ialon.control;

import com.jme3.input.controls.ActionListener;
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

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.TypeIds.RAIL;
import static com.rvandoosselaer.blocks.TypeIds.RAIL_CURVED;
import static com.rvandoosselaer.blocks.TypeIds.RAIL_SLOPE;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_BACKWARD;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_FORWARD;

@Slf4j
public class PlayerRailControl extends AbstractControl implements ActionListener {

    private static final Vector3f SOUTH = new Vector3f(0, 0, 1);
    private static final Vector3f EAST = new Vector3f(1, 0, 0);
    private static final Vector3f SE = new Vector3f(1, 0, 1);
    private static final Vector3f NE = new Vector3f(1, 0, -1);
    private static final Vector3f WP_NORTH = new Vector3f(0, 0, -1f);
    private static final Vector3f WP_SOUTH = new Vector3f(0, 0, 1f);
    private static final Vector3f WP_WEST = new Vector3f(-1f, 0, 0);
    private static final Vector3f WP_EAST = new Vector3f(1f, 0, 0);

    private static final String[] ACTIONS = new String[]{
            ACTION_FORWARD,
            ACTION_BACKWARD
    };

    private boolean forward;
    private boolean backward;
    private float speed = 0;
    private float acceleration = 0;
    private Spatial body;
    private Spatial head;
    private Spatial feet;

    @Getter
    @Setter
    private Spatial wagon;

    private final Vector3f oldPlayerBlockCenterLocation = new Vector3f();
    private final Vector3f playerBlockCenterLocation = new Vector3f();
    private final Vector3f playerBlockBelowCenterLocation = new Vector3f();
    private final Quaternion tmpQuaternion = new Quaternion();
    private final float[] angles = new float[3];
    private final Vector3f move = new Vector3f();
    private final Vector3f currentDirection = new Vector3f();
    private final Vector3f railDirection = new Vector3f();
    private final Vector3f headDir = new Vector3f();
    private final Vector3f waypoint = new Vector3f();
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
        body = ((Node) spatial).getChild("Body");
        head = ((Node) body).getChild("Head");
        feet = ((Node) body).getChild("Feet");
        if (playerCharacterControl == null || head == null || feet == null) {
            throw new IllegalStateException("PlayerRailControl needs a PlayerCharacterControl attached on the spatial");
        }
        setEnabled(enabled);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (enabled) {
            config.getInputActionManager().addListener(this, ACTIONS);
        } else {
            config.getInputActionManager().removeListener(this);
            if (wagon != null && wagon.getParent() != null) {
                ((Node) body).detachChild(wagon);
            }
        }
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (log.isDebugEnabled()) {
            log.debug("Action {} isPressed {}", name, isPressed);
        }

        if (ACTION_FORWARD.equals(name)) {
            forward = isPressed;
        } else if (ACTION_BACKWARD.equals(name)) {
            backward = isPressed;
        }
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (spatial == null) {
            return;
        }

        if (!isRailBlock(playerCharacterControl.getBlock()) && Vector3f.ZERO.equals(railDirection)) {
            // Neither on a rail block nor in a rail move
            if (wagon != null && wagon.getParent() != null) {
                ((Node) body).detachChild(wagon);
            }
            return;
        }

        // Either on a rail block or in a rail move

        // Update variable cache
        playerLocation.set(playerCharacterControl.getPlayerLocation()).addLocal(feet.getLocalTranslation());
        currentDirection.set(playerCharacterControl.getWalkDirection());
        playerBlockCenterLocation.set(playerCharacterControl.getPlayerBlockCenterLocation());
        oldPlayerBlockCenterLocation.set(playerCharacterControl.getOldPlayerBlockCenterLocation());

        // Update rail direction, speed and rotation (if needed)
        updateRailDirection(currentDirection, playerCharacterControl.getBlock());
        updateRotation(railDirection, tpf);
        updateSpeed(tpf);

        // Apply move
        if (railDirection.lengthSquared() > 0) {
            if (wagon != null && wagon.getParent() == null) {
                ((Node)body).attachChild(wagon);
            }
            move.set(railDirection).setY(0).normalizeLocal().multLocal(speed);
            playerCharacterControl.setWalkDirection(move);
        }
    }

    private boolean isRailBlock(Block block) {
        return block != null
                && (RAIL_CURVED.equals(block.getType()) || block.getType().startsWith(RAIL));
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
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
            log.debug("Speed:" + speed + " accel:" + acceleration);
        }
    }

    private void updateRailDirection(Vector3f currentDirection, Block block) {
        if (isRailBlock(block)) {
            updateRailMove(currentDirection, block);
            return;
        }

        // Not inside a rail block but moving on a rail :
        // check if the block below is a rail slope
        playerBlockBelowCenterLocation.set(playerBlockCenterLocation).addLocal(0, -1, 0);
        Block blockBelow = worldManager.getBlock(playerBlockBelowCenterLocation);
        if (blockBelow != null && RAIL_SLOPE.equals(blockBelow.getType())) {
            updateRailMove(currentDirection, blockBelow);
            return;
        }

        // No longer in a rail block
        log.info("No more rail block below");
        stop();
    }

    private void updateRailMove(Vector3f currentDirection, Block block) {
        Vector3f dir = null;
        boolean onRail = !railDirection.equals(Vector3f.ZERO);
        if (onRail && forward) {
            updateHeadDirection();
            currentDirection.set(railDirection).setY(0).normalizeLocal();
            if (headDir.setY(0).normalizeLocal().dot(currentDirection) < -0.75f) {
                log.info("Reverse rail direction");
                stop();
                forward = false;
            }

        } else if (onRail && backward) {
            updateHeadDirection();
            currentDirection.set(railDirection).setY(0).normalizeLocal();
            if (headDir.setY(0).normalizeLocal().dot(currentDirection) > 0.75f) {
                log.info("Reverse rail direction");
                stop();
                backward = false;
            }

        } else if (!onRail) {
            // No current direction, infer from current move or head direction
            updateHeadDirection();
            dir = currentDirection.lengthSquared() > 0.0f ? currentDirection : headDir;
            alignBodyToDirection(dir);

        } else if (!oldPlayerBlockCenterLocation.equals(playerBlockCenterLocation)) {
            // Compute new way point
            dir = railDirection;
        }

        if (dir != null) {
            computeRailWaypoint(block, dir, waypoint);
            acceleration = computeAcceleration(waypoint);
            waypointToDirection(playerLocation, waypoint, railDirection);
        }
    }

    private void updateHeadDirection() {
        head.getWorldRotation().getRotationColumn(2, headDir);
    }

    private float computeAcceleration(Vector3f direction) {
        return -direction.y * 2;
    }

    /**
     * Given a location and a waypoint, compute the normalized direction
     * from the location to the waypoint, and store it in the store.
     *
     * @param waypoint the waypoint
     * @param location the location
     * @param store    to store the result
     */
    private void waypointToDirection(Vector3f location, Vector3f waypoint, Vector3f store) {
        store.set(waypoint);

        // Add horizontal path correction towards the center of the rail
        store.addLocal(
                playerBlockCenterLocation.x - location.x,
                0,
                playerBlockCenterLocation.z - location.z);
    }

    /**
     * Given a rail block type and a direction, compute the next waypoint for the rail move
     * and store it in the store.
     * The waypoint is given relative to the center of the block.
     *
     * @param block     the rail block (noop if the block type is not a rail block)
     * @param direction the direction (of the current move or of the camera)
     * @param store     will receive the computed waypoint
     */
    private void computeRailWaypoint(Block block, Vector3f direction, Vector3f store) {
        if (RAIL_CURVED.equals(block.getType())) {
            // RAIL_CURVED
            computeRailCurvedWaypoint(block, direction, store);

        } else if (block.getType().startsWith(RAIL)) {
            // RAIL or RAIL_SLOPE
            computeRailStraightWaypoint(block, direction, store);
        }
    }

    private void computeRailStraightWaypoint(Block block, Vector3f direction, Vector3f store) {
        float dotSouth = direction.dot(SOUTH);
        float dotEast = direction.dot(EAST);
        switch (block.getShape()) {
            case ShapeIds.WEDGE_NORTH:
                store.set(0, dotSouth > 0 ? 1f : -1f, sign(dotSouth)).normalizeLocal();
                break;
            case ShapeIds.WEDGE_SOUTH:
                store.set(0, dotSouth < 0 ? 1f : -1f, sign(dotSouth)).normalizeLocal();
                break;
            case ShapeIds.SQUARE_HS:
                store.set(0, 0, sign(dotSouth));
                break;
            case ShapeIds.WEDGE_WEST:
                store.set(sign(dotEast), dotEast > 0 ? 1f : -1f, 0).normalizeLocal();
                break;
            case ShapeIds.WEDGE_EAST:
                store.set(sign(dotEast), dotEast < 0 ? 1f : -1f, 0).normalizeLocal();
                break;
            case ShapeIds.SQUARE_HE:
                store.set(sign(dotEast), 0, 0);
                break;
            default:
                // Illegal block
                break;
        }
        if (log.isDebugEnabled()) {
            log.debug("Computed rail straight waypoint {}", store);
        }
    }

    private void computeRailCurvedWaypoint(Block block, Vector3f direction, Vector3f store) {
        float dotSE = direction.dot(SE);
        float dotNE = direction.dot(NE);
        switch (block.getShape()) {
            case ShapeIds.SQUARE_HW:
                // case \ HW:¨|, possible waypoints : S or W
                store.set(dotSE > 0 ? WP_SOUTH : WP_WEST);
                break;
            case ShapeIds.SQUARE_HE: // ok
                // case \ HE:|_, possible waypoints : N or E
                store.set(dotSE > 0 ? WP_EAST : WP_NORTH);
                break;
            case ShapeIds.SQUARE_HS: // ok
                // case / HS:_|,  possible waypoints : W or N
                store.set(dotNE > 0 ? WP_NORTH : WP_WEST);
                break;
            case ShapeIds.SQUARE_HN: // ok
                // case / HN:|¨, possible waypoints : S or E
                store.set(dotNE > 0 ? WP_EAST : WP_SOUTH);
                break;
            default:
                // Illegal curve shape
                break;
        }
        if (log.isDebugEnabled()) {
            log.debug("Computed rail curved waypoint {}", store);
        }
    }

    private int sign(Float f) {
        if (f < 0) {
            return -1;
        } else {
            return 1;
        }
    }

    /**
     * Update the camera rotation.
     * The camera direction is interpolated to smoothly align to the the rail direction,
     * keeping the pitch.
     *
     * @param direction the target rail direction
     * @param tpf       the time per frame
     */
    private void updateRotation(Vector3f direction, float tpf) {
        // noinspection SuspiciousNameCombination
        float yaw = FastMath.atan2(direction.x, direction.z);
        float pitch = -FastMath.asin((direction.dot(Vector3f.UNIT_Y)));
        tmpQuaternion.fromAngles(pitch, yaw, 0);

        // Interpolate to the target rotation
        body.getLocalRotation().slerp(tmpQuaternion, config.getRotationSpeedRail() * tpf);
    }

    public void stop() {
        log.info("Stop rail move");
        speed = config.getPlayerRailSpeed();
        acceleration = 0;

        float pitchCorrection = 0;
        if (Math.abs(railDirection.y) > 0f) {
            log.info("Stop rail move on a slope");
            pitchCorrection = FastMath.asin((railDirection.dot(Vector3f.UNIT_Y)));
        }
        railDirection.set(0, 0, 0);
        resetBodyRotation(pitchCorrection);
    }

    /**
     * Sets the body rotation to identity
     * while keeping the head world view direction
     */
    private void resetBodyRotation(float pitchCorrection) {
        if (pitchCorrection != 0) {
            body.getLocalRotation().toAngles(angles);
            angles[0] = angles[0] + pitchCorrection;
            body.setLocalRotation(tmpQuaternion.fromAngles(angles));
        }

        head.getWorldRotation().toAngles(angles);
        body.setLocalRotation(Quaternion.IDENTITY);
        head.setLocalRotation(tmpQuaternion.fromAngles(angles));
    }

    /**
     * Sets the body rotation to match the given direction
     * while the keeping the head world view rotation
     */
    private void alignBodyToDirection(Vector3f dir) {
        @SuppressWarnings("SuspiciousNameCombination")
        float yaw = FastMath.atan2(dir.x, dir.z);
        body.setLocalRotation(tmpQuaternion.fromAngles(0, yaw, 0));

        head.getLocalRotation().toAngles(angles);
        tmpQuaternion.fromAngles(angles[0], angles[1] - yaw, 0);
        head.setLocalRotation(tmpQuaternion);
    }

}

/*
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon.state;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.collision.shapes.SphereCollisionShape;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.debug.WireBox;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Direction;
import com.rvandoosselaer.blocks.Shape;
import com.rvandoosselaer.blocks.ShapeIds;
import com.rvandoosselaer.blocks.TypeIds;
import com.rvandoosselaer.blocks.shapes.CrossPlane;
import com.rvandoosselaer.blocks.shapes.Pyramid;
import com.rvandoosselaer.blocks.shapes.Stairs;
import com.rvandoosselaer.blocks.shapes.Wedge;
import com.simsilica.lemur.Label;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.ChunkLightManager;
import org.delaunois.ialon.ChunkManager;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.PlayerActionButtons;
import org.delaunois.ialon.PlayerActionListener;
import org.delaunois.ialon.PlayerListener;
import org.delaunois.ialon.PlayerTouchListener;
import org.delaunois.ialon.WorldManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.TypeIds.RAIL;
import static com.rvandoosselaer.blocks.TypeIds.RAIL_CURVED;
import static com.rvandoosselaer.blocks.TypeIds.RAIL_SLOPE;

@Slf4j
public class PlayerState extends BaseAppState {

    private static final String ALPHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";
    private static final Vector3f OFFSET = new Vector3f(0.5f, 0.5f, 0.5f);
    private static final Vector3f SOUTH = new Vector3f(0, 0, 1);
    private static final Vector3f EAST = new Vector3f(1, 0, 0);
    private static final Vector3f SE = new Vector3f(1, 0, 1);
    private static final Vector3f NE = new Vector3f(1, 0, -1);
    private static final Vector3f WP_NORTH = new Vector3f(0, 0, -0.5f);
    private static final Vector3f WP_SOUTH = new Vector3f(0, 0, 0.5f);
    private static final Vector3f WP_WEST = new Vector3f(-0.5f, 0, 0);
    private static final Vector3f WP_EAST = new Vector3f(0.5f, 0, 0);
    private static final float SPEED_FACTOR_SLOPE = FastMath.sqrt(0.5f);

    private SimpleApplication app;
    private Label crossHair;
    private Geometry addPlaceholder;
    private Geometry removePlaceholder;
    private CharacterControl player;
    private PlayerActionListener playerActionListener;
    private PlayerTouchListener playerTouchListener;

    private ExecutorService executorService;
    private Node chunkNode;

    @Getter
    private WorldManager worldManager;

    @Getter
    @Setter
    private boolean touchEnabled = true;

    @Getter
    @Setter
    private Vector3f playerLocation;

    private float speed = 0;
    private float speedFactor = 1f;
    private float acceleration = 0;

    private boolean left = false;
    private boolean right = false;
    private boolean forward = false;
    private boolean backward = false;
    private boolean up = false;
    private boolean down = false;

    @Getter
    private boolean fly = false;

    @Getter
    private boolean jump = false;

    @Getter
    private boolean underWater = false;

    @Getter
    private boolean onScale = false;

    @Getter
    private Camera camera;

    @Getter
    private PlayerActionButtons playerActionButtons;

    //Temporary vectors used on each frame.
    //They here to avoid instanciating new vectors on each frame
    private final Vector3f walkDirection = new Vector3f();
    private final Vector3f camDir = new Vector3f();
    private final Vector3f camLeft = new Vector3f();
    private final Vector3f move = new Vector3f();
    private final Vector3f railDirection = new Vector3f();
    private final Vector3f oldPlayerBlockCenterLocation = new Vector3f();
    private final Vector3f playerBlockCenterLocation = new Vector3f();
    private final Vector3f playerBlockBelowCenterLocation = new Vector3f();
    private final Vector3f waypoint = new Vector3f();
    private final Quaternion tmpQuaternion = new Quaternion();
    private final float[] angles = new float[3];
    private long lastCollisionTest = System.currentTimeMillis();
    private final List<PlayerListener> listeners = new CopyOnWriteArrayList<>();
    private final CollisionResults collisionResults = new CollisionResults();
    private final Ray ray = new Ray();

    private Material ballMaterial;
    private final IalonConfig config;

    public PlayerState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application simpleApp) {
        app = (SimpleApplication) simpleApp;
        camera = app.getCamera();

        worldManager = new WorldManager(
                config.getChunkManager(),
                new ChunkLightManager(config),
                Optional.ofNullable(app.getStateManager().getState(ChunkLiquidManagerState.class))
                        .map(ChunkLiquidManagerState::getChunkLiquidManager).orElse(null)
        );
        crossHair = createCrossHair();
        addPlaceholder = createAddPlaceholder();
        removePlaceholder = createRemovePlaceholder();
        executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("player").build());
        fly = config.isPlayerStartFly();
        playerLocation = config.getPlayerLocation();
        player = createPlayer();
        playerActionListener = new PlayerActionListener(this, config);
        playerTouchListener = new PlayerTouchListener(this, config);
        playerActionButtons = new PlayerActionButtons(this);
        updatePlayerPosition();
    }

    @Override
    protected void cleanup(Application app) {
        executorService.shutdown();
    }

    @Override
    protected void onEnable() {
        log.info("Enabling player");
        app.getGuiNode().attachChild(crossHair);
        chunkNode = (Node) app.getRootNode().getChild(IalonConfig.CHUNK_NODE_NAME);
        if (chunkNode == null) {
            throw new IllegalStateException("Chunk Node is not attached !");
        }

        player.setGravity(config.getGroundGravity());
        setFly(fly);
        playerActionListener.addKeyMappings();
        playerTouchListener.addKeyMappings();
        playerActionButtons.showControlButtons();
    }

    @Override
    protected void onDisable() {
        log.info("Disabling player");
        crossHair.removeFromParent();
        playerActionListener.deleteKeyMappings();
        playerTouchListener.deleteKeyMappings();
        playerActionButtons.hideControlButtons();
    }

    @Override
    public void update(float tpf) {
        camDir.set(camera.getDirection());
        camLeft.set(camera.getLeft());
        walkDirection.set(0, 0, 0);

        move.zero();
        playerLocation.set(player.getPhysicsLocation());

        oldPlayerBlockCenterLocation.set(playerBlockCenterLocation);
        playerBlockCenterLocation.set(
                (int)playerLocation.x + 0.5f * Math.signum(playerLocation.x),
                (int)playerLocation.y + 0.5f,
                (int)playerLocation.z + 0.5f * Math.signum(playerLocation.z));

        Block block = worldManager.getBlock(playerBlockCenterLocation);
        updateMove(move, block, tpf);
        updatePlaceholders();

        if (move.lengthSquared() > 0) {
            walkDirection.set(move);
        }

        player.setWalkDirection(walkDirection);

        updatePlayerPosition();
        updateFallSpeed(block);
    }

    private void updateMove(Vector3f move, Block block, float tpf) {
        if (fly) {
            updateFlyMove(move);
        } else {
            updateWalkMove(move);
            updateRailMove(move, block, tpf);
        }
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
            stopRailMove();
        }

    }

    private void stopRailMove() {
        speed = config.getPlayerRailSpeed();
        speedFactor = -1;
        acceleration = 0;
        waypoint.set(0, 0, 0);
        railDirection.set(0, 0, 0);
    }

    private void updateRailStraightMove(Vector3f move, Block block, float tpf) {
        if (railDirection.equals(Vector3f.ZERO)) {
            // No current direction, infer from current move or camera direction
            if (move.lengthSquared() > 0.1f) {
                computeRailStraightMove(block, move);
            } else {
                computeRailStraightMove(block, camDir);
            }
            railDirection.set(waypoint).subtractLocal(playerLocation).setY(0).normalizeLocal();

        } else if (!oldPlayerBlockCenterLocation.equals(playerBlockCenterLocation)) {
            // Compute new way point
            computeRailStraightMove(block, railDirection);
            railDirection.set(waypoint).subtractLocal(playerLocation).setY(0).normalizeLocal();
        }

        // All cases : update speed and direction
        updateSpeed(tpf);
        updateRailMoveDirection(railDirection, tpf);
    }

    private void updateRailCurvedMove(Vector3f move, Block block, float tpf) {
        if (railDirection.equals(Vector3f.ZERO)) {
            // No current direction, infer from current move or camera direction
            if (move.lengthSquared() > 0.1f) {
                computeRailCurvedMove(block, move);
            } else {
                computeRailCurvedMove(block, camDir);
            }
            railDirection.set(waypoint).subtractLocal(playerLocation).setY(0).normalizeLocal();

        } else if (!oldPlayerBlockCenterLocation.equals(playerBlockCenterLocation)) {
            // Compute new way point
            computeRailCurvedMove(block, railDirection);
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

        // Get existing roll, yaw, pitch
        camera.getRotation().toAngles(angles);

        // Keep existing pitch, no roll (never!), specify target yaw
        Quaternion targetRotation = tmpQuaternion.fromAngles(angles[0], yaw, 0);

        // Interpolate to the target rotation
        camera.getRotation().slerp(targetRotation, config.getRotationSpeedRail() * tpf);
        move.addLocal(direction);
    }

    private void computeRailStraightMove(Block block, Vector3f direction) {
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

    private void computeRailCurvedMove(Block block, Vector3f direction) {
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

    private void updateFlyMove(Vector3f move) {
        if (left) {
            move.addLocal(camLeft.x, 0, camLeft.z);
        }
        if (right) {
            move.addLocal(-camLeft.x, 0, -camLeft.z);
        }
        if (jump) {
            up = forward;
            down = backward;
        } else {
            if (forward) {
                move.addLocal(camDir.x, 0, camDir.z);
            }
            if (backward) {
                move.addLocal(-camDir.x, 0, -camDir.z);
            }
        }
        if (up && playerLocation.y <= config.getMaxy()) {
            move.addLocal(0, 1, 0);
        }
        if (down) {
            move.addLocal(0, -1, 0);
        }
        move.normalizeLocal().multLocal(config.getPlayerFlySpeed());
    }

    public void resize() {
        crossHair.setLocalTranslation((app.getCamera().getWidth() / 2f) - (crossHair.getPreferredSize().getX() / 2), (app.getCamera().getHeight() / 2f) + (crossHair.getPreferredSize().getY() / 2), crossHair.getLocalTranslation().getZ());
        playerActionButtons.resize();
    }

    public Vector3f getRemovePlaceholderPosition() {
        return removePlaceholder.getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
    }

    public Vector3f getAddPlaceholderPosition() {
        return addPlaceholder.getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
    }

    /**
     * Create the player, configure it, add it to the physic engine and position it on the scene
     * @return the created player
     */
    private CharacterControl createPlayer() {
        // We set up collision detection for the player by creating
        // a capsule collision shape and a CharacterControl.
        // The CharacterControl offers extra settings for
        // size, stepheight, jumping, falling, and gravity.
        // We also put the player in its starting position.
        CapsuleCollisionShape capsuleShape = new CapsuleCollisionShape(
                config.getPlayerRadius(),
                config.getPlayerHeight() - 2 * config.getPlayerRadius(),
                1);
        CharacterControl characterControl = new CharacterControl(capsuleShape, config.getPlayerStepHeight());
        characterControl.setJumpSpeed(config.getJumpSpeed());
        characterControl.setFallSpeed(config.getGroundGravity());
        characterControl.setGravity(0);
        characterControl.getCharacter().setMaxSlope(FastMath.PI * 0.3f);

        if (playerLocation == null) {
            playerLocation = new Vector3f(
                    config.getChunkSize() / 2f,
                    config.getTerrainGenerator().getHeight(new Vector3f(0, 0, 0)) + config.getPlayerStartHeight(),
                    config.getChunkSize() / 2f
            );
        }
        characterControl.setPhysicsLocation(playerLocation);

        app.getStateManager().getState(BulletAppState.class).getPhysicsSpace().add(characterControl);

        playerLocation.set(characterControl.getPhysicsLocation());

        return characterControl;
    }

    private Label createCrossHair() {
        Label crossHairLabel = new Label("+");
        crossHairLabel.setColor(ColorRGBA.White);
        crossHairLabel.setFontSize(30);
        crossHairLabel.getFont().getPage(0).clearParam(ALPHA_DISCARD_THRESHOLD);

        int width = camera.getWidth();
        int height = camera.getHeight();
        crossHairLabel.setLocalTranslation((width / 2f) - (crossHairLabel.getPreferredSize().getX() / 2), (height / 2f) + (crossHairLabel.getPreferredSize().getY() / 2), crossHairLabel.getLocalTranslation().getZ());
        return crossHairLabel;
    }

    private Geometry createRemovePlaceholder() {
        Material removePlaceholderMaterial = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        removePlaceholderMaterial.setColor("Color", new ColorRGBA(0.4549f, 0.851f, 1, 1));

        Geometry geometry = new Geometry("remove-placeholder", new WireBox(0.505f, 0.505f, 0.505f));
        geometry.setMaterial(removePlaceholderMaterial);
        geometry.setLocalScale(BlocksConfig.getInstance().getBlockScale());

        return geometry;
    }

    private Geometry createAddPlaceholder() {
        Geometry geometry = new Geometry("add-placeholder", new Box(0.5f, 0.5f, 0.5f));
        geometry.setLocalScale(BlocksConfig.getInstance().getBlockScale());
        return geometry;
    }

    private void updatePlayerPosition() {
        if (playerLocation.y < 1) {
            playerLocation.setY(config.getMaxy());
            player.setPhysicsLocation(playerLocation);
        }
        if (config.isDebugCollisions()) {
            camera.setLocation(playerLocation.add(-2, config.getPlayerHeight() / 2 - 0.15f, 0));

        } else {
            // The player location is at the center of the capsule shape. So we need to level the camera
            // up to set it at the top of the shape.
            camera.setLocation(playerLocation.add(0, config.getPlayerHeight() / 2 - 0.15f, 0));
        }

        listeners.forEach(listener -> listener.onMove(playerLocation));
    }

    public void actionLeft(boolean isPressed) {
        left = isPressed;
    }

    public void actionRight(boolean isPressed) {
        right = isPressed;
    }

    public void actionForward(boolean isPressed) {
        forward = isPressed;
    }

    public void actionBackward(boolean isPressed) {
        backward = isPressed;
    }

    public void actionFlyUp(boolean isPressed) {
        if (fly) {
            up = isPressed;
        }
    }

    public void actionFlyDown(boolean isPressed) {
        if (fly) {
            down = isPressed;
        }
    }

    public void actionJump(boolean isPressed) {
        jump = isPressed;
        up = false;
        down = false;
        if (jump && !fly) {
            playerJump();
        }
    }

    public void actionFireBall(boolean isPressed) {
        if (!isPressed) {
            return;
        }

        Geometry ball = new Geometry("ball", new Sphere(32, 32, 0.4f));
        ball.setMaterial(getBallMaterial());
        ball.setLocalTranslation(app.getCamera().getLocation());
        ball.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);

        RigidBodyControl bulletControl = new RigidBodyControl(new SphereCollisionShape(0.4f), 20);
        bulletControl.setLinearVelocity(app.getCamera().getDirection().mult(25));
        ball.addControl(bulletControl);

        app.getRootNode().attachChild(ball);
        app.getStateManager().getState(BulletAppState.class).getPhysicsSpace().add(bulletControl);
    }

    public void playerJump() {
        // Do not jump if the player is flying
        if (fly) {
            return;
        }

        // Do not jump if there is a block above the player, unless it is water
        Block above = worldManager.getBlock(camera.getLocation().add(0, 1, 0));
        if (above != null && above.getLiquidLevel() < 0) {
            return;
        }

        // Do not jump if the player is not on the ground, unless he is in water
        Block block = worldManager.getBlock(playerLocation);
        if (!player.onGround() && (block == null || block.getLiquidLevel() != Block.LIQUID_FULL)) {
            return;
        }

        // Adjust jump strength according to space available above the player
        Block aboveAbove = worldManager.getBlock(camera.getLocation().add(0, 2, 0));
        if (aboveAbove == null) {
            player.jump();
        } else {
            float availableJumpSpace = ((int)camera.getLocation().y) + 2 - camera.getLocation().y;
            // Hack to avoid bug when jumping and touching a block above
            // Still does not work when being on half-blocks
            player.setJumpSpeed(config.getJumpSpeed() * availableJumpSpace * 0.4f);
            player.jump();
            player.setJumpSpeed(config.getJumpSpeed());
        }
    }

    public void toogleFly(boolean isPressed) {
        if (isPressed) {
            setFly(!fly);
        }
    }

    public void setFly(boolean fly) {
        this.fly = fly;
        config.setPlayerStartFly(fly);
        if (player == null) {
            return;
        }

        if (fly) {
            log.info("Flying");
            player.setFallSpeed(0);
            stopRailMove();
        } else {
            log.info("Not Flying");
            if (underWater) {
                player.setFallSpeed(config.getWaterGravity());
            } else {
                player.setFallSpeed(config.getGroundGravity());
            }
        }
    }

    public void addBlock() {
        log.info("Action : addBlock triggered");

        if (removePlaceholder.getParent() == null) {
            log.info("Not adding. No parent for placeholder");
            return;
        }

        final Vector3f worldBlockLocation = addPlaceholder.getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
        Vec3i playerBlockLocation = ChunkManager.getBlockLocation(playerLocation);
        Vec3i blockLocation = ChunkManager.getBlockLocation(worldBlockLocation);
        final Block selectedBlock = config.getSelectedBlock();

        // Prevents adding a solid block where the player stands
        if (selectedBlock.isSolid()
                && blockLocation.x == playerBlockLocation.x
                && blockLocation.z == playerBlockLocation.z
                && (blockLocation.y == playerBlockLocation.y || blockLocation.y == playerBlockLocation.y + 1)) {
            log.info("Can't add a solid block where the player stands");
            return;
        }

        executorService.submit(() -> addBlockTask(worldBlockLocation, selectedBlock));
    }

    private void addBlockTask(Vector3f location, Block block) {
        // Orientate the selected block
        Block orientatedBlock = worldManager.orientateBlock(block, location,
                camDir,
                Direction.fromVector(addPlaceholder.getWorldTranslation()
                        .subtract(removePlaceholder.getWorldTranslation())));

        if (orientatedBlock == null) {
            // Can't place the block like this
            return;
        }

        if (TypeIds.WATER.equals(block.getType())) {
            Vector3f tmp = removePlaceholder.getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
            Block previousBlock = worldManager.getBlock(tmp);
            if (previousBlock != null && !ShapeIds.CUBE.equals(previousBlock.getShape())) {
                location = tmp;
            }
        }

        worldManager.addBlock(location, orientatedBlock);
    }

    public void addListener(@NonNull PlayerListener listener) {
        listeners.add(listener);
    }

    public void removeBlock() {
        log.info("Action : removeBlock triggered");

        if (removePlaceholder.getParent() == null) {
            log.info("Not removing. No parent for placeholder");
            return;
        }

        executorService.submit(() -> removeBlockTask(removePlaceholder
                .getWorldTranslation()
                .subtract(0.5f, 0.5f, 0.5f)));
    }

    private void removeBlockTask(Vector3f blockLocation) {
        worldManager.removeBlock(blockLocation);
    }

    private CollisionResult getCollisionResult() {
        ray.setOrigin(camera.getLocation());
        ray.setDirection(camera.getDirection().normalizeLocal());
        collisionResults.clear();
        chunkNode.collideWith(ray, collisionResults);

        for (CollisionResult collisionResult : collisionResults) {
            if (collisionResult.getGeometry().getMaterial().getName() != null &&
                    !collisionResult.getGeometry().getMaterial().getName().contains("water")) {
                return collisionResult;
            }
        }

        return null;
    }

    private void updatePlaceholders() {
        if (System.currentTimeMillis() - lastCollisionTest > 100) {
            lastCollisionTest = System.currentTimeMillis();
            CollisionResult result = getCollisionResult();
            updatePlaceholders(result);
        }
    }

    private void updatePlaceholders(CollisionResult result) {
        if (result == null || result.getDistance() >= 20) {
            removePlaceholder.removeFromParent();
            return;
        }

        Vec3i pointingLocation = ChunkManager.getBlockLocation(result);
        Vector3f localTranslation = pointingLocation.toVector3f().addLocal(OFFSET).multLocal(BlocksConfig.getInstance().getBlockScale());
        removePlaceholder.setLocalTranslation(localTranslation);
        if (removePlaceholder.getParent() == null) {
            app.getRootNode().attachChild(removePlaceholder);
        }

        Block b = worldManager.getBlock(localTranslation);
        Shape shape = b == null ? null : BlocksConfig.getInstance().getShapeRegistry().get(b.getShape());

        if ((shape instanceof Wedge
                || shape instanceof CrossPlane
                || shape instanceof Stairs
                || shape instanceof Pyramid)) {
            // These shapes have slew faces, it is better to define the "add location"
            // based on the face of the enclosing cube where the user points to.
            addPlaceholder.setLocalTranslation(localTranslation);
            ray.setOrigin(camera.getLocation());
            ray.setDirection(camera.getDirection().normalizeLocal());
            collisionResults.clear();
            addPlaceholder.collideWith(ray, collisionResults);
            result = collisionResults.getClosestCollision();
            if (result == null || result.getDistance() >= 20) {
                addPlaceholder.removeFromParent();
                return;
            }
        }

        Vec3i placingLocation = ChunkManager.getNeighbourBlockLocation(result);
        addPlaceholder.setLocalTranslation(placingLocation.toVector3f().addLocal(OFFSET).multLocal(BlocksConfig.getInstance().getBlockScale()));
    }

    private void updateFallSpeed(Block block) {
        if (block != null && Vector3f.ZERO.equals(railDirection)) {
            if (block.getName().contains("water")) {
                updateFallSpeedWaterIn();

            } else if (TypeIds.SCALE.equals(block.getType())) {
                updateFallSpeedScaleIn();
            }

        } else if (underWater) {
            updateFallSpeedWaterOut();

        } else if (onScale) {
            updateFallSpeedScaleOut();
        }
    }

    private void updateFallSpeedWaterIn() {
        if (!underWater) {
            log.info("Water - IN");
            underWater = true;

            if (!fly) {
                // In water, we don't need to climb stairs, just swim ;-)
                // Setting step height to a low value prevents a bug in bullet
                // that makes the character fall with a different speed below
                // the stepHeight. This bug is noticeable especially under water.
                player.getCharacter().setStepHeight(0.03f);
                player.setFallSpeed(config.getWaterGravity());
                player.setJumpSpeed(config.getWaterJumpSpeed());
            }
        }
    }

    private void updateFallSpeedWaterOut() {
        log.info("Water - OUT");
        underWater = false;
        if (!fly) {
            player.getCharacter().setStepHeight(config.getPlayerStepHeight());
            player.setFallSpeed(config.getGroundGravity());
            player.setJumpSpeed(config.getJumpSpeed());
        }
    }

    private void updateFallSpeedScaleIn() {
        if (!onScale) {
            log.info("Scale - IN");
            onScale = true;
        }

        if (!fly) {
            player.setFallSpeed(0);
        }
    }

    private void updateFallSpeedScaleOut() {
        log.info("Scale - OUT");
        onScale = false;
        if (!fly) {
            player.setFallSpeed(config.getGroundGravity());
        }
    }

    private Material getBallMaterial() {
        if (ballMaterial == null) {
            ballMaterial = new Material(app.getAssetManager(), "Common/MatDefs/Light/Lighting.j3md");
            ColorRGBA color = ColorRGBA.randomColor();
            ballMaterial.setColor("Diffuse", color);
            ballMaterial.setColor("Ambient", color);
            ballMaterial.setBoolean("UseMaterialColors", true);
        }
        return ballMaterial;
    }

}

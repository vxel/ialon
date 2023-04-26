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
import org.delaunois.ialon.PlayerTouchListener;
import org.delaunois.ialon.WorldManager;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PlayerState extends BaseAppState {

    private static final String ALPHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";
    private static final Vector3f OFFSET = new Vector3f(0.5f, 0.5f, 0.5f);

    private SimpleApplication app;
    private Label crossHair;
    private Geometry addPlaceholder;
    private Geometry removePlaceholder;
    private CharacterControl player;
    private PlayerActionListener playerActionListener;
    private PlayerTouchListener playerTouchListener;
    private ChunkManager chunkManager;
    private ExecutorService executorService;

    private Node chunkNode;

    private ChunkPagerState chunkPagerState;
    private PhysicsChunkPagerState physicsChunkPagerState;

    @Getter
    private WorldManager worldManager;

    @Getter
    @Setter
    private boolean touchEnabled = true;

    @Getter
    @Setter
    private Vector3f playerLocation;

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
    private long lastCollisionTest = System.currentTimeMillis();

    private Material ballMaterial;
    private final IalonConfig config;

    public PlayerState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application simpleApp) {
        app = (SimpleApplication) simpleApp;
        camera = app.getCamera();

        chunkManager = config.getChunkManager();
        worldManager = new WorldManager(
                chunkManager,
                new ChunkLightManager(config),
                Optional.ofNullable(app.getStateManager().getState(ChunkLiquidManagerState.class))
                        .map(ChunkLiquidManagerState::getChunkLiquidManager).orElse(null),
                app.getStateManager().getState(ChunkSaverState.class)
        );
        crossHair = createCrossHair();
        addPlaceholder = createAddPlaceholder();
        removePlaceholder = createRemovePlaceholder();
        executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("save").build());
        fly = config.isPlayerStartFly();
        playerLocation = config.getPlayerLocation();
        player = createPlayer();
        playerActionListener = new PlayerActionListener(this, config);
        playerTouchListener = new PlayerTouchListener(this, config);
        playerActionButtons = new PlayerActionButtons(this);
        chunkPagerState = app.getStateManager().getState(ChunkPagerState.class, true);
        physicsChunkPagerState = app.getStateManager().getState(PhysicsChunkPagerState.class, true);
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
        updatePlaceholders();
        updateMove(move);

        if (move.length() > 0) {
            move.normalizeLocal().multLocal(fly ? config.getPlayerFlySpeed() : config.getPlayerMoveSpeed());
            walkDirection.set(move);
        }

        player.setWalkDirection(walkDirection);

        updatePlayerPosition();
        updateFallSpeed();
    }

    private void updateMove(Vector3f move) {
        if (fly) {
            updateFlyMove(move);
        } else {
            updateWalkMove(move);
        }

        if (up && playerLocation.y <= config.getMaxy()) {
            move.addLocal(0, 1, 0);
        }
        if (down) {
            move.addLocal(0, -1, 0);
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

        chunkPagerState.setLocation(playerLocation);
        physicsChunkPagerState.setLocation(playerLocation);
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
        Block above = chunkManager.getBlock(camera.getLocation().add(0, 1, 0)).orElse(null);
        if (above != null && above.getLiquidLevel() < 0) {
            return;
        }

        // Do not jump if the player is not on the ground, unless he is in water
        Block block = chunkManager.getBlock(playerLocation).orElse(null);
        if (!player.onGround() && (block == null || block.getLiquidLevel() != Block.LIQUID_FULL)) {
            return;
        }

        // Adjust jump strength according to space available above the player
        Block aboveAbove = chunkManager.getBlock(camera.getLocation().add(0, 2, 0)).orElse(null);
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
        final Block selectedBlock = app.getStateManager().getState(BlockSelectionState.class).getSelectedBlock();

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
                Direction.fromVector(addPlaceholder.getWorldTranslation()
                        .subtract(removePlaceholder.getWorldTranslation())));

        if (orientatedBlock == null) {
            // Can't place the block like this
            return;
        }

        if (TypeIds.WATER.equals(block.getType())) {
            Vector3f tmp = removePlaceholder.getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
            Block previousBlock = chunkManager.getBlock(tmp).orElse(null);
            if (previousBlock != null && !ShapeIds.CUBE.equals(previousBlock.getShape())) {
                location = tmp;
            }
        }

        worldManager.addBlock(location, orientatedBlock);
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
        CollisionResults collisionResults = new CollisionResults();
        Ray ray = new Ray(camera.getLocation(), camera.getDirection());

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

        Vec3i placingLocation;
        Block b = chunkManager.getBlock(localTranslation).orElse(null);
        Shape shape = b == null ? null : BlocksConfig.getInstance().getShapeRegistry().get(b.getShape());

        if ((shape instanceof Wedge
                || shape instanceof CrossPlane
                || shape instanceof Stairs
                || shape instanceof Pyramid)) {
            // These shapes have slew faces, it is better to define the "add location"
            // based on the face of the enclosing cube where the user points to.
            addPlaceholder.setLocalTranslation(localTranslation);
            CollisionResults collisionResults = new CollisionResults();
            Ray ray = new Ray(camera.getLocation(), camera.getDirection());
            addPlaceholder.collideWith(ray, collisionResults);
            result = collisionResults.getClosestCollision();
            if (result == null || result.getDistance() >= 20) {
                addPlaceholder.removeFromParent();
                return;
            }
        }

        placingLocation = ChunkManager.getNeighbourBlockLocation(result);
        addPlaceholder.setLocalTranslation(placingLocation.toVector3f().addLocal(OFFSET).multLocal(BlocksConfig.getInstance().getBlockScale()));
    }

    private void updateFallSpeed() {
        Block block = chunkManager.getBlock(camera.getLocation().subtract(0, 1, 0)).orElse(null);
        if (block != null) {
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

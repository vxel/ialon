/**
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
import com.jme3.app.state.BaseAppState;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.collision.shapes.SphereCollisionShape;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.TouchInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.input.controls.TouchListener;
import com.jme3.input.controls.TouchTrigger;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
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
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.DynamicInsetsComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.lemur.event.MouseListener;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.CameraHelper;
import org.delaunois.ialon.ChunkManager;
import org.delaunois.ialon.Config;
import org.delaunois.ialon.Ialon;
import org.delaunois.ialon.PlayerTouchListener;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.Config.CHUNK_SIZE;
import static org.delaunois.ialon.Config.DEBUG_COLLISIONS;
import static org.delaunois.ialon.Config.GROUND_GRAVITY;
import static org.delaunois.ialon.Config.JUMP_SPEED;
import static org.delaunois.ialon.Config.MAXY;
import static org.delaunois.ialon.Config.PLAYER_FLY_SPEED;
import static org.delaunois.ialon.Config.PLAYER_HEIGHT;
import static org.delaunois.ialon.Config.PLAYER_MOVE_SPEED;
import static org.delaunois.ialon.Config.PLAYER_RADIUS;
import static org.delaunois.ialon.Config.PLAYER_START_FLY;
import static org.delaunois.ialon.Config.PLAYER_START_HEIGHT;
import static org.delaunois.ialon.Config.PLAYER_STEP_HEIGHT;
import static org.delaunois.ialon.Config.WATER_GRAVITY;
import static org.delaunois.ialon.Config.WATER_JUMP_SPEED;

@Slf4j
public class PlayerState extends BaseAppState implements ActionListener, AnalogListener {

    private static final String ACTION_LEFT = "left";
    private static final String ACTION_RIGHT = "right";
    private static final String ACTION_FORWARD = "forward";
    private static final String ACTION_BACKWARD = "backward";
    private static final String ACTION_JUMP = "jump";
    private static final String ACTION_FIRE = "fire";
    private static final String ACTION_FLY = "fly";
    private static final String ACTION_FLY_UP = "fly_up";
    private static final String ACTION_FLY_DOWN = "fly_down";

    private static final String ACTION_LOOK_LEFT = "look-left";
    private static final String ACTION_LOOK_RIGHT = "look-right";
    private static final String ACTION_LOOK_UP = "look-up";
    private static final String ACTION_LOOK_DOWN = "look-down";

    private static final String ACTION_ADD_BLOCK = "add-block";
    private static final String ACTION_REMOVE_BLOCK = "remove-block";
    private static final String ACTION_DEBUG_CHUNK = "debug-chunk";

    private static final String TOUCH_MAPPING = "touch";

    private static final String[] ACTIONS = new String[]{
            ACTION_LEFT,
            ACTION_RIGHT,
            ACTION_FORWARD,
            ACTION_BACKWARD,
            ACTION_JUMP,
            ACTION_FIRE,
            ACTION_FLY,
            ACTION_FLY_UP,
            ACTION_FLY_DOWN,
            ACTION_LOOK_LEFT,
            ACTION_LOOK_RIGHT,
            ACTION_LOOK_UP,
            ACTION_LOOK_DOWN,
            ACTION_ADD_BLOCK,
            ACTION_REMOVE_BLOCK,
            ACTION_DEBUG_CHUNK
    };

    private static final Vector3f DIRECTION_UP = new Vector3f(0, 1, 0);
    private boolean left = false, right = false, forward = false, backward = false, up = false, down = false, jump = false;
    private boolean underWater = false;
    private boolean onScale = false;
    private Ialon app;
    private Label crossHair;
    private Geometry addPlaceholder;
    private Geometry removePlaceholder;
    private CharacterControl player;
    private InputManager inputManager;
    private ChunkManager chunkManager;
    private TouchListener touchListener;
    private ExecutorService executorService;

    @Getter
    @Setter
    private boolean touchEnabled = true;

    @Getter
    @Setter
    private Vector3f playerLocation;

    @Getter
    private boolean fly = PLAYER_START_FLY;

    @Getter
    private Camera camera;

    @Getter
    private int buttonSize = 100;

    @Getter
    private static final float SCREEN_MARGIN = 30;

    @Getter
    private static final float SPACING = 10;

    @Getter
    private Node directionButtons;

    @Getter
    private Container buttonLeft;

    @Getter
    private Container buttonRight;

    @Getter
    private Container buttonForward;

    @Getter
    private Container buttonBackward;

    @Getter
    private Container buttonJump;

    @Getter
    private Container buttonAddBlock;

    @Getter
    private Container buttonRemoveBlock;

    @Getter
    private Container buttonFly;

    //Temporary vectors used on each frame.
    //They here to avoid instanciating new vectors on each frame
    private final Vector3f walkDirection = new Vector3f();
    private final Vector3f camDir = new Vector3f();
    private final Vector3f camLeft = new Vector3f();
    private final Vector3f move = new Vector3f();
    private static final Vector3f OFFSET = new Vector3f(0.5f, 0.5f, 0.5f);
    private long lastCollisionTest = System.currentTimeMillis();
    private Material ballMaterial;

    @Override
    protected void initialize(Application simpleApp) {
        app = (Ialon) simpleApp;
        camera = app.getCamera();
        buttonSize = camera.getHeight() / 6;

        inputManager = app.getInputManager();
        chunkManager = app.getChunkManager();
        touchListener = new PlayerTouchListener(this);
        crossHair = createCrossHair();
        addPlaceholder = createAddPlaceholder();
        removePlaceholder = createRemovePlaceholder();
        executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("save").build());
        player = createPlayer();
        updatePlayerPosition();
        createButttons();
    }

    @Override
    protected void cleanup(Application app) {
        executorService.shutdown();
    }

    @Override
    protected void onEnable() {
        log.info("Enabling player");
        app.getGuiNode().attachChild(crossHair);
        player.setGravity(GROUND_GRAVITY);
        setFly(fly);
        addKeyMappings();
        showControlButtons();
    }

    @Override
    protected void onDisable() {
        log.info("Disabling player");
        crossHair.removeFromParent();
        deleteKeyMappings();
        hideControlButtons();
    }

    @Override
    public void update(float tpf) {
        camDir.set(camera.getDirection());
        camLeft.set(camera.getLeft());
        walkDirection.set(0, 0, 0);
        //walkDirection.multLocal(0.9f);
        move.zero();
        playerLocation.set(player.getPhysicsLocation());

        if (System.currentTimeMillis() - lastCollisionTest > 100) {
            lastCollisionTest = System.currentTimeMillis();
            CollisionResult result = getCollisionResult();
            updatePlaceholders(result);
        }

        if (left) {
            move.addLocal(camLeft.x, fly ? 0 : camLeft.y, camLeft.z);
        }
        if (right) {
            move.addLocal(-camLeft.x, fly ? 0 : -camLeft.y, -camLeft.z);
        }
        if (fly && jump) {
            up = forward;
            down = backward;
        }
        if (forward && (!fly || !jump)) {
            move.addLocal(camDir.x, fly ? 0 : camDir.y, camDir.z);
        }
        if (backward && (!fly || !jump)) {
            move.addLocal(-camDir.x, fly ? 0 : -camDir.y, -camDir.z);
        }
        if (up && playerLocation.y <= MAXY) {
            move.addLocal(0, 1, 0);
        }
        if (down) {
            move.addLocal(0, -1, 0);
        }

        if (move.length() > 0) {
            move.normalizeLocal().multLocal(fly ? PLAYER_FLY_SPEED : PLAYER_MOVE_SPEED);
            walkDirection.set(move);
        }

        player.setWalkDirection(walkDirection);

        updatePlayerPosition();
        updateFallSpeed();
    }

    public void resize() {
        crossHair.setLocalTranslation((app.getCamera().getWidth() / 2f) - (crossHair.getPreferredSize().getX() / 2), (app.getCamera().getHeight() / 2f) + (crossHair.getPreferredSize().getY() / 2), crossHair.getLocalTranslation().getZ());
        buttonJump.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, SCREEN_MARGIN + buttonSize, 1);
        buttonAddBlock.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
        buttonRemoveBlock.setLocalTranslation(SCREEN_MARGIN, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
        buttonFly.setLocalTranslation(app.getCamera().getWidth() - SCREEN_MARGIN - 2 * buttonSize - SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, 1);
    }

    public Vector3f getRemovePlaceholderPosition() {
        return removePlaceholder.getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
    }

    public Vector3f getAddPlaceholderPosition() {
        return addPlaceholder.getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
    }

    private void addKeyMappings() {
        inputManager.addMapping(TOUCH_MAPPING, new TouchTrigger(TouchInput.ALL));
        inputManager.addListener(touchListener, TOUCH_MAPPING);

        inputManager.addMapping(ACTION_LOOK_LEFT, new MouseAxisTrigger(MouseInput.AXIS_X, true));
        inputManager.addMapping(ACTION_LOOK_RIGHT, new MouseAxisTrigger(MouseInput.AXIS_X, false));
        inputManager.addMapping(ACTION_LOOK_UP, new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        inputManager.addMapping(ACTION_LOOK_DOWN, new MouseAxisTrigger(MouseInput.AXIS_Y, true));

        inputManager.addMapping(ACTION_ADD_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_LEFT), new KeyTrigger(KeyInput.KEY_RETURN));
        inputManager.addMapping(ACTION_REMOVE_BLOCK, new MouseButtonTrigger(MouseInput.BUTTON_RIGHT), new KeyTrigger(KeyInput.KEY_DELETE));
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

        inputManager.addListener(this, ACTIONS);
    }

    private void deleteKeyMappings() {
        for (String action : ACTIONS) {
            inputManager.deleteMapping(action);
        }
        inputManager.removeListener(this);
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
        CapsuleCollisionShape capsuleShape = new CapsuleCollisionShape(PLAYER_RADIUS, PLAYER_HEIGHT - 2 * PLAYER_RADIUS, 1);
        CharacterControl player = new CharacterControl(capsuleShape, PLAYER_STEP_HEIGHT);
        player.setJumpSpeed(JUMP_SPEED);
        player.setFallSpeed(GROUND_GRAVITY);
        player.setGravity(0);
        player.getCharacter().setMaxSlope(FastMath.PI * 0.3f);

        if (playerLocation == null) {
            playerLocation = new Vector3f(CHUNK_SIZE / 2f, app.getTerrainGenerator().getHeight(new Vector3f(0, 0, 0)) + PLAYER_START_HEIGHT, CHUNK_SIZE / 2f);
        }
        player.setPhysicsLocation(playerLocation);

        app.getStateManager().getState(BulletAppState.class).getPhysicsSpace().add(player);

        playerLocation.set(player.getPhysicsLocation());

        return player;
    }

    private Label createCrossHair() {
        Label crossHair = new Label("+");
        crossHair.setColor(ColorRGBA.White);
        crossHair.setFontSize(30);
        crossHair.getFont().getPage(0).clearParam("AlphaDiscardThreshold");

        int width = camera.getWidth();
        int height = camera.getHeight();
        crossHair.setLocalTranslation((width / 2f) - (crossHair.getPreferredSize().getX() / 2), (height / 2f) + (crossHair.getPreferredSize().getY() / 2), crossHair.getLocalTranslation().getZ());
        return crossHair;
    }

    private Geometry createRemovePlaceholder() {
        Material removePlaceholderMaterial = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        removePlaceholderMaterial.setColor("Color", new ColorRGBA(0.4549f, 0.851f, 1, 1));

        Geometry removePlaceholder = new Geometry("remove-placeholder", new WireBox(0.505f, 0.505f, 0.505f));
        removePlaceholder.setMaterial(removePlaceholderMaterial);
        removePlaceholder.setLocalScale(BlocksConfig.getInstance().getBlockScale());

        return removePlaceholder;
    }

    private Geometry createAddPlaceholder() {
        Geometry addPlaceholder = new Geometry("add-placeholder", new Box(0.5f, 0.5f, 0.5f));
        addPlaceholder.setLocalScale(BlocksConfig.getInstance().getBlockScale());
        return addPlaceholder;
    }

    private void updatePlayerPosition() {
        if (playerLocation.y < 1) {
            playerLocation.setY(MAXY);
            player.setPhysicsLocation(playerLocation);
        }
        if (DEBUG_COLLISIONS) {
            camera.setLocation(playerLocation.add(-2, PLAYER_HEIGHT / 2 - 0.15f, 0));

        } else {
            // The player location is at the center of the capsule shape. So we need to level the camera
            // up to set it at the top of the shape.
            camera.setLocation(playerLocation.add(0, PLAYER_HEIGHT / 2 - 0.15f, 0));
        }

        app.getStateManager().getState(ChunkPagerState.class).setLocation(playerLocation);
        app.getStateManager().getState(PhysicsChunkPagerState.class).setLocation(playerLocation);
    }

    private void highlight(boolean isPressed, Container button) {
        if (isPressed) {
            ((QuadBackgroundComponent)button.getBackground()).getColor().set(0.5f, 0.5f, 0.5f, 0.5f);
        } else {
            ((QuadBackgroundComponent)button.getBackground()).getColor().set(0, 0, 0, 0.5f);
        }
    }

    private void createButttons() {
        directionButtons = new Node();
        buttonLeft = createButton("Left", buttonSize, SCREEN_MARGIN, SCREEN_MARGIN + buttonSize, new DefaultMouseListener() {
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                left = event.isPressed();
                highlight(left, buttonLeft);
            }
        });
        buttonBackward = createButton("Backward", buttonSize, SCREEN_MARGIN + buttonSize + SPACING, SCREEN_MARGIN + buttonSize, new DefaultMouseListener() {
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                backward = event.isPressed();
                highlight(backward, buttonBackward);
            }
        });
        buttonForward = createButton("Forward", buttonSize, SCREEN_MARGIN + buttonSize + SPACING, SCREEN_MARGIN + buttonSize * 2 + SPACING, new DefaultMouseListener() {
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                forward = event.isPressed();
                highlight(forward, buttonForward);
            }
        });
        buttonRight = createButton("Right", buttonSize, SCREEN_MARGIN + (buttonSize + SPACING) * 2, SCREEN_MARGIN + buttonSize, new DefaultMouseListener() {
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                right = event.isPressed();
                highlight(right, buttonRight);
            }
        });
        buttonJump = createButton("Jump", buttonSize, app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, SCREEN_MARGIN + buttonSize, new DefaultMouseListener() {
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                jump = event.isPressed();
                up = false;
                down = false;
                highlight(jump, buttonJump);
                if (jump && !fly) {
                    playerJump();
                }
            }
        });
        buttonAddBlock = createButton("Add", buttonSize, app.getCamera().getWidth() - SCREEN_MARGIN - buttonSize, app.getCamera().getHeight() - SCREEN_MARGIN, new DefaultMouseListener() {
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                highlight(event.isPressed(), buttonAddBlock);
                if (event.isPressed()) {
                    addBlock();
                }
            }
        });
        buttonRemoveBlock = createButton("Remove", buttonSize, SCREEN_MARGIN, app.getCamera().getHeight() - SCREEN_MARGIN, new DefaultMouseListener() {
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                highlight(event.isPressed(), buttonRemoveBlock);
                if (event.isPressed()) {
                    removeBlock();
                }
            }
        });
        buttonFly = createButton("Fly", buttonSize, app.getCamera().getWidth() - SCREEN_MARGIN - 2 * buttonSize - SPACING, app.getCamera().getHeight() - SCREEN_MARGIN, new DefaultMouseListener() {
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                if (event.isPressed()) {
                    toogleFly();
                }
            }
        });
        directionButtons.attachChild(buttonLeft);
        directionButtons.attachChild(buttonBackward);
        directionButtons.attachChild(buttonForward);
        directionButtons.attachChild(buttonRight);
    }

    private Container createButton(String text, float size, float posx, float posy, MouseListener listener) {
        Container buttonContainer = new Container();
        buttonContainer.setName(text);
        buttonContainer.setPreferredSize(new Vector3f(size, size, 0));
        QuadBackgroundComponent background = new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0.5f));
        // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
        background.getMaterial().getMaterial().clearParam("AlphaDiscardThreshold");
        buttonContainer.setBackground(background);
        //buttonContainer.setBackground(TbtQuadBackgroundComponent.create("/com/simsilica/lemur/icons/border.png", 1, 2, 2, 3, 3, 0, false));
        Label label = buttonContainer.addChild(new Label(text));
        label.getFont().getPage(0).clearParam("AlphaDiscardThreshold");
        label.getFont().getPage(0).clearParam("VertexColor");

        // Center the text in the box.
        label.setInsetsComponent(new DynamicInsetsComponent(0.5f, 0.5f, 0.5f, 0.5f));
        label.setColor(ColorRGBA.White);
        buttonContainer.setLocalTranslation(posx, posy, 1);

        buttonContainer.addMouseListener(listener);
        return buttonContainer;
    }

    public void showControlButtons() {
        if (directionButtons.getParent() == null) {
            app.getGuiNode().attachChild(directionButtons);
            app.getGuiNode().attachChild(buttonJump);
            app.getGuiNode().attachChild(buttonAddBlock);
            app.getGuiNode().attachChild(buttonRemoveBlock);
            app.getGuiNode().attachChild(buttonFly);
        }
    }

    public void hideControlButtons() {
        if (directionButtons.getParent() != null) {
            directionButtons.removeFromParent();
            buttonJump.removeFromParent();
            buttonAddBlock.removeFromParent();
            buttonRemoveBlock.removeFromParent();
            buttonFly.removeFromParent();
        }
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (log.isDebugEnabled()) {
            log.debug("Action {} isPressed {}", name, isPressed);
        }
        if (ACTION_ADD_BLOCK.equals(name) && buttonAddBlock.getParent() == null) {
            if (isPressed) {
                addBlock();
            }
            highlight(isPressed, buttonAddBlock);

        } else if (ACTION_REMOVE_BLOCK.equals(name) && buttonRemoveBlock.getParent() == null) {
            if (isPressed) {
                removeBlock();
            }
            highlight(isPressed, buttonRemoveBlock);

        } else if (ACTION_LEFT.equals(name)) {
            left = isPressed;
            highlight(isPressed, buttonLeft);

        } else if (ACTION_RIGHT.equals(name)) {
            right = isPressed;
            highlight(isPressed, buttonRight);

        } else if (ACTION_FORWARD.equals(name)) {
            forward = isPressed;
            highlight(isPressed, buttonForward);

        } else if (ACTION_BACKWARD.equals(name)) {
            backward = isPressed;
            highlight(isPressed, buttonBackward);

        } else if (fly && ACTION_FLY_UP.equals(name)) {
            up = isPressed;

        } else if (fly && ACTION_FLY_DOWN.equals(name)) {
            down = isPressed;

        } else if (ACTION_JUMP.equals(name)) {
            if (isPressed) {
                playerJump();
            }
            highlight(isPressed, buttonJump);

        } else if (ACTION_FIRE.equals(name) && isPressed) {
            fireBall();

        } else if (ACTION_FLY.equals(name) && isPressed) {
            toogleFly();
        } else if (ACTION_DEBUG_CHUNK.equals(name) && isPressed) {
            Config.DEBUG_CHUNKS = !Config.DEBUG_CHUNKS;
        }
    }

    private void fireBall() {
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

    private void playerJump() {
        if (fly) {
            return;
        }

        Block above = chunkManager.getBlock(camera.getLocation().add(0, 1, 0)).orElse(null);

        // Do not jump if there is a block above the player, unless it is water
        if (above == null || above.getLiquidLevel() >= 0) {

            // Do not jump if the player is not on the ground, unless he is in water
            Block block = chunkManager.getBlock(playerLocation).orElse(null);
            if ((block != null && block.getLiquidLevel() == Block.LIQUID_FULL) || player.onGround()) {
                Block aboveAbove = chunkManager.getBlock(camera.getLocation().add(0, 2, 0)).orElse(null);
                if (aboveAbove == null) {
                    player.jump();
                } else {
                    float availableJumpSpace = ((int)camera.getLocation().y) + 2 - camera.getLocation().y;
                    // Hack to avoid bug when jumping and touching a block above
                    // Still does not work when being on half-blocks
                    player.setJumpSpeed(JUMP_SPEED * availableJumpSpace * 0.4f);
                    player.jump();
                    player.setJumpSpeed(JUMP_SPEED);
                }
            }
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
        if (app.isMouselocked()) {
            if (ACTION_LOOK_LEFT.equals(name)) {
                CameraHelper.rotate(camera, value, DIRECTION_UP);

            } else if (ACTION_LOOK_RIGHT.equals(name)) {
                CameraHelper.rotate(camera, -value, DIRECTION_UP);

            } else if (ACTION_LOOK_UP.equals(name)) {
                CameraHelper.rotate(camera, -value, camera.getLeft());

            } else if (ACTION_LOOK_DOWN.equals(name)) {
                CameraHelper.rotate(camera, value, camera.getLeft());
            }
        }
    }

    private void toogleFly() {
        setFly(!fly);
    }

    public void setFly(boolean fly) {
        this.fly = fly;
        if (player == null) {
            return;
        }

        highlight(fly, buttonFly);
        if (fly) {
            log.info("Flying");
            player.setFallSpeed(0);
        } else {
            log.info("Not Flying");
            if (underWater) {
                player.setFallSpeed(WATER_GRAVITY);
            } else {
                player.setFallSpeed(GROUND_GRAVITY);
            }
        }
    }

    private void addBlock() {
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

        executorService.submit(() -> {
            try {
                Vector3f location = worldBlockLocation;
                // Orientate the selected block
                Block block = orientateBlock(selectedBlock, location);
                if (block == null) {
                    // Can't place the block like this
                    return;
                }

                if (TypeIds.WATER.equals(selectedBlock.getType())) {
                    Vector3f tmp = removePlaceholder.getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
                    Block previousBlock = chunkManager.getBlock(tmp).orElse(null);
                    if (previousBlock != null && !ShapeIds.CUBE.equals(previousBlock.getShape())) {
                        location = tmp;
                    }
                }

                // Add the block, which removes the light at this location
                log.info("Adding block {}", block.getName());
                Set<Vec3i> updatedChunks = chunkManager.addBlock(location, block);

                // Computes the light if the block is a torch
                if (block.isTorchlight()) {
                    updatedChunks.addAll(chunkManager.addTorchlight(location, 15));
                }

                if (!updatedChunks.isEmpty()) {
                    chunkManager.requestOrderedMeshChunks(updatedChunks);

                    for (Vec3i loc : updatedChunks) {
                        app.asyncSave(loc);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to add block", e);
            }

        });
    }

    private void removeBlock() {
        log.info("Action : removeBlock triggered");

        if (removePlaceholder.getParent() == null) {
            log.info("Not removing. No parent for placeholder");
            return;
        }

        executorService.submit(() -> {
            try {
                Vector3f blockLocation = removePlaceholder.getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
                if (blockLocation.y <= 1) {
                    return;
                }

                log.info("Removing block at {}", blockLocation);
                Set<Vec3i> updatedChunks = chunkManager.removeBlock(blockLocation);

                cleanAroundBlocks(blockLocation, updatedChunks);

                if (!updatedChunks.isEmpty()) {
                    chunkManager.requestOrderedMeshChunks(updatedChunks);

                    for (Vec3i location : updatedChunks) {
                        app.asyncSave(location);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to remove block", e);
            }
        });
    }

    private void cleanAroundBlocks(Vector3f blockLocation, Set<Vec3i> updatedChunks) {
        Vector3f aroundLocation;
        Block aroundBlock;

        // WEST
        aroundLocation = blockLocation.add(-1, 0, 0);
        aroundBlock = chunkManager.getBlock(aroundLocation).orElse(null);
        if (aroundBlock != null) {
            if (ShapeIds.SQUARE_WEST.equals(aroundBlock.getShape())) {
                updatedChunks.addAll(chunkManager.removeBlock(aroundLocation));
            }
        }

        // EAST
        aroundLocation = blockLocation.add(1, 0, 0);
        aroundBlock = chunkManager.getBlock(aroundLocation).orElse(null);
        if (aroundBlock != null) {
            if (ShapeIds.SQUARE_EAST.equals(aroundBlock.getShape())) {
                updatedChunks.addAll(chunkManager.removeBlock(aroundLocation));
            }
        }

        // NORTH
        aroundLocation = blockLocation.add(0, 0, -1);
        aroundBlock = chunkManager.getBlock(aroundLocation).orElse(null);
        if (aroundBlock != null) {
            if (ShapeIds.SQUARE_NORTH.equals(aroundBlock.getShape())) {
                updatedChunks.addAll(chunkManager.removeBlock(aroundLocation));
            }
        }

        // SOUTH
        aroundLocation = blockLocation.add(0, 0, 1);
        aroundBlock = chunkManager.getBlock(aroundLocation).orElse(null);
        if (aroundBlock != null) {
            if (ShapeIds.SQUARE_SOUTH.equals(aroundBlock.getShape())) {
                updatedChunks.addAll(chunkManager.removeBlock(aroundLocation));
            }
        }
    }

    private Block orientateBlock(Block block, Vector3f blockLocation) {
        String type = block.getType();
        String[] shapeProperties = block.getShape().split("_");
        String shape = shapeProperties[0];

        // Turn the block according to where the user clicked
        Direction direction = Direction.fromVector(addPlaceholder.getWorldTranslation().subtract(removePlaceholder.getWorldTranslation()));
        if (TypeIds.SCALE.equals(block.getType())) {
            // A scale can only be added if a block exists behind it
            if (Direction.UP.equals(direction) || Direction.DOWN.equals(direction)) {
                log.info("Can't add an horizontal scale");
                return null;
            }
            Block behind = chunkManager.getBlock(blockLocation.subtract(direction.getVector().toVector3f())).orElse(null);
            if (behind == null || !ShapeIds.CUBE.equals(behind.getShape())) {
                log.info("No cube block behind scale");
                return null;
            }
        }

        String subShape = shapeProperties.length <= 2 ? "" : String.join("_", Arrays.copyOfRange(shapeProperties, 1, shapeProperties.length - 1));
        Block above = chunkManager.getBlock(blockLocation.add(0, 1, 0)).orElse(null);
        Block below = chunkManager.getBlock(blockLocation.add(0, -1, 0)).orElse(null);
        if (above != null && below == null
                && (Objects.equals("stairs", shape) || Objects.equals("wedge", shape))) {
            shape = String.join("_", shape, "inverted");
        }

        if (subShape.length() > 0) {
            shape = String.join("_", shape, subShape);
        }
        String orientedBlockName = String.format("%s-%s-%s", type, String.join("_", shape, direction.name().toLowerCase()), block.getLiquidLevelId());
        Block orientedBlock = BlocksConfig.getInstance().getBlockRegistry().get(orientedBlockName);

        // Just in case this particulier orientation does not exist...
        if (orientedBlock != null) {
            block = orientedBlock;
        }
        return block;
    }

    private CollisionResult getCollisionResult() {
        CollisionResults collisionResults = new CollisionResults();
        Ray ray = new Ray(camera.getLocation(), camera.getDirection());

        app.getChunkNode().collideWith(ray, collisionResults);

        for (CollisionResult collisionResult : collisionResults) {
            if (collisionResult.getGeometry().getMaterial().getName() != null &&
                    !collisionResult.getGeometry().getMaterial().getName().contains("water")) {
                return collisionResult;
            }
        }

        return null;
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
                if (!underWater) {
                    log.info("Water - IN");
                    underWater = true;

                    if (!fly) {
                        // In water, we don't need to climb stairs, just swim ;-)
                        // Setting step height to a low value prevents a bug in bullet
                        // that makes the character fall with a different speed below
                        // the stepHeight. This bug is noticeable especially under water.
                        player.getCharacter().setStepHeight(0.03f);
                        player.setFallSpeed(WATER_GRAVITY);
                        player.setJumpSpeed(WATER_JUMP_SPEED);
                    }
                }
            } else if (TypeIds.SCALE.equals(block.getType())) {
                if (!onScale) {
                    log.info("Scale - IN");
                    onScale = true;
                }

                if (!fly) {
                    player.setFallSpeed(0);
                }
            }
        } else if (underWater) {
            log.info("Water - OUT");
            underWater = false;
            if (!fly) {
                player.getCharacter().setStepHeight(PLAYER_STEP_HEIGHT);
                player.setFallSpeed(GROUND_GRAVITY);
                player.setJumpSpeed(JUMP_SPEED);
            }
        } else if (onScale) {
            log.info("Scale - OUT");
            onScale = false;
            if (!fly) {
                player.setFallSpeed(GROUND_GRAVITY);
            }
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

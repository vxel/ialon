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

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.CameraNode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.CameraControl;
import com.simsilica.lemur.Label;

import org.delaunois.ialon.ChunkLightManager;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.PlayerListener;
import org.delaunois.ialon.WorldManager;
import org.delaunois.ialon.control.ButtonHighlightControl;
import org.delaunois.ialon.control.PlaceholderControl;
import org.delaunois.ialon.control.PlayerActionControl;
import org.delaunois.ialon.control.PlayerCharacterControl;
import org.delaunois.ialon.control.PlayerFlyControl;
import org.delaunois.ialon.control.PlayerHeadDirectionControl;
import org.delaunois.ialon.control.PlayerRailControl;
import org.delaunois.ialon.control.PlayerWalkControl;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PlayerState extends BaseAppState {

    private static final String ALPHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";

    @Getter
    private Node playerNode;

    @Getter
    private Node headNode;

    @Getter
    private Node bodyNode;

    @Getter
    private Spatial wagon;

    @Getter
    private WorldManager worldManager;

    @Getter
    @Setter
    private boolean touchEnabled = true;

    @Getter
    private Camera camera;

    private SimpleApplication app;
    private Label crossHair;
    private PlaceholderControl placeholderControl;
    private PlayerActionControl playerActionControl;
    private PlayerWalkControl playerWalkControl;
    private PlayerFlyControl playerFlyControl;
    private PlayerCharacterControl playerCharacterControl;
    private final List<PlayerListener> listeners = new CopyOnWriteArrayList<>();
    private final IalonConfig config;

    public PlayerState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application simpleApp) {
        app = (SimpleApplication) simpleApp;
        camera = app.getCamera();
        crossHair = createCrossHair();
        wagon = createWagon();
        worldManager = new WorldManager(
                config.getChunkManager(),
                new ChunkLightManager(config),
                Optional.ofNullable(app.getStateManager().getState(ChunkLiquidManagerState.class))
                        .map(ChunkLiquidManagerState::getChunkLiquidManager).orElse(null)
        );

        playerNode = createPlayer(app, config, worldManager);
        bodyNode = (Node) playerNode.getChild("Body");
        headNode = (Node) bodyNode.getChild("Head");
        playerCharacterControl = playerNode.getControl(PlayerCharacterControl.class);
        playerActionControl = playerNode.getControl(PlayerActionControl.class);
        playerWalkControl = playerNode.getControl(PlayerWalkControl.class);
        playerFlyControl = playerNode.getControl(PlayerFlyControl.class);
    }

    @Override
    public void update(float tpf) {
        listeners.forEach(listener -> listener.onMove(config.getPlayerLocation()));
    }

    @Override
    protected void onEnable() {
        log.info("Enabling player");

        app.getGuiNode().attachChild(crossHair);
        Node chunkNode = (Node) app.getRootNode().getChild(IalonConfig.CHUNK_NODE_NAME);
        if (chunkNode == null) {
            throw new IllegalStateException("Chunk Node is not attached !");
        }

        if (placeholderControl == null) {
            placeholderControl = new PlaceholderControl(chunkNode, worldManager, app);
            headNode.addControl(placeholderControl);
        } else {
            placeholderControl.setChunkNode(chunkNode);
        }

        app.getRootNode().attachChild(playerNode);
        BulletAppState bulletAppState = app.getStateManager().getState(BulletAppState.class);
        ButtonManagerState buttonManagerState = app.getStateManager().getState(ButtonManagerState.class);
        bulletAppState.getPhysicsSpace().add(playerCharacterControl);
        config.getInputActionManager().addListener(playerActionControl);
        config.getInputActionManager().addListener(playerWalkControl);
        config.getInputActionManager().addListener(playerFlyControl);
        if (buttonManagerState != null) {
            ButtonHighlightControl buttonHighlightControl = buttonManagerState.getButtonFly().getControl(ButtonHighlightControl.class);
            if (buttonHighlightControl != null) {
                buttonHighlightControl.setStatusSupplier(playerActionControl::isFly);
                buttonHighlightControl.highlight(playerActionControl.isFly());
            }
        }
    }

    @Override
    protected void onDisable() {
        log.info("Disabling player");

        crossHair.removeFromParent();
        config.getInputActionManager().removeListener(playerActionControl);

        if (playerNode.getParent() != null) {
            playerNode.getParent().detachChild(playerNode);
            BulletAppState bulletAppState = app.getStateManager().getState(BulletAppState.class);
            if (bulletAppState != null && bulletAppState.getPhysicsSpace() != null) {
                bulletAppState.getPhysicsSpace().remove(playerCharacterControl);
            }
        }
    }

    public void resize() {
        crossHair.setLocalTranslation((app.getCamera().getWidth() / 2f) - (crossHair.getPreferredSize().getX() / 2), (app.getCamera().getHeight() / 2f) + (crossHair.getPreferredSize().getY() / 2), crossHair.getLocalTranslation().getZ());
        app.getStateManager().getState(ButtonManagerState.class).resize();
    }

    /**
     * Create the player, configure it, add it to the physic engine and position it on the scene
     */
    private Node createPlayer(SimpleApplication app, IalonConfig config, WorldManager worldManager) {
        // We set up collision detection for the player by creating
        // a capsule collision shape and a CharacterControl.
        // The CharacterControl offers extra settings for
        // size, stepheight, jumping, falling, and gravity.
        // We also put the player in its starting position.
        CapsuleCollisionShape capsuleShape = new CapsuleCollisionShape(
                config.getPlayerRadius(),
                config.getPlayerHeight() - 2 * config.getPlayerRadius(),
                1);
        PlayerCharacterControl characterControl = new PlayerCharacterControl(capsuleShape, config.getPlayerStepHeight(), worldManager, config);
        characterControl.setJumpSpeed(config.getJumpSpeed());
        characterControl.setFallSpeed(config.getGroundGravity());
        characterControl.setGravity(config.getGroundGravity());
        characterControl.getCharacter().setMaxSlope(FastMath.PI * 0.3f);

        if (config.getPlayerLocation() == null) {
            config.setPlayerLocation(new Vector3f(
                    config.getChunkSize() / 2f,
                    config.getTerrainGenerator().getHeight(new Vector3f(0, 0, 0)) + config.getPlayerStartHeight(),
                    config.getChunkSize() / 2f
            ));
        }

        PlayerWalkControl walkControl = new PlayerWalkControl(config);
        PlayerRailControl railControl = new PlayerRailControl(config, worldManager);
        railControl.setWagon(wagon);
        PlayerFlyControl flyControl = new PlayerFlyControl(config, app.getCamera());
        PlayerHeadDirectionControl camDirectionControl = new PlayerHeadDirectionControl(config, app.getInputManager(), app.getCamera());
        PlayerActionControl actionControl = new PlayerActionControl(app, config);

        Node player = new Node("Player");
        Node head = new Node("Head");
        Node body = new Node("Body");
        Node feet = new Node("Feet");
        player.attachChild(body);
        body.attachChild(head);
        body.attachChild(feet);

        // The player location is at the center of the capsule shape.
        // The head is near the top of the shape (its eyes).
        // The feet are at the bottom of the shape.
        feet.setLocalTranslation(new Vector3f(0, -config.getPlayerHeight() / 2f, 0));
        if (config.isDebugCollisions()) {
            head.setLocalTranslation(new Vector3f(-2, config.getPlayerHeight() / 2 - 0.15f, 0));
        } else {
            head.setLocalTranslation(new Vector3f(0, config.getPlayerHeight() / 2 - 0.15f, 0));
        }

        CameraNode cameraNode = new CameraNode("Camera", app.getCamera());
        cameraNode.setControlDir(CameraControl.ControlDirection.SpatialToCamera);
        head.attachChild(cameraNode);
        head.addControl(camDirectionControl);
        head.getLocalRotation().fromAngleAxis(config.getPlayerPitch(), Vector3f.UNIT_X);
        body.getLocalRotation().fromAngleAxis(config.getPlayerYaw(), Vector3f.UNIT_Y);

        player.setLocalTranslation(config.getPlayerLocation());
        player.addControl(characterControl);
        player.addControl(flyControl);
        player.addControl(walkControl);
        player.addControl(railControl);
        player.addControl(actionControl);

        return player;
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

    private Spatial createWagon() {
        Geometry model = (Geometry) app.getAssetManager().loadModel("Models/Wagon/wagon.j3o");
        Material modelMaterial = model.getMaterial();
        config.getTextureAtlasManager().getAtlas().applyCoords(model, 0f);
        modelMaterial.setTexture("DiffuseMap", config.getTextureAtlasManager().getDiffuseMap());
        model.setLocalTranslation(0, 0, 0);
        return model;
    }

    public void addListener(@NonNull PlayerListener listener) {
        listeners.add(listener);
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }


}

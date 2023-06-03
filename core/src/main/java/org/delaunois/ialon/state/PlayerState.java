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
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import com.simsilica.lemur.Label;

import org.delaunois.ialon.ChunkLightManager;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.PlayerListener;
import org.delaunois.ialon.WorldManager;
import org.delaunois.ialon.control.ButtonHighlightControl;
import org.delaunois.ialon.control.CamFollowSpatialControl;
import org.delaunois.ialon.control.PlaceholderControl;
import org.delaunois.ialon.control.PlayerActionControl;
import org.delaunois.ialon.control.PlayerCamDirectionControl;
import org.delaunois.ialon.control.PlayerCharacterControl;
import org.delaunois.ialon.control.PlayerFlyControl;
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
        worldManager = new WorldManager(
                config.getChunkManager(),
                new ChunkLightManager(config),
                Optional.ofNullable(app.getStateManager().getState(ChunkLiquidManagerState.class))
                        .map(ChunkLiquidManagerState::getChunkLiquidManager).orElse(null)
        );

        playerNode = createPlayer(app, config, worldManager);
        playerCharacterControl = playerNode.getControl(PlayerCharacterControl.class);
        playerActionControl = playerNode.getControl(PlayerActionControl.class);
        playerWalkControl = playerNode.getControl(PlayerWalkControl.class);
        playerFlyControl = playerNode.getControl(PlayerFlyControl.class);
    }

    @Override
    public void update(float tpf) {
        // Nothing to do
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
            placeholderControl = new PlaceholderControl(chunkNode, app);
            playerNode.addControl(placeholderControl);
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
        ButtonHighlightControl buttonHighlightControl = buttonManagerState.getButtonFly().getControl(ButtonHighlightControl.class);
        if (buttonHighlightControl != null) {
            buttonHighlightControl.setStatusSupplier(playerActionControl::isFly);
            buttonHighlightControl.highlight(playerActionControl.isFly());
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

        if (config.getPlayerRotation() != null) {
            float[] angles = new float[3];
            config.getPlayerRotation().toAngles(angles);
            app.getCamera().setRotation(config.getPlayerRotation().fromAngles(angles[0], angles[1], 0));
        }

        PlayerWalkControl walkControl = new PlayerWalkControl(config, app.getCamera());
        PlayerRailControl railControl = new PlayerRailControl(config, worldManager, app.getCamera());
        PlayerFlyControl flyControl = new PlayerFlyControl(config, app.getCamera());
        PlayerCamDirectionControl camDirectionControl = new PlayerCamDirectionControl(config, app.getInputManager(), app.getCamera());
        CamFollowSpatialControl camFollowSpatialControl = new CamFollowSpatialControl(app.getCamera());
        if (config.isDebugCollisions()) {
            camFollowSpatialControl.setLocalTranslation(new Vector3f(-2, config.getPlayerHeight() / 2 - 0.15f, 0));
        } else {
            // The player location is at the center of the capsule shape. So we need to level the camera
            // up to set it near the top of the shape (its eyes).
            camFollowSpatialControl.setLocalTranslation(new Vector3f(0, config.getPlayerHeight() / 2 - 0.15f, 0));
        }

        PlayerActionControl actionControl = new PlayerActionControl(app, config);

        Node node = new Node("Player");
        node.setLocalTranslation(config.getPlayerLocation());
        node.setLocalRotation(config.getPlayerRotation());

        node.addControl(characterControl);
        node.addControl(camFollowSpatialControl);
        node.addControl(camDirectionControl);
        node.addControl(flyControl);
        node.addControl(walkControl);
        node.addControl(railControl);
        node.addControl(actionControl);

        return node;
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

    public void addListener(@NonNull PlayerListener listener) {
        listeners.add(listener);
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }


}

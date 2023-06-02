package org.delaunois.ialon.control;

import com.jme3.app.SimpleApplication;
import com.jme3.input.controls.ActionListener;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.Direction;
import com.rvandoosselaer.blocks.ShapeIds;
import com.rvandoosselaer.blocks.TypeIds;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.ChunkManager;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.WorldManager;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static org.delaunois.ialon.IalonKeyMapping.ACTION_ADD_BLOCK;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_DEBUG_CHUNK;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_FLY;
import static org.delaunois.ialon.IalonKeyMapping.ACTION_REMOVE_BLOCK;

@Slf4j
public class PlayerActionControl extends AbstractControl implements ActionListener {

    private static final String[] ACTIONS = new String[]{
            ACTION_ADD_BLOCK,
            ACTION_REMOVE_BLOCK,
            ACTION_FLY,
            ACTION_DEBUG_CHUNK
    };

    @Getter
    private boolean fly;

    private WorldManager worldManager;
    private PlayerControl playerControl;
    private PlaceholderControl placeholderControl;
    private PlayerFlyControl playerFlyControl;
    private PlayerWalkControl playerWalkControl;
    private PlayerRailControl playerRailControl;
    private PlayerCamDirectionControl playerCamDirectionControl;

    private final SimpleApplication app;
    private final IalonConfig config;

    public PlayerActionControl(SimpleApplication app, IalonConfig config) {
        this.config = config;
        this.app = app;
        setFly(config.isPlayerStartFly());
        config.getInputActionManager().addListener(this, ACTIONS);
    }

    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        this.playerControl = spatial.getControl(PlayerControl.class);
        assert(playerControl != null);
        this.worldManager = playerControl.getWorldManager();
        loadControls();
    }

    private void loadControls() {
        if (placeholderControl == null) {
            placeholderControl = spatial.getControl(PlaceholderControl.class);
        }
        if (playerCamDirectionControl == null) {
            playerCamDirectionControl = spatial.getControl(PlayerCamDirectionControl.class);
        }
        if (playerFlyControl == null) {
            playerFlyControl = spatial.getControl(PlayerFlyControl.class);
        }
        if (playerWalkControl == null) {
            playerWalkControl = spatial.getControl(PlayerWalkControl.class);
        }
        if (playerRailControl == null) {
            playerRailControl = spatial.getControl(PlayerRailControl.class);
        }
    }

    @Override
    protected void controlUpdate(float tpf) {
        // Nothing to do
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (isEnabled()) {
            loadControls();
            config.getInputActionManager().addListener(this, ACTIONS);
        } else {
            config.getInputActionManager().removeListener(this);
        }
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        loadControls();

        if (log.isDebugEnabled()) {
            log.debug("Action {} isPressed {}", name, isPressed);
        }

        switch (name) {
            case ACTION_ADD_BLOCK:
                addBlock(isPressed);
                break;
            case ACTION_REMOVE_BLOCK:
                removeBlock(isPressed);
                break;
            case ACTION_FLY:
                toogleFly(isPressed);
                break;
            case ACTION_DEBUG_CHUNK:
                actionDebugChunks(isPressed);
                break;
            default:
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

        if (fly) {
            if (playerRailControl != null) {
                playerRailControl.stop();
                playerRailControl.setEnabled(false);
            }
            if (playerWalkControl != null) {
                playerWalkControl.setEnabled(false);
            }
            if (playerFlyControl != null) {
                playerFlyControl.setEnabled(true);
            }
        } else {
            if (playerRailControl != null) {
                playerRailControl.setEnabled(true);
            }
            if (playerWalkControl != null) {
                playerWalkControl.setEnabled(true);
            }
            if (playerFlyControl != null) {
                playerFlyControl.setEnabled(false);
            }
        }
    }

    public void addBlock(boolean isPressed) {
        if (!isPressed) {
            return;
        }

        log.info("Action : addBlock triggered");

        if (placeholderControl == null || placeholderControl.getRemovePlaceholder().getParent() == null) {
            log.info("Not adding. No placeholder.");
            return;
        }

        final Vector3f worldBlockLocation = placeholderControl.getAddPlaceholder().getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
        Vec3i playerBlockLocation = ChunkManager.getBlockLocation(playerControl.getPlayerLocation());
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

        app.enqueue(() -> addBlockTask(worldBlockLocation, selectedBlock));
    }

    private void addBlockTask(Vector3f location, Block block) {
        // Orientate the selected block
        Block orientatedBlock = worldManager.orientateBlock(block, location,
                playerCamDirectionControl.getCamDir(),
                Direction.fromVector(placeholderControl.getAddPlaceholder().getWorldTranslation()
                        .subtract(placeholderControl.getRemovePlaceholder().getWorldTranslation())));

        if (orientatedBlock == null) {
            // Can't place the block like this
            return;
        }

        if (TypeIds.WATER.equals(block.getType())) {
            Vector3f tmp = placeholderControl.getRemovePlaceholder().getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
            Block previousBlock = worldManager.getBlock(tmp);
            if (previousBlock != null && !ShapeIds.CUBE.equals(previousBlock.getShape())) {
                location = tmp;
            }
        }

        worldManager.addBlock(location, orientatedBlock);
    }

    public void removeBlock(boolean isPressed) {
        if (!isPressed) {
            return;
        }

        log.info("Action : removeBlock triggered");

        if (placeholderControl == null || placeholderControl.getRemovePlaceholder().getParent() == null) {
            log.info("Not removing. No parent for placeholder");
            return;
        }

        app.enqueue(() -> removeBlockTask(placeholderControl
                .getRemovePlaceholder()
                .getWorldTranslation()
                .subtract(0.5f, 0.5f, 0.5f)));
    }

    private void removeBlockTask(Vector3f blockLocation) {
        worldManager.removeBlock(blockLocation);
    }

    public void actionDebugChunks(boolean isPressed) {
        if (isPressed) {
            config.setDebugChunks(!config.isDebugChunks());
        }
    }

}

package org.delaunois.ialon.control;

import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_ADD_BLOCK;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_DEBUG_CHUNK;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_FLY;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_REMOVE_BLOCK;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_ACTION_OBJECT;

import com.jme3.app.SimpleApplication;
import com.jme3.input.controls.ActionListener;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.Direction;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeIds;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.state.BlockPickingMode;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PlayerActionControl extends AbstractControl implements ActionListener {

    private static final String[] ACTIONS = new String[]{
            ACTION_ADD_BLOCK,
            ACTION_REMOVE_BLOCK,
            ACTION_ACTION_OBJECT,
            ACTION_FLY,
            ACTION_DEBUG_CHUNK
    };

    @Getter
    private boolean fly;

    private Spatial head;
    private WorldManager worldManager;
    private PlayerCharacterControl playerCharacterControl;
    private PlaceholderControl placeholderControl;
    private PlayerFlyControl playerFlyControl;
    private PlayerWalkControl playerWalkControl;
    private PlayerRailControl playerRailControl;
    private PlayerHeadDirectionControl playerHeadDirectionControl;

    private final SimpleApplication app;
    private final IalonConfig config;

    public PlayerActionControl(SimpleApplication app, IalonConfig config) {
        this.config = config;
        this.app = app;
        config.getInputActionManager().addListener(this, ACTIONS);
    }

    @Override
    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        this.head = ((Node)spatial).getChild("Head");
        this.playerCharacterControl = spatial.getControl(PlayerCharacterControl.class);
        assert(playerCharacterControl != null);
        this.worldManager = playerCharacterControl.getWorldManager();
        loadControls();
        setFly(config.isPlayerStartFly());
    }

    private void loadControls() {
        if (placeholderControl == null) {
            placeholderControl = head.getControl(PlaceholderControl.class);
        }
        if (playerHeadDirectionControl == null) {
            playerHeadDirectionControl = head.getControl(PlayerHeadDirectionControl.class);
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

        // During a block-picking mode (creation capture/placement) the world editing is suspended : a
        // left-click or the action button confirms (capture: select the aimed block ; placement: stamp the
        // creation), and a right-click (ACTION_REMOVE_BLOCK on desktop) aborts the mode.
        BlockPickingMode picking = BlockPickingMode.active(app.getStateManager());

        switch (name) {
            case ACTION_ADD_BLOCK:
                if (picking != null) {
                    if (isPressed) {
                        picking.onPick(pickedCell());
                    }
                } else {
                    addBlock(isPressed);
                }
                break;
            case ACTION_REMOVE_BLOCK:
                if (picking != null) {
                    if (isPressed) {
                        picking.onCancel();
                    }
                } else {
                    removeBlock(isPressed);
                }
                break;
            case ACTION_ACTION_OBJECT:
                if (picking != null) {
                    if (isPressed) {
                        picking.onPick(pickedCell());
                    }
                } else {
                    toggleDoor(isPressed);
                }
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
        if (!isPressed || config.getSelectedBlock() == null) {
            return;
        }

        log.debug("Action : addBlock triggered");

        if (placeholderControl == null || placeholderControl.getRemovePlaceholder().getParent() == null) {
            log.debug("Not adding. No placeholder.");
            return;
        }

        final Vector3f worldBlockLocation = placeholderControl.getAddPlaceholder().getWorldTranslation().subtract(0.5f, 0.5f, 0.5f);
        Vec3i playerBlockLocation = ChunkManager.getBlockLocation(playerCharacterControl.getPlayerLocation());
        Vec3i blockLocation = ChunkManager.getBlockLocation(worldBlockLocation);

        // Prevents adding a solid block where the player stands
        if (config.getSelectedBlock().isSolid()
                && blockLocation.x == playerBlockLocation.x
                && blockLocation.z == playerBlockLocation.z
                && (blockLocation.y == playerBlockLocation.y || blockLocation.y == playerBlockLocation.y + 1)) {
            log.debug("Can't add a solid block where the player stands");
            return;
        }

        app.enqueue(() -> addBlockTask(worldBlockLocation, config.getSelectedBlock()));
    }

    private void addBlockTask(Vector3f location, Block block) {
        // Orientate the selected block
        Block orientatedBlock = worldManager.orientateBlock(block, location,
                playerHeadDirectionControl.getCamDir(),
                Direction.fromVector(placeholderControl.getAddPlaceholder().getWorldTranslation()
                        .subtract(placeholderControl.getRemovePlaceholder().getWorldTranslation())));

        if (orientatedBlock == null) {
            // Can't place the block like this
            return;
        }

        // Fire cannot be placed inside water : the target cell would be flooded.
        if (TypeIds.FIRE.equals(block.getType())) {
            Block target = worldManager.getBlock(location);
            if (target != null && TypeIds.WATER.equals(target.getType())) {
                log.debug("Can't place fire in water");
                return;
            }
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

        log.debug("Action : removeBlock triggered");

        if (placeholderControl == null || placeholderControl.getRemovePlaceholder().getParent() == null) {
            log.debug("Not removing. No parent for placeholder");
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

    public void toggleDoor(boolean isPressed) {
        if (!isPressed) {
            return;
        }

        log.debug("Action : toggleDoor triggered");

        if (placeholderControl == null || placeholderControl.getRemovePlaceholder().getParent() == null) {
            log.debug("Not toggling. No placeholder.");
            return;
        }

        final Vector3f blockLocation = placeholderControl.getRemovePlaceholder()
                .getWorldTranslation()
                .subtract(0.5f, 0.5f, 0.5f);
        app.enqueue(() -> worldManager.toggleDoor(blockLocation));
    }

    /**
     * The block currently aimed at (under the remove placeholder), or {@code null} when nothing is aimed.
     * Fed to the active picking mode so it can select / confirm against it.
     */
    private Vec3i pickedCell() {
        if (placeholderControl == null || placeholderControl.getRemovePlaceholder().getParent() == null) {
            return null;
        }
        return ChunkManager.getBlockLocation(placeholderControl.getRemovePlaceholderPosition());
    }

    public void actionDebugChunks(boolean isPressed) {
        if (isPressed) {
            config.setDebugChunks(!config.isDebugChunks());
        }
    }

}

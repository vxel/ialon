package org.delaunois.ialon.control;

import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_ACTION_OBJECT;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_ADD_BLOCK;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_DEBUG_CHUNK;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_FLY;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_REMOVE_BLOCK;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_RESET_CHUNK;

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
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.state.BlockPickingMode;
import org.delaunois.ialon.state.ChunkResetState;
import org.delaunois.ialon.state.RegionAddState;
import org.delaunois.ialon.state.RegionDeleteState;
import org.delaunois.ialon.state.RegionSelectionState;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PlayerActionControl extends AbstractControl implements ActionListener {

    private static final String[] ACTIONS = new String[]{
            ACTION_ADD_BLOCK,
            ACTION_REMOVE_BLOCK,
            ACTION_ACTION_OBJECT,
            ACTION_RESET_CHUNK,
            ACTION_FLY,
            ACTION_DEBUG_CHUNK
    };

    /**
     * How long the "-" button (or the DELETE key) must be held to switch to the region-delete mode
     * instead of removing the single aimed block.
     */
    private static final float LONG_PRESS_SECONDS = 0.5f;

    @Getter
    private boolean fly;

    // Press tracking for the two block-editing controls. Both act on RELEASE, because holding either of
    // them is a gesture of its own : it opens the matching bulk mode (fill for "+", delete for "-"), and
    // that has to be decided before a single block is placed or destroyed.
    //
    // The pressed flags also guard against a release that was never preceded by a press : the on-screen
    // buttons synthesise one when the cursor leaves them (ButtonManagerState#mouseExited), so merely
    // sliding over a button would otherwise place or break a block.
    private boolean addPressed;
    private float addHeld;
    private boolean addLongFired;
    private boolean removePressed;
    private float removeHeld;
    private boolean removeLongFired;

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

    /**
     * Times the "+" and "-" holds. This has to live here rather than in the action callback : both input
     * routes deliver the press with {@code tpf = 0} ({@code InputActionManager.triggerAction}), so the
     * only real clock available is the per-frame tick.
     */
    @Override
    protected void controlUpdate(float tpf) {
        if (addPressed && !addLongFired) {
            addHeld += tpf;
            if (addHeld >= LONG_PRESS_SECONDS) {
                addLongFired = true;
                enterRegionMode(RegionAddState.class, "add");
            }
        }
        if (removePressed && !removeLongFired) {
            removeHeld += tpf;
            if (removeHeld >= LONG_PRESS_SECONDS) {
                // Consumed either way : the timer stops here, so a missing state warns once instead of
                // every frame for as long as the button is held.
                removeLongFired = true;
                enterRegionMode(RegionDeleteState.class, "remove");
            }
        }
    }

    /** Switches to a bulk selection mode ; the pending release will be swallowed. */
    private void enterRegionMode(Class<? extends RegionSelectionState> type, String origin) {
        RegionSelectionState state = app.getStateManager().getState(type);
        if (state == null) {
            log.warn("No {} attached : long press ignored", type.getSimpleName());
            return;
        }
        log.debug("Action : long press on {} -> {}", origin, type.getSimpleName());
        // The block under the crosshair when the hold fires becomes the first corner : the press already
        // aimed at it, so the player only has the two remaining points to pick.
        state.enter(pickedCell());
    }

    /** Forgets any in-flight "+" hold, so a mode switch or a disable cannot leave a phantom press. */
    private void resetAddPress() {
        addPressed = false;
        addHeld = 0;
        addLongFired = false;
    }

    /** Forgets any in-flight "-" hold, so a mode switch or a disable cannot leave a phantom press. */
    private void resetRemovePress() {
        removePressed = false;
        removeHeld = 0;
        removeLongFired = false;
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
            // No listener left to deliver the releases : drop them rather than act on them (or resume
            // timing a hold, and open a bulk mode) when the control comes back.
            resetAddPress();
            resetRemovePress();
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
                    // A hold that just opened a picking mode must not also confirm its first point : the
                    // press that triggered the switch is still down, and its release lands here.
                    resetAddPress();
                    if (isPressed) {
                        picking.onPick(pickedCell());
                    }
                } else {
                    addBlockPress(isPressed);
                }
                break;
            case ACTION_REMOVE_BLOCK:
                if (picking != null) {
                    // A hold that just opened a picking mode must not immediately cancel it : the press
                    // that triggered the switch is still down, and its release lands here.
                    resetRemovePress();
                    if (isPressed) {
                        picking.onCancel();
                    }
                } else {
                    removeBlockPress(isPressed);
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
            case ACTION_RESET_CHUNK:
                if (isPressed) {
                    resetChunk();
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

    /**
     * The "Reset chunk" button, offered in the "-" slot while the region-delete selection is up : it drops
     * that selection and asks {@link ChunkResetState} to confirm reverting the player's chunk to generated
     * terrain. The selection goes first — the two are alternatives, and its preview box and guidance
     * message have no business showing under the confirmation dialog.
     */
    private void resetChunk() {
        ChunkResetState chunkReset = app.getStateManager().getState(ChunkResetState.class);
        if (chunkReset == null) {
            log.warn("No ChunkResetState attached : reset chunk ignored");
            return;
        }
        log.debug("Action : reset chunk");
        BlockPickingMode picking = BlockPickingMode.active(app.getStateManager());
        if (picking != null) {
            picking.onCancel();
        }
        chunkReset.askConfirmation();
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

    /**
     * Handles a press or release of the "+" control : a tap places the aimed block on <b>release</b>,
     * a hold opens the region-fill mode instead (see {@link #controlUpdate}).
     */
    public void addBlockPress(boolean isPressed) {
        if (isPressed) {
            addPressed = true;
            addHeld = 0;
            addLongFired = false;
            return;
        }
        if (!addPressed) {
            return; // a release we never saw the press of : the cursor merely slid off the button
        }
        boolean wasLongPress = addLongFired;
        resetAddPress();
        if (!wasLongPress) {
            addBlock();
        }
    }

    public void addBlock() {
        final Block selectedBlock = config.getSelectedBlock();
        if (selectedBlock == null) {
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
        if (selectedBlock.isSolid()
                && blockLocation.x == playerBlockLocation.x
                && blockLocation.z == playerBlockLocation.z
                && (blockLocation.y == playerBlockLocation.y || blockLocation.y == playerBlockLocation.y + 1)) {
            log.debug("Can't add a solid block where the player stands");
            return;
        }

        app.enqueue(() -> addBlockTask(worldBlockLocation, selectedBlock));
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

        if (block.isLiquidSource()) {
            // Waterlogging : a water source aimed at a non-full (non-cube) block floods that block's own
            // cell (co-habiting with it) instead of landing in the adjacent cell. Any other block, or a
            // full-cube / non-floodable target, keeps the normal adjacent-cell placement.
            Vector3f aimed = placeholderControl.getRemovePlaceholder().getWorldTranslation()
                    .subtract(0.5f, 0.5f, 0.5f);
            Block target = worldManager.getBlock(aimed);
            if (target != null
                    && !ShapeIds.CUBE.equals(target.getShape())
                    && target.getLiquidLevelId() != Block.LIQUID_DISABLED
                    && !target.isLiquidSource()) {
                location = aimed;
            }
        }

        worldManager.addBlock(location, orientatedBlock);
    }

    /**
     * Handles a press or release of the "-" control.
     *
     * <p>The single-block removal fires on <b>release</b>, not on press : the same control also opens
     * the region-delete mode when held, and that has to be decided before anything is destroyed.
     * A tap still removes the aimed block, one frame later than it used to.</p>
     */
    public void removeBlockPress(boolean isPressed) {
        if (isPressed) {
            removePressed = true;
            removeHeld = 0;
            removeLongFired = false;
            return;
        }

        if (!removePressed) {
            return; // a release we never saw the press of : the cursor merely slid off the button
        }
        boolean wasLongPress = removeLongFired;
        resetRemovePress();
        if (!wasLongPress) {
            removeBlock();
        }
    }

    public void removeBlock() {
        log.debug("Action : removeBlock triggered");

        if (placeholderControl == null || placeholderControl.getRemovePlaceholder().getParent() == null) {
            log.debug("Not removing. No parent for placeholder");
            return;
        }

        final Vector3f blockLocation = placeholderControl
                .getRemovePlaceholder()
                .getWorldTranslation()
                .subtract(0.5f, 0.5f, 0.5f);

        app.enqueue(() -> worldManager.removeBlock(blockLocation));
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

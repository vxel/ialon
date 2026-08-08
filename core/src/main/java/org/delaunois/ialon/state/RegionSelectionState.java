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

import static org.delaunois.ialon.Ialon.IALON_STYLE;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.material.RenderState.FaceCullMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.debug.WireBox;
import com.jme3.scene.shape.Box;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Label;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.ui.ConfirmDialog;
import org.delaunois.ialon.ui.IalonTheme;
import org.delaunois.ialon.ui.UiHelper;

import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared machinery for the bulk-editing modes : the player outlines a box with three picks, confirms,
 * and the whole box is edited in one go.
 *
 * <p>The gesture is the one {@link CreationCaptureState} established — the first two picks define the
 * <b>base rectangle</b> (its X/Z footprint and base level), the third the <b>height</b> — down to
 * reusing {@link CreationCaptureState#computeVolume}. Entered by holding one of the on-screen editing
 * buttons (see {@code PlayerActionControl}). While selecting, the block-selection, minimap and
 * time-factor UI are hidden and the add/remove buttons are replaced by the action button (see
 * {@link ButtonManagerState#setBuildMode(boolean)}).</p>
 *
 * <p>Each side of the box is capped at {@value #MAX_REGION_SIDE} blocks. Rather than refusing an
 * oversized pick, the box simply stops growing on the offending axis (see {@link #clampToMaxSide}) :
 * the preview therefore always shows exactly what will be edited.</p>
 *
 * <p>Subclasses supply only what actually differs between the modes : the preview colour, the wording
 * and count on the confirmation dialog, and the edit itself.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public abstract class RegionSelectionState extends BaseAppState
        implements Resizable, BlockPickingMode, Popup {

    /**
     * Longest side, in blocks, a selection may have. A bulk edit re-lights and re-meshes every chunk it
     * touches ; this cap keeps the worst case (a few dozen chunks) within what a mid-range Android
     * device absorbs in a short burst.
     */
    public static final int MAX_REGION_SIDE = 32;

    private static final String[] STEP_MESSAGES = {
            "Select the first base corner",
            "Select the opposite base corner",
            "Select the height"
    };

    // How long the closing confirmation stays on screen after the edit completes.
    private static final float CONFIRM_SECONDS = 3f;

    protected final IalonConfig config;
    protected SimpleApplication app;

    // Reused bounds of the live/final box, in block coordinates (inclusive), already clamped.
    protected final Vec3i boundsMin = new Vec3i();
    protected final Vec3i boundsMax = new Vec3i();

    private final Vec3i[] points = new Vec3i[3];
    private final Vector3f scratch = new Vector3f();

    private Label hud;
    private Node previewNode;
    private boolean selecting;
    private int step;
    private Vec3i currentTarget;
    // The confirmation dialog, while it is up. Non-null means the selection is frozen.
    private Container confirmOverlay;
    // Seconds left to display the closing confirmation message (0 = none showing).
    private float confirmTimeLeft;

    protected RegionSelectionState(IalonConfig config) {
        this.config = config;
    }

    // --- What the modes differ by -----------------------------------------------------------------

    /** The translucent fill colour of the preview box. Its outline reuses the same hue, opaque. */
    protected abstract ColorRGBA previewColor();

    /**
     * Number of cells the edit will actually change inside the current box — the count shown on the
     * confirmation dialog. Zero produces the "nothing to do" wording.
     */
    protected abstract int countAffectedCells();

    /** The confirmation dialog's question, e.g. {@code "Delete 219 blocks ?"}. */
    protected abstract String confirmQuestion(int affected);

    /** Label of the confirming button, naming the act rather than agreeing with a question. */
    protected abstract String confirmButtonLabel();

    /** The wording of the transient HUD message shown once the edit is under way. */
    protected abstract String doneMessage(int affected);

    /** Applies the edit to {@code min..max} (inclusive). Runs on the update thread. */
    protected abstract void applyEdit(WorldManager worldManager, Vec3i min, Vec3i max);

    // --- Lifecycle --------------------------------------------------------------------------------

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        hud = new Label("", IALON_STYLE);
        hud.setColor(ColorRGBA.White);
        hud.setTextHAlignment(HAlignment.Center);
        previewNode = createPreviewNode();
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());
        ScreenState.registerOn(app, this);
    }

    @Override
    protected void cleanup(Application application) {
        ScreenState.unregisterFrom(application, this);
    }

    @Override
    protected void onEnable() {
        // Nothing : the mode is driven by enter()/exit(), not by enable/disable.
    }

    @Override
    protected void onDisable() {
        // Nothing.
    }

    @Override
    public boolean isPicking() {
        return selecting;
    }

    @Override
    public void onPick(Vec3i cell) {
        addSelectedBlock(cell);
    }

    @Override
    public void onCancel() {
        cancel();
    }

    /** Starts the selection : hides the editing UI and shows the action button + guidance HUD. */
    public void enter() {
        if (selecting) {
            return;
        }
        selecting = true;
        confirmTimeLeft = 0; // clear any lingering confirmation
        step = 0;
        currentTarget = null;
        points[0] = null;
        points[1] = null;
        points[2] = null;

        setUiEnabled(false);
        ButtonManagerState buttons = app.getStateManager().getState(ButtonManagerState.class);
        if (buttons != null) {
            buttons.setBuildMode(true);
        }
        showHud();
        updateHud();
        refreshPreview();
    }

    /** Cancels an in-progress selection without editing anything. */
    public void cancel() {
        if (selecting) {
            exit();
        }
    }

    /**
     * The block currently aimed at (or {@code null} when nothing is targeted), pushed each placeholder
     * tick by {@link org.delaunois.ialon.control.PlaceholderControl} while selecting. Drives the live
     * preview box.
     */
    @Override
    public void onTarget(Vec3i cell) {
        if (!selecting || confirmOverlay != null) {
            return;
        }
        currentTarget = cell;
        refreshPreview();
    }

    /**
     * Records the block at {@code loc} as the next selection point. Once three points are gathered, the
     * confirmation dialog opens. Called from the player's action handler on the update thread.
     */
    public void addSelectedBlock(Vec3i loc) {
        if (!selecting || loc == null || confirmOverlay != null) {
            return;
        }
        points[step] = new Vec3i(loc);
        step++;
        if (step < 3) {
            updateHud();
            refreshPreview();
        } else {
            askConfirmation();
        }
    }

    // --- Confirmation -----------------------------------------------------------------------------

    /** Freezes the selection on its final box and asks the player to confirm. */
    private void askConfirmation() {
        computeBounds(points[0], points[1], points[2]);
        refreshPreviewBox();

        int sizeX = boundsMax.x - boundsMin.x + 1;
        int sizeY = boundsMax.y - boundsMin.y + 1;
        int sizeZ = boundsMax.z - boundsMin.z + 1;
        int affected = countAffectedCells();

        hideHud();
        Container overlay = ConfirmDialog.createModalOverlay(config);
        confirmOverlay = overlay;

        Container dialog = ConfirmDialog.createDialogBox(app);
        dialog.addChild(ConfirmDialog.title(app, confirmQuestion(affected)), 0, 0);
        dialog.addChild(ConfirmDialog.title(app, "(" + sizeX + " x " + sizeY + " x " + sizeZ + ")"), 1, 0);

        Button confirm = ConfirmDialog.button(app, confirmButtonLabel());
        confirm.addClickCommands(source -> {
            closeConfirm();
            runEdit(affected);
        });
        Button cancel = ConfirmDialog.button(app, "Cancel");
        cancel.addClickCommands(source -> {
            closeConfirm();
            exit();
        });
        dialog.addChild(IalonTheme.dialogButtonRow(app.getCamera().getHeight(), confirm, cancel), 2, 0);

        ConfirmDialog.showOverScreen(app, overlay, dialog);
        // The veil swallows clicks, but not the camera drag : without this a finger sliding over the
        // dialog would still turn the view behind it.
        setPlayerTouchEnabled(false);
    }

    private void closeConfirm() {
        if (confirmOverlay != null) {
            confirmOverlay.removeFromParent();
            confirmOverlay = null;
            setPlayerTouchEnabled(true);
        }
    }

    /**
     * Runs the edit on the update thread (like every other world edit) and leaves the mode with a
     * transient confirmation on the HUD.
     */
    private void runEdit(int affected) {
        WorldManager worldManager = Optional
                .ofNullable(app.getStateManager().getState(PlayerState.class))
                .map(PlayerState::getWorldManager)
                .orElse(null);
        // Snapshot the box before leaving the mode : exit() clears the selection state.
        Vec3i min = new Vec3i(boundsMin);
        Vec3i max = new Vec3i(boundsMax);

        exit();

        if (worldManager == null) {
            log.warn("No WorldManager available : bulk edit skipped");
            return;
        }

        app.enqueue(() -> applyEdit(worldManager, min, max));

        // exit() has just hidden the guidance label ; re-show it with the confirmation, then update()
        // removes it after a few seconds.
        hud.setText(doneMessage(affected));
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());
        showHud();
        confirmTimeLeft = CONFIRM_SECONDS;
    }

    // --- Popup ------------------------------------------------------------------------------------

    /**
     * True for the <b>whole</b> mode, not just while the confirmation dialog is up : ESC quits the game
     * when no popup claims it, so the selection steps have to claim it too or aiming the first corner
     * and pressing ESC would close Ialon.
     */
    @Override
    public boolean isPopupOpen() {
        return selecting;
    }

    /** ESC : abandons the selection at any step, dialog included. Nothing is edited. */
    @Override
    public void closePopup() {
        cancel();
    }

    @Override
    public void update(float tpf) {
        if (confirmTimeLeft > 0) {
            confirmTimeLeft -= tpf;
            if (confirmTimeLeft <= 0) {
                hideHud();
            }
        }
    }

    private void exit() {
        selecting = false;
        currentTarget = null;
        closeConfirm();
        setUiEnabled(true);
        ButtonManagerState buttons = app.getStateManager().getState(ButtonManagerState.class);
        if (buttons != null) {
            buttons.setBuildMode(false);
        }
        hideHud();
        previewNode.removeFromParent();
    }

    /** Enables or disables the editing UI hidden during selection (block selection, minimap, time factor). */
    private void setUiEnabled(boolean enabled) {
        setStateEnabled(BlockSliderSelectionState.class, enabled);
        setStateEnabled(MinimapState.class, enabled);
        setStateEnabled(TimeFactorState.class, enabled);
    }

    private void setStateEnabled(Class<? extends AppState> type, boolean enabled) {
        AppState state = app.getStateManager().getState(type);
        if (state != null) {
            state.setEnabled(enabled);
        }
    }

    private void setPlayerTouchEnabled(boolean enabled) {
        Optional.ofNullable(app.getStateManager().getState(PlayerState.class))
                .ifPresent(ps -> ps.setTouchEnabled(enabled));
    }

    // --- Bounds -----------------------------------------------------------------------------------

    /**
     * Iterates the cells of the current box, handing each one its world location. The location is a
     * <b>reused</b> scratch vector : read it, do not keep it.
     */
    protected void forEachCell(java.util.function.Consumer<Vector3f> action) {
        float scale = BlocksConfig.getInstance().getBlockScale();
        for (int y = boundsMin.y; y <= boundsMax.y; y++) {
            for (int z = boundsMin.z; z <= boundsMax.z; z++) {
                for (int x = boundsMin.x; x <= boundsMax.x; x++) {
                    scratch.set((x + 0.5f) * scale, (y + 0.5f) * scale, (z + 0.5f) * scale);
                    action.accept(scratch);
                }
            }
        }
    }

    /** Number of cells of the current box holding a block. */
    protected int countFilledCells() {
        ChunkManager chunkManager = config.getChunkManager();
        int[] count = {0};
        forEachCell(loc -> {
            if (chunkManager.getBlock(loc).isPresent()) {
                count[0]++;
            }
        });
        return count[0];
    }

    /**
     * Fills {@link #boundsMin}/{@link #boundsMax} with the selection box, clamped to
     * {@value #MAX_REGION_SIDE} per axis around the first picked corner.
     */
    private void computeBounds(Vec3i a, Vec3i b, Vec3i heightPoint) {
        CreationCaptureState.computeVolume(a, b, heightPoint, boundsMin, boundsMax);
        clampToMaxSide(a, boundsMin, boundsMax);
    }

    /**
     * Shrinks a box to at most {@value #MAX_REGION_SIDE} cells per axis, keeping {@code anchor} inside
     * it : each side grows away from the anchor and simply stops there. Static and side-effect free for
     * unit testing.
     *
     * <p>Because the anchor is the first picked corner and the opposite corner follows the aim, this
     * reads on screen as the box refusing to grow any further in that direction — no error state, and
     * the preview keeps showing exactly the cells that will be edited.</p>
     *
     * @param anchor the corner to keep (the first picked block)
     * @param min the inclusive lower corner, updated in place
     * @param max the inclusive upper corner, updated in place
     */
    static void clampToMaxSide(Vec3i anchor, Vec3i min, Vec3i max) {
        min.x = clampLow(anchor.x, min.x, max.x);
        max.x = clampHigh(anchor.x, min.x, max.x);
        min.y = clampLow(anchor.y, min.y, max.y);
        max.y = clampHigh(anchor.y, min.y, max.y);
        min.z = clampLow(anchor.z, min.z, max.z);
        max.z = clampHigh(anchor.z, min.z, max.z);
    }

    /** Raises {@code min} just enough for the side to fit, never above the anchor. */
    private static int clampLow(int anchor, int min, int max) {
        return Math.max(min, Math.min(anchor, max - MAX_REGION_SIDE + 1));
    }

    /** Lowers {@code max} just enough for the side to fit, never below the anchor. */
    private static int clampHigh(int anchor, int min, int max) {
        return Math.min(max, Math.max(anchor, min + MAX_REGION_SIDE - 1));
    }

    // --- Preview ----------------------------------------------------------------------------------

    /**
     * Recomputes the live preview box from the points selected so far and the block currently aimed at :
     * <ul>
     *   <li>step 0 (no point yet) : nothing — the normal block placeholder already highlights the aim;</li>
     *   <li>step 1 (first corner set) : the base rectangle forming between the first corner and the aim;</li>
     *   <li>step 2 (base set) : the full volume, its height following the aim.</li>
     * </ul>
     */
    private void refreshPreview() {
        boolean shown = false;
        if (step == 1 && currentTarget != null) {
            // Base rectangle : box between the first corner and the aim.
            computeBounds(points[0], currentTarget, null);
            shown = true;
        } else if (step == 2 && currentTarget != null) {
            // Volume : fixed X/Z footprint, height extended to the aim.
            computeBounds(points[0], points[1], currentTarget);
            shown = true;
        }

        if (!shown) {
            previewNode.removeFromParent();
            return;
        }

        refreshPreviewBox();
    }

    /** Places the preview node on the current {@link #boundsMin}/{@link #boundsMax} box. */
    private void refreshPreviewBox() {
        float scale = BlocksConfig.getInstance().getBlockScale();
        float sizeX = (boundsMax.x - boundsMin.x + 1) * scale;
        float sizeY = (boundsMax.y - boundsMin.y + 1) * scale;
        float sizeZ = (boundsMax.z - boundsMin.z + 1) * scale;
        // Inflate the box by a small margin so its faces never coincide with the enclosed block faces,
        // which would z-fight ; the centre stays on the true box centre.
        float eps = 0.04f * scale;
        previewNode.setLocalScale(sizeX + eps, sizeY + eps, sizeZ + eps);
        previewNode.setLocalTranslation(
                boundsMin.x * scale + sizeX / 2f,
                boundsMin.y * scale + sizeY / 2f,
                boundsMin.z * scale + sizeZ / 2f);
        if (previewNode.getParent() == null) {
            app.getRootNode().attachChild(previewNode);
        }
    }

    /**
     * A unit-cube node (a translucent fill + a wireframe outline) scaled/positioned to the preview box.
     * Its hue is the mode's, since the modes share a gesture and the colour is what tells the player
     * which edit is being outlined. Built once ; only its transform changes per tick, so the preview is
     * allocation-free.
     */
    private Node createPreviewNode() {
        ColorRGBA fillColor = previewColor();
        Node node = new Node(getClass().getSimpleName() + "Preview");

        Material fillMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        fillMat.setColor("Color", fillColor);
        fillMat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        fillMat.getAdditionalRenderState().setDepthWrite(false);
        fillMat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
        Geometry fill = new Geometry("RegionPreviewFill", new Box(0.5f, 0.5f, 0.5f));
        fill.setMaterial(fillMat);
        fill.setQueueBucket(Bucket.Transparent);
        node.attachChild(fill);

        Material wireMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        wireMat.setColor("Color", new ColorRGBA(fillColor.r, fillColor.g, fillColor.b, 1f));
        Geometry outline = new Geometry("RegionPreviewOutline", new WireBox(0.5f, 0.5f, 0.5f));
        outline.setMaterial(wireMat);
        node.attachChild(outline);

        return node;
    }

    // --- HUD --------------------------------------------------------------------------------------

    private void updateHud() {
        hud.setText(STEP_MESSAGES[Math.min(step, STEP_MESSAGES.length - 1)]);
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());
    }

    private void showHud() {
        if (hud.getParent() == null) {
            app.getGuiNode().attachChild(hud);
        }
    }

    private void hideHud() {
        hud.removeFromParent();
    }

    @Override
    public void onResize(int width, int height) {
        layout(width, height);
    }

    /**
     * Centers the HUD label horizontally, just below the top row of buttons (the settings gear / fly /
     * action), so it does not overlap them.
     */
    private void layout(int width, int height) {
        float vh = height / 100f;
        float topButtonsBottom = height - UiHelper.screenMargin(height) - height / 12f;
        hud.setFontSize(4 * vh);
        Vector3f size = hud.getPreferredSize();
        hud.setLocalTranslation((width - size.x) / 2f, topButtonsBottom - 2 * vh, 10);
    }
}

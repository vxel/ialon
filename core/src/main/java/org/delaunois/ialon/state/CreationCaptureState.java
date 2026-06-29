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
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Label;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.serialize.Creation;
import org.delaunois.ialon.serialize.CreationRepository;
import org.delaunois.ialon.ui.UiHelper;

import lombok.extern.slf4j.Slf4j;

/**
 * Drives the "new creation" capture mode. The player selects three blocks with the on-screen "action"
 * button : the first two define the <b>base rectangle</b> (its X/Z footprint and base level) and the
 * third defines the <b>height</b>. The resulting box is read from the world and saved as a reusable
 * {@link Creation} under {@code save/creations/}. An on-screen HUD label guides the player, and a live
 * preview (semi-transparent white box + wireframe outline) shows the rectangle, then the volume, as the
 * player aims.
 *
 * <p>While capturing, the block-selection, minimap and time-factor UI are disabled and the add/remove
 * block buttons are replaced by the action button (see {@link ButtonManagerState#setBuildMode(boolean)}).
 * Re-opening the world menu cancels an in-progress capture (see {@link WorldMenuState}).</p>
 *
 * <p>Limitation : blocks lying in chunks not currently paged in are read as air. In practice the player
 * walks the box's corners so the relevant chunks are loaded ; a very large box spanning unpaged chunks
 * would capture those cells as empty.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class CreationCaptureState extends BaseAppState implements Resizable, BlockPickingMode {

    private static final String[] STEP_MESSAGES = {
            "Select the first base corner",
            "Select the opposite base corner",
            "Select the height"
    };

    // How long the "creation saved" confirmation stays on screen after a capture completes.
    private static final float CONFIRM_SECONDS = 3f;

    private final IalonConfig config;
    private final Vec3i[] points = new Vec3i[3];
    private final Vector3f scratch = new Vector3f();
    // Reused bounds of the live/final box, in block coordinates (inclusive).
    private final Vec3i boundsMin = new Vec3i();
    private final Vec3i boundsMax = new Vec3i();

    private SimpleApplication app;
    private Label hud;
    private Node previewNode;
    private boolean capturing;
    private int step;
    private Vec3i currentTarget;
    // Seconds left to display the post-capture confirmation message (0 = none showing).
    private float confirmTimeLeft;

    public CreationCaptureState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        hud = new Label("", IALON_STYLE);
        hud.setColor(ColorRGBA.White);
        hud.setTextHAlignment(HAlignment.Center);
        previewNode = createPreviewNode();
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());
        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).register(this);
        }
    }

    @Override
    protected void cleanup(Application application) {
        if (application.getStateManager().getState(ScreenState.class) != null) {
            application.getStateManager().getState(ScreenState.class).unregister(this);
        }
    }

    @Override
    protected void onEnable() {
        // Nothing : the mode is driven by enter()/exit(), not by enable/disable.
    }

    @Override
    protected void onDisable() {
        // Nothing.
    }

    public boolean isCapturing() {
        return capturing;
    }

    @Override
    public boolean isPicking() {
        return capturing;
    }

    @Override
    public void onPick(Vec3i cell) {
        addSelectedBlock(cell);
    }

    @Override
    public void onCancel() {
        cancel();
    }

    /** Starts the capture mode : hides the editing UI and shows the action button + guidance HUD. */
    public void enter() {
        if (capturing) {
            return;
        }
        capturing = true;
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

    /** Cancels an in-progress capture without saving anything. */
    public void cancel() {
        if (capturing) {
            exit();
        }
    }

    /**
     * The block currently aimed at (or {@code null} when nothing is targeted), pushed each placeholder
     * tick by {@link org.delaunois.ialon.control.PlaceholderControl} while capturing. Drives the live
     * preview box.
     */
    public void onTarget(Vec3i cell) {
        if (!capturing) {
            return;
        }
        currentTarget = cell;
        refreshPreview();
    }

    /**
     * Records the block at {@code loc} as the next selection point. Once three points are gathered, the
     * creation is finalized and saved. Called from the player's action handler on the update thread.
     */
    public void addSelectedBlock(Vec3i loc) {
        if (!capturing || loc == null) {
            return;
        }
        points[step] = new Vec3i(loc);
        step++;
        if (step < 3) {
            updateHud();
            refreshPreview();
        } else {
            finalizeCreation();
        }
    }

    private void finalizeCreation() {
        // Final box : the first two points define the X/Z footprint and base level, the third the height.
        computeVolume(points[0], points[1], points[2], boundsMin, boundsMax);

        int sizeX = boundsMax.x - boundsMin.x + 1;
        int sizeY = boundsMax.y - boundsMin.y + 1;
        int sizeZ = boundsMax.z - boundsMin.z + 1;
        float scale = BlocksConfig.getInstance().getBlockScale();
        ChunkManager chunkManager = config.getChunkManager();

        String[] names = new String[sizeX * sizeY * sizeZ];
        int idx = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    scratch.set((boundsMin.x + x + 0.5f) * scale,
                            (boundsMin.y + y + 0.5f) * scale,
                            (boundsMin.z + z + 0.5f) * scale);
                    Block block = chunkManager.getBlock(scratch).orElse(null);
                    names[idx++] = block == null ? BlockIds.NONE : block.getName();
                }
            }
        }

        Creation creation = new Creation();
        String name = CreationRepository.nextCreationName(config.getSavePath());
        creation.setName(name);
        creation.setId(CreationRepository.generateUniqueId(config.getSavePath(), name));
        creation.setSizeX(sizeX);
        creation.setSizeY(sizeY);
        creation.setSizeZ(sizeZ);
        creation.setBlocks(names);
        CreationRepository.save(config.getSavePath(), creation);

        // Thumbnail : an isolated offscreen render of the creation's blocks (framed from above a corner).
        // For a creation too big to mesh in one chunk, fall back to a clean 3D screen grab (the preview box
        // lives in the 3D scene WorldPreview captures, so remove it first ; the HUD is in the GUI viewport,
        // which WorldPreview does not capture, so it needs no hiding).
        java.nio.file.Path preview = CreationRepository.previewPath(config.getSavePath(), creation.getId());
        Node mesh = CreationMesh.build(names, sizeX, sizeY, sizeZ);
        if (mesh != null) {
            CreationPreview.render(app, mesh,
                    new Vector3f(sizeX * scale, sizeY * scale, sizeZ * scale), preview);
        } else {
            previewNode.removeFromParent();
            WorldPreview.capture(app, preview, null);
        }

        log.info("Captured creation '{}' : {}x{}x{} ({} cells)", name, sizeX, sizeY, sizeZ, names.length);
        exit();

        // Briefly confirm on the HUD (exit() has just hidden the guidance label ; re-show it with the
        // confirmation, then update() removes it after a few seconds).
        hud.setText("Creation \"" + name + "\" saved");
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());
        showHud();
        confirmTimeLeft = CONFIRM_SECONDS;
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
        capturing = false;
        currentTarget = null;
        setUiEnabled(true);
        ButtonManagerState buttons = app.getStateManager().getState(ButtonManagerState.class);
        if (buttons != null) {
            buttons.setBuildMode(false);
        }
        hideHud();
        previewNode.removeFromParent();
    }

    /** Enables or disables the editing UI hidden during capture (block selection, minimap, time factor). */
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

    // --- Preview --------------------------------------------------------------------------------------

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
            computeVolume(points[0], currentTarget, null, boundsMin, boundsMax);
            shown = true;
        } else if (step == 2 && currentTarget != null) {
            // Volume : fixed X/Z footprint, height extended to the aim.
            computeVolume(points[0], points[1], currentTarget, boundsMin, boundsMax);
            shown = true;
        }

        if (!shown) {
            previewNode.removeFromParent();
            return;
        }

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
     * A unit-cube node (a translucent white fill + a white wireframe outline) scaled/positioned to the
     * preview box. Built once ; only its transform changes per tick, so the preview is allocation-free.
     */
    private Node createPreviewNode() {
        Node node = new Node("CreationPreview");

        Material fillMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        fillMat.setColor("Color", new ColorRGBA(1f, 1f, 1f, 0.25f));
        fillMat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        fillMat.getAdditionalRenderState().setDepthWrite(false);
        fillMat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
        Geometry fill = new Geometry("CreationPreviewFill", new Box(0.5f, 0.5f, 0.5f));
        fill.setMaterial(fillMat);
        fill.setQueueBucket(Bucket.Transparent);
        node.attachChild(fill);

        Material wireMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        wireMat.setColor("Color", ColorRGBA.White);
        Geometry outline = new Geometry("CreationPreviewOutline", new WireBox(0.5f, 0.5f, 0.5f));
        outline.setMaterial(wireMat);
        node.attachChild(outline);

        return node;
    }

    /**
     * Computes the inclusive block-coordinate bounds of a creation box. {@code a} and {@code b} are the
     * two base corners : they fix the X/Z footprint and the base Y range. {@code heightPoint} (nullable)
     * only extends the Y range, never the footprint — that is the "base rectangle + height" rule. Static
     * for unit testing.
     */
    static void computeVolume(Vec3i a, Vec3i b, Vec3i heightPoint, Vec3i outMin, Vec3i outMax) {
        int minX = Math.min(a.x, b.x);
        int maxX = Math.max(a.x, b.x);
        int minZ = Math.min(a.z, b.z);
        int maxZ = Math.max(a.z, b.z);
        int minY = Math.min(a.y, b.y);
        int maxY = Math.max(a.y, b.y);
        if (heightPoint != null) {
            minY = Math.min(minY, heightPoint.y);
            maxY = Math.max(maxY, heightPoint.y);
        }
        outMin.set(minX, minY, minZ);
        outMax.set(maxX, maxY, maxZ);
    }

    // --- HUD ------------------------------------------------------------------------------------------

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

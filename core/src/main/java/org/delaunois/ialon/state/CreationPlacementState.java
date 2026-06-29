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

import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_PLACE_ROTATE;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_PLACE_X_MINUS;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_PLACE_X_PLUS;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_PLACE_Y_MINUS;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_PLACE_Y_PLUS;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_PLACE_Z_MINUS;
import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_PLACE_Z_PLUS;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.material.RenderState.FaceCullMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.debug.WireBox;
import com.jme3.scene.shape.Box;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockRegistry;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.TypeIds;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.serialize.Creation;
import org.delaunois.ialon.ui.UiHelper;
import org.delaunois.ialon.ui.UiHelper.IconButton;

import java.util.LinkedHashSet;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * Drives the "build" (placement) mode : a saved {@link Creation} is shown as a live ghost that follows
 * the block the player aims at, and stamped into the world on confirm (left-click / action button). The
 * aimed block becomes the creation's lower corner (min X/Y/Z) ; the box then extends towards +X/+Y/+Z.
 * The whole box is written, air cells included (air clears the world block), so the creation appears
 * exactly as captured.
 *
 * <p>The ghost is the creation meshed with the real block renderer (so it looks like the actual blocks),
 * with a white wireframe box marking the extent. The pick reach is extended while placing (see
 * {@code PlaceholderControl}) so the player can stand back / fly up for an overview. Aim positions the
 * ghost ; the on-screen nudge buttons (or the I/J/K/L/U/O keys) then fine-tune it one block at a time
 * along the world axes — the first nudge freezes the ghost so it no longer follows the aim until the
 * placement is confirmed (Action) or cancelled (right-click / gear).</p>
 *
 * <p>Limitations : cells in not-yet-paged chunks are skipped. Rotation of the creation is not yet
 * supported.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class CreationPlacementState extends BaseAppState implements Resizable, BlockPickingMode, ActionListener {

    private static final String[] PLACE_ACTIONS = {
            ACTION_PLACE_X_MINUS, ACTION_PLACE_X_PLUS,
            ACTION_PLACE_Y_MINUS, ACTION_PLACE_Y_PLUS,
            ACTION_PLACE_Z_MINUS, ACTION_PLACE_Z_PLUS,
            ACTION_PLACE_ROTATE
    };

    private final IalonConfig config;
    private final Vector3f scratch = new Vector3f();
    // The placement anchor (creation lower corner) once the player starts adjusting it by hand.
    private final Vec3i manualAnchor = new Vec3i();

    private SimpleApplication app;
    private Label hud;
    private Node ghostNode;
    private Creation creation;
    private boolean placing;
    private Vec3i currentTarget;
    // Once the player nudges the ghost, it stops following the aim and is positioned by hand until confirm.
    private boolean manual;
    // Flat on-screen nudge buttons (guiNode) : [X-,X+,Y-,Y+,Z-,Z+,rotate]. Backgrounds are invisible
    // (kept only as the pickable tap target).
    private IconButton[] nudgeButtons;
    private int buttonSize;
    // The ±X/±Z cross + rotate live in this node, spun in the screen plane (around Z) to follow the camera
    // heading so each arrow points the way it moves. The ±Y buttons stay fixed on screen, outside it.
    private Node padNode;
    private final Quaternion padRot = new Quaternion();

    public CreationPlacementState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;
        hud = new Label("", IALON_STYLE);
        hud.setColor(ColorRGBA.White);
        hud.setTextHAlignment(HAlignment.Center);
        buttonSize = app.getCamera().getHeight() / 9;
        nudgeButtons = new IconButton[]{
                nudgeButton("arrowleft.png", ACTION_PLACE_X_MINUS),
                nudgeButton("arrowright.png", ACTION_PLACE_X_PLUS),
                nudgeButton("arrowjumpdown.png", ACTION_PLACE_Y_MINUS),
                nudgeButton("arrowjump.png", ACTION_PLACE_Y_PLUS),
                nudgeButton("arrowup.png", ACTION_PLACE_Z_MINUS),
                nudgeButton("arrowdown.png", ACTION_PLACE_Z_PLUS),
                nudgeButton("rotate.png", ACTION_PLACE_ROTATE)
        };

        // The cross (±X/±Z + rotate) lives in a node spun to follow the camera. Local +x = arrowright =
        // world +X (east), local +y = arrowup = world north ; spinning the node keeps every arrow aligned.
        padNode = new Node("CreationNudgePad");
        float step = buttonSize * 1.2f;
        placeInPad(nudgeButtons[6], 0, 0);        // rotate (centre)
        placeInPad(nudgeButtons[1], step, 0);     // X+ (east,  arrowright)
        placeInPad(nudgeButtons[0], -step, 0);    // X- (west,  arrowleft)
        placeInPad(nudgeButtons[4], 0, step);     // Z- (north, arrowup)
        placeInPad(nudgeButtons[5], 0, -step);    // Z+ (south, arrowdown)

        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).register(this);
        }
    }

    /** Places a cross button centred at {@code (cx,cy)} in the pad's (rotating) local plane. */
    private void placeInPad(IconButton button, float cx, float cy) {
        float s = buttonSize;
        button.background.setLocalTranslation(cx - s / 2f, cy + s / 2f, -1);
        button.icon.setLocalTranslation(cx - s / 2f, cy + s / 2f, 0);
        padNode.attachChild(button.background);
        padNode.attachChild(button.icon);
    }

    /** Creates a nudge button that fires {@code action} (handled by {@link #onAction}) when tapped. */
    private IconButton nudgeButton(String texture, String action) {
        IconButton button = UiHelper.createTextureButton(config, texture, buttonSize, 0, 0);
        // Keeps the default semi-transparent black background from createTextureButton.
        button.background.addMouseListener(new DefaultMouseListener() {
            @Override
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                config.getInputActionManager().triggerAction(action, event.isPressed());
            }
        });
        return button;
    }

    @Override
    protected void cleanup(Application application) {
        if (application.getStateManager().getState(ScreenState.class) != null) {
            application.getStateManager().getState(ScreenState.class).unregister(this);
        }
    }

    @Override
    protected void onEnable() {
        // Driven by enter()/exit().
    }

    @Override
    protected void onDisable() {
        // Nothing.
    }

    @Override
    public void update(float tpf) {
        if (placing) {
            // Spin the cross to follow the camera heading so the arrows keep matching the view.
            refreshPadRotation();
        }
    }

    @Override
    public boolean isPicking() {
        return placing;
    }

    /** Starts placing the given (fully loaded) creation. */
    public void enter(Creation creation) {
        if (placing || creation == null || creation.getBlocks() == null) {
            return;
        }
        this.creation = creation;
        placing = true;
        manual = false;
        currentTarget = null;

        setUiEnabled(false);
        ButtonManagerState buttons = app.getStateManager().getState(ButtonManagerState.class);
        if (buttons != null) {
            buttons.setBuildMode(true);
        }
        ghostNode = buildGhost(creation);
        config.getInputActionManager().addListener(this, PLACE_ACTIONS);
        showNudgeButtons();

        hud.setText("Aim a block; arrows fine-tune; Action builds");
        layout(app.getCamera().getWidth(), app.getCamera().getHeight());
        if (hud.getParent() == null) {
            app.getGuiNode().attachChild(hud);
        }
    }

    /** Cancels placement without modifying the world. */
    public void cancel() {
        if (placing) {
            exit();
        }
    }

    @Override
    public void onTarget(Vec3i cell) {
        if (!placing) {
            return;
        }
        currentTarget = cell;
        if (manual) {
            return; // frozen : the player is adjusting by hand, ignore the aim
        }
        if (cell == null) {
            ghostNode.removeFromParent();
            return;
        }
        // The creation sits ON the aimed block : its lower corner is one block ABOVE the target.
        positionGhost(anchorOf(cell));
    }

    @Override
    public void onPick(Vec3i cell) {
        // Left-click / action button stamps the creation : at the hand-adjusted anchor if the player has
        // nudged it, otherwise at the aimed block (only when actually aiming at one).
        if (!placing) {
            return;
        }
        Vec3i anchor = manual ? manualAnchor : (cell != null ? anchorOf(cell) : null);
        if (anchor == null) {
            return;
        }
        apply(anchor);
        exit();
    }

    @Override
    public void onCancel() {
        cancel();
    }

    /**
     * Fine-adjust input (on-screen nudge buttons or desktop keys). The first nudge freezes the ghost at
     * its current anchor ; further nudges move it one block per press along the world axes.
     */
    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (!placing || !isPressed) {
            return;
        }
        switch (name) {
            case ACTION_PLACE_X_MINUS: nudge(-1, 0, 0); break;
            case ACTION_PLACE_X_PLUS:  nudge(1, 0, 0); break;
            case ACTION_PLACE_Y_MINUS: nudge(0, -1, 0); break;
            case ACTION_PLACE_Y_PLUS:  nudge(0, 1, 0); break;
            case ACTION_PLACE_Z_MINUS: nudge(0, 0, -1); break;
            case ACTION_PLACE_Z_PLUS:  nudge(0, 0, 1); break;
            case ACTION_PLACE_ROTATE:  rotate(); break;
            default:
        }
    }

    /**
     * Rotates the creation 90° around the vertical axis : re-indexes the block grid (X/Z swap) and
     * re-orients every directional block, then rebuilds the ghost at the current anchor.
     */
    private void rotate() {
        rotate90(creation, BlocksConfig.getInstance().getBlockRegistry());
        ghostNode.removeFromParent();
        ghostNode = buildGhost(creation);
        Vec3i anchor = manual ? manualAnchor : (currentTarget != null ? anchorOf(currentTarget) : null);
        if (anchor != null) {
            positionGhost(anchor);
        }
    }

    private void nudge(int dx, int dy, int dz) {
        if (!manual) {
            if (currentTarget == null) {
                return; // nothing aimed yet : no anchor to start from
            }
            manual = true;
            manualAnchor.set(anchorOf(currentTarget));
        }
        manualAnchor.set(manualAnchor.x + dx, manualAnchor.y + dy, manualAnchor.z + dz);
        positionGhost(manualAnchor);
    }

    private void positionGhost(Vec3i anchor) {
        float scale = BlocksConfig.getInstance().getBlockScale();
        ghostNode.setLocalTranslation(anchor.x * scale, anchor.y * scale, anchor.z * scale);
        if (ghostNode.getParent() == null) {
            app.getRootNode().attachChild(ghostNode);
        }
    }

    /**
     * The creation's lower (min) corner for a given aimed block : the aimed block is the CENTRE of the
     * base rectangle (footprint centred on it) and the creation sits one block ABOVE it.
     */
    private Vec3i anchorOf(Vec3i cell) {
        return new Vec3i(
                cell.x - (creation.getSizeX() - 1) / 2,
                cell.y + 1,
                cell.z - (creation.getSizeZ() - 1) / 2);
    }

    // --- Rotation -------------------------------------------------------------------------------------

    /**
     * Rotates the creation 90° around Y, in place. The footprint is re-indexed ({@code (x,z) -> (sizeZ-1-z,
     * x)}, swapping the X/Z sizes) and each block's directional shape suffix is cycled the same way
     * ({@code north->east->south->west}), so directional blocks (stairs, wedges, doors, plates, poles,
     * slabs…) keep pointing the right way relative to the rotated structure.
     */
    private static void rotate90(Creation c, BlockRegistry registry) {
        int sx = c.getSizeX();
        int sy = c.getSizeY();
        int sz = c.getSizeZ();
        String[] src = c.getBlocks();
        int nsx = sz;
        int nsz = sx;
        String[] dst = new String[nsx * sy * nsz];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    String name = src[((y * sz) + z) * sx + x];
                    int nx = sz - 1 - z;
                    int nz = x;
                    dst[((y * nsz) + nz) * nsx + nx] = rotateName(name, registry);
                }
            }
        }
        c.setSizeX(nsx);
        c.setSizeZ(nsz);
        c.setBlocks(dst);
    }

    /** Maps a block name to the name of the same block rotated 90° around Y (unchanged if not directional). */
    private static String rotateName(String name, BlockRegistry registry) {
        if (CreationMesh.isAir(name)) {
            return name;
        }
        Block block = registry.get(name);
        if (block == null) {
            return name;
        }
        String shape = block.getShape();
        String rotated = rotateShapeY(shape);
        if (rotated.equals(shape)) {
            return name;
        }
        // name = type[-shape[-waterLevel]] : swap the shape segment, keep the rest (e.g. a water level).
        String rotatedName = name.replace(shape, rotated);
        // Doors encode the hinge side in the TYPE (door_left/door_right), and their open swing is
        // asymmetric (toggledDoorShape). A 90° rotation must therefore FLIP the hinge so the door's closed
        // AND open positions both rotate consistently — otherwise the hinge lands on the wrong jamb and the
        // door opens the wrong way.
        if (WorldManager.isDoor(block.getType())) {
            rotatedName = flipDoorHinge(rotatedName);
        }
        return registry.get(rotatedName) != null ? rotatedName : name;
    }

    /** Swaps a door block name's hinge (door_left &lt;-&gt; door_right), preserving its look and shape. */
    static String flipDoorHinge(String name) {
        if (name.startsWith(TypeIds.DOOR_RIGHT)) {
            return TypeIds.DOOR_LEFT + name.substring(TypeIds.DOOR_RIGHT.length());
        }
        if (name.startsWith(TypeIds.DOOR_LEFT)) {
            return TypeIds.DOOR_RIGHT + name.substring(TypeIds.DOOR_LEFT.length());
        }
        return name;
    }

    /** Cycles a shape's horizontal direction suffix by 90° ; non-directional shapes are unchanged. */
    static String rotateShapeY(String shape) {
        if (shape.endsWith("_north")) {
            return shape.substring(0, shape.length() - "_north".length()) + "_east";
        }
        if (shape.endsWith("_east")) {
            return shape.substring(0, shape.length() - "_east".length()) + "_south";
        }
        if (shape.endsWith("_south")) {
            return shape.substring(0, shape.length() - "_south".length()) + "_west";
        }
        if (shape.endsWith("_west")) {
            return shape.substring(0, shape.length() - "_west".length()) + "_north";
        }
        return shape;
    }

    private void exit() {
        placing = false;
        manual = false;
        currentTarget = null;
        creation = null;
        config.getInputActionManager().removeListener(this);
        hideNudgeButtons();
        setUiEnabled(true);
        ButtonManagerState buttons = app.getStateManager().getState(ButtonManagerState.class);
        if (buttons != null) {
            buttons.setBuildMode(false);
        }
        hud.removeFromParent();
        if (ghostNode != null) {
            ghostNode.removeFromParent();
            ghostNode = null;
        }
    }

    // --- Nudge buttons --------------------------------------------------------------------------------

    private void showNudgeButtons() {
        layoutNudgeButtons(app.getCamera().getWidth(), app.getCamera().getHeight());
        if (padNode.getParent() == null) {
            app.getGuiNode().attachChild(padNode);
        }
        attachToGui(nudgeButtons[2]); // Y-
        attachToGui(nudgeButtons[3]); // Y+
        refreshPadRotation();
    }

    private void hideNudgeButtons() {
        padNode.removeFromParent();
        detachFromGui(nudgeButtons[2]);
        detachFromGui(nudgeButtons[3]);
    }

    private void attachToGui(IconButton b) {
        if (b.background.getParent() == null) {
            app.getGuiNode().attachChild(b.background);
            app.getGuiNode().attachChild(b.icon);
        }
    }

    private void detachFromGui(IconButton b) {
        b.background.removeFromParent();
        b.icon.removeFromParent();
    }

    /** Positions the (rotating) cross pad and the fixed ±Y pair to its right. */
    private void layoutNudgeButtons(int width, int height) {
        float margin = UiHelper.screenMargin(height);
        float step = buttonSize * 1.2f;
        float padX = width - margin - 3f * buttonSize;
        float padY = height * 0.5f;
        padNode.setLocalTranslation(padX, padY, 0);
        float yx = padX + 2.2f * step;
        placeCentered(nudgeButtons[3], yx, padY + step * 0.6f);   // Y+
        placeCentered(nudgeButtons[2], yx, padY - step * 0.6f);   // Y-
    }

    /** Spins the cross (in the screen plane) to the camera heading, so each arrow points the way it moves. */
    private void refreshPadRotation() {
        app.getCamera().getDirection(scratch);
        float yaw = FastMath.atan2(scratch.x, -scratch.z);
        padRot.fromAngleAxis(yaw, Vector3f.UNIT_Z);
        padNode.setLocalRotation(padRot);
    }

    /** Positions a button centred at the given screen point (panels are placed by their top-left corner). */
    private void placeCentered(IconButton button, float px, float py) {
        float s = buttonSize;
        button.background.setLocalTranslation(px - s / 2f, py + s / 2f, -1);
        button.icon.setLocalTranslation(px - s / 2f, py + s / 2f, 0);
    }

    // --- Apply ----------------------------------------------------------------------------------------

    /**
     * Stamps the creation into the world with its lower corner at {@code anchor}. Writes directly to the
     * paged chunks (the whole box, air included) and re-meshes the touched chunks plus their neighbours.
     */
    private void apply(Vec3i anchor) {
        ChunkManager chunkManager = config.getChunkManager();
        BlockRegistry registry = BlocksConfig.getInstance().getBlockRegistry();
        float scale = BlocksConfig.getInstance().getBlockScale();
        String[] blocks = creation.getBlocks();
        int sizeX = creation.getSizeX();
        int sizeY = creation.getSizeY();
        int sizeZ = creation.getSizeZ();

        Set<Vec3i> touched = new LinkedHashSet<>();
        int skipped = 0;
        int idx = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    String name = blocks[idx++];
                    scratch.set((anchor.x + x + 0.5f) * scale,
                            (anchor.y + y + 0.5f) * scale,
                            (anchor.z + z + 0.5f) * scale);
                    Vec3i chunkLoc = ChunkManager.getChunkLocation(scratch);
                    Chunk chunk = chunkManager.getChunk(chunkLoc).orElse(null);
                    if (chunk == null) {
                        skipped++;
                        continue;
                    }
                    Vec3i local = chunk.toLocalLocation(ChunkManager.getBlockLocation(scratch));
                    Block block = CreationMesh.isAir(name) ? null : registry.get(name);
                    if (block == null) {
                        chunk.removeBlock(local.x, local.y, local.z);
                    } else {
                        chunk.addBlock(local.x, local.y, local.z, block);
                    }
                    touched.add(chunkLoc);
                }
            }
        }

        // Light : the bulk write does not propagate sunlight, so the stamped blocks would sample stale
        // (often dark) lightmap values and render black. Recomputing a full flood per cell would be far
        // too slow here, so instead force full sunlight on the AIR cells of the box plus a one-cell margin.
        // A block face always samples the (air) cell it faces, so every exposed face of the creation ends
        // up lit ; solid cells are left untouched (no halo on the surrounding terrain). The creation is
        // therefore uniformly lit (no self-shadowing), matching the placement ghost.
        relight(chunkManager, anchor, sizeX, sizeY, sizeZ, scale, touched);

        // Re-mesh the touched chunks and their axis neighbours (faces at chunk borders change too).
        Set<Vec3i> toMesh = new LinkedHashSet<>(touched);
        for (Vec3i c : touched) {
            toMesh.add(new Vec3i(c.x + 1, c.y, c.z));
            toMesh.add(new Vec3i(c.x - 1, c.y, c.z));
            toMesh.add(new Vec3i(c.x, c.y + 1, c.z));
            toMesh.add(new Vec3i(c.x, c.y - 1, c.z));
            toMesh.add(new Vec3i(c.x, c.y, c.z + 1));
            toMesh.add(new Vec3i(c.x, c.y, c.z - 1));
        }
        chunkManager.requestOrderedMeshChunks(toMesh);

        log.info("Placed creation '{}' at {} : {} chunks touched, {} cells skipped (unpaged)",
                creation.getName(), anchor, touched.size(), skipped);
    }

    /** Sets full sunlight on the air cells of the box + a one-cell margin (see {@link #apply}). */
    private void relight(ChunkManager chunkManager, Vec3i anchor, int sizeX, int sizeY, int sizeZ,
                         float scale, Set<Vec3i> touched) {
        for (int y = -1; y <= sizeY; y++) {
            for (int z = -1; z <= sizeZ; z++) {
                for (int x = -1; x <= sizeX; x++) {
                    scratch.set((anchor.x + x + 0.5f) * scale,
                            (anchor.y + y + 0.5f) * scale,
                            (anchor.z + z + 0.5f) * scale);
                    Vec3i chunkLoc = ChunkManager.getChunkLocation(scratch);
                    Chunk chunk = chunkManager.getChunk(chunkLoc).orElse(null);
                    if (chunk == null) {
                        continue;
                    }
                    Vec3i local = chunk.toLocalLocation(ChunkManager.getBlockLocation(scratch));
                    if (chunk.getBlock(local.x, local.y, local.z) == null) {
                        chunk.setSunlight(local.x, local.y, local.z, 15);
                        touched.add(chunkLoc);
                    }
                }
            }
        }
    }

    // --- Ghost ----------------------------------------------------------------------------------------

    /**
     * Builds the placement ghost : the creation meshed with the real block renderer when it fits in one
     * chunk, otherwise just a wireframe box. A white wireframe outline always marks the extent. The node
     * origin is the creation's lower corner.
     */
    private Node buildGhost(Creation creation) {
        Node node = new Node("CreationGhost");
        float scale = BlocksConfig.getInstance().getBlockScale();
        int sizeX = creation.getSizeX();
        int sizeY = creation.getSizeY();
        int sizeZ = creation.getSizeZ();

        Node mesh = CreationMesh.build(creation.getBlocks(), sizeX, sizeY, sizeZ);
        boolean meshed = mesh != null;
        if (meshed) {
            node.attachChild(mesh);
        }

        // Inflate the box by a small margin so its faces / edges never coincide with the meshed block
        // faces, which would z-fight ; the centre stays on the true box centre.
        float eps = 0.04f * scale;
        float boxX = sizeX * scale + eps;
        float boxY = sizeY * scale + eps;
        float boxZ = sizeZ * scale + eps;
        Vector3f center = new Vector3f(sizeX * scale / 2f, sizeY * scale / 2f, sizeZ * scale / 2f);

        // When the creation is too big to mesh, show the same semi-transparent white box as the capture
        // selection preview so the placement volume is still visible (a wireframe alone reads poorly on a
        // large box). When meshed, the structure itself is shown, so only the wireframe outline is added.
        if (!meshed) {
            Material fillMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
            fillMat.setColor("Color", new ColorRGBA(1f, 1f, 1f, 0.25f));
            fillMat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
            fillMat.getAdditionalRenderState().setDepthWrite(false);
            fillMat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
            Geometry fill = new Geometry("CreationGhostFill", new Box(0.5f, 0.5f, 0.5f));
            fill.setMaterial(fillMat);
            fill.setQueueBucket(Bucket.Transparent);
            fill.setLocalScale(boxX, boxY, boxZ);
            fill.setLocalTranslation(center);
            node.attachChild(fill);
        }

        // White wireframe outline of the whole box (always shown, marks the extent).
        Material wireMat = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        wireMat.setColor("Color", ColorRGBA.White);
        Geometry outline = new Geometry("CreationGhostOutline", new WireBox(0.5f, 0.5f, 0.5f));
        outline.setMaterial(wireMat);
        outline.setLocalScale(boxX, boxY, boxZ);
        outline.setLocalTranslation(center);
        node.attachChild(outline);

        return node;
    }

    // --- UI -------------------------------------------------------------------------------------------

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

    @Override
    public void onResize(int width, int height) {
        layout(width, height);
    }

    /** Centers the HUD label horizontally, just below the top row of buttons, and re-lays the nudge pad. */
    private void layout(int width, int height) {
        float vh = height / 100f;
        float topButtonsBottom = height - UiHelper.screenMargin(height) - height / 12f;
        hud.setFontSize(4 * vh);
        Vector3f size = hud.getPreferredSize();
        hud.setLocalTranslation((width - size.x) / 2f, topButtonsBottom - 2 * vh, 10);
        // The ±Y screen buttons keep their creation-time size ; only their positions are recomputed here.
        if (nudgeButtons != null) {
            layoutNudgeButtons(width, height);
        }
    }
}

package org.delaunois.ialon.control;

import com.jme3.app.SimpleApplication;
import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;
import com.jme3.scene.debug.WireBox;
import com.jme3.scene.shape.Box;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Shape;
import org.delaunois.ialon.blocks.TypeIds;
import org.delaunois.ialon.state.BlockPickingMode;
import org.delaunois.ialon.state.ButtonManagerState;
import org.delaunois.ialon.blocks.shapes.CrossPlane;
import org.delaunois.ialon.blocks.shapes.Pyramid;
import org.delaunois.ialon.blocks.shapes.Stairs;
import org.delaunois.ialon.blocks.shapes.Wedge;
import com.simsilica.mathd.Vec3i;


import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.WorldManager;

@Slf4j
public class PlaceholderControl extends AbstractControl {

    private static final Vector3f OFFSET = new Vector3f(0.5f, 0.5f, 0.5f);
    private static final float UPDATE_TIME = 0.1f;
    private static final float MAX_REACH = 20f;
    // Reach while a block-picking mode (creation capture/placement) is active : far longer than the normal
    // editing reach, so the player can stand back / fly up for an overview and still aim at a distant
    // ground block to capture or place a (possibly large) creation.
    private static final float PICK_REACH = 256f;

    private final CollisionResults collisionResults = new CollisionResults();
    private final Ray ray = new Ray();
    // Scratch reused by the voxel grid-march (runs every UPDATE_TIME, not in the render loop).
    private final Vector3f marchPoint = new Vector3f();
    private final Vector3f marchNormal = new Vector3f();
    private final CollisionResult gridCollision = new CollisionResult();
    private final SimpleApplication app;

    @Setter
    private Node chunkNode;

    private float curTime = 1;
    private final Geometry addPlaceholder;
    private final Geometry removePlaceholder;
    private final Vector3f addPlaceholderLocation = new Vector3f();
    private final Vector3f removePlaceholderLocation = new Vector3f();
    private final WorldManager worldManager;

    private PlayerHeadDirectionControl playerHeadDirectionControl = null;

    public PlaceholderControl(Node chunkNode, WorldManager worldManager, SimpleApplication app) {
        this.app = app;
        this.chunkNode = chunkNode;
        this.addPlaceholder = createAddPlaceholder();
        this.removePlaceholder = createRemovePlaceholder();
        this.worldManager = worldManager;
    }

    @Override
    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        this.playerHeadDirectionControl = spatial.getControl(PlayerHeadDirectionControl.class);
        assert playerHeadDirectionControl != null;
    }

    @Override
    protected void controlUpdate(float tpf) {
        curTime += tpf;

        if (chunkNode != null
                && worldManager != null
                && playerHeadDirectionControl != null
                && curTime > UPDATE_TIME) {
            curTime = 0;
            updatePlaceholders(getCollisionResult());
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }

    public Geometry getRemovePlaceholder() {
        return removePlaceholder;
    }

    public Geometry getAddPlaceholder() {
        return addPlaceholder;
    }

    public Vector3f getRemovePlaceholderPosition() {
        return removePlaceholderLocation;
    }

    public Vector3f getAddPlaceholderPosition() {
        return addPlaceholderLocation;
    }

    private Geometry createAddPlaceholder() {
        Geometry geometry = new Geometry("add-placeholder", new Box(0.5f, 0.5f, 0.5f));
        geometry.setLocalScale(BlocksConfig.getInstance().getBlockScale());
        return geometry;
    }

    private Geometry createRemovePlaceholder() {
        Material removePlaceholderMaterial = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        removePlaceholderMaterial.setColor("Color", new ColorRGBA(0.4549f, 0.851f, 1, 1));

        Geometry geometry = new Geometry("remove-placeholder", new WireBox(0.505f, 0.505f, 0.505f));
        geometry.setMaterial(removePlaceholderMaterial);
        geometry.setLocalScale(BlocksConfig.getInstance().getBlockScale());

        return geometry;
    }

    private void updatePlaceholders(CollisionResult result) {
        if (result == null || result.getDistance() >= currentReach()) {
            removePlaceholder.removeFromParent();
            updateActionButton(null);
            updateCapturePreview(null);
            return;
        }

        Vec3i pointingLocation = ChunkManager.getBlockLocation(result);
        updateCapturePreview(pointingLocation);
        Vector3f localTranslation = pointingLocation.toVector3f().addLocal(OFFSET).multLocal(BlocksConfig.getInstance().getBlockScale());
        removePlaceholder.setLocalTranslation(localTranslation);
        removePlaceholderLocation.set(removePlaceholder.getLocalTranslation());
        if (removePlaceholder.getParent() == null) {
            this.app.getRootNode().attachChild(removePlaceholder);
        }

        Block b = worldManager.getBlock(localTranslation);
        updateActionButton(b);
        Shape shape = b == null ? null : BlocksConfig.getInstance().getShapeRegistry().get(b.getShape());

        if ((shape instanceof Wedge
                || shape instanceof CrossPlane
                || shape instanceof Stairs
                || shape instanceof Pyramid)) {
            // These shapes have slew faces, it is better to define the "add location"
            // based on the face of the enclosing cube where the user points to.
            addPlaceholder.setLocalTranslation(localTranslation);
            ray.setOrigin(playerHeadDirectionControl.getCamera().getLocation());
            ray.setDirection(playerHeadDirectionControl.getCamDir());
            collisionResults.clear();
            addPlaceholder.collideWith(ray, collisionResults);
            result = collisionResults.getClosestCollision();
            if (result == null || result.getDistance() >= currentReach()) {
                addPlaceholder.removeFromParent();
                return;
            }
        }

        Vec3i placingLocation = ChunkManager.getNeighbourBlockLocation(result);
        addPlaceholder.setLocalTranslation(placingLocation.toVector3f().addLocal(OFFSET).multLocal(BlocksConfig.getInstance().getBlockScale()));
        addPlaceholderLocation.set(addPlaceholder.getLocalTranslation());
    }

    /**
     * Shows the on-screen "Action" button only while the targeted block is a door. Called each
     * placeholder tick (0.1 s), so it stays cheap : a type compare plus a state lookup.
     */
    private void updateActionButton(Block b) {
        ButtonManagerState buttonManager = app.getStateManager().getState(ButtonManagerState.class);
        if (buttonManager != null) {
            // During a block-picking mode (creation capture/placement) the action button replaces the
            // "+" button and stays visible throughout (it is how the player picks), regardless of target.
            boolean picking = BlockPickingMode.active(app.getStateManager()) != null;
            buttonManager.setActionButtonVisible(picking || (b != null && WorldManager.isDoor(b.getType())));
        }
    }

    /** Feeds the block currently aimed at (or {@code null}) to the active picking mode, for its live preview. */
    private void updateCapturePreview(Vec3i cell) {
        BlockPickingMode mode = BlockPickingMode.active(app.getStateManager());
        if (mode != null) {
            mode.onTarget(cell);
        }
    }

    private CollisionResult getCollisionResult() {
        // The camera direction is computed by PlayerHeadDirectionControl, which is disabled while the
        // world menu is open. Right after a world switch it may not have run yet, leaving a zero
        // (non-unit) direction : skip until it is valid, otherwise the grid-march normalises garbage.
        Vector3f camDir = playerHeadDirectionControl.getCamDir();
        if (!camDir.isUnitVector()) {
            return null;
        }
        // Block picking by a voxel grid-march (DDA), NOT chunkNode.collideWith. A per-triangle BIH raycast
        // against the chunk meshes had to BUILD each crossed chunk's BIHTree lazily on the main thread,
        // costing intermittent 20-60ms updateLogicalState hitches while flying (new chunks keep entering
        // the ray, never cached). Marching the voxel grid and querying worldManager.getBlock is O(reach),
        // needs no BIH/triangles and is insensitive to paging. Picking is therefore cell-granular (like
        // Minecraft's per-block picking) : pointing at a partial shape's cell targets that block.
        return getGridHit(playerHeadDirectionControl.getCamera().getLocation(), camDir, currentReach());
    }

    /** Normal editing reach, extended while a block-picking mode is active (see {@link #PICK_REACH}). */
    private float currentReach() {
        return BlockPickingMode.active(app.getStateManager()) != null ? PICK_REACH : MAX_REACH;
    }

    /**
     * Marches the world voxel grid along the ray and returns a synthetic collision on the first
     * non-liquid block found within {@code maxDist}, or {@code null}. Liquid cells (water/lava) are
     * passed through so the ray stops on the first solid block, exactly as the former mesh raycast did
     * (which skipped liquid surfaces). The contact point is placed at the cell centre (so the same
     * floor-based block lookup the rest of the code uses resolves it), and the contact normal is the
     * face the ray entered through (for adjacent placement). Billboard blocks (e.g. fire) collapse all
     * their vertices to the block centre and have no CPU triangles, so a mesh raycast would miss them —
     * the grid-march catches them like any other block.
     */
    private CollisionResult getGridHit(Vector3f origin, Vector3f direction, float maxDist) {
        return marchGrid(worldManager::getBlock, origin, direction, maxDist,
                BlocksConfig.getInstance().getBlockScale(), marchPoint, marchNormal, gridCollision);
    }

    /**
     * The voxel grid-march itself, extracted as a package-private static method so it can be unit-tested
     * with a plain {@code Vector3f -> Block} lookup, without standing up a world or an app. The
     * {@code scratch*} arguments are reused across the (throttled, ~10 Hz) call so production stays
     * allocation-free in the per-frame path; tests pass fresh instances. Returns {@code scratchResult}
     * pointing at the first non-liquid cell, or {@code null} if none within {@code maxDist}.
     */
    static CollisionResult marchGrid(java.util.function.Function<Vector3f, Block> blockAt,
                                     Vector3f origin, Vector3f direction, float maxDist, float scale,
                                     Vector3f scratchPoint, Vector3f scratchNormal, CollisionResult scratchResult) {
        float step = 0.1f * scale;
        Vec3i previousCell = null;
        for (float d = 0; d <= maxDist; d += step) {
            scratchPoint.set(direction).multLocal(d).addLocal(origin);
            Vec3i cell = ChunkManager.getBlockLocation(scratchPoint);
            if (cell.equals(previousCell)) {
                continue;
            }
            Block block = blockAt.apply(scratchPoint);
            if (block != null && !isLiquid(block)) {
                scratchNormal.set(0, 1, 0);
                if (previousCell != null) {
                    scratchNormal.set(previousCell.x - cell.x, previousCell.y - cell.y, previousCell.z - cell.z);
                    scratchNormal.normalizeLocal();
                }
                scratchResult.setContactPoint(new Vector3f(
                        (cell.x + 0.5f) * scale, (cell.y + 0.5f) * scale, (cell.z + 0.5f) * scale));
                scratchResult.setContactNormal(scratchNormal.clone());
                scratchResult.setDistance(d);
                return scratchResult;
            }
            previousCell = cell;
        }
        return null;
    }

    /** A pure-liquid cell (water or lava) : the ray passes through it, as the former raycast skipped
     * liquid surfaces. A solid block submerged in liquid keeps its own type, so it is NOT skipped. */
    private static boolean isLiquid(Block block) {
        String type = block.getType();
        return TypeIds.WATER.equals(type) || TypeIds.LAVA.equals(type);
    }

}

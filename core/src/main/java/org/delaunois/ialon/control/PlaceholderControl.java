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
import org.delaunois.ialon.blocks.ShapeIds;
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

    private final CollisionResults collisionResults = new CollisionResults();
    private final Ray ray = new Ray();
    // Scratch reused by the billboard grid-march (runs every UPDATE_TIME, not in the render loop).
    private final Vector3f marchPoint = new Vector3f();
    private final Vector3f marchNormal = new Vector3f();
    private final CollisionResult billboardCollision = new CollisionResult();
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
            CollisionResult result = getCollisionResult();
            updatePlaceholders(result);
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
        if (result == null || result.getDistance() >= 20) {
            removePlaceholder.removeFromParent();
            updateActionButton(null);
            return;
        }

        Vec3i pointingLocation = ChunkManager.getBlockLocation(result);
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
            if (result == null || result.getDistance() >= 20) {
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
            buttonManager.setActionButtonVisible(b != null && WorldManager.isDoor(b.getType()));
        }
    }

    private CollisionResult getCollisionResult() {
        // The camera direction is computed by PlayerHeadDirectionControl, which is disabled while the
        // world menu is open. Right after a world switch it may not have run yet, leaving a zero
        // (non-unit) direction : skip until it is valid, otherwise Ray.setDirection asserts.
        Vector3f camDir = playerHeadDirectionControl.getCamDir();
        if (!camDir.isUnitVector()) {
            return null;
        }
        ray.setOrigin(playerHeadDirectionControl.getCamera().getLocation());
        ray.setDirection(camDir);
        // Bound the pick ray to the interaction reach. Without a limit the ray is infinite, so collideWith
        // runs the (expensive) per-triangle BIH traversal on EVERY chunk the ray crosses across the whole
        // world -- the cause of intermittent ~25ms updateLogicalState hitches while moving/looking around.
        // jME's BIHTree honours Ray.limit (it clamps the traversal to [tMin, min(tMax, limit)]), so chunks
        // beyond MAX_REACH cost only their O(1) bounding-box test. A block can't be targeted past MAX_REACH
        // anyway (maxDist is clamped to it below), so the picking result is unchanged.
        ray.setLimit(MAX_REACH);
        collisionResults.clear();
        chunkNode.collideWith(ray, collisionResults);

        CollisionResult geomResult = null;
        for (CollisionResult collisionResult : collisionResults) {
            String materialName = collisionResult.getGeometry().getMaterial().getName();
            // Skip liquid surfaces (water and lava) so the ray stops on the first SOLID block : a liquid
            // block is placed/removed against that solid hit, exactly like water.
            if (materialName != null && !materialName.contains("water") && !materialName.contains("lava")) {
                geomResult = collisionResult;
                break;
            }
        }

        // Billboard blocks (e.g. fire) collapse all their vertices to the block centre in the render
        // mesh — they have no CPU-side triangles, so the ray above passes straight through them. Walk
        // the world grid along the ray (up to the first solid hit, or the max reach) to catch them,
        // so they can be selected/removed just like a regular cross-plane block.
        float maxDist = geomResult != null ? geomResult.getDistance() : MAX_REACH;
        CollisionResult billboardResult = getBillboardHit(ray.getOrigin(), ray.getDirection(), maxDist);
        return billboardResult != null ? billboardResult : geomResult;
    }

    /**
     * Marches the world grid along the ray and returns a synthetic collision on the first billboard
     * block (e.g. fire) found within {@code maxDist}, or {@code null}. The contact point is placed at
     * the cell centre (so the same floor-based block lookup the rest of the code uses resolves it),
     * and the contact normal is the face the ray entered through (for adjacent placement).
     */
    private CollisionResult getBillboardHit(Vector3f origin, Vector3f direction, float maxDist) {
        float scale = BlocksConfig.getInstance().getBlockScale();
        float step = 0.1f * scale;
        Vec3i previousCell = null;
        for (float d = 0; d <= maxDist; d += step) {
            marchPoint.set(direction).multLocal(d).addLocal(origin);
            Vec3i cell = ChunkManager.getBlockLocation(marchPoint);
            if (cell.equals(previousCell)) {
                continue;
            }
            Block block = worldManager.getBlock(marchPoint);
            if (block != null && ShapeIds.BILLBOARD.equals(block.getShape())) {
                marchNormal.set(0, 1, 0);
                if (previousCell != null) {
                    marchNormal.set(previousCell.x - cell.x, previousCell.y - cell.y, previousCell.z - cell.z);
                    marchNormal.normalizeLocal();
                }
                billboardCollision.setContactPoint(new Vector3f(
                        (cell.x + 0.5f) * scale, (cell.y + 0.5f) * scale, (cell.z + 0.5f) * scale));
                billboardCollision.setContactNormal(marchNormal.clone());
                billboardCollision.setDistance(d);
                return billboardCollision;
            }
            previousCell = cell;
        }
        return null;
    }

}

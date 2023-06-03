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
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Shape;
import com.rvandoosselaer.blocks.shapes.CrossPlane;
import com.rvandoosselaer.blocks.shapes.Pyramid;
import com.rvandoosselaer.blocks.shapes.Stairs;
import com.rvandoosselaer.blocks.shapes.Wedge;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.ChunkManager;
import org.delaunois.ialon.WorldManager;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PlaceholderControl extends AbstractControl {

    private static final Vector3f OFFSET = new Vector3f(0.5f, 0.5f, 0.5f);

    private final CollisionResults collisionResults = new CollisionResults();
    private final Ray ray = new Ray();
    private final SimpleApplication app;

    @Setter
    private Node chunkNode;

    private WorldManager worldManager;
    private PlayerCamDirectionControl playerCamDirectionControl = null;
    private long lastCollisionTest = System.currentTimeMillis();
    private final Geometry addPlaceholder;
    private final Geometry removePlaceholder;
    private final Vector3f addPlaceholderLocation = new Vector3f();
    private final Vector3f removePlaceholderLocation = new Vector3f();

    public PlaceholderControl(Node chunkNode, SimpleApplication app) {
        this.app = app;
        this.chunkNode = chunkNode;
        this.addPlaceholder = createAddPlaceholder();
        this.removePlaceholder = createRemovePlaceholder();
    }

    @Override
    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        this.worldManager = spatial.getControl(PlayerCharacterControl.class).getWorldManager();
        this.playerCamDirectionControl = spatial.getControl(PlayerCamDirectionControl.class);
        assert(playerCamDirectionControl != null);
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (chunkNode != null
                && worldManager != null
                && playerCamDirectionControl != null
                && System.currentTimeMillis() - lastCollisionTest > 100) {
            lastCollisionTest = System.currentTimeMillis();
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
        Shape shape = b == null ? null : BlocksConfig.getInstance().getShapeRegistry().get(b.getShape());

        if ((shape instanceof Wedge
                || shape instanceof CrossPlane
                || shape instanceof Stairs
                || shape instanceof Pyramid)) {
            // These shapes have slew faces, it is better to define the "add location"
            // based on the face of the enclosing cube where the user points to.
            addPlaceholder.setLocalTranslation(localTranslation);
            ray.setOrigin(playerCamDirectionControl.getCamera().getLocation());
            ray.setDirection(playerCamDirectionControl.getCamDir());
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

    private CollisionResult getCollisionResult() {
        ray.setOrigin(playerCamDirectionControl.getCamera().getLocation());
        ray.setDirection(playerCamDirectionControl.getCamDir());
        collisionResults.clear();
        chunkNode.collideWith(ray, collisionResults);

        for (CollisionResult collisionResult : collisionResults) {
            if (collisionResult.getGeometry().getMaterial().getName() != null &&
                    !collisionResult.getGeometry().getMaterial().getName().contains("water")) {
                return collisionResult;
            }
        }

        return null;
    }

}

package org.delaunois.ialon.state;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.bounding.BoundingVolume;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.ChunkPager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The merged region geometry's <b>world</b> bound must keep up as chunks join the region.
 *
 * <p>Regression test. {@code MeshMerger} writes the bound on
 * the MESH, and a mesh bound written in place does not invalidate the {@link Geometry}'s cached world bound
 * (jME only recomputes it when the spatial is flagged, and a batch's transform never changes). So a region
 * kept the bound of its FIRST merge for ever : if that merge held one chunk of eight, the bound covered an
 * eighth of the geometry and jME frustum-culled the whole region as soon as that eighth left the view —
 * chunks vanishing while plainly on screen.
 *
 * <p>Written as a unit test rather than a screenshot because the screenshot HARNESS could not see it : it
 * attaches 100 pages per frame while building, so a region's first merge already holds most of its chunks.
 * The game attaches 32 and spreads a slab over frames, and reproduces it readily on desktop and on device
 * alike. The condition is only that one region's pages arrive in separate merges, which is what this test
 * arranges directly. {@code ChunkMeshBatchState} carries the same fix (its {@code refreshBound}) for the same
 * reason — "the sea vanishes when you tilt up".
 */
class ChunkRegionBatchBoundTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION,
                PrimitiveAllocator.class.getName());
    }

    private static final String TYPE = "generic";
    private static final int CHUNK = 16;

    /** A page node at the chunk's world position, holding one small geometry of the batched type. */
    private static Node page(Vec3i chunk) {
        Mesh mesh = new Mesh();
        // A single triangle spanning the chunk, in CHUNK-LOCAL coordinates : the merger is what puts it
        // into world space, which is the behaviour under test.
        mesh.setBuffer(VertexBuffer.Type.Position, 3, new float[]{
                0, 0, 0,
                CHUNK, 0, 0,
                0, CHUNK, CHUNK});
        mesh.setBuffer(VertexBuffer.Type.Index, 3, new short[]{0, 1, 2});
        mesh.updateBound();
        Geometry geometry = new Geometry(TYPE, mesh);
        Node node = new Node("page " + chunk);
        node.attachChild(geometry);
        node.setLocalTranslation(chunk.x * (float) CHUNK, chunk.y * (float) CHUNK, chunk.z * (float) CHUNK);
        return node;
    }

    /**
     * One frame : the state's update, then jME's scene-graph pass. The pass matters — it consumes the bound
     * refresh flag, so a mesh bound mutated afterwards is not picked up. Doing it only once at the end would
     * let the flag set when the geometry was created survive and quietly hide the very staleness under test.
     */
    private static void frame(SimpleApplication app, ChunkRegionBatchState state) {
        state.update(0f);
        app.getRootNode().updateGeometricState();
    }

    @Test
    void regionBoundGrowsAsChunksJoinTheRegion() {
        SimpleApplication app = new SimpleApplication() {
            @Override
            public void simpleInitApp() {
                // never started : only getRootNode() is needed
            }
        };
        // ChunkPager reads the global BlocksConfig in its constructor.
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
        ChunkPager pager = new ChunkPager(new Node("chunks"),
                ChunkManager.builder().poolSize(1).build());
        // Region 2x2x2, and one rebuild per frame so the merges really are separate — that separation is
        // exactly the condition the bug needs.
        ChunkRegionBatchState state = new ChunkRegionBatchState(TYPE, 2, 1, pager);
        state.initialize(app);

        // First merge : the region holds a single chunk at the origin.
        Node first = page(new Vec3i(0, 0, 0));
        app.getRootNode().attachChild(first);
        state.onPageTracked(new Vec3i(0, 0, 0), first);
        frame(app, state);

        // Then the rest of the 2x2x2 region arrives, one merge at a time.
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    Vec3i location = new Vec3i(x, y, z);
                    Node node = page(location);
                    app.getRootNode().attachChild(node);
                    state.onPageTracked(location, node);
                    frame(app, state);
                }
            }
        }
        frame(app, state); // drain any region still queued

        Node batchNode = (Node) app.getRootNode().getChild("RegionBatch - " + TYPE);
        assertNotNull(batchNode, "the batch node must be attached");
        assertTrue(batchNode.getQuantity() > 0, "the region must have produced a merged geometry");
        Geometry merged = (Geometry) batchNode.getChild(0);

        // The far chunk's geometry reaches (16,32,32) in world space : chunk (1,1,1) sits at (16,16,16) and
        // its triangle spans another chunk in Y and Z. A bound frozen on the first merge stops at (16,16,16).
        BoundingVolume bound = merged.getWorldBound();
        assertNotNull(bound, "the merged geometry must have a world bound");
        Vector3f far = new Vector3f(16f, 32f, 32f);
        assertTrue(bound.contains(far) || bound.intersects(far),
                "the region's world bound must cover every chunk that joined it, not just the first :"
                        + " bound=" + bound + " does not reach " + far);
    }
}

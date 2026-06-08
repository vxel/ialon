package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.mesh.IndexBuffer;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import com.simsilica.mathd.Vec3i;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.delaunois.ialon.blocks.FacesMeshGenerator;

/**
 * Validates the calm-water greedy surface mesher (FacesMeshGenerator) : the flat top of a still
 * (source) water body must be merged into far fewer quads than one-per-block, while conserving the
 * total surface area exactly (no holes, no overlap).
 */
class CalmWaterGreedyTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @BeforeAll
    static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @Test
    void calmWaterTopIsMergedAndAreaConserved() {
        IalonConfig config = new IalonConfig();
        config.setGreedyCalmWater(true);

        BlocksConfig blocksConfig = BlocksConfig.getInstance();
        Vec3i cs = blocksConfig.getChunkSize();
        float blockScale = blocksConfig.getBlockScale();
        Block rock = blocksConfig.getBlockRegistry().get(BlockIds.ROCK);
        Block waterSource = blocksConfig.getBlockRegistry().get(BlockIds.WATER_SOURCE);
        assertNotNull(waterSource, "test requires the water source block");

        // A full sea-surface layer : rock floor at y=0, a source-water layer at y=1 open to the air.
        Chunk chunk = Chunk.createAt(new Vec3i(0, 0, 0));
        for (int x = 0; x < cs.x; x++) {
            for (int z = 0; z < cs.z; z++) {
                chunk.addBlock(x, 0, z, rock);
                chunk.addBlock(x, 1, z, waterSource);
            }
        }
        chunk.update();

        FacesMeshGenerator generator = new FacesMeshGenerator(config);
        generator.createAndSetNodeAndCollisionMesh(chunk);

        Mesh calm = findCalmWaterMesh(chunk.getNode());
        assertNotNull(calm, "a calm-water surface mesh must have been generated");

        int surfaceCells = cs.x * cs.z;
        int triangles = calm.getTriangleCount();
        // One textured quad per cell would be 2 triangles per cell ; greedy must do much better.
        assertTrue(triangles < surfaceCells, // i.e. < 2 triangles per cell on average, by a wide margin
                "calm water surface should be merged (got " + triangles + " triangles for " + surfaceCells + " cells)");

        // Area conservation : the merged quads must cover exactly the surface, no more, no less.
        float expectedArea = surfaceCells * blockScale * blockScale;
        float actualArea = totalArea(calm);
        assertTrue(Math.abs(actualArea - expectedArea) < 1e-2f * expectedArea,
                "merged surface area " + actualArea + " must match the cell area " + expectedArea);
    }

    private static Mesh findCalmWaterMesh(Node node) {
        for (Spatial child : node.getChildren()) {
            if (child instanceof Geometry && "water_calm".equals(child.getName())) {
                return ((Geometry) child).getMesh();
            }
        }
        return null;
    }

    private static float totalArea(Mesh mesh) {
        FloatBuffer pos = mesh.getFloatBuffer(VertexBuffer.Type.Position);
        IndexBuffer ib = mesh.getIndexBuffer();
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        float area = 0f;
        for (int i = 0; i < ib.size(); i += 3) {
            read(pos, ib.get(i), a);
            read(pos, ib.get(i + 1), b);
            read(pos, ib.get(i + 2), c);
            area += b.subtract(a).cross(c.subtract(a)).length() * 0.5f;
        }
        return area;
    }

    private static void read(FloatBuffer pos, int index, Vector3f store) {
        store.set(pos.get(index * 3), pos.get(index * 3 + 1), pos.get(index * 3 + 2));
    }
}

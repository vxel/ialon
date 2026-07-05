package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.mesh.IndexBuffer;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import java.nio.FloatBuffer;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.Direction;
import org.delaunois.ialon.blocks.FacesMeshGenerator;
import org.delaunois.ialon.blocks.Shape;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeIds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the z-fighting artifact seen when a partially-covering solid (a stair or a wedge)
 * sits against or in water : the solid's full opaque back and a water face are emitted at the same cell
 * boundary and z-fight (a shimmer in the back of the solid). Such a back fully covers that boundary
 * square just like an opaque cube does, so the water must cull its face there exactly as against a cube.
 * <p>
 * Two cases are checked, for both stairs and wedges (both fully cover their NORTH back and DOWN base) :
 * <ul>
 *   <li>the solid dams water from an adjacent cell (water's neighbour is the solid), and</li>
 *   <li>the solid is submerged, water co-habiting its own cell (the water shape's centre block IS the
 *       solid) — the case in the reported screenshot.</li>
 * </ul>
 */
class StairInWaterZFightTest {

    /** Solid families that only partially fill their cell yet fully cover their NORTH back + DOWN base. */
    private static final String[][] COVERING_FAMILIES = {ShapeIds.ALL_STAIRS, ShapeIds.ALL_WEDGES};

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @BeforeAll
    static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @Test
    void waterCullsFaceAgainstFullBack() {
        BlocksConfig blocksConfig = BlocksConfig.getInstance();
        Block waterSource = blocksConfig.getBlockRegistry().get(BlockIds.WATER_SOURCE);
        assertNotNull(waterSource, "test requires the water source block");
        Block rock = blocksConfig.getBlockRegistry().get(BlockIds.ROCK);
        assertNotNull(rock, "test requires an opaque full-cube block (rock)");

        int withCube = waterTriangles(buildScene(waterSource, rock));

        for (String[] family : COVERING_FAMILIES) {
            // A variant whose full back covers its NORTH face : placed to the SOUTH of a water cell, that
            // back seals the shared boundary exactly like an opaque cube would.
            Block northCovering = findOpaqueCovering(blocksConfig, Direction.NORTH, family);
            assertNotNull(northCovering, "test requires an opaque variant covering its NORTH face in " + family[0]);

            int withShape = waterTriangles(buildScene(waterSource, northCovering));
            assertEquals(withCube, withShape,
                    "water must cull its face against the fully-covering back of " + northCovering.getName()
                            + ", like against a cube (z-fighting)");
        }
    }

    @Test
    void submergedSolidEmitsNoWaterFaceOnItsCoveredBack() {
        BlocksConfig blocksConfig = BlocksConfig.getInstance();
        float blockScale = blocksConfig.getBlockScale();
        // NORTH is -Z : the back face of cell (8,8,8) lies at world z = (8 - 0.5) * blockScale.
        float coveredZ = (8f - 0.5f) * blockScale;

        for (String[] family : COVERING_FAMILIES) {
            // A variant that (a) carries water in its own cell (submerged in water) and (b) fully covers
            // its NORTH back. The co-habiting water must NOT emit a face on that back : the solid's own
            // opaque back already seals it, so a water quad there would be coplanar and z-fight.
            Block solidInWater = findWaterCarryingCovering(blocksConfig, Direction.NORTH, family);
            assertNotNull(solidInWater, "test requires a water-carrying variant covering its NORTH face in " + family[0]);

            Node node = buildSingle(solidInWater);
            assertEquals(0, coplanarWaterTriangles(node, coveredZ),
                    "the co-habiting water must emit no quad on the fully-covered opaque back of "
                            + solidInWater.getName() + " (z-fighting)");
        }
    }

    /** A single block at (8,8,8), all neighbours air. */
    private static Node buildSingle(Block block) {
        Chunk chunk = Chunk.createAt(new Vec3i(0, 0, 0));
        chunk.addBlock(8, 8, 8, block);
        chunk.update();

        FacesMeshGenerator generator = new FacesMeshGenerator(new IalonConfig());
        generator.createAndSetNodeAndCollisionMesh(chunk);
        return chunk.getNode();
    }

    /** Water cell at (8,8,8) with {@code southNeighbour} directly to its SOUTH at (8,8,9). */
    private static Node buildScene(Block water, Block southNeighbour) {
        Chunk chunk = Chunk.createAt(new Vec3i(0, 0, 0));
        chunk.addBlock(8, 8, 8, water);
        chunk.addBlock(8, 8, 9, southNeighbour);
        chunk.update();

        FacesMeshGenerator generator = new FacesMeshGenerator(new IalonConfig());
        generator.createAndSetNodeAndCollisionMesh(chunk);
        return chunk.getNode();
    }

    private static int waterTriangles(Node node) {
        for (Spatial child : node.getChildren()) {
            if (child instanceof Geometry && TypeIds.WATER.equals(child.getName())) {
                return ((Geometry) child).getMesh().getTriangleCount();
            }
        }
        return 0;
    }

    /** Number of WATER triangles whose 3 vertices all lie in the plane {@code z == planeZ}. */
    private static int coplanarWaterTriangles(Node node, float planeZ) {
        for (Spatial child : node.getChildren()) {
            if (child instanceof Geometry && TypeIds.WATER.equals(child.getName())) {
                Mesh mesh = ((Geometry) child).getMesh();
                FloatBuffer pos = mesh.getFloatBuffer(VertexBuffer.Type.Position);
                IndexBuffer idx = mesh.getIndexBuffer();
                int count = 0;
                for (int t = 0; t < idx.size(); t += 3) {
                    if (inPlane(pos, idx.get(t), planeZ)
                            && inPlane(pos, idx.get(t + 1), planeZ)
                            && inPlane(pos, idx.get(t + 2), planeZ)) {
                        count++;
                    }
                }
                return count;
            }
        }
        return 0;
    }

    private static boolean inPlane(FloatBuffer pos, int vertexIndex, float planeZ) {
        return Math.abs(pos.get(vertexIndex * 3 + 2) - planeZ) < 1e-3f;
    }

    /** An opaque, water-free block using one of {@code shapeIds} whose shape fully covers {@code face}. */
    private static Block findOpaqueCovering(BlocksConfig blocksConfig, Direction face, String[] shapeIds) {
        return findCovering(blocksConfig, face, shapeIds, false);
    }

    /** A water-carrying block using one of {@code shapeIds} whose shape fully covers {@code face}. */
    private static Block findWaterCarryingCovering(BlocksConfig blocksConfig, Direction face, String[] shapeIds) {
        return findCovering(blocksConfig, face, shapeIds, true);
    }

    private static Block findCovering(BlocksConfig blocksConfig, Direction face, String[] shapeIds, boolean withWater) {
        for (String shapeId : shapeIds) {
            Shape shape = blocksConfig.getShapeRegistry().get(shapeId);
            if (shape == null || !shape.fullyCoversFace(face)) {
                continue;
            }
            for (Block b : blocksConfig.getBlockRegistry().getAll()) {
                if (!shapeId.equals(b.getShape()) || b.isTransparent()) {
                    continue;
                }
                boolean carriesWater = b.getLiquidLevel() > 0
                        && !TypeIds.WATER.equals(b.getType()) && !TypeIds.LAVA.equals(b.getType());
                if (withWater ? carriesWater : b.getLiquidLevel() == 0) {
                    return b;
                }
            }
        }
        return null;
    }
}

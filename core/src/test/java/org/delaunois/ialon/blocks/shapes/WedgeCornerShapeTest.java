package org.delaunois.ialon.blocks.shapes;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkMesh;
import org.delaunois.ialon.blocks.Direction;
import org.delaunois.ialon.blocks.Shape;
import org.delaunois.ialon.blocks.ShapeIds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry checks for the two wedge corner shapes ({@link WedgeInnerCorner}, {@link WedgeOuterCorner}),
 * which exist to join two perpendicular wedges. The properties tested are the ones a screenshot cannot
 * confirm and a wrong sign silently breaks :
 * <ul>
 *   <li>every triangle winds counter-clockwise around its own emitted normal (an inverted winding shows
 *       up as wrong lighting, invisible while the block material culls no face) ;</li>
 *   <li>no vertex leaves its cell, in any of the 4 rotations x inverted ;</li>
 *   <li>the corner meets the wedge it is meant to continue <b>vertex for vertex</b> in their shared
 *       boundary plane — the watertightness that makes a run of wedges turn a corner without a gap ;</li>
 *   <li>{@code fullyCoversFace} answers for the rotated shape, since neighbours cull their faces on it.</li>
 * </ul>
 */
class WedgeCornerShapeTest {

    private static final Vec3i ORIGIN = new Vec3i(0, 0, 0);
    private static final Vec3i CELL = new Vec3i(8, 8, 8);
    /** Tolerance on a vertex coordinate : the positions are plain floats, only scaled and offset. */
    private static final float EPSILON = 1e-4f;

    private static final String[] INNER_CORNERS = {
            ShapeIds.WEDGE_INNER_CORNER_NORTH, ShapeIds.WEDGE_INNER_CORNER_EAST,
            ShapeIds.WEDGE_INNER_CORNER_SOUTH, ShapeIds.WEDGE_INNER_CORNER_WEST,
            ShapeIds.WEDGE_INVERTED_INNER_CORNER_NORTH, ShapeIds.WEDGE_INVERTED_INNER_CORNER_EAST,
            ShapeIds.WEDGE_INVERTED_INNER_CORNER_SOUTH, ShapeIds.WEDGE_INVERTED_INNER_CORNER_WEST
    };
    private static final String[] OUTER_CORNERS = {
            ShapeIds.WEDGE_OUTER_CORNER_NORTH, ShapeIds.WEDGE_OUTER_CORNER_EAST,
            ShapeIds.WEDGE_OUTER_CORNER_SOUTH, ShapeIds.WEDGE_OUTER_CORNER_WEST,
            ShapeIds.WEDGE_INVERTED_OUTER_CORNER_NORTH, ShapeIds.WEDGE_INVERTED_OUTER_CORNER_EAST,
            ShapeIds.WEDGE_INVERTED_OUTER_CORNER_SOUTH, ShapeIds.WEDGE_INVERTED_OUTER_CORNER_WEST
    };

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @BeforeAll
    static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @Test
    void registersEveryRotationOfBothCorners() {
        for (String id : INNER_CORNERS) {
            assertEquals(WedgeInnerCorner.class, shape(id).getClass(), id);
        }
        for (String id : OUTER_CORNERS) {
            assertEquals(WedgeOuterCorner.class, shape(id).getClass(), id);
        }
    }

    @Test
    void emitsTheExpectedFaces() {
        // Outer : the base square (2 triangles) + 2 vertical triangles + the 2 slopes.
        assertEquals(6, mesh(ShapeIds.WEDGE_OUTER_CORNER_SOUTH).triangles());
        // Inner : the base square + 2 full vertical squares (2 triangles each) + 2 vertical triangles
        // + the 2 slopes.
        assertEquals(10, mesh(ShapeIds.WEDGE_INNER_CORNER_SOUTH).triangles());
    }

    @Test
    void windsEveryTriangleAroundItsNormal() {
        for (String id : INNER_CORNERS) {
            assertWindingMatchesNormals(id);
        }
        for (String id : OUTER_CORNERS) {
            assertWindingMatchesNormals(id);
        }
    }

    @Test
    void keepsEveryVertexInsideTheCell() {
        float scale = BlocksConfig.getInstance().getBlockScale();
        for (String id : allCorners()) {
            for (Vector3f v : mesh(id).vertices()) {
                assertVertexInCell(id, v, scale);
            }
        }
    }

    /**
     * The outer corner turns a convex corner : its west face continues the slope of a {@code wedge_south}
     * placed to its WEST, and its north face that of a {@code wedge_east} placed to its NORTH.
     */
    @Test
    void outerCornerMeetsTheWedgesItTurns() {
        assertBoundaryMatches(ShapeIds.WEDGE_OUTER_CORNER_SOUTH, ShapeIds.WEDGE_SOUTH, Direction.WEST);
        assertBoundaryMatches(ShapeIds.WEDGE_OUTER_CORNER_SOUTH, ShapeIds.WEDGE_EAST, Direction.NORTH);
    }

    /**
     * The inner corner turns a concave corner : the wedges it continues sit on the other two sides, a
     * {@code wedge_south} to its EAST and a {@code wedge_east} to its SOUTH.
     */
    @Test
    void innerCornerMeetsTheWedgesItTurns() {
        assertBoundaryMatches(ShapeIds.WEDGE_INNER_CORNER_SOUTH, ShapeIds.WEDGE_SOUTH, Direction.EAST);
        assertBoundaryMatches(ShapeIds.WEDGE_INNER_CORNER_SOUTH, ShapeIds.WEDGE_EAST, Direction.SOUTH);
    }

    @Test
    void reportsTheFacesItFullyCovers() {
        // Upright, default (south) facing : only the base square fills its face for the outer corner ; the
        // inner corner also keeps its north and west walls whole.
        assertCovers(ShapeIds.WEDGE_OUTER_CORNER_SOUTH, Direction.DOWN);
        assertCovers(ShapeIds.WEDGE_INNER_CORNER_SOUTH, Direction.DOWN, Direction.NORTH, Direction.WEST);
        // Rotated : the yaw turns the walls with the shape (local north -> west, local west -> south).
        assertCovers(ShapeIds.WEDGE_INNER_CORNER_EAST, Direction.DOWN, Direction.WEST, Direction.SOUTH);
        // Inverted : a half turn around Z, so down becomes up and west becomes east.
        assertCovers(ShapeIds.WEDGE_INVERTED_OUTER_CORNER_SOUTH, Direction.UP);
        assertCovers(ShapeIds.WEDGE_INVERTED_INNER_CORNER_SOUTH, Direction.UP, Direction.NORTH, Direction.EAST);
    }

    /**
     * The inverted variant is the upright one turned upside down, which the wedge family implements as a
     * half turn around the Z axis : the same shape with y and x mirrored through the cell centre. So a
     * ceiling corner keeps the slope of the floor corner it hangs above, rather than being a fresh mesh.
     */
    @Test
    void invertsAsAHalfTurnAroundZ() {
        for (String[] family : new String[][]{INNER_CORNERS, OUTER_CORNERS}) {
            // family[2] is the upright SOUTH variant, family[6] its inverted counterpart.
            assertEquals(mirroredThroughCellCentre(mesh(family[2])), corners(mesh(family[6])),
                    family[6] + " is not " + family[2] + " turned upside down");
        }
    }

    /** The distinct vertices of the mesh, mirrored in x and y around the centre of the cell. */
    private static Set<String> mirroredThroughCellCentre(Mesh mesh) {
        float scale = BlocksConfig.getInstance().getBlockScale();
        Set<String> mirrored = new HashSet<>();
        for (Vector3f v : mesh.vertices()) {
            mirrored.add(key(2 * CELL.x * scale - v.x, 2 * CELL.y * scale - v.y, v.z));
        }
        return mirrored;
    }

    private static Set<String> corners(Mesh mesh) {
        Set<String> all = new HashSet<>();
        for (Vector3f v : mesh.vertices()) {
            all.add(key(v.x, v.y, v.z));
        }
        return all;
    }

    private static void assertWindingMatchesNormals(String id) {
        Mesh mesh = mesh(id);
        assertFalse(mesh.vertices().isEmpty(), id + " emitted nothing");
        for (int t = 0; t < mesh.triangles(); t++) {
            Vector3f v0 = mesh.vertex(mesh.index(t * 3));
            Vector3f v1 = mesh.vertex(mesh.index(t * 3 + 1));
            Vector3f v2 = mesh.vertex(mesh.index(t * 3 + 2));
            Vector3f geometric = v1.subtract(v0).crossLocal(v2.subtract(v0));
            assertTrue(geometric.length() > EPSILON, id + " emitted a degenerate triangle " + t);
            geometric.normalizeLocal();
            // All 3 vertices of a face share its normal, so any of them will do.
            Vector3f emitted = mesh.normal(mesh.index(t * 3));
            assertEquals(1f, geometric.dot(emitted), 1e-3f,
                    id + " triangle " + t + " winds against its normal " + emitted + " (geometric " + geometric + ")");
        }
    }

    private static void assertVertexInCell(String id, Vector3f v, float scale) {
        assertTrue(Math.abs(v.x - CELL.x * scale) <= 0.5f * scale + EPSILON, id + " leaves its cell : " + v);
        assertTrue(Math.abs(v.y - CELL.y * scale) <= 0.5f * scale + EPSILON, id + " leaves its cell : " + v);
        assertTrue(Math.abs(v.z - CELL.z * scale) <= 0.5f * scale + EPSILON, id + " leaves its cell : " + v);
    }

    /**
     * Asserts that {@code cornerId} at {@link #CELL} and {@code wedgeId} in the neighbouring cell towards
     * {@code side} put exactly the same vertices in the boundary plane they share. Both shapes are meshed
     * alone (all neighbours air), so no face is culled and the profiles are compared in full.
     */
    private static void assertBoundaryMatches(String cornerId, String wedgeId, Direction side) {
        float scale = BlocksConfig.getInstance().getBlockScale();
        Vec3i step = side.getVector();
        Vec3i neighbour = new Vec3i(CELL.x + step.x, CELL.y + step.y, CELL.z + step.z);
        // The shared plane, on the axis the two cells differ along.
        int axis = step.x != 0 ? 0 : (step.y != 0 ? 1 : 2);
        float plane = (component(CELL, axis) + 0.5f * component(step, axis)) * scale;

        Set<String> cornerProfile = mesh(cornerId, CELL).verticesInPlane(axis, plane);
        Set<String> wedgeProfile = mesh(wedgeId, neighbour).verticesInPlane(axis, plane);
        assertFalse(cornerProfile.isEmpty(), cornerId + " has no face on its " + side + " boundary");
        assertEquals(wedgeProfile, cornerProfile,
                cornerId + " does not meet " + wedgeId + " placed to its " + side);
    }

    private static void assertCovers(String id, Direction... covered) {
        Set<Direction> expected = new HashSet<>();
        for (Direction d : covered) {
            expected.add(d);
        }
        for (Direction d : Direction.values()) {
            assertEquals(expected.contains(d), shape(id).fullyCoversFace(d),
                    id + " fullyCoversFace(" + d + ")");
        }
    }

    /** A comparable key for a vertex : rounded, so two shapes that meet exactly compare equal. */
    private static String key(float x, float y, float z) {
        return String.format("%.3f/%.3f/%.3f", x, y, z);
    }

    private static int component(Vec3i v, int axis) {
        return axis == 0 ? v.x : (axis == 1 ? v.y : v.z);
    }

    private static Shape shape(String id) {
        Shape shape = BlocksConfig.getInstance().getShapeRegistry().get(id);
        assertNotNull(shape, "shape " + id + " is not registered");
        return shape;
    }

    private static Mesh mesh(String shapeId) {
        return mesh(shapeId, CELL);
    }

    /** Meshes a single (opaque, single-texture) block of the given shape, alone in its chunk. */
    private static Mesh mesh(String shapeId, Vec3i location) {
        Block block = BlocksConfig.getInstance().getBlockRegistry()
                .get(BlockIds.getName(org.delaunois.ialon.blocks.TypeIds.ROCK, shapeId, 0));
        assertNotNull(block, "test requires the rock block in shape " + shapeId);
        Chunk chunk = Chunk.createAt(ORIGIN);
        chunk.addBlock(location.x, location.y, location.z, block);
        chunk.update();

        ChunkMesh chunkMesh = new ChunkMesh(false);
        shape(shapeId).add(location, chunk, chunkMesh);
        return new Mesh(chunkMesh);
    }

    /** A snapshot of the buffers a shape emitted (they can only be read once). */
    private static final class Mesh {
        private final float[] positions;
        private final float[] normals;
        private final int[] indices;

        private Mesh(ChunkMesh chunkMesh) {
            positions = floats(chunkMesh.getPositions().getBuffer());
            normals = floats(chunkMesh.getNormals().getBuffer());
            indices = ints(chunkMesh.getIndices().getBuffer());
        }

        private int triangles() {
            return indices.length / 3;
        }

        private int index(int i) {
            return indices[i];
        }

        private Vector3f vertex(int i) {
            return new Vector3f(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
        }

        private Vector3f normal(int i) {
            return new Vector3f(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]);
        }

        private List<Vector3f> vertices() {
            List<Vector3f> all = new ArrayList<>();
            for (int i = 0; i < positions.length / 3; i++) {
                all.add(vertex(i));
            }
            return all;
        }

        /** The distinct vertices lying in the plane {@code axis == value}, rounded so they can be compared. */
        private Set<String> verticesInPlane(int axis, float value) {
            Set<String> inPlane = new HashSet<>();
            for (Vector3f v : vertices()) {
                float[] c = {v.x, v.y, v.z};
                if (Math.abs(c[axis] - value) < EPSILON) {
                    inPlane.add(key(v.x, v.y, v.z));
                }
            }
            return inPlane;
        }

        private static float[] floats(FloatBuffer buffer) {
            float[] out = new float[buffer.limit()];
            buffer.get(out);
            return out;
        }

        private static int[] ints(IntBuffer buffer) {
            int[] out = new int[buffer.limit()];
            buffer.get(out);
            return out;
        }
    }

    private static List<String> allCorners() {
        List<String> all = new ArrayList<>();
        for (String id : INNER_CORNERS) {
            all.add(id);
        }
        for (String id : OUTER_CORNERS) {
            all.add(id);
        }
        return all;
    }
}

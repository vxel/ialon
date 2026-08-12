package org.delaunois.ialon.blocks.shapes;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockNeighborhood;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkMesh;
import org.delaunois.ialon.blocks.Direction;
import org.delaunois.ialon.blocks.Shape;

import lombok.ToString;

/**
 * A shape implementation for the <b>inner</b> (concave) corner of a wedge : the roof-valley corner that
 * joins two perpendicular wedges. The default facing is South, like the {@link Wedge} : the shape keeps
 * its full height along the NORTH and WEST faces and slopes down to the single south-east corner — the
 * very quadrant the {@link StairsInnerCorner} steps down to, so both families rotate alike.
 * <p>
 * Geometrically it is the union of a south-facing and an east-facing wedge ({@code y <= max(-x, -z)}) :
 * a cube minus the tetrahedron above its south-east corner, with a valley running down from the top
 * north-west corner to that corner. Its east face is exactly the side triangle of a {@code wedge_south}
 * and its south face that of a {@code wedge_east}, so it continues a run of wedges placed to its EAST
 * (south-facing) and to its SOUTH (east-facing) without a gap.
 * <p>
 * Seven faces : the full base square, the two full vertical squares (north, west), two vertical triangles
 * (east, south) and the two sloping triangles. Every vertex is smooth-shadowed from the same neighbour
 * lights the {@link Wedge} uses, so the shading runs across the joint instead of the corner reading as a
 * flat-lit patch against its neighbours.
 *
 * @author Cedric de Launois
 */
@ToString
public class WedgeInnerCorner implements Shape {

    private static final Quaternion PI_X = new Quaternion().fromAngleAxis(FastMath.PI, Vector3f.UNIT_X);
    private static final Quaternion PI_Y = new Quaternion().fromAngleAxis(FastMath.PI, Vector3f.UNIT_Y);
    private static final Quaternion INVERSE = PI_X.mult(PI_Y);

    // The corners of the shape (named by the sign of x, y, z : n = -0.5, p = +0.5) : the 4 base corners
    // plus the 3 top ones the cube keeps (the top south-east corner is cut away). Shared immutable
    // constants ; Shape.emitQuad/emitTriangle never mutate the local vertices.
    private static final Vector3f C_NNN = new Vector3f(-0.5f, -0.5f, -0.5f);
    private static final Vector3f C_NNP = new Vector3f(-0.5f, -0.5f, 0.5f);
    private static final Vector3f C_PNN = new Vector3f(0.5f, -0.5f, -0.5f);
    private static final Vector3f C_PNP = new Vector3f(0.5f, -0.5f, 0.5f);
    private static final Vector3f C_NPN = new Vector3f(-0.5f, 0.5f, -0.5f);
    private static final Vector3f C_NPP = new Vector3f(-0.5f, 0.5f, 0.5f);
    private static final Vector3f C_PPN = new Vector3f(0.5f, 0.5f, -0.5f);

    // A face coordinate t (0..1 over the face) mapped into the padded tile : the tile occupies the middle
    // half of its texture-array layer (see Shape#UV_PADDING), so t -> t / UV_PADDING_FACTOR + UV_PADDING.
    private static final float T0 = UV_PADDING;
    private static final float T1 = 1f / UV_PADDING_FACTOR + UV_PADDING;
    // Stacked (top/side/bottom) textures : v also selects the band, a third of the image each
    // (TypeRegistry#assignLayers picks the layer from floor(v * 3) and rescales v inside the band). The
    // V0/V1 pairs are the same t = 0 / t = 1 ends, expressed in the bottom, side and top band.
    private static final float BOTTOM_V0 = UV_PADDING;
    private static final float BOTTOM_V1 = 1f / 3f - UV_PADDING;
    private static final float SIDE_V0 = 2f / 3f - UV_PADDING;
    private static final float SIDE_V1 = 1f / 3f + UV_PADDING;
    private static final float TOP_V0 = 1f - UV_PADDING;
    private static final float TOP_V1 = 2f / 3f + UV_PADDING;

    // DOWN : the full base square, u = x + 0.5, v = z + 0.5.
    private static final Vector2f[] UV_DOWN_SINGLE = {
            new Vector2f(T0, T0), new Vector2f(T1, T0),
            new Vector2f(T0, T1), new Vector2f(T1, T1)
    };
    private static final Vector2f[] UV_DOWN_MULTI = {
            new Vector2f(T0, BOTTOM_V0), new Vector2f(T1, BOTTOM_V0),
            new Vector2f(T0, BOTTOM_V1), new Vector2f(T1, BOTTOM_V1)
    };
    // The two full vertical squares (north, west) share the cube's side layout : u along the face, v = the
    // height. Emitted as (bottom-right, top-right, bottom-left, top-left) seen from outside.
    private static final Vector2f[] UV_SIDE_SINGLE = {
            new Vector2f(T1, T0), new Vector2f(T1, T1),
            new Vector2f(T0, T0), new Vector2f(T0, T1)
    };
    private static final Vector2f[] UV_SIDE_MULTI = {
            new Vector2f(T1, SIDE_V0), new Vector2f(T1, SIDE_V1),
            new Vector2f(T0, SIDE_V0), new Vector2f(T0, SIDE_V1)
    };
    // EAST triangle (C_PNN, C_PPN, C_PNP) : u = 0.5 - z, v = y + 0.5. Same triangle a wedge_south puts on
    // its east side.
    private static final Vector2f[] UV_EAST_SINGLE = {
            new Vector2f(T1, T0), new Vector2f(T1, T1), new Vector2f(T0, T0)
    };
    private static final Vector2f[] UV_EAST_MULTI = {
            new Vector2f(T1, SIDE_V0), new Vector2f(T1, SIDE_V1), new Vector2f(T0, SIDE_V0)
    };
    // SOUTH triangle (C_NNP, C_PNP, C_NPP) : u = x + 0.5, v = y + 0.5.
    private static final Vector2f[] UV_SOUTH_SINGLE = {
            new Vector2f(T0, T0), new Vector2f(T1, T0), new Vector2f(T0, T1)
    };
    private static final Vector2f[] UV_SOUTH_MULTI = {
            new Vector2f(T0, SIDE_V0), new Vector2f(T1, SIDE_V0), new Vector2f(T0, SIDE_V1)
    };
    // South-facing slope (C_NPN, C_PNP, C_PPN) : u = x + 0.5, v = y + 0.5, the mapping a wedge_south uses
    // for its own slope, so the texture flows from one onto the other.
    private static final Vector2f[] UV_SLOPE_SOUTH_SINGLE = {
            new Vector2f(T0, T1), new Vector2f(T1, T0), new Vector2f(T1, T1)
    };
    private static final Vector2f[] UV_SLOPE_SOUTH_MULTI = {
            new Vector2f(T0, TOP_V1), new Vector2f(T1, TOP_V0), new Vector2f(T1, TOP_V1)
    };
    // East-facing slope (C_NPN, C_NPP, C_PNP) : u = 0.5 - z, v = y + 0.5, the same mapping seen from the
    // east — i.e. what a wedge_east lays on its slope.
    private static final Vector2f[] UV_SLOPE_EAST_SINGLE = {
            new Vector2f(T1, T1), new Vector2f(T0, T1), new Vector2f(T0, T0)
    };
    private static final Vector2f[] UV_SLOPE_EAST_MULTI = {
            new Vector2f(T1, TOP_V1), new Vector2f(T0, TOP_V1), new Vector2f(T0, TOP_V0)
    };

    // Smooth (ambient-occlusion) shading tables. A vertex is shaded from the 3 neighbours touching its
    // corner : the (side, side, corner) triples below index the 8 lights BlockNeighborhood returns around
    // a face, in the winding order of its offset table. Reusing the tables of Cube (for the axis-aligned
    // faces) and of Wedge (for a slope) is what makes the shading flow across the joint between a corner
    // and the wedges it continues, instead of the corner standing out as a flat-lit patch.
    private static final int[][] LIGHTS_FACE = {{7, 1, 0}, {1, 3, 2}, {5, 7, 6}, {3, 5, 4}};
    private static final int[][] LIGHTS_SLOPE = {{5, 7, 6}, {7, 1, 0}, {3, 5, 4}, {1, 3, 2}};
    // Which corners of the full face/slope each part uses, in the order its vertices are emitted. Face
    // corners are numbered in Cube's emission order (bottom-far, top-far, bottom-near, top-near) and slope
    // corners in Wedge's (C_PNP, C_PPN, C_NNP, C_NPN, i.e. low-east, high-east, low-west, high-west).
    private static final int[] CORNERS_EAST = {0, 1, 2};        // C_PNN, C_PPN, C_PNP
    private static final int[] CORNERS_SOUTH = {2, 0, 3};       // C_NNP, C_PNP, C_NPP
    private static final int[] CORNERS_SLOPE_SOUTH = {3, 0, 1}; // C_NPN, C_PNP, C_PPN
    private static final int[] CORNERS_SLOPE_EAST = {1, 3, 2};  // C_NPN, C_NPP, C_PNP

    private final Direction direction;
    private final boolean upsideDown;
    private final Quaternion emitRotation;

    private final Vector3f nDown;
    private final Vector3f nNorth;
    private final Vector3f nWest;
    private final Vector3f nEast;
    private final Vector3f nSouth;
    private final Vector3f nSlopeSouth;
    private final Vector3f nSlopeEast;

    public WedgeInnerCorner() {
        this(Direction.UP, false);
    }

    public WedgeInnerCorner(Direction direction, boolean upsideDown) {
        this.direction = direction;
        this.upsideDown = upsideDown;

        // when the shape is upside down (inverted), we need to perform 3 rotations. Two to invert the shape
        // and one for the direction.
        Quaternion rot = Shape.getYawFromDirection(direction);
        if (upsideDown) {
            rot = INVERSE.mult(rot.inverse());
        }
        this.emitRotation = Quaternion.IDENTITY.equals(rot) ? null : rot;

        this.nDown = rot.mult(new Vector3f(0.0f, -1.0f, 0.0f));
        this.nNorth = rot.mult(new Vector3f(0.0f, 0.0f, -1.0f));
        this.nWest = rot.mult(new Vector3f(-1.0f, 0.0f, 0.0f));
        this.nEast = rot.mult(new Vector3f(1.0f, 0.0f, 0.0f));
        this.nSouth = rot.mult(new Vector3f(0.0f, 0.0f, 1.0f));
        this.nSlopeSouth = rot.mult(new Vector3f(0.0f, 0.70710677f, 0.70710677f));
        this.nSlopeEast = rot.mult(new Vector3f(0.70710677f, 0.70710677f, 0.0f));
    }

    @Override
    public void add(BlockNeighborhood neighborhood, ChunkMesh chunkMesh) {
        Chunk chunk = neighborhood.getChunk();
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have 3 textures or only one
        Block block = neighborhood.getCenterBlock();
        if (block == null)
            return;

        boolean multipleImages = block.isUsingMultipleImages();
        boolean cm = chunkMesh.isCollisionMesh();
        boolean enlight = !cm;
        boolean flip = false;
        Vec3i location = neighborhood.getLocation();

        // The two slopes are interior to the cell : no neighbour can hide them, they are always emitted.
        if (enlight) {
            shadeSlope(neighborhood, chunkMesh, faceDirection(Direction.SOUTH), CORNERS_SLOPE_SOUTH);
        }
        createSlopeSouth(location, chunkMesh, blockScale, multipleImages, cm);

        if (enlight) {
            shadeSlope(neighborhood, chunkMesh, faceDirection(Direction.EAST), CORNERS_SLOPE_EAST);
        }
        createSlopeEast(location, chunkMesh, blockScale, multipleImages, cm);

        Direction face = faceDirection(Direction.NORTH);
        if (chunk.isFaceVisible(location, face)) {
            flip = enlight && shadeQuad(neighborhood, chunkMesh, face);
            createNorth(location, chunkMesh, blockScale, multipleImages, flip, cm);
        }

        face = faceDirection(Direction.WEST);
        if (chunk.isFaceVisible(location, face)) {
            flip = enlight && shadeQuad(neighborhood, chunkMesh, face);
            createWest(location, chunkMesh, blockScale, multipleImages, flip, cm);
        }

        face = faceDirection(Direction.EAST);
        if (chunk.isFaceVisible(location, face)) {
            if (enlight) {
                shadeFace(neighborhood, chunkMesh, face, CORNERS_EAST);
            }
            createEast(location, chunkMesh, blockScale, multipleImages, cm);
        }

        face = faceDirection(Direction.SOUTH);
        if (chunk.isFaceVisible(location, face)) {
            if (enlight) {
                shadeFace(neighborhood, chunkMesh, face, CORNERS_SOUTH);
            }
            createSouth(location, chunkMesh, blockScale, multipleImages, cm);
        }

        face = faceDirection(Direction.DOWN);
        if (chunk.isFaceVisible(location, face)) {
            flip = enlight && shadeQuad(neighborhood, chunkMesh, face);
            createDown(location, chunkMesh, blockScale, multipleImages, flip, cm);
        }
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        add(new BlockNeighborhood(location, chunk), chunkMesh);
    }

    /**
     * The base square and the two full vertical squares (north, west) cover their face entirely : the two
     * side triangles cover half of theirs, and both slopes are interior.
     */
    public boolean fullyCoversFace(Direction direction) {
        Direction localFace = Shape.getOppositeYawFaceDirection(direction, this.direction);
        return localFace == (upsideDown ? Direction.UP : Direction.DOWN)
                || localFace == Direction.NORTH
                || localFace == (upsideDown ? Direction.EAST : Direction.WEST);
    }

    /**
     * The world direction a local face of the (un-rotated, upright) shape points to. Inverting the shape
     * is a half turn around the Z axis, which swaps UP/DOWN and EAST/WEST but leaves NORTH/SOUTH alone.
     */
    private Direction faceDirection(Direction localFace) {
        return Shape.getYawFaceDirection(upsideDown ? mirrored(localFace) : localFace, direction);
    }

    private static Direction mirrored(Direction localFace) {
        switch (localFace) {
            case UP:
                return Direction.DOWN;
            case DOWN:
                return Direction.UP;
            case EAST:
                return Direction.WEST;
            case WEST:
                return Direction.EAST;
            default:
                return localFace;
        }
    }

    /** Shades the vertices of a triangular part of an axis-aligned face, in their emission order. */
    private void shadeFace(BlockNeighborhood n, ChunkMesh chunkMesh, Direction face, int[] corners) {
        shade(n, chunkMesh, n.getFaceLight(face),
                n.getNeighbourLights(direction, face, null, upsideDown), LIGHTS_FACE, corners);
    }

    /**
     * Shades a slope triangle exactly as the wedge whose slope it continues does : same neighbour table
     * (the one of a wedge whose slope faces {@code face}), same per-corner triples.
     */
    private void shadeSlope(BlockNeighborhood n, ChunkMesh chunkMesh, Direction face, int[] corners) {
        shade(n, chunkMesh, n.getSelfLight(),
                n.getNeighbourWedgeLights(face, null, upsideDown), LIGHTS_SLOPE, corners);
    }

    private static void shade(BlockNeighborhood n, ChunkMesh chunkMesh, Vector4f center, Vector4f[] nb,
                              int[][] lights, int[] corners) {
        Vector4f store = n.getColorScratch();
        for (int corner : corners) {
            int[] l = lights[corner];
            chunkMesh.getColors().add(n.getChunk().vertexColor(nb[l[0]], nb[l[1]], nb[l[2]], center, store));
        }
    }

    /**
     * Shades the 4 vertices of a full axis-aligned face and returns whether the quad should be split along
     * its other diagonal, the smoother one (same criterion as {@link Cube}).
     */
    private boolean shadeQuad(BlockNeighborhood n, ChunkMesh chunkMesh, Direction face) {
        Vector4f center = n.getFaceLight(face);
        Vector4f[] nb = n.getNeighbourLights(direction, face, null, upsideDown);
        Vector4f store = n.getColorScratch();
        Chunk chunk = n.getChunk();
        float a11 = chunk.vertexColor(nb[7], nb[1], nb[0], center, store).w;
        chunkMesh.getColors().add(store);
        float a01 = chunk.vertexColor(nb[1], nb[3], nb[2], center, store).w;
        chunkMesh.getColors().add(store);
        float a10 = chunk.vertexColor(nb[5], nb[7], nb[6], center, store).w;
        chunkMesh.getColors().add(store);
        float a00 = chunk.vertexColor(nb[3], nb[5], nb[4], center, store).w;
        chunkMesh.getColors().add(store);
        // Split along the diagonal whose two ends differ the least, so the quad's shading does not fold.
        return Math.abs(a00 - a11) < Math.abs(a01 - a10);
    }

    private void createDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages,
                            boolean flip, boolean cm) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                C_NNN, C_PNN, C_NNP, C_PNP,
                nDown, multipleImages ? UV_DOWN_MULTI : UV_DOWN_SINGLE, flip, cm);
    }

    private void createNorth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages,
                             boolean flip, boolean cm) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                C_NNN, C_NPN, C_PNN, C_PPN,
                nNorth, multipleImages ? UV_SIDE_MULTI : UV_SIDE_SINGLE, flip, cm);
    }

    private void createWest(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages,
                            boolean flip, boolean cm) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                C_NNP, C_NPP, C_NNN, C_NPN,
                nWest, multipleImages ? UV_SIDE_MULTI : UV_SIDE_SINGLE, flip, cm);
    }

    private void createEast(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        Shape.emitTriangle(chunkMesh, location, blockScale, emitRotation,
                C_PNN, C_PPN, C_PNP,
                nEast, multipleImages ? UV_EAST_MULTI : UV_EAST_SINGLE, cm);
    }

    private void createSouth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        Shape.emitTriangle(chunkMesh, location, blockScale, emitRotation,
                C_NNP, C_PNP, C_NPP,
                nSouth, multipleImages ? UV_SOUTH_MULTI : UV_SOUTH_SINGLE, cm);
    }

    private void createSlopeSouth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        Shape.emitTriangle(chunkMesh, location, blockScale, emitRotation,
                C_NPN, C_PNP, C_PPN,
                nSlopeSouth, multipleImages ? UV_SLOPE_SOUTH_MULTI : UV_SLOPE_SOUTH_SINGLE, cm);
    }

    private void createSlopeEast(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        Shape.emitTriangle(chunkMesh, location, blockScale, emitRotation,
                C_NPN, C_NPP, C_PNP,
                nSlopeEast, multipleImages ? UV_SLOPE_EAST_MULTI : UV_SLOPE_EAST_SINGLE, cm);
    }

}

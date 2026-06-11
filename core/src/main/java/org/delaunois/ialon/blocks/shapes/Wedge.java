package org.delaunois.ialon.blocks.shapes;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkMesh;
import org.delaunois.ialon.blocks.Direction;
import org.delaunois.ialon.blocks.Shape;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.BlockNeighborhood;

import lombok.ToString;

/**
 * A shape implementation for a wedge. The default facing of a wedge is South: the sloping side (hypotenuse) will face
 * south.
 * <p>
 * The three quad faces (down, north, slope) are emitted through the shared {@link Shape#emitQuad} helper with
 * precomputed normals and corner vertices. The two triangular sides (east/west) keep their own emission but reuse
 * the precomputed normals.
 *
 * @author rvandoosselaer
 */
@ToString
public class Wedge implements Shape {

    private static final Quaternion PI_X = new Quaternion().fromAngleAxis(FastMath.PI, Vector3f.UNIT_X);
    private static final Quaternion PI_Y = new Quaternion().fromAngleAxis(FastMath.PI, Vector3f.UNIT_Y);
    private static final Quaternion INVERSE = PI_X.mult(PI_Y);

    // The 8 cube corners (named by the sign of x, y, z : n = -0.5, p = +0.5). Shared immutable constants ;
    // Shape.emitQuad never mutates the local vertices.
    private static final Vector3f C_NNN = new Vector3f(-0.5f, -0.5f, -0.5f);
    private static final Vector3f C_NNP = new Vector3f(-0.5f, -0.5f, 0.5f);
    private static final Vector3f C_NPN = new Vector3f(-0.5f, 0.5f, -0.5f);
    private static final Vector3f C_PNN = new Vector3f(0.5f, -0.5f, -0.5f);
    private static final Vector3f C_PNP = new Vector3f(0.5f, -0.5f, 0.5f);
    private static final Vector3f C_PPN = new Vector3f(0.5f, 0.5f, -0.5f);

    // The north face keeps a fixed (un-rotated) normal, exactly as the original implementation.
    private static final Vector3f NORMAL_NORTH = new Vector3f(0.0f, 0.0f, -1.0f);

    private static final Vector2f[] UV_DOWN_SINGLE = {
            new Vector2f(UV_PADDING, UV_PADDING), new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, UV_PADDING),
            new Vector2f(UV_PADDING, 1f / UV_PADDING_FACTOR + UV_PADDING), new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, 1f / UV_PADDING_FACTOR + UV_PADDING)
    };
    private static final Vector2f[] UV_DOWN_MULTI = {
            new Vector2f(UV_PADDING, UV_PADDING), new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, UV_PADDING),
            new Vector2f(UV_PADDING, 1f / 3f - UV_PADDING), new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f - UV_PADDING)
    };
    private static final Vector2f[] UV_NORTH_SINGLE = {
            new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, UV_PADDING), new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, 1f / UV_PADDING_FACTOR + UV_PADDING),
            new Vector2f(UV_PADDING, UV_PADDING), new Vector2f(UV_PADDING, 1f / UV_PADDING_FACTOR + UV_PADDING)
    };
    private static final Vector2f[] UV_NORTH_MULTI = {
            new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f - UV_PADDING), new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f + UV_PADDING),
            new Vector2f(UV_PADDING, 2f / 3f - UV_PADDING), new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING)
    };
    private static final Vector2f[] UV_SOUTH_SINGLE = {
            new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, UV_PADDING), new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, 1f / UV_PADDING_FACTOR + UV_PADDING),
            new Vector2f(UV_PADDING, UV_PADDING), new Vector2f(UV_PADDING, 1f / UV_PADDING_FACTOR + UV_PADDING)
    };
    private static final Vector2f[] UV_SOUTH_MULTI = {
            new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, 1f - UV_PADDING), new Vector2f(1f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f + UV_PADDING),
            new Vector2f(UV_PADDING, 1f - UV_PADDING), new Vector2f(UV_PADDING, 2f / 3f + UV_PADDING)
    };

    private final Direction direction;
    private final boolean upsideDown;
    private final Quaternion rotation;
    private final Quaternion emitRotation;

    private final Vector3f nDown;
    private final Vector3f nSouth;
    private final Vector3f nEast;
    private final Vector3f nWest;

    public Wedge() {
        this(Direction.UP, false);
    }

    public Wedge(Direction direction, boolean upsideDown) {
        this.direction = direction;
        this.upsideDown = upsideDown;

        // when the shape is upside down (inverted), we need to perform 3 rotations. Two to invert the shape and one
        // for the direction.
        Quaternion rot = Shape.getYawFromDirection(direction);
        if (upsideDown) {
            rot = INVERSE.mult(rot.inverse());
        }
        this.rotation = rot;
        this.emitRotation = Quaternion.IDENTITY.equals(rot) ? null : rot;

        this.nDown = rot.mult(new Vector3f(0.0f, -1.0f, 0.0f));
        this.nSouth = rot.mult(new Vector3f(0.0f, 0.70710677f, 0.70710677f));
        this.nEast = rot.mult(new Vector3f(1.0f, 0.0f, 0.0f));
        this.nWest = rot.mult(new Vector3f(-1.0f, 0.0f, 0.0f));
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

        Direction faceDirection;
        boolean flip;
        boolean enlight = !chunkMesh.isCollisionMesh();
        boolean multipleImages = block.isUsingMultipleImages();

        Vec3i location = neighborhood.getLocation();

        // SOUTH
        faceDirection = Shape.getYawFaceDirection(Direction.SOUTH, direction);
        flip = enlight && softShadowSouthFace(neighborhood, location, faceDirection, chunk, chunkMesh);
        createSouth(location, chunkMesh, blockScale, multipleImages, flip);

        // WEST
        faceDirection = Shape.getYawFaceDirection(upsideDown ? Direction.EAST : Direction.WEST, direction);
        if (chunk.isFaceVisible(location, faceDirection)) {
            softShadowWestFace(neighborhood, location, faceDirection, chunk, chunkMesh);
            createWest(location, chunkMesh, blockScale, multipleImages);
        }

        // EAST
        faceDirection = Shape.getYawFaceDirection(upsideDown ? Direction.WEST : Direction.EAST, direction);
        if (chunk.isFaceVisible(location, faceDirection)) {
            softShadowEastFace(neighborhood, location, faceDirection, chunk, chunkMesh);
            createEast(location, chunkMesh, blockScale, multipleImages);
        }

        // NORTH
        faceDirection = Shape.getYawFaceDirection(Direction.NORTH, direction);
        if (chunk.isFaceVisible(location, faceDirection)) {
            flip = enlight && softShadow(neighborhood, location, faceDirection, chunk, chunkMesh);
            createNorth(location, chunkMesh, blockScale, multipleImages, flip);
        }

        // DOWN
        faceDirection = Shape.getYawFaceDirection(upsideDown ? Direction.UP : Direction.DOWN, direction);
        if (chunk.isFaceVisible(location, faceDirection)) {
            flip = enlight && softShadow(neighborhood, location, faceDirection, chunk, chunkMesh);
            createDown(location, chunkMesh, blockScale, multipleImages, flip);
        }
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        add(new BlockNeighborhood(location, chunk), chunkMesh);
    }

    public boolean fullyCoversFace(Direction direction) {
        Direction oppositeYawFaceDirection = Shape.getOppositeYawFaceDirection(direction, this.direction);
        return oppositeYawFaceDirection == (upsideDown ? Direction.UP : Direction.DOWN)
                || oppositeYawFaceDirection == Direction.NORTH;
    }

    private boolean softShadow(BlockNeighborhood n, Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);
        Vector4f[] nb = n.getNeighbourLights(this.direction, face, null, upsideDown);

        // a01  a11
        // a00  a10
        Vector4f store = new Vector4f();
        float a11 = chunk.vertexColor(nb[7], nb[1], nb[0], color, store).w;
        chunkMesh.getColors().add(store);

        float a01 = chunk.vertexColor(nb[1], nb[3], nb[2], color, store).w;
        chunkMesh.getColors().add(store);

        float a10 = chunk.vertexColor(nb[5], nb[7], nb[6], color, store).w;
        chunkMesh.getColors().add(store);

        float a00 = chunk.vertexColor(nb[3], nb[5], nb[4], color, store).w;
        chunkMesh.getColors().add(store);

        float grad1 = Math.abs(a00 - a11);
        float grad2 = Math.abs(a01 - a10);
        return grad1 < grad2;
    }

    private boolean softShadowEastFace(BlockNeighborhood n, Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);
        Vector4f[] nb = n.getNeighbourLights(this.direction, face, null, upsideDown);
        Vector4f store = new Vector4f();
        chunk.vertexColor(nb[7], nb[1], nb[0], color, store);
        chunkMesh.getColors().add(store);
        chunk.vertexColor(nb[1], nb[3], nb[2], color, store);
        chunkMesh.getColors().add(store);
        chunk.vertexColor(nb[5], nb[7], nb[6], color, store);
        chunkMesh.getColors().add(store);
        return false;
    }

    private boolean softShadowWestFace(BlockNeighborhood n, Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);
        Vector4f[] nb = n.getNeighbourLights(this.direction, face, null, upsideDown);
        Vector4f store = new Vector4f();
        chunk.vertexColor(nb[7], nb[1], nb[0], color, store);
        chunkMesh.getColors().add(store);
        chunk.vertexColor(nb[3], nb[5], nb[4], color, store);
        chunkMesh.getColors().add(store);
        chunk.vertexColor(nb[5], nb[7], nb[6], color, store);
        chunkMesh.getColors().add(store);
        return false;
    }

    private boolean softShadowSouthFace(BlockNeighborhood n, Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, null);
        Vector4f[] nb = n.getNeighbourWedgeLights(face, null, upsideDown);

        Vector4f store = new Vector4f();
        float a10 = chunk.vertexColor(nb[5], nb[7], nb[6], color, store).w;
        chunkMesh.getColors().add(store);
        float a11 = chunk.vertexColor(nb[7], nb[1], nb[0], color, store).w;
        chunkMesh.getColors().add(store);
        float a00 = chunk.vertexColor(nb[3], nb[5], nb[4], color, store).w;
        chunkMesh.getColors().add(store);
        float a01 = chunk.vertexColor(nb[1], nb[3], nb[2], color, store).w;
        chunkMesh.getColors().add(store);

        float grad1 = Math.abs(a01 - a10);
        float grad2 = Math.abs(a11 - a00);
        return grad1 < grad2;
    }

    private void createDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean flip) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                C_NNN, C_PNN, C_NNP, C_PNP,
                nDown, multipleImages ? UV_DOWN_MULTI : UV_DOWN_SINGLE, flip, chunkMesh.isCollisionMesh());
    }

    private void createNorth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean flip) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                C_NNN, C_NPN, C_PNN, C_PPN,
                NORMAL_NORTH, multipleImages ? UV_NORTH_MULTI : UV_NORTH_SINGLE, flip, chunkMesh.isCollisionMesh());
    }

    private void createSouth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean flip) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                C_PNP, C_PPN, C_NNP, C_NPN,
                nSouth, multipleImages ? UV_SOUTH_MULTI : UV_SOUTH_SINGLE, flip, chunkMesh.isCollisionMesh());
    }

    // East/West are triangular faces : emitQuad only handles quads, so they keep their own emission, but the
    // rotated normal is now computed once (precomputed) instead of per-vertex in a loop.
    private void createEast(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        int offset = chunkMesh.getPositions().size();
        Vector3f scratch = VERTEX_SCRATCH.get();
        Shape.emitVertex(chunkMesh, scratch, emitRotation, C_PNN, location, blockScale);
        Shape.emitVertex(chunkMesh, scratch, emitRotation, C_PPN, location, blockScale);
        Shape.emitVertex(chunkMesh, scratch, emitRotation, C_PNP, location, blockScale);
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private void createWest(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        int offset = chunkMesh.getPositions().size();
        Vector3f scratch = VERTEX_SCRATCH.get();
        Shape.emitVertex(chunkMesh, scratch, emitRotation, C_NNP, location, blockScale);
        Shape.emitVertex(chunkMesh, scratch, emitRotation, C_NPN, location, blockScale);
        Shape.emitVertex(chunkMesh, scratch, emitRotation, C_NNN, location, blockScale);
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

}

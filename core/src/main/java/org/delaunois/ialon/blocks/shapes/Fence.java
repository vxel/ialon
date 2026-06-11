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
import org.delaunois.ialon.blocks.ShapeIds;
import com.simsilica.mathd.Vec3i;

import lombok.Getter;
import lombok.ToString;

/**
 * A shape implementation for a fence. The default direction of a fence is UP. A direction of NORTH/EAST/SOUTH/WEST will
 * create a horizontal fence, with the top face facing the direction. The Direction UP/DOWN will create a vertical fence.
 * The depth/width of the fence can be configured with the widthExtend.
 * <p>
 * The central post is fixed (it only depends on the final direction and width), so its normals, corner vertices
 * and UVs are precomputed in the constructor and emitted through the shared {@link Shape#emitQuad} helper. The
 * connection rails are sized dynamically from the neighbouring blocks, so their vertices/UVs are built per call,
 * but they still reuse the precomputed normals and the shared emission path.
 *
 * @author Cedric de Launois
 */
@ToString
public class Fence implements Shape {

    private static final float RAIL = 0.08f;

    @Getter
    private final Direction direction;
    @Getter
    private final float widthExtend;

    private final Quaternion emitRotation;

    private final Vector3f nUp;
    private final Vector3f nDown;
    private final Vector3f nNorth;
    private final Vector3f nSouth;
    private final Vector3f nEast;
    private final Vector3f nWest;

    // Central-post corner vertices : b* on the bottom plane (y=-0.5), t* on the top plane (y=0.5), named by
    // the sign of (x, z) : m = -widthExtend, p = +widthExtend.
    private final Vector3f bMM;
    private final Vector3f bMP;
    private final Vector3f bPM;
    private final Vector3f bPP;
    private final Vector3f tMM;
    private final Vector3f tMP;
    private final Vector3f tPM;
    private final Vector3f tPP;

    private final Vector2f[] uvSideSingle;
    private final Vector2f[] uvSideMulti;
    private final Vector2f[] uvDownSingle;
    private final Vector2f[] uvDownMulti;
    private final Vector2f[] uvUpSingle;
    private final Vector2f[] uvUpMulti;

    public Fence() {
        this(Direction.UP, 0.15f);
    }

    public Fence(Direction direction, float widthExtend) {
        this.direction = direction;
        this.widthExtend = FastMath.clamp(Math.abs(widthExtend), 0, 0.5f);

        Quaternion rotation = Shape.getRotationFromDirection(direction);
        this.emitRotation = direction == Direction.UP ? null : rotation;

        this.nUp = rotation.mult(new Vector3f(0f, 1f, 0f));
        this.nDown = rotation.mult(new Vector3f(0f, -1f, 0f));
        this.nNorth = rotation.mult(new Vector3f(0f, 0f, -1f));
        this.nSouth = rotation.mult(new Vector3f(0f, 0f, 1f));
        this.nEast = rotation.mult(new Vector3f(1f, 0f, 0f));
        this.nWest = rotation.mult(new Vector3f(-1f, 0f, 0f));

        float te = this.widthExtend;
        this.bMM = new Vector3f(-te, -0.5f, -te);
        this.bMP = new Vector3f(-te, -0.5f, te);
        this.bPM = new Vector3f(te, -0.5f, -te);
        this.bPP = new Vector3f(te, -0.5f, te);
        this.tMM = new Vector3f(-te, 0.5f, -te);
        this.tMP = new Vector3f(-te, 0.5f, te);
        this.tPM = new Vector3f(te, 0.5f, -te);
        this.tPP = new Vector3f(te, 0.5f, te);

        float f = UV_PADDING_FACTOR;
        this.uvSideSingle = new Vector2f[]{
                new Vector2f((0.5f + te) / f + UV_PADDING, UV_PADDING), new Vector2f((0.5f + te) / f + UV_PADDING, 1f - UV_PADDING),
                new Vector2f((0.5f - te) / f + UV_PADDING, UV_PADDING), new Vector2f((0.5f - te) / f + UV_PADDING, 1f - UV_PADDING)
        };
        this.uvSideMulti = new Vector2f[]{
                new Vector2f(0.5f + te - UV_PADDING, 1f / 3f + UV_PADDING), new Vector2f(0.5f + te - UV_PADDING, 2f / 3f - UV_PADDING),
                new Vector2f(0.5f - te + UV_PADDING, 1f / 3f + UV_PADDING), new Vector2f(0.5f - te + UV_PADDING, 2f / 3f - UV_PADDING)
        };
        this.uvDownSingle = new Vector2f[]{
                new Vector2f(0.5f - te / f, 0.5f - te / f), new Vector2f(0.5f + te / f, 0.5f - te / f),
                new Vector2f(0.5f - te / f, 0.5f + te / f), new Vector2f(0.5f + te / f, 0.5f + te / f)
        };
        this.uvDownMulti = new Vector2f[]{
                new Vector2f(0.5f - te / f, 1f / 6f - te / 3f / f), new Vector2f(0.5f + te / f, 1f / 6f - te / 3f / f),
                new Vector2f(0.5f - te / f, 1f / 6f + te / 3f / f), new Vector2f(0.5f + te / f, 1f / 6f + te / 3f / f)
        };
        this.uvUpSingle = new Vector2f[]{
                new Vector2f(0.5f + te / f, 0.5f + te / f), new Vector2f(0.5f - te / f, 0.5f + te / f),
                new Vector2f(0.5f + te / f, 0.5f - te / f), new Vector2f(0.5f - te / f, 0.5f - te / f)
        };
        this.uvUpMulti = new Vector2f[]{
                new Vector2f(0.5f + te / f, 5f / 6f + te / 3f / f), new Vector2f(0.5f - te / f, 5f / 6f + te / 3f / f),
                new Vector2f(0.5f + te / f, 5f / 6f - te / 3f / f), new Vector2f(0.5f - te / f, 5f / 6f - te / 3f / f)
        };
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have 3 textures or only one
        boolean multipleImages = chunk.getBlock(location.x, location.y, location.z).isUsingMultipleImages();
        boolean cm = chunkMesh.isCollisionMesh();

        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.UP, direction))) {
            createUp(location, chunkMesh, blockScale, multipleImages, cm);
            enlightFace(location, Shape.getFaceDirection(Direction.UP, direction), chunk, chunkMesh);
        }

        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.DOWN, direction))) {
            createDown(location, chunkMesh, blockScale, multipleImages, cm);
            enlightFace(location, Shape.getFaceDirection(Direction.DOWN, direction), chunk, chunkMesh);
        }

        createWest(location, chunkMesh, blockScale, multipleImages, cm);
        enlightFace(location, null, chunk, chunkMesh);

        createEast(location, chunkMesh, blockScale, multipleImages, cm);
        enlightFace(location, null, chunk, chunkMesh);

        createSouth(location, chunkMesh, blockScale, multipleImages, cm);
        enlightFace(location, null, chunk, chunkMesh);

        createNorth(location, chunkMesh, blockScale, multipleImages, cm);
        enlightFace(location, null, chunk, chunkMesh);

        float x1 = widthExtend;
        float z1 = widthExtend;
        float x2 = -widthExtend;
        float z2 = -widthExtend;
        if (needFence(location, Shape.getFaceDirection(Direction.NORTH, direction), chunk)) {
            z1 = -0.5f;
        }
        if (needFence(location, Shape.getFaceDirection(Direction.SOUTH, direction), chunk)) {
            z2 = 0.5f;
        }
        if (needFence(location, Shape.getFaceDirection(Direction.WEST, direction), chunk)) {
            x1 = -0.5f;
        }
        if (needFence(location, Shape.getFaceDirection(Direction.EAST, direction), chunk)) {
            x2 = 0.5f;
        }
        if (x1 < x2) {
            createFenceEWUp(location, chunkMesh, blockScale, multipleImages, cm, RAIL, x1, x2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceEWNorth(location, chunkMesh, blockScale, multipleImages, cm, RAIL, x1, x2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceEWSouth(location, chunkMesh, blockScale, multipleImages, cm, RAIL, x1, x2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceEWDown(location, chunkMesh, blockScale, multipleImages, cm, RAIL, x1, x2);
            enlightFace(location, null, chunk, chunkMesh);
        }
        if (z1 < z2) {
            createFenceNSUp(location, chunkMesh, blockScale, multipleImages, cm, RAIL, z1, z2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceNSEast(location, chunkMesh, blockScale, multipleImages, cm, RAIL, z1, z2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceNSWest(location, chunkMesh, blockScale, multipleImages, cm, RAIL, z1, z2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceNSDown(location, chunkMesh, blockScale, multipleImages, cm, RAIL, z1, z2);
            enlightFace(location, null, chunk, chunkMesh);
        }
    }

    private void enlightFace(Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
    }

    private boolean needFence(Vec3i location, Direction face, Chunk chunk) {
        Block block = chunk.getNeighbour(location, Shape.getFaceDirection(face, direction));
        return block != null && (ShapeIds.CUBE.equals(block.getShape()) || block.getShape().startsWith(ShapeIds.FENCE));
    }

    // --- Central post (constant geometry) -------------------------------------------------------------

    private void createNorth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bMM, tMM, bPM, tPM,
                nNorth, multipleImages ? uvSideMulti : uvSideSingle, false, cm);
    }

    private void createSouth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bPP, tPP, bMP, tMP,
                nSouth, multipleImages ? uvSideMulti : uvSideSingle, false, cm);
    }

    private void createEast(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bPM, tPM, bPP, tPP,
                nEast, multipleImages ? uvSideMulti : uvSideSingle, false, cm);
    }

    private void createWest(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bMP, tMP, bMM, tMM,
                nWest, multipleImages ? uvSideMulti : uvSideSingle, false, cm);
    }

    private void createDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bMM, bPM, bMP, bPP,
                nDown, multipleImages ? uvDownMulti : uvDownSingle, false, cm);
    }

    private void createUp(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                tPM, tMM, tPP, tMP,
                nUp, multipleImages ? uvUpMulti : uvUpSingle, false, cm);
    }

    // --- Connection rails (dynamic geometry from neighbours) ------------------------------------------

    private void createFenceEWNorth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages,
                                    boolean cm, float te, float x1, float x2) {
        float f = UV_PADDING_FACTOR;
        Vector2f[] uvs = multipleImages ? new Vector2f[]{
                new Vector2f((0.5f + x1) / f + UV_PADDING, 1f / 3f + (0.2f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + x1) / f + UV_PADDING, 1f / 3f + (0.4f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + x2) / f + UV_PADDING, 1f / 3f + (0.2f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + x2) / f + UV_PADDING, 1f / 3f + (0.4f + 0.5f + UV_PADDING) / f / 3f)
        } : new Vector2f[]{
                new Vector2f((0.5f + x1) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + x1) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + x2) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + x2) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING)
        };
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                new Vector3f(x1, 0.2f, -te), new Vector3f(x1, 0.4f, -te),
                new Vector3f(x2, 0.2f, -te), new Vector3f(x2, 0.4f, -te),
                nNorth, uvs, false, cm);
    }

    private void createFenceEWSouth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages,
                                    boolean cm, float te, float x1, float x2) {
        float f = UV_PADDING_FACTOR;
        Vector2f[] uvs = multipleImages ? new Vector2f[]{
                new Vector2f((0.5f + x2) / f + UV_PADDING, 1f / 3f + (0.2f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + x2) / f + UV_PADDING, 1f / 3f + (0.4f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + x1) / f + UV_PADDING, 1f / 3f + (0.2f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + x1) / f + UV_PADDING, 1f / 3f + (0.4f + 0.5f + UV_PADDING) / f / 3f)
        } : new Vector2f[]{
                new Vector2f((0.5f + x2) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + x2) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + x1) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + x1) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING)
        };
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                new Vector3f(x2, 0.2f, te), new Vector3f(x2, 0.4f, te),
                new Vector3f(x1, 0.2f, te), new Vector3f(x1, 0.4f, te),
                nSouth, uvs, false, cm);
    }

    private void createFenceNSEast(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages,
                                   boolean cm, float te, float z1, float z2) {
        float f = UV_PADDING_FACTOR;
        Vector2f[] uvs = multipleImages ? new Vector2f[]{
                new Vector2f((0.5f + z1) / f + UV_PADDING, 1f / 3f + (0.2f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + z1) / f + UV_PADDING, 1f / 3f + (0.4f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + z2) / f + UV_PADDING, 1f / 3f + (0.2f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + z2) / f + UV_PADDING, 1f / 3f + (0.4f + 0.5f + UV_PADDING) / f / 3f)
        } : new Vector2f[]{
                new Vector2f((0.5f + z1) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z1) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z2) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z2) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING)
        };
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                new Vector3f(te, 0.2f, z1), new Vector3f(te, 0.4f, z1),
                new Vector3f(te, 0.2f, z2), new Vector3f(te, 0.4f, z2),
                nEast, uvs, false, cm);
    }

    private void createFenceNSWest(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages,
                                   boolean cm, float te, float z1, float z2) {
        float f = UV_PADDING_FACTOR;
        Vector2f[] uvs = multipleImages ? new Vector2f[]{
                new Vector2f(0.5f + te - UV_PADDING, 2f / 3f - UV_PADDING),
                new Vector2f(0.5f - te + UV_PADDING, 2f / 3f - UV_PADDING),
                new Vector2f(0.5f + te - UV_PADDING, 1f / 3f + UV_PADDING),
                new Vector2f(0.5f - te + UV_PADDING, 1f / 3f + UV_PADDING)
        } : new Vector2f[]{
                new Vector2f((0.5f + z2) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z2) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z1) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z1) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING)
        };
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                new Vector3f(-te, 0.2f, z2), new Vector3f(-te, 0.4f, z2),
                new Vector3f(-te, 0.2f, z1), new Vector3f(-te, 0.4f, z1),
                nWest, uvs, false, cm);
    }

    private void createFenceNSDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages,
                                   boolean cm, float te, float z1, float z2) {
        float f = UV_PADDING_FACTOR;
        Vector2f[] uvs = multipleImages ? new Vector2f[]{
                new Vector2f((0.5f + z1) / f + UV_PADDING, 1f / 3f + (0.2f + 0.5f) / f / 3f),
                new Vector2f((0.5f + z1) / f + UV_PADDING, 1f / 3f + (0.4f + 0.5f) / f / 3f),
                new Vector2f((0.5f + z2) / f + UV_PADDING, 1f / 3f + (0.2f + 0.5f) / f / 3f),
                new Vector2f((0.5f + z2) / f + UV_PADDING, 1f / 3f + (0.4f + 0.5f) / f / 3f)
        } : new Vector2f[]{
                new Vector2f((0.5f + z1) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z1) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z2) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z2) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING)
        };
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                new Vector3f(-te, 0.2f, z1), new Vector3f(te, 0.2f, z1),
                new Vector3f(-te, 0.2f, z2), new Vector3f(te, 0.2f, z2),
                nDown, uvs, false, cm);
    }

    private void createFenceEWDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages,
                                   boolean cm, float te, float x1, float x2) {
        float f = UV_PADDING_FACTOR;
        Vector2f[] uvs = multipleImages ? new Vector2f[]{
                new Vector2f((0.5f + x1) / f + UV_PADDING, 1f / 3f + (0.5f - te) / f / 3f),
                new Vector2f((0.5f + x2) / f + UV_PADDING, 1f / 3f + (0.5f - te) / f / 3f),
                new Vector2f((0.5f + x1) / f + UV_PADDING, 1f / 3f + (0.5f + te) / f / 3f),
                new Vector2f((0.5f + x2) / f + UV_PADDING, 1f / 3f + (0.5f + te) / f / 3f)
        } : new Vector2f[]{
                new Vector2f((0.5f + x1) / f + UV_PADDING, (0.5f - te) / f + UV_PADDING),
                new Vector2f((0.5f + x2) / f + UV_PADDING, (0.5f - te) / f + UV_PADDING),
                new Vector2f((0.5f + x1) / f + UV_PADDING, (0.5f + te) / f + UV_PADDING),
                new Vector2f((0.5f + x2) / f + UV_PADDING, (0.5f + te) / f + UV_PADDING)
        };
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                new Vector3f(x1, 0.2f, -te), new Vector3f(x2, 0.2f, -te),
                new Vector3f(x1, 0.2f, te), new Vector3f(x2, 0.2f, te),
                nDown, uvs, false, cm);
    }

    private void createFenceNSUp(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages,
                                 boolean cm, float te, float z1, float z2) {
        float f = UV_PADDING_FACTOR;
        Vector2f[] uvs = multipleImages ? new Vector2f[]{
                new Vector2f((0.5f + z1) / f + UV_PADDING, 1f / 3f + (0.2f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + z1) / f + UV_PADDING, 1f / 3f + (0.4f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + z2) / f + UV_PADDING, 1f / 3f + (0.2f + 0.5f + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + z2) / f + UV_PADDING, 1f / 3f + (0.4f + 0.5f + UV_PADDING) / f / 3f)
        } : new Vector2f[]{
                new Vector2f((0.5f + z1) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z1) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z2) / f + UV_PADDING, (0.2f + 0.5f) / f + UV_PADDING),
                new Vector2f((0.5f + z2) / f + UV_PADDING, (0.4f + 0.5f) / f + UV_PADDING)
        };
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                new Vector3f(te, 0.4f, z1), new Vector3f(-te, 0.4f, z1),
                new Vector3f(te, 0.4f, z2), new Vector3f(-te, 0.4f, z2),
                nUp, uvs, false, cm);
    }

    private void createFenceEWUp(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages,
                                 boolean cm, float te, float x1, float x2) {
        float f = UV_PADDING_FACTOR;
        Vector2f[] uvs = multipleImages ? new Vector2f[]{
                new Vector2f((0.5f + x1) / f + UV_PADDING, 1f / 3f + (0.5f - te + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + x2) / f + UV_PADDING, 1f / 3f + (0.5f - te + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + x1) / f + UV_PADDING, 1f / 3f + (0.5f + te + UV_PADDING) / f / 3f),
                new Vector2f((0.5f + x2) / f + UV_PADDING, 1f / 3f + (0.5f + te + UV_PADDING) / f / 3f)
        } : new Vector2f[]{
                new Vector2f((0.5f + x1) / f + UV_PADDING, (0.5f - te) / f + UV_PADDING),
                new Vector2f((0.5f + x2) / f + UV_PADDING, (0.5f - te) / f + UV_PADDING),
                new Vector2f((0.5f + x1) / f + UV_PADDING, (0.5f + te) / f + UV_PADDING),
                new Vector2f((0.5f + x2) / f + UV_PADDING, (0.5f + te) / f + UV_PADDING)
        };
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                new Vector3f(x1, 0.4f, te), new Vector3f(x2, 0.4f, te),
                new Vector3f(x1, 0.4f, -te), new Vector3f(x2, 0.4f, -te),
                nUp, uvs, false, cm);
    }


}

package org.delaunois.ialon.blocks.shapes;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkMesh;
import org.delaunois.ialon.blocks.Direction;
import org.delaunois.ialon.blocks.Shape;
import com.simsilica.mathd.Vec3i;

import lombok.Getter;
import lombok.ToString;

/**
 * A shape implementation for a pole. The default direction of a pole is UP. A direction of NORTH/EAST/SOUTH/WEST will
 * create a horizontal pole, with the top face facing the direction. The Direction UP/DOWN will create a vertical pole.
 * The depth/width of the pole can be configured with the widthExtend.
 * <p>
 * The pole geometry only depends on its (final) direction and width, so the rotated normals, the 8 corner
 * vertices and the UV coordinates are precomputed once in the constructor and emitted through the shared
 * {@link Shape#emitQuad} helper, avoiding per-vertex allocations in the meshing loop.
 *
 * @author rvandoosselaer
 */
@ToString
public class Pole implements Shape {

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

    // Corner vertices : b* on the bottom plane (y=-0.5), t* on the top plane (y=0.5), named by the sign
    // of (x, z) : m = -widthExtend, p = +widthExtend.
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

    public Pole() {
        this(Direction.UP, 0.15f);
    }

    public Pole(Direction direction, float widthExtend) {
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
                new Vector2f(0.5f + te - UV_PADDING, UV_PADDING), new Vector2f(0.5f + te - UV_PADDING, 1f - UV_PADDING),
                new Vector2f(0.5f - te + UV_PADDING, UV_PADDING), new Vector2f(0.5f - te + UV_PADDING, 1f - UV_PADDING)
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
        boolean collisionMesh = chunkMesh.isCollisionMesh();

        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.UP, direction))) {
            createUp(location, chunkMesh, blockScale, multipleImages, collisionMesh);
            enlightFace(location, Shape.getFaceDirection(Direction.UP, direction), chunk, chunkMesh);
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.DOWN, direction))) {
            createDown(location, chunkMesh, blockScale, multipleImages, collisionMesh);
            enlightFace(location, Shape.getFaceDirection(Direction.DOWN, direction), chunk, chunkMesh);
        }
        createWest(location, chunkMesh, blockScale, multipleImages, collisionMesh);
        enlightFace(location, null, chunk, chunkMesh);

        createEast(location, chunkMesh, blockScale, multipleImages, collisionMesh);
        enlightFace(location, null, chunk, chunkMesh);

        createSouth(location, chunkMesh, blockScale, multipleImages, collisionMesh);
        enlightFace(location, null, chunk, chunkMesh);

        createNorth(location, chunkMesh, blockScale, multipleImages, collisionMesh);
        enlightFace(location, null, chunk, chunkMesh);
    }

    private void enlightFace(Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
    }

    private void createNorth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean collisionMesh) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bMM, tMM, bPM, tPM,
                nNorth, multipleImages ? uvSideMulti : uvSideSingle, false, collisionMesh);
    }

    private void createSouth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean collisionMesh) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bPP, tPP, bMP, tMP,
                nSouth, multipleImages ? uvSideMulti : uvSideSingle, false, collisionMesh);
    }

    private void createEast(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean collisionMesh) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bPM, tPM, bPP, tPP,
                nEast, multipleImages ? uvSideMulti : uvSideSingle, false, collisionMesh);
    }

    private void createWest(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean collisionMesh) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bMP, tMP, bMM, tMM,
                nWest, multipleImages ? uvSideMulti : uvSideSingle, false, collisionMesh);
    }

    private void createDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean collisionMesh) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bMM, bPM, bMP, bPP,
                nDown, multipleImages ? uvDownMulti : uvDownSingle, false, collisionMesh);
    }

    private void createUp(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean collisionMesh) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                tPM, tMM, tPP, tMP,
                nUp, multipleImages ? uvUpMulti : uvUpSingle, false, collisionMesh);
    }


}

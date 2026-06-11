package org.delaunois.ialon.blocks.shapes;

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

import lombok.ToString;

import static org.delaunois.ialon.blocks.Direction.UP;

/**
 * A shape implementation for a small floating cube. Only 4 vertices are used per face, 2 vertices are shared.
 * The default cube has a Direction.UP.
 * <p>
 * The face normals and UVs only depend on the (final) direction, so they are precomputed once in the constructor
 * and the per-face geometry is emitted through the shared {@link Shape#emitQuad} helper. The corner vertices are
 * built once per {@code add()} (they depend on the shared, mutable {@code half_width}).
 *
 * @author rvandoosselaer
 */
@ToString
public class FloatingCube implements Shape {

    private static final Vector2f[] UV_SIDE_SINGLE = {
            new Vector2f(1f, 0f), new Vector2f(1f, 1f), new Vector2f(0f, 0f), new Vector2f(0f, 1f)
    };
    private static final Vector2f[] UV_SIDE_MULTI = {
            new Vector2f(1f, 1f / 3f), new Vector2f(1f, 2f / 3f), new Vector2f(0f, 1f / 3f), new Vector2f(0f, 2f / 3f)
    };
    private static final Vector2f[] UV_DOWN_SINGLE = {
            new Vector2f(0f, 0f), new Vector2f(1f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f)
    };
    private static final Vector2f[] UV_DOWN_MULTI = {
            new Vector2f(0f, 0f), new Vector2f(1f, 0f), new Vector2f(0f, 1f / 3f), new Vector2f(1f, 1f / 3f)
    };
    private static final Vector2f[] UV_UP_SINGLE = {
            new Vector2f(1f, 1f), new Vector2f(0f, 1f), new Vector2f(1f, 0f), new Vector2f(0f, 0f)
    };
    private static final Vector2f[] UV_UP_MULTI = {
            new Vector2f(1f, 1f), new Vector2f(0f, 1f), new Vector2f(1f, 2f / 3f), new Vector2f(0f, 2f / 3f)
    };

    private final Direction direction;
    private static float half_width = 0.15f;

    private final Quaternion emitRotation;
    private final Vector3f nUp;
    private final Vector3f nDown;
    private final Vector3f nNorth;
    private final Vector3f nSouth;
    private final Vector3f nEast;
    private final Vector3f nWest;

    public FloatingCube() {
        this(UP);
    }

    public FloatingCube(float width) {
        this(UP);
        half_width = width / 2f;
    }

    public FloatingCube(Direction direction) {
        this.direction = direction;
        Quaternion rotation = Shape.getRotationFromDirection(direction);
        this.emitRotation = direction == UP ? null : rotation;
        this.nUp = rotation.mult(new Vector3f(0f, 1f, 0f));
        this.nDown = rotation.mult(new Vector3f(0f, -1f, 0f));
        this.nNorth = rotation.mult(new Vector3f(0f, 0f, -1f));
        this.nSouth = rotation.mult(new Vector3f(0f, 0f, 1f));
        this.nEast = rotation.mult(new Vector3f(1f, 0f, 0f));
        this.nWest = rotation.mult(new Vector3f(-1f, 0f, 0f));
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have 3 textures or only one
        Block block = chunk.getBlock(location.x, location.y, location.z);
        if (block == null)
            return;

        boolean multipleImages = block.isUsingMultipleImages();
        boolean cm = chunkMesh.isCollisionMesh();

        // The 8 corners (named by the sign of x, y, z : m = -half_width, p = +half_width), built once per
        // call : half_width is a shared mutable static, so we read it here rather than precomputing.
        float hw = half_width;
        Vector3f mmm = new Vector3f(-hw, -hw, -hw);
        Vector3f mmp = new Vector3f(-hw, -hw, hw);
        Vector3f mpm = new Vector3f(-hw, hw, -hw);
        Vector3f mpp = new Vector3f(-hw, hw, hw);
        Vector3f pmm = new Vector3f(hw, -hw, -hw);
        Vector3f pmp = new Vector3f(hw, -hw, hw);
        Vector3f ppm = new Vector3f(hw, hw, -hw);
        Vector3f ppp = new Vector3f(hw, hw, hw);

        // UP
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation, ppm, mpm, ppp, mpp,
                nUp, multipleImages ? UV_UP_MULTI : UV_UP_SINGLE, false, cm);
        enlightFace(location, chunk, chunkMesh);
        // DOWN
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation, mmm, pmm, mmp, pmp,
                nDown, multipleImages ? UV_DOWN_MULTI : UV_DOWN_SINGLE, false, cm);
        enlightFace(location, chunk, chunkMesh);
        // WEST
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation, mmp, mpp, mmm, mpm,
                nWest, multipleImages ? UV_SIDE_MULTI : UV_SIDE_SINGLE, false, cm);
        enlightFace(location, chunk, chunkMesh);
        // EAST
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation, pmm, ppm, pmp, ppp,
                nEast, multipleImages ? UV_SIDE_MULTI : UV_SIDE_SINGLE, false, cm);
        enlightFace(location, chunk, chunkMesh);
        // SOUTH
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation, pmp, ppp, mmp, mpp,
                nSouth, multipleImages ? UV_SIDE_MULTI : UV_SIDE_SINGLE, false, cm);
        enlightFace(location, chunk, chunkMesh);
        // NORTH
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation, mmm, mpm, pmm, ppm,
                nNorth, multipleImages ? UV_SIDE_MULTI : UV_SIDE_SINGLE, false, cm);
        enlightFace(location, chunk, chunkMesh);
    }

    private void enlightFace(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
    }

}

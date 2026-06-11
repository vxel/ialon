package org.delaunois.ialon.blocks.shapes;

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

import lombok.ToString;

/**
 * A shape implementation for a square plane in a given direction. The default square plane has a direction UP.
 *
 * @author rvandoosselaer
 */
@ToString
public class Square implements Shape {

    // The single face : 4 fixed local vertices (never mutated by Shape.emitQuad), shared across instances.
    private static final Vector3f V0 = new Vector3f(0.5f, -0.48f, -0.5f);
    private static final Vector3f V1 = new Vector3f(-0.5f, -0.48f, -0.5f);
    private static final Vector3f V2 = new Vector3f(0.5f, -0.48f, 0.5f);
    private static final Vector3f V3 = new Vector3f(-0.5f, -0.48f, 0.5f);

    private static final Vector2f[] UV_NORTH_SOUTH = {
            new Vector2f(1f - UV_PADDING, 1f - UV_PADDING), new Vector2f(UV_PADDING, 1f - UV_PADDING),
            new Vector2f(1f - UV_PADDING, UV_PADDING), new Vector2f(UV_PADDING, UV_PADDING)
    };
    private static final Vector2f[] UV_OTHER = {
            new Vector2f(UV_PADDING, 1f - UV_PADDING), new Vector2f(UV_PADDING, UV_PADDING),
            new Vector2f(1f - UV_PADDING, 1f - UV_PADDING), new Vector2f(1f - UV_PADDING, UV_PADDING)
    };

    private final Direction direction;

    // emitRotation is null for the default UP plane (identity) so the per-vertex rotation is skipped.
    private final Quaternion emitRotation;
    private final Vector3f normal;
    private final Vector2f[] uvFace;

    public Square() {
        this(Direction.UP);
    }

    public Square(Direction direction) {
        this(direction, Shape.getRotationFromDirection(direction));
    }

    public Square(Quaternion yaw) {
        this(null, yaw);
    }

    private Square(Direction direction, Quaternion rotation) {
        this.direction = direction;
        this.emitRotation = direction == Direction.UP ? null : rotation;
        this.normal = rotation.mult(new Vector3f(0f, 1f, 0f));
        this.uvFace = (direction == Direction.NORTH || direction == Direction.SOUTH) ? UV_NORTH_SOUTH : UV_OTHER;
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                V0, V1, V2, V3,
                normal, uvFace, false, chunkMesh.isCollisionMesh());
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

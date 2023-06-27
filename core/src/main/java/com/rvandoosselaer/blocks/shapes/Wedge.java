package com.rvandoosselaer.blocks.shapes;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkMesh;
import com.rvandoosselaer.blocks.Direction;
import com.rvandoosselaer.blocks.Shape;
import com.simsilica.mathd.Vec3i;

import lombok.ToString;

/**
 * A shape implementation for a wedge. The default facing of a wedge is South: the sloping side (hypotenuse) will face
 * south.
 *
 * @author rvandoosselaer
 */
@ToString
public class Wedge implements Shape {

    private static final Quaternion PI_X = new Quaternion().fromAngleAxis(FastMath.PI, Vector3f.UNIT_X);
    private static final Quaternion PI_Y = new Quaternion().fromAngleAxis(FastMath.PI, Vector3f.UNIT_Y);
    private static final Quaternion INVERSE = PI_X.mult(PI_Y);

    private final Direction direction;
    private final boolean upsideDown;
    private Quaternion rotation;

    public Wedge() {
        this(Direction.UP, false);
    }

    public Wedge(Direction direction, boolean upsideDown) {
        this.direction = direction;
        this.upsideDown = upsideDown;

        // when the shape is upside down (inverted), we need to perform 3 rotations. Two to invert the shape and one
        // for the direction.
        rotation = Shape.getYawFromDirection(direction);
        if (upsideDown) {
            rotation = INVERSE.mult(rotation.inverse());
        }
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have 3 textures or only one
        boolean multipleImages = chunk.getBlock(location.x, location.y, location.z).isUsingMultipleImages();

        createSouth(location, chunkMesh, rotation, blockScale, multipleImages);
        enlightFace(location, null, chunk, chunkMesh, 4);

        if (chunk.isFaceVisible(location, Shape.getYawFaceDirection(upsideDown ? Direction.EAST : Direction.WEST, direction))) {
            createWest(location, chunkMesh, rotation, blockScale, multipleImages);
            enlightFace(location, Shape.getYawFaceDirection(upsideDown ? Direction.EAST : Direction.WEST, direction), chunk, chunkMesh, 3);
        }
        if (chunk.isFaceVisible(location, Shape.getYawFaceDirection(upsideDown ? Direction.WEST : Direction.EAST, direction))) {
            createEast(location, chunkMesh, rotation, blockScale, multipleImages);
            enlightFace(location, Shape.getYawFaceDirection(upsideDown ? Direction.WEST : Direction.EAST, direction), chunk, chunkMesh, 3);
        }
        if (chunk.isFaceVisible(location, Shape.getYawFaceDirection(Direction.NORTH, direction))) {
            createNorth(location, chunkMesh, rotation, blockScale, multipleImages);
            enlightFace(location, Shape.getYawFaceDirection(Direction.NORTH, direction), chunk, chunkMesh, 4);
        }
        if (chunk.isFaceVisible(location, Shape.getYawFaceDirection(upsideDown ? Direction.UP : Direction.DOWN, direction))) {
            createDown(location, chunkMesh, rotation, blockScale, multipleImages);
            enlightFace(location, Shape.getYawFaceDirection(upsideDown ? Direction.UP : Direction.DOWN, direction), chunk, chunkMesh, 4);
        }
    }

    public boolean fullyCoversFace(Direction direction) {
        Direction oppositeYawFaceDirection = Shape.getOppositeYawFaceDirection(direction, this.direction);
        return oppositeYawFaceDirection == (upsideDown ? Direction.UP : Direction.DOWN)
                || oppositeYawFaceDirection == Direction.NORTH;
    }

    private void enlightFace(Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh, int numVertices) {
        Vector4f color = chunk.getLightLevel(location, face);
        for (int i = 0; i < numVertices; i++) {
            chunkMesh.getColors().add(color);
        }
    }

    private static void createDown(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, -0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, -0.5f, 0.5f)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 1);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, -1.0f, 0.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            }
        }
    }

    private static void createNorth(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, 0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, -0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, 0.5f, -0.5f)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 1);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, 0.0f, -1.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f - UV_PADDING));
            }
        }
    }

    private static void createEast(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, -0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, 0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, -0.5f, 0.5f)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 3; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(1.0f, 0.0f, 0.0f)));
            }
            // uvs
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

    private static void createWest(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages) {
        int offset;
        // calculate index offset, we use this to connect the triangles
        offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, 0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, 0.5f)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 3; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(-1.0f, 0.0f, 0.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private static void createSouth(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, 0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, -0.5f, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, 0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, 0.5f)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 1);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, 0.70710677f, 0.70710677f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f + UV_PADDING));
            }
        }
    }

}

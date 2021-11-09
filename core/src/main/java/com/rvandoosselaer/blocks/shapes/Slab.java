package com.rvandoosselaer.blocks.shapes;

import com.jme3.math.FastMath;
import com.jme3.math.Matrix4f;
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

import java.util.List;

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * A shape implementation for a slab. A slab is actual a cube shape with a controllable y (height) value. If you specify
 * a starting y value of 0 and an end y value of 1, you have a unit cube shape.
 * Only 4 vertices are used per face, 2 vertices are shared.
 *
 * @author rvandoosselaer
 */
@Slf4j
@ToString
public class Slab implements Shape {

    protected final float startY;
    protected final float endY;
    protected final Direction direction;

    public Slab(float startY, float endY) {
        this(startY, endY, Direction.UP);
    }

    public Slab(float startY, float endY, Direction direction) {
        if (startY < 0 || startY > 1 || endY < 0 || endY > 1 || startY > endY) {
            endY = FastMath.clamp(endY, 0, 1);
            startY = Math.min(endY, FastMath.clamp(startY, 0, 1));
            log.warn("Invalid height values specified. Normalized values to: start y: {}, end y: {}.", startY, endY);
        }
        this.startY = startY - 0.5f;
        this.endY = endY - 0.5f;
        this.direction = direction;
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have 3 textures or only one
        boolean multipleImages = chunk.getBlock(location.x, location.y, location.z).isUsingMultipleImages();
        // get the rotation of the shape based on the direction
        Quaternion rotation = Shape.getRotationFromDirection(direction);

        if (endY < 1 || chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.UP, direction))) {
            createUp(location, rotation, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.UP, direction), chunk, chunkMesh);
        }
        if (startY > 0 || chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.DOWN, direction))) {
            createDown(location, rotation, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.DOWN, direction), chunk, chunkMesh);
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.WEST, direction))) {
            createWest(location, rotation, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.WEST, direction), chunk, chunkMesh);
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.EAST, direction))) {
            createEast(location, rotation, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.EAST, direction), chunk, chunkMesh);
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.SOUTH, direction))) {
            createSouth(location, rotation, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.SOUTH, direction), chunk, chunkMesh);
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.NORTH, direction))) {
            createNorth(location, rotation, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.NORTH, direction), chunk, chunkMesh);
        }
    }

    private void enlightFace(Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);
        List<Vector4f> colors = chunkMesh.getColors();
        colors.add(color);
        colors.add(color);
        colors.add(color);
        colors.add(color);
    }

    protected void createNorth(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, endY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, endY, -0.5f)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, 0.0f, -1.0f)));
                Matrix4f rotationMatrix = rotation.toRotationMatrix(new Matrix4f());
                chunkMesh.getTangents().add(rotationMatrix.mult(new Vector4f(-1.0f, 0.0f, 0.0f, 1.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING ));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
            }
        }
    }

    protected void createSouth(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, endY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, endY, 0.5f)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, 0.0f, 1.0f)));
                Matrix4f rotationMatrix = rotation.toRotationMatrix(new Matrix4f());
                chunkMesh.getTangents().add(rotationMatrix.mult(new Vector4f(1.0f, 0.0f, 0.0f, 1.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING ));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING ));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
            }
        }
    }

    protected void createEast(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, endY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, endY, 0.5f)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(1.0f, 0.0f, 0.0f)));
                Matrix4f rotationMatrix = rotation.toRotationMatrix(new Matrix4f());
                chunkMesh.getTangents().add(rotationMatrix.mult(new Vector4f(0.0f, 0.0f, -1.0f, 1.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
            }
        }
    }

    protected void createWest(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, endY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, endY, -0.5f)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(-1.0f, 0.0f, 0.0f)));
                Matrix4f rotationMatrix = rotation.toRotationMatrix(new Matrix4f());
                chunkMesh.getTangents().add(rotationMatrix.mult(new Vector4f(0.0f, 0.0f, 1.0f, 1.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
            }
        }
    }

    protected void createDown(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, 0.5f)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, -1.0f, 0.0f)));
                Matrix4f rotationMatrix = rotation.toRotationMatrix(new Matrix4f());
                chunkMesh.getTangents().add(rotationMatrix.mult(new Vector4f(1.0f, 0.0f, 0.0f, 1.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    protected void createUp(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, endY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, endY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, endY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, endY, 0.5f)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, 1.0f, 0.0f)));
                Matrix4f rotationMatrix = rotation.toRotationMatrix(new Matrix4f());
                chunkMesh.getTangents().add(rotationMatrix.mult(new Vector4f(1.0f, 0.0f, 0.0f, 1.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f + UV_PADDING));
            }
        }
    }

    /**
     * Calculate the value in a range to a value in another range.
     *
     * @param value            input value
     * @param range            input value range
     * @param destinationRange destination range
     * @return value in destination range
     */
    private static float mapValueToRange(float value, Vector2f range, Vector2f destinationRange) {
        return (value - range.x) * ((destinationRange.y - destinationRange.x) / (range.y - range.x)) + destinationRange.x;
    }

}

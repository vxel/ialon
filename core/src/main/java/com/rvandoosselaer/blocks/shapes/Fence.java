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

import lombok.Getter;
import lombok.ToString;

/**
 * A shape implementation for a fence. The default direction of a fence is UP. A direction of NORTH/EAST/SOUTH/WEST will
 * create a horizontal fence, with the top face facing the direction. The Direction UP/DOWN will create a vertical fence.
 * The depth/width of the fence can be configured with the widthExtend.
 *
 * @author Cedric de Launois
 */
@Getter
@ToString
public class Fence implements Shape {

    private final Direction direction;
    private final float widthExtend;

    public Fence() {
        this(Direction.UP, 0.15f);
    }

    public Fence(Direction direction, float widthExtend) {
        this.direction = direction;
        this.widthExtend = FastMath.clamp(Math.abs(widthExtend), 0, 0.5f);
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have 3 textures or only one
        boolean multipleImages = chunk.getBlock(location.x, location.y, location.z).isUsingMultipleImages();
        // get the rotation of the shape based on the direction
        Quaternion rotation = Shape.getRotationFromDirection(direction);

        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.UP, direction))) {
            createUp(location, rotation, chunkMesh, blockScale, multipleImages, widthExtend);
            enlightFace(location, Shape.getFaceDirection(Direction.UP, direction), chunk, chunkMesh);
        }

        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.DOWN, direction))) {
            createDown(location, rotation, chunkMesh, blockScale, multipleImages, widthExtend);
            enlightFace(location, Shape.getFaceDirection(Direction.DOWN, direction), chunk, chunkMesh);
        }

        createWest(location, rotation, chunkMesh, blockScale, multipleImages, widthExtend);
        enlightFace(location, null, chunk, chunkMesh);

        createEast(location, rotation, chunkMesh, blockScale, multipleImages, widthExtend);
        enlightFace(location, null, chunk, chunkMesh);

        createSouth(location, rotation, chunkMesh, blockScale, multipleImages, widthExtend);
        enlightFace(location, null, chunk, chunkMesh);

        createNorth(location, rotation, chunkMesh, blockScale, multipleImages, widthExtend);
        enlightFace(location, null, chunk, chunkMesh);

        float x1 = 0;
        float z1 = 0;
        float x2 = 0;
        float z2 = 0;
        if (chunk.getNeighbour(location, Shape.getFaceDirection(Direction.NORTH, direction)) != null) {
            z1 = -0.5f;
        }
        if (chunk.getNeighbour(location, Shape.getFaceDirection(Direction.SOUTH, direction)) != null) {
            z2 = 0.5f;
        }
        if (chunk.getNeighbour(location, Shape.getFaceDirection(Direction.WEST, direction)) != null) {
            x1 = -0.5f;
        }
        if (chunk.getNeighbour(location, Shape.getFaceDirection(Direction.EAST, direction)) != null) {
            x2 = 0.5f;
        }
        if (x1 != 0 || x2 != 0) {
            createFenceEWUp(location, rotation, chunkMesh, blockScale, multipleImages, 0.08f, x1, x2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceEWNorth(location, rotation, chunkMesh, blockScale, multipleImages, 0.08f, x1, x2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceEWSouth(location, rotation, chunkMesh, blockScale, multipleImages, 0.08f, x1, x2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceEWDown(location, rotation, chunkMesh, blockScale, multipleImages, 0.08f, x1, x2);
            enlightFace(location, null, chunk, chunkMesh);
        }
        if (z1 != 0 || z2 != 0) {
            createFenceNSUp(location, rotation, chunkMesh, blockScale, multipleImages, 0.08f, z1, z2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceNSEast(location, rotation, chunkMesh, blockScale, multipleImages, 0.08f, z1, z2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceNSWest(location, rotation, chunkMesh, blockScale, multipleImages, 0.08f, z1, z2);
            enlightFace(location, null, chunk, chunkMesh);
            createFenceNSDown(location, rotation, chunkMesh, blockScale, multipleImages, 0.08f, z1, z2);
            enlightFace(location, null, chunk, chunkMesh);
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

    private static void createNorth(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, float thicknessExtend) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, -0.5f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.5f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, -0.5f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.5f, thicknessExtend * -1f)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 2f / 3f - UV_PADDING));
            }
        }
    }

    private static void createFenceEWNorth(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh,
                                           float blockScale, boolean multipleImages, float thicknessExtend,
                                           float x1, float x2) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x1, 0.2f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x1, 0.4f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x2, 0.2f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x2, 0.4f, thicknessExtend * -1f)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private static void createSouth(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, float thicknessExtend) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, -0.5f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.5f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, -0.5f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.5f, thicknessExtend)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 2f / 3f - UV_PADDING));
            }
        }
    }

    private static void createFenceEWSouth(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh,
                                           float blockScale, boolean multipleImages, float thicknessExtend,
                                           float x1, float x2) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x2, 0.2f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x2, 0.4f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x1, 0.2f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x1, 0.4f, thicknessExtend)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private static void createEast(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, float thicknessExtend) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, -0.5f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.5f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, -0.5f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.5f, thicknessExtend)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 2f / 3f - UV_PADDING));
            }
        }
    }

    private static void createFenceNSEast(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh,
                                          float blockScale, boolean multipleImages, float thicknessExtend,
                                          float z1, float z2) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.2f, z1)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.4f, z1)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.2f, z2)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.4f, z2)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private static void createWest(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, float thicknessExtend) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, -0.5f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.5f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, -0.5f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.5f, thicknessExtend * -1f)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 2f / 3f - UV_PADDING));
            }
        }
    }

    private static void createFenceNSWest(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh,
                                          float blockScale, boolean multipleImages, float thicknessExtend,
                                          float z1, float z2) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.2f, z2)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.4f, z2)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.2f, z1)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.4f, z1)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private static void createDown(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, float thicknessExtend) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, -0.5f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, -0.5f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, -0.5f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, -0.5f, thicknessExtend)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 0.5f - thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 0.5f - thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 0.5f + thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 0.5f + thicknessExtend / UV_PADDING_FACTOR));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 1f / 6f - thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 1f / 6f - thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 1f / 6f + thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 1f / 6f + thicknessExtend / 3f / UV_PADDING_FACTOR));
            }
        }
    }

    private static void createFenceNSDown(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh,
                                          float blockScale, boolean multipleImages, float thicknessExtend,
                                          float z1, float z2) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.2f, z1)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.2f, z1)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.2f, z2)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.2f, z2)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 0.5f - thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 0.5f - thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 0.5f + thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 0.5f + thicknessExtend / UV_PADDING_FACTOR));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 1f / 6f - thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 1f / 6f - thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 1f / 6f + thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 1f / 6f + thicknessExtend / 3f / UV_PADDING_FACTOR));
            }
        }
    }

    private static void createFenceEWDown(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh,
                                          float blockScale, boolean multipleImages, float thicknessExtend,
                                          float x1, float x2) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x1, 0.2f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x2, 0.2f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x1, 0.2f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x2, 0.2f, thicknessExtend)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 0.5f - thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 0.5f - thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 0.5f + thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 0.5f + thicknessExtend / UV_PADDING_FACTOR));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 1f / 6f - thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 1f / 6f - thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 1f / 6f + thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 1f / 6f + thicknessExtend / 3f / UV_PADDING_FACTOR));
            }
        }
    }

    private static void createUp(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, float thicknessExtend) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.5f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.5f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.5f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.5f, thicknessExtend)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 0.5f + thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 0.5f + thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 0.5f - thicknessExtend / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 0.5f - thicknessExtend / UV_PADDING_FACTOR));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 5f / 6f + thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 5f / 6f + thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend / UV_PADDING_FACTOR, 5f / 6f - thicknessExtend / 3f / UV_PADDING_FACTOR));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend / UV_PADDING_FACTOR, 5f / 6f - thicknessExtend / 3f / UV_PADDING_FACTOR));
            }
        }
    }

    private static void createFenceNSUp(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh,
                                        float blockScale, boolean multipleImages, float thicknessExtend,
                                        float z1, float z2) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.4f, z1)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.4f, z1)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend, 0.4f, z2)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(thicknessExtend * -1f, 0.4f, z2)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private static void createFenceEWUp(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh,
                                        float blockScale, boolean multipleImages, float thicknessExtend,
                                        float x1, float x2) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x1, 0.4f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x2, 0.4f, thicknessExtend)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x1, 0.4f, thicknessExtend * -1f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(x2, 0.4f, thicknessExtend * -1f)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f + thicknessExtend - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f - thicknessExtend + UV_PADDING, 2f / 3f - UV_PADDING));
            }
        }
    }

    /**
     * Function that scales an input value in a given range to a given output range.
     */
    public static float map(float value, float startRangeIn, float endRangeIn, float startRangeOut, float endRangeOut) {
        return (value - startRangeIn) * (endRangeOut - startRangeOut) / (endRangeIn - startRangeIn) + startRangeOut;
    }

}

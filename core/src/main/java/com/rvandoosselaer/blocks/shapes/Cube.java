package com.rvandoosselaer.blocks.shapes;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkMesh;
import com.rvandoosselaer.blocks.Direction;
import com.rvandoosselaer.blocks.Shape;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.BlockNeighborhood;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.ToString;

import static com.rvandoosselaer.blocks.Direction.DOWN;
import static com.rvandoosselaer.blocks.Direction.EAST;
import static com.rvandoosselaer.blocks.Direction.NORTH;
import static com.rvandoosselaer.blocks.Direction.SOUTH;
import static com.rvandoosselaer.blocks.Direction.UP;
import static com.rvandoosselaer.blocks.Direction.WEST;

/**
 * A shape implementation for a cube. Only 4 vertices are used per face, 2 vertices are shared. A face is only added
 * to the resulting mesh if the face is visible. eg. When there is a block above this block, the top face will not be
 * added to the mesh.
 * The default cube has a Direction.UP.
 *
 * @author rvandoosselaer
 */
@ToString
@RequiredArgsConstructor
public class Cube implements Shape {

    @Override
    public void add(BlockNeighborhood neighborhood, ChunkMesh chunkMesh) {
        Chunk chunk = neighborhood.getChunk();
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have 3 textures or only one
        Block block = neighborhood.getCenterBlock();
        if (block == null)
            return;

        boolean flip;
        boolean enlight = !chunkMesh.isCollisionMesh();
        boolean multipleImages = block.isUsingMultipleImages();

        Vec3i location = neighborhood.getLocation();

        if (chunk.isFaceVisible(neighborhood, UP)) {
            flip = enlight && enlightFace(neighborhood, location, UP, chunk, chunkMesh);
            createUp(location, chunkMesh, blockScale, multipleImages, flip);
        }
        if (chunk.isFaceVisible(neighborhood, DOWN)) {
            flip = enlight && enlightFace(neighborhood, location, DOWN, chunk, chunkMesh);
            createDown(location, chunkMesh, blockScale, multipleImages, flip);
        }
        if (chunk.isFaceVisible(neighborhood, WEST)) {
            flip = enlight && enlightFace(neighborhood, location, WEST, chunk, chunkMesh);
            createWest(location, chunkMesh, blockScale, multipleImages, flip);
        }
        if (chunk.isFaceVisible(neighborhood, EAST)) {
            flip = enlight && enlightFace(neighborhood, location, EAST, chunk, chunkMesh);
            createEast(location, chunkMesh, blockScale, multipleImages, flip);
        }
        if (chunk.isFaceVisible(neighborhood, SOUTH)) {
            flip = enlight && enlightFace(neighborhood, location, SOUTH, chunk, chunkMesh);
            createSouth(location, chunkMesh, blockScale, multipleImages, flip);
        }
        if (chunk.isFaceVisible(neighborhood, NORTH)) {
            flip = enlight && enlightFace(neighborhood, location, NORTH, chunk, chunkMesh);
            createNorth(location, chunkMesh, blockScale, multipleImages, flip);
        }
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        add(new BlockNeighborhood(location, chunk), chunkMesh);
    }

    private boolean enlightFace(BlockNeighborhood n, Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevels(location, face);
        List<Vector4f> colors = chunkMesh.getColors();
        Block[] nb = n.getNeighbours(face);

        // Vertices sorted clockwise for flip test
        int a00 = chunk.vertexAO(nb[7], nb[1], nb[0]);
        int a01 = chunk.vertexAO(nb[1], nb[3], nb[2]);
        int a11 = chunk.vertexAO(nb[3], nb[5], nb[4]);
        int a10 = chunk.vertexAO(nb[5], nb[7], nb[6]);

        // Vertices sorted in the order of their position in the buffer
        colors.add(chunk.applyAO(color, a00));
        colors.add(chunk.applyAO(color, a01));
        colors.add(chunk.applyAO(color, a10));
        colors.add(chunk.applyAO(color, a11));

        return (a00 + a11 > a01 + a10);

    }

    private static void createNorth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean flip) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, -0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, 0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, -0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, 0.5f, -0.5f), location, blockScale));
        // indices
        if (flip) {
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset);

        } else {
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
        }

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 0.0f, -1.0f));
                chunkMesh.getTangents().add(new Vector4f(-1.0f, 0.0f, 0.0f, 1.0f));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private static void createSouth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean flip) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, -0.5f, 0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, 0.5f, 0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, -0.5f, 0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, 0.5f, 0.5f), location, blockScale));
        // indices
        if (flip) {
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset);

        } else {
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
        }

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 0.0f, 1.0f));
                chunkMesh.getTangents().add(new Vector4f(1.0f, 0.0f, 0.0f, 1.0f));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private static void createEast(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean flip) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, -0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, 0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, -0.5f, 0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, 0.5f, 0.5f), location, blockScale));
        // indices
        if (flip) {
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset);

        } else {
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
        }

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(1.0f, 0.0f, 0.0f));
                chunkMesh.getTangents().add(new Vector4f(0.0f, 0.0f, -1.0f, 1.0f));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private static void createWest(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean flip) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, -0.5f, 0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, 0.5f, 0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, -0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, 0.5f, -0.5f), location, blockScale));
        // indices
        if (flip) {
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset);

        } else {
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
        }

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(-1.0f, 0.0f, 0.0f));
                chunkMesh.getTangents().add(new Vector4f(0.0f, 0.0f, 1.0f, 1.0f));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private static void createDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean flip) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, -0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, -0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, -0.5f, 0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, -0.5f, 0.5f), location, blockScale));
        // indices
        if (flip) {
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset);

        } else {
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
        }

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, -1.0f, 0.0f));
                chunkMesh.getTangents().add(new Vector4f(1.0f, 0.0f, 0.0f, 1.0f));
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

    private static void createUp(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean flip) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, 0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, 0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, 0.5f, 0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, 0.5f, 0.5f), location, blockScale));
        // indices
        if (flip) {
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset);

        } else {
            chunkMesh.getIndices().add(offset);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 2);
            chunkMesh.getIndices().add(offset + 1);
            chunkMesh.getIndices().add(offset + 3);
            chunkMesh.getIndices().add(offset + 2);
        }

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 1.0f, 0.0f));
                chunkMesh.getTangents().add(new Vector4f(1.0f, 0.0f, 0.0f, 1.0f));
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

}

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
import com.rvandoosselaer.blocks.TypeIds;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.BlockNeighborhood;

import java.util.List;

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.Direction.DOWN;
import static com.rvandoosselaer.blocks.Direction.EAST;
import static com.rvandoosselaer.blocks.Direction.NORTH;
import static com.rvandoosselaer.blocks.Direction.SOUTH;
import static com.rvandoosselaer.blocks.Direction.UP;
import static com.rvandoosselaer.blocks.Direction.WEST;

/**
 * A shape implementation for a water cube cuboid.
 * A water cube has the following properties :
 * - it is like a cube if all its neighbours are water blocks
 * - it is like a slab ending at 9/10 height if there is no water blocks above
 * - if the neighbour of a face is a block but not a water block, then the face will be visible
 *   and its position will be shifted (by a small delta) towards the center of the block to
 *   prevent Z-fighting
 * A water block does not support multiple images nor rotation.
 * @author Cedric de Launois
 */
@Slf4j
@ToString
public class Water implements Shape {

    protected final float height;

    public Water(float height) {
        this.height = height;
    }

    @Override
    public void add(BlockNeighborhood neighborhood, ChunkMesh chunkMesh) {
        Chunk chunk = neighborhood.getChunk();
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();

        Block block = neighborhood.getCenterBlock();
        if (block == null)
            return;

        Vec3i location = neighborhood.getLocation();
        float[][] v = new float[][] {
                { -0.5f, -0.5f, -0.5f },
                {  0.5f, -0.5f, -0.5f },
                { -0.5f,  height - 0.5f, -0.5f },
                {  0.5f,  height - 0.5f, -0.5f },
                { -0.5f, -0.5f,  0.5f },
                {  0.5f, -0.5f,  0.5f },
                { -0.5f,  height - 0.5f,  0.5f },
                {  0.5f,  height - 0.5f,  0.5f }
        };

        if (isWaterFaceVisible(neighborhood, UP)) {
            v[2][1] -= 1.0f/10;
            v[3][1] -= 1.0f/10;
            v[6][1] -= 1.0f/10;
            v[7][1] -= 1.0f/10;
        }

        if (isWaterFaceVisible(neighborhood, UP)) {
            createUp(location,  chunkMesh, blockScale, v[3], v[2], v[7], v[6]);
            enlightFace(location, Direction.UP, chunk, chunkMesh);
        }
        if (isWaterFaceVisible(neighborhood, DOWN)) {
            createDown(location, chunkMesh, blockScale, v[0], v[1], v[4], v[5]);
            enlightFace(location, Direction.DOWN, chunk, chunkMesh);
        }
        if (isWaterFaceVisible(neighborhood, WEST)) {
            createWest(location, chunkMesh, blockScale, v[4], v[6], v[0], v[2]);
            enlightFace(location, WEST, chunk, chunkMesh);
        }
        if (isWaterFaceVisible(neighborhood, EAST)) {
            createEast(location, chunkMesh, blockScale, v[1], v[3], v[5], v[7]);
            enlightFace(location, EAST, chunk, chunkMesh);
        }
        if (isWaterFaceVisible(neighborhood, SOUTH)) {
            createSouth(location, chunkMesh, blockScale, v[5], v[7], v[4], v[6]);
            enlightFace(location, Direction.SOUTH, chunk, chunkMesh);
        }
        if (isWaterFaceVisible(neighborhood, NORTH)) {
            createNorth(location, chunkMesh, blockScale, v[0], v[2], v[1], v[3]);
            enlightFace(location, NORTH, chunk, chunkMesh);
        }
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        add(new BlockNeighborhood(location, chunk), chunkMesh);
    }

    private void enlightFace(Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = new Vector4f(15, 0, 0, 1);
        List<Vector4f> colors = chunkMesh.getColors();
        colors.add(color);
        colors.add(color);
        colors.add(color);
        colors.add(color);
    }

    protected void createNorth(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        addQuad(location, chunkMesh, blockScale, v0, v1, v2, v3);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 0.0f, -1.0f));
                chunkMesh.getTangents().add(new Vector4f(-1.0f, 0.0f, 0.0f, 1.0f));
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, height / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, height / UV_PADDING_FACTOR + UV_PADDING));
        }
    }

    protected void createSouth(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        addQuad(location, chunkMesh, blockScale, v0, v1, v2, v3);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 0.0f, 1.0f));
                chunkMesh.getTangents().add(new Vector4f(1.0f, 0.0f, 0.0f, 1.0f));
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, height / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, height / UV_PADDING_FACTOR + UV_PADDING));
        }
    }

    protected void createEast(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        addQuad(location, chunkMesh, blockScale, v0, v1, v2, v3);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(1.0f, 0.0f, 0.0f));
                chunkMesh.getTangents().add(new Vector4f(0.0f, 0.0f, -1.0f, 1.0f));
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, height / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, height / UV_PADDING_FACTOR + UV_PADDING));
        }
    }

    protected void createWest(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        addQuad(location, chunkMesh, blockScale, v0, v1, v2, v3);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(-1, 0.0f, 0.0f));
                chunkMesh.getTangents().add(new Vector4f(0.0f, 0.0f, 1.0f, 1.0f));
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, height / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, height / UV_PADDING_FACTOR + UV_PADDING));
        }
    }

    protected void createDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        addQuad(location, chunkMesh, blockScale, v0, v1, v2, v3);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, -1.0f, 0.0f));
                chunkMesh.getTangents().add(new Vector4f(1.0f, 0.0f, 0.0f, 1.0f));
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 0.0f + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 0.0f + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 1.0f - UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
        }
    }

    protected void createUp(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        addQuad(location, chunkMesh, blockScale, v0, v1, v2, v3);

        if (!chunkMesh.isCollisionMesh()) {
            // normals and tangents
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 1.0f, 0.0f));
                chunkMesh.getTangents().add(new Vector4f(1.0f, 0.0f, 0.0f, 1.0f));
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 1.0f - UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 0.0f + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 0.0f + UV_PADDING));
        }
    }

    private void addQuad(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();

        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(vec(v0), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(vec(v1), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(vec(v2), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(vec(v3), location, blockScale));

        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);
    }

    private Vector3f vec(float[] v) {
        return new Vector3f(v[0], v[1], v[2]);
    }


    private boolean isWaterFaceVisible(BlockNeighborhood neighborhood, Direction direction) {
        // A water face is always visible excepted if the neighbour is also water
        Block neighbour = neighborhood.getNeighbour(direction);
        if (neighbour == null) {
            return true;
        }
        if (neighbour.getType().equals(TypeIds.WATER)) {
            return false;
        }
        return true;
    }

}

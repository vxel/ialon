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
import com.rvandoosselaer.blocks.ShapeIds;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.BlockNeighborhood;

import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.Direction.DOWN;
import static com.rvandoosselaer.blocks.Direction.EAST;
import static com.rvandoosselaer.blocks.Direction.NORTH;
import static com.rvandoosselaer.blocks.Direction.SOUTH;
import static com.rvandoosselaer.blocks.Direction.UP;
import static com.rvandoosselaer.blocks.Direction.WEST;

/**
 * A shape implementation for a liquid cube cuboid.
 * A liquid cube has the following properties :
 * - it is like a cube if all its neighbours are liquid blocks
 * - it is like a slab ending at 9/10 height if there is no liquid blocks above
 * - if the neighbour of a face is a block but not a liquid block, then the face will be visible
 *   and its position will be shifted (by a small delta) towards the center of the block to
 *   prevent Z-fighting
 * A liquid block does not support multiple images nor rotation.
 * @author Cedric de Launois
 */
@Slf4j
@ToString
public class Liquid implements Shape {

    private static final float[] HEIGHTS = { 0.0f, 0.05f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f };
    public static final int LEVEL_MAX = HEIGHTS.length - 1;

    @Getter
    protected final int level;

    public Liquid(int level) {
        this.level = Math.min(LEVEL_MAX, level);
    }

    @Override
    public void add(BlockNeighborhood neighborhood, ChunkMesh chunkMesh) {
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();

        Block block = neighborhood.getCenterBlock();
        if (block == null)
            return;

        Vec3i location = neighborhood.getLocation();
        float[][] v = new float[][] {
                { -0.5f, -0.5f, -0.5f },
                {  0.5f, -0.5f, -0.5f },
                { -0.5f,  0.5f, -0.5f },
                {  0.5f,  0.5f, -0.5f },
                { -0.5f, -0.5f,  0.5f },
                {  0.5f, -0.5f,  0.5f },
                { -0.5f,  0.5f,  0.5f },
                {  0.5f,  0.5f,  0.5f }
        };

        v[2][1] = HEIGHTS[level] - 0.5f;
        v[3][1] = HEIGHTS[level] - 0.5f;
        v[6][1] = HEIGHTS[level] - 0.5f;
        v[7][1] = HEIGHTS[level] - 0.5f;

        if (level < LEVEL_MAX) {
            Block[] b = neighborhood.getNeighbours();
            v[3][1] = computeHeight(b[0], b[1], b[7]);
            v[2][1] = computeHeight(b[1], b[2], b[3]);
            v[6][1] = computeHeight(b[3], b[4], b[5]);
            v[7][1] = computeHeight(b[5], b[6], b[7]);
            createUp(location,  chunkMesh, blockScale, v[3], v[2], v[7], v[6]);
            enlightFace(neighborhood, location, UP, neighborhood.getChunk(), chunkMesh);
        } else if (neighborhood.getNeighbour(Direction.UP) == null) {
            createUp(location,  chunkMesh, blockScale, v[3], v[2], v[7], v[6]);
            enlightFace(neighborhood, location, UP, neighborhood.getChunk(), chunkMesh);
        }

        if (isLiquidFaceVisible(neighborhood, DOWN)) {
            createDown(location, chunkMesh, blockScale, v[0], v[1], v[4], v[5]);
            enlightFace(neighborhood, location, DOWN, neighborhood.getChunk(), chunkMesh);
        }
        if (isLiquidFaceVisible(neighborhood, WEST)) {
            createWest(location, chunkMesh, blockScale, v[4], v[6], v[0], v[2]);
            enlightFace(neighborhood, location, WEST, neighborhood.getChunk(), chunkMesh);
        }
        if (isLiquidFaceVisible(neighborhood, EAST)) {
            createEast(location, chunkMesh, blockScale, v[1], v[3], v[5], v[7]);
            enlightFace(neighborhood, location, EAST, neighborhood.getChunk(), chunkMesh);
        }
        if (isLiquidFaceVisible(neighborhood, SOUTH)) {
            createSouth(location, chunkMesh, blockScale, v[5], v[7], v[4], v[6]);
            enlightFace(neighborhood, location, SOUTH, neighborhood.getChunk(), chunkMesh);
        }
        if (isLiquidFaceVisible(neighborhood, NORTH)) {
            createNorth(location, chunkMesh, blockScale, v[0], v[2], v[1], v[3]);
            enlightFace(neighborhood, location, NORTH, neighborhood.getChunk(), chunkMesh);
        }
    }

    private float computeHeight(Block... blocks) {
        int maxLevel = level;

        for (Block b : blocks) {
            if (b != null) {
                if (maxLevel < b.getLiquidLevel()) {
                    maxLevel = b.getLiquidLevel();
                }
            }
        }

        return HEIGHTS[maxLevel] - 0.5f;
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        add(new BlockNeighborhood(location, chunk), chunkMesh);
    }

    private boolean enlightFace(BlockNeighborhood n, Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        return softShadow(n, location, face, chunk, chunkMesh);
    }

    private boolean softShadow(BlockNeighborhood n, Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);

        //  Bottom     Middle      Top            Y ---> X
        // 00 01 02   09 10 11   18 19 20         |
        // 03 04 05   12 13 14   21 22 23         v
        // 06 07 08   15 16 17   24 25 26         Z

        Vector4f[] nb = n.getNeighbourLights(face); // UP: 20, 19 18, 21, 24, 25, 26, 23

        // a01  a11
        // a00  a10
        Vector4f store = new Vector4f();
        float a11 = chunk.vertexColor(nb[7], nb[1], nb[0], color, store).w; // UP:20 DO:00 NO:00 SO:08 WE:06 EA:02
        chunkMesh.getColors().add(store);

        float a01 = chunk.vertexColor(nb[1], nb[3], nb[2], color, store).w; // UP:18 DO:02 NO:18 SO:26 WE:24 EA:20
        chunkMesh.getColors().add(store);

        float a10 = chunk.vertexColor(nb[5], nb[7], nb[6], color, store).w; // UP:26 DO:06 NO:02 SO:06 WE:00 EA:08
        chunkMesh.getColors().add(store);

        float a00 = chunk.vertexColor(nb[3], nb[5], nb[4], color, store).w; // UP:24 DO:08 NO:20 SO:24 WE:18 EA:26
        chunkMesh.getColors().add(store);

        float grad1 = Math.abs(a00 - a11);
        float grad2 = Math.abs(a01 - a10);
        return grad1 < grad2;
    }

    private void hardShadow(Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
    }

    protected void createNorth(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        addQuad(location, chunkMesh, blockScale, v0, v1, v2, v3);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 0.0f, -1.0f));
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, HEIGHTS[level] / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, HEIGHTS[level] / UV_PADDING_FACTOR + UV_PADDING));
        }
    }

    protected void createSouth(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        addQuad(location, chunkMesh, blockScale, v0, v1, v2, v3);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 0.0f, 1.0f));
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, HEIGHTS[level] / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, HEIGHTS[level] / UV_PADDING_FACTOR + UV_PADDING));
        }
    }

    protected void createEast(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        addQuad(location, chunkMesh, blockScale, v0, v1, v2, v3);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(1.0f, 0.0f, 0.0f));
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, HEIGHTS[level] / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, HEIGHTS[level] / UV_PADDING_FACTOR + UV_PADDING));
        }
    }

    protected void createWest(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        addQuad(location, chunkMesh, blockScale, v0, v1, v2, v3);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(-1, 0.0f, 0.0f));
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, HEIGHTS[level] / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, HEIGHTS[level] / UV_PADDING_FACTOR + UV_PADDING));
        }
    }

    protected void createDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, float[] v0, float[] v1, float[] v2, float[] v3) {
        addQuad(location, chunkMesh, blockScale, v0, v1, v2, v3);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, -1.0f, 0.0f));
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

        // Compute flow direction (x and z)
        float[] min = v0;
        float[] max = v0;
        if (v1[1] < min[1]) {
            min = v1;
        } else if (v1[1] > max[1]) {
            max = v1;
        }
        if (v2[1] < min[1]) {
            min = v2;
        } else if (v2[1] > max[1]) {
            max = v2;
        }
        if (v3[1] < min[1]) {
            min = v3;
        } else if (v3[1] > max[1]) {
            max = v3;
        }
        float x = min[0] - max[0];
        float z = min[2] - max[2];

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 1.0f, 0.0f));
            }
            // uvs, following flow direction
            if (z > 0) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 0.0f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 0.0f + UV_PADDING));

            } else if (z < 0) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 0.0f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 0.0f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 1.0f - UV_PADDING));

            } else if (x > 0) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 0.0f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 0.0f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 1.0f - UV_PADDING));

            } else {
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 0.0f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 0.0f + UV_PADDING));
            }
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


    private boolean isLiquidFaceVisible(BlockNeighborhood neighborhood, Direction direction) {
        // A liquid face is always visible excepted if the neighbour is also liquid
        Block neighbour = neighborhood.getNeighbour(direction);
        if (neighbour == null) {
            return true;
        }
        if (neighbour.getLiquidLevel() > 0) {
            return false;
        }
        return neighbour.isTransparent() || !ShapeIds.CUBE.equals(neighbour.getShape());
    }

}

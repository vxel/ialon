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

    public boolean fullyCoversFace(Direction direction) {
        return true;
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
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 0.0f, -1.0f));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f + UV_PADDING));
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
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 0.0f, 1.0f));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f + UV_PADDING));
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
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(1.0f, 0.0f, 0.0f));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f + UV_PADDING));
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
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(-1.0f, 0.0f, 0.0f));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f + UV_PADDING));
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
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, -1.0f, 0.0f));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1f / 3f - UV_PADDING));
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
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(0.0f, 1.0f, 0.0f));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f + UV_PADDING));
            }
        }
    }

}

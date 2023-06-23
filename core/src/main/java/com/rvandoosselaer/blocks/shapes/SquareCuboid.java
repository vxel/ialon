package com.rvandoosselaer.blocks.shapes;

import com.jme3.math.Quaternion;
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

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * A shape implementation for a square cuboid. A square cuboid is a cube shape with a controllable y (height) value just
 * as a {@link Slab}. The main difference between a square cuboid and a slab, is that a square cuboid is considered as
 * a cube in the face visible check algorithm. Even if the y values are different from the default cube (greater then 0
 * or smaller then 1), the faces between adjacent square cuboids will not be rendered even if they are not shared or
 * touching.
 *
 * @author rvandoosselaer
 */
@Slf4j
@ToString
public class SquareCuboid extends Slab {

    private static final float DELTA = 0.0f;

    private static final int UP = 0;

    public SquareCuboid(float startY, float endY) {
        super(startY, endY);
    }

    @Override
    public void add(BlockNeighborhood neighborhood, ChunkMesh chunkMesh) {
        Chunk chunk = neighborhood.getChunk();
        Vec3i location = neighborhood.getLocation();

        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have 3 textures or only one
        Block block = chunk.getBlock(location.x, location.y, location.z);
        if (block == null)
            return;

        boolean multipleImages = block.isUsingMultipleImages();

        // get the rotation of the shape based on the direction
        Quaternion rotation = Shape.getRotationFromDirection(direction);

        float localStartY = startY;
        float localEndY = endY;

        boolean upFace = chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.UP, direction));
        boolean downFace = chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.DOWN, direction));
        boolean westFace = chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.WEST, direction));
        boolean eastFace = chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.EAST, direction));
        boolean southFace = chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.SOUTH, direction));
        boolean northFace = chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.NORTH, direction));
        boolean[] faces = {upFace, downFace, westFace, eastFace, southFace, northFace};

        neighborhood.getNeighbours();
        Block[] neighbours = {
                chunk.getNeighbour(location, Direction.UP),
                chunk.getNeighbour(location, Direction.DOWN),
                chunk.getNeighbour(location, Direction.WEST),
                chunk.getNeighbour(location, Direction.EAST),
                chunk.getNeighbour(location, Direction.SOUTH),
                chunk.getNeighbour(location, Direction.NORTH),
        };

        if (upFace) {
            createUp(location, rotation, chunkMesh, blockScale, multipleImages);
            enlightFace(neighborhood, location, Direction.UP, chunk, chunkMesh);
        } else {
            localEndY = .5f;
        }
        if (downFace) {
            createDown(location, rotation, chunkMesh, blockScale, multipleImages, faces, neighbours, chunk);
            enlightFace(neighborhood, location, Direction.DOWN, chunk, chunkMesh);
        } else {
            localStartY = -0.5f;
        }
        if (westFace) {
            createWest(location, rotation, chunkMesh, blockScale, multipleImages, localStartY, localEndY, faces, neighbours, chunk);
            enlightFace(neighborhood, location, Direction.WEST, chunk, chunkMesh);
        }
        if (eastFace) {
            createEast(location, rotation, chunkMesh, blockScale, multipleImages, localStartY, localEndY, faces, neighbours, chunk);
            enlightFace(neighborhood, location, Direction.EAST, chunk, chunkMesh);
        }
        if (southFace) {
            createSouth(location, rotation, chunkMesh, blockScale, multipleImages, localStartY, localEndY, faces, neighbours, chunk);
            enlightFace(neighborhood, location, Direction.SOUTH, chunk, chunkMesh);
        }
        if (northFace) {
            createNorth(location, rotation, chunkMesh, blockScale, multipleImages, localStartY, localEndY, faces, neighbours, chunk);
            enlightFace(neighborhood, location, Direction.NORTH, chunk, chunkMesh);
        }
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        add(new BlockNeighborhood(location, chunk), chunkMesh);
    }

    private void enlightFace2(Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
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

    protected void createNorth(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, float startY, float endY, boolean[] faces, Block[] neighbours, Chunk chunk) {
        Direction faceDirection = Direction.NORTH;
        float extendEast = getExtension(location, Direction.EAST, faceDirection, faces, neighbours, chunk);
        float extendWest = getExtension(location, Direction.WEST, faceDirection, faces, neighbours, chunk);
        float extendUp = faces[UP] ? 0 : DELTA;//getExtension(location, Direction.UP, faceDirection, faces, neighbours, chunk);
        float extendDown = getExtension(location, Direction.DOWN, faceDirection, faces, neighbours, chunk);

        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();

        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f - extendWest, startY - extendDown, -(.5f - DELTA))), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f - extendWest, endY + extendUp, -(.5f - DELTA))), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f + extendEast, startY - extendDown, -(.5f - DELTA))), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f + extendEast, endY + extendUp, -(.5f - DELTA))), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, 0.0f, -1.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange(startY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange(endY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange(startY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange(endY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
            }
        }
    }

    protected void createSouth(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, float startY, float endY, boolean[] faces, Block[] neighbours, Chunk chunk) {
        Direction faceDirection = Direction.SOUTH;
        float extendEast = getExtension(location, Direction.EAST, faceDirection, faces, neighbours, chunk);
        float extendWest = getExtension(location, Direction.WEST, faceDirection, faces, neighbours, chunk);
        float extendUp = faces[UP] ? 0 : DELTA;
        float extendDown = getExtension(location, Direction.DOWN, faceDirection, faces, neighbours, chunk);

        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f + extendEast, startY - extendDown, (.5f - DELTA))), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f + extendEast, endY + extendUp, (.5f - DELTA))), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f - extendWest, startY - extendDown, (.5f - DELTA))), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f - extendWest, endY + extendUp, (.5f - DELTA))), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, 0.0f, 1.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange(startY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange(endY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange(startY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange(endY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
            }
        }
    }

    protected void createEast(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, float startY, float endY, boolean[] faces, Block[] neighbours, Chunk chunk) {
        Direction faceDirection = Direction.EAST;
        float extendNorth = getExtension(location, Direction.NORTH, faceDirection, faces, neighbours, chunk);
        float extendSouth = getExtension(location, Direction.SOUTH, faceDirection, faces, neighbours, chunk);
        float extendUp = faces[UP] ? 0 : DELTA;
        float extendDown = getExtension(location, Direction.DOWN, faceDirection, faces, neighbours, chunk);

        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f((.5f - DELTA), startY - extendDown, -0.5f - extendNorth)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f((.5f - DELTA), endY + extendUp, -0.5f - extendNorth)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f((.5f - DELTA), startY - extendDown, 0.5f + extendSouth)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f((.5f - DELTA), endY + extendUp, 0.5f + extendSouth)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(1.0f, 0.0f, 0.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange(startY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange(endY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange(startY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange(endY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
            }
        }
    }

    protected void createWest(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, float startY, float endY, boolean[] faces, Block[] neighbours, Chunk chunk) {
        Direction faceDirection = Direction.WEST;
        float extendNorth = getExtension(location, Direction.NORTH, faceDirection, faces, neighbours, chunk);
        float extendSouth = getExtension(location, Direction.SOUTH, faceDirection, faces, neighbours, chunk);
        float extendUp = faces[UP] ? 0 : DELTA;
        float extendDown = getExtension(location, Direction.DOWN, faceDirection, faces, neighbours, chunk);

        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-(.5f - DELTA), startY - extendDown, 0.5f + extendSouth)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-(.5f - DELTA), endY + extendUp, 0.5f + extendSouth)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-(.5f - DELTA), startY - extendDown, -0.5f - extendNorth)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-(.5f - DELTA), endY + extendUp, -0.5f - extendNorth)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(-1, 0.0f, 0.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange(startY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange(endY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange(startY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange(endY + 0.5f, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f)) / UV_PADDING_FACTOR + UV_PADDING));
            }
        }
    }

    private float getExtension(Vec3i location, Direction extensionDirection, Direction faceDirection, boolean[] faces, Block[] neighbours, Chunk chunk) {
        if (faces[extensionDirection.ordinal()]) return -DELTA;
        if (neighbours[extensionDirection.ordinal()] == null) return DELTA;
        if (chunk.isNeighbourFaceVisible(location, extensionDirection, faceDirection)) return 0;
        return DELTA;
    }

    protected void createDown(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean[] faces, Block[] neighbours, Chunk chunk) {
        Direction faceDirection = Direction.DOWN;
        float extendEast = getExtension(location, Direction.EAST, faceDirection, faces, neighbours, chunk);
        float extendWest = getExtension(location, Direction.WEST, faceDirection, faces, neighbours, chunk);
        float extendNorth = getExtension(location, Direction.NORTH, faceDirection, faces, neighbours, chunk);
        float extendSouth = getExtension(location, Direction.SOUTH, faceDirection, faces, neighbours, chunk);

        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f - extendWest, startY + DELTA, -0.5f - extendNorth)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f + extendEast, startY + DELTA, -0.5f - extendNorth)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f - extendWest, startY + DELTA, 0.5f + extendSouth)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f + extendEast, startY + DELTA, 0.5f + extendSouth)), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, -1.0f, 0.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 0.0f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 0.0f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 0.0f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 0.0f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 1f / 3f - UV_PADDING));
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
            // normals
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, 1.0f, 0.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 0.0f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 0.0f + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, 2f / 3f + UV_PADDING));
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

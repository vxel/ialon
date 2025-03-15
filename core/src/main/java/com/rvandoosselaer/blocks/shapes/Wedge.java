package com.rvandoosselaer.blocks.shapes;

import com.jme3.math.FastMath;
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
    public void add(BlockNeighborhood neighborhood, ChunkMesh chunkMesh) {
        Chunk chunk = neighborhood.getChunk();
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have 3 textures or only one
        Block block = neighborhood.getCenterBlock();
        if (block == null)
            return;

        Direction faceDirection;
        boolean flip = false;
        boolean enlight = !chunkMesh.isCollisionMesh();
        boolean multipleImages = block.isUsingMultipleImages();

        Vec3i location = neighborhood.getLocation();

        // SOUTH
        faceDirection = Shape.getYawFaceDirection(Direction.SOUTH, direction);
        flip = enlight && softShadowSouthFace(neighborhood, location, faceDirection, chunk, chunkMesh);
        createSouth(location, chunkMesh, rotation, blockScale, multipleImages, flip);

        // WEST
        faceDirection = Shape.getYawFaceDirection(upsideDown ? Direction.EAST : Direction.WEST, direction);
        if (chunk.isFaceVisible(location, faceDirection)) {
            flip = enlight && softShadowWestFace(neighborhood, location, faceDirection, chunk, chunkMesh);
            createWest(location, chunkMesh, rotation, blockScale, multipleImages, flip);
        }

        // EAST
        faceDirection = Shape.getYawFaceDirection(upsideDown ? Direction.WEST : Direction.EAST, direction);
        if (chunk.isFaceVisible(location, faceDirection)) {
            flip = enlight && softShadowEastFace(neighborhood, location, faceDirection, chunk, chunkMesh);
            createEast(location, chunkMesh, rotation, blockScale, multipleImages, flip);
        }

        // NORTH
        faceDirection = Shape.getYawFaceDirection(Direction.NORTH, direction);
        if (chunk.isFaceVisible(location, faceDirection)) {
            flip = enlight && softShadow(neighborhood, location, faceDirection, chunk, chunkMesh);
            createNorth(location, chunkMesh, rotation, blockScale, multipleImages, flip);
        }

        // DOWN
        faceDirection = Shape.getYawFaceDirection(upsideDown ? Direction.UP : Direction.DOWN, direction);
        if (chunk.isFaceVisible(location, faceDirection)) {
            flip = enlight && softShadow(neighborhood, location, faceDirection, chunk, chunkMesh);
            createDown(location, chunkMesh, rotation, blockScale, multipleImages, flip);
        }
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        add(new BlockNeighborhood(location, chunk), chunkMesh);
    }

    public boolean fullyCoversFace(Direction direction) {
        Direction oppositeYawFaceDirection = Shape.getOppositeYawFaceDirection(direction, this.direction);
        return oppositeYawFaceDirection == (upsideDown ? Direction.UP : Direction.DOWN)
                || oppositeYawFaceDirection == Direction.NORTH;
    }

    private boolean softShadow(BlockNeighborhood n, Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);
        Vector4f[] nb = n.getNeighbourLights(this.direction, face, null, upsideDown);

        // a01  a11
        // a00  a10
        Vector4f store = new Vector4f();
        float a11 = chunk.vertexColor(nb[7], nb[1], nb[0], color, store).w;
        chunkMesh.getColors().add(store);

        float a01 = chunk.vertexColor(nb[1], nb[3], nb[2], color, store).w;
        chunkMesh.getColors().add(store);

        float a10 = chunk.vertexColor(nb[5], nb[7], nb[6], color, store).w;
        chunkMesh.getColors().add(store);

        float a00 = chunk.vertexColor(nb[3], nb[5], nb[4], color, store).w;
        chunkMesh.getColors().add(store);

        float grad1 = Math.abs(a00 - a11);
        float grad2 = Math.abs(a01 - a10);
        return grad1 < grad2;
    }

    private boolean softShadowEastFace(BlockNeighborhood n, Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);
        Vector4f[] nb = n.getNeighbourLights(this.direction, face, null, upsideDown);
        Vector4f store = new Vector4f();
        chunk.vertexColor(nb[7], nb[1], nb[0], color, store);
        chunkMesh.getColors().add(store);
        chunk.vertexColor(nb[1], nb[3], nb[2], color, store);
        chunkMesh.getColors().add(store);
        chunk.vertexColor(nb[5], nb[7], nb[6], color, store);
        chunkMesh.getColors().add(store);
        return false;
    }

    private boolean softShadowWestFace(BlockNeighborhood n, Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);
        Vector4f[] nb = n.getNeighbourLights(this.direction, face, null, upsideDown);
        Vector4f store = new Vector4f();
        chunk.vertexColor(nb[7], nb[1], nb[0], color, store);
        chunkMesh.getColors().add(store);
        chunk.vertexColor(nb[3], nb[5], nb[4], color, store);
        chunkMesh.getColors().add(store);
        chunk.vertexColor(nb[5], nb[7], nb[6], color, store);
        chunkMesh.getColors().add(store);
        return false;
    }

    private boolean softShadowSouthFace(BlockNeighborhood n, Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, null);
        Vector4f[] nb = n.getNeighbourWedgeLights(face, null, upsideDown);

        Vector4f store = new Vector4f();
        float a10 = chunk.vertexColor(nb[5], nb[7], nb[6], color, store).w;
        chunkMesh.getColors().add(store);
        float a11 = chunk.vertexColor(nb[7], nb[1], nb[0], color, store).w;
        chunkMesh.getColors().add(store);
        float a00 = chunk.vertexColor(nb[3], nb[5], nb[4], color, store).w;
        chunkMesh.getColors().add(store);
        float a01 = chunk.vertexColor(nb[1], nb[3], nb[2], color, store).w;
        chunkMesh.getColors().add(store);

        float grad1 = Math.abs(a01 - a10);
        float grad2 = Math.abs(a11 - a00);
        return grad1 < grad2;
    }

    private static void createDown(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages, boolean flip) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, -0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, -0.5f, 0.5f)), location, blockScale));
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
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, -1.0f, 0.0f)));
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

    private static void createNorth(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages, boolean flip) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, 0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, -0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, 0.5f, -0.5f)), location, blockScale));
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

    private static void createEast(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages, boolean flip) {
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

    private static void createWest(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages, boolean flip) {
        int offset;
        // calculate index offset, we use this to connect the triangles
        offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, 0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, -0.5f)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING));
            }
        }
    }

    private static void createSouth(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages, boolean flip) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, -0.5f, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, 0.5f, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, -0.5f, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, 0.5f, -0.5f)), location, blockScale));
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
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, 0.70710677f, 0.70710677f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 2f / 3f + UV_PADDING));
            }
        }
    }

}

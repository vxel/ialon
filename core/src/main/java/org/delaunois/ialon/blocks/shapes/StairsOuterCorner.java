package org.delaunois.ialon.blocks.shapes;

import com.jme3.math.FastMath;
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
 * A shape implementation for an inner corner stair. The default facing of a stair is South.
 *
 * @author rvandoosselaer
 */
@ToString
public class StairsOuterCorner implements Shape {

    private static final Quaternion PI_X = new Quaternion().fromAngleAxis(FastMath.PI, Vector3f.UNIT_X);
    private static final Quaternion PI_Y = new Quaternion().fromAngleAxis(FastMath.PI, Vector3f.UNIT_Y);
    private static final Quaternion INVERSE = PI_X.mult(PI_Y);

    private final Direction direction;
    private final boolean upsideDown;
    private final Quaternion rotation;

    // Each face uses a single uniform normal : rotate it once here instead of per vertex.
    private final Vector3f nUp;
    private final Vector3f nDown;
    private final Vector3f nNorth;
    private final Vector3f nSouth;
    private final Vector3f nEast;
    private final Vector3f nWest;

    public StairsOuterCorner() {
        this(Direction.UP, false);
    }

    public StairsOuterCorner(Direction direction, boolean upsideDown) {
        this.direction = direction;
        this.upsideDown = upsideDown;

        // when the shape is upside down (inverted), we need to perform 3 rotations. Two to invert the shape and one
        // for the direction.
        Quaternion rot = Shape.getYawFromDirection(direction);
        if (upsideDown) {
            rot = INVERSE.mult(rot.inverse());
        }
        this.rotation = rot;

        this.nUp = rot.mult(new Vector3f(0.0f, 1.0f, 0.0f));
        this.nDown = rot.mult(new Vector3f(0.0f, -1.0f, 0.0f));
        this.nNorth = rot.mult(new Vector3f(0.0f, 0.0f, -1.0f));
        this.nSouth = rot.mult(new Vector3f(0.0f, 0.0f, 1.0f));
        this.nEast = rot.mult(new Vector3f(1.0f, 0.0f, 0.0f));
        this.nWest = rot.mult(new Vector3f(-1.0f, 0.0f, 0.0f));
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have 3 textures or only one
        boolean multipleImages = chunk.getBlock(location.x, location.y, location.z).isUsingMultipleImages();

        createUp(location, chunkMesh, rotation, blockScale, multipleImages);
        enlightFace(location, null, chunk, chunkMesh, 16);

        createSouth(location, chunkMesh, rotation, blockScale, multipleImages);
        enlightFace(location, null, chunk, chunkMesh, 12);

        if (chunk.isFaceVisible(location, Shape.getYawFaceDirection(upsideDown ? Direction.EAST : Direction.WEST, direction))) {
            createWest(location, chunkMesh, rotation, blockScale, multipleImages);
            enlightFace(location, Shape.getYawFaceDirection(upsideDown ? Direction.EAST : Direction.WEST, direction), chunk, chunkMesh, 10);
        }

        createEast(location, chunkMesh, rotation, blockScale, multipleImages);
        enlightFace(location, null, chunk, chunkMesh, 12);

        if (chunk.isFaceVisible(location, Shape.getYawFaceDirection(Direction.NORTH, direction))) {
            createNorth(location, chunkMesh, rotation, blockScale, multipleImages);
            enlightFace(location, Shape.getYawFaceDirection(Direction.NORTH, direction), chunk, chunkMesh, 10);
        }
        if (chunk.isFaceVisible(location, Shape.getYawFaceDirection(upsideDown ? Direction.UP : Direction.DOWN, direction))) {
            createDown(location, chunkMesh, rotation, blockScale, multipleImages);
            enlightFace(location, Shape.getYawFaceDirection(upsideDown ? Direction.UP : Direction.DOWN, direction), chunk, chunkMesh, 4);
        }
    }

    public boolean fullyCoversFace(Direction direction) {
        return Shape.getOppositeYawFaceDirection(direction, this.direction) == Direction.DOWN;
    }

    private void enlightFace(Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh, int numVertices) {
        Vector4f color = chunk.getLightLevel(location, face);
        for (int i = 0; i < numVertices; i++) {
            chunkMesh.getColors().add(color);
        }
    }

    private void createUp(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages) {
        int offset = chunkMesh.getPositions().size();
        // # Positions:16
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, 0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.167f, -0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, 0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, -0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.167f, 0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.167f, -0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.167f, 0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.500f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.500f, -0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.500f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, -0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.500f, -0.167f)), location, blockScale));
        // Index:
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 4);
        chunkMesh.getIndices().add(offset + 5);
        chunkMesh.getIndices().add(offset + 6);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 7);
        chunkMesh.getIndices().add(offset + 8);
        chunkMesh.getIndices().add(offset + 4);
        chunkMesh.getIndices().add(offset + 9);
        chunkMesh.getIndices().add(offset + 10);
        chunkMesh.getIndices().add(offset + 11);
        chunkMesh.getIndices().add(offset + 12);
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 13);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 14);
        chunkMesh.getIndices().add(offset + 4);
        chunkMesh.getIndices().add(offset + 6);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 8);
        chunkMesh.getIndices().add(offset + 5);
        chunkMesh.getIndices().add(offset + 4);
        chunkMesh.getIndices().add(offset + 10);
        chunkMesh.getIndices().add(offset + 15);
        chunkMesh.getIndices().add(offset + 11);
        if (!chunkMesh.isCollisionMesh()) {
            // Normals:
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            chunkMesh.getNormals().add(nUp);
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.889f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.778f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.778f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.778f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.889f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.778f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.889f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.889f / UV_PADDING_FACTOR + UV_PADDING));
            }
        }
    }

    private void createSouth(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages) {
        int offset = chunkMesh.getPositions().size();
        // # Positions:12
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.167f, -0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.500f, -0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.167f, -0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, 0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, -0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.167f, 0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.167f, 0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.500f, 0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.500f, -0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.500f, 0.500f)), location, blockScale));
        // Index:
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 4);
        chunkMesh.getIndices().add(offset + 5);
        chunkMesh.getIndices().add(offset + 6);
        chunkMesh.getIndices().add(offset + 7);
        chunkMesh.getIndices().add(offset + 8);
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 9);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 10);
        chunkMesh.getIndices().add(offset + 4);
        chunkMesh.getIndices().add(offset + 6);
        chunkMesh.getIndices().add(offset + 8);
        chunkMesh.getIndices().add(offset + 11);
        if (!chunkMesh.isCollisionMesh()) {
            // Normals:
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.000f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.555f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.665f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.555f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.555f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.445f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.445f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.445f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.445f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.335f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.665f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.555f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.335f / UV_PADDING_FACTOR + UV_PADDING));
            }
        }
    }

    private void createDown(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages) {
        int offset = chunkMesh.getPositions().size();
        // # Positions:4
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.500f, 0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.500f, 0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.500f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.500f, -0.500f)), location, blockScale));
        // Index:
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 3);
        if (!chunkMesh.isCollisionMesh()) {
            // Normals:
            chunkMesh.getNormals().add(nDown);
            chunkMesh.getNormals().add(nDown);
            chunkMesh.getNormals().add(nDown);
            chunkMesh.getNormals().add(nDown);
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f - UV_PADDING));
            }
        }
    }

    private void createNorth(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages) {
        int offset = chunkMesh.getPositions().size();
        // # Positions:10
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.500f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.500f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, -0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.500f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, 0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.500f, -0.500f)), location, blockScale));
        // Index:
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 4);
        chunkMesh.getIndices().add(offset + 5);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 6);
        chunkMesh.getIndices().add(offset + 7);
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 5);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 8);
        chunkMesh.getIndices().add(offset + 4);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 9);
        chunkMesh.getIndices().add(offset + 6);
        if (!chunkMesh.isCollisionMesh()) {
            // Normals:
            chunkMesh.getNormals().add(nNorth);
            chunkMesh.getNormals().add(nNorth);
            chunkMesh.getNormals().add(nNorth);
            chunkMesh.getNormals().add(nNorth);
            chunkMesh.getNormals().add(nNorth);
            chunkMesh.getNormals().add(nNorth);
            chunkMesh.getNormals().add(nNorth);
            chunkMesh.getNormals().add(nNorth);
            chunkMesh.getNormals().add(nNorth);
            chunkMesh.getNormals().add(nNorth);
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.444f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.556f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.444f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.444f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.556f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.556f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
            }
        }
    }

    private void createEast(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages) {
        int offset = chunkMesh.getPositions().size();
        // # Positions:12
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.500f, 0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.500f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, 0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, -0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, -0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.500f, -0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.167f, -0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.500f, -0.167f, 0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.167f, 0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.167f, 0.500f, -0.500f)), location, blockScale));
        // Index:
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 4);
        chunkMesh.getIndices().add(offset + 5);
        chunkMesh.getIndices().add(offset + 6);
        chunkMesh.getIndices().add(offset + 7);
        chunkMesh.getIndices().add(offset + 8);
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 9);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 10);
        chunkMesh.getIndices().add(offset + 4);
        chunkMesh.getIndices().add(offset + 6);
        chunkMesh.getIndices().add(offset + 11);
        chunkMesh.getIndices().add(offset + 7);
        if (!chunkMesh.isCollisionMesh()) {
            // Normals:
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.444f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.556f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.444f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.444f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.556f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.556f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.444f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.556f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
            }
        }
    }

    private void createWest(Vec3i location, ChunkMesh chunkMesh, Quaternion rotation, float blockScale, boolean multipleImages) {
        int offset = chunkMesh.getPositions().size();
        // # Positions:10
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.167f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.500f, 0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.167f, 0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.167f, -0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.500f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.167f, 0.167f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, -0.500f, -0.500f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.500f, 0.500f, -0.167f)), location, blockScale));
        // Index:
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 4);
        chunkMesh.getIndices().add(offset + 5);
        chunkMesh.getIndices().add(offset + 6);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 0);
        chunkMesh.getIndices().add(offset + 7);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 8);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 5);
        chunkMesh.getIndices().add(offset + 9);
        chunkMesh.getIndices().add(offset + 6);
        if (!chunkMesh.isCollisionMesh()) {
            // Normals:
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.000f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 1.000f / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.444f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.556f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.444f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.000f / UV_PADDING_FACTOR + UV_PADDING, 0.444f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.556f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.667f / UV_PADDING_FACTOR + UV_PADDING, 0.556f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.000f / UV_PADDING_FACTOR + UV_PADDING, 0.333f / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.333f / UV_PADDING_FACTOR + UV_PADDING, 0.667f / UV_PADDING_FACTOR + UV_PADDING));
            }
        }
    }

}

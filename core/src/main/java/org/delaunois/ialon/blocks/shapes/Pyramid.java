package org.delaunois.ialon.blocks.shapes;

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
 * A shape implementation for a pyramid. The default direction of a pyramid is UP: the point will face the UP direction.
 * <p>
 * The pyramid is made of 4 triangular sides and a quad base with a non-standard triangulation, so it does not use
 * {@link Shape#emitQuad}. It still benefits from the shared infrastructure : positions go through
 * {@link Shape#emitVertex} (reusing a scratch vertex, no per-vertex allocation) and the rotated face normals are
 * precomputed once in the constructor instead of being recomputed per vertex.
 *
 * @author rvandoosselaer
 */
@ToString
public class Pyramid implements Shape {

    // Cube base corners + apex (named by the sign of x, y, z : n = -0.5, p = +0.5). Never mutated.
    private static final Vector3f C_NNN = new Vector3f(-0.5f, -0.5f, -0.5f);
    private static final Vector3f C_NNP = new Vector3f(-0.5f, -0.5f, 0.5f);
    private static final Vector3f C_PNN = new Vector3f(0.5f, -0.5f, -0.5f);
    private static final Vector3f C_PNP = new Vector3f(0.5f, -0.5f, 0.5f);
    private static final Vector3f APEX = new Vector3f(0.0f, 0.5f, 0.0f);

    private final Direction direction;
    private final Quaternion emitRotation;

    private final Vector3f nDown;
    private final Vector3f nSouth;
    private final Vector3f nEast;
    private final Vector3f nNorth;
    private final Vector3f nWest;

    public Pyramid() {
        this(Direction.UP);
    }

    public Pyramid(Direction direction) {
        this.direction = direction;
        Quaternion rotation = Shape.getRotationFromDirection(direction);
        this.emitRotation = direction == Direction.UP ? null : rotation;
        this.nDown = rotation.mult(new Vector3f(0.0f, -1.0f, 0.0f));
        this.nSouth = rotation.mult(new Vector3f(0.0f, 0.4472136f, 0.8944272f));
        this.nEast = rotation.mult(new Vector3f(0.8944272f, 0.4472136f, 0.0f));
        this.nNorth = rotation.mult(new Vector3f(0.0f, 0.4472136f, -0.8944272f));
        this.nWest = rotation.mult(new Vector3f(-0.8944272f, 0.4472136f, 0.0f));
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have only one texture
        boolean multipleImages = chunk.getBlock(location.x, location.y, location.z).isUsingMultipleImages();
        boolean cm = chunkMesh.isCollisionMesh();

        createWest(location, chunkMesh, blockScale, multipleImages, cm);
        enlightFace(location, null, chunk, chunkMesh, 3);

        createNorth(location, chunkMesh, blockScale, multipleImages, cm);
        enlightFace(location, null, chunk, chunkMesh, 3);

        createEast(location, chunkMesh, blockScale, multipleImages, cm);
        enlightFace(location, null, chunk, chunkMesh, 3);

        createSouth(location, chunkMesh, blockScale, multipleImages, cm);
        enlightFace(location, null, chunk, chunkMesh, 3);

        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.DOWN, direction))) {
            createDown(location, chunkMesh, blockScale, multipleImages, cm);
            enlightFace(location, Shape.getFaceDirection(Direction.DOWN, direction), chunk, chunkMesh, 4);
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

    private void createDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        int offset = chunkMesh.getPositions().size();
        Vector3f s = VERTEX_SCRATCH.get();
        Shape.emitVertex(chunkMesh, s, emitRotation, C_PNN, location, blockScale);
        Shape.emitVertex(chunkMesh, s, emitRotation, C_NNP, location, blockScale);
        Shape.emitVertex(chunkMesh, s, emitRotation, C_NNN, location, blockScale);
        Shape.emitVertex(chunkMesh, s, emitRotation, C_PNP, location, blockScale);
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 1);

        if (!cm) {
            chunkMesh.getNormals().add(nDown);
            chunkMesh.getNormals().add(nDown);
            chunkMesh.getNormals().add(nDown);
            chunkMesh.getNormals().add(nDown);
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 1f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 1f / 3f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            }
        }
    }

    private void createSouth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        int offset = chunkMesh.getPositions().size();
        Vector3f s = VERTEX_SCRATCH.get();
        Shape.emitVertex(chunkMesh, s, emitRotation, C_PNP, location, blockScale);
        Shape.emitVertex(chunkMesh, s, emitRotation, APEX, location, blockScale);
        Shape.emitVertex(chunkMesh, s, emitRotation, C_NNP, location, blockScale);
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        if (!cm) {
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            chunkMesh.getNormals().add(nSouth);
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f + UV_PADDING));
            }
        }
    }

    private void createEast(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        int offset = chunkMesh.getPositions().size();
        Vector3f s = VERTEX_SCRATCH.get();
        Shape.emitVertex(chunkMesh, s, emitRotation, C_PNN, location, blockScale);
        Shape.emitVertex(chunkMesh, s, emitRotation, APEX, location, blockScale);
        Shape.emitVertex(chunkMesh, s, emitRotation, C_PNP, location, blockScale);
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        if (!cm) {
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            chunkMesh.getNormals().add(nEast);
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f + UV_PADDING));
            }
        }
    }

    private void createNorth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        int offset = chunkMesh.getPositions().size();
        Vector3f s = VERTEX_SCRATCH.get();
        Shape.emitVertex(chunkMesh, s, emitRotation, C_NNN, location, blockScale);
        Shape.emitVertex(chunkMesh, s, emitRotation, APEX, location, blockScale);
        Shape.emitVertex(chunkMesh, s, emitRotation, C_PNN, location, blockScale);
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        if (!cm) {
            chunkMesh.getNormals().add(nNorth);
            chunkMesh.getNormals().add(nNorth);
            chunkMesh.getNormals().add(nNorth);
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f + UV_PADDING));
            }
        }
    }

    private void createWest(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages, boolean cm) {
        int offset = chunkMesh.getPositions().size();
        Vector3f s = VERTEX_SCRATCH.get();
        Shape.emitVertex(chunkMesh, s, emitRotation, C_NNP, location, blockScale);
        Shape.emitVertex(chunkMesh, s, emitRotation, APEX, location, blockScale);
        Shape.emitVertex(chunkMesh, s, emitRotation, C_NNN, location, blockScale);
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        if (!cm) {
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            chunkMesh.getNormals().add(nWest);
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, 2f / 3f + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.5f, 1.0f - UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, 2f / 3f + UV_PADDING));
            }
        }
    }

}

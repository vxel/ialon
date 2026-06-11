package org.delaunois.ialon.blocks;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.simsilica.mathd.Vec3i;


import static org.delaunois.ialon.blocks.Direction.DOWN;
import static org.delaunois.ialon.blocks.Direction.EAST;
import static org.delaunois.ialon.blocks.Direction.NORTH;
import static org.delaunois.ialon.blocks.Direction.SOUTH;
import static org.delaunois.ialon.blocks.Direction.UP;
import static org.delaunois.ialon.blocks.Direction.WEST;

/**
 * The interface describing the shape of a {@link Block} element. The {@link #add(Vec3i, Chunk, ChunkMesh)} method is
 * called for each block in the chunk when the mesh is constructed using the {@link ChunkMeshGenerator}.
 *
 * @author rvandoosselaer
 */
public interface Shape {

    // Texture padding (prevents color bleeding). Must be >= 0 (no padding) and < 0.5
    float UV_PADDING = 0.25f;
    float UV_PADDING_FACTOR = 1 / (1 - 2 * UV_PADDING);

    // Static constants as these operations are costly and heavily used during chunk mesh generation
    Quaternion ROTATION_DOWN = new Quaternion().fromAngleAxis(FastMath.PI, Vector3f.UNIT_X);
    Quaternion ROTATION_EAST = new Quaternion().fromAngleAxis(-FastMath.HALF_PI, Vector3f.UNIT_Z);
    Quaternion ROTATION_WEST = new Quaternion().fromAngleAxis(FastMath.HALF_PI, Vector3f.UNIT_Z);
    Quaternion ROTATION_NORTH = new Quaternion().fromAngleAxis(-FastMath.HALF_PI, Vector3f.UNIT_X);
    Quaternion ROTATION_SOUTH = new Quaternion().fromAngleAxis(FastMath.HALF_PI, Vector3f.UNIT_X);
    Quaternion ROTATION_UP = new Quaternion();

    Quaternion YAW_NORTH = new Quaternion().fromAngleAxis(-FastMath.PI, Vector3f.UNIT_Y);
    Quaternion YAW_EAST = new Quaternion().fromAngleAxis(FastMath.HALF_PI, Vector3f.UNIT_Y);
    Quaternion YAW_WEST = new Quaternion().fromAngleAxis(-FastMath.HALF_PI, Vector3f.UNIT_Y);
    Quaternion YAW_UP = new Quaternion();

    // Precomputed face directions
    int DIRECTIONS_SIZE = Direction.values().length;
    Direction[] FACES_DIR = {UP,DOWN,WEST,EAST,SOUTH,NORTH,DOWN,UP,EAST,WEST,NORTH,SOUTH,WEST,WEST,DOWN,UP,WEST,WEST,EAST,EAST,UP,DOWN,EAST,EAST,SOUTH,NORTH,SOUTH,SOUTH,DOWN,UP,NORTH,SOUTH,NORTH,NORTH,UP,DOWN};

    /**
     * Adds the shape at the location in the chunk to the chunk mesh.
     *
     * @param location  of the shape in the chunk
     * @param chunk     of the shape
     * @param chunkMesh to add the shape to
     */
    void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh);

    /**
     * Adds the shape at the center of the neighborhood to the chunk mesh.
     *
     * @param neighborhood the neighborhood of the block (includes the blocks, its location and its chunk)
     * @param chunkMesh to add the shape to
     */
    default void add(BlockNeighborhood neighborhood, ChunkMesh chunkMesh) {
        add(neighborhood.getLocation(), neighborhood.getChunk(), chunkMesh);
    }

    /**
     * Defines whether the shape fully covers the face in the given direction, i.e. does
     * the face leaves any empty space on this face.
     * Example : a wedge fully covers the DOWN and NORTH face, but not the others.
     *
     * @param direction the face
     * @return true if the shape covers the given face, false if not
     */
    default boolean fullyCoversFace(Direction direction) {
        return false;
    }

    /**
     * A helper method that offsets a vertex based on the location of the block in the chunk and the block scale.
     *
     * @param vertex
     * @param blockLocation
     * @param blockScale
     * @return the same vertex with an offset
     */
    static Vector3f createVertex(Vector3f vertex, Vec3i blockLocation, float blockScale) {
        return vertex.addLocal(blockLocation.x, blockLocation.y, blockLocation.z).multLocal(blockScale);
    }

    /**
     * Per-thread scratch vertex, reused by {@link #emitQuad} to rotate/offset/scale every emitted
     * position without allocating a {@link Vector3f} in the (multi-threaded) meshing loop. The buffer
     * {@code add(...)} methods copy the components, so a single instance can be reused for all vertices.
     */
    ThreadLocal<Vector3f> VERTEX_SCRATCH = ThreadLocal.withInitial(Vector3f::new);

    /**
     * Emits one quad (4 vertices / 2 triangles) into the chunk mesh. This is the shared building block
     * for all quad-based shapes : it factors out the index/normal/uv emission that used to be copy-pasted
     * in every {@code createXxx()} method, and removes the per-vertex {@link Vector3f} allocations.
     * <p>
     * The local vertices {@code v0..v3} and the {@code normal} are <strong>never mutated</strong>, so
     * callers can (and should) pass shared {@code static final} constants. The {@code normal} must already
     * be expressed in the shape's world orientation : pre-rotate/precompute it once (e.g. in the shape
     * constructor) rather than rotating it per vertex.
     *
     * @param rotation      the shape rotation, or {@code null} for an axis-aligned (identity) shape
     * @param v0 v1 v2 v3   the 4 local vertices, in the emission order the legacy {@code createXxx()} used
     * @param normal        the (already world-oriented) face normal, shared across the 4 vertices
     * @param uvs           the 4 texture coordinates
     * @param flip          when {@code true}, swaps the triangle diagonal (used by smooth-shadow AO)
     * @param collisionMesh when {@code true}, only positions + indices are emitted (no normals / uvs)
     */
    static void emitQuad(ChunkMesh mesh, Vec3i location, float blockScale, Quaternion rotation,
                         Vector3f v0, Vector3f v1, Vector3f v2, Vector3f v3,
                         Vector3f normal, Vector2f[] uvs, boolean flip, boolean collisionMesh) {
        int offset = mesh.getPositions().size();
        Vector3f scratch = VERTEX_SCRATCH.get();
        emitVertex(mesh, scratch, rotation, v0, location, blockScale);
        emitVertex(mesh, scratch, rotation, v1, location, blockScale);
        emitVertex(mesh, scratch, rotation, v2, location, blockScale);
        emitVertex(mesh, scratch, rotation, v3, location, blockScale);

        DirectIntBuffer indices = mesh.getIndices();
        if (flip) {
            indices.add(offset + 1);
            indices.add(offset + 3);
            indices.add(offset);
            indices.add(offset + 3);
            indices.add(offset + 2);
            indices.add(offset);
        } else {
            indices.add(offset);
            indices.add(offset + 1);
            indices.add(offset + 2);
            indices.add(offset + 1);
            indices.add(offset + 3);
            indices.add(offset + 2);
        }

        if (!collisionMesh) {
            DirectVector3fBuffer normals = mesh.getNormals();
            normals.add(normal);
            normals.add(normal);
            normals.add(normal);
            normals.add(normal);
            DirectVector2fBuffer uvBuffer = mesh.getUvs();
            uvBuffer.add(uvs[0]);
            uvBuffer.add(uvs[1]);
            uvBuffer.add(uvs[2]);
            uvBuffer.add(uvs[3]);
        }
    }

    /**
     * Rotates (when {@code rotation} is non-null), offsets by the block location and scales the
     * {@code local} vertex into the supplied {@code scratch} instance, then appends it to the mesh
     * positions. No allocation : {@code scratch} is mutated and the buffer copies its components.
     */
    static void emitVertex(ChunkMesh mesh, Vector3f scratch, Quaternion rotation, Vector3f local,
                           Vec3i location, float blockScale) {
        if (rotation == null) {
            scratch.set(local);
        } else {
            rotation.mult(local, scratch);
        }
        scratch.addLocal(location.x, location.y, location.z).multLocal(blockScale);
        mesh.getPositions().add(scratch);
    }

    /**
     * A helper method to calculate the rotation of the shape from the given direction. Depending on the direction,
     * the rotation is done around a different axis. This has the effect of 'pushing' a shape over to the direction.
     * This should be used for shapes that don't have a fixed upward position.
     * The default direction is UP. All rotation calculations are relative to the UP direction.
     *
     * @param direction the shape is facing
     * @return the rotation to face the direction
     */
    static Quaternion getRotationFromDirection(Direction direction) {
        switch (direction) {
            case DOWN:
                return ROTATION_DOWN;
            case EAST:
                return ROTATION_EAST;
            case WEST:
                return ROTATION_WEST;
            case NORTH:
                return ROTATION_NORTH;
            case SOUTH:
                return ROTATION_SOUTH;
            default:
                return ROTATION_UP;
        }
    }

    /**
     * A helper method to calculate the yaw rotation (rotation around the y-axis) of the shape for the given direction.
     * This should be used for shapes that have a fixed upwards position.
     * The default direction is SOUTH. All rotation calculations are relative to the SOUTH direction.
     *
     * @param direction the shape is facing
     * @return the rotation to face the direction
     */
    static Quaternion getYawFromDirection(Direction direction) {
        switch (direction) {
            case NORTH:
                return YAW_NORTH;
            case EAST:
                return YAW_EAST;
            case WEST:
                return YAW_WEST;
            default:
                return YAW_UP;
        }
    }

    static Quaternion getOppositeYawFromDirection(Direction direction) {
        switch (direction) {
            case NORTH:
                return YAW_NORTH;
            case EAST:
                return YAW_WEST;
            case WEST:
                return YAW_EAST;
            default:
                return YAW_UP;
        }
    }

    /**
     * Calculates the new direction of a face, based on the rotation of the shape. eg. The north face of a shape that is
     * rotated, is not facing north anymore.
     *
     * @param faceDirection  the original direction of the face
     * @param shapeDirection the direction of the shape
     * @return the new direction of the face based on the direction of the shape
     */
    static Direction getFaceDirection(Direction faceDirection, Direction shapeDirection) {
        return FACES_DIR[faceDirection.ordinal() * DIRECTIONS_SIZE + shapeDirection.ordinal()];
    }

    /**
     * Calculates the new yaw direction (rotation around the y-axis) of a face, based on the yaw rotation of the shape.
     * This should be used for shapes that have a fixed upwards position.
     *
     * @param faceDirection  the original direction of the face
     * @param shapeDirection the direction of the shape
     * @return the new direction of the face based on the direction of the shape
     */
    static Direction getYawFaceDirection(Direction faceDirection, Direction shapeDirection) {
        Quaternion shapeRotation = getYawFromDirection(shapeDirection);
        Vector3f newFaceDirection = shapeRotation.mult(faceDirection.getVector().toVector3f());

        return Direction.fromVector(newFaceDirection);
    }

    static Direction getOppositeYawFaceDirection(Direction faceDirection, Direction shapeDirection) {
        Quaternion shapeRotation = getOppositeYawFromDirection(shapeDirection);
        Vector3f newFaceDirection = shapeRotation.mult(faceDirection.getVector().toVector3f());

        return Direction.fromVector(newFaceDirection);
    }

    /**
     * Linearly maps {@code value} from the source range {@code [rangeMin, rangeMax]} to the
     * destination range {@code [destMin, destMax]}. Float-based to avoid the throwaway
     * {@link Vector2f} allocations the per-shape variants used for their (constant) ranges.
     */
    static float mapValueToRange(float value, float rangeMin, float rangeMax, float destMin, float destMax) {
        return (value - rangeMin) * ((destMax - destMin) / (rangeMax - rangeMin)) + destMin;
    }
}

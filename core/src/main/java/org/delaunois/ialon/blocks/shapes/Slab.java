package org.delaunois.ialon.blocks.shapes;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import org.delaunois.ialon.blocks.BlockNeighborhood;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkMesh;
import org.delaunois.ialon.blocks.Direction;
import org.delaunois.ialon.blocks.Shape;
import com.simsilica.mathd.Vec3i;

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * A shape implementation for a slab. A slab is actual a cube shape with a controllable y (height) value. If you specify
 * a starting y value of 0 and an end y value of 1, you have a unit cube shape.
 * Only 4 vertices are used per face, 2 vertices are shared.
 *
 * @author rvandoosselaer
 */
@Slf4j
@ToString
public class Slab implements Shape {

    // Local (signX, signZ) of the 4 vertices, in the exact order createUp()/createDown() emit them.
    // Used to map each emitted vertex to the neighbour cells that occlude it (smooth top/bottom AO).
    private static final int[] UP_CORNERS = {1, -1, -1, -1, 1, 1, -1, 1};
    private static final int[] DOWN_CORNERS = {-1, -1, 1, -1, -1, 1, 1, 1};

    protected final float startY;
    protected final float endY;
    protected final Direction direction;

    public Slab(float startY, float endY) {
        this(startY, endY, Direction.UP);
    }

    public Slab(float startY, float endY, Direction direction) {
        if (startY < 0 || startY > 1 || endY < 0 || endY > 1 || startY > endY) {
            endY = FastMath.clamp(endY, 0, 1);
            startY = Math.min(endY, FastMath.clamp(startY, 0, 1));
            log.warn("Invalid height values specified. Normalized values to: start y: {}, end y: {}.", startY, endY);
        }
        this.startY = startY - 0.5f;
        this.endY = endY - 0.5f;
        this.direction = direction;
    }

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        add(location, chunk, chunkMesh, null);
    }

    @Override
    public void add(BlockNeighborhood neighborhood, ChunkMesh chunkMesh) {
        add(neighborhood.getLocation(), neighborhood.getChunk(), chunkMesh, neighborhood);
    }

    private void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh, BlockNeighborhood neighborhood) {
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        // check if we have 3 textures or only one
        boolean multipleImages = chunk.getBlock(location.x, location.y, location.z).isUsingMultipleImages();
        // get the rotation of the shape based on the direction
        Quaternion rotation = Shape.getRotationFromDirection(direction);

        // Smooth (ambient-occlusion) lighting is only applied to the top and bottom faces, and only
        // when those faces are actually horizontal in world space, i.e. for UP/DOWN-oriented slabs.
        // For the side orientations the createUp()/createDown() faces point sideways, so they keep the
        // flat per-face light. The collision mesh (no neighborhood) also keeps the flat path.
        boolean softTopBottom = neighborhood != null && !chunkMesh.isCollisionMesh()
                && (direction == Direction.UP || direction == Direction.DOWN);

        Direction faceUp = Shape.getFaceDirection(Direction.UP, direction);
        Direction faceDown = Shape.getFaceDirection(Direction.DOWN, direction);

        if (endY < 0.5f || chunk.isFaceVisible(location, faceUp)) {
            createUp(location, rotation, chunkMesh, blockScale, multipleImages);
            Direction face = endY < 0.5f ? null : faceUp;
            if (softTopBottom) {
                softShadowFace(neighborhood, Direction.UP, face, chunk, chunkMesh);
            } else {
                enlightFace(location, face, chunk, chunkMesh);
            }
        }
        if (startY > -0.5f || chunk.isFaceVisible(location, faceDown)) {
            createDown(location, rotation, chunkMesh, blockScale, multipleImages);
            Direction face = startY > -0.5f ? null : faceDown;
            if (softTopBottom) {
                softShadowFace(neighborhood, Direction.DOWN, face, chunk, chunkMesh);
            } else {
                enlightFace(location, face, chunk, chunkMesh);
            }
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.WEST, direction))) {
            createWest(location, rotation, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.WEST, direction), chunk, chunkMesh);
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.EAST, direction))) {
            createEast(location, rotation, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.EAST, direction), chunk, chunkMesh);
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.SOUTH, direction))) {
            createSouth(location, rotation, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.SOUTH, direction), chunk, chunkMesh);
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.NORTH, direction))) {
            createNorth(location, rotation, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.NORTH, direction), chunk, chunkMesh);
        }
    }

    public boolean fullyCoversFace(Direction direction) {
        switch (Shape.getOppositeYawFaceDirection(direction, this.direction)) {
            case DOWN:
                return this.startY == -0.5f;
            case UP:
                return this.startY == 0.5f;
            default:
                return false;
        }
    }

    private void enlightFace(Vec3i location, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location, face);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
        chunkMesh.getColors().add(color);
    }

    /**
     * Per-vertex smooth lighting (ambient occlusion) for the horizontal top/bottom face of an
     * UP/DOWN-oriented slab. Each of the 4 vertices is darkened by averaging the "looked-into" light
     * with the 3 neighbour cells that touch its corner, exactly like {@code Cube} does, so slabs blend
     * seamlessly with full cubes.
     *
     * @param localFace the face being emitted, {@link Direction#UP} (createUp) or {@link Direction#DOWN} (createDown)
     * @param face      the world neighbour the face looks into, or {@code null} when the face lies inside
     *                  the cell (slab surface not reaching the cell boundary) : the own-cell light is used
     *                  and the occluders are sampled at the slab's own level
     */
    private void softShadowFace(BlockNeighborhood n, Direction localFace, Direction face, Chunk chunk, ChunkMesh chunkMesh) {
        // Light the face looks into : the neighbour cell when the surface is flush with the cell
        // boundary, the slab's own cell when the surface lies inside the cell.
        Vector4f color = (face == null) ? n.getSelfLight() : n.getFaceLight(face);
        // Occluder layer relative to the slab : +1 above a flush top, -1 below a flush bottom,
        // and the same level (0) for a surface that lies inside the cell.
        int dy = (face == null) ? 0 : face.getVector().y;

        int[] corners = (localFace == Direction.UP) ? UP_CORNERS : DOWN_CORNERS;
        Vector4f store = n.getColorScratch();
        for (int i = 0; i < corners.length; i += 2) {
            int sx = corners[i];
            // A DOWN-oriented slab is the UP geometry flipped 180° around X : world Z of every vertex
            // is negated, so sample the mirrored neighbour corner.
            int sz = (direction == Direction.DOWN) ? -corners[i + 1] : corners[i + 1];
            chunk.vertexColor(n.neighbourLight(sx, dy, 0), n.neighbourLight(0, dy, sz), n.neighbourLight(sx, dy, sz), color, store);
            chunkMesh.getColors().add(store);
        }
    }

    protected void createNorth(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, endY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, endY, -0.5f)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING ));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
            }
        }
    }

    protected void createSouth(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, endY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, 0.5f)), location, blockScale));
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
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(0.0f, 0.0f, 1.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING ));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING ));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
            }
        }
    }

    protected void createEast(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, endY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, endY, 0.5f)), location, blockScale));
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
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(0.0f + UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
            }
        }
    }

    protected void createWest(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, endY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, endY, -0.5f)), location, blockScale));
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
                chunkMesh.getNormals().add(rotation.mult(new Vector3f(-1.0f, 0.0f, 0.0f)));
            }
            // uvs
            if (!multipleImages) {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, (startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, (endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING));
            } else {
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(1.0f - UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, mapValueToRange((startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
                chunkMesh.getUvs().add(new Vector2f(UV_PADDING, mapValueToRange((endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING, new Vector2f(0, 1), new Vector2f(1f / 3f, 2f / 3f))));
            }
        }
    }

    protected void createDown(Vec3i location, Quaternion rotation, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, -0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(-0.5f, startY, 0.5f)), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(rotation.mult(new Vector3f(0.5f, startY, 0.5f)), location, blockScale));
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

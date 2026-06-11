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
 * <p>
 * Since {@code startY}, {@code endY} and {@code direction} are fixed per instance (shapes are shared
 * singletons), the rotated face normals, the 8 corner vertices and the UV coordinates are all
 * precomputed once in the constructor. The per-face geometry is then emitted through the shared
 * {@link Shape#emitQuad} helper, which avoids allocating a {@link Vector3f}/{@link Vector2f} per vertex
 * in the meshing loop.
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

    // UV coordinates of the top/bottom caps : they don't depend on startY/endY, so they are shared.
    private static final Vector2f[] UV_UP_SINGLE = {
            new Vector2f(1f - UV_PADDING, 1f - UV_PADDING), new Vector2f(UV_PADDING, 1f - UV_PADDING),
            new Vector2f(1f - UV_PADDING, UV_PADDING), new Vector2f(UV_PADDING, UV_PADDING)
    };
    private static final Vector2f[] UV_UP_MULTI = {
            new Vector2f(1f - UV_PADDING, 1f - UV_PADDING), new Vector2f(UV_PADDING, 1f - UV_PADDING),
            new Vector2f(1f - UV_PADDING, 2f / 3f + UV_PADDING), new Vector2f(UV_PADDING, 2f / 3f + UV_PADDING)
    };
    private static final Vector2f[] UV_DOWN_SINGLE = {
            new Vector2f(UV_PADDING, UV_PADDING), new Vector2f(1f - UV_PADDING, UV_PADDING),
            new Vector2f(UV_PADDING, 1f - UV_PADDING), new Vector2f(1f - UV_PADDING, 1f - UV_PADDING)
    };
    private static final Vector2f[] UV_DOWN_MULTI = {
            new Vector2f(UV_PADDING, UV_PADDING), new Vector2f(1f - UV_PADDING, UV_PADDING),
            new Vector2f(UV_PADDING, 1f / 3f + UV_PADDING), new Vector2f(1f - UV_PADDING, 1f / 3f + UV_PADDING)
    };

    protected final float startY;
    protected final float endY;
    protected final Direction direction;

    // Rotation applied to the local geometry. emitRotation is null for an UP slab (identity rotation),
    // so emitQuad can skip the per-vertex quaternion multiply for the most common orientation.
    // protected : reused by the SquareCuboid subclass.
    protected final Quaternion rotation;
    protected final Quaternion emitRotation;

    // Precomputed, world-oriented face normals (rotated once instead of 4x per face). protected : reused
    // by the SquareCuboid subclass.
    protected final Vector3f nUp;
    protected final Vector3f nDown;
    protected final Vector3f nNorth;
    protected final Vector3f nSouth;
    protected final Vector3f nEast;
    protected final Vector3f nWest;

    // Precomputed local corner vertices : b* on the bottom plane (startY), t* on the top plane (endY),
    // named by the sign of (x, z) : n = -0.5, p = +0.5.
    private final Vector3f bNN;
    private final Vector3f bNP;
    private final Vector3f bPN;
    private final Vector3f bPP;
    private final Vector3f tNN;
    private final Vector3f tNP;
    private final Vector3f tPN;
    private final Vector3f tPP;

    // Precomputed side UVs (the 4 N/S/E/W faces share the same layout, which depends on startY/endY).
    private final Vector2f[] uvSideSingle;
    private final Vector2f[] uvSideMulti;

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

        this.rotation = Shape.getRotationFromDirection(direction);
        this.emitRotation = direction == Direction.UP ? null : rotation;

        this.nUp = rotation.mult(new Vector3f(0f, 1f, 0f));
        this.nDown = rotation.mult(new Vector3f(0f, -1f, 0f));
        this.nNorth = rotation.mult(new Vector3f(0f, 0f, -1f));
        this.nSouth = rotation.mult(new Vector3f(0f, 0f, 1f));
        this.nEast = rotation.mult(new Vector3f(1f, 0f, 0f));
        this.nWest = rotation.mult(new Vector3f(-1f, 0f, 0f));

        this.bNN = new Vector3f(-0.5f, this.startY, -0.5f);
        this.bNP = new Vector3f(-0.5f, this.startY, 0.5f);
        this.bPN = new Vector3f(0.5f, this.startY, -0.5f);
        this.bPP = new Vector3f(0.5f, this.startY, 0.5f);
        this.tNN = new Vector3f(-0.5f, this.endY, -0.5f);
        this.tNP = new Vector3f(-0.5f, this.endY, 0.5f);
        this.tPN = new Vector3f(0.5f, this.endY, -0.5f);
        this.tPP = new Vector3f(0.5f, this.endY, 0.5f);

        float vStart = (this.startY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING;
        float vEnd = (this.endY + 0.5f) / UV_PADDING_FACTOR + UV_PADDING;
        this.uvSideSingle = new Vector2f[]{
                new Vector2f(1f - UV_PADDING, vStart), new Vector2f(1f - UV_PADDING, vEnd),
                new Vector2f(UV_PADDING, vStart), new Vector2f(UV_PADDING, vEnd)
        };
        float vStartM = Shape.mapValueToRange(vStart, 0f, 1f, 1f / 3f, 2f / 3f);
        float vEndM = Shape.mapValueToRange(vEnd, 0f, 1f, 1f / 3f, 2f / 3f);
        this.uvSideMulti = new Vector2f[]{
                new Vector2f(1f - UV_PADDING, vStartM), new Vector2f(1f - UV_PADDING, vEndM),
                new Vector2f(UV_PADDING, vStartM), new Vector2f(UV_PADDING, vEndM)
        };
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

        // Smooth (ambient-occlusion) lighting is only applied to the top and bottom faces, and only
        // when those faces are actually horizontal in world space, i.e. for UP/DOWN-oriented slabs.
        // For the side orientations the createUp()/createDown() faces point sideways, so they keep the
        // flat per-face light. The collision mesh (no neighborhood) also keeps the flat path.
        boolean softTopBottom = neighborhood != null && !chunkMesh.isCollisionMesh()
                && (direction == Direction.UP || direction == Direction.DOWN);

        Direction faceUp = Shape.getFaceDirection(Direction.UP, direction);
        Direction faceDown = Shape.getFaceDirection(Direction.DOWN, direction);

        if (endY < 0.5f || chunk.isFaceVisible(location, faceUp)) {
            createUp(location, chunkMesh, blockScale, multipleImages);
            Direction face = endY < 0.5f ? null : faceUp;
            if (softTopBottom) {
                softShadowFace(neighborhood, Direction.UP, face, chunk, chunkMesh);
            } else {
                enlightFace(location, face, chunk, chunkMesh);
            }
        }
        if (startY > -0.5f || chunk.isFaceVisible(location, faceDown)) {
            createDown(location, chunkMesh, blockScale, multipleImages);
            Direction face = startY > -0.5f ? null : faceDown;
            if (softTopBottom) {
                softShadowFace(neighborhood, Direction.DOWN, face, chunk, chunkMesh);
            } else {
                enlightFace(location, face, chunk, chunkMesh);
            }
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.WEST, direction))) {
            createWest(location, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.WEST, direction), chunk, chunkMesh);
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.EAST, direction))) {
            createEast(location, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.EAST, direction), chunk, chunkMesh);
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.SOUTH, direction))) {
            createSouth(location, chunkMesh, blockScale, multipleImages);
            enlightFace(location, Shape.getFaceDirection(Direction.SOUTH, direction), chunk, chunkMesh);
        }
        if (chunk.isFaceVisible(location, Shape.getFaceDirection(Direction.NORTH, direction))) {
            createNorth(location, chunkMesh, blockScale, multipleImages);
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

    protected void createNorth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bNN, tNN, bPN, tPN,
                nNorth, multipleImages ? uvSideMulti : uvSideSingle, false, chunkMesh.isCollisionMesh());
    }

    protected void createSouth(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bPP, tPP, bNP, tNP,
                nSouth, multipleImages ? uvSideMulti : uvSideSingle, false, chunkMesh.isCollisionMesh());
    }

    protected void createEast(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bPN, tPN, bPP, tPP,
                nEast, multipleImages ? uvSideMulti : uvSideSingle, false, chunkMesh.isCollisionMesh());
    }

    protected void createWest(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bNP, tNP, bNN, tNN,
                nWest, multipleImages ? uvSideMulti : uvSideSingle, false, chunkMesh.isCollisionMesh());
    }

    protected void createDown(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                bNN, bPN, bNP, bPP,
                nDown, multipleImages ? UV_DOWN_MULTI : UV_DOWN_SINGLE, false, chunkMesh.isCollisionMesh());
    }

    protected void createUp(Vec3i location, ChunkMesh chunkMesh, float blockScale, boolean multipleImages) {
        Shape.emitQuad(chunkMesh, location, blockScale, emitRotation,
                tPN, tNN, tPP, tNP,
                nUp, multipleImages ? UV_UP_MULTI : UV_UP_SINGLE, false, chunkMesh.isCollisionMesh());
    }

}

/*
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon;

import com.jme3.math.Vector4f;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.Direction;
import com.simsilica.mathd.Vec3i;

import java.util.Arrays;

import lombok.Getter;

/**
 * The 3x3x3 block neighborhood centered on a block, used during chunk meshing to provide both the
 * surrounding blocks (face visibility) and their light levels (smooth per-vertex lighting).
 *
 * <p>Each slot of the neighborhood is identified by an index derived from its offset relative to the
 * center : {@code index = CENTER_IDX + dx + dz * SIDE_SIZE + dy * SLICE_SIZE}. The selection of the
 * 8 blocks/lights surrounding a given face is therefore fully described by a table of
 * {@code (dx, dy, dz)} offsets (see the {@code OFF_*} constants) rather than hard-coded index lists.
 *
 * <pre>
 *  Bottom     Middle      Top            Y ---> X
 * 00 01 02   09 10 11   18 19 20         |
 * 03 04 05   12 13 14   21 22 23         v
 * 06 07 08   15 16 17   24 25 26         Z
 * </pre>
 *
 * <p>The block and light buffers are reused across blocks. Per-block invalidation is O(1) : a slot
 * is considered up-to-date when its stamp equals the current {@code epoch}, which is bumped on each
 * {@link #setLocation(Vec3i)} instead of clearing the whole arrays.
 *
 * <p>The neighborhood is confined to a single meshing thread, so these buffers can be pre-allocated
 * once and refilled rather than re-allocated per block.
 */
public class BlockNeighborhood {

    private static final String ILLEGAL_DIRECTION_ERROR = "Illegal Direction";
    private static final Block EMPTY = new Block();
    private static final int SIDE_SIZE = 3;
    private static final int SLICE_SIZE = SIDE_SIZE * SIDE_SIZE;
    private static final int NEIGHB_SIZE = SIDE_SIZE * SIDE_SIZE * SIDE_SIZE;
    private static final int CENTER_IDX = (NEIGHB_SIZE - 1) / 2;

    // Offset tables : 8 (dx, dy, dz) triples (flattened) describing the blocks/lights surrounding a
    // face, in winding order. Block neighbours and light neighbours of a face share the same offsets.
    private static final int[] OFF_FACE = {
            1, 0, -1, 0, 0, -1, -1, 0, -1, -1, 0, 0, -1, 0, 1, 0, 0, 1, 1, 0, 1, 1, 0, 0};
    private static final int[] OFF_UP = {
            1, 1, -1, 0, 1, -1, -1, 1, -1, -1, 1, 0, -1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0};
    private static final int[] OFF_DOWN = {
            -1, -1, -1, 0, -1, -1, 1, -1, -1, 1, -1, 0, 1, -1, 1, 0, -1, 1, -1, -1, 1, -1, -1, 0};
    private static final int[] OFF_NORTH = {
            -1, -1, -1, -1, 0, -1, -1, 1, -1, 0, 1, -1, 1, 1, -1, 1, 0, -1, 1, -1, -1, 0, -1, -1};
    private static final int[] OFF_SOUTH = {
            1, -1, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, -1, 1, 1, -1, 0, 1, -1, -1, 1, 0, -1, 1};
    private static final int[] OFF_WEST = {
            -1, -1, 1, -1, 0, 1, -1, 1, 1, -1, 1, 0, -1, 1, -1, -1, 0, -1, -1, -1, -1, -1, -1, 0};
    private static final int[] OFF_EAST = {
            1, -1, -1, 1, 0, -1, 1, 1, -1, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, -1, 1, 1, -1, 0};

    // Wedge light offset tables, per face and per upside-down flag.
    private static final int[] OFF_WEDGE_NORTH = {
            -1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 0, 0, 1, -1, -1, 0, -1, -1, -1, -1, -1, -1, 0, 0};
    private static final int[] OFF_WEDGE_NORTH_UD = {
            1, -1, 1, 0, -1, 1, -1, -1, 1, -1, 0, 0, -1, 1, -1, 0, 1, -1, 1, 1, -1, 1, 0, 0};
    private static final int[] OFF_WEDGE_SOUTH = {
            1, 1, -1, 0, 1, -1, -1, 1, -1, -1, 0, 0, -1, -1, 1, 0, -1, 1, 1, -1, 1, 1, 0, 0};
    private static final int[] OFF_WEDGE_SOUTH_UD = {
            -1, -1, -1, 0, -1, -1, 1, -1, -1, 1, 0, 0, 1, 1, 1, 0, 1, 1, -1, 1, 1, -1, 0, 0};
    private static final int[] OFF_WEDGE_WEST = {
            1, 1, 1, 1, 1, 0, 1, 1, -1, 0, 0, -1, -1, -1, -1, -1, -1, 0, -1, -1, 1, 0, 0, 1};
    private static final int[] OFF_WEDGE_WEST_UD = {
            1, -1, -1, 1, -1, 0, 1, -1, 1, 0, 0, 1, -1, 1, 1, -1, 1, 0, -1, 1, -1, 0, 0, -1};
    private static final int[] OFF_WEDGE_EAST = {
            -1, 1, -1, -1, 1, 0, -1, 1, 1, 0, 0, 1, 1, -1, 1, 1, -1, 0, 1, -1, -1, 0, 0, -1};
    private static final int[] OFF_WEDGE_EAST_UD = {
            -1, -1, 1, -1, -1, 0, -1, -1, -1, 0, 0, -1, 1, 1, -1, 1, 1, 0, 1, 1, 1, 0, 0, 1};

    @Getter
    // Location of the center block
    private Vec3i location;

    @Getter
    // Chunk of the center block
    private final Chunk chunk;

    // The neighborhood : all blocks surrounding the location (lazily filled, see get()).
    private final Block[] n = new Block[NEIGHB_SIZE];
    private final Block[] face = new Block[8];
    // Light buffers reused across blocks/faces.
    private final Vector4f[] lights = new Vector4f[NEIGHB_SIZE];
    private final Vector4f[] facelights = new Vector4f[8];
    private final Vector4f[] tmp = new Vector4f[8];
    // O(1) per-block cache invalidation : a slot is valid iff its stamp equals the current epoch.
    private final int[] blockStamp = new int[NEIGHB_SIZE];
    private final int[] lightStamp = new int[NEIGHB_SIZE];
    private int epoch;
    // Scratch buffers for the center face light and the per-vertex output color.
    private final Vector4f faceLightScratch = new Vector4f();
    /**
     * -- GETTER --
     *  A reused buffer for the per-vertex output color. Safe because consumers copy the components
     *  into the mesh color buffer immediately after each use.
     */
    @Getter
    private final Vector4f colorScratch = new Vector4f();

    // Per-face visibility of the center block, recorded by Chunk.isFaceVisible(neighborhood, dir)
    // during the render pass so the collision mesher can reuse it (shared visibility mask). Always
    // overwritten for the 6 faces before being read, so it needs no epoch validation.
    private final boolean[] faceVisible = new boolean[Direction.values().length];

    public BlockNeighborhood(Vec3i location, Chunk chunk) {
        this.chunk = chunk;
        for (int i = 0; i < NEIGHB_SIZE; i++) {
            lights[i] = new Vector4f();
        }
        setLocation(location);
    }

    public void setLocation(Vec3i location) {
        // Bump the epoch to invalidate every cached slot in O(1). Reset the stamps on the (extremely
        // rare) wrap-around so a stale stamp can never collide with the new epoch.
        if (++epoch == Integer.MAX_VALUE) {
            Arrays.fill(blockStamp, 0);
            Arrays.fill(lightStamp, 0);
            epoch = 1;
        }
        this.location = location;
        n[CENTER_IDX] = this.chunk.getBlock(location.x, location.y, location.z);
        blockStamp[CENTER_IDX] = epoch;
    }

    /**
     * Light of the block adjacent to the center in the given face direction (water tint applied),
     * written into a reused scratch buffer. Equivalent to {@code chunk.getLightLevel(location, face)}
     * but allocation-free.
     */
    public Vector4f getFaceLight(Direction face) {
        return chunk.getLightLevel(location.x, location.y, location.z, face, faceLightScratch);
    }

    public Block getCenterBlock() {
        return n[CENTER_IDX];
    }

    public void setFaceVisible(Direction direction, boolean visible) {
        faceVisible[direction.ordinal()] = visible;
    }

    public boolean isFaceVisible(Direction direction) {
        return faceVisible[direction.ordinal()];
    }

    public Block[] getNeighbours() {
        return fillBlocks(OFF_FACE, face);
    }

    public Block[] getNeighbours(Block[] store) {
        return fillBlocks(OFF_FACE, store);
    }

    public Vector4f[] getNeighbourLights(Vector4f[] store) {
        return fillLights(OFF_FACE, store);
    }

    public Block[] getNeighbours(Direction direction) {
        return getNeighbours(direction, null);
    }

    public Block[] getNeighbours(Direction direction, Block[] store) {
        return fillBlocks(faceOffsets(direction), store == null ? face : store);
    }

    public Vector4f[] getNeighbourLights(Direction direction) {
        return getNeighbourLights(Direction.SOUTH, direction, null, false);
    }

    public Vector4f[] getNeighbourLights(Direction orientation, Direction face, Vector4f[] store, boolean upsideDown) {
        if (store == null) {
            store = facelights;
        }
        fillLights(faceOffsets(face), store);
        if (face == Direction.UP || face == Direction.DOWN) {
            yawFace(orientation, store, upsideDown);
        } else if (upsideDown) {
            rotate(store, 4);
        }
        return store;
    }

    public Vector4f[] getNeighbourWedgeLights(Direction direction, Vector4f[] store, boolean upsideDown) {
        return fillLights(wedgeOffsets(direction, upsideDown), store == null ? facelights : store);
    }

    public Block getNeighbour(Direction direction) {
        Vec3i dir = direction.getVector();
        return getOff(dir.x, dir.y, dir.z);
    }

    private static int[] faceOffsets(Direction face) {
        return switch (face) {
            case UP -> OFF_UP;
            case DOWN -> OFF_DOWN;
            case EAST -> OFF_EAST;
            case WEST -> OFF_WEST;
            case NORTH -> OFF_NORTH;
            case SOUTH -> OFF_SOUTH;
        };
    }

    private static int[] wedgeOffsets(Direction direction, boolean upsideDown) {
        return switch (direction) {
            case EAST -> upsideDown ? OFF_WEDGE_EAST_UD : OFF_WEDGE_EAST;
            case WEST -> upsideDown ? OFF_WEDGE_WEST_UD : OFF_WEDGE_WEST;
            case NORTH -> upsideDown ? OFF_WEDGE_NORTH_UD : OFF_WEDGE_NORTH;
            case SOUTH -> upsideDown ? OFF_WEDGE_SOUTH_UD : OFF_WEDGE_SOUTH;
            default -> throw new IllegalArgumentException(ILLEGAL_DIRECTION_ERROR);
        };
    }

    private Block[] fillBlocks(int[] offsets, Block[] store) {
        for (int i = 0, j = 0; i < 8; i++, j += 3) {
            store[i] = getOff(offsets[j], offsets[j + 1], offsets[j + 2]);
        }
        return store;
    }

    private Vector4f[] fillLights(int[] offsets, Vector4f[] store) {
        for (int i = 0, j = 0; i < 8; i++, j += 3) {
            store[i] = getLightOff(offsets[j], offsets[j + 1], offsets[j + 2]);
        }
        return store;
    }

    private void rotate(Vector4f[] store, int positions) {
        int length = store.length;
        for (int i = 0; i < length; i++) {
            tmp[i] = store[(i + positions) % length];
        }

        System.arraycopy(tmp, 0, store, 0, length);
    }

    private void yawFace(Direction orientation, Vector4f[] store, boolean upsideDown) {
        switch (orientation) {
            case WEST:
                rotate(store, upsideDown ? 6 : 2);
                break;
            case EAST:
                rotate(store, upsideDown ? 2 : 6);
                break;
            case SOUTH:
                rotate(store, 0);
                break;
            case NORTH:
                rotate(store, 4);
                break;
            default:
                throw new IllegalArgumentException("Illegal Orientation");
        }
    }

    private Block getOff(int dx, int dy, int dz) {
        return get(CENTER_IDX + dx + dz * SIDE_SIZE + dy * SLICE_SIZE, dx, dy, dz);
    }

    private Vector4f getLightOff(int dx, int dy, int dz) {
        return getLight(CENTER_IDX + dx + dz * SIDE_SIZE + dy * SLICE_SIZE, dx, dy, dz);
    }

    private Block get(int index, int dx, int dy, int dz) {
        if (blockStamp[index] != epoch) {
            Block cb = this.chunk.getNeighbour(location.x, location.y, location.z, dx, dy, dz);
            n[index] = cb == null ? EMPTY : cb;
            blockStamp[index] = epoch;
        }
        Block cb = n[index];
        return cb == EMPTY ? null : cb;
    }

    private Vector4f getLight(int index, int dx, int dy, int dz) {
        if (lightStamp[index] != epoch) {
            this.chunk.getLightLevel(location.x + dx, location.y + dy, location.z + dz, null, lights[index]);
            lightStamp[index] = epoch;
        }
        return lights[index];
    }
}

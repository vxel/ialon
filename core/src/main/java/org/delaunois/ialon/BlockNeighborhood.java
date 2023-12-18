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
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.Direction;
import com.simsilica.mathd.Vec3i;

import java.util.Arrays;

import lombok.Getter;

public class BlockNeighborhood {

    private static final Block EMPTY = new Block();
    private static final int SIDE_SIZE = 3;
    private static final int SLICE_SIZE = SIDE_SIZE * SIDE_SIZE;
    private static final int NEIGHB_SIZE = SIDE_SIZE * SIDE_SIZE * SIDE_SIZE;
    private static final int CENTER_IDX = (NEIGHB_SIZE - 1) / 2;

    @Getter
    // Location of the center block
    private Vec3i location;

    @Getter
    // Chunk of the center block
    private final Chunk chunk;

    // The neighborhood : all blocks surrounding the location
    // Order of blocks is :
    //  Bottom     Middle      Top            Y ---> X
    // 00 01 02   09 10 11   18 19 20         |
    // 03 04 05   12 13 14   21 22 23         v
    // 06 07 08   15 16 17   24 25 26         Z
    private final Block[] n = new Block[NEIGHB_SIZE];
    private final Block[] face = new Block[8];
    private final Vector4f[] lights = new Vector4f[NEIGHB_SIZE];
    private final Vector4f[] facelights = new Vector4f[8];
    private final Vector4f[] tmp = new Vector4f[8];

    public BlockNeighborhood(Vec3i location, Chunk chunk) {
        this.chunk = chunk;
        setLocation(location);
    }

    public void setLocation(Vec3i location) {
        Arrays.fill(n, null);
        Arrays.fill(lights, null);
        n[CENTER_IDX] = this.chunk.getBlock(location.x, location.y, location.z);

        this.location = location;
    }

    public Block getCenterBlock() {
        return n[CENTER_IDX];
    }

    public Block[] getNeighbours() {
        return getNeighbours(face);
    }

    public Block[] getNeighbours(Block[] store) {
        store[0] = get(11, 1, 0, -1);
        store[1] = get(10, 0, 0, -1);
        store[2] = get(9, -1, 0, -1);
        store[3] = get(12, -1, 0, 0);
        store[4] = get(15, -1, 0, 1);
        store[5] = get(16, 0, 0, 1);
        store[6] = get(17, 1, 0, 1);
        store[7] = get(14, 1, 0, 0);
        return store;
    }

    public Vector4f[] getNeighbourLights(Vector4f[] store) {
        store[0] = getLight(11, 1, 0, -1);
        store[1] = getLight(10, 0, 0, -1);
        store[2] = getLight(9, -1, 0, -1);
        store[3] = getLight(12, -1, 0, 0);
        store[4] = getLight(15, -1, 0, 1);
        store[5] = getLight(16, 0, 0, 1);
        store[6] = getLight(17, 1, 0, 1);
        store[7] = getLight(14, 1, 0, 0);
        return store;
    }

    public Block[] getNeighbours(Direction direction) {
        return getNeighbours(direction, null);
    }

    public Block[] getNeighbours(Direction direction, Block[] store) {
        if (store == null) {
            store = face;
        }
        switch (direction) {
            case UP:
                return getNeighboursUp(store);
            case DOWN:
                return getNeighboursDown(store);
            case EAST:
                return getNeighboursEast(store);
            case WEST:
                return getNeighboursWest(store);
            case NORTH:
                return getNeighboursNorth(store);
            case SOUTH:
                return getNeighboursSouth(store);
            default:
                throw new IllegalArgumentException("Illegal Direction");
        }
    }

    public Vector4f[] getNeighbourLights(Direction direction) {
        return getNeighbourLights(Direction.SOUTH, direction, null, false);
    }

    private void rotate(Vector4f[] store, int positions) {
        int length = store.length;
        for (int i = 0; i < length; i++) {
            tmp[i] = store[(i + positions) % length];
        }

        System.arraycopy(tmp, 0, store, 0, length);
    }

    private void invert(Vector4f[] store) {
        System.arraycopy(store, 0, tmp, 0, 8);
        store[0] = tmp[2];
        store[1] = tmp[1];
        store[2] = tmp[0];
        store[3] = tmp[7];
        store[4] = tmp[6];
        store[5] = tmp[5];
        store[6] = tmp[4];
        store[7] = tmp[3];
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

    public Vector4f[] getNeighbourLights(Direction orientation, Direction face, Vector4f[] store, boolean upsideDown) {
        if (store == null) {
            store = facelights;
        }
        switch (face) {
            case UP:
                getNeighbourLightsUp(store);
                if (upsideDown) {
                    //invert(store);
                }
                yawFace(orientation, store, upsideDown);
                break;
            case DOWN:
                getNeighbourLightsDown(store);
                if (upsideDown) {
                    //invert(store);
                }
                yawFace(orientation, store, upsideDown);
                break;
            case EAST:
                getNeighbourLightsEast(store);
                if (upsideDown) {
                    rotate(store, 4);
                }
                break;
            case WEST:
                getNeighbourLightsWest(store);
                if (upsideDown) {
                    rotate(store, 4);
                }
                break;
            case NORTH:
                getNeighbourLightsNorth(store);
                if (upsideDown) {
                    rotate(store, 4);
                }
                break;
            case SOUTH:
                getNeighbourLightsSouth(store);
                if (upsideDown) {
                    rotate(store, 4);
                }
                break;
            default:
                throw new IllegalArgumentException("Illegal Direction");
        }
        return store;
    }

    public Vector4f[] getNeighbourWedgeLights(Direction direction, Vector4f[] store, boolean upsideDown) {
        if (store == null) {
            store = facelights;
        }
        switch (direction) {
            case EAST:
                return getNeighbourWedgeLightsEast(store, upsideDown);
            case WEST:
                return getNeighbourWedgeLightsWest(store, upsideDown);
            case NORTH:
                return getNeighbourWedgeLightsNorth(store, upsideDown);
            case SOUTH:
                return getNeighbourWedgeLightsSouth(store, upsideDown);
            default:
                throw new IllegalArgumentException("Illegal Direction");
        }
    }

    public Block[] getNeighboursUp(Block[] store) {
        store[0] = get(20, 1, 1, -1);
        store[1] = get(19, 0, 1, -1);
        store[2] = get(18, -1, 1, -1);
        store[3] = get(21, -1, 1, 0);
        store[4] = get(24, -1, 1, 1);
        store[5] = get(25, 0, 1, 1);
        store[6] = get(26, 1, 1, 1);
        store[7] = get(23, 1, 1, 0);
        return store;
    }

    public Vector4f[] getNeighbourLightsUp(Vector4f[] store) {
        store[0] = getLight(20, 1, 1, -1);
        store[1] = getLight(19, 0, 1, -1);
        store[2] = getLight(18, -1, 1, -1);
        store[3] = getLight(21, -1, 1, 0);
        store[4] = getLight(24, -1, 1, 1);
        store[5] = getLight(25, 0, 1, 1);
        store[6] = getLight(26, 1, 1, 1);
        store[7] = getLight(23, 1, 1, 0);
        return store;
    }

    //  Bottom     Middle      Top            Y ---> X
    // 00 01 02   09 10 11   18 19 20         |
    // 03 04 05   12 13 14   21 22 23         v
    // 06 07 08   15 16 17   24 25 26         Z
    public Block[] getNeighboursDown(Block[] store) {
        store[0] = get(0, -1, -1, -1);
        store[1] = get(1, 0, -1, -1);
        store[2] = get(2, 1, -1, -1);
        store[3] = get(5, 1, -1, 0);
        store[4] = get(8, 1, -1, 1);
        store[5] = get(7, 0, -1, 1);
        store[6] = get(6, -1, -1, 1);
        store[7] = get(3, -1, -1, 0);
        return store;
    }

    public Vector4f[] getNeighbourLightsDown(Vector4f[] store) {
        store[0] = getLight(0, -1, -1, -1);
        store[1] = getLight(1, 0, -1, -1);
        store[2] = getLight(2, 1, -1, -1);
        store[3] = getLight(5, 1, -1, 0);
        store[4] = getLight(8, 1, -1, 1);
        store[5] = getLight(7, 0, -1, 1);
        store[6] = getLight(6, -1, -1, 1);
        store[7] = getLight(3, -1, -1, 0);
        return store;
    }

    public Block[] getNeighboursNorth(Block[] store) {
        store[0] = get(0, -1, -1, -1);
        store[1] = get(9, -1, 0, -1);
        store[2] = get(18, -1, 1, -1);
        store[3] = get(19, 0, 1, -1);
        store[4] = get(20, 1, 1, -1);
        store[5] = get(11, 1, 0, -1);
        store[6] = get(2, 1, -1, -1);
        store[7] = get(1, 0, -1, -1);
        return store;
    }

    public Vector4f[] getNeighbourLightsNorth(Vector4f[] store) {
        store[0] = getLight(0, -1, -1, -1);
        store[1] = getLight(9, -1, 0, -1);
        store[2] = getLight(18, -1, 1, -1);
        store[3] = getLight(19, 0, 1, -1);
        store[4] = getLight(20, 1, 1, -1);
        store[5] = getLight(11, 1, 0, -1);
        store[6] = getLight(2, 1, -1, -1);
        store[7] = getLight(1, 0, -1, -1);
        return store;
    }

    public Vector4f[] getNeighbourWedgeLightsNorth(Vector4f[] store, boolean upsideDown) {
        if (upsideDown) {
            store[0] = getLight(8, 1, -1, 1);
            store[1] = getLight(7, 0, -1, 1);
            store[2] = getLight(6, -1, -1, 1);
            store[3] = getLight(12, -1, 0, 0);
            store[4] = getLight(18, -1, 1, -1);
            store[5] = getLight(19, 0, 1, -1);
            store[6] = getLight(20, 1, 1, -1);
            store[7] = getLight(14, 1, 0, 0);

        } else {
            store[0] = getLight(24, -1, 1, 1);
            store[1] = getLight(25, 0, 1, 1);
            store[2] = getLight(26, 1, 1, 1);
            store[3] = getLight(14, 1, 0, 0);
            store[4] = getLight(2, 1, -1, -1);
            store[5] = getLight(1, 0, -1, -1);
            store[6] = getLight(0, -1, -1, -1);
            store[7] = getLight(12, -1, 0, 0);
        }
        return store;
    }

    public Block[] getNeighboursSouth(Block[] store) {
        store[0] = get(8, 1, -1, 1);
        store[1] = get(17, 1, 0, 1);
        store[2] = get(26, 1, 1, 1);
        store[3] = get(25, 0, 1, 1);
        store[4] = get(24, -1, 1, 1);
        store[5] = get(15, -1, 0, 1);
        store[6] = get(6, -1, -1, 1);
        store[7] = get(7, 0, -1, 1);
        return store;
    }

    public Vector4f[] getNeighbourLightsSouth(Vector4f[] store) {
        store[0] = getLight(8, 1, -1, 1);
        store[1] = getLight(17, 1, 0, 1);
        store[2] = getLight(26, 1, 1, 1);
        store[3] = getLight(25, 0, 1, 1);
        store[4] = getLight(24, -1, 1, 1);
        store[5] = getLight(15, -1, 0, 1);
        store[6] = getLight(6, -1, -1, 1);
        store[7] = getLight(7, 0, -1, 1);
        return store;
    }

    public Vector4f[] getNeighbourWedgeLightsSouth(Vector4f[] store, boolean upsideDown) {
        if (upsideDown) {
            store[0] = getLight(0, -1, -1, -1);
            store[1] = getLight(1, 0, -1, -1);
            store[2] = getLight(2, 1, -1, -1);
            store[3] = getLight(14, 1, 0, 0);
            store[4] = getLight(26, 1, 1, 1);
            store[5] = getLight(25, 0, 1, 1);
            store[6] = getLight(24, -1, 1, 1);
            store[7] = getLight(12, -1, 0, 0);

        } else {
            store[0] = getLight(20, 1, 1, -1);
            store[1] = getLight(19, 0, 1, -1);
            store[2] = getLight(18, -1, 1, -1);
            store[3] = getLight(12, -1, 0, 0);
            store[4] = getLight(6, -1, -1, 1);
            store[5] = getLight(7, 0, -1, 1);
            store[6] = getLight(8, 1, -1, 1);
            store[7] = getLight(14, 1, 0, 0);
        }
        return store;
    }

    public Block[] getNeighboursWest(Block[] store) {
        store[0] = get(6, -1, -1, 1);
        store[1] = get(15, -1, 0, 1);
        store[2] = get(24, -1, 1, 1);
        store[3] = get(21, -1, 1, 0);
        store[4] = get(18, -1, 1, -1);
        store[5] = get(9, -1, 0, -1);
        store[6] = get(0, -1, -1, -1);
        store[7] = get(3, -1, -1, 0);
        return store;
    }

    public Vector4f[] getNeighbourLightsWest(Vector4f[] store) {
        store[0] = getLight(6, -1, -1, 1);
        store[1] = getLight(15, -1, 0, 1);
        store[2] = getLight(24, -1, 1, 1);
        store[3] = getLight(21, -1, 1, 0);
        store[4] = getLight(18, -1, 1, -1);
        store[5] = getLight(9, -1, 0, -1);
        store[6] = getLight(0, -1, -1, -1);
        store[7] = getLight(3, -1, -1, 0);
        return store;
    }

    //  Bottom     Middle      Top            Y ---> X
    // 00 01 02   09 10 11   18 19 20         |
    // 03 04 05   12 13 14   21 22 23         v
    // 06 07 08   15 16 17   24 25 26         Z
    public Vector4f[] getNeighbourWedgeLightsWest(Vector4f[] store, boolean upsideDown) {
        if (upsideDown) {
            store[0] = getLight(2, 1, -1, -1);
            store[1] = getLight(5, 1, -1, 0);
            store[2] = getLight(8, 1, -1, 1);
            store[3] = getLight(16, 0, 0, 1);
            store[4] = getLight(24, -1, 1, 1);
            store[5] = getLight(21, -1, 1, 0);
            store[6] = getLight(18, -1, 1, -1);
            store[7] = getLight(10, 0, 0, -1);

        } else {
            store[0] = getLight(26, 1, 1, 1);
            store[1] = getLight(23, 1, 1, 0);
            store[2] = getLight(20, 1, 1, -1);
            store[3] = getLight(10, 0, 0, -1);
            store[4] = getLight(0, -1, -1, -1);
            store[5] = getLight(3, -1, -1, 0);
            store[6] = getLight(6, -1, -1, 1);
            store[7] = getLight(16, 0, 0, 1);
        }
        return store;
    }

    public Block[] getNeighboursEast(Block[] store) {
        store[0] = get(2, 1, -1, -1);
        store[1] = get(11, 1, 0, -1);
        store[2] = get(20, 1, 1, -1);
        store[3] = get(23, 1, 1, 0);
        store[4] = get(26, 1, 1, 1);
        store[5] = get(17, 1, 0, 1);
        store[6] = get(8, 1, -1, 1);
        store[7] = get(5, 1, -1, 0);
        return store;
    }

    public Vector4f[] getNeighbourLightsEast(Vector4f[] store) {
        store[0] = getLight(2, 1, -1, -1);
        store[1] = getLight(11, 1, 0, -1);
        store[2] = getLight(20, 1, 1, -1);
        store[3] = getLight(23, 1, 1, 0);
        store[4] = getLight(26, 1, 1, 1);
        store[5] = getLight(17, 1, 0, 1);
        store[6] = getLight(8, 1, -1, 1);
        store[7] = getLight(5, 1, -1, 0);
        return store;
    }

    public Vector4f[] getNeighbourWedgeLightsEast(Vector4f[] store, boolean upsideDown) {
        if (upsideDown) {
            store[0] = getLight(6, -1, -1, 1);
            store[1] = getLight(3, -1, -1, 0);
            store[2] = getLight(0, -1, -1, -1);
            store[3] = getLight(10, 0, 0, -1);
            store[4] = getLight(20, 1, 1, -1);
            store[5] = getLight(23, 1, 1, 0);
            store[6] = getLight(26, 1, 1, 1);
            store[7] = getLight(16, 0, 0, 1);

        } else {
            store[0] = getLight(18, -1, 1, -1);
            store[1] = getLight(21, -1, 1, 0);
            store[2] = getLight(24, -1, 1, 1);
            store[3] = getLight(16, 0, 0, 1);
            store[4] = getLight(8, 1, -1, 1);
            store[5] = getLight(5, 1, -1, 0);
            store[6] = getLight(2, 1, -1, -1);
            store[7] = getLight(10, 0, 0, -1);
        }
        return store;
    }

    public Block getNeighbour(Direction direction) {
        Vec3i dir = direction.getVector();
        return get(CENTER_IDX + dir.x + (dir.z * SIDE_SIZE) + (dir.y * SLICE_SIZE), dir.x, dir.y, dir.z);
    }

    public Vector4f getNeighbourLight(Direction direction) {
        Vec3i dir = direction.getVector();
        return getLight(CENTER_IDX + dir.x + (dir.z * SIDE_SIZE) + (dir.y * SLICE_SIZE), dir.x, dir.y, dir.z);
    }

    private Block get(int index, int dx, int dy, int dz) {
        Block cb = n[index];
        if (cb == null) {
            cb = this.chunk.getNeighbour(location.x, location.y, location.z, dx, dy, dz);
            n[index] = cb == null ? EMPTY : cb;
        }
        return cb == EMPTY ? null : cb;
    }

    private Vector4f getLight(int index, int dx, int dy, int dz) {
        Vector4f light = lights[index];
        if (light == null) {
            light = this.chunk.getLightLevel(location.x + dx, location.y + dy, location.z  + dz, null);
            lights[index] = light;
        }
        return light;
    }
}

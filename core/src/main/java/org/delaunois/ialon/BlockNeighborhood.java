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
        face[0] = get(11, 1, 0, -1);
        face[1] = get(10, 0, 0, -1);
        face[2] = get(9, -1, 0, -1);
        face[3] = get(12, -1, 0, 0);
        face[4] = get(15, -1, 0, 1);
        face[5] = get(16, 0, 0, 1);
        face[6] = get(17, 1, 0, 1);
        face[7] = get(14, 1, 0, 0);
        return face;
    }

    public Vector4f[] getNeighbourLights() {
        facelights[0] = getLight(11, 1, 0, -1);
        facelights[1] = getLight(10, 0, 0, -1);
        facelights[2] = getLight(9, -1, 0, -1);
        facelights[3] = getLight(12, -1, 0, 0);
        facelights[4] = getLight(15, -1, 0, 1);
        facelights[5] = getLight(16, 0, 0, 1);
        facelights[6] = getLight(17, 1, 0, 1);
        facelights[7] = getLight(14, 1, 0, 0);
        return facelights;
    }

    public Block[] getNeighbours(Direction direction) {
        switch (direction) {
            case UP:
                return getNeighboursUp();
            case DOWN:
                return getNeighboursDown();
            case EAST:
                return getNeighboursEast();
            case WEST:
                return getNeighboursWest();
            case NORTH:
                return getNeighboursNorth();
            case SOUTH:
                return getNeighboursSouth();
            default:
                throw new IllegalArgumentException("Illegal Direction");
        }
    }

    public Vector4f[] getNeighbourLights(Direction direction) {
        switch (direction) {
            case UP:
                return getNeighbourLightsUp();
            case DOWN:
                return getNeighbourLightsDown();
            case EAST:
                return getNeighbourLightsEast();
            case WEST:
                return getNeighbourLightsWest();
            case NORTH:
                return getNeighbourLightsNorth();
            case SOUTH:
                return getNeighbourLightsSouth();
            default:
                throw new IllegalArgumentException("Illegal Direction");
        }
    }

    public Block[] getNeighboursUp() {
        face[0] = get(20, 1, 1, -1);
        face[1] = get(19, 0, 1, -1);
        face[2] = get(18, -1, 1, -1);
        face[3] = get(21, -1, 1, 0);
        face[4] = get(24, -1, 1, 1);
        face[5] = get(25, 0, 1, 1);
        face[6] = get(26, 1, 1, 1);
        face[7] = get(23, 1, 1, 0);
        return face;
    }

    public Vector4f[] getNeighbourLightsUp() {
        facelights[0] = getLight(20, 1, 1, -1);
        facelights[1] = getLight(19, 0, 1, -1);
        facelights[2] = getLight(18, -1, 1, -1);
        facelights[3] = getLight(21, -1, 1, 0);
        facelights[4] = getLight(24, -1, 1, 1);
        facelights[5] = getLight(25, 0, 1, 1);
        facelights[6] = getLight(26, 1, 1, 1);
        facelights[7] = getLight(23, 1, 1, 0);
        return facelights;
    }

    //  Bottom     Middle      Top            Y ---> X
    // 00 01 02   09 10 11   18 19 20         |
    // 03 04 05   12 13 14   21 22 23         v
    // 06 07 08   15 16 17   24 25 26         Z
    public Block[] getNeighboursDown() {
        face[0] = get(0, -1, -1, -1);
        face[1] = get(1, 0, -1, -1);
        face[2] = get(2, 1, -1, -1);
        face[3] = get(5, 1, -1, 0);
        face[4] = get(8, 1, -1, 1);
        face[5] = get(7, 0, -1, 1);
        face[6] = get(6, -1, -1, 1);
        face[7] = get(3, -1, -1, 0);
        return face;
    }

    public Vector4f[] getNeighbourLightsDown() {
        facelights[0] = getLight(0, -1, -1, -1);
        facelights[1] = getLight(1, 0, -1, -1);
        facelights[2] = getLight(2, 1, -1, -1);
        facelights[3] = getLight(5, 1, -1, 0);
        facelights[4] = getLight(8, 1, -1, 1);
        facelights[5] = getLight(7, 0, -1, 1);
        facelights[6] = getLight(6, -1, -1, 1);
        facelights[7] = getLight(3, -1, -1, 0);
        return facelights;
    }

    public Block[] getNeighboursNorth() {
        face[0] = get(0, -1, -1, -1);
        face[1] = get(9, -1, 0, -1);
        face[2] = get(18, -1, 1, -1);
        face[3] = get(19, 0, 1, -1);
        face[4] = get(20, 1, 1, -1);
        face[5] = get(11, 1, 0, -1);
        face[6] = get(2, 1, -1, -1);
        face[7] = get(1, 0, -1, -1);
        return face;
    }

    public Vector4f[] getNeighbourLightsNorth() {
        facelights[0] = getLight(0, -1, -1, -1);
        facelights[1] = getLight(9, -1, 0, -1);
        facelights[2] = getLight(18, -1, 1, -1);
        facelights[3] = getLight(19, 0, 1, -1);
        facelights[4] = getLight(20, 1, 1, -1);
        facelights[5] = getLight(11, 1, 0, -1);
        facelights[6] = getLight(2, 1, -1, -1);
        facelights[7] = getLight(1, 0, -1, -1);
        return facelights;
    }

    public Block[] getNeighboursSouth() {
        face[0] = get(8, 1, -1, 1);
        face[1] = get(17, 1, 0, 1);
        face[2] = get(26, 1, 1, 1);
        face[3] = get(25, 0, 1, 1);
        face[4] = get(24, -1, 1, 1);
        face[5] = get(15, -1, 0, 1);
        face[6] = get(6, -1, -1, 1);
        face[7] = get(7, 0, -1, 1);
        return face;
    }

    public Vector4f[] getNeighbourLightsSouth() {
        facelights[0] = getLight(8, 1, -1, 1);
        facelights[1] = getLight(17, 1, 0, 1);
        facelights[2] = getLight(26, 1, 1, 1);
        facelights[3] = getLight(25, 0, 1, 1);
        facelights[4] = getLight(24, -1, 1, 1);
        facelights[5] = getLight(15, -1, 0, 1);
        facelights[6] = getLight(6, -1, -1, 1);
        facelights[7] = getLight(7, 0, -1, 1);
        return facelights;
    }

    public Block[] getNeighboursWest() {
        face[0] = get(6, -1, -1, 1);
        face[1] = get(15, -1, 0, 1);
        face[2] = get(24, -1, 1, 1);
        face[3] = get(21, -1, 1, 0);
        face[4] = get(18, -1, 1, -1);
        face[5] = get(9, -1, 0, -1);
        face[6] = get(0, -1, -1, -1);
        face[7] = get(3, -1, -1, 0);
        return face;
    }

    public Vector4f[] getNeighbourLightsWest() {
        facelights[0] = getLight(6, -1, -1, 1);
        facelights[1] = getLight(15, -1, 0, 1);
        facelights[2] = getLight(24, -1, 1, 1);
        facelights[3] = getLight(21, -1, 1, 0);
        facelights[4] = getLight(18, -1, 1, -1);
        facelights[5] = getLight(9, -1, 0, -1);
        facelights[6] = getLight(0, -1, -1, -1);
        facelights[7] = getLight(3, -1, -1, 0);
        return facelights;
    }

    public Block[] getNeighboursEast() {
        face[0] = get(2, 1, -1, -1);
        face[1] = get(11, 1, 0, -1);
        face[2] = get(20, 1, 1, -1);
        face[3] = get(23, 1, 1, 0);
        face[4] = get(26, 1, 1, 1);
        face[5] = get(17, 1, 0, 1);
        face[6] = get(8, 1, -1, 1);
        face[7] = get(5, 1, -1, 0);
        return face;
    }

    public Vector4f[] getNeighbourLightsEast() {
        facelights[0] = getLight(2, 1, -1, -1);
        facelights[1] = getLight(11, 1, 0, -1);
        facelights[2] = getLight(20, 1, 1, -1);
        facelights[3] = getLight(23, 1, 1, 0);
        facelights[4] = getLight(26, 1, 1, 1);
        facelights[5] = getLight(17, 1, 0, 1);
        facelights[6] = getLight(8, 1, -1, 1);
        facelights[7] = getLight(5, 1, -1, 0);
        return facelights;
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

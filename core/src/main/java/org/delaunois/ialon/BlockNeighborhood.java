package org.delaunois.ialon;

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

    public BlockNeighborhood(Vec3i location, Chunk chunk) {
        this.chunk = chunk;
        setLocation(location);
    }

    public void setLocation(Vec3i location) {
        Arrays.fill(n, null);
        n[CENTER_IDX] = this.chunk.getBlock(location.x, location.y, location.z);

        this.location = location;
    }

    public Block getCenterBlock() {
        return n[CENTER_IDX];
    }

    public Block[] getNeighbours() {
        return new Block[]{
                get(11, 1, 0, -1),
                get(10, 0, 0, -1),
                get(9, -1, 0, -1),
                get(12, -1, 0, 0),
                get(15, -1, 0, 1),
                get(16, 0, 0, 1),
                get(17, 1, 0, 1),
                get(14, 1, 0, 0),
        };
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
        }
        return null;
    }

    public Block[] getNeighboursUp() {
        return new Block[]{
                get(20, 1, 1, -1),
                get(19, 0, 1, -1),
                get(18, -1, 1, -1),
                get(21, -1, 1, 0),
                get(24, -1, 1, 1),
                get(25, 0, 1, 1),
                get(26, 1, 1, 1),
                get(23, 1, 1, 0),
        };
    }

    public Block[] getNeighboursDown() {
        return new Block[]{
                get(0, -1, -1, -1),
                get(1, 0, -1, -1),
                get(2, 1, -1, -1),
                get(5, 1, -1, 0),
                get(8, 1, -1, 1),
                get(7, 0, -1, 1),
                get(6, -1, -1, 1),
                get(3, -1, -1, 0),
        };
    }

    public Block[] getNeighboursNorth() {
        return new Block[]{
                get(0, -1, -1, -1),
                get(9, -1, 0, -1),
                get(18, -1, 1, -1),
                get(19, 0, 1, -1),
                get(20, 1, 1, -1),
                get(11, 1, 0, -1),
                get(2, 1, -1, -1),
                get(1, 0, -1, -1),
        };
    }

    public Block[] getNeighboursSouth() {
        return new Block[]{
                get(8, 1, -1, 1),
                get(17, 1, 0, 1),
                get(26, 1, 1, 1),
                get(25, 0, 1, 1),
                get(24, -1, 1, 1),
                get(15, -1, 0, 1),
                get(6, -1, -1, 1),
                get(7, 0, -1, 1),
        };
    }

    public Block[] getNeighboursWest() {
        return new Block[]{
                get(6, -1, -1, 1),
                get(15, -1, 0, 1),
                get(24, -1, 1, 1),
                get(21, -1, 1, 0),
                get(18, -1, 1, -1),
                get(9, -1, 0, -1),
                get(0, -1, -1, -1),
                get(3, -1, -1, 0),
        };
    }

    public Block[] getNeighboursEast() {
        return new Block[]{
                get(2, 1, -1, -1),
                get(11, 1, 0, -1),
                get(20, 1, 1, -1),
                get(23, 1, 1, 0),
                get(26, 1, 1, 1),
                get(17, 1, 0, 1),
                get(8, 1, -1, 1),
                get(5, 1, -1, 0),
        };
    }

    public Block getNeighbour(Direction direction) {
        Vec3i dir = direction.getVector();
        return resolve(CENTER_IDX + dir.x + (dir.z * SIDE_SIZE) + (dir.y * SLICE_SIZE), dir.x, dir.y, dir.z);
    }

    private Block get(int index, int dx, int dy, int dz) {
        return resolve(index, dx, dy, dz);
    }

    private Block resolve(int index, int dx, int dy, int dz) {
        Block cb = n[index];
        if (cb == null) {
            cb = this.chunk.getNeighbour(location.x, location.y, location.z, dx, dy, dz);
            n[index] = cb == null ? EMPTY : cb;
        }
        return cb == EMPTY ? null : cb;
    }
}

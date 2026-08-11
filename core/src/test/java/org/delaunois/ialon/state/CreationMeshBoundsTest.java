/**
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

package org.delaunois.ialon.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.delaunois.ialon.blocks.BlockIds;
import org.junit.jupiter.api.Test;

/**
 * Guards {@code CreationMesh.contentBounds}, which is what the card image is framed on : a creation is
 * captured as a <b>box</b> and stripping its ground only turns cells to air, so the grid's own extent says
 * little about where the blocks are.
 *
 * @author Cedric de Launois
 */
class CreationMeshBoundsTest {

    private static final int SX = 4;
    private static final int SY = 3;
    private static final int SZ = 5;

    @Test
    void boundsAreTheCellsThatHoldSomething() {
        String[] blocks = air();
        set(blocks, 1, 0, 2);
        set(blocks, 2, 2, 3);
        assertArrayEquals(new int[]{1, 0, 2, 2, 2, 3},
                CreationMesh.contentBounds(blocks, SX, SY, SZ));
    }

    @Test
    void aSingleBlockBoundsItself() {
        String[] blocks = air();
        set(blocks, 3, 1, 4);
        assertArrayEquals(new int[]{3, 1, 4, 3, 1, 4},
                CreationMesh.contentBounds(blocks, SX, SY, SZ));
    }

    @Test
    void aFullGridBoundsTheWholeGrid() {
        String[] blocks = air();
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = BlockIds.GRASS;
        }
        assertArrayEquals(new int[]{0, 0, 0, SX - 1, SY - 1, SZ - 1},
                CreationMesh.contentBounds(blocks, SX, SY, SZ));
    }

    /** Air, whichever way it is spelled : missing, empty or the air block's own id. */
    @Test
    void anEmptyCreationHasNoBounds() {
        String[] blocks = air();
        blocks[0] = null;
        blocks[1] = "";
        blocks[2] = BlockIds.NONE;
        assertNull(CreationMesh.contentBounds(blocks, SX, SY, SZ));
        assertNull(CreationMesh.contentBounds(null, SX, SY, SZ));
    }

    private static String[] air() {
        String[] blocks = new String[SX * SY * SZ];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = BlockIds.NONE;
        }
        return blocks;
    }

    /** The grid is row-major : {@code ((y * sizeZ) + z) * sizeX + x}. */
    private static void set(String[] blocks, int x, int y, int z) {
        blocks[((y * SZ) + z) * SX + x] = BlockIds.GRASS;
    }
}

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

package org.delaunois.ialon.serialize;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A reusable "creation" : a box-shaped capture of the world's blocks, defined by the player. Stored
 * globally (in {@code save/creations/}) so it can be copied into any world. These are GLOBAL artifacts,
 * unlike the per-world {@link WorldParams}.
 *
 * <p>Persisted by {@link CreationRepository} in the same compact format as chunks : a Protobuf
 * {@code ChunkProto} (size + block names) compressed in a ZIP. The {@link #blocks} grid is row-major :
 * index {@code ((y * sizeZ) + z) * sizeX + x}, with {@code x} the fastest-varying axis ; air cells use
 * {@code BlockIds.NONE}. When a creation is listed (not fully loaded) {@link #blocks} is {@code null}.</p>
 *
 * @author Cedric de Launois
 */
@Getter
@Setter
@NoArgsConstructor
public class Creation {

    private String id;
    private String name;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    /** Row-major block names ({@code BlockIds.NONE} = air), or {@code null} when only metadata is loaded. */
    private String[] blocks;

    /** Number of cells in the box ({@code sizeX * sizeY * sizeZ}). */
    public int volume() {
        return sizeX * sizeY * sizeZ;
    }
}

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

import com.jme3.scene.Node;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlockRegistry;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkResolver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Meshes a creation's block grid into a renderable jME {@link Node}, reusing the real block mesh
 * generator (so it looks exactly like in-world blocks). Shared by the placement ghost
 * ({@link CreationPlacementState}) and the offscreen thumbnail ({@link CreationPreview}).
 *
 * <p>The grid is row-major ({@code ((y * sizeZ) + z) * sizeX + x}). The node origin is the creation's
 * lower corner (block 0,0,0 at the origin). A creation larger than one chunk is tiled across several
 * temporary {@link Chunk}s (each positioned at its world location by the mesh generator), so any size is
 * supported. The creation is surrounded by a one-cell air margin and the temp chunks share a
 * {@link ChunkResolver}, so the mesh generator can sample a fully-sunlit air neighbour for every face of
 * the creation — including the faces on the min (0,0,0) side and at chunk seams, which would otherwise be
 * unlit (their neighbour cell lies outside the chunk). {@link #build} returns {@code null} for an all-air
 * grid. The returned node's origin is the creation's lower corner (the +1 margin offset is compensated
 * internally).</p>
 *
 * @author Cedric de Launois
 */
final class CreationMesh {

    private CreationMesh() {
        // Prevent instanciation
    }

    /** Whether a stored block name denotes air (empty / {@code BlockIds.NONE}). */
    static boolean isAir(String name) {
        return name == null || name.isEmpty() || BlockIds.NONE.equals(name);
    }

    /**
     * Builds the meshed node for the given grid (tiling over as many chunks as needed), or {@code null}
     * if the grid is entirely air. Internal faces at chunk seams are not culled across the temporary
     * chunks, but they are hidden by the abutting blocks, so the result looks solid.
     */
    static Node build(String[] blocks, int sizeX, int sizeY, int sizeZ) {
        if (blocks == null) {
            return null;
        }
        Vec3i cs = BlocksConfig.getInstance().getChunkSize();
        BlockRegistry registry = BlocksConfig.getInstance().getBlockRegistry();
        float scale = BlocksConfig.getInstance().getBlockScale();

        // Work in a padded space : creation block c sits at padded coord c+1, leaving a one-cell air
        // margin on every side (padded coords 0 and size+1). Every face of the creation then has an air
        // neighbour inside the temp chunks, which is fully sunlit, so no face is left unlit.
        int paddedX = sizeX + 2;
        int paddedY = sizeY + 2;
        int paddedZ = sizeZ + 2;
        int chunksX = (paddedX + cs.x - 1) / cs.x;
        int chunksY = (paddedY + cs.y - 1) / cs.y;
        int chunksZ = (paddedZ + cs.z - 1) / cs.z;

        // First pass : create every touched chunk (allocated → all-air + full sunlight), fill the creation
        // blocks, and remember which chunks actually hold blocks (only those are meshed).
        Map<Vec3i, Chunk> chunks = new HashMap<>();
        Set<Vec3i> meshable = new HashSet<>();
        for (int cx = 0; cx < chunksX; cx++) {
            for (int cy = 0; cy < chunksY; cy++) {
                for (int cz = 0; cz < chunksZ; cz++) {
                    Chunk chunk = Chunk.createAt(new Vec3i(cx, cy, cz));
                    chunk.setSunlight(0, 0, 0, 15); // forces allocate() : empty blocks + full-sunlight lightmap
                    boolean filled = false;
                    for (int lx = 0; lx < cs.x && cx * cs.x + lx < paddedX; lx++) {
                        int x = cx * cs.x + lx - 1; // padded -> creation coord
                        for (int ly = 0; ly < cs.y && cy * cs.y + ly < paddedY; ly++) {
                            int y = cy * cs.y + ly - 1;
                            for (int lz = 0; lz < cs.z && cz * cs.z + lz < paddedZ; lz++) {
                                int z = cz * cs.z + lz - 1;
                                if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
                                    continue; // air margin
                                }
                                String name = blocks[((y * sizeZ) + z) * sizeX + x];
                                if (!isAir(name)) {
                                    Block block = registry.get(name);
                                    if (block != null) {
                                        chunk.addBlock(lx, ly, lz, block);
                                        filled = true;
                                    }
                                }
                            }
                        }
                    }
                    chunks.put(chunk.getLocation(), chunk);
                    if (filled) {
                        meshable.add(chunk.getLocation());
                    }
                }
            }
        }
        if (meshable.isEmpty()) {
            return null;
        }

        // A resolver over ALL temp chunks (including the air-only margin chunks), so the mesh generator
        // samples neighbour blocks/light across chunk seams and on the creation's outer faces.
        ChunkResolver resolver = new ChunkResolver() {
            @Override
            public Optional<Chunk> get(Vec3i location) {
                return Optional.ofNullable(chunks.get(location));
            }

            @Override
            public Chunk unsafeFastGet(Vec3i location) {
                return chunks.get(location);
            }
        };

        // Second pass : mesh the block-bearing chunks now that neighbours are resolvable.
        Node result = new Node("CreationMesh");
        for (Vec3i location : meshable) {
            Chunk chunk = chunks.get(location);
            chunk.setChunkResolver(resolver);
            result.attachChild(BlocksConfig.getInstance().getChunkMeshGenerator().createNode(chunk));
        }
        // Undo the +1 padding offset so the node origin is the creation's lower corner (block 0 at 0).
        result.setLocalTranslation(-scale, -scale, -scale);
        return result;
    }
}

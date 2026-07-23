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

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlockRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventory and removal of block types inside a creation's grid.
 *
 * <p>Everything is keyed on the block <b>type</b> ({@link Block#getType()}), never on the block name : a
 * type is shared by every shape and every liquid level of a block family, so a single "grass" entry covers
 * {@code grass}, {@code grass-slab_up-0..7} and {@code grass-double_slab_up-0..7}, and a single "water"
 * entry covers {@code water_source} and {@code water-liquid_1..5}.</p>
 *
 * <p>The {@code terrain} flag of {@link Block} is carried by the CUBE form only (see
 * {@code IalonBlockCatalog}), so terrain-ness is resolved per type : a type is terrain as soon as ONE of
 * its registered blocks is flagged, which also marks its slabs and other shapes as terrain here. In the
 * shipped catalog that gives {@code grass, grass_snow, snow, sand, rock, dirt}.</p>
 *
 * @author Cedric de Launois
 */
final class CreationBlockFilter {

    private CreationBlockFilter() {
        // Prevent instanciation
    }

    /** A block type present in a creation : its cell count and whether it is natural ground. */
    static final class TypeEntry {

        private final String type;
        private final int count;
        private final boolean terrain;

        TypeEntry(String type, int count, boolean terrain) {
            this.type = type;
            this.count = count;
            this.terrain = terrain;
        }

        String getType() {
            return type;
        }

        int getCount() {
            return count;
        }

        boolean isTerrain() {
            return terrain;
        }
    }

    /**
     * The block types present in the grid, terrain types first (they are the ones the player usually wants
     * to strip), then by descending cell count, then by type name.
     * Air cells and names absent from the registry are ignored.
     * Returns an empty list for a {@code null} or all-air grid.
     *
     * @param limit keeps only the {@code limit} most frequent types (all of them when {@code <= 0}) : a
     *              creation mixing a great variety of blocks would otherwise give the dialog more rows
     *              than it can show. The cut is made on the cell count alone, before the terrain-first
     *              ordering, so a rare terrain type does not push out a frequent one.
     */
    static List<TypeEntry> typesPresent(String[] blocks, BlockRegistry registry, int limit) {
        if (blocks == null) {
            return new ArrayList<>();
        }
        Set<String> terrainTypes = terrainTypes(registry);
        // Counts per type, keyed by name first so the registry is queried once per distinct block name.
        Map<String, String> typeOfName = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : blocks) {
            if (CreationMesh.isAir(name)) {
                continue;
            }
            String type = typeOfName.get(name);
            if (type == null) {
                Block block = registry.get(name);
                if (block == null) {
                    continue;
                }
                type = block.getType();
                typeOfName.put(name, type);
            }
            counts.compute(type, (k, count) -> count == null ? 1 : count + 1);
        }

        List<TypeEntry> entries = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            entries.add(new TypeEntry(entry.getKey(), entry.getValue(), terrainTypes.contains(entry.getKey())));
        }
        if (limit > 0 && entries.size() > limit) {
            entries.sort(CreationBlockFilter::compareByCount);
            entries = new ArrayList<>(entries.subList(0, limit));
        }
        entries.sort(CreationBlockFilter::compareEntries);
        return entries;
    }

    /** The most numerous first, then alphabetically : selects which types make it into the dialog. */
    private static int compareByCount(TypeEntry a, TypeEntry b) {
        if (a.count != b.count) {
            return b.count - a.count;
        }
        return a.type.compareTo(b.type);
    }

    /** Terrain first, then the most numerous first, then alphabetically. */
    private static int compareEntries(TypeEntry a, TypeEntry b) {
        if (a.terrain != b.terrain) {
            return a.terrain ? -1 : 1;
        }
        if (a.count != b.count) {
            return b.count - a.count;
        }
        return a.type.compareTo(b.type);
    }

    /**
     * Empties (sets to {@link BlockIds#NONE}) every cell whose block type is in {@code types}, in place.
     * Returns the number of cells emptied ({@code 0} when nothing matched).
     */
    static int strip(String[] blocks, Set<String> types, BlockRegistry registry) {
        if (blocks == null || types == null || types.isEmpty()) {
            return 0;
        }
        // Resolved once per distinct block name : true = remove, false = keep.
        Map<String, Boolean> removeName = new LinkedHashMap<>();
        int removed = 0;
        for (int i = 0; i < blocks.length; i++) {
            String name = blocks[i];
            if (CreationMesh.isAir(name)) {
                continue;
            }
            Boolean remove = removeName.get(name);
            if (remove == null) {
                Block block = registry.get(name);
                remove = block != null && types.contains(block.getType());
                removeName.put(name, remove);
            }
            if (remove) {
                blocks[i] = BlockIds.NONE;
                removed++;
            }
        }
        return removed;
    }

    /** The types having at least one block flagged {@code terrain} in the registry. */
    private static Set<String> terrainTypes(BlockRegistry registry) {
        Set<String> types = new HashSet<>();
        for (Block block : registry.getAll()) {
            if (block.isTerrain()) {
                types.add(block.getType());
            }
        }
        return types;
    }
}

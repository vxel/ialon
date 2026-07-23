package org.delaunois.ialon.state;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreationBlockFilterTest {

    private BlockRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new BlockRegistry();
        // grass : the terrain flag is carried by the CUBE form only (as in the real catalog), so the slab
        // must still be recognised as a grass block through its type.
        register("grass", "grass", true);
        register("grass-slab_up-0", "grass", false);
        register("dirt", "dirt", true);
        // water : every liquid level shares the same type.
        register("water_source", "water", false);
        register("water-liquid_1", "water", false);
        register("oak_planks", "oak_planks", false);
    }

    private void register(String name, String type, boolean terrain) {
        registry.register(Block.builder().name(name).type(type).shape("cube_up").terrain(terrain).build());
    }

    @Test
    void typesPresentCountsByTypeAndListsTerrainFirst() {
        String[] blocks = {
                "oak_planks", "oak_planks", "oak_planks",
                "grass", "grass-slab_up-0",
                "dirt",
                "water_source", "water-liquid_1",
                "", null
        };

        List<CreationBlockFilter.TypeEntry> types = CreationBlockFilter.typesPresent(blocks, registry, 0);

        // grass (2) and dirt (1) are terrain, so they come first ; then the others by descending count.
        assertEquals(Arrays.asList("grass", "dirt", "oak_planks", "water"), typeNames(types));
        assertEquals(2, types.get(0).getCount(), "both grass shapes must count under the grass type");
        assertTrue(types.get(0).isTerrain());
        assertEquals(1, types.get(1).getCount());
        assertTrue(types.get(1).isTerrain());
        assertEquals(3, types.get(2).getCount());
        assertFalse(types.get(2).isTerrain());
        assertEquals(2, types.get(3).getCount(), "both water levels must count under the water type");
        assertFalse(types.get(3).isTerrain());
    }

    @Test
    void typesPresentIgnoresAirAndUnknownNames() {
        String[] blocks = {"", null, "not_a_block", "grass"};

        List<CreationBlockFilter.TypeEntry> types = CreationBlockFilter.typesPresent(blocks, registry, 0);

        assertEquals(Collections.singletonList("grass"), typeNames(types));
    }

    @Test
    void typesPresentKeepsOnlyTheMostFrequentTypesUpToTheLimit() {
        // oak_planks x3, water x2, grass x2 (terrain), dirt x1 (terrain).
        String[] blocks = {
                "oak_planks", "oak_planks", "oak_planks",
                "water_source", "water-liquid_1",
                "grass", "grass-slab_up-0",
                "dirt"
        };

        // The cut is made on the count, so the rarest type goes even though it is terrain...
        assertEquals(Arrays.asList("grass", "oak_planks", "water"),
                typeNames(CreationBlockFilter.typesPresent(blocks, registry, 3)));
        // ... and the survivors keep the terrain-first display order.
        assertEquals(Arrays.asList("grass", "oak_planks"),
                typeNames(CreationBlockFilter.typesPresent(blocks, registry, 2)));
        // A limit at or above the number of types present changes nothing.
        assertEquals(Arrays.asList("grass", "dirt", "oak_planks", "water"),
                typeNames(CreationBlockFilter.typesPresent(blocks, registry, 4)));
        assertEquals(4, CreationBlockFilter.typesPresent(blocks, registry, 30).size());
    }

    @Test
    void typesPresentOnEmptyOrNullGridIsEmpty() {
        assertTrue(CreationBlockFilter.typesPresent(null, registry, 0).isEmpty());
        assertTrue(CreationBlockFilter.typesPresent(new String[]{"", null}, registry, 0).isEmpty());
    }

    @Test
    void stripEmptiesEveryShapeAndLevelOfTheCheckedTypes() {
        String[] blocks = {"grass", "grass-slab_up-0", "dirt", "water_source", "water-liquid_1", "oak_planks", ""};

        int removed = CreationBlockFilter.strip(blocks, new HashSet<>(Arrays.asList("grass", "water")), registry);

        assertEquals(4, removed);
        assertArrayEquals(new String[]{"", "", "dirt", "", "", "oak_planks", ""}, blocks);
    }

    @Test
    void stripLeavesTheGridAloneWhenNothingIsChecked() {
        String[] blocks = {"grass", "dirt", "oak_planks"};

        assertEquals(0, CreationBlockFilter.strip(blocks, Collections.emptySet(), registry));
        assertEquals(0, CreationBlockFilter.strip(blocks, null, registry));
        assertEquals(0, CreationBlockFilter.strip(null, Collections.singleton("grass"), registry));
        assertArrayEquals(new String[]{"grass", "dirt", "oak_planks"}, blocks);
    }

    @Test
    void stripKeepsUnknownNames() {
        String[] blocks = {"not_a_block", "grass"};

        assertEquals(1, CreationBlockFilter.strip(blocks, Collections.singleton("grass"), registry));
        assertArrayEquals(new String[]{"not_a_block", ""}, blocks);
    }

    private static List<String> typeNames(List<CreationBlockFilter.TypeEntry> types) {
        return types.stream().map(CreationBlockFilter.TypeEntry::getType).collect(java.util.stream.Collectors.toList());
    }
}

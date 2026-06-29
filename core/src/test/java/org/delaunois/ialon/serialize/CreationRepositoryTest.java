package org.delaunois.ialon.serialize;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreationRepositoryTest {

    @Test
    void saveLoadRoundTripPreservesBlocks(@TempDir Path save) {
        String[] grid = {"rock", "none", "grass", "none", "water", "none"};
        CreationRepository.save(save, creation("alpha", "Alpha", 3, 2, 1, grid));

        Creation loaded = CreationRepository.load(save, "alpha");
        assertNotNull(loaded);
        assertEquals("Alpha", loaded.getName());
        assertEquals(3, loaded.getSizeX());
        assertEquals(2, loaded.getSizeY());
        assertEquals(1, loaded.getSizeZ());
        assertArrayEquals(grid, loaded.getBlocks());
    }

    @Test
    void listReadsMetadataOnlyWithoutBlocks(@TempDir Path save) {
        CreationRepository.save(save, creation("alpha", "Alpha", 2, 1, 1, new String[]{"rock", "none"}));
        CreationRepository.save(save, creation("beta", "Beta", 1, 1, 1, new String[]{"grass"}));

        List<Creation> all = CreationRepository.listCreations(save);
        assertEquals(2, all.size());
        assertEquals("alpha", all.get(0).getId(), "creations must be listed ordered by id");
        assertEquals("Alpha", all.get(0).getName());
        assertEquals(2, all.get(0).getSizeX());
        assertNull(all.get(0).getBlocks(), "listing must not load the (potentially large) block grid");
    }

    @Test
    void deleteRemovesTheFile(@TempDir Path save) {
        CreationRepository.save(save, creation("alpha", "Alpha", 1, 1, 1, new String[]{"rock"}));
        assertTrue(CreationRepository.exists(save, "alpha"));
        CreationRepository.delete(save, "alpha");
        assertFalse(CreationRepository.exists(save, "alpha"));
        assertNull(CreationRepository.load(save, "alpha"));
    }

    @Test
    void listOnFreshSaveIsEmpty(@TempDir Path save) {
        assertTrue(CreationRepository.listCreations(save).isEmpty());
    }

    @Test
    void nextNameAndUniqueIdAvoidCollisions(@TempDir Path save) {
        assertEquals("Creation 1", CreationRepository.nextCreationName(save));
        CreationRepository.save(save, creation(
                CreationRepository.generateUniqueId(save, "Creation 1"), "Creation 1", 1, 1, 1, new String[]{"none"}));
        assertEquals("Creation 2", CreationRepository.nextCreationName(save));
        String id = CreationRepository.generateUniqueId(save, "Creation 1");
        assertFalse(CreationRepository.exists(save, id), "generated id must be free");
    }

    private static Creation creation(String id, String name, int sx, int sy, int sz, String[] grid) {
        Creation c = new Creation();
        c.setId(id);
        c.setName(name);
        c.setSizeX(sx);
        c.setSizeY(sy);
        c.setSizeZ(sz);
        c.setBlocks(grid);
        return c;
    }
}

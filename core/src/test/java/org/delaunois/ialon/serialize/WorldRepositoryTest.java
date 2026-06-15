package org.delaunois.ialon.serialize;

import org.delaunois.ialon.IalonConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldRepositoryTest {

    @Test
    void createListAndDeleteWorlds(@TempDir Path save) {
        WorldRepository.createWorld(save, world("alpha", "Alpha"));
        WorldRepository.createWorld(save, world("beta", "Beta"));

        List<WorldParams> worlds = WorldRepository.listWorlds(save);
        assertEquals(2, worlds.size());
        assertEquals("alpha", worlds.get(0).getId(), "worlds must be listed ordered by id");
        assertEquals("beta", worlds.get(1).getId());
        assertTrue(WorldRepository.worldExists(save, "alpha"));

        WorldParams loaded = WorldRepository.loadWorldParams(save, "beta");
        assertNotNull(loaded);
        assertEquals("Beta", loaded.getName());

        WorldRepository.deleteWorld(save, "alpha");
        assertFalse(WorldRepository.worldExists(save, "alpha"));
        assertEquals(1, WorldRepository.listWorlds(save).size());
        assertFalse(Files.exists(WorldRepository.worldDir(save, "alpha")));
    }

    @Test
    void listWorldsOnFreshSaveIsEmpty(@TempDir Path save) {
        assertTrue(WorldRepository.listWorlds(save).isEmpty());
    }

    @Test
    void lastModifiedReflectsPlayerThenFallsBackToDescriptor(@TempDir Path save) throws IOException {
        WorldRepository.createWorld(save, world("alpha", "Alpha"));
        // Only world.yml exists so far : the descriptor time is used (non-zero).
        assertTrue(WorldRepository.lastModifiedMillis(save, "alpha") > 0L);
        // Unknown world : zero.
        assertEquals(0L, WorldRepository.lastModifiedMillis(save, "ghost"));
        // Once player.yml exists, its time is used.
        Files.write(WorldRepository.worldDir(save, "alpha")
                .resolve(IalonConfigRepository.PLAYER_STATE_FILENAME), "posx: 0".getBytes(StandardCharsets.UTF_8));
        assertTrue(WorldRepository.lastModifiedMillis(save, "alpha") > 0L);
    }

    @Test
    void migrateLegacySaveMovesChunksAndPlayerIntoDefaultWorld(@TempDir Path save) throws IOException {
        // Simulate a legacy flat save : chunk files and player.yml directly under save/.
        Files.write(save.resolve("chunk_0_0_0.zblock"), new byte[]{1, 2, 3});
        Files.write(save.resolve("chunk_1_0_2.zblock"), new byte[]{4, 5});
        Files.write(save.resolve(IalonConfigRepository.PLAYER_STATE_FILENAME),
                "posx: 1.0".getBytes(StandardCharsets.UTF_8));

        WorldRepository.migrateLegacySave(save);

        Path defaultDir = WorldRepository.worldDir(save, IalonConfig.DEFAULT_WORLD_ID);
        assertTrue(Files.exists(defaultDir.resolve("chunk_0_0_0.zblock")), "chunk must be moved into default world");
        assertTrue(Files.exists(defaultDir.resolve("chunk_1_0_2.zblock")));
        assertTrue(Files.exists(defaultDir.resolve(IalonConfigRepository.PLAYER_STATE_FILENAME)));
        assertTrue(Files.exists(defaultDir.resolve(WorldRepository.WORLD_FILENAME)), "a default world.yml must be written");

        // Originals removed from the root.
        assertFalse(Files.exists(save.resolve("chunk_0_0_0.zblock")));
        assertFalse(Files.exists(save.resolve(IalonConfigRepository.PLAYER_STATE_FILENAME)));

        // The default world reproduces the original generation parameters.
        WorldParams params = WorldRepository.loadWorldParams(save, IalonConfig.DEFAULT_WORLD_ID);
        assertNotNull(params);
        assertEquals(2, params.getSeed());
    }

    @Test
    void migrateLegacySaveIsIdempotent(@TempDir Path save) throws IOException {
        Files.write(save.resolve("chunk_0_0_0.zblock"), new byte[]{1});
        WorldRepository.migrateLegacySave(save);
        WorldRepository.migrateLegacySave(save); // second call must be a no-op, not move into a nested world
        Path defaultDir = WorldRepository.worldDir(save, IalonConfig.DEFAULT_WORLD_ID);
        assertTrue(Files.exists(defaultDir.resolve("chunk_0_0_0.zblock")));
        assertFalse(Files.exists(WorldRepository.worldDir(save, IalonConfig.DEFAULT_WORLD_ID)
                .resolve("worlds")), "migration must not recurse");
    }

    @Test
    void migrateLegacySaveNoopOnFreshInstall(@TempDir Path save) {
        // Nothing to migrate : the worlds directory must not be created (loadConfig creates the default).
        WorldRepository.migrateLegacySave(save);
        assertFalse(Files.exists(WorldRepository.worldsDir(save)));
    }

    private static WorldParams world(String id, String name) {
        WorldParams params = new WorldParams();
        params.setId(id);
        params.setName(name);
        return params;
    }
}

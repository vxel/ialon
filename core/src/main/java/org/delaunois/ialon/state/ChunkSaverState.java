package org.delaunois.ialon.state;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.rvandoosselaer.blocks.BlockRegistry;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkManagerListener;
import com.rvandoosselaer.blocks.ShapeIds;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.extern.slf4j.Slf4j;

/**
 * Persist chunk using the chunk repository
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ChunkSaverState extends BaseAppState implements ChunkManagerListener {

    private ExecutorService executorService;

    private final IalonConfig config;

    public ChunkSaverState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("chunk-saver").build());
    }

    /**
     * Saves (in another thread) the chunk at the given location
     * @param location the location of the chunk
     */
    public void asyncSave(Vec3i location) {
        config
                .getChunkManager()
                .getChunk(location)
                .ifPresent(chunk -> executorService.submit(() -> {
                    try {
                        config.getChunkRepository().save(chunk);
                        log.info("Chunk {} saved", location);
                    } catch (Exception e) {
                        log.error("Failed to save chunk", e);
                    }
                }));
    }

    public void fixLighting(Chunk chunk) {
        short[] blocks = chunk.getBlocks();
        byte[] lightmap = chunk.getLightMap();
        BlockRegistry registry = BlocksConfig.getInstance().getBlockRegistry();
        for (int i = 0; i < blocks.length; i++) {
            short block = blocks[i];
            byte lightLevel = lightmap[i];
            if (block != 0) {
                if (ShapeIds.CUBE.equals(registry.get(block).getShape()) && lightLevel != 0) {
                    lightmap[i] = 0;
                }
            }
        }
    }

    @Override
    protected void cleanup(Application app) {
        log.info("Stopping chunk-saver");
        executorService.shutdown();
    }

    @Override
    protected void onEnable() {
        // Nothing to do
    }

    @Override
    protected void onDisable() {
        // Nothing to do
    }

    @Override
    public void onChunkUpdated(Chunk chunk) {
        asyncSave(chunk.getLocation());
    }

    @Override
    public void onChunkAvailable(Chunk chunk) {
        // Nothing to do
    }
}

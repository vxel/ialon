package org.delaunois.ialon.state;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkManagerListener;
import org.delaunois.ialon.blocks.WorldEditOverlay;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.serialize.WorldEditOverlayRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

/**
 * Persist chunk using the chunk repository
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ChunkSaverState extends BaseAppState implements ChunkManagerListener {

    private ExecutorService executorService;

    // At most one overlay-save task in flight : a single edit fires onChunkUpdated for the chunk AND its
    // neighbours, so without this guard each notification would queue a redundant full rewrite of
    // edits.dat on the saver thread, in competition with the chunk saves.
    private final AtomicBoolean overlaySavePending = new AtomicBoolean(false);

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

    /**
     * Flushes the per-world edit overlay (felled trees / far-relief height overrides) on the same
     * persistence thread as the chunks, so it gains the same <b>live</b> durability they have. Without
     * this the overlay would only be written at clean-shutdown / world-switch checkpoints
     * (IalonConfigRepository.saveConfig) ; a tree felled since the last checkpoint was then lost if the
     * process exited uncleanly (IDE terminate, Android process kill, crash) — the chunk was saved live
     * so the near tree stayed gone, but the overlay had no record of the cut, so the distant billboard
     * reappeared after reload. The {@code isDirty()} guard makes this a no-op during normal play : a file
     * write happens only right after an actual tree/relief edit, never on the routine mesh updates (e.g.
     * liquid flow) that also fire {@link #onChunkUpdated}.
     */
    private void asyncSaveOverlay() {
        WorldEditOverlay overlay = config.getWorldEditOverlay();
        if (overlay == null || !overlay.isDirty()) {
            return;
        }
        // Collapse the burst of notifications from a single edit into one queued save. The flag is
        // released at the start of the task, so any edit made after the task picked up its work still
        // schedules a fresh save (and the repository's isDirty() guard makes a redundant one a no-op).
        if (!overlaySavePending.compareAndSet(false, true)) {
            return;
        }
        executorService.submit(() -> {
            overlaySavePending.set(false);
            try {
                WorldEditOverlayRepository.save(config.getCurrentWorldPath(), overlay);
            } catch (Exception e) {
                log.error("Failed to save world-edit overlay", e);
            }
        });
    }

    @Override
    public void onChunkUpdated(Chunk chunk) {
        asyncSave(chunk.getLocation());
        asyncSaveOverlay();
    }

    @Override
    public void onChunkUnfetched(Chunk chunk) {
        // The far-relief height overrides are (re)computed at unfetch (FarTerrainState), so flush them
        // here too — an unfetch does not necessarily coincide with a chunk-mesh update.
        asyncSaveOverlay();
    }

    @Override
    public void onChunkAvailable(Chunk chunk) {
        // Nothing to do
    }
}

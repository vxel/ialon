package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;

import org.delaunois.ialon.IalonConfig;

import java.util.Optional;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.delaunois.ialon.blocks.ChunkPager;
import org.delaunois.ialon.blocks.PhysicsChunkPager;

/**
 * Builds the World
 *
 * @author Cedric de Launois
 */
@Slf4j
public class WorldBuilderState extends BaseAppState {

    // Abandon the splash if the build makes no progress at all for this long. This is a stall guard
    // (a chunk failed to mesh, generation hung, ...), NOT a deadline on the whole build : a large grid
    // or a slow device can legitimately take much longer than this to finish, so the timer is reset
    // every time a page gets attached. Dismissing on an absolute deadline would hide the splash while
    // chunks are still streaming in, making them pop in after the loading screen is gone.
    private static final long STALL_TIMEOUT_MS = 10000;

    @Getter
    private final long startTime = System.currentTimeMillis();

    private int pagesAttached = 0;
    private int physicPagesAttached = 0;
    private long lastProgressTime = startTime;

    private PlayerState playerState;
    private final IalonConfig config;

    public WorldBuilderState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        playerState = app.getStateManager().getState(PlayerState.class, true);
        playerState.setEnabled(false);
    }

    @Override
    public void update(float tpf) {
        if (!playerState.isEnabled()) {
            startPlayer();
        }
    }

    public void startPlayer() {
        int gridSize = config.getGridRadius() * 2 + 1;
        int total = gridSize * gridSize * config.getGridHeight();

        ChunkPager chunkPager = getStateManager().getState(ChunkPagerState.class).getChunkPager();
        PhysicsChunkPager physicsChunkPager = getStateManager().getState(PhysicsChunkPagerState.class).getPhysicsChunkPager();
        int numPagesAttached = chunkPager.getAttachedPages().size();
        int numPhysicPagesAttached = physicsChunkPager.getAttachedPages().size();
        int percent = numPagesAttached * 100 / total;
        if (numPagesAttached > pagesAttached || numPhysicPagesAttached > physicPagesAttached) {
            log.debug("{} pages - {} physic pages attached ({}%)", numPagesAttached, numPhysicPagesAttached, percent);
            pagesAttached = numPagesAttached;
            physicPagesAttached = numPhysicPagesAttached;
            lastProgressTime = System.currentTimeMillis();
        }
        if (numPagesAttached >= total && physicsChunkPager.isReady()
                || (System.currentTimeMillis() - lastProgressTime > STALL_TIMEOUT_MS)) {
            long stopTime = System.currentTimeMillis();
            long duration = stopTime - startTime;
            log.info("World built in {}ms ({}ms per page)", duration, ((float)duration) / pagesAttached);
            log.info("Starting player");
            chunkPager.setMaxUpdatePerFrame(config.getMaxUpdatePerFrame());
            physicsChunkPager.setMaxUpdatePerFrame(10);
            playerState.setEnabled(true);
            Optional.ofNullable(getStateManager().getState(ChunkLiquidManagerState.class))
                    .ifPresent(state -> state.setEnabled(true));
            Optional.ofNullable(getStateManager().getState(SplashscreenState.class))
                    .ifPresent(state -> state.setEnabled(false));
            this.setEnabled(false);
        }
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        // Nothing to do
    }

    @Override
    protected void onDisable() {
        // Nothing to do
    }


}

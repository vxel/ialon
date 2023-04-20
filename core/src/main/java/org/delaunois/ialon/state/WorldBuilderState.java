package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;

import org.delaunois.ialon.ChunkPager;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.PhysicsChunkPager;

import java.util.Optional;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the World
 *
 * @author Cedric de Launois
 */
@Slf4j
public class WorldBuilderState extends BaseAppState {

    @Getter
    private final long startTime = System.currentTimeMillis();

    private int pagesAttached = 0;
    private int physicPagesAttached = 0;

    private PlayerState playerState;
    private IalonConfig config;

    @Override
    protected void initialize(Application app) {
        playerState = app.getStateManager().getState(PlayerState.class, true);
        playerState.setEnabled(false);
        config = IalonConfig.getInstance();
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
        }
        if (numPagesAttached >= total && physicsChunkPager.isReady()) {
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

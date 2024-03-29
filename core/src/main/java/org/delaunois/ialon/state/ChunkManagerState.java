package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;

import org.delaunois.ialon.ChunkManager;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * An application state to handle the lifecycle of a ChunkManager.
 *
 * @author rvandoosselaer
 */
@Getter
@RequiredArgsConstructor
public class ChunkManagerState extends BaseAppState {

    private final ChunkManager chunkManager;

    @Override
    protected void initialize(Application app) {
        chunkManager.initialize();
    }

    @Override
    protected void cleanup(Application app) {
        chunkManager.cleanup();
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

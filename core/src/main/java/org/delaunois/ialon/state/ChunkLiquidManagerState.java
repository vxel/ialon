package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.ChunkLiquidManager;
import org.delaunois.ialon.ChunkManager;
import org.delaunois.ialon.Ialon;

import java.util.HashSet;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * An application state to handle the liquid simulation.
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ChunkLiquidManagerState extends BaseAppState {

    private ChunkManager chunkManager;
    private ChunkLiquidManager chunkLiquidManager;
    private float elapsed = 0;
    private Ialon app;

    @Override
    protected void initialize(Application app) {
        this.chunkManager = ((Ialon) app).getChunkManager();
        this.chunkLiquidManager = chunkManager.getChunkLiquidManager();
        this.app = (Ialon) app;
    }

    @Override
    protected void cleanup(Application app) {
    }

    @Override
    protected void onEnable() {
    }

    @Override
    protected void onDisable() {
    }

    @Override
    public void update(float tpf) {
        elapsed += tpf;
        if (elapsed > 0.1f) {
            Set<Vec3i> updatedChunks = new HashSet<>();
            for (int i = 0; i < 10; i ++) {
                updatedChunks.addAll(chunkLiquidManager.step());
            }
            if (!updatedChunks.isEmpty()) {
                chunkManager.requestMeshChunks(updatedChunks);
                for (Vec3i location : updatedChunks) {
                    app.asyncSave(location);
                }
            }
            elapsed = 0;
        }
    }

}

package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.ChunkLiquidManager;
import org.delaunois.ialon.ChunkManager;
import org.delaunois.ialon.Ialon;

import java.util.Set;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChunkLiquidManagerState extends BaseAppState {

    private ChunkManager chunkManager;
    private ChunkLiquidManager chunkLiquidManager;
    private float elapsed = 0;

    @Override
    protected void initialize(Application app) {
        this.chunkManager = ((Ialon) app).getChunkManager();
        this.chunkLiquidManager = chunkManager.getChunkLiquidManager();
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
        if (elapsed > 1f) {
            Set<Vec3i> updatedChunks = chunkLiquidManager.step();
            if (!updatedChunks.isEmpty()) {
                chunkManager.requestMeshChunks(updatedChunks);
                // TODO save blocks
            }
            elapsed = 0;
        }
    }

}

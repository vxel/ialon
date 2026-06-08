package org.delaunois.ialon.blocks;

import com.jme3.math.ColorRGBA;

/**
 * The settings the voxel engine reads from its host application.
 */
public interface WorldSettings {

    ChunkManager getChunkManager();

    int getSimulateLiquidFlowModel();

    boolean isManualGammaEncode();

    ColorRGBA getCalmWaterColor();

    boolean isGreedyCalmWater();

    boolean isDebugChunks();

    int getChunkSize();

}

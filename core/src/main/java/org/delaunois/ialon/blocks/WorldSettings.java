package org.delaunois.ialon.blocks;

import com.jme3.math.ColorRGBA;

/**
 * The settings the voxel engine reads from its host application.
 * <p>
 * Defining this contract inside the {@code blocks} package (and having the game's
 * {@code IalonConfig} implement it) keeps the engine self-contained: the engine depends only on
 * this interface, never on game classes, so there is no package cycle.
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

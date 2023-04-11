package org.delaunois.ialon;

import com.jme3.math.Vector3f;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.simsilica.mathd.Vec3i;

public class FlatTerrainGenerator implements TerrainGenerator {

    /**
     * the y value (inclusive) of the highest blocks
     */
    private final int ground;
    private final Block block;

    public FlatTerrainGenerator(int y, Block block) {
        if (y < 0 || y >= BlocksConfig.getInstance().getChunkSize().y) {
            throw new IllegalArgumentException("Invalid parameters specified! [y=" + y + "]");
        }
        this.ground = y;
        this.block = block;
    }

    @Override
    public Chunk generate(Vec3i location) {
        Chunk chunk = Chunk.createAt(location);

        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        for (int x = 0; x < chunkSize.x; x++) {
            for (int y = 0; y < chunkSize.y; y++) {
                for (int z = 0; z < chunkSize.z; z++) {
                    if (y <= ground) {
                        chunk.addBlock(x, y, z, block);
                        chunk.setSunlight(x, y, z, 0);
                    } else {
                        chunk.setSunlight(x, y, z, 15);
                    }
                }
            }
        }

        chunk.setDirty(false);
        return chunk;
    }

    public float getWaterHeight() {
        return ground - 1f;
    }

    public void setWaterHeight(float waterHeight) {
        throw new UnsupportedOperationException("Water is not supported by " + this.getClass().getSimpleName());
    }

    public float getHeight(Vector3f blockLocation) {
        return ground;
    }

}
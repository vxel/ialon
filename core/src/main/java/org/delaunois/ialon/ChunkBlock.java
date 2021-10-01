package org.delaunois.ialon;

import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.Chunk;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChunkBlock {

    private Chunk chunk;
    private Block block;

    public ChunkBlock() {
        chunk = null;
        block = null;
    }

    public ChunkBlock(Chunk chunk, Block block) {
        this.chunk = chunk;
        this.block = block;
    }
}

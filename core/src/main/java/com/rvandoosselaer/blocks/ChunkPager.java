package com.rvandoosselaer.blocks;

import com.jme3.scene.Node;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * A pager implementation that pages the meshes of the chunks around the given location. Attaching chunks that are in
 * range and detaching them when they are out of range, from the given node.
 *
 * @author rvandoosselaer
 */
@Slf4j
public class ChunkPager extends Pager<Node> {

    @Getter
    private final Node node;

    public ChunkPager(@NonNull Node node, @NonNull ChunkManager chunkManager) {
        super(chunkManager);
        this.node = node;
        this.gridSize = BlocksConfig.getInstance().getGrid();
    }

    @Override
    protected Node createPage(Chunk chunk) {
        return chunk.getNode();
    }

    @Override
    protected boolean detachPage(Node page) {
        if (log.isTraceEnabled()) {
            log.trace("Detaching {} from {}", page, node);
        }
        node.detachChild(page);
        return true;
    }

    @Override
    protected void attachPage(Node page) {
        if (log.isTraceEnabled()) {
            log.trace("Attaching {} to {}", page, node);
        }
        node.attachChild(page);
    }

}

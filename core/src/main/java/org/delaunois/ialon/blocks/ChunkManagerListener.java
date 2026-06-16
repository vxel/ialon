package org.delaunois.ialon.blocks;


/**
 * A listener that can be registered to the {@link ChunkManager}. Use this to get notified when the mesh of a chunk is
 * updated or when a new chunk is available for retrieval.
 *
 * @author: rvandoosselaer
 */
public interface ChunkManagerListener {

    void onChunkUpdated(Chunk chunk);

    void onChunkAvailable(Chunk chunk);

    default void onChunkFetched(Chunk chunk) {};

    /**
     * Called when a chunk is about to be unfetched (paged out of the loaded grid), while it is still
     * available in the cache. Useful to capture the chunk's final state right before it leaves — e.g.
     * to refresh the distant far-terrain relief for an edited chunk that becomes visible at the horizon.
     */
    default void onChunkUnfetched(Chunk chunk) {};
}

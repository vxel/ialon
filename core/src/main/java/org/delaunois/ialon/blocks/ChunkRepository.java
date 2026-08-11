package org.delaunois.ialon.blocks;

import com.simsilica.mathd.Vec3i;

/**
 * The contract of a ChunkRepository implementation.
 *
 * @author rvandoosselaer
 */
public interface ChunkRepository {

    /**
     * Loads the chunk for the given chunk location.
     *
     * @param location of the chunk
     * @return chunk or null if the chunk could not be loaded
     */
    Chunk load(Vec3i location);

    /**
     * Saves the chunk
     *
     * @param chunk to save
     * @return true when successfully saved, false otherwise
     */
    boolean save(Chunk chunk);

    /**
     * Deletes the saved chunk at the given location, if there is one, so that loading it again falls back
     * to the generated terrain. Used by the chunk reset, which discards the player's edits for good.
     *
     * @param location of the chunk
     * @return true when a saved chunk was removed, false when there was nothing to remove (or it failed)
     */
    boolean delete(Vec3i location);

}

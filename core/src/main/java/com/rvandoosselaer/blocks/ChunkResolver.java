package com.rvandoosselaer.blocks;

import com.simsilica.mathd.Vec3i;

import java.util.Optional;

import lombok.NonNull;

/**
 * A service to retrieve chunks.
 *
 * @author: rvandoosselaer
 */
public interface ChunkResolver {

    /**
     * Return a Chunk optional.
     *
     * @param location of the chunk
     * @return chunk
     */
    Optional<Chunk> get(@NonNull Vec3i location);

    /**
     * Return the chunk, if present in cache.
     * @param location of the chunk
     * @return chunk or null
     */
    Chunk unsafeFastGet(@NonNull Vec3i location);

}

package com.rvandoosselaer.blocks;

import com.simsilica.mathd.Vec3i;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * An in memory threadsafe chunk cache implementation.
 *
 * @author: rvandoosselaer
 */
@Slf4j
public class ChunkCache implements ChunkResolver {

    private final Map<Vec3i, Chunk> cache = new ConcurrentHashMap<>();

    public ChunkCache() {
        this(0);
    }

    public ChunkCache(int cacheSize) {
    }

    @Override
    public Optional<Chunk> get(@NonNull Vec3i location) {
        return Optional.ofNullable(cache.get(location));
    }

    @Override
    public Chunk unsafeFastGet(@NonNull Vec3i location) {
        return cache.get(location);
    }

    public void evict(@NonNull Vec3i location) {
        if (log.isDebugEnabled()) {
            log.debug("Cache evicted {}", location);
        }
        cache.remove(location);
    }

    public void evictAll() {
        cache.clear();
    }

    public void put(@NonNull Chunk chunk) {
        if (log.isDebugEnabled()) {
            log.debug("Cache added {}", chunk.getLocation());
        }
        cache.put(chunk.getLocation(), chunk);
    }

    public long getSize() {
        return cache.size();
    }

    public void maintain() {

    }

}

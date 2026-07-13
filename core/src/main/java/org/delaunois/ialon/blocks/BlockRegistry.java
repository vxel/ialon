package org.delaunois.ialon.blocks;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * A thread safe register for blocks. The register is used so only one instance of a block is used throughout the Blocks
 * framework.
 *
 * @author rvandoosselaer
 */
@Slf4j
public class BlockRegistry {

    private final ConcurrentMap<String, Block> registry = new ConcurrentHashMap<>();

    private static final int MAX_BLOCKS = 32000;
    private final Block[] aregistry = new Block[MAX_BLOCKS];
    private short size = 1; // Block id 0 is an empty block

    public BlockRegistry() {
        // Blocks are registered by the game from the YAML catalog (see IalonBlockCatalog) ;
        // the registry ships empty.
    }

    /**
     * @param registerDefaultBlocks retained for API compatibility. Ialon owns no built-in default
     *                              blocks — every block comes from the YAML catalog — so this is a no-op.
     */
    public BlockRegistry(boolean registerDefaultBlocks) {
        // no-op : see IalonBlockCatalog
    }

    public Block register(@NonNull Block block) {
        return register(block.getName(), block);
    }

    public Block register(@NonNull String name, Block block) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Invalid block name " + name + " specified.");
        }

        registry.put(name, block);

        block.setId(size);
        aregistry[size] = block;
        size += 1;

        if (log.isTraceEnabled()) {
            log.trace("Registered block {} -> {}", name, block);
        }
        return block;
    }

    public void register(@NonNull Block... blocks) {
        Arrays.stream(blocks).forEach(this::register);
    }

    public void register(@NonNull Collection<Block> collection) {
        collection.forEach(this::register);
    }

    public boolean remove(@NonNull Block block) {
        return remove(block.getName());
    }

    public boolean remove(@NonNull String name) {
        if (registry.containsKey(name)) {
            Block block = registry.remove(name);
            if (log.isTraceEnabled()) {
                log.trace("Removed block {} -> {}", name, block);
            }
            return true;
        }
        return false;
    }

    public Block get(short id) {
        return aregistry[id];
    }

    public Block get(@NonNull String name) {
        if (BlockIds.NONE.equals(name)) {
            return null;
        }

        Block b = registry.get(name);
        if (b == null) {
            log.warn("No block registered with name {}", name);
        }
        return b;
    }

    public void clear() {
        registry.clear();
    }

    public int size() {
        return registry.size();
    }

    public Collection<Block> getAll() {
        return Collections.unmodifiableCollection(registry.values());
    }

}

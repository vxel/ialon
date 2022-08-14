package com.rvandoosselaer.blocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.rvandoosselaer.blocks.serialize.BlockDTO;
import com.rvandoosselaer.blocks.serialize.BlockDefinition;
import com.rvandoosselaer.blocks.serialize.BlockFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    private static final int MAX_BLOCKS = 2000;
    private final Block[] aregistry = new Block[MAX_BLOCKS];
    private short size = 1; // Block id 0 is an empty block

    /**
     * Will register default blocks
     */
    public BlockRegistry() {
        this(true);
    }

    public BlockRegistry(boolean registerDefaultBlocks) {
        if (registerDefaultBlocks) {
            registerDefaultBlocks();
        }
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

    public Collection<Block> getAll() {
        return Collections.unmodifiableCollection(registry.values());
    }

    public void registerDefaultBlocks() {
        registerBirchBlocks();
        registerBrickBlocks();
        registerCobbleStoneBlocks();
        registerDirtBlocks();
        registerGravelBlocks();
        registerGrassBlocks();
        registerPalmTreeBlocks();
        registerRockBlocks();
        registerOakBlocks();
        registerSandBlocks();
        registerSnowBlocks();
        registerLightBlocks();
        registerSpruceBlocks();
        registerStoneBrickBlocks();
        registerWaterBlocks();
        registerWindowBlocks();
        registerItemBlocks();
        registerScaleBlocks();
    }

    public void load(InputStream inputStream) {
        try {
            List<BlockDTO> blocks = objectMapper.readValue(inputStream, objectMapper.getTypeFactory().constructCollectionType(List.class, BlockDTO.class));
            if (log.isTraceEnabled()) {
                log.trace("Loaded {} blocks from inputstream.", blocks.size());
            }
            blocks.forEach(blockDTO -> register(Block.createFrom(blockDTO)));
        } catch (IOException e) {
            log.error("Unable to read inputstream. Error: {}", e.getMessage(), e);
        }
    }

    private void registerWindowBlocks() {
        BlockDefinition windowDef = new BlockDefinition(TypeIds.WINDOW, true, true, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_SQUARES);
        register(BlockFactory.create(windowDef));
    }

    private void registerWaterBlocks() {
        register(Block.builder().name(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID1)).shape(ShapeIds.LIQUID1).type(TypeIds.WATER).transparent(true).liquidLevel(1).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID2)).shape(ShapeIds.LIQUID2).type(TypeIds.WATER).transparent(true).liquidLevel(2).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID3)).shape(ShapeIds.LIQUID3).type(TypeIds.WATER).transparent(true).liquidLevel(3).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID4)).shape(ShapeIds.LIQUID4).type(TypeIds.WATER).transparent(true).liquidLevel(4).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID5)).shape(ShapeIds.LIQUID5).type(TypeIds.WATER).transparent(true).liquidLevel(5).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID)).shape(ShapeIds.LIQUID).type(TypeIds.WATER).transparent(true).liquidLevel(6).build());
    }

    private void registerStoneBrickBlocks() {
        BlockDefinition stoneBrickDef = new BlockDefinition(TypeIds.STONE_BRICKS, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ALL_FENCES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(stoneBrickDef));

        BlockDefinition mossyStoneBrickDef = new BlockDefinition(TypeIds.MOSSY_STONE_BRICKS, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ALL_FENCES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(mossyStoneBrickDef));
    }

    private void registerSpruceBlocks() {
        BlockDefinition spruceLogDef = new BlockDefinition(TypeIds.SPRUCE_LOG, true, false, true)
                .addShapes(ShapeIds.ALL_CUBES)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ALL_ROUNDED_CUBES)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES);
        register(BlockFactory.create(spruceLogDef));

        BlockDefinition sprucePlankDef = new BlockDefinition(TypeIds.SPRUCE_PLANKS, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(sprucePlankDef));

        Block spruceLeaves = new Block(BlockIds.SPRUCE_LEAVES, ShapeIds.CUBE, TypeIds.SPRUCE_LEAVES, false, true, true, false);
        register(spruceLeaves);
    }

    private void registerSnowBlocks() {
        BlockDefinition snowDef = new BlockDefinition(TypeIds.SNOW, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(snowDef));
    }

    private void registerLightBlocks() {
        register(Block.builder().name(BlockIds.getName(TypeIds.WHITE_LIGHT, ShapeIds.SHORT_POLE)).shape(ShapeIds.SHORT_POLE).type(TypeIds.WHITE_LIGHT).solid(true).torchlight(true).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.WHITE_LIGHT, ShapeIds.SHORT_POLE_DOWN)).shape(ShapeIds.SHORT_POLE_DOWN).type(TypeIds.WHITE_LIGHT).solid(true).torchlight(true).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.WHITE_LIGHT, ShapeIds.SHORT_POLE_EAST)).shape(ShapeIds.SHORT_POLE_EAST).type(TypeIds.WHITE_LIGHT).solid(true).torchlight(true).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.WHITE_LIGHT, ShapeIds.SHORT_POLE_WEST)).shape(ShapeIds.SHORT_POLE_WEST).type(TypeIds.WHITE_LIGHT).solid(true).torchlight(true).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.WHITE_LIGHT, ShapeIds.SHORT_POLE_SOUTH)).shape(ShapeIds.SHORT_POLE_SOUTH).type(TypeIds.WHITE_LIGHT).solid(true).torchlight(true).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.WHITE_LIGHT, ShapeIds.SHORT_POLE_NORTH)).shape(ShapeIds.SHORT_POLE_NORTH).type(TypeIds.WHITE_LIGHT).solid(true).torchlight(true).build());
    }

    private void registerItemBlocks() {
        register(Block.builder().name(BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 0)).shape(ShapeIds.CROSS_PLANE).type(TypeIds.ITEM_GRASS).liquidLevel(0).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 1)).shape(ShapeIds.CROSS_PLANE).type(TypeIds.ITEM_GRASS).liquidLevel(1).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 2)).shape(ShapeIds.CROSS_PLANE).type(TypeIds.ITEM_GRASS).liquidLevel(2).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 3)).shape(ShapeIds.CROSS_PLANE).type(TypeIds.ITEM_GRASS).liquidLevel(3).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 4)).shape(ShapeIds.CROSS_PLANE).type(TypeIds.ITEM_GRASS).liquidLevel(4).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 5)).shape(ShapeIds.CROSS_PLANE).type(TypeIds.ITEM_GRASS).liquidLevel(5).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 6)).shape(ShapeIds.CROSS_PLANE).type(TypeIds.ITEM_GRASS).liquidLevel(6).build());
    }

    private void registerScaleBlocks() {
        BlockDefinition scaleDef = new BlockDefinition(TypeIds.SCALE, false, false, false)
                .addShapes(ShapeIds.SQUARE_NORTH)
                .addShapes(ShapeIds.SQUARE_SOUTH)
                .addShapes(ShapeIds.SQUARE_WEST)
                .addShapes(ShapeIds.SQUARE_EAST);
        register(BlockFactory.create(scaleDef));
    }

    private void registerSandBlocks() {
        BlockDefinition sandDef = new BlockDefinition(TypeIds.SAND, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(sandDef));
    }

    private void registerOakBlocks() {
        BlockDefinition oakLogDef = new BlockDefinition(TypeIds.OAK_LOG, true, false, true)
                .addShapes(ShapeIds.ALL_CUBES)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ALL_FENCES)
                .addShapes(ShapeIds.ALL_ROUNDED_CUBES)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES);
        register(BlockFactory.create(oakLogDef));

        register(Block.builder().name(BlockIds.getName(TypeIds.OAK_LOG, ShapeIds.POLE, 0)).shape(ShapeIds.POLE).type(TypeIds.OAK_LOG).liquidLevel(0).solid(true).usingMultipleImages(true).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.OAK_LOG, ShapeIds.POLE, 1)).shape(ShapeIds.POLE).type(TypeIds.OAK_LOG).liquidLevel(1).solid(true).usingMultipleImages(true).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.OAK_LOG, ShapeIds.POLE, 2)).shape(ShapeIds.POLE).type(TypeIds.OAK_LOG).liquidLevel(2).solid(true).usingMultipleImages(true).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.OAK_LOG, ShapeIds.POLE, 3)).shape(ShapeIds.POLE).type(TypeIds.OAK_LOG).liquidLevel(3).solid(true).usingMultipleImages(true).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.OAK_LOG, ShapeIds.POLE, 4)).shape(ShapeIds.POLE).type(TypeIds.OAK_LOG).liquidLevel(4).solid(true).usingMultipleImages(true).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.OAK_LOG, ShapeIds.POLE, 5)).shape(ShapeIds.POLE).type(TypeIds.OAK_LOG).liquidLevel(5).solid(true).usingMultipleImages(true).build());
        register(Block.builder().name(BlockIds.getName(TypeIds.OAK_LOG, ShapeIds.POLE, 6)).shape(ShapeIds.POLE).type(TypeIds.OAK_LOG).liquidLevel(6).solid(true).usingMultipleImages(true).build());

        BlockDefinition oakPlankDef = new BlockDefinition(TypeIds.OAK_PLANKS, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ALL_FENCES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(oakPlankDef));

        Block oakLeaves = new Block(BlockIds.OAK_LEAVES, ShapeIds.CUBE, TypeIds.OAK_LEAVES, false, true, true, false);
        register(oakLeaves);
    }

    private void registerRockBlocks() {
        BlockDefinition rockDef = new BlockDefinition(TypeIds.ROCK, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ALL_FENCES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(rockDef));
    }

    private void registerPalmTreeBlocks() {
        BlockDefinition palmTreeLogDef = new BlockDefinition(TypeIds.PALM_TREE_LOG, true, false, true)
                .addShapes(ShapeIds.ALL_CUBES)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ALL_ROUNDED_CUBES)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES);
        register(BlockFactory.create(palmTreeLogDef));

        BlockDefinition palmTreePlankDef = new BlockDefinition(TypeIds.PALM_TREE_PLANKS, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(palmTreePlankDef));

        Block palmTreeLeaves = new Block(BlockIds.PALM_TREE_LEAVES, ShapeIds.CUBE, TypeIds.PALM_TREE_LEAVES, false, true, true, false);
        register(palmTreeLeaves);
    }

    private void registerGrassBlocks() {
        BlockDefinition grassDef = new BlockDefinition(TypeIds.GRASS, true, false, true)
                .addShapes(ShapeIds.ALL_CUBES)
                .addShapes(ShapeIds.ALL_ROUNDED_CUBES)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS);
        register(BlockFactory.create(grassDef));

        BlockDefinition snowedGrassDef = new BlockDefinition(TypeIds.GRASS_SNOW, true, false, true)
                .addShapes(ShapeIds.ALL_CUBES)
                .addShapes(ShapeIds.ALL_ROUNDED_CUBES)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS);
        register(BlockFactory.create(snowedGrassDef));
    }

    private void registerGravelBlocks() {
        BlockDefinition gravelDef = new BlockDefinition(TypeIds.GRAVEL, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(gravelDef));
    }

    private void registerDirtBlocks() {
        BlockDefinition dirtDef = new BlockDefinition(TypeIds.DIRT, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(dirtDef));
    }

    private void registerCobbleStoneBlocks() {
        BlockDefinition cobbleStoneDef = new BlockDefinition(TypeIds.COBBLESTONE, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ALL_FENCES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(cobbleStoneDef));

        BlockDefinition mossyCobbleStoneDef = new BlockDefinition(TypeIds.MOSSY_COBBLESTONE, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ALL_FENCES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(mossyCobbleStoneDef));
    }

    private void registerBrickBlocks() {
        BlockDefinition brickDef = new BlockDefinition(TypeIds.BRICKS, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(brickDef));
    }

    private void registerBirchBlocks() {
        BlockDefinition birchLogDef = new BlockDefinition(TypeIds.BIRCH_LOG, true, false, true)
                .addShapes(ShapeIds.ALL_CUBES)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ALL_FENCES)
                .addShapes(ShapeIds.ALL_ROUNDED_CUBES)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES);
        register(BlockFactory.create(birchLogDef));

        BlockDefinition birchPlankDef = new BlockDefinition(TypeIds.BIRCH_PLANKS, true, false, false)
                .addShapes(ShapeIds.CUBE)
                .addShapes(ShapeIds.ALL_PYRAMIDS)
                .addShapes(ShapeIds.ALL_WEDGES)
                .addShapes(ShapeIds.ALL_POLES)
                .addShapes(ShapeIds.ALL_FENCES)
                .addShapes(ShapeIds.ROUNDED_CUBE)
                .addShapes(ShapeIds.ALL_SLABS)
                .addShapes(ShapeIds.ALL_DOUBLE_SLABS)
                .addShapes(ShapeIds.ALL_PLATES)
                .addShapes(ShapeIds.ALL_STAIRS);
        register(BlockFactory.create(birchPlankDef));

        Block birchLeaves = new Block(BlockIds.BIRCH_LEAVES, ShapeIds.CUBE, TypeIds.BIRCH_LEAVES, false, true, true, false);
        register(birchLeaves);
    }

}

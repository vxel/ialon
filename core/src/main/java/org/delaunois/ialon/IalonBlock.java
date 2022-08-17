package org.delaunois.ialon;

import com.rvandoosselaer.blocks.ShapeIds;
import com.rvandoosselaer.blocks.TypeIds;

public enum IalonBlock {

    WINDOW(TypeIds.WINDOW, true, true, false, ShapeIds.CUBE, ShapeIds.PLATE, ShapeIds.SQUARE),

    /*
    WATER(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID), TypeIds.WATER, false, true, false, ShapeIds.LIQUID, 6),
    WATER1(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID1), TypeIds.WATER, false, true, false, ShapeIds.LIQUID1, 1),
    WATER2(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID2), TypeIds.WATER, false, true, false, ShapeIds.LIQUID2, 2),
    WATER3(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID3), TypeIds.WATER, false, true, false, ShapeIds.LIQUID3, 3),
    WATER4(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID4), TypeIds.WATER, false, true, false, ShapeIds.LIQUID4, 4),
    WATER5(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID5), TypeIds.WATER, false, true, false, ShapeIds.LIQUID5, 5),
    */

    STONE_BRICKS(TypeIds.STONE_BRICKS, true, false, false, Config.STANDARD_SHAPES),
    MOSSY_STONE_BRICKS(TypeIds.MOSSY_STONE_BRICKS, true, false, false, Config.STANDARD_SHAPES),

    SPRUCE_LOG(TypeIds.SPRUCE_LOG, true, false, true, Config.STANDARD_SHAPES_NO_STAIRS),
    SPRUCE_PLANKS(TypeIds.SPRUCE_PLANKS, true, false, false, Config.STANDARD_SHAPES),
    SPRUCE_LEAVES(TypeIds.SPRUCE_LEAVES, true, true, false, ShapeIds.CUBE),

    SNOW(TypeIds.SNOW, true, false, false, Config.STANDARD_SHAPES),
    ITEM_GRASS(TypeIds.ITEM_GRASS, false, false, false, ShapeIds.CROSS_PLANE),
    SCALE(TypeIds.SCALE, false, false, false, ShapeIds.SQUARE_NORTH, ShapeIds.SQUARE_SOUTH, ShapeIds.SQUARE_WEST, ShapeIds.SQUARE_EAST),
    SAND(TypeIds.SAND, true, false, false, Config.STANDARD_SHAPES),

    OAK_LOG(TypeIds.OAK_LOG, true, false, true, Config.STANDARD_SHAPES_NO_STAIRS),
    OAK_PLANKS(TypeIds.OAK_PLANKS, true, false, false, Config.STANDARD_SHAPES),
    OAK_LEAVES(TypeIds.OAK_LEAVES, true, true, false, ShapeIds.CUBE),

    ROCK(TypeIds.ROCK, true, false, false, Config.STANDARD_SHAPES),

    PALM_TREE_LOG(TypeIds.PALM_TREE_LOG, true, false, true, Config.STANDARD_SHAPES_NO_STAIRS),
    PALM_TREE_PLANKS(TypeIds.PALM_TREE_PLANKS, true, false, false, Config.STANDARD_SHAPES),
    PALM_TREE_LEAVES(TypeIds.PALM_TREE_LEAVES, true, true, false, ShapeIds.CUBE),

    GRASS(TypeIds.GRASS, true, false, true, ShapeIds.CUBE, ShapeIds.SLAB, ShapeIds.DOUBLE_SLAB),
    GRASS_SNOW(TypeIds.GRASS_SNOW, true, false, true, ShapeIds.CUBE, ShapeIds.SLAB, ShapeIds.DOUBLE_SLAB),
    GRAVEL(TypeIds.GRAVEL, true, false, false, Config.STANDARD_SHAPES),
    DIRT(TypeIds.DIRT, true, false, false, Config.STANDARD_SHAPES),
    COBBLESTONE(TypeIds.COBBLESTONE, true, false, false, Config.STANDARD_SHAPES),
    MOSSY_COBBLESTONE(TypeIds.MOSSY_COBBLESTONE, true, false, false, Config.STANDARD_SHAPES),
    BRICKS(TypeIds.BRICKS, true, false, false, Config.STANDARD_SHAPES),

    BIRCH_LOG(TypeIds.BIRCH_LOG, true, false, true, Config.STANDARD_SHAPES_NO_STAIRS),
    BIRCH_PLANKS(TypeIds.BIRCH_PLANKS, true, false, false, Config.STANDARD_SHAPES),
    BIRCH_LEAVES(TypeIds.BIRCH_LEAVES, true, true, false, ShapeIds.CUBE),

    TILE_RED("tile_red", true, false, false, Config.STANDARD_SHAPES),
    SLATE("slate", true, false, false, Config.STANDARD_SHAPES),
    METAL1("metal1", true, false, false, Config.STANDARD_SHAPES),
    METAL2("metal2", true, true, false, Config.STANDARD_SHAPES),
    METAL3("metal3", true, false, false, Config.STANDARD_SHAPES),
    METAL4("metal4", true, false, false, Config.STANDARD_SHAPES),
    METAL5("metal5", true, true, false, Config.STANDARD_SHAPES),

    WHITE_LIGHT(TypeIds.WHITE_LIGHT, true, false, false, true,
            ShapeIds.SHORT_POLE, ShapeIds.SHORT_POLE_DOWN, ShapeIds.SHORT_POLE_EAST, ShapeIds.SHORT_POLE_WEST, ShapeIds.SHORT_POLE_SOUTH, ShapeIds.SHORT_POLE_NORTH);

    private final String name;
    private final String type;
    private final boolean solid;
    private final boolean transparent;
    private final boolean multitexture;
    private final boolean torchlight;
    private final String[] shapes;
    private final int[] waterLevels;

    IalonBlock(String type, boolean solid, boolean transparent, boolean multitexture, String... shapes) {
        this.name = null; // Automatically generated based on type, shape and water level
        this.type = type;
        this.transparent = transparent;
        this.solid = solid;
        this.multitexture = multitexture;
        this.torchlight = false;
        this.shapes = shapes;
        this.waterLevels = Config.ALL_LEVELS;
    }

    IalonBlock(String type, boolean solid, boolean transparent, boolean multitexture, boolean torchlight, String... shapes) {
        this.name = null; // Automatically generated based on type, shape and water level
        this.type = type;
        this.transparent = transparent;
        this.solid = solid;
        this.multitexture = multitexture;
        this.torchlight = torchlight;
        this.shapes = shapes;
        this.waterLevels = Config.ALL_LEVELS;
    }

    IalonBlock(String name, String type, boolean solid, boolean transparent, boolean multitexture, String shape, int waterLevel) {
        this.name = name;
        this.type = type;
        this.transparent = transparent;
        this.solid = solid;
        this.multitexture = multitexture;
        this.torchlight = false;
        this.shapes = new String[]{ shape };
        this.waterLevels = new int[]{ waterLevel };
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public boolean isSolid() {
        return solid;
    }

    public boolean isTransparent() {
        return transparent;
    }

    public boolean isMultitexture() { 
        return multitexture; 
    }

    public String[] getShapes() {
        return shapes;
    }

    public int[] getWaterLevels() {
        return waterLevels;
    }

    public boolean isTorchlight() {
        return torchlight;
    }
}

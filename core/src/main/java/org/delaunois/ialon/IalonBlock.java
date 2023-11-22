/*
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon;

import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.ShapeIds;
import com.rvandoosselaer.blocks.TypeIds;

public enum IalonBlock {

    WINDOW(TypeIds.WINDOW, true, true, false, ShapeIds.CUBE, ShapeIds.PLATE, ShapeIds.SQUARE),

    WATER1(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID1), TypeIds.WATER, false, true, false, ShapeIds.LIQUID1, Block.LIQUID_LEVEL1),
    WATER2(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID2), TypeIds.WATER, false, true, false, ShapeIds.LIQUID2, Block.LIQUID_LEVEL2),
    WATER3(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID3), TypeIds.WATER, false, true, false, ShapeIds.LIQUID3, Block.LIQUID_LEVEL3),
    WATER4(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID4), TypeIds.WATER, false, true, false, ShapeIds.LIQUID4, Block.LIQUID_LEVEL4),
    WATER5(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID5), TypeIds.WATER, false, true, false, ShapeIds.LIQUID5, Block.LIQUID_LEVEL5),
    WATER(BlockIds.getName(TypeIds.WATER, ShapeIds.LIQUID), TypeIds.WATER, false, true, false, ShapeIds.LIQUID, Block.LIQUID_FULL),
    WATER_SOURCE(BlockIds.WATER_SOURCE, TypeIds.WATER, false, true, false, ShapeIds.LIQUID5, Block.LIQUID_SOURCE),

    STONE_BRICKS(TypeIds.STONE_BRICKS, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    STONE_BRICKS2("stone_bricks2", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    STONE_BRICKS3("stone_bricks3", true, false, false, ShapeIds.CUBE),
    MOSSY_STONE_BRICKS(TypeIds.MOSSY_STONE_BRICKS, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),

    SPRUCE_LOG(TypeIds.SPRUCE_LOG, true, false, true, IalonShapeSet.STANDARD_SHAPES_NO_STAIRS.getShapes()),
    SPRUCE_PLANKS(TypeIds.SPRUCE_PLANKS, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    SPRUCE_LEAVES(TypeIds.SPRUCE_LEAVES, true, true, false, ShapeIds.CUBE),

    SNOW(TypeIds.SNOW, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    ITEM_SEAWEED("item_seaweed", false, false, false, ShapeIds.CROSS_PLANE),
    ITEM_GRASS(TypeIds.ITEM_GRASS, false, false, false, ShapeIds.CROSS_PLANE),
    SCALE(TypeIds.SCALE, false, false, false, ShapeIds.SQUARE_NORTH, ShapeIds.SQUARE_SOUTH, ShapeIds.SQUARE_WEST, ShapeIds.SQUARE_EAST),
    RAIL(TypeIds.RAIL, true, false, false, ShapeIds.SQUARE_HE, ShapeIds.SQUARE_HS, ShapeIds.WEDGE_NORTH, ShapeIds.WEDGE_EAST, ShapeIds.WEDGE_SOUTH, ShapeIds.WEDGE_WEST),
    RAIL_CURVED(TypeIds.RAIL_CURVED, true, false, false, ShapeIds.SQUARE_HE, ShapeIds.SQUARE_HW, ShapeIds.SQUARE_HS, ShapeIds.SQUARE_HN),
    RAIL_SLOPE(TypeIds.RAIL_SLOPE, true, false, true, ShapeIds.WEDGE_NORTH, ShapeIds.WEDGE_EAST, ShapeIds.WEDGE_SOUTH, ShapeIds.WEDGE_WEST),
    SAND(TypeIds.SAND, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),

    OAK_LOG(TypeIds.OAK_LOG, true, false, true, IalonShapeSet.STANDARD_SHAPES_NO_STAIRS.getShapes()),
    OAK_PLANKS(TypeIds.OAK_PLANKS, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    OAK_LEAVES(TypeIds.OAK_LEAVES, true, true, false, ShapeIds.CUBE),

    ROCK(TypeIds.ROCK, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),

    PALM_TREE_LOG(TypeIds.PALM_TREE_LOG, true, false, true, IalonShapeSet.STANDARD_SHAPES_NO_STAIRS.getShapes()),
    PALM_TREE_PLANKS(TypeIds.PALM_TREE_PLANKS, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    PALM_TREE_LEAVES(TypeIds.PALM_TREE_LEAVES, true, true, false, ShapeIds.CUBE),

    GRASS(TypeIds.GRASS, true, false, true, ShapeIds.CUBE, ShapeIds.SLAB, ShapeIds.DOUBLE_SLAB),
    GRASS_SNOW(TypeIds.GRASS_SNOW, true, false, true, ShapeIds.CUBE, ShapeIds.SLAB, ShapeIds.DOUBLE_SLAB),
    GRAVEL(TypeIds.GRAVEL, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    DIRT(TypeIds.DIRT, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    COBBLESTONE(TypeIds.COBBLESTONE, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    MOSSY_COBBLESTONE(TypeIds.MOSSY_COBBLESTONE, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    BRICKS(TypeIds.BRICKS, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    PAVING("paving", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),

    BIRCH_LOG(TypeIds.BIRCH_LOG, true, false, true, IalonShapeSet.STANDARD_SHAPES_NO_STAIRS.getShapes()),
    BIRCH_PLANKS(TypeIds.BIRCH_PLANKS, true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    BIRCH_LEAVES(TypeIds.BIRCH_LEAVES, true, true, false, ShapeIds.CUBE),

    TILE_RED("tile_red", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    SLATE("slate", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    METAL1("metal1", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    METAL2("metal2", true, true, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    METAL3("metal3", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    METAL4("metal4", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    METAL5("metal5", true, true, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),

    COLOR_BLACK("color_black", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    COLOR_BLUE("color_blue", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    COLOR_CYAN("color_cyan", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    COLOR_GREEN("color_green", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    COLOR_MAGENTA("color_magenta", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    COLOR_ORANGE("color_orange", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    COLOR_RED("color_red", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    COLOR_RED2("color_red2", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    COLOR_ROSE("color_rose", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),
    COLOR_YELLOW("color_yellow", true, false, false, IalonShapeSet.STANDARD_SHAPES.getShapes()),

    BOOKS("books", true, false, true, ShapeIds.CUBE),
    OVEN("oven", true, false, true, ShapeIds.CUBE),
    DRAWERS("drawers", true, false, true, ShapeIds.CUBE),
    BED("bed", true, false, true, ShapeIds.SLAB),
    BEDPILLOW("bedpillow", true, false, true, ShapeIds.SLAB),

    WHITE_LIGHT(TypeIds.WHITE_LIGHT, true, false, false, true,
            ShapeIds.SHORT_POLE, ShapeIds.SHORT_POLE_DOWN, ShapeIds.SHORT_POLE_EAST, ShapeIds.SHORT_POLE_WEST, ShapeIds.SHORT_POLE_SOUTH, ShapeIds.SHORT_POLE_NORTH);

    private final String name;
    private final String type;
    private final boolean solid;
    private final boolean transparent;
    private final boolean multitexture;
    private final boolean torchlight;
    private final String[] shapes;
    private final byte[] waterLevels;

    IalonBlock(String type, boolean solid, boolean transparent, boolean multitexture, String... shapes) {
        this.name = null; // Automatically generated based on type, shape and water level
        this.type = type;
        this.transparent = transparent;
        this.solid = solid;
        this.multitexture = multitexture;
        this.torchlight = false;
        this.shapes = shapes;
        this.waterLevels = WaterLevel.getAllLevels();
    }

    IalonBlock(String type, boolean solid, boolean transparent, boolean multitexture, boolean torchlight, String... shapes) {
        this.name = null; // Automatically generated based on type, shape and water level
        this.type = type;
        this.transparent = transparent;
        this.solid = solid;
        this.multitexture = multitexture;
        this.torchlight = torchlight;
        this.shapes = shapes;
        this.waterLevels = WaterLevel.getAllLevels();
    }

    IalonBlock(String name, String type, boolean solid, boolean transparent, boolean multitexture, String shape, byte waterLevel) {
        this.name = name;
        this.type = type;
        this.transparent = transparent;
        this.solid = solid;
        this.multitexture = multitexture;
        this.torchlight = false;
        this.shapes = new String[]{ shape };
        this.waterLevels = new byte[]{ waterLevel };
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

    public byte[] getWaterLevels() {
        return waterLevels;
    }

    public boolean isTorchlight() {
        return torchlight;
    }
}

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

    STONE_BRICKS(TypeIds.STONE_BRICKS, true, false, false, IalonConfig.getInstance().getStandardShapes()),
    STONE_BRICKS2("stone_bricks2", true, false, false, IalonConfig.getInstance().getStandardShapes()),
    STONE_BRICKS3("stone_bricks3", true, false, false, ShapeIds.CUBE),
    MOSSY_STONE_BRICKS(TypeIds.MOSSY_STONE_BRICKS, true, false, false, IalonConfig.getInstance().getStandardShapes()),

    SPRUCE_LOG(TypeIds.SPRUCE_LOG, true, false, true, IalonConfig.getInstance().getStandardShapesNoStairs()),
    SPRUCE_PLANKS(TypeIds.SPRUCE_PLANKS, true, false, false, IalonConfig.getInstance().getStandardShapes()),
    SPRUCE_LEAVES(TypeIds.SPRUCE_LEAVES, true, true, false, ShapeIds.CUBE),

    SNOW(TypeIds.SNOW, true, false, false, IalonConfig.getInstance().getStandardShapes()),
    ITEM_SEAWEED("item_seaweed", false, false, false, ShapeIds.CROSS_PLANE),
    ITEM_GRASS(TypeIds.ITEM_GRASS, false, false, false, ShapeIds.CROSS_PLANE),
    SCALE(TypeIds.SCALE, false, false, false, ShapeIds.SQUARE_NORTH, ShapeIds.SQUARE_SOUTH, ShapeIds.SQUARE_WEST, ShapeIds.SQUARE_EAST),
    SAND(TypeIds.SAND, true, false, false, IalonConfig.getInstance().getStandardShapes()),

    OAK_LOG(TypeIds.OAK_LOG, true, false, true, IalonConfig.getInstance().getStandardShapesNoStairs()),
    OAK_PLANKS(TypeIds.OAK_PLANKS, true, false, false, IalonConfig.getInstance().getStandardShapes()),
    OAK_LEAVES(TypeIds.OAK_LEAVES, true, true, false, ShapeIds.CUBE),

    ROCK(TypeIds.ROCK, true, false, false, IalonConfig.getInstance().getStandardShapes()),

    PALM_TREE_LOG(TypeIds.PALM_TREE_LOG, true, false, true, IalonConfig.getInstance().getStandardShapesNoStairs()),
    PALM_TREE_PLANKS(TypeIds.PALM_TREE_PLANKS, true, false, false, IalonConfig.getInstance().getStandardShapes()),
    PALM_TREE_LEAVES(TypeIds.PALM_TREE_LEAVES, true, true, false, ShapeIds.CUBE),

    GRASS(TypeIds.GRASS, true, false, true, ShapeIds.CUBE, ShapeIds.SLAB, ShapeIds.DOUBLE_SLAB),
    GRASS_SNOW(TypeIds.GRASS_SNOW, true, false, true, ShapeIds.CUBE, ShapeIds.SLAB, ShapeIds.DOUBLE_SLAB),
    GRAVEL(TypeIds.GRAVEL, true, false, false, IalonConfig.getInstance().getStandardShapes()),
    DIRT(TypeIds.DIRT, true, false, false, IalonConfig.getInstance().getStandardShapes()),
    COBBLESTONE(TypeIds.COBBLESTONE, true, false, false, IalonConfig.getInstance().getStandardShapes()),
    MOSSY_COBBLESTONE(TypeIds.MOSSY_COBBLESTONE, true, false, false, IalonConfig.getInstance().getStandardShapes()),
    BRICKS(TypeIds.BRICKS, true, false, false, IalonConfig.getInstance().getStandardShapes()),

    BIRCH_LOG(TypeIds.BIRCH_LOG, true, false, true, IalonConfig.getInstance().getStandardShapesNoStairs()),
    BIRCH_PLANKS(TypeIds.BIRCH_PLANKS, true, false, false, IalonConfig.getInstance().getStandardShapes()),
    BIRCH_LEAVES(TypeIds.BIRCH_LEAVES, true, true, false, ShapeIds.CUBE),

    TILE_RED("tile_red", true, false, false, IalonConfig.getInstance().getStandardShapes()),
    SLATE("slate", true, false, false, IalonConfig.getInstance().getStandardShapes()),
    METAL1("metal1", true, false, false, IalonConfig.getInstance().getStandardShapes()),
    METAL2("metal2", true, true, false, IalonConfig.getInstance().getStandardShapes()),
    METAL3("metal3", true, false, false, IalonConfig.getInstance().getStandardShapes()),
    METAL4("metal4", true, false, false, IalonConfig.getInstance().getStandardShapes()),
    METAL5("metal5", true, true, false, IalonConfig.getInstance().getStandardShapes()),

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
        this.waterLevels = IalonConfig.getInstance().getAllLevels();
    }

    IalonBlock(String type, boolean solid, boolean transparent, boolean multitexture, boolean torchlight, String... shapes) {
        this.name = null; // Automatically generated based on type, shape and water level
        this.type = type;
        this.transparent = transparent;
        this.solid = solid;
        this.multitexture = multitexture;
        this.torchlight = torchlight;
        this.shapes = shapes;
        this.waterLevels = IalonConfig.getInstance().getAllLevels();
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

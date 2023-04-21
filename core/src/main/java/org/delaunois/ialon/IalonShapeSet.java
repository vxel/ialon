package org.delaunois.ialon;

import com.rvandoosselaer.blocks.ShapeIds;

public enum IalonShapeSet {

    STANDARD_SHAPES_NO_STAIRS(
            new String[] {
                    ShapeIds.CUBE,
                    ShapeIds.PYRAMID,
                    ShapeIds.POLE,
                    ShapeIds.FENCE,
                    ShapeIds.SLAB,
                    ShapeIds.DOUBLE_SLAB,
                    ShapeIds.PLATE,
                    ShapeIds.WEDGE_NORTH,
                    ShapeIds.WEDGE_EAST,
                    ShapeIds.WEDGE_SOUTH,
                    ShapeIds.WEDGE_WEST,
                    ShapeIds.WEDGE_INVERTED_NORTH,
                    ShapeIds.WEDGE_INVERTED_EAST,
                    ShapeIds.WEDGE_INVERTED_SOUTH,
                    ShapeIds.WEDGE_INVERTED_WEST,
            }
    ),
    STANDARD_SHAPES(new String[]{
            ShapeIds.CUBE,
            ShapeIds.PYRAMID,
            ShapeIds.POLE,
            ShapeIds.FENCE,
            ShapeIds.SLAB,
            ShapeIds.DOUBLE_SLAB,
            ShapeIds.PLATE,
            ShapeIds.WEDGE_NORTH,
            ShapeIds.WEDGE_EAST,
            ShapeIds.WEDGE_SOUTH,
            ShapeIds.WEDGE_WEST,
            ShapeIds.WEDGE_INVERTED_NORTH,
            ShapeIds.WEDGE_INVERTED_EAST,
            ShapeIds.WEDGE_INVERTED_SOUTH,
            ShapeIds.WEDGE_INVERTED_WEST,
            ShapeIds.STAIRS_NORTH,
            ShapeIds.STAIRS_EAST,
            ShapeIds.STAIRS_SOUTH,
            ShapeIds.STAIRS_WEST,
            ShapeIds.STAIRS_INVERTED_NORTH,
            ShapeIds.STAIRS_INVERTED_EAST,
            ShapeIds.STAIRS_INVERTED_SOUTH,
            ShapeIds.STAIRS_INVERTED_WEST,
            ShapeIds.STAIRS_INNER_CORNER_NORTH,
            ShapeIds.STAIRS_INNER_CORNER_EAST,
            ShapeIds.STAIRS_INNER_CORNER_SOUTH,
            ShapeIds.STAIRS_INNER_CORNER_WEST,
            ShapeIds.STAIRS_INVERTED_INNER_CORNER_NORTH,
            ShapeIds.STAIRS_INVERTED_INNER_CORNER_EAST,
            ShapeIds.STAIRS_INVERTED_INNER_CORNER_SOUTH,
            ShapeIds.STAIRS_INVERTED_INNER_CORNER_WEST,
            ShapeIds.STAIRS_OUTER_CORNER_NORTH,
            ShapeIds.STAIRS_OUTER_CORNER_EAST,
            ShapeIds.STAIRS_OUTER_CORNER_SOUTH,
            ShapeIds.STAIRS_OUTER_CORNER_WEST,
            ShapeIds.STAIRS_INVERTED_OUTER_CORNER_NORTH,
            ShapeIds.STAIRS_INVERTED_OUTER_CORNER_EAST,
            ShapeIds.STAIRS_INVERTED_OUTER_CORNER_SOUTH,
            ShapeIds.STAIRS_INVERTED_OUTER_CORNER_WEST
    });

    private final String[] shapes;

    IalonShapeSet(String[] shapes) {
        this.shapes = shapes;
    }

    public String[] getShapes() {
        return shapes;
    }
}


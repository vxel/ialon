package org.delaunois.ialon.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreationPlacementStateTest {

    @Test
    void rotateShapeCyclesHorizontalDirectionsAndPreservesShapeFamily() {
        // north -> east -> south -> west -> north
        assertEquals("stairs_east", CreationPlacementState.rotateShapeY("stairs_north"));
        assertEquals("stairs_south", CreationPlacementState.rotateShapeY("stairs_east"));
        assertEquals("stairs_west", CreationPlacementState.rotateShapeY("stairs_south"));
        assertEquals("stairs_north", CreationPlacementState.rotateShapeY("stairs_west"));
    }

    @Test
    void rotateShapeKeepsCompoundFamiliesAndInvertedVariants() {
        assertEquals("wedge_inverted_east", CreationPlacementState.rotateShapeY("wedge_inverted_north"));
        assertEquals("stairs_outer_corner_east", CreationPlacementState.rotateShapeY("stairs_outer_corner_north"));
        assertEquals("door_east", CreationPlacementState.rotateShapeY("door_north"));
    }

    @Test
    void rotateShapeLeavesNonDirectionalShapesUnchanged() {
        assertEquals("cube_up", CreationPlacementState.rotateShapeY("cube_up"));
        assertEquals("slab_up", CreationPlacementState.rotateShapeY("slab_up"));
        assertEquals("slab_down", CreationPlacementState.rotateShapeY("slab_down"));
        assertEquals("cross_plane", CreationPlacementState.rotateShapeY("cross_plane"));
        assertEquals("", CreationPlacementState.rotateShapeY(""));
    }

    @Test
    void fourRotationsReturnToTheOriginalDirection() {
        String s = "pole_east";
        for (int i = 0; i < 4; i++) {
            s = CreationPlacementState.rotateShapeY(s);
        }
        assertEquals("pole_east", s);
    }

    @Test
    void doorHingeFlipSwapsLeftAndRightAndPreservesLookAndShape() {
        assertEquals("door_right-door_west", CreationPlacementState.flipDoorHinge("door_left-door_west"));
        assertEquals("door_left-door_west", CreationPlacementState.flipDoorHinge("door_right-door_west"));
        // Look suffix (metal/glass) and any trailing segment are preserved.
        assertEquals("door_right_metal-door_north", CreationPlacementState.flipDoorHinge("door_left_metal-door_north"));
        assertEquals("door_left_glass-door_east-0", CreationPlacementState.flipDoorHinge("door_right_glass-door_east-0"));
    }

    @Test
    void doorHingeFlipIsItsOwnInverse() {
        String name = "door_left_metal-door_south";
        assertEquals(name, CreationPlacementState.flipDoorHinge(CreationPlacementState.flipDoorHinge(name)));
    }
}

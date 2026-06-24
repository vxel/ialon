package org.delaunois.ialon.blocks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the door hinge geometry : a closed door must hinge against the wall it opens onto, so doors
 * placed against a jamb open flat against it. The expected neighbours were derived from the engine's
 * actual plate rotation (a {@code plate_north} panel is flush against the SOUTH cell face, etc.) — see
 * WorldManager#doorHingeNeighbour. DOOR_RIGHT is the mirror : for the same closed shape it hinges on
 * the opposite side, which is what lets two coplanar leaves form a double-leaf door.
 */
class DoorHingeTest {

    @Test
    void doorHingesOnTheSideItOpensOnto() {
        assertEquals(Direction.WEST, WorldManager.doorHingeNeighbour(TypeIds.DOOR_LEFT, ShapeIds.PLATE_NORTH));
        assertEquals(Direction.EAST, WorldManager.doorHingeNeighbour(TypeIds.DOOR_LEFT, ShapeIds.PLATE_SOUTH));
        assertEquals(Direction.SOUTH, WorldManager.doorHingeNeighbour(TypeIds.DOOR_LEFT, ShapeIds.PLATE_EAST));
        assertEquals(Direction.NORTH, WorldManager.doorHingeNeighbour(TypeIds.DOOR_LEFT, ShapeIds.PLATE_WEST));
    }

    @Test
    void doorRightHingesOnTheOppositeSide() {
        // Same closed shape as DOOR but the mirror hinge — opposite jamb (enables double-leaf doors).
        assertEquals(Direction.EAST, WorldManager.doorHingeNeighbour(TypeIds.DOOR_RIGHT, ShapeIds.PLATE_NORTH));
        assertEquals(Direction.WEST, WorldManager.doorHingeNeighbour(TypeIds.DOOR_RIGHT, ShapeIds.PLATE_SOUTH));
        assertEquals(Direction.NORTH, WorldManager.doorHingeNeighbour(TypeIds.DOOR_RIGHT, ShapeIds.PLATE_EAST));
        assertEquals(Direction.SOUTH, WorldManager.doorHingeNeighbour(TypeIds.DOOR_RIGHT, ShapeIds.PLATE_WEST));
    }
}

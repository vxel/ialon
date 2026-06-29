package org.delaunois.ialon.serialize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreationTest {

    @Test
    void volumeMatchesDimensions() {
        Creation c = new Creation();
        c.setSizeX(2);
        c.setSizeY(3);
        c.setSizeZ(4);
        assertEquals(24, c.volume());
    }
}

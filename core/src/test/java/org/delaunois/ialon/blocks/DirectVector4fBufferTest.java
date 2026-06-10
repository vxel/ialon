package org.delaunois.ialon.blocks;

import com.jme3.math.Vector4f;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the colour-packing contract of {@link DirectVector4fBuffer#getByteBuffer()}, in particular
 * that the alpha channel (the packed light level) is preserved as an 8-bit field even when it
 * arrives as a NEGATIVE float — which happens whenever a shape reads the light straight from the
 * signed {@code byte[]} lightmap (e.g. CrossPlane / liquids via {@link Chunk#getLightLevel}), where
 * full sun 0xF0 surfaces as -16.0. A regression here renders those blocks black.
 */
class DirectVector4fBufferTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    private static int u(byte b) {
        return b & 0xFF;
    }

    @Test
    void positivePackedAlphaIsPreserved() {
        DirectVector4fBuffer buf = new DirectVector4fBuffer(4);
        // White light, full sun packed as a positive int (vertexColor path) : 0xF0 = 240.
        buf.add(new Vector4f(1f, 1f, 1f, 240f));
        ByteBuffer bb = buf.getByteBuffer();
        assertEquals(255, u(bb.get(0)));
        assertEquals(255, u(bb.get(1)));
        assertEquals(255, u(bb.get(2)));
        assertEquals(240, u(bb.get(3)));
    }

    @Test
    void negativePackedAlphaKeepsLowEightBits() {
        DirectVector4fBuffer buf = new DirectVector4fBuffer(4);
        // getLightLevel path : the signed byte 0xF0 (full sun) reaches us as -16.0. The low 8 bits
        // must round-trip to 240, NOT be clamped to 0.
        buf.add(new Vector4f(1f, 1f, 1f, -16f));
        ByteBuffer bb = buf.getByteBuffer();
        assertEquals(240, u(bb.get(3)));
    }

    @Test
    void colourChannelsAreScaledAndClamped() {
        DirectVector4fBuffer buf = new DirectVector4fBuffer(4);
        buf.add(new Vector4f(0f, 0.5f, 1f, 16f));
        ByteBuffer bb = buf.getByteBuffer();
        assertEquals(0, u(bb.get(0)));
        assertEquals(128, u(bb.get(1)));
        assertEquals(255, u(bb.get(2)));
        assertEquals(16, u(bb.get(3))); // sun 1, torch 0
    }
}

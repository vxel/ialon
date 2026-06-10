package org.delaunois.ialon.blocks;

import com.jme3.math.Vector3f;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the signed-byte packing of {@link DirectVector3fBuffer#getByteBuffer()} used for normals :
 * unit-range components scaled by 127, clamped to [-127, 127], the GPU expands them back to [-1, 1].
 */
class DirectVector3fBufferTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @Test
    void axisNormalPacksToFullRange() {
        DirectVector3fBuffer buf = new DirectVector3fBuffer(2);
        buf.add(new Vector3f(0f, 1f, 0f));
        ByteBuffer bb = buf.getByteBuffer();
        assertEquals(0, bb.get(0));
        assertEquals(127, bb.get(1));
        assertEquals(0, bb.get(2));
    }

    @Test
    void diagonalNormalRoundsPerComponent() {
        // The CrossPlane normal (-0.707, 0, -0.707) : -0.7071 * 127 = -89.8 -> -90.
        DirectVector3fBuffer buf = new DirectVector3fBuffer(2);
        Vector3f n = new Vector3f(-1f, 0f, -1f).normalize();
        buf.add(n);
        ByteBuffer bb = buf.getByteBuffer();
        assertEquals(-90, bb.get(0));
        assertEquals(0, bb.get(1));
        assertEquals(-90, bb.get(2));
    }

    @Test
    void outOfRangeIsClampedNotWrapped() {
        // Defensive : a component slightly past 1 must clamp to 127, never wrap to a negative byte.
        DirectVector3fBuffer buf = new DirectVector3fBuffer(2);
        buf.add(new Vector3f(1.01f, -1.01f, 1f));
        ByteBuffer bb = buf.getByteBuffer();
        assertEquals(127, bb.get(0));
        assertEquals(-127, bb.get(1));
        assertEquals(127, bb.get(2));
    }
}

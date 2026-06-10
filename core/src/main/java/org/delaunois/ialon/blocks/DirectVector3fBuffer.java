package org.delaunois.ialon.blocks;

import com.jme3.math.Vector3f;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DirectVector3fBuffer {

    private static final int INITIAL_CAPACITY = 1000;
    private int size = 0;
    private FloatBuffer buff;

    public DirectVector3fBuffer() {
        buff = BufferUtils.createFloatBuffer(INITIAL_CAPACITY * 3);
    }

    public DirectVector3fBuffer(int capacity) {
        buff = BufferUtils.createFloatBuffer(capacity * 3);
    }

    public void add(Vector3f v) {
        if (buff.position() + 3 > buff.capacity()) {
            increaseCapacity();
        }
        buff.put(v.x).put(v.y).put(v.z);
        size++;
    }

    public FloatBuffer getBuffer() {
        FloatBuffer newbuffer = BufferUtils.createFloatBuffer(buff.position());
        buff.flip();
        newbuffer.put(buff);
        newbuffer.flip();
        return newbuffer;
    }

    /**
     * Returns a tightly-sized {@link ByteBuffer} copy with each component packed as one SIGNED byte,
     * for buffers whose values are unit-range vectors (e.g. NORMALS, in [-1, 1]) : 3 bytes/vertex
     * instead of 12, meant to feed a {@code Byte}, normalized vertex buffer that the GPU expands back
     * to [-1, 1]. Must NOT be used for positions (which exceed [-1, 1] and need full float range).
     * Components are scaled by 127 and clamped to [-127, 127] (avoiding -128, which maps below -1).
     */
    public ByteBuffer getByteBuffer() {
        ByteBuffer newbuffer = BufferUtils.createByteBuffer(buff.position());
        buff.flip();
        while (buff.hasRemaining()) {
            newbuffer.put(toSignedByte(buff.get()));
        }
        newbuffer.flip();
        return newbuffer;
    }

    private static byte toSignedByte(float v) {
        int i = Math.round(v * 127f);
        if (i < -127) {
            i = -127;
        } else if (i > 127) {
            i = 127;
        }
        return (byte) i;
    }

    public FloatBuffer getInternalBuffer() {
        return buff;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        buff.clear();
        size = 0;
    }

    private void increaseCapacity() {
        FloatBuffer newbuffer = BufferUtils.createFloatBuffer(buff.capacity() * 2);
        buff.clear();
        newbuffer.put(buff);
        buff = newbuffer;
    }

}

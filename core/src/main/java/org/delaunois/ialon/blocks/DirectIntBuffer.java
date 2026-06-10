package org.delaunois.ialon.blocks;

import com.jme3.util.BufferUtils;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public class DirectIntBuffer {

    private static final int INITIAL_CAPACITY = 1000;
    private IntBuffer buff;
    private int size = 0;

    public DirectIntBuffer() {
        buff = BufferUtils.createIntBuffer(INITIAL_CAPACITY);
    }

    public DirectIntBuffer(int capacity) {
        buff = BufferUtils.createIntBuffer(capacity);
    }

    public void add(int i) {
        if (buff.position() + 1 > buff.capacity()) {
            increaseCapacity();
        }
        buff.put(i);
        size++;
    }

    public IntBuffer getBuffer() {
        IntBuffer newbuffer = BufferUtils.createIntBuffer(buff.position());
        buff.flip();
        newbuffer.put(buff);
        newbuffer.flip();
        return newbuffer;
    }

    /**
     * Returns a tightly-sized {@link ShortBuffer} copy of the accumulated indices, for meshes whose
     * vertex count fits in an unsigned short (&le; 65536). Halves the index buffer footprint
     * (2 bytes/index instead of 4). The caller is responsible for checking the vertex count ; values
     * &gt; 65535 are truncated to their low 16 bits. Use {@link #getBuffer()} otherwise.
     */
    public ShortBuffer getShortBuffer() {
        ShortBuffer newbuffer = BufferUtils.createShortBuffer(buff.position());
        buff.flip();
        while (buff.hasRemaining()) {
            newbuffer.put((short) buff.get());
        }
        newbuffer.flip();
        return newbuffer;
    }

    public IntBuffer getInternalBuffer() {
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
        IntBuffer newbuffer = BufferUtils.createIntBuffer(buff.capacity() * 2);
        buff.clear();
        newbuffer.put(buff);
        buff = newbuffer;
    }

}

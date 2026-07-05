package org.delaunois.ialon.blocks;

import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * A growable direct {@link FloatBuffer} holding one scalar float per vertex. Used for the per-vertex
 * texture-array layer index (bound as a 1-component {@code TexCoord2} attribute). Mirrors
 * {@link DirectVector2fBuffer}.
 */
public class DirectFloatBuffer {

    private static final int INITIAL_CAPACITY = 1000;
    private FloatBuffer buff;
    private int size = 0;

    public DirectFloatBuffer() {
        buff = BufferUtils.createFloatBuffer(INITIAL_CAPACITY);
    }

    public DirectFloatBuffer(int capacity) {
        buff = BufferUtils.createFloatBuffer(capacity);
    }

    public void add(float v) {
        if (buff.position() + 1 > buff.capacity()) {
            increaseCapacity();
        }
        buff.put(v);
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
     * Quantizes the layer indices to unsigned bytes : 1 byte/vertex instead of 4. Bound NON-normalized,
     * the shader receives the integer index back as a float. Clamped to [0,255] (the array has far fewer
     * than 256 layers).
     */
    public ByteBuffer getByteBuffer() {
        int n = buff.position();
        ByteBuffer bb = BufferUtils.createByteBuffer(n);
        for (int i = 0; i < n; i++) {
            int v = Math.round(buff.get(i));
            if (v < 0) {
                v = 0;
            } else if (v > 255) {
                v = 255;
            }
            bb.put((byte) v);
        }
        bb.flip();
        return bb;
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

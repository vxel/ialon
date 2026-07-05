package org.delaunois.ialon.blocks;

import com.jme3.math.Vector2f;
import com.jme3.util.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class DirectVector2fBuffer {

    private static final int INITIAL_CAPACITY = 1000;
    private FloatBuffer buff;
    private int size = 0;

    public DirectVector2fBuffer() {
        buff = BufferUtils.createFloatBuffer(INITIAL_CAPACITY * 2);
    }

    public DirectVector2fBuffer(int capacity) {
        buff = BufferUtils.createFloatBuffer(capacity * 2);
    }

    public void add(Vector2f v) {
        if (buff.position() + 2 > buff.capacity()) {
            increaseCapacity();
        }
        buff.put(v.x).put(v.y);
        size++;
    }

    public FloatBuffer getBuffer() {
        FloatBuffer newbuffer = BufferUtils.createFloatBuffer(buff.position());
        buff.flip();
        newbuffer.put(buff);
        newbuffer.flip();
        return newbuffer;
    }

    public FloatBuffer getInternalBuffer() {
        return buff;
    }

    /**
     * Quantizes the (block-local, [0,1]) UVs to normalized unsigned shorts : 2 bytes/component instead
     * of 4. Bound with {@code setNormalized(true)}, the shader receives them back in [0,1]. Values are
     * clamped to [0,1] (a stored UV outside the tile would otherwise wrap/overflow). 1/65535 precision
     * is far finer than a 128 px tile texel.
     */
    public ShortBuffer getShortBuffer() {
        int n = buff.position();
        ShortBuffer sb = BufferUtils.createShortBuffer(n);
        for (int i = 0; i < n; i++) {
            float f = buff.get(i);
            if (f < 0f) {
                f = 0f;
            } else if (f > 1f) {
                f = 1f;
            }
            sb.put((short) Math.round(f * 65535f));
        }
        sb.flip();
        return sb;
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

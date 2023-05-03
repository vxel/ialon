package com.rvandoosselaer.blocks;

import com.jme3.math.Vector2f;
import com.jme3.util.BufferUtils;

import java.nio.FloatBuffer;

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

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        buff.clear();
    }

    private void increaseCapacity() {
        FloatBuffer newbuffer = BufferUtils.createFloatBuffer(buff.capacity() * 2);
        buff.clear();
        newbuffer.put(buff);
        buff = newbuffer;
    }

}

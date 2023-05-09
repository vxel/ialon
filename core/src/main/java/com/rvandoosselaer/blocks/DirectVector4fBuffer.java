package com.rvandoosselaer.blocks;

import com.jme3.math.Vector4f;
import com.jme3.util.BufferUtils;

import java.nio.FloatBuffer;

public class DirectVector4fBuffer {

    private static final int INITIAL_CAPACITY = 1000;
    private FloatBuffer buff;
    private int size = 0;

    public DirectVector4fBuffer() {
        buff = BufferUtils.createFloatBuffer(INITIAL_CAPACITY * 4);
    }

    public DirectVector4fBuffer(int capacity) {
        buff = BufferUtils.createFloatBuffer(capacity * 4);
    }

    public void add(Vector4f v) {
        if (buff.position() + 4 > buff.capacity()) {
            increaseCapacity();
        }
        buff.put(v.x).put(v.y).put(v.z).put(v.w);
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

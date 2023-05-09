package com.rvandoosselaer.blocks;

import com.jme3.util.BufferUtils;

import java.nio.IntBuffer;

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
    }

    private void increaseCapacity() {
        IntBuffer newbuffer = BufferUtils.createIntBuffer(buff.capacity() * 2);
        buff.clear();
        newbuffer.put(buff);
        buff = newbuffer;
    }

}

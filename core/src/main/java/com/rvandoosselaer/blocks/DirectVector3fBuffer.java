package com.rvandoosselaer.blocks;

import com.jme3.math.Vector3f;
import com.jme3.util.BufferUtils;

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
        buff.flip();
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

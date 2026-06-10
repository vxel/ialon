package org.delaunois.ialon.blocks;

import com.jme3.math.Vector4f;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
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

    /**
     * Returns a tightly-sized {@link ByteBuffer} copy of the accumulated colours, packed as 4
     * unsigned bytes per vertex (RGBA) instead of 4 floats : a 4x smaller footprint, meant to feed a
     * {@code UnsignedByte}, normalized Color vertex buffer.
     * <p>
     * R, G, B are colour channels in [0, 1] and are scaled to [0, 255]. The A channel is NOT a colour
     * but the packed light level (sunlight in the high nibble, torch in the low nibble, range
     * [0, 255]) and is stored as-is. Shaders reading this buffer get A back in [0, 1] (normalized),
     * so they must multiply by 255 (with rounding) before unpacking the nibbles.
     */
    public ByteBuffer getByteBuffer() {
        ByteBuffer newbuffer = BufferUtils.createByteBuffer(buff.position());
        buff.flip();
        while (buff.hasRemaining()) {
            newbuffer.put(colorByte(buff.get())); // r : [0,1] colour -> [0,255]
            newbuffer.put(colorByte(buff.get())); // g
            newbuffer.put(colorByte(buff.get())); // b
            newbuffer.put(packedByte(buff.get())); // a : packed light bitfield, keep the low 8 bits
        }
        newbuffer.flip();
        return newbuffer;
    }

    /** Scales a [0,1] colour channel to a [0,255] byte (clamped). */
    private static byte colorByte(float v) {
        int i = Math.round(v * 255f);
        if (i < 0) {
            i = 0;
        } else if (i > 255) {
            i = 255;
        }
        return (byte) i;
    }

    /**
     * Packs the alpha channel, which carries the packed light level (sunlight high nibble, torch low
     * nibble), NOT a colour. It must be masked to its low 8 bits and NOT clamped : the value reaches
     * us as a float that may be negative, because {@link Chunk#getLightLevel} writes the raw
     * {@code byte[]} lightmap entry (a signed byte, so 0xF0 = full sun arrives as -16.0). Clamping
     * negatives to 0 would zero the light and render the block black.
     */
    private static byte packedByte(float v) {
        return (byte) (Math.round(v) & 0xFF);
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

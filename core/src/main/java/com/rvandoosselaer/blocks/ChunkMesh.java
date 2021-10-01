package com.rvandoosselaer.blocks;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A class to help create a mesh for a specific block type of a chunk.
 *
 * @author rvandoosselaer
 */
@Slf4j
@Getter
@NoArgsConstructor
public class ChunkMesh {

    private boolean collisionMesh = false;
    private final List<Vector3f> positions = new ArrayList<>();
    private final List<Vector3f> normals = new ArrayList<>();
    private final List<Vector4f> tangents = new ArrayList<>();
    private final List<Vector2f> uvs = new ArrayList<>();
    private final List<Integer> indices = new ArrayList<>();
    private final List<Vector4f> colors = new ArrayList<>();

    public ChunkMesh(boolean collisionMesh) {
        this.collisionMesh = collisionMesh;
    }

    public Mesh generateMesh() {
        long start = System.nanoTime();
        Mesh mesh = new Mesh();
        // all meshes have a position and index buffer
        mesh.setBuffer(VertexBuffer.Type.Position, 3, vector3fToBuffer(positions));
        mesh.setBuffer(VertexBuffer.Type.Index, 1, intToBuffer(indices));

        // collision meshes don't require uvs, normals and tangents
        if (!isCollisionMesh()) {
            mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, vector2fToBuffer(uvs));
            mesh.setBuffer(VertexBuffer.Type.Normal, 3, vector3fToBuffer(normals));
            if (!tangents.isEmpty()) {
                mesh.setBuffer(VertexBuffer.Type.Tangent, 4, vector4fToBuffer(tangents));
            }
            if (!colors.isEmpty()) {
                mesh.setBuffer(VertexBuffer.Type.Color, 4, vector4fToBuffer(colors));
            }
        }
        mesh.updateBound();
        long stop = System.nanoTime();
        if (log.isTraceEnabled()) {
            log.trace("Mesh generation took {}ms", TimeUnit.NANOSECONDS.toMillis(stop - start));
        }
        return mesh;
    }

    public void clear() {
        positions.clear();
        indices.clear();
        uvs.clear();
        normals.clear();
        tangents.clear();
        colors.clear();
    }

    private static FloatBuffer vector3fToBuffer(List<Vector3f> list) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(list.size() * 3);
        for (Vector3f vec : list) {
            buf.put(vec.x).put(vec.y).put(vec.z);
        }
        flipBuffer(buf);
        return buf;
    }

    private static FloatBuffer vector2fToBuffer(List<Vector2f> list) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(list.size() * 2);
        for (Vector2f vec : list) {
            buf.put(vec.x).put(vec.y);
        }
        flipBuffer(buf);
        return buf;
    }

    private static IntBuffer intToBuffer(List<Integer> list) {
        IntBuffer buf = BufferUtils.createIntBuffer(list.size());
        for (int i : list) {
            buf.put(i);
        }
        flipBuffer(buf);
        return buf;
    }

    private static FloatBuffer vector4fToBuffer(List<Vector4f> list) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(list.size() * 4);
        for (Vector4f vec : list) {
            buf.put(vec.x).put(vec.y).put(vec.z).put(vec.w);
        }
        flipBuffer(buf);
        return buf;
    }

    private static void flipBuffer(Buffer buffer) {
        // Since JDK 9, ByteBuffer class overrides some methods and their return type in the Buffer class. To
        // ensure compatibility with JDK 8, calling the 'flipBuffer' method forces using the
        // JDK 8 Buffer's methods signature, and avoids explicit casts.
        buffer.flip();
    }

}

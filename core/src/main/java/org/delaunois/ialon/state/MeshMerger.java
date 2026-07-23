/*
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon.state;

import com.jme3.bounding.BoundingBox;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.mesh.IndexBuffer;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.List;

/**
 * Concatenates several chunk geometries into one mesh, in world space.
 *
 * <p>Extracted from {@link ChunkMeshBatchState} so the per-region batcher
 * ({@link ChunkRegionBatchState}) reuses it rather than duplicating it — the value here is not the
 * concatenation, which is easy, but the accumulated knowledge of how to write nio buffers on ART without
 * paying 10x for it. See {@link #copyBulk} : the JDK and ART disagree about which nio calls are bulk, and
 * getting that wrong was measured on device as milliseconds per merge.
 *
 * <p>One instance is shared by all callers on the render thread : the staging arrays are reused across
 * merges (grown, never shrunk), so a steady-state merge allocates nothing. The TARGET mesh and its buffers
 * belong to the caller, which is what lets one merger serve many regions.
 *
 * <p>Not thread safe, by design : it is render-thread scratch.
 */
class MeshMerger {

    /** Spare capacity kept when a buffer has to grow, so steady-state paging reallocates nothing. */
    private static final float GROWTH_FACTOR = 1.25f;

    /** Largest vertex count addressable with 16-bit (unsigned) indices. */
    private static final int MAX_USHORT_VERTICES = 65536;

    // The merge in progress : set by merge() so the extracted helpers keep their original shape.
    private Mesh target;
    private List<Geometry> sources;

    // World-space extents, accumulated vertex by vertex as the positions are written (see growBound), so
    // the merged bound costs nothing instead of a second O(vertices) pass.
    private float minX;
    private float minY;
    private float minZ;
    private float maxX;
    private float maxY;
    private float maxZ;

    // Reused scratch for the buffer transfers : every copy in this class stages through a primitive array,
    // because that is the only form ART bulk-copies (see copyBulk). Grown, never shrunk, so a steady-state
    // merge allocates nothing.
    private float[] positionStaging;
    private short[] indexStaging;
    private float[] floatStaging;
    private short[] shortStaging;
    private int[] intStaging;

    /** Sources of the last merge that could not fill the batch's attribute set. Must stay 0. */
    private int mismatched;

    int getMismatchedSources() {
        return mismatched;
    }

    /** Time spent in getWorldTranslation() during the last merge, for the callers' slow-merge logs. */
    private long parentLookupNanos;

    long getParentLookupNanos() {
        return parentLookupNanos;
    }

    /**
     * The source with the most buffers, used as the layout template.
     *
     * <p><b>Not</b> {@code sources.get(0)}, which is what this did at first and what made merged output
     * depend on page attach order : chunk meshes do NOT all carry the same attributes ({@code TexCoord2},
     * {@code Tangent} and {@code Color} are each conditional in {@code ChunkMesh.generateMesh}), so a batch
     * whose first source happened to be the narrow one lost those attributes for every other source. It
     * showed up as two runs of the same build rendering grossly differently — a 0.52 peak pixel difference
     * where the noise floor is 1/255.
     *
     * <p>Taking the widest is necessary but not sufficient : a source narrower than the template leaves its
     * range of that buffer unwritten, which {@link #mergeInto} reports rather than pass off silently.
     */
    private static Geometry widestSource(List<Geometry> sources) {
        Geometry widest = sources.get(0);
        int most = widest.getMesh().getBufferList().size();
        for (int i = 1; i < sources.size(); i++) {
            int count = sources.get(i).getMesh().getBufferList().size();
            if (count > most) {
                most = count;
                widest = sources.get(i);
            }
        }
        return widest;
    }

    /**
     * Merges {@code sources} into {@code target} and writes the world-space bound into {@code bound}.
     *
     * @return the number of vertices merged, or 0 when there was nothing to do
     */
    int merge(List<Geometry> sources, Mesh target, BoundingBox bound) {
        if (sources.isEmpty()) {
            return 0;
        }
        this.target = target;
        this.sources = sources;

        int totalVerts = 0;
        int totalIndices = 0;
        for (Geometry source : sources) {
            Mesh mesh = source.getMesh();
            totalVerts += mesh.getVertexCount();
            totalIndices += mesh.getTriangleCount() * 3;
        }
        if (totalVerts == 0 || totalIndices == 0) {
            return 0;
        }

        prepareBuffers(widestSource(sources).getMesh(), totalVerts, totalIndices);
        mergeInto(totalVerts);
        // NOT target.updateBound() : jME computes a mesh bound with BoundingBox.computeFromPoints, which
        // walks the whole position buffer one float at a time — the same per-element buffer access that made
        // the copy phase so expensive on ART, measured at 11.7 ms for a 12916-vertex merge, i.e. more than
        // the whole rest of the merge put together once the copy was fixed. The merge already visits every
        // vertex in world space inside a JIT-friendly array loop (copyPositions), so the bound comes out of
        // that for six comparisons per vertex and the O(n) buffer pass disappears.
        //
        // The bound instance belongs to the CALLER, one per target mesh : the mesh keeps a reference to it,
        // so sharing one across targets would make every merge mutate the previous mesh's bound.
        bound.setMinMax(new Vector3f(minX, minY, minZ), new Vector3f(maxX, maxY, maxZ));
        target.setBound(bound);
        target.updateCounts();
        this.sources = null;
        this.target = null;
        return totalVerts;
    }

    /**
     * Sizes the output buffers, mirroring the source's buffer set (component count, format and
     * normalization) so any vertex compression the mesher uses — byte normals, unsigned-short UVs,
     * unsigned-byte colours — survives the merge untouched. Buffers are reused as long as they are big
     * enough : in steady state a rebuild allocates nothing.
     */
    private void prepareBuffers(Mesh source, int totalVerts, int totalIndices) {
        for (VertexBuffer in : source.getBufferList().getArray()) {
            if (in.getBufferType() == VertexBuffer.Type.Index) {
                continue;
            }
            VertexBuffer out = target.getBuffer(in.getBufferType());
            if (out == null || out.getNumComponents() != in.getNumComponents()
                    || out.getFormat() != in.getFormat() || capacityOf(out) < totalVerts) {
                out = allocate(in.getBufferType(), in.getFormat(), in.getNumComponents(),
                        grown(totalVerts), in.isNormalized());
                // setBuffer refuses to replace a type already present : drop the outgrown one first.
                target.clearBuffer(in.getBufferType());
                target.setBuffer(out);
            }
        }

        // 16-bit indices while the merged sheet fits, exactly like the per-chunk meshes.
        VertexBuffer.Format format = totalVerts <= MAX_USHORT_VERTICES
                ? VertexBuffer.Format.UnsignedShort : VertexBuffer.Format.UnsignedInt;
        VertexBuffer index = target.getBuffer(VertexBuffer.Type.Index);
        if (index == null || index.getFormat() != format || capacityOf(index) < totalIndices) {
            target.clearBuffer(VertexBuffer.Type.Index);
            target.setBuffer(allocate(VertexBuffer.Type.Index, format, 1, grown(totalIndices), false));
        }
    }

    private static VertexBuffer allocate(VertexBuffer.Type type, VertexBuffer.Format format,
                                         int components, int elements, boolean normalized) {
        VertexBuffer vb = new VertexBuffer(type);
        vb.setupData(VertexBuffer.Usage.Dynamic, components, format,
                VertexBuffer.createBuffer(format, components, elements));
        vb.setNormalized(normalized);
        return vb;
    }

    private static int grown(int elements) {
        return Math.max(1, (int) (elements * GROWTH_FACTOR));
    }

    /** Element capacity of a vertex buffer (its data buffer's capacity, in elements). */
    private static int capacityOf(VertexBuffer vb) {
        return vb.getData().capacity() / vb.getNumComponents();
    }

    /**
     * Copies every source into the batch buffers. Positions and indices are the only ones needing work :
     * chunk meshes are chunk-local, so each vertex is offset by its page's world translation, and each
     * index is rebased by the running vertex count. The rest is a straight format-agnostic bulk copy.
     *
     * <p>Everything here is written to allocate nothing and to touch the buffers only in bulk. Both
     * matter, for different reasons and on different platforms : per-element {@code get}/{@code put} is
     * an order of magnitude dearer on ART than on a desktop JVM (that loop alone was milliseconds on
     * device), while a merge of a hundred chunks of ten vertices each — an open sea's calm surface — is
     * dominated by the PER-SOURCE overhead, so a temporary read-only view per source per buffer is not
     * affordable either. {@link VertexBuffer#copyElements} allocates one such view per call.
     */
    private void mergeInto(int totalVerts) {
        parentLookupNanos = 0;
        minX = Float.POSITIVE_INFINITY;
        minY = Float.POSITIVE_INFINITY;
        minZ = Float.POSITIVE_INFINITY;
        maxX = Float.NEGATIVE_INFINITY;
        maxY = Float.NEGATIVE_INFINITY;
        maxZ = Float.NEGATIVE_INFINITY;
        // The buffers outlive a single merge and their limits still describe the PREVIOUS content ;
        // puts are bounded by the limit, not the capacity, so open them up before writing.
        for (VertexBuffer vb : target.getBufferList().getArray()) {
            vb.getData().clear();
        }

        int vertexOffset = 0;
        int indexOffset = 0;
        mismatched = 0;

        for (Geometry source : sources) {
            Mesh mesh = source.getMesh();
            int vertexCount = mesh.getVertexCount();

            int filled = 0;
            for (VertexBuffer in : mesh.getBufferList().getArray()) {
                VertexBuffer.Type type = in.getBufferType();
                if (type == VertexBuffer.Type.Index || type == VertexBuffer.Type.Position) {
                    continue; // both need a value fix-up : handled below
                }
                VertexBuffer out = target.getBuffer(type);
                if (out != null) {
                    copyBulk(in, out, vertexOffset, vertexCount);
                    filled++;
                }
            }
            // Position and Index are handled separately, hence the -2 on the template's count. A shortfall
            // means this source lacks an attribute the batch has, so its slice of that buffer keeps whatever
            // the previous merge left there — visible corruption. Loud, because it is silent otherwise.
            if (filled < target.getBufferList().size() - 2) {
                mismatched++;
            }
            // Timed apart : jME's getWorldTranslation() is not a plain getter, it calls
            // checkDoTransformUpdate(), which walks up the parent chain and takes a TempVars. With pages
            // attaching and detaching every frame there is real work behind some of these calls, and it
            // is the one per-source cost that the phase splits above cannot separate out.
            long t0 = System.nanoTime();
            Vector3f origin = source.getParent().getWorldTranslation();
            parentLookupNanos += System.nanoTime() - t0;
            copyPositions(mesh, vertexOffset, vertexCount, origin);

            int indexCount = mesh.getTriangleCount() * 3;
            copyIndices(mesh, indexOffset, indexCount, vertexOffset);

            vertexOffset += vertexCount;
            indexOffset += indexCount;
        }

        // jME reads the counts from the buffer limits, so they must describe THIS merge, not the
        // (possibly larger) capacity kept from a previous one.
        for (VertexBuffer vb : target.getBufferList().getArray()) {
            int elements = vb.getBufferType() == VertexBuffer.Type.Index ? indexOffset : totalVerts;
            Buffer data = vb.getData();
            data.limit(elements * vb.getNumComponents());
            data.position(0);
            vb.updateData(data); // flags the buffer for re-upload
        }
    }

    /**
     * Copies {@code elements} elements from the start of a source buffer into a destination buffer at an
     * element offset, whatever the format. This is {@link VertexBuffer#copyElements} without its per-call
     * read-only view : it borrows the source's own position/limit and puts them back, which is safe because
     * chunk render meshes are only ever touched from the render thread (the physics thread works on the
     * separate collision meshes) and this runs inside the update phase.
     *
     * <p>Everything goes through a primitive array rather than the one-liner
     * {@code ((FloatBuffer) out).put((FloatBuffer) in)}, for the reason that keeps catching this class out :
     * <b>the JDK and ART disagree about which nio calls are bulk</b>. Verified against the platform sources
     * (android-32 {@code java/nio}), for the {@code ByteBufferAsXBuffer} views that jME's direct buffers
     * actually are :
     *
     * <pre>
     *   put(FloatBuffer/ShortBuffer/IntBuffer)  no override -> base class loops "for (i) put(src.get())"
     *   get(float[]/short[]/int[], off, len)    overridden  -> bb.getUnchecked, one bulk memory copy
     *   put(float[]/short[]/int[], off, len)    overridden  -> bb.putUnchecked, one bulk memory copy
     *   ByteBuffer.put(ByteBuffer)              patched by Android -> Memory.memmove
     * </pre>
     *
     * So buffer-to-buffer put is a per-element loop on device for every type <i>except</i> bytes — while
     * OpenJDK's {@code DirectFloatBufferU} does override it with a memory copy, which is exactly why this
     * looked fine on the desktop. It was measured on device as the dominant cost of the merge : ~1.3 us per
     * vertex for volumetric water, ~3.3 us for the calm surface, i.e. 7-11 ms merges warning about their own
     * slowness. Array staging is what {@link #copyPositions} had already been forced into for the same
     * reason ; this is the same fix for the remaining buffers.
     */
    private void copyBulk(VertexBuffer inVb, VertexBuffer outVb, int outElement, int elements) {
        int components = inVb.getNumComponents();
        Buffer in = inVb.getData();
        Buffer out = outVb.getData();
        int count = elements * components;
        int inPosition = in.position();
        int inLimit = in.limit();
        int outPosition = out.position();
        in.clear().limit(count);
        out.position(outElement * components);

        if (in instanceof FloatBuffer) {
            float[] staging = floatStaging(count);
            ((FloatBuffer) in).get(staging, 0, count);
            ((FloatBuffer) out).put(staging, 0, count);
        } else if (in instanceof ShortBuffer) {
            short[] staging = shortStaging(count);
            ((ShortBuffer) in).get(staging, 0, count);
            ((ShortBuffer) out).put(staging, 0, count);
        } else if (in instanceof IntBuffer) {
            int[] staging = intStaging(count);
            ((IntBuffer) in).get(staging, 0, count);
            ((IntBuffer) out).put(staging, 0, count);
        } else {
            // The only type Android bulk-copies buffer to buffer directly (Memory.memmove).
            ((ByteBuffer) out).put((ByteBuffer) in);
        }

        in.limit(inLimit).position(inPosition);
        out.position(outPosition);
    }

    /**
     * Copies one chunk's positions, shifted from chunk-local to world coordinates.
     *
     * <p>It goes through a plain {@code float[]} on purpose. The obvious version — copy the buffer, then
     * add the offset with absolute {@code FloatBuffer.get/put} — costs six bounds-checked buffer accesses
     * per vertex, and on ART (where these are far less optimised than on a desktop JVM) that loop alone
     * dominated the merge : several milliseconds for a few thousand vertices on device. A bulk get, a
     * tight array loop the JIT can actually optimise, and a bulk put replace it with two memory copies.
     */
    private void copyPositions(Mesh mesh, int vertexOffset, int vertexCount, Vector3f translation) {
        VertexBuffer inVb = mesh.getBuffer(VertexBuffer.Type.Position);
        int floats = vertexCount * 3;
        float[] staging = positionStaging(floats);
        FloatBuffer in = (FloatBuffer) inVb.getData();
        int inPosition = in.position();
        int inLimit = in.limit();
        in.clear();
        in.get(staging, 0, floats);
        in.limit(inLimit).position(inPosition);

        float tx = translation.x;
        float ty = translation.y;
        float tz = translation.z;
        for (int i = 0; i < floats; i += 3) {
            float x = staging[i] + tx;
            float y = staging[i + 1] + ty;
            float z = staging[i + 2] + tz;
            staging[i] = x;
            staging[i + 1] = y;
            staging[i + 2] = z;
            growBound(x, y, z);
        }

        FloatBuffer out = (FloatBuffer) target.getBuffer(VertexBuffer.Type.Position).getData();
        int outPosition = out.position();
        out.position(vertexOffset * 3);
        out.put(staging, 0, floats);
        out.position(outPosition);
    }

    /**
     * Copies one chunk's indices, rebased by the running vertex count. Same reasoning as
     * {@link #copyPositions} : the readable version walks an {@link IndexBuffer} wrapper one element at a
     * time through two virtual calls, which is the other half of the cost on device. Chunk meshes always
     * index with unsigned shorts, and so does the output as long as the merge fits in 65536 vertices —
     * the common case gets two bulk transfers around one array loop, and the oversized case falls back to
     * the element-wise path.
     */
    private void copyIndices(Mesh mesh, int indexOffset, int indexCount, int vertexOffset) {
        Buffer in = mesh.getBuffer(VertexBuffer.Type.Index).getData();
        Buffer out = target.getBuffer(VertexBuffer.Type.Index).getData();
        if (in instanceof ShortBuffer && out instanceof ShortBuffer) {
            short[] staging = indexStaging(indexCount);
            ShortBuffer typedIn = (ShortBuffer) in;
            int inPosition = typedIn.position();
            int inLimit = typedIn.limit();
            typedIn.clear();
            typedIn.get(staging, 0, indexCount);
            typedIn.limit(inLimit).position(inPosition);
            // The indices stay below 65536 (that is the condition for a short output buffer), so the
            // unsigned wrap-around of the short arithmetic is exactly the value we want to store.
            short offset = (short) vertexOffset;
            for (int i = 0; i < indexCount; i++) {
                staging[i] += offset;
            }
            ShortBuffer typedOut = (ShortBuffer) out;
            int outPosition = typedOut.position();
            typedOut.position(indexOffset);
            typedOut.put(staging, 0, indexCount);
            typedOut.position(outPosition);
            return;
        }
        IndexBuffer inIndex = mesh.getIndicesAsList();
        IndexBuffer outIndex = target.getIndexBuffer();
        for (int i = 0; i < indexCount; i++) {
            outIndex.put(indexOffset + i, inIndex.get(i) + vertexOffset);
        }
    }

    /** Grows the merged bound to contain one world-space vertex. See {@link #finishGeometry}. */
    private void growBound(float x, float y, float z) {
        if (x < minX) {
            minX = x;
        }
        if (x > maxX) {
            maxX = x;
        }
        if (y < minY) {
            minY = y;
        }
        if (y > maxY) {
            maxY = y;
        }
        if (z < minZ) {
            minZ = z;
        }
        if (z > maxZ) {
            maxZ = z;
        }
    }

    private float[] positionStaging(int floats) {
        if (positionStaging == null || positionStaging.length < floats) {
            positionStaging = new float[(int) (floats * GROWTH_FACTOR)];
        }
        return positionStaging;
    }

    private short[] indexStaging(int indices) {
        if (indexStaging == null || indexStaging.length < indices) {
            indexStaging = new short[(int) (indices * GROWTH_FACTOR)];
        }
        return indexStaging;
    }

    // Scratch for copyBulk. Separate from the position/index staging above because a source's other
    // buffers are copied before its positions and indices are, and sharing one array across the two would
    // make that ordering load-bearing. Grown, never shrunk : steady-state merges allocate nothing.

    private float[] floatStaging(int count) {
        if (floatStaging == null || floatStaging.length < count) {
            floatStaging = new float[(int) (count * GROWTH_FACTOR)];
        }
        return floatStaging;
    }

    private short[] shortStaging(int count) {
        if (shortStaging == null || shortStaging.length < count) {
            shortStaging = new short[(int) (count * GROWTH_FACTOR)];
        }
        return shortStaging;
    }

    private int[] intStaging(int count) {
        if (intStaging == null || intStaging.length < count) {
            intStaging = new int[(int) (count * GROWTH_FACTOR)];
        }
        return intStaging;
    }

    /**
     * Creates the batch geometry on first use and refreshes its bound. Material, render bucket and
     * transparency layer are taken from a source geometry, so the merged copy renders exactly like the
     * per-chunk ones it replaces (and keeps sharing the live material the water/fire states animate).
     */
}

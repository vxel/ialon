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

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.jme.TextureAtlas;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.blocks.generator.TerrainGenerator;
import org.delaunois.ialon.control.SkyControl;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders distant trees on the {@link FarTerrainState far horizon} as a single batched mesh of GPU
 * billboards, so the woods that cover the loaded chunks keep going to the horizon instead of stopping
 * dead at the chunk-grid edge.
 *
 * <p><b>Placement</b> reuses the voxel generator's exact tree scatter ({@link
 * NoiseTerrainGenerator#forEachTreeAnchor}) : same cells, density, altitude band, slope and species, so a
 * far tree sits where its voxel counterpart would. Anchors inside the loaded-chunk square are skipped
 * (the real voxel trees are there), and beyond it the keep probability tapers with distance so the far
 * field thins out cheaply ; a hard cap bounds the worst case.
 *
 * <p><b>Rendering</b> is one draw call : every tree is a single quad whose 4 vertices share the trunk-base
 * anchor, expanded into a camera-facing, upright (cylindrical) billboard <i>in the vertex shader</i> — no
 * per-frame CPU rotation, no allocation in the render loop. The fragment shader alpha-discards the
 * silhouette (so no depth sorting) and fades the colour into the same horizon fog as the far terrain.
 *
 * <p>The billboard mesh is rebuilt off the render thread (like chunk generation) whenever the player has
 * roamed far enough, then swapped in on the render thread. The world being a torus, anchors are sampled
 * at the player's true world coordinates : the periodic noise makes the distant forest tile seamlessly.
 */
@Slf4j
public class FarTreeState extends BaseAppState {

    /**
     * Silhouette texture per species, indexed by the species ordinal the generator emits
     * (0 = oak, 1 = birch, 2 = spruce, 3 = palm). Packed into the shared block atlas by
     * {@code IalonInitializer.setupAtlasManager} ; FarTreeState resolves their atlas tiles by these keys.
     * Placeholders for now — replace the PNGs (proper tree silhouettes with a transparent background).
     */
    public static final String[] FAR_TREE_TEXTURES = {
            "Textures/FarTree/far_tree_oak.png",
            "Textures/FarTree/far_tree_birch.png",
            "Textures/FarTree/far_tree_spruce.png",
            "Textures/FarTree/far_tree_palm.png"
    };

    // Billboard width as a fraction of its height (trees are taller than wide).
    private static final float WIDTH_RATIO = 0.8f;
    // Keep probability at the far edge of the billboard region (1.0 just outside the chunk grid) : the
    // distant forest thins towards the horizon, where the shader forest tint (FarTerrain) takes over.
    private static final float FAR_KEEP_MIN = 0.4f;
    // Below this alpha a texel is the transparent silhouette background and is skipped (hard alpha test).
    private static final float ALPHA_DISCARD = 0.5f;

    private final IalonConfig config;

    private SimpleApplication app;
    private NoiseTerrainGenerator generator;

    @Getter
    private Geometry treeGeom;
    private Material material;
    // Per-species atlas UVs for the 4 quad corners (bottom-left, bottom-right, top-right, top-left),
    // packed as [species][8] = (u,v) x4, resolved once from the shared atlas. Null = textures missing.
    private float[][] speciesUv;

    // Off-render-thread mesh builder : one daemon thread, results polled in update() and swapped in.
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "far-tree-builder");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean building = new AtomicBoolean(false);
    private volatile Mesh pendingMesh;
    // World XZ the current/last build was centered on (sentinel = nothing built yet -> build on 1st update).
    private float lastBuildX = Float.MAX_VALUE;
    private float lastBuildZ = Float.MAX_VALUE;

    // Light/fog colours, bound once by reference to the live scene colours (mutated in place by the
    // day/night cycle), exactly like FarTerrainState, so the trees dim and tint with the cycle.
    private boolean lightColorsBound = false;
    private boolean fogColorBound = false;
    // World-unit spacing of the far-terrain heightmap grid (extent / (HEIGHTMAP_SIZE - 1)). Trees are
    // grounded on the COARSE far-terrain surface (this grid), not the per-block height, so they don't
    // float above the under-sampled relief the far terrain actually renders. Mirrors FarTerrainState.
    private float farGridStep;

    public FarTreeState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;

        TerrainGenerator gen = config.getTerrainGenerator();
        if (!(gen instanceof NoiseTerrainGenerator)) {
            log.info("Terrain generator has no trees : far trees disabled");
            return;
        }
        this.generator = (NoiseTerrainGenerator) gen;

        // Far-terrain heightmap grid spacing : extent / (HEIGHTMAP_SIZE - 1), with HEIGHTMAP_SIZE = 513
        // and extent = 2*worldSize on the torus (else farTerrainExtent). Must match FarTerrainState.
        float extent = config.getWorldSize() > 0f ? 2f * config.getWorldSize() : config.getFarTerrainExtent();
        farGridStep = extent / 512f;

        if (!resolveSpeciesUv()) {
            this.generator = null; // textures not in the atlas : disable rather than draw garbage
            return;
        }

        material = createMaterial();
        treeGeom = new Geometry("FarTrees", emptyMesh());
        treeGeom.setMaterial(material);
        treeGeom.setShadowMode(RenderQueue.ShadowMode.Off);
        // Opaque alpha-tested billboards : no fade of any kind, so the Opaque bucket (depth-write on,
        // correct occlusion, no depth sorting). The silhouette is a hard alpha test ; trees appear/vanish
        // crisply at the inner clip and the ring edge.
        treeGeom.setQueueBucket(RenderQueue.Bucket.Opaque);
    }

    /** Resolves each species' atlas tile UVs (same in-tile convention as the block faces). */
    private boolean resolveSpeciesUv() {
        TextureAtlas atlas = config.getTextureAtlasManager().getAtlas();
        speciesUv = new float[FAR_TREE_TEXTURES.length][];
        Vector2f in = new Vector2f();
        for (int s = 0; s < FAR_TREE_TEXTURES.length; s++) {
            Texture tex = app.getAssetManager().loadTexture(FAR_TREE_TEXTURES[s]);
            TextureAtlas.TextureAtlasTile tile = atlas.getAtlasTile(tex);
            if (tile == null) {
                log.warn("Far-tree texture {} is not in the atlas : far trees disabled", FAR_TREE_TEXTURES[s]);
                speciesUv = null;
                return false;
            }
            // Corners in tile-local UV : bottom-left, bottom-right, top-right, top-left (block convention).
            float[] uv = new float[8];
            store(uv, 0, tile.getLocation(in.set(0f, 0f), 0f));
            store(uv, 2, tile.getLocation(in.set(1f, 0f), 0f));
            store(uv, 4, tile.getLocation(in.set(1f, 1f), 0f));
            store(uv, 6, tile.getLocation(in.set(0f, 1f), 0f));
            speciesUv[s] = uv;
        }
        return true;
    }

    private static void store(float[] uv, int i, Vector2f loc) {
        uv[i] = loc.x;
        uv[i + 1] = loc.y;
    }

    private Material createMaterial() {
        Material mat = new Material(app.getAssetManager(), "Shaders/FarTree.j3md");
        mat.setBoolean("ManualSrgb", config.isManualGammaEncode());
        mat.setTexture("TreeAtlas", config.getTextureAtlasManager().getDiffuseMap());
        mat.setFloat("FogDistance", config.getFarTerrainFogDistance());
        mat.setFloat("FogDensity", config.getFarTerrainFogDensity());
        // Same inner radius as the far terrain : the square loaded-chunk footprint, with a chunk margin.
        mat.setFloat("InnerRadius", (float) config.getGridRadius() * config.getChunkSize() - config.getChunkSize());
        mat.setFloat("AlphaDiscard", ALPHA_DISCARD);
        // SAME clip-space depth bias as the far terrain : the far terrain is pushed back so the voxels win
        // the depth test ; if the trees kept bias 0 they would win against that pushed-back relief and poke
        // through the hills (appearing to float, un-masked by the terrain). Sharing the bias puts trees and
        // far terrain in the same depth space, so the relief occludes the trees correctly, while both still
        // lose to the (un-biased) voxel chunks near the player.
        mat.setFloat("DepthBias", config.getFarTerrainDepthBias());
        // Lighting / fog colours : placeholders, rebound by reference in update() to the live scene colours.
        mat.setColor("AmbientColor", com.jme3.math.ColorRGBA.White.mult(config.getAmbiantIntensity()));
        mat.setColor("SunColor", com.jme3.math.ColorRGBA.White.mult(config.getSunIntensity()));
        com.jme3.math.ColorRGBA ground = config.getGroundDayColor();
        mat.setColor("FogColor", new com.jme3.math.ColorRGBA(ground.r, ground.g, ground.b, 1f));
        // Single quad facing the camera : render both faces (the winding flips as the camera circles).
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return mat;
    }

    @Override
    protected void onEnable() {
        Node root = app.getRootNode();
        if (treeGeom != null && treeGeom.getParent() == null) {
            root.attachChild(treeGeom);
        }
    }

    @Override
    protected void onDisable() {
        if (treeGeom != null && treeGeom.getParent() != null) {
            treeGeom.removeFromParent();
        }
    }

    @Override
    protected void cleanup(Application application) {
        builder.shutdownNow();
    }

    @Override
    public void update(float tpf) {
        if (generator == null || treeGeom == null) {
            return;
        }

        // Swap in a freshly built mesh (built off-thread, only GL-touched here on the render thread).
        Mesh ready = pendingMesh;
        if (ready != null) {
            pendingMesh = null;
            treeGeom.setMesh(ready);
            treeGeom.updateModelBound();
        }

        bindLiveColors();

        // Rebuild when the player has roamed more than a chunk from the last build centre : the region is
        // an annulus around the player, so it must follow them. Only one build runs at a time.
        Vector3f p = config.getPlayerLocation();
        if (p != null && !building.get()) {
            float threshold = config.getChunkSize();
            if (Math.abs(p.x - lastBuildX) >= threshold || Math.abs(p.z - lastBuildZ) >= threshold) {
                submitBuild(p.x, p.z);
            }
        }
    }

    /** Binds the live ambient/sun/fog colours once, like {@link FarTerrainState}, then they track the cycle. */
    private void bindLiveColors() {
        if (!lightColorsBound) {
            LightingState lighting = getState(LightingState.class);
            if (lighting != null && lighting.getDirectionalLight() != null) {
                material.setColor("AmbientColor", lighting.getAmbientLight().getColor());
                material.setColor("SunColor", lighting.getDirectionalLight().getColor());
                lightColorsBound = true;
            }
        }
        if (!fogColorBound) {
            SkyState sky = getState(SkyState.class);
            SkyControl skyControl = (sky != null) ? sky.getSkyControl() : null;
            if (skyControl != null) {
                material.setColor("FogColor", skyControl.getGroundColor());
                fogColorBound = true;
            }
        }
    }

    private void submitBuild(float centerX, float centerZ) {
        building.set(true);
        lastBuildX = centerX;
        lastBuildZ = centerZ;
        final int cx = Math.round(centerX);
        final int cz = Math.round(centerZ);
        builder.submit(() -> {
            try {
                pendingMesh = buildMesh(cx, cz);
            } catch (Exception e) {
                log.warn("Far-tree mesh build failed", e);
            } finally {
                building.set(false);
            }
        });
    }

    /**
     * Enumerates the tree anchors in the annulus around (cx, cz), thins them with distance, caps the
     * count, and packs them into a single billboard mesh. Runs off the render thread.
     */
    private Mesh buildMesh(int cx, int cz) {
        int innerR = config.getGridRadius() * config.getChunkSize() - config.getChunkSize();
        int region = Math.round(config.getFarTreeDistance());
        // Ground the trunk base on the far-terrain surface (+ vertical offset), then sink it a little so the
        // billboard doesn't appear to float on slopes (its base is a flat line, above the downhill side).
        float voff = config.getFarTerrainVerticalOffset() - config.getFarTreeSink();
        // Scratch for the coarse-height lookups (off-thread, single-threaded builder : safe to share).
        Vector3f scratch = new Vector3f();

        AnchorList list = new AnchorList();
        generator.forEachTreeAnchor(cx - region, cx + region, cz - region, cz + region,
                (wx, wz, gy, species, height) -> {
                    float cheb = Math.max(Math.abs(wx - cx), Math.abs(wz - cz));
                    if (cheb < innerR || cheb > region) {
                        return; // inside the chunk grid (real voxel trees) or outside the round region
                    }
                    // Thin the forest towards the horizon : keep all near the grid, fewer far out.
                    float t = (cheb - innerR) / Math.max(1f, region - (float) innerR);
                    float keep = 1f - t * (1f - FAR_KEEP_MIN);
                    if (hash01((int) wx, (int) wz) > keep) {
                        return;
                    }
                    // Anchor on the COARSE far-terrain surface (not the per-block gy) so the tree sits on
                    // the relief the far terrain actually renders, instead of floating above its under-
                    // sampled surface.
                    list.add(wx, coarseHeight(wx, wz, scratch) + voff, wz, species, height);
                });

        return packMesh(list);
    }

    /**
     * Height of the far-terrain surface at world (wx, wz) : bilinear interpolation of {@code getHeight}
     * over the far-terrain heightmap lattice (spacing {@link #farGridStep}, aligned on multiples of the
     * step like FarTerrainState's grid). This is the surface the far terrain actually renders — grounding
     * trees on it (rather than the per-block height) stops them floating above the under-sampled relief.
     */
    private float coarseHeight(float wx, float wz, Vector3f scratch) {
        float step = farGridStep;
        float x0 = (float) Math.floor(wx / step) * step;
        float z0 = (float) Math.floor(wz / step) * step;
        float fx = (wx - x0) / step;
        float fz = (wz - z0) / step;
        float h00 = generator.getHeight(scratch.set(x0, 0f, z0));
        float h10 = generator.getHeight(scratch.set(x0 + step, 0f, z0));
        float h01 = generator.getHeight(scratch.set(x0, 0f, z0 + step));
        float h11 = generator.getHeight(scratch.set(x0 + step, 0f, z0 + step));
        float hx0 = h00 + (h10 - h00) * fx;
        float hx1 = h01 + (h11 - h01) * fx;
        return hx0 + (hx1 - hx0) * fz;
    }

    private Mesh packMesh(AnchorList a) {
        int maxCount = config.getFarTreeMaxCount();
        int total = a.n;
        int stride = 1;
        int kept = total;
        if (maxCount > 0 && total > maxCount) {
            stride = (total + maxCount - 1) / maxCount;
            kept = (total + stride - 1) / stride;
            log.info("Far trees : {} anchors, capped to {} billboards (stride {})", total, kept, stride);
        } else {
            log.debug("Far trees : {} billboards", total);
        }

        float scale = config.getFarTreeScale();
        int verts = kept * 4;
        FloatBuffer pos = BufferUtils.createFloatBuffer(verts * 3);
        FloatBuffer uv = BufferUtils.createFloatBuffer(verts * 2);
        FloatBuffer corner = BufferUtils.createFloatBuffer(verts * 4);

        boolean useShort = verts <= 65536;
        java.nio.ShortBuffer si = null;
        java.nio.IntBuffer ii = null;
        if (useShort) {
            si = BufferUtils.createShortBuffer(kept * 6);
        } else {
            ii = BufferUtils.createIntBuffer(kept * 6);
        }

        int v = 0;
        for (int i = 0; i < total; i += stride) {
            float wx = a.x[i];
            float wy = a.y[i];
            float wz = a.z[i];
            int s = a.sp[i];
            float h = a.h[i] * scale;
            float w = h * WIDTH_RATIO;

            // Per-species atlas UVs for the 4 corners (resolved once, same convention as the block faces).
            float[] t = speciesUv[s];

            // 4 corners : (cornerX in {-0.5,+0.5}, cornerY in {0,1}, width, height). All share the anchor.
            putVertex(pos, uv, corner, wx, wy, wz, t[0], t[1], -0.5f, 0f, w, h); // bottom-left
            putVertex(pos, uv, corner, wx, wy, wz, t[2], t[3], 0.5f, 0f, w, h);  // bottom-right
            putVertex(pos, uv, corner, wx, wy, wz, t[4], t[5], 0.5f, 1f, w, h);  // top-right
            putVertex(pos, uv, corner, wx, wy, wz, t[6], t[7], -0.5f, 1f, w, h); // top-left

            if (useShort) {
                si.put((short) v).put((short) (v + 1)).put((short) (v + 2));
                si.put((short) v).put((short) (v + 2)).put((short) (v + 3));
            } else {
                ii.put(v).put(v + 1).put(v + 2);
                ii.put(v).put(v + 2).put(v + 3);
            }
            v += 4;
        }

        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, pos);
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, uv);
        mesh.setBuffer(VertexBuffer.Type.TexCoord2, 4, corner);
        if (useShort) {
            mesh.setBuffer(VertexBuffer.Type.Index, 3, si);
        } else {
            mesh.setBuffer(VertexBuffer.Type.Index, 3, ii);
        }
        mesh.updateBound();
        return mesh;
    }

    private static void putVertex(FloatBuffer pos, FloatBuffer uv, FloatBuffer corner,
                                  float wx, float wy, float wz, float u, float vv,
                                  float cornerX, float cornerY, float width, float height) {
        pos.put(wx).put(wy).put(wz);
        uv.put(u).put(vv);
        corner.put(cornerX).put(cornerY).put(width).put(height);
    }

    private static Mesh emptyMesh() {
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(0));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createShortBuffer(0));
        mesh.updateBound();
        return mesh;
    }

    /** Deterministic, position-stable hash in [0, 1) used to thin the far forest reproducibly. */
    private static float hash01(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= (h >>> 16);
        return (h & 0x7fffffff) / (float) 0x7fffffff;
    }

    /** Growable primitive store for tree anchors (avoids one object per anchor during enumeration). */
    private static final class AnchorList {
        float[] x = new float[2048];
        float[] y = new float[2048];
        float[] z = new float[2048];
        float[] h = new float[2048];
        int[] sp = new int[2048];
        int n = 0;

        void add(float ax, float ay, float az, int species, float height) {
            if (n == x.length) {
                int cap = x.length * 2;
                x = Arrays.copyOf(x, cap);
                y = Arrays.copyOf(y, cap);
                z = Arrays.copyOf(z, cap);
                h = Arrays.copyOf(h, cap);
                sp = Arrays.copyOf(sp, cap);
            }
            x[n] = ax;
            y[n] = ay;
            z[n] = az;
            sp[n] = species;
            h[n] = height;
            n++;
        }
    }
}

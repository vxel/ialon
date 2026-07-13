package org.delaunois.ialon.blocks;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.material.MatParamTexture;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Geometry;
import com.jme3.shader.VarType;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureArray;
import com.jme3.texture.image.ColorSpace;
import com.jme3.texture.image.ImageRaster;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static java.util.Optional.empty;

/**
 * A thread safe register for block types. The register is used so only one instance of a type is used throughout the
 * Blocks framework.
 * <p>
 * Types are registered with an explicit appearance : either a diffuse {@code texture} (packed into the block
 * {@link #getBlockTextureArray() texture array}) via {@link #registerTexture(String, String)}, or a full
 * {@code .j3m} {@code material} rendered directly (procedural fire/lava, kept out of the array as it carries no
 * diffuse tile) via {@link #registerMaterial(String, String)}. The former filename-convention theme lookup is gone :
 * the asset paths are supplied explicitly by the caller (from the YAML block catalog).
 *
 * @author rvandoosselaer
 */
@Slf4j
public class TypeRegistry {

    public static final String DEFAULT_BLOCK_MATERIAL = "Blocks/Materials/default-block.j3m";

    private final ConcurrentMap<String, Material> registry = new ConcurrentHashMap<>();
    private final AssetManager assetManager;

    // Shared block materials (both sample the texture array). Built lazily, cached.
    private Material genericMaterial;
    private Material waterMaterial;
    // Built lazily from every registered block type's (expanded) diffuse image. Nulled on (re)register.
    private TextureArray blockTextureArray;
    // type name -> layer index(es) : single tile => [layer] ; multi (top/side/bottom) => 3 consecutive
    // layers matching the vertical thirds the shape emits (base+floor(v*3)).
    private final Map<String, int[]> typeLayers = new ConcurrentHashMap<>();

    public TypeRegistry(@NonNull AssetManager assetManager) {
        this.assetManager = assetManager;
    }

    /**
     * Register a block type from an explicit diffuse texture. The type is materialed with a copy of the
     * default block material and its {@code DiffuseMap} set to the given image, so it is packed into the
     * block texture array.
     *
     * @param name        block type (grass, rock, ...)
     * @param diffusePath asset path of the diffuse texture (e.g. {@code Blocks/Textures/grass.png})
     */
    public Material registerTexture(@NonNull String name, @NonNull String diffusePath) {
        TexturesWrapper textures = new TexturesWrapper(expandTexture(new TextureKey(diffusePath)), empty(), empty());
        return register(name, setTextures(textures, load(DEFAULT_BLOCK_MATERIAL)));
    }

    /**
     * Register a block type from an explicit {@code .j3m} material (e.g. the procedural fire/lava shaders).
     * A material without a {@code DiffuseMap} carries no tile and is therefore skipped by the texture array
     * (see {@link #buildBlockTextureArray()}).
     *
     * @param name         block type (fire, lava, ...)
     * @param materialPath asset path of the material (e.g. {@code Blocks/Textures/fire.j3m})
     */
    public Material registerMaterial(@NonNull String name, @NonNull String materialPath) {
        return register(name, expandMaterialTexture(assetManager.loadMaterial(materialPath)));
    }

    public void applyMaterial(@NonNull Geometry geom, @NonNull String name) {
        geom.setMaterial(TypeIds.WATER.equals(name) ? getWaterMaterial() : getGenericMaterial());
    }

    public void applyGenericMaterial(@NonNull Geometry geom) {
        geom.setMaterial(getGenericMaterial());
    }

    /**
     * The shared block material : samples the block {@link #getBlockTextureArray() texture array} through
     * {@code IalonArray.j3md}. Cached and reused by every generic (opaque) chunk geometry.
     */
    public Material getGenericMaterial() {
        if (this.genericMaterial != null) {
            return this.genericMaterial;
        }
        Material mat = assetManager.loadMaterial("Blocks/Materials/default-block-array.j3m");
        mat.setParam("AlphaDiscardThreshold", VarType.Float, 0.1f);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        mat.setTexture("DiffuseArray", getBlockTextureArray());
        this.genericMaterial = mat;
        return mat;
    }

    /** The flowing-water material : same texture array, with time-based scroll (see water-array.j3m). */
    public Material getWaterMaterial() {
        if (this.waterMaterial != null) {
            return this.waterMaterial;
        }
        Material mat = assetManager.loadMaterial("Blocks/Materials/water-array.j3m");
        mat.setTexture("DiffuseArray", getBlockTextureArray());
        this.waterMaterial = mat;
        return mat;
    }

    /**
     * The block diffuse texture array, one layer per tile (single-tile types occupy one layer, multi-tile
     * top/side/bottom types occupy three consecutive layers). Built lazily from every registered type's
     * already-expanded diffuse image and cached ; invalidated on (re)registration.
     */
    public synchronized TextureArray getBlockTextureArray() {
        if (blockTextureArray == null) {
            buildBlockTextureArray();
        }
        return blockTextureArray;
    }

    private void buildBlockTextureArray() {
        List<Image> layerImages = new ArrayList<>();
        typeLayers.clear();
        int tileSize = -1;
        for (Map.Entry<String, Material> entry : registry.entrySet()) {
            String name = entry.getKey();
            MatParamTexture dm = entry.getValue().getTextureParam("DiffuseMap");
            if (dm == null || dm.getTextureValue() == null || dm.getTextureValue().getImage() == null) {
                continue;
            }
            Image img = dm.getTextureValue().getImage();
            int w = img.getWidth();
            int h = img.getHeight();
            if (tileSize < 0) {
                tileSize = w;
            }
            if (w != tileSize) {
                log.warn("Block tile {} width {} != expected {}, excluded from the texture array", name, w, tileSize);
                continue;
            }
            if (h == w) {
                // single tile -> one layer
                typeLayers.put(name, new int[]{layerImages.size()});
                layerImages.add(sliceToRgba8(img, 0, w));
            } else if (h == 3 * w) {
                // multi tile (stacked thirds) -> three consecutive layers, in emitted-v order
                int base = layerImages.size();
                typeLayers.put(name, new int[]{base, base + 1, base + 2});
                layerImages.add(sliceToRgba8(img, 0, w));
                layerImages.add(sliceToRgba8(img, w, w));
                layerImages.add(sliceToRgba8(img, 2 * w, w));
            } else {
                log.warn("Block tile {} has unexpected size {}x{}, excluded from the texture array", name, w, h);
            }
        }
        if (layerImages.isEmpty()) {
            throw new IllegalStateException("No block tiles available to build the texture array");
        }

        TextureArray array = new TextureArray(layerImages);
        array.setMagFilter(Texture.MagFilter.Nearest);
        array.setMinFilter(Texture.MinFilter.Trilinear);
        // Repeat lets flowing water scroll and tile natively within its (mirror-bordered) layer. Opaque
        // blocks keep their UVs inside [0,1] so the wrap mode is a no-op for them.
        array.setWrap(Texture.WrapMode.Repeat);
        array.getImage().setColorSpace(ColorSpace.sRGB);
        blockTextureArray = array;
        log.info("Built block texture array : {} layers of {}x{} for {} block types",
                layerImages.size(), tileSize, tileSize, typeLayers.size());
    }

    /**
     * Copies a square [startRow, startRow+size) row band of {@code src} into a fresh RGBA8 image. The
     * RGBA8 rebuild (via {@link ImageRaster}, which normalises any source format) guarantees a
     * GL_UNSIGNED_BYTE upload — the format the ANGLE/GLES3 backend accepts (mirrors the atlas ABGR8->RGBA8
     * repack).
     */
    private static Image sliceToRgba8(Image src, int startRow, int size) {
        ByteBuffer data = BufferUtils.createByteBuffer(size * size * 4);
        Image dst = new Image(Image.Format.RGBA8, size, size, data, ColorSpace.sRGB);
        ImageRaster srcRaster = ImageRaster.create(src);
        ImageRaster dstRaster = ImageRaster.create(dst);
        ColorRGBA c = new ColorRGBA();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                srcRaster.getPixel(x, startRow + y, c);
                dstRaster.setPixel(x, y, c);
            }
        }
        dilateTransparentEdges(data, size, size);
        return dst;
    }

    /**
     * Bleeds each opaque texel's RGB into surrounding fully-transparent texels (alpha left untouched),
     * so mipmap box-filtering of alpha-tested tiles (grass tufts, leaves) doesn't drag edge colours
     * toward the black RGB that transparent texels normally carry — the dark-halo fix that the atlas
     * packer applied (TextureAtlas.dilateTransparentEdges), here per array layer, in RGBA8 (bytes R,G,B,A).
     * Multi-source BFS from every opaque texel ; a no-op for fully-opaque tiles.
     */
    private static void dilateTransparentEdges(ByteBuffer data, int w, int h) {
        int n = w * h;
        boolean[] known = new boolean[n];
        int[] queue = new int[n];
        int head = 0;
        int tail = 0;
        for (int p = 0; p < n; p++) {
            if (data.get(p * 4 + 3) != 0) {
                known[p] = true;
                queue[tail++] = p;
            }
        }
        if (tail == 0) {
            return;
        }
        while (head < tail) {
            int p = queue[head++];
            int px = p % w;
            int py = p / w;
            int src = p * 4;
            byte r = data.get(src);
            byte g = data.get(src + 1);
            byte b = data.get(src + 2);
            if (px > 0) {
                tail = bleedInto(data, known, queue, tail, p - 1, r, g, b);
            }
            if (px < w - 1) {
                tail = bleedInto(data, known, queue, tail, p + 1, r, g, b);
            }
            if (py > 0) {
                tail = bleedInto(data, known, queue, tail, p - w, r, g, b);
            }
            if (py < h - 1) {
                tail = bleedInto(data, known, queue, tail, p + w, r, g, b);
            }
        }
    }

    private static int bleedInto(ByteBuffer data, boolean[] known, int[] queue, int tail, int np, byte r, byte g, byte b) {
        if (known[np]) {
            return tail;
        }
        known[np] = true;
        int dst = np * 4;
        data.put(dst, r);
        data.put(dst + 1, g);
        data.put(dst + 2, b);
        queue[tail] = np;
        return tail + 1;
    }

    /**
     * Texture-array counterpart of the per-tile UV mapping. Leaves the shape's local [0,1] UVs in
     * place (single tile) or rescales the vertical third to [0,1] (multi tile), and appends one texture
     * layer index per vertex to {@code layerBuf}. A type absent from the array (fire/lava) is left
     * untouched with no layer emitted.
     */
    public void assignLayers(@NonNull String name, FloatBuffer uvBuf, int position, int len, DirectFloatBuffer layerBuf) {
        if (blockTextureArray == null) {
            getBlockTextureArray();
        }
        int[] layers = typeLayers.get(name);
        if (layers == null || len <= 0) {
            return;
        }
        int end = position + len;
        if (layers.length == 1) {
            float base = layers[0];
            for (int i = position; i < end; i += 2) {
                layerBuf.add(base);
            }
        } else {
            for (int i = position; i < end; i += 2) {
                float v = uvBuf.get(i + 1);
                int third = (int) Math.floor(v * 3f);
                if (third < 0) {
                    third = 0;
                } else if (third > 2) {
                    third = 2;
                }
                uvBuf.put(i + 1, v * 3f - third);
                layerBuf.add(layers[third]);
            }
        }
    }

    public Material register(@NonNull String name, @NonNull Material material) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Invalid type name " + name + " specified.");
        }

        registry.put(name, material);
        // Invalidate the (lazily built) texture array : it is rebuilt from the registry on next access.
        blockTextureArray = null;
        if (log.isTraceEnabled()) {
            log.trace("Registered type {} -> {}", name, material);
        }
        return material;
    }

    public Material get(String name) {
        Material material = registry.get(name);
        if (material == null) {
            log.warn("No type found for name {}", name);
        }
        return material;
    }

    public boolean remove(@NonNull String name) {
        if (registry.containsKey(name)) {
            Material material = registry.remove(name);
            if (log.isTraceEnabled()) {
                log.trace("Removed type {} -> {}", name, material);
            }
            return true;
        }
        return false;
    }

    public void clear() {
        registry.clear();
    }

    public Collection<String> getAll() {
        return Collections.unmodifiableCollection(registry.keySet());
    }

    /**
     * Set the textures in the TexturesWrapper on the material.
     */
    private Material setTextures(TexturesWrapper textures, Material material) {
        material.setTexture("DiffuseMap", textures.getDiffuseMap());
        material.setTexture("NormalMap", textures.getNormalMap().orElse(null));
        material.setTexture("ParallaxMap", textures.getParallaxMap().orElse(null));

        return material;
    }

    private Material load(String materialPath) {
        if (log.isTraceEnabled()) {
            log.trace("Loading material {}", materialPath);
        }
        return assetManager.loadMaterial(materialPath);
    }

    private Material expandMaterialTexture(Material material) {
        MatParamTexture diffuseMapParamTexture = material.getTextureParam("DiffuseMap");
        MatParamTexture normalMapParamTexture = material.getTextureParam("NormalMap");
        MatParamTexture parallaxMapParamTexture = material.getTextureParam("ParallaxMap");

        if (diffuseMapParamTexture != null) {
            expandTexture(diffuseMapParamTexture.getTextureValue());
        }
        if (normalMapParamTexture != null) {
            expandTexture(normalMapParamTexture.getTextureValue());
        }
        if (parallaxMapParamTexture != null) {
            expandTexture(parallaxMapParamTexture.getTextureValue());
        }
        return material;
    }

    private Texture expandTexture(Texture texture) {
        if (texture != null) {
            texture.setImage(expandImage(texture.getImage()));
        }
        return texture;
    }

    private Texture expandTexture(TextureKey key) {
        Texture texture = assetManager.loadTexture(key);
        if (texture != null) {
            texture.setImage(expandImage(texture.getImage()));
        }
        return texture;
    }

    private Image expandImage(Image source) {
        if (source.getFormat() != Image.Format.ABGR8 && source.getFormat() != Image.Format.RGBA8) {
            log.warn("Image format must be ABGR8, not " + source.getFormat());
            return source;
        }

        ByteBuffer sourceData = source.getData(0);
        int height = source.getHeight();
        int width = source.getWidth();
        int dstHeight = height * 2;
        int dstWidth = width * 2;
        byte[] image = new byte[dstHeight * dstWidth * 4];

        if (height == 3 * width) {
            //multiple texture
            expand(sourceData, image, width, 0, height / 3);
            expand(sourceData, image, width, height / 3, height * 2 / 3);
            expand(sourceData, image, width, height * 2 / 3, height);

        } else if (width == height) {
            expand(sourceData, image, width, 0, height);

        } else {
            log.warn("Image must be squared." + source.toString());
            return source;
        }

        return new Image(source.getFormat(), dstWidth, dstHeight, BufferUtils.createByteBuffer(image), null, source.getColorSpace());
    }

    private void expand(ByteBuffer src, byte[] dst, int srcWidth, int startHeight, int endHeight) {
        int dstWidth = srcWidth * 2;
        int height = endHeight - startHeight;
        int dstHeight = height * 2;
        int s = startHeight * 2 + dstHeight - 1;

        for (int y = startHeight; y < endHeight; y++) {
            int dstY = startHeight + y + height / 2;
            for (int x = 0; x < srcWidth; x++) {
                int j = (x + y * srcWidth) * 4;

                // Center Tile
                int dstX = x + srcWidth / 2;
                int i = (dstX + dstY * dstWidth) * 4;
                dst[i] = src.get(j);
                dst[i + 1] = src.get(j + 1);
                dst[i + 2] = src.get(j + 2);
                dst[i + 3] = src.get(j + 3);

                // West/East Tile
                int tdstX = (dstX + srcWidth) % dstWidth;
                i = (tdstX + dstY * dstWidth) * 4;
                dst[i] = src.get(j);
                dst[i + 1] = src.get(j + 1);
                dst[i + 2] = src.get(j + 2);
                dst[i + 3] = src.get(j + 3);

                // North/South Tile, flipped upside down to get seamless tiling
                int tdstY = s - (dstY + height) % dstHeight;
                i = (dstX + tdstY * dstWidth) * 4;
                dst[i] = src.get(j);
                dst[i + 1] = src.get(j + 1);
                dst[i + 2] = src.get(j + 2);
                dst[i + 3] = src.get(j + 3);

                // Corner Tile
                i = (tdstX + tdstY * dstWidth) * 4;
                dst[i] = src.get(j);
                dst[i + 1] = src.get(j + 1);
                dst[i + 2] = src.get(j + 2);
                dst[i + 3] = src.get(j + 3);
            }
        }
    }

    @Getter
    @RequiredArgsConstructor
    private static class TexturesWrapper {

        private final Texture diffuseMap;
        private final Optional<Texture> normalMap;
        private final Optional<Texture> parallaxMap;

    }
}

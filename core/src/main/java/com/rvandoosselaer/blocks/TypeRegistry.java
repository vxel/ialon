package com.rvandoosselaer.blocks;

import com.jme3.asset.AssetManager;
import com.jme3.asset.AssetNotFoundException;
import com.jme3.asset.TextureKey;
import com.jme3.material.MatParamTexture;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import org.delaunois.ialon.TextureAtlasManager;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jme3tools.optimize.TextureAtlas;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * A thread safe register for block types. The register is used so only one instance of a type is used throughout the
 * Blocks framework.
 *
 * @author rvandoosselaer
 */
@Slf4j
public class TypeRegistry {

    public static final String DEFAULT_BLOCK_MATERIAL = "Blocks/Materials/default-block.j3m";
    public static final BlocksTheme FAITHFUL_THEME = new BlocksTheme("Faithful", "Blocks/Themes/faithful/");

    private enum TextureType {
        DIFFUSE, NORMAL, PARALLAX, OVERLAY;
    }

    private final ConcurrentMap<String, Material> registry = new ConcurrentHashMap<>();
    private final AssetManager assetManager;
    @Getter
    private BlocksTheme theme;
    @Getter
    private BlocksTheme defaultTheme = new BlocksTheme("Soartex Fanver", "Blocks/Themes/default/");

    @Getter
    @Setter
    TextureAtlasManager atlasRepository;

    /**
     * Will register default materials
     */
    public TypeRegistry(@NonNull AssetManager assetManager) {
        this(assetManager, null, true);
    }

    /**
     * Will register default materials
     */
    public TypeRegistry(@NonNull AssetManager assetManager, BlocksTheme theme) {
        this(assetManager, theme, true);
    }

    public TypeRegistry(@NonNull AssetManager assetManager, BlocksTheme theme, boolean registerDefaultMaterials) {
        this.assetManager = assetManager;
        this.theme = theme;
        this.atlasRepository = new TextureAtlasManager();

        if (registerDefaultMaterials) {
            registerDefaultMaterials();
        }
    }

    public Material register(@NonNull String name) {
        Material mat = getMaterial(name);
        addToTextureAtlas(name, mat, "DiffuseMap", TextureAtlasManager.DIFFUSE);
        addToTextureAtlas(name, mat, "OverlayMap", TextureAtlasManager.OVERLAY);
        return register(name, mat);
    }

    private void addToTextureAtlas(@NonNull String name, Material mat, String textureParam, String atlasMapName) {
        MatParamTexture matParamTexture = mat.getTextureParam(textureParam);
        if (matParamTexture != null) {
            Texture texture = matParamTexture.getTextureValue();
            if (texture != null) {
                if (TextureAtlasManager.DIFFUSE.equals(atlasMapName)) {
                    texture.setKey(new TextureKey(name));
                    atlasRepository.getAtlas().addTexture(texture, atlasMapName);
                } else {
                    texture.setKey(new TextureKey(name + "-overlay"));
                    atlasRepository.getAtlas().addTexture(texture, atlasMapName, new TextureKey(name).toString());
                }
            }
        }
    }

    public boolean applyMaterial(@NonNull Geometry geom, @NonNull String name) {
        Mesh inMesh = geom.getMesh();
        Mesh outMesh = geom.getMesh();
        geom.computeWorldMatrix();
        Material mat = get(name);

        Texture texture = mat.getTextureParam("DiffuseMap").getTextureValue();
        texture.setImage(atlasRepository.getDiffuseMap().getImage());
        // ok: NearestLinearMipMap, NearestNearestMipMap
        // ko : NearestNoMipMaps, BilinearNoMipMaps, BilinearNearestMipMap, Trilinear
        //mat.getTextureParam("DiffuseMap").getTextureValue().setMinFilter(Texture.MinFilter.NearestLinearMipMap);
        if (mat.getTextureParam("OverlayMap") != null) {
            mat.setTexture("OverlayMap", atlasRepository.getOverlayMap());
        }
        geom.setMaterial(mat);

        VertexBuffer inBuf = inMesh.getBuffer(VertexBuffer.Type.TexCoord);
        VertexBuffer outBuf = outMesh.getBuffer(VertexBuffer.Type.TexCoord);

        if (inBuf == null || outBuf == null) {
            throw new IllegalStateException("Geometry mesh has no texture coordinate buffer.");
        }
        Texture2D dummy = new Texture2D();
        dummy.setKey(new TextureKey(name));
        TextureAtlas.TextureAtlasTile tile = this.atlasRepository.getAtlas().getAtlasTile(dummy);
        if (tile != null) {
            FloatBuffer inPos = (FloatBuffer) inBuf.getData();
            FloatBuffer outPos = (FloatBuffer) outBuf.getData();
            tile.transformTextureCoords(inPos, 0, outPos);
            return true;
        }
        return false;
    }

    public Material register(@NonNull String name, @NonNull Material material) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Invalid type name " + name + " specified.");
        }

        registry.put(name, material);
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

    public boolean usingTheme() {
        return theme != null;
    }

    public void clear() {
        registry.clear();
    }

    public Collection<String> getAll() {
        return Collections.unmodifiableCollection(registry.keySet());
    }

    public void registerDefaultMaterials() {
        register(TypeIds.BIRCH_LOG);
        register(TypeIds.BIRCH_PLANKS);
        register(TypeIds.BRICKS);
        register(TypeIds.COBBLESTONE);
        register(TypeIds.MOSSY_COBBLESTONE);
        register(TypeIds.DIRT);
        register(TypeIds.GRAVEL);
        register(TypeIds.GRASS);
        register(TypeIds.GRASS_SNOW);
        register(TypeIds.PALM_TREE_LOG);
        register(TypeIds.PALM_TREE_PLANKS);
        register(TypeIds.ROCK);
        register(TypeIds.OAK_LOG);
        register(TypeIds.OAK_PLANKS);
        register(TypeIds.SAND);
        register(TypeIds.SNOW);
        register(TypeIds.SPRUCE_LOG);
        register(TypeIds.SPRUCE_PLANKS);
        register(TypeIds.STONE_BRICKS);
        register(TypeIds.MOSSY_STONE_BRICKS);
        register(TypeIds.WATER);
        register(TypeIds.WATER_STILL);
        register(TypeIds.BIRCH_LEAVES);
        register(TypeIds.PALM_TREE_LEAVES);
        register(TypeIds.OAK_LEAVES);
        register(TypeIds.SPRUCE_LEAVES);
        register(TypeIds.WHITE_CUBE_LIGHT);
        register(TypeIds.WINDOW);
    }

    public void setTheme(BlocksTheme theme) {
        if (log.isDebugEnabled()) {
            log.debug("Setting {}", theme);
        }
        this.theme = theme;
        reload();
    }

    public void setDefaultTheme(@NonNull BlocksTheme defaultTheme) {
        if (log.isDebugEnabled()) {
            log.debug("Setting {} as default theme", theme);
        }
        this.defaultTheme = defaultTheme;
        reload();
    }

    public static Texture combineTextures(Texture topTexture, Texture sideTexture, Texture bottomTexture) {
        boolean widthEqual = assertValuesAreEqual(topTexture.getImage().getWidth(), sideTexture.getImage().getWidth(), bottomTexture.getImage().getWidth());
        boolean heightEqual = assertValuesAreEqual(topTexture.getImage().getHeight(), sideTexture.getImage().getHeight(), bottomTexture.getImage().getHeight());

        if (!widthEqual || !heightEqual) {
            String message = String.format("Textures (%s, %s, %s) have different sizes! The widths and heights of the textures should be equal.",
                    topTexture.getKey(), sideTexture.getKey(), bottomTexture.getKey());
            throw new IllegalArgumentException(message);
        }

        boolean colorSpacesEqual = assertColorSpacesAreEqual(topTexture.getImage().getColorSpace(), sideTexture.getImage().getColorSpace(), bottomTexture.getImage().getColorSpace());
        if (!colorSpacesEqual) {
            String message = String.format("Textures (%s, %s, %s) have different colorspaces! Colorspaces of the textures should be equal.",
                    topTexture.getKey(), sideTexture.getKey(), bottomTexture.getKey());
            throw new IllegalArgumentException(message);
        }

        TextureAtlas textureAtlas = new TextureAtlas(topTexture.getImage().getWidth(), topTexture.getImage().getHeight() * 3);
        textureAtlas.addTexture(bottomTexture, "main");
        textureAtlas.addTexture(sideTexture, "main");
        textureAtlas.addTexture(topTexture, "main");

        Texture texture = textureAtlas.getAtlasTexture("main");
        texture.getImage().setColorSpace(topTexture.getImage().getColorSpace());
        return texture;
    }

    /**
     * Retrieves a material for the block type. When a material file isn't found, the default material will be used and
     * the textures found in the theme or default theme folder will be added to the material.
     *
     * @param name block type (grass, rock, ...)
     * @return the material of the block type
     */
    private Material getMaterial(String name) {
        if (usingTheme()) {
            Optional<Material> optionalThemeMaterial = loadMaterial(name, theme);
            if (optionalThemeMaterial.isPresent()) {
                return optionalThemeMaterial.get();
            }

            Optional<TexturesWrapper> optionalThemeTextures = getTextures(name, theme);
            if (optionalThemeTextures.isPresent()) {
                return setTextures(optionalThemeTextures.get(), load(DEFAULT_BLOCK_MATERIAL));
            }
        }

        Optional<Material> optionalDefaultThemeMaterial = loadMaterial(name, defaultTheme);
        if (optionalDefaultThemeMaterial.isPresent()) {
            return optionalDefaultThemeMaterial.get();
        }

        Optional<TexturesWrapper> optionalDefaultThemeTextures = getTextures(name, defaultTheme);
        if (!optionalDefaultThemeTextures.isPresent()) {
            throw new AssetNotFoundException("Texture " + getTexturePath(name, TextureType.DIFFUSE, defaultTheme) + " not found!");
        }

        return setTextures(optionalDefaultThemeTextures.get(), load(DEFAULT_BLOCK_MATERIAL));
    }

    /**
     * Load the material in the given theme.
     *
     * @param name  block type
     * @param theme
     * @return an optional of the material
     */
    private Optional<Material> loadMaterial(String name, BlocksTheme theme) {
        String materialPath = getMaterialPath(name, theme);
        try {
            if (log.isTraceEnabled()) {
                log.trace("Loading material {}", materialPath);
            }
            Material material = assetManager.loadMaterial(materialPath);
            return of(expandMaterialTexture(material));
        } catch (AssetNotFoundException e) {
            if (log.isTraceEnabled()) {
                log.trace("Material {} not found in theme {}", materialPath, theme);
            }
        }

        return empty();
    }

    /**
     * Set the textures in the TexturesWrapper on the material.
     *
     * @param textures
     * @param material
     * @return the material with the textures applied
     */
    private Material setTextures(TexturesWrapper textures, Material material) {
        material.setTexture("DiffuseMap", textures.getDiffuseMap());
        material.setTexture("NormalMap", textures.getNormalMap().orElse(null));
        material.setTexture("ParallaxMap", textures.getParallaxMap().orElse(null));
        material.setTexture("OverlayMap", textures.getOverlayMap().orElse(null));

        return material;
    }

    /**
     * Reload all the materials in the registry.
     */
    private void reload() {
        registry.keySet().forEach(this::register);
    }

    private Material load(String materialPath) {
        if (log.isTraceEnabled()) {
            log.trace("Loading material {}", materialPath);
        }
        return assetManager.loadMaterial(materialPath);
    }

    /**
     * Retrieves the textures for the block type in the given theme.
     *
     * @param type
     * @param theme
     * @return an optional of the textures
     */
    private Optional<TexturesWrapper> getTextures(String type, BlocksTheme theme) {
        Optional<Texture> diffuseMap = getTexture(type, TextureType.DIFFUSE, theme);
        // map the value if present, or return an empty optional
        return diffuseMap.map(texture -> new TexturesWrapper(texture,
                getTexture(type, TextureType.NORMAL, theme),
                getTexture(type, TextureType.PARALLAX, theme),
                getTexture(type, TextureType.OVERLAY, theme)));

    }

    /**
     * @param type        block type (grass, rock, ...)
     * @param textureType kind of texture (diffuse, normal, parallax)
     * @param theme
     * @return an optional of the texture
     */
    private Optional<Texture> getTexture(String type, TextureType textureType, BlocksTheme theme) {
        String texture = getTexturePath(type, textureType, theme);
        try {
            if (log.isTraceEnabled()) {
                log.trace("Loading {}", texture);
            }
            return of(expandTexture(new TextureKey(texture)));
        } catch (AssetNotFoundException e) {
            if (log.isTraceEnabled()) {
                log.trace("Texture {} not found in theme {}", texture, theme);
            }
        }

        return getCombinedTexture(type, textureType, theme);
    }

    private Optional<Texture> getCombinedTexture(String type, TextureType textureType, BlocksTheme theme) {
        String topTexturePath = getTopTexturePath(type, textureType, theme);
        String sideTexturePath = getSideTexturePath(type, textureType, theme);
        String bottomTexturePath = getBottomTexturePath(type, textureType, theme);
        try {
            if (log.isTraceEnabled()) {
                log.trace("Loading {}, {}, {}", topTexturePath, sideTexturePath, bottomTexturePath);
            }
            Texture topTexture = expandTexture(new TextureKey(topTexturePath));
            Texture sideTexture = expandTexture(new TextureKey(sideTexturePath));
            Texture bottomTexture = expandTexture(new TextureKey(bottomTexturePath));

            return of(combineTextures(topTexture, sideTexture, bottomTexture));
        } catch (AssetNotFoundException e) {
            if (log.isTraceEnabled()) {
                log.trace("Texture {} not found in theme {}", e.getMessage(), theme);
            }
        } catch (IllegalArgumentException e) {
            log.warn(e.getMessage());
        }

        return empty();
    }

    private static boolean assertValuesAreEqual(int... values) {
        if (values.length == 0) {
            return true;
        }

        int checkValue = values[0];
        for (int i : values) {
            if (i != checkValue) {
                return false;
            }
        }

        return true;
    }

    private static boolean assertColorSpacesAreEqual(ColorSpace... colorSpaces) {
        if (colorSpaces.length == 0) {
            return true;
        }

        ColorSpace colorSpace = colorSpaces[0];
        for (ColorSpace c : colorSpaces) {
            if (colorSpace != c) {
                return false;
            }
        }

        return true;
    }

    private String getTopTexturePath(String type, TextureType textureType, BlocksTheme theme) {
        return getTexturePath(type + "_top", textureType, theme);
    }

    private String getSideTexturePath(String type, TextureType textureType, BlocksTheme theme) {
        return getTexturePath(type + "_side", textureType, theme);
    }

    private String getBottomTexturePath(String type, TextureType textureType, BlocksTheme theme) {
        return getTexturePath(type + "_bottom", textureType, theme);
    }

    /**
     * @param type        block type (grass, rock, ...)
     * @param textureType kind of texture (diffuse, normal, parallax)
     * @param theme       current theme
     * @return the full path to the texture, used by the assetmanager to load the texture
     */
    private static String getTexturePath(String type, TextureType textureType, BlocksTheme theme) {
        String path = Paths.get(theme.getPath(), getTextureFilename(type, textureType)).toString();
        // this should be interpreted as a java path. It will look for this texture in the classpath. When this code is
        // executed on windows, it will use backward slashes when constructing the path, and the texture will not be
        // properly resolved.
        path = path.replace('\\', '/');
        return path;
    }

    private static String getMaterialPath(String type, BlocksTheme theme) {
        String path = Paths.get(theme.getPath(), getMaterialFilename(type)).toString();
        // this should be interpreted as a java path. It will look for this texture in the classpath. When this code is
        // executed on windows, it will use backward slashes when constructing the path, and the texture will not be
        // properly resolved.
        path = path.replace('\\', '/');
        return path;
    }

    private static String getMaterialFilename(String type) {
        String fileExtension = ".j3m";
        return type + fileExtension;
    }

    private Material expandMaterialTexture(Material material) {
        MatParamTexture diffuseMapParamTexture = material.getTextureParam("DiffuseMap");
        MatParamTexture normalMapParamTexture = material.getTextureParam("NormalMap");
        MatParamTexture parallaxMapParamTexture = material.getTextureParam("ParallaxMap");
        MatParamTexture overlayMapParamTexture = material.getTextureParam("OverlayMap");

        if (diffuseMapParamTexture != null) {
            expandTexture(diffuseMapParamTexture.getTextureValue());
        }
        if (normalMapParamTexture != null) {
            expandTexture(normalMapParamTexture.getTextureValue());
        }
        if (parallaxMapParamTexture != null) {
            expandTexture(parallaxMapParamTexture.getTextureValue());
        }
        if (overlayMapParamTexture != null) {
            expandTexture(overlayMapParamTexture.getTextureValue());
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
        if (source.getFormat() != Image.Format.ABGR8) {
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

        return new Image(Image.Format.ABGR8, dstWidth, dstHeight, BufferUtils.createByteBuffer(image), null, source.getColorSpace());
    }

    private void expand(ByteBuffer src, byte[] dst, int srcWidth, int startHeight, int endHeight) {
        int dstWidth = srcWidth * 2;
        int height = endHeight - startHeight;
        int dstHeight = height * 2;

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

                // North/South Tile
                int tdstY = startHeight * 2 + (dstY + height) % dstHeight;
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

    /**
     * @param type        block type (grass, rock, ...)
     * @param textureType kind of texture (diffuse, normal, parallax)
     * @return the filename of the texture
     */
    private static String getTextureFilename(String type, TextureType textureType) {
        String fileExtension = ".png";
        switch (textureType) {
            case NORMAL:
                return type + "-normal" + fileExtension;
            case PARALLAX:
                return type + "-parallax" + fileExtension;
            case OVERLAY:
                return type + "-overlay" + fileExtension;
            default:
                return type + fileExtension;
        }
    }

    @Getter
    @RequiredArgsConstructor
    private static class TexturesWrapper {

        private final Texture diffuseMap;
        private final Optional<Texture> normalMap;
        private final Optional<Texture> parallaxMap;
        private final Optional<Texture> overlayMap;

    }

}

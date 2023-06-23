/*
 *  Copyright (c) 2009-2021 jMonkeyEngine
 *  All rights reserved.
 * 
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are
 *  met:
 * 
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 
 *  * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 * 
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *  "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *  TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 *  PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 *  CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.delaunois.ialon.jme;

import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.material.MatParamTexture;
import com.jme3.material.Material;
import com.jme3.math.Vector2f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.texture.Image;
import com.jme3.texture.Image.Format;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.util.MipMapGenerator;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import jme3tools.optimize.GeometryBatchFactory;
import lombok.Getter;

/**
 * <b><code>TextureAtlas</code></b> allows combining multiple textures to one texture atlas.
 * 
 * <p>After the TextureAtlas has been created with a certain size, textures can be added for
 * freely chosen "map names". The textures are automatically placed on the atlas map and the
 * image data is stored in a byte array for each map name. Later each map can be retrieved as
 * a Texture to be used further in materials.</p>
 * 
 * <p>The first map name used is the "master map" that defines new locations on the atlas. Secondary
 * textures (other map names) have to reference a texture of the master map to position the texture
 * on the secondary map. This is necessary as the maps share texture coordinates and thus need to be
 * placed at the same location on both maps.</p>
 * 
 * <p>The helper methods that work with <code>Geometry</code> objects handle the <em>DiffuseMap</em> or <em>ColorMap</em> as the master map and
 * additionally handle <em>NormalMap</em> and <em>SpecularMap</em> as secondary maps.</p>
 * 
 * <p>The textures are referenced by their <b>asset key name</b> and for each texture the location
 * inside the atlas is stored. A texture with an existing key name is never added more than once
 * to the atlas. You can access the information for each texture or geometry texture via helper methods.</p>
 * 
 * <p>The TextureAtlas also allows you to change the texture coordinates of a mesh or geometry
 * to point at the new locations of its texture inside the atlas (if the texture exists inside the atlas).</p>
 * 
 * <p>Note that models that use texture coordinates outside the 0-1 range (repeating/wrapping textures)
 * will not work correctly as their new coordinates leak into other parts of the atlas and thus display
 * other textures instead of repeating the texture.</p>
 * 
 * <p>Also note that textures are not scaled and the atlas needs to be large enough to hold all textures.
 * All methods that allow adding textures return false if the texture could not be added due to the
 * atlas being full. Furthermore secondary textures (normal, spcular maps etc.) have to be the same size
 * as the main (e.g. DiffuseMap) texture.</p>
 * 
 * <p><b>Usage examples</b></p>
 * Create one geometry out of several geometries that are loaded from a j3o file:
 * <pre>
 * Node scene = assetManager.loadModel("Scenes/MyScene.j3o");
 * Geometry geom = TextureAtlas.makeAtlasBatch(scene);
 * rootNode.attachChild(geom);
 * </pre>
 * Create a texture atlas and change the texture coordinates of one geometry:
 * <pre>
 * Node scene = assetManager.loadModel("Scenes/MyScene.j3o");
 * //either auto-create from node:
 * TextureAtlas atlas = TextureAtlas.createAtlas(scene);
 * //or create manually by adding textures or geometries with textures
 * TextureAtlas atlas = new TextureAtlas(1024,1024);
 * atlas.addTexture(myTexture, DIFFUSE_MAP);
 * atlas.addGeometry(myGeometry);
 * //create material and set texture
 * Material mat = new Material(mgr, "Common/MatDefs/Light/Lighting.j3md");
 * mat.setTexture(DIFFUSE_MAP, atlas.getAtlasTexture(DIFFUSE_MAP));
 * //change one geometry to use atlas, apply texture coordinates and replace material.
 * Geometry geom = scene.getChild("MyGeometry");
 * atlas.applyCoords(geom);
 * geom.setMaterial(mat);
 * </pre>
 * 
 * @author normenhansen, Lukasz Bruun - lukasz.dk
 */
public class TextureAtlas {

    private static final Logger logger = Logger.getLogger(TextureAtlas.class.getName());

    private static final String DIFFUSE_MAP = "DiffuseMap";
    private static final String NORMAL_MAP = "NormalMap";
    private static final String SPECULAR_MAP = "SpecularMap";
    private static final String COLOR_MAP = "ColorMap";

    private Map<String, byte[]> images;

    @Getter
    private final int atlasWidth, atlasHeight;

    private final Format format = Format.ABGR8;
    private final Node root;
    private final Map<String, TextureAtlasTile> locationMap;
    private final Map<String, String> mapNameMap;
    private final Map<Integer, byte[]> mipmap;
    private String rootMapName;

    public TextureAtlas(int width, int height) {
        this.atlasWidth = width;
        this.atlasHeight = height;
        root = new Node(0, 0, width, height);
        locationMap = new TreeMap<>();
        mapNameMap = new HashMap<>();
        mipmap = new HashMap<>();
    }

    /**
     * Add a geometries DiffuseMap (or ColorMap), NormalMap and SpecularMap to the atlas.
     *
     * @param geometry the Geometry to be added (not null)
     * @return false if the atlas is full.
     */
    public boolean addGeometry(Geometry geometry) {
        Texture diffuse = getMaterialTexture(geometry, DIFFUSE_MAP);
        Texture normal = getMaterialTexture(geometry, NORMAL_MAP);
        Texture specular = getMaterialTexture(geometry, SPECULAR_MAP);
        if (diffuse == null) {
            diffuse = getMaterialTexture(geometry, COLOR_MAP);
        }
        if (diffuse != null && diffuse.getKey() != null) {
            String keyName = diffuse.getKey().toString();
            if (!addTexture(diffuse, DIFFUSE_MAP)) {
                return false;
            } else {
                if (normal != null && normal.getKey() != null) {
                    addTexture(normal, NORMAL_MAP, keyName);
                }
                if (specular != null && specular.getKey() != null) {
                    addTexture(specular, SPECULAR_MAP, keyName);
                }
            }
            return true;
        }
        return true;
    }

    /**
     * Add a texture for a specific map name
     * @param texture A texture to add to the atlas.
     * @param mapName A freely chosen map name that can be later retrieved as a Texture. The first map name supplied will be the master map.
     * @return false if the atlas is full.
     */
    public boolean addTexture(Texture texture, String mapName) {
        if (texture == null) {
            throw new IllegalStateException("Texture cannot be null!");
        }
        String name = textureName(texture);
        if (texture.getImage() != null && name != null) {
            return addImage(texture.getImage(), name, mapName, null);
        } else {
            throw new IllegalStateException("Texture has no asset key name!");
        }
    }

    /**
     * Add a texture for a specific map name at the location of another existing texture on the master map.
     * @param texture A texture to add to the atlas.
     * @param mapName A freely chosen map name that can be later retrieved as a Texture.
     * @param masterTexture The master texture for determining the location, it has to exist in tha master map.
     */
    public void addTexture(Texture texture, String mapName, Texture masterTexture) {
        String sourceTextureName = textureName(masterTexture);
        if (sourceTextureName == null) {
            throw new IllegalStateException("Supplied master map texture has no asset key name!");
        } else {
            addTexture(texture, mapName, sourceTextureName);
        }
    }

    /**
     * Add a texture for a specific map name at the location of another existing texture (on the master map).
     * @param texture A texture to add to the atlas.
     * @param mapName A freely chosen map name that can be later retrieved as a Texture.
     * @param sourceTextureName Name of the master map used for the location.
     */
    public void addTexture(Texture texture, String mapName, String sourceTextureName) {
        if (texture == null) {
            throw new IllegalStateException("Texture cannot be null!");
        }
        String name = textureName(texture);
        if (texture.getImage() != null && name != null) {
            addImage(texture.getImage(), name, mapName, sourceTextureName);
        } else {
            throw new IllegalStateException("Texture has no asset key name!");
        }
    }

    private String textureName(Texture texture) {
        if (texture == null) {
            return null;
        }
        AssetKey<?> key = texture.getKey();
        if (key != null) {
            return key.toString();
        } else {
            return null;
        }
    }

    private boolean addImage(Image image, String name, String mapName, String sourceTextureName) {
        if (rootMapName == null) {
            rootMapName = mapName;
        }
        if (sourceTextureName == null && !rootMapName.equals(mapName)) {
            throw new IllegalStateException("Atlas already has a master map called " + rootMapName + "."
                    + " Textures for new maps have to use a texture from the master map for their location.");
        }
        TextureAtlasTile location = locationMap.get(name);
        if (location != null) {
            //have location for texture
            if (!mapName.equals(mapNameMap.get(name))) {
                logger.log(Level.WARNING, "Same texture " + name + " is used in different maps! (" + mapName + " and " + mapNameMap.get(name) + "). Location will be based on location in " + mapNameMap.get(name) + "!");
                drawImage(image, location.getX(), location.getY(), mapName);
                //addMipMap(image, location.getX(), location.getY());
            }
            return true;
        } else if (sourceTextureName == null) {
            //need to make new tile
            Node node = root.insert(image);
            if (node == null) {
                return false;
            }
            location = node.location;
        } else {
            //got old tile to align to
            location = locationMap.get(sourceTextureName);
            if (location == null) {
                throw new IllegalStateException("Cannot find master map texture for " + name + ".");
            } else if (location.width != image.getWidth() || location.height != image.getHeight()) {
                throw new IllegalStateException(mapName + " " + name + " does not fit " + rootMapName + " tile size. Make sure all textures (diffuse, normal, specular) for one model are the same size.");
            }
        }
        mapNameMap.put(name, mapName);
        locationMap.put(name, location);
        drawImage(image, location.getX(), location.getY(), mapName);
        //addMipMap(image, location.getX(), location.getY());
        return true;
    }

    private void addMipMap(Image image, int x, int y) {
        int width = image.getWidth();
        int height = image.getHeight();

        Image current = image;
        ArrayList<Image> output = new ArrayList<>();

        while (height > 1 && width > 1) {
            height /= 2;
            width  /= 2;
            current = MipMapGenerator.scaleImage(current, width, height);
            output.add(current);
        }

        int scale = 2;
        for (int i = 0; i < output.size(); i++) {
            byte[] img = mipmap.get(i);
            if (img == null) {
                img = new byte[(atlasWidth / scale) * (atlasHeight / scale) * 4];
                mipmap.put(i, img);
            }
            drawMipMap(output.get(i), atlasWidth / scale, x / scale, y / scale, img);
            scale *= 2;
        }
    }

    private void drawMipMap(Image source, int aWidth, int x, int y, byte[] output) {
        ByteBuffer sourceData = source.getData(0);
        int height = source.getHeight();
        int width = source.getWidth();

        PixelConverter converter;
        switch (source.getFormat()) {
            case ABGR8:
                converter = this::abgr8Converter;
                break;
            case BGR8:
                converter = this::bgr8Converter;
                break;
            case RGB8:
                converter = this::rgb8Converter;
                break;
            case RGBA8:
                converter = this::rgba8Converter;
                break;
            case Luminance8:
                converter = this::luminance8Converter;
                break;
            case Luminance8Alpha8:
                converter = this::luminance8Aplha8Converter;
                break;
            default:
                Image newImage = convertImageToAwt(source);
                if (newImage == null) {
                    throw new UnsupportedOperationException("Cannot draw or convert textures with format " + source.getFormat());
                }
                sourceData = newImage.getData(0);
                converter = this::awtConverter;
        }

        for (int yPos = 0; yPos < height; yPos++) {
            for (int xPos = 0; xPos < width; xPos++) {
                int i = ((xPos + x) + (yPos + y) * aWidth) * 4;
                converter.convert(output, sourceData, i, xPos, yPos, width);
            }
        }
    }

    private void drawImage(Image source, int x, int y, String mapName) {
        if (images == null) {
            images = new HashMap<>();
        }
        //FIXME this is not accounting for color space.
        //Texture Atlas should linearize the data if the source image isSRGB
        byte[] image = images.computeIfAbsent(mapName, k -> new byte[atlasWidth * atlasHeight * 4]);

        //TODO: all buffers?
        ByteBuffer sourceData = source.getData(0);
        int height = source.getHeight();
        int width = source.getWidth();

        PixelConverter converter;
        switch (source.getFormat()) {
            case ABGR8:
                converter = this::abgr8Converter;
                break;
            case BGR8:
                converter = this::bgr8Converter;
                break;
            case RGB8:
                converter = this::rgb8Converter;
                break;
            case RGBA8:
                converter = this::rgba8Converter;
                break;
            case Luminance8:
                converter = this::luminance8Converter;
                break;
            case Luminance8Alpha8:
                converter = this::luminance8Aplha8Converter;
                break;
            default:
                Image newImage = convertImageToAwt(source);
                if (newImage == null) {
                    throw new UnsupportedOperationException("Cannot draw or convert textures with format " + source.getFormat());
                }
                sourceData = newImage.getData(0);
                converter = this::awtConverter;
        }

        for (int yPos = 0; yPos < height; yPos++) {
            for (int xPos = 0; xPos < width; xPos++) {
                int i = ((xPos + x) + (yPos + y) * atlasWidth) * 4;
                converter.convert(image, sourceData, i, xPos, yPos, width);
            }
        }
    }

    private void abgr8Converter(byte[] image, ByteBuffer sourceData, int i, int xPos, int yPos, int width) {
        int j = (xPos + yPos * width) * 4;
        image[i] = sourceData.get(j); //a
        image[i + 1] = sourceData.get(j + 1); //b
        image[i + 2] = sourceData.get(j + 2); //g
        image[i + 3] = sourceData.get(j + 3); //r
    }

    private void bgr8Converter(byte[] image, ByteBuffer sourceData, int i, int xPos, int yPos, int width) {
        int j = (xPos + yPos * width) * 3;
        image[i] = 1; //a
        image[i + 1] = sourceData.get(j); //b
        image[i + 2] = sourceData.get(j + 1); //g
        image[i + 3] = sourceData.get(j + 2); //r
    }

    private void rgb8Converter(byte[] image, ByteBuffer sourceData, int i, int xPos, int yPos, int width) {
        int j = (xPos + yPos * width) * 3;
        image[i] = 1; //a
        image[i + 1] = sourceData.get(j + 2); //b
        image[i + 2] = sourceData.get(j + 1); //g
        image[i + 3] = sourceData.get(j); //r
    }

    private void rgba8Converter(byte[] image, ByteBuffer sourceData, int i, int xPos, int yPos, int width) {
        int j = (xPos + yPos * width) * 4;
        image[i] = sourceData.get(j + 3); //a
        image[i + 1] = sourceData.get(j + 2); //b
        image[i + 2] = sourceData.get(j + 1); //g
        image[i + 3] = sourceData.get(j); //r
    }

    private void luminance8Converter(byte[] image, ByteBuffer sourceData, int i, int xPos, int yPos, int width) {
        int j = (xPos + yPos * width);
        image[i] = 1; //a
        image[i + 1] = sourceData.get(j); //b
        image[i + 2] = sourceData.get(j); //g
        image[i + 3] = sourceData.get(j); //r
    }

    private void luminance8Aplha8Converter(byte[] image, ByteBuffer sourceData, int i, int xPos, int yPos, int width) {
        int j = (xPos + yPos * width) * 2;
        image[i] = sourceData.get(j + 1); //a
        image[i + 1] = sourceData.get(j); //b
        image[i + 2] = sourceData.get(j); //g
        image[i + 3] = sourceData.get(j); //r
    }

    private void awtConverter(byte[] image, ByteBuffer sourceData, int i, int xPos, int yPos, int width) {
        int j = (xPos + yPos * width) * 4;
        image[i] = sourceData.get(j); //a
        image[i + 1] = sourceData.get(j + 1); //b
        image[i + 2] = sourceData.get(j + 2); //g
        image[i + 3] = sourceData.get(j + 3); //r
    }

    private Image convertImageToAwt(Image source) {
        //use awt dependent classes without actual dependency via reflection
        try {
            Class<?> clazz = Class.forName("jme3tools.converters.ImageToAwt");
            Image newImage = new Image(format, source.getWidth(), source.getHeight(), BufferUtils.createByteBuffer(source.getWidth() * source.getHeight() * 4), null, ColorSpace.Linear);
            clazz.getMethod("convert", Image.class, Image.class).invoke(clazz.getDeclaredConstructor().newInstance(), source, newImage);
            return newImage;
        } catch (InstantiationException
                | IllegalAccessException
                | IllegalArgumentException
                | InvocationTargetException
                | NoSuchMethodException
                | SecurityException
                | ClassNotFoundException ex) {
            logger.log(Level.WARNING, "Failed to convert image to awt", ex);
        }
        return null;
    }

    /**
     * Get the <code>TextureAtlasTile</code> for the given Texture
     * @param texture The texture to retrieve the <code>TextureAtlasTile</code> for.
     * @return the atlas tile
     */
    public TextureAtlasTile getAtlasTile(Texture texture) {
        String sourceTextureName = textureName(texture);
        if (sourceTextureName != null) {
            return getAtlasTile(sourceTextureName);
        }
        return null;
    }

    /**
     * Get the <code>TextureAtlasTile</code> for the given Texture
     * @param assetName The texture to retrieve the <code>TextureAtlasTile</code> for.
     * @return the TextureAtlasTile
     */
    private TextureAtlasTile getAtlasTile(String assetName) {
        return locationMap.get(assetName);
    }

    /**
     * Creates a new atlas texture for the given map name.
     *
     * @param mapName the desired name
     * @return the atlas texture
     */
    public Texture getAtlasTexture(String mapName) {
        if (images == null) {
            return null;
        }
        byte[] image = images.get(mapName);
        if (image != null) {
            Image img;

            if (DIFFUSE_MAP.equals(mapName) && mipmap.size() > 0) {
                // Generate atlas image with mipmaps
                completeMipMap(atlasWidth, atlasHeight);
                ByteBuffer bb = BufferUtils.createByteBuffer(image.length * 4);
                bb.put(image);
                int[] mipmapSizes = new int[mipmap.size() + 1];
                mipmapSizes[0] = image.length;
                for (int i = 0; i < mipmap.size(); i++) {
                    byte[] mm = mipmap.get(i);
                    mipmapSizes[i + 1] = mm.length;
                    bb.put(mm);
                }
                img = new Image(format, atlasWidth, atlasHeight, bb, mipmapSizes, ColorSpace.Linear);

            } else {
                //TODO check if color space shouldn't be sRGB
                img = new Image(format, atlasWidth, atlasHeight, BufferUtils.createByteBuffer(image), null, ColorSpace.Linear);
            }

            Texture2D tex = new Texture2D(img);
            tex.setMagFilter(Texture.MagFilter.Bilinear);
            tex.setMinFilter(Texture.MinFilter.BilinearNearestMipMap);
            tex.setWrap(Texture.WrapMode.EdgeClamp);
            return tex;
        }
        return null;
    }

    private void completeMipMap(int atlasWidth, int atlasHeight) {
        if (mipmap.size() > 0) {
            int i = mipmap.size() - 1;
            byte[] lastMipMap = mipmap.get(i);
            int scale = ((int)Math.pow(2, i + 1));
            int width = atlasWidth / scale;
            int height = atlasHeight / scale;

            Image current = new Image(format, width, height, BufferUtils.createByteBuffer(lastMipMap), null, ColorSpace.Linear);
            while (height > 1 && width > 1) {
                height /= 2;
                width /= 2;
                i++;
                byte[] img = new byte[width * height * 4];
                current = MipMapGenerator.scaleImage(current, width, height);
                current.getData(0).rewind().get(img);
                mipmap.put(i, img);
            }
        }
    }

    /**
     * Applies the texture coordinates to the given geometry
     * if its DiffuseMap or ColorMap exists in the atlas.
     * @param geom The geometry to change the texture coordinate buffer on.
     * @return true if texture has been found and coords have been changed, false otherwise.
     */
    public boolean applyCoords(Geometry geom) {
        return applyCoords(geom, 0, geom.getMesh());
    }

    public boolean applyCoords(Geometry geom, float padding) {
        return applyCoords(geom, 0, geom.getMesh(), padding);
    }


    /**
     * Applies the texture coordinates to the given output mesh
     * if the DiffuseMap or ColorMap of the input geometry exist in the atlas.
     * @param geom The geometry to change the texture coordinate buffer on.
     * @param offset Target buffer offset.
     * @param outMesh The mesh to set the coords in (can be same as input).
     * @return true if texture has been found and coords have been changed, false otherwise.
     */
    public boolean applyCoords(Geometry geom, int offset, Mesh outMesh) {
        return applyCoords(geom, offset, outMesh, 0);
    }

    /**
     * Applies the texture coordinates to the given output mesh
     * if the DiffuseMap or ColorMap of the input geometry exist in the atlas.
     * @param geom The geometry to change the texture coordinate buffer on.
     * @param offset Target buffer offset.
     * @param outMesh The mesh to set the coords in (can be same as input).
     * @param padding the percentage of padding added to the new texture coordinate. Used
     *                to prevent color bleeding artifact due to the use of the atlas.
     *                Value range between 0 (no padding) and 0.5f (entirely padded).
     * @return true if texture has been found and coords have been changed, false otherwise.
     */
    public boolean applyCoords(Geometry geom, int offset, Mesh outMesh, float padding) {
        Mesh inMesh = geom.getMesh();
        geom.computeWorldMatrix();

        VertexBuffer inBuf = inMesh.getBuffer(Type.TexCoord);
        VertexBuffer outBuf = outMesh.getBuffer(Type.TexCoord);

        if (inBuf == null || outBuf == null) {
            throw new IllegalStateException("Geometry mesh has no texture coordinate buffer.");
        }

        Texture tex = getMaterialTexture(geom, DIFFUSE_MAP);
        if (tex == null) {
            tex = getMaterialTexture(geom, COLOR_MAP);

        }
        if (tex != null) {
            TextureAtlasTile tile = getAtlasTile(tex);
            if (tile != null) {
                FloatBuffer inPos = (FloatBuffer) inBuf.getData();
                FloatBuffer outPos = (FloatBuffer) outBuf.getData();
                tile.transformTextureCoords(inPos, offset, outPos, padding);
                return true;
            } else {
                return false;
            }
        } else {
            throw new IllegalStateException("Geometry has no proper texture.");
        }
    }

    /**
     * Create a texture atlas for the given root node, containing DiffuseMap, NormalMap and SpecularMap.
     * @param root The rootNode to create the atlas for.
     * @param atlasSize The size of the atlas (width and height).
     * @return Null if the atlas cannot be created because not all textures fit.
     */
    public static TextureAtlas createAtlas(Spatial root, int atlasSize) {
        List<Geometry> geometries = new ArrayList<>();
        GeometryBatchFactory.gatherGeoms(root, geometries);
        TextureAtlas atlas = new TextureAtlas(atlasSize, atlasSize);
        for (Geometry geometry : geometries) {
            if (!atlas.addGeometry(geometry)) {
                logger.log(Level.WARNING, "Texture atlas size too small, cannot add all textures");
                return null;
            }
        }
        return atlas;
    }

    /**
     * Creates one geometry out of the given root spatial and merges all single
     * textures into one texture of the given size.
     * @param spat The root spatial of the scene to batch
     * @param mgr An assetmanager that can be used to create the material.
     * @param atlasSize A size for the atlas texture, it has to be large enough to hold all single textures.
     * @return A new geometry that uses the generated texture atlas and merges all meshes of the root spatial, null if the atlas cannot be created because not all textures fit.
     */
    public static Geometry makeAtlasBatch(Spatial spat, AssetManager mgr, int atlasSize) {
        List<Geometry> geometries = new ArrayList<>();
        GeometryBatchFactory.gatherGeoms(spat, geometries);
        TextureAtlas atlas = createAtlas(spat, atlasSize);
        if (atlas == null) {
            return null;
        }
        Geometry geom = new Geometry();
        Mesh mesh = new Mesh();
        GeometryBatchFactory.mergeGeometries(geometries, mesh);
        applyAtlasCoords(geometries, mesh, atlas);
        mesh.updateCounts();
        mesh.updateBound();
        geom.setMesh(mesh);

        Material mat = new Material(mgr, "Common/MatDefs/Light/Lighting.j3md");
        Texture diffuseMap = atlas.getAtlasTexture(DIFFUSE_MAP);
        Texture normalMap = atlas.getAtlasTexture(NORMAL_MAP);
        Texture specularMap = atlas.getAtlasTexture(SPECULAR_MAP);
        if (diffuseMap != null) {
            mat.setTexture(DIFFUSE_MAP, diffuseMap);
        }
        if (normalMap != null) {
            mat.setTexture(NORMAL_MAP, normalMap);
        }
        if (specularMap != null) {
            mat.setTexture(SPECULAR_MAP, specularMap);
        }
        mat.setFloat("Shininess", 16.0f);

        geom.setMaterial(mat);
        return geom;
    }

    private static void applyAtlasCoords(List<Geometry> geometries, Mesh outMesh, TextureAtlas atlas) {
        int globalVertIndex = 0;

        for (Geometry geom : geometries) {
            Mesh inMesh = geom.getMesh();
            geom.computeWorldMatrix();

            int geomVertCount = inMesh.getVertexCount();

            VertexBuffer inBuf = inMesh.getBuffer(Type.TexCoord);
            VertexBuffer outBuf = outMesh.getBuffer(Type.TexCoord);

            if (inBuf == null || outBuf == null) {
                continue;
            }

            atlas.applyCoords(geom, globalVertIndex, outMesh);

            globalVertIndex += geomVertCount;
        }
    }

    private static Texture getMaterialTexture(Geometry geometry, String mapName) {
        Material mat = geometry.getMaterial();
        if (mat == null || mat.getParam(mapName) == null || !(mat.getParam(mapName) instanceof MatParamTexture)) {
            return null;
        }
        MatParamTexture param = (MatParamTexture) mat.getParam(mapName);
        return param.getTextureValue();
    }

    private class Node {

        private final TextureAtlasTile location;
        private final Node[] child;
        private boolean occupied;

        public Node(int x, int y, int width, int height) {
            location = new TextureAtlasTile(x, y, width, height);
            child = new Node[2];
            child[0] = null;
            child[1] = null;
            occupied = false;
        }

        public boolean isLeaf() {
            return child[0] == null && child[1] == null;
        }

        // Algorithm from http://www.blackpawn.com/texts/lightmaps/
        public Node insert(Image image) {
            if (!isLeaf()) {
                Node newNode = child[0].insert(image);

                if (newNode != null) {
                    return newNode;
                }

                return child[1].insert(image);
            } else {
                if (occupied) {
                    return null; // occupied
                }

                if (image.getWidth() > location.getWidth() || image.getHeight() > location.getHeight()) {
                    return null; // does not fit
                }

                if (image.getWidth() == location.getWidth() && image.getHeight() == location.getHeight()) {
                    occupied = true; // perfect fit
                    return this;
                }

                int dw = location.getWidth() - image.getWidth();
                int dh = location.getHeight() - image.getHeight();

                if (dw > dh) {
                    child[0] = new Node(location.getX(), location.getY(), image.getWidth(), location.getHeight());
                    child[1] = new Node(location.getX() + image.getWidth(), location.getY(), location.getWidth() - image.getWidth(), location.getHeight());
                } else {
                    child[0] = new Node(location.getX(), location.getY(), location.getWidth(), image.getHeight());
                    child[1] = new Node(location.getX(), location.getY() + image.getHeight(), location.getWidth(), location.getHeight() - image.getHeight());
                }

                return child[0].insert(image);
            }
        }
    }

    @FunctionalInterface
    protected interface PixelConverter {
        void convert(byte[] image, ByteBuffer sourceData, int i, int xPos, int yPos, int width);
    }

    public class TextureAtlasTile {

        private final int x;
        private final int y;
        private final int width;
        private final int height;

        public TextureAtlasTile(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /**
         * Get the transformed texture coordinate for a given input location.
         * @param previousLocation The old texture coordinate.
         * @param padding the percentage of padding added to the new texture coordinate. Used
         *                to prevent color bleeding artifact due to the use of the atlas.
         *                Value range between 0 (no padding) and 0.5f (entirely padded).
         * @return The new texture coordinate inside the atlas.
         */
        public Vector2f getLocation(Vector2f previousLocation, float padding) {
            float x = getX() / (float) atlasWidth;
            float y = getY() / (float) atlasHeight;
            float w = getWidth() / (float) atlasWidth;
            float h = getHeight() / (float) atlasHeight;
            float paddingScale = 1 - 2 * padding;
            Vector2f location = new Vector2f(x, y);
            float prevX = previousLocation.x * paddingScale + padding;
            float prevY = previousLocation.y * paddingScale + padding;
            location.addLocal(prevX * w, prevY * h);
            return location;
        }

        /**
         * Transforms a whole texture coordinates buffer.
         * @param inBuf The input texture buffer.
         * @param offset The offset in the output buffer
         * @param outBuf The output buffer.
         */
        public void transformTextureCoords(FloatBuffer inBuf, int offset, FloatBuffer outBuf) {
            transformTextureCoords(inBuf, offset, outBuf, 0);
        }

        public void transformTextureCoords(FloatBuffer inBuf, FloatBuffer outBuf, int position, int len) {
            Vector2f tex = new Vector2f();

            for (int i = position / 2; i < (position + len) / 2; i++) {
                tex.x = inBuf.get(i * 2);
                tex.y = inBuf.get(i * 2 + 1);
                Vector2f location = getLocation(tex, 0);
                outBuf.put(i * 2, location.x);
                outBuf.put(i * 2 + 1, location.y);
            }
        }

        /**
         * Transforms a whole texture coordinates buffer.
         * @param inBuf The input texture buffer.
         * @param offset The offset in the output buffer
         * @param outBuf The output buffer.
         * @param padding the percentage of padding added to the new texture coordinate. Used
         *                to prevent color bleeding artifact due to the use of the atlas.
         *                Value range between 0 (no padding) and 0.5f (entirely padded).
         */
        public void transformTextureCoords(FloatBuffer inBuf, int offset, FloatBuffer outBuf, float padding) {
            Vector2f tex = new Vector2f();

            // offset is given in element units
            // convert to be in component units
            offset *= 2;

            for (int i = 0; i < inBuf.limit() / 2; i++) {
                tex.x = inBuf.get(i * 2);
                tex.y = inBuf.get(i * 2 + 1);
                Vector2f location = getLocation(tex, padding);
                //TODO: add proper texture wrapping for atlases..
                outBuf.put(offset + i * 2, location.x);
                outBuf.put(offset + i * 2 + 1, location.y);
            }
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }
}

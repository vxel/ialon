/*
 * Copyright (c) 2009-2020 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.delaunois.ialon;

import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.font.BitmapCharacter;
import com.jme3.font.BitmapCharacterSet;
import com.jme3.font.BitmapFont;
import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Texture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class BitmapFontLoader {

    private static BitmapFont load(AssetManager assetManager, String folder, InputStream in, int offx, int offy) throws IOException {
        MaterialDef spriteMat =
                assetManager.loadAsset(new AssetKey<>("Common/MatDefs/Misc/Unshaded.j3md"));
        BitmapCharacterSet charSet = new BitmapCharacterSet();
        Material[] matPages = null;
        BitmapFont font = new BitmapFont();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String regex = "[\\s=]+";
        font.setCharSet(charSet);
        String line;
        while ((line = reader.readLine()) != null) {
            String[] tokens = line.split(regex);
            if (tokens[0].equals("info")) {
                // Get rendered size
                for (int i = 1; i < tokens.length; i++) {
                    if (tokens[i].equals("size")) {
                        charSet.setRenderedSize(Integer.parseInt(tokens[i + 1]));
                    }
                }
            } else if (tokens[0].equals("common")) {
                // Fill out BitmapCharacterSet fields
                for (int i = 1; i < tokens.length; i++) {
                    String token = tokens[i];
                    if (token.equals("lineHeight")) {
                        charSet.setLineHeight(Integer.parseInt(tokens[i + 1]));
                    } else if (token.equals("base")) {
                        charSet.setBase(Integer.parseInt(tokens[i + 1]));
                    } else if (token.equals("scaleW")) {
                        charSet.setWidth(Integer.parseInt(tokens[i + 1]));
                    } else if (token.equals("scaleH")) {
                        charSet.setHeight(Integer.parseInt(tokens[i + 1]));
                    } else if (token.equals("pages")) {
                        // number of texture pages
                        matPages = new Material[Integer.parseInt(tokens[i + 1])];
                        font.setPages(matPages);
                    }
                }
            } else if (tokens[0].equals("page")) {
                int index = -1;
                Texture tex = null;

                for (int i = 1; i < tokens.length; i++) {
                    String token = tokens[i];
                    if (token.equals("id")) {
                        index = Integer.parseInt(tokens[i + 1]);
                    } else if (token.equals("file")) {
                        String file = tokens[i + 1];
                        if (file.startsWith("\"")) {
                            file = file.substring(1, file.length() - 1);
                        }
                        TextureKey key = new TextureKey(folder + file, true);
                        key.setGenerateMips(false);
                        tex = assetManager.loadTexture(key);
                        tex.setMagFilter(Texture.MagFilter.Bilinear);
                        tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
                    }
                }
                // set page
                if (index >= 0 && tex != null) {
                    Material mat = new Material(spriteMat);
                    mat.setTexture("ColorMap", tex);
                    mat.setBoolean("VertexColor", true);
                    mat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
                    matPages[index] = mat;
                }
            } else if (tokens[0].equals("char")) {
                // New BitmapCharacter
                BitmapCharacter ch = null;
                for (int i = 1; i < tokens.length; i++) {
                    String token = tokens[i];
                    if (token.equals("id")) {
                        int index = Integer.parseInt(tokens[i + 1]);
                        ch = new BitmapCharacter();
                        charSet.addCharacter(index, ch);
                    } else if (token.equals("x")) {
                        ch.setX(offx + Integer.parseInt(tokens[i + 1]));
                    } else if (token.equals("y")) {
                        ch.setY(offy + Integer.parseInt(tokens[i + 1]));
                    } else if (token.equals("width")) {
                        ch.setWidth(Integer.parseInt(tokens[i + 1]));
                    } else if (token.equals("height")) {
                        ch.setHeight(Integer.parseInt(tokens[i + 1]));
                    } else if (token.equals("xoffset")) {
                        ch.setXOffset(Integer.parseInt(tokens[i + 1]));
                    } else if (token.equals("yoffset")) {
                        ch.setYOffset(Integer.parseInt(tokens[i + 1]));
                    } else if (token.equals("xadvance")) {
                        ch.setXAdvance(Integer.parseInt(tokens[i + 1]));
                    } else if (token.equals("page")) {
                        ch.setPage(Integer.parseInt(tokens[i + 1]));
                    }
                }
            } else if (tokens[0].equals("kerning")) {
                // Build kerning list
                int index = 0;
                int second = 0;
                int amount = 0;

                for (int i = 1; i < tokens.length; i++) {
                    if (tokens[i].equals("first")) {
                        index = Integer.parseInt(tokens[i + 1]);
                    } else if (tokens[i].equals("second")) {
                        second = Integer.parseInt(tokens[i + 1]);
                    } else if (tokens[i].equals("amount")) {
                        amount = Integer.parseInt(tokens[i + 1]);
                    }
                }

                BitmapCharacter ch = charSet.getCharacter(index);
                ch.addKerning(second, amount);
            }
        }
        return font;
    }


    public static BitmapFont load(AssetInfo info) throws IOException {
        try (InputStream in = info.openStream()) {
            return load(info.getManager(), info.getKey().getFolder(), in, 0, 0);
        }
    }

    /**
     * Remap a given font to a tile in an atlas
     * @param info the key of the original font definition file
     * @param font the original font
     * @param textureAtlas the texture atlas
     * @return the remapped font
     * @throws IOException if the font could not be loaded
     */
    public static BitmapFont mapAtlasFont(AssetInfo info, BitmapFont font, TextureAtlas textureAtlas) throws IOException {
        // Find the tile in the atlas for the given font
        Texture fontTexture = font.getPage(0).getTextureParam("ColorMap").getTextureValue();
        TextureAtlas.TextureAtlasTile textureAtlasTile = textureAtlas.getAtlasTile(fontTexture);

        // Compute the atlas offset for the tile
        int offy = textureAtlas.getAtlasHeight() - textureAtlasTile.getY() - textureAtlasTile.getHeight();
        int offx = textureAtlasTile.getX();

        // Reload the characters using the x and Y offsets
        BitmapFont atlasFont;
        try (InputStream in = info.openStream()) {
            atlasFont = load(info.getManager(), info.getKey().getFolder(), in, offx, offy);
        }

        // Update the charset scale parameter to match the new texture
        atlasFont.getCharSet().setWidth(textureAtlas.getAtlasWidth());
        atlasFont.getCharSet().setHeight(textureAtlas.getAtlasHeight());

        return atlasFont;
    }
}

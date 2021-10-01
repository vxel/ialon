package org.delaunois.ialon;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetManager;
import com.jme3.material.MatParamTexture;
import com.jme3.material.Material;
import com.jme3.math.Vector2f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GdxTextureAtlas {

    private final List<Page> pages = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();
    private AssetManager assetManager;
    private final String dir;

    public GdxTextureAtlas(SimpleApplication app, String dir, String file) {
        this.dir = dir;
        assetManager = app.getAssetManager();
        if (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        load(dir + "/" + file, false);
    }

    public Texture getTexture() {
        if (pages.size() == 0) {
            return null;
        }
        return assetManager.loadTexture(dir + "/" + pages.get(0).filename);
    }

    public List<Page> getPages() {
        return pages;
    }

    public List<Region> getRegions() {
        return regions;
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
        Mesh inMesh = geom.getMesh();
        geom.computeWorldMatrix();

        VertexBuffer inBuf = inMesh.getBuffer(VertexBuffer.Type.TexCoord);
        VertexBuffer outBuf = outMesh.getBuffer(VertexBuffer.Type.TexCoord);

        if (inBuf == null || outBuf == null) {
            throw new IllegalStateException("Geometry mesh has no texture coordinate buffer.");
        }

        Texture tex = getMaterialTexture(geom, "DiffuseMap");
        if (tex == null) {
            tex = getMaterialTexture(geom, "ColorMap");

        }
        if (tex != null) {
            TextureAtlasTile tile = null;
            Texture finalTex = tex;
            Region region = regions.stream().filter(r -> finalTex.getName().contains(r.name)).findFirst().orElse(null);
            if (region != null) {
                tile = new TextureAtlasTile(region.left, region.top, region.width, region.height);
            }
            if (tile != null) {
                FloatBuffer inPos = (FloatBuffer) inBuf.getData();
                FloatBuffer outPos = (FloatBuffer) outBuf.getData();
                tile.transformTextureCoords(inPos, offset, outPos);
                return true;
            } else {
                return false;
            }
        } else {
            throw new IllegalStateException("Geometry has no proper texture.");
        }
    }

    private static Texture getMaterialTexture(Geometry geometry, String mapName) {
        Material mat = geometry.getMaterial();
        if (mat == null || mat.getParam(mapName) == null || !(mat.getParam(mapName) instanceof MatParamTexture)) {
            return null;
        }
        MatParamTexture param = (MatParamTexture) mat.getParam(mapName);
        Texture texture = param.getTextureValue();
        if (texture == null) {
            return null;
        }
        return texture;


    }
    public class TextureAtlasTile {

        final private int x;
        final private int y;
        private int width;
        private int height;

        public TextureAtlasTile(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /**
         * Get the transformed texture coordinate for a given input location.
         *
         * @param previousLocation The old texture coordinate.
         * @return The new texture coordinate inside the atlas.
         */
        public Vector2f getLocation(Vector2f previousLocation) {
            float atlasWidth = pages.get(0).width;
            float atlasHeight = pages.get(0).height;
            float x = getX() / (float) atlasWidth;
            float y = getY() / (float) atlasHeight;
            float w = getWidth() / (float) atlasWidth;
            float h = getHeight() / (float) atlasHeight;
            Vector2f location = new Vector2f(x, y);
            float prevX = previousLocation.x;
            float prevY = previousLocation.y;
            location.addLocal(prevX * w, prevY * h);
            return location;
        }

        /**
         * Transforms a whole texture coordinates buffer.
         *
         * @param inBuf  The input texture buffer.
         * @param offset The offset in the output buffer
         * @param outBuf The output buffer.
         */
        public void transformTextureCoords(FloatBuffer inBuf, int offset, FloatBuffer outBuf) {
            Vector2f tex = new Vector2f();

            // offset is given in element units
            // convert to be in component units
            offset *= 2;

            for (int i = 0; i < inBuf.limit() / 2; i++) {
                tex.x = inBuf.get(i * 2 + 0);
                tex.y = inBuf.get(i * 2 + 1);
                Vector2f location = getLocation(tex);
                //TODO: add proper texture wrapping for atlases..
                outBuf.put(offset + i * 2 + 0, location.x);
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


    public void load(String file, boolean flip) {
        final String[] entry = new String[5];
        final boolean[] hasIndexes = {false};

        Map<String, Field<Page>> pageFields = new HashMap<>();
        pageFields.put("size", page -> {
            page.width = Integer.parseInt(entry[1]);
            page.height = Integer.parseInt(entry[2]);
        });

        Map<String, Field<Region>> regionFields = new HashMap<>();
        regionFields.put("xy", region -> {
            region.left = Integer.parseInt(entry[1]);
            region.top = Integer.parseInt(entry[2]);
        });

        regionFields.put("size", region -> {
            region.width = Integer.parseInt(entry[1]);
            region.height = Integer.parseInt(entry[2]);
        });

        regionFields.put("bounds", region -> {
            region.left = Integer.parseInt(entry[1]);
            region.top = Integer.parseInt(entry[2]);
            region.width = Integer.parseInt(entry[3]);
            region.height = Integer.parseInt(entry[4]);
        });

        regionFields.put("offset", region -> {
            region.offsetX = Integer.parseInt(entry[1]);
            region.offsetY = Integer.parseInt(entry[2]);
        });

        regionFields.put("orig", region -> {
            region.originalWidth = Integer.parseInt(entry[1]);
            region.originalHeight = Integer.parseInt(entry[2]);
        });

        regionFields.put("offsets", region -> {
            region.offsetX = Integer.parseInt(entry[1]);
            region.offsetY = Integer.parseInt(entry[2]);
            region.originalWidth = Integer.parseInt(entry[3]);
            region.originalHeight = Integer.parseInt(entry[4]);
        });

        regionFields.put("rotate", region -> {
            String value = entry[1];
            if (value.equals("true"))
                region.degrees = 90;
            else if (!value.equals("false")) //
                region.degrees = Integer.parseInt(value);
            region.rotate = region.degrees == 90;
        });

        regionFields.put("index", region -> {
            region.index = Integer.parseInt(entry[1]);
            if (region.index != -1) hasIndexes[0] = true;
        });

        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(file);
        if (is == null) {
            throw new RuntimeException("Texture atlas file not found : " + file);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        try {
            String line = reader.readLine();
            // Ignore empty lines before first entry.
            while (line != null && line.trim().length() == 0)
                line = reader.readLine();
            // Header entries.
            while (true) {
                if (line == null || line.trim().length() == 0) break;
                if (readEntry(entry, line) == 0) break; // Silently ignore all header fields.
                line = reader.readLine();
            }
            // Page and region entries.
            Page page = null;
            List<String> names = null;
            List<int[]> values = null;
            while (true) {
                if (line == null) break;
                if (line.trim().length() == 0) {
                    page = null;
                    line = reader.readLine();
                } else if (page == null) {
                    page = new Page();
                    page.filename = line;
                    while (true) {
                        if (readEntry(entry, line = reader.readLine()) == 0) break;
                        Field<Page> field = pageFields.get(entry[0]);
                        if (field != null)
                            field.parse(page); // Silently ignore unknown page fields.
                    }
                    pages.add(page);
                } else {
                    Region region = new Region();
                    region.page = page;
                    region.name = line.trim();
                    if (flip) region.flip = true;
                    while (true) {
                        int count = readEntry(entry, line = reader.readLine());
                        if (count == 0) break;
                        Field<Region> field = regionFields.get(entry[0]);
                        if (field != null)
                            field.parse(region);
                        else {
                            if (names == null) {
                                names = new ArrayList<>(8);
                                values = new ArrayList<>(8);
                            }
                            names.add(entry[0]);
                            int[] entryValues = new int[count];
                            for (int i = 0; i < count; i++) {
                                try {
                                    entryValues[i] = Integer.parseInt(entry[i + 1]);
                                } catch (NumberFormatException ignored) { // Silently ignore non-integer values.
                                }
                            }
                            values.add(entryValues);
                        }
                    }
                    if (region.originalWidth == 0 && region.originalHeight == 0) {
                        region.originalWidth = region.width;
                        region.originalHeight = region.height;
                    }
                    if (names != null && names.size() > 0) {
                        region.names = names.toArray(new String[0]);
                        region.values = values.toArray(new int[0][0]);
                        names.clear();
                        values.clear();
                    }
                    regions.add(region);
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException("Error reading texture atlas file: " + file, ex);
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (hasIndexes[0]) {
            regions.sort((region1, region2) -> {
                int i1 = region1.index;
                if (i1 == -1) i1 = Integer.MAX_VALUE;
                int i2 = region2.index;
                if (i2 == -1) i2 = Integer.MAX_VALUE;
                return i1 - i2;
            });
        }
    }

    static private int readEntry(String[] entry, String line) {
        if (line == null) return 0;
        line = line.trim();
        if (line.length() == 0) return 0;
        int colon = line.indexOf(':');
        if (colon == -1) return 0;
        entry[0] = line.substring(0, colon).trim();
        for (int i = 1, lastMatch = colon + 1; ; i++) {
            int comma = line.indexOf(',', lastMatch);
            if (comma == -1) {
                entry[i] = line.substring(lastMatch).trim();
                return i;
            }
            entry[i] = line.substring(lastMatch, comma).trim();
            lastMatch = comma + 1;
            if (i == 4) return 4;
        }
    }

    private interface Field<T> {
        void parse(T object);
    }

    static public class Page {
        public String filename;
        public float width, height;
    }

    static public class Region {
        public Page page;
        public String name;
        public int left, top, width, height;
        public float offsetX, offsetY;
        public int originalWidth, originalHeight;
        public int degrees;
        public boolean rotate;
        public int index = -1;
        public String[] names;
        public int[][] values;
        public boolean flip;

        public int[] findValue(String name) {
            if (names != null) {
                for (int i = 0, n = names.length; i < n; i++)
                    if (name.equals(names[i])) return values[i];
            }
            return null;
        }
    }

    public static String dumpTextCoord(Geometry geometry) {
        return dumpBuffer(geometry, VertexBuffer.Type.TexCoord);
    }

    public static String dumpBuffer(Geometry geometry, VertexBuffer.Type type) {
        FloatBuffer buf = (FloatBuffer) geometry.getMesh().getBuffer(type).getData();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buf.limit() / 2; i++) {
            sb.append(String.format("%s:(%s, %s) ", i, buf.get(i * 2), buf.get(i * 2 + 1)));
        }
        return sb.toString();
    }
}

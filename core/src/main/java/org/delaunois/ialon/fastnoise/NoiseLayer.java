package org.delaunois.ialon.fastnoise;

import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.texture.image.ImageRaster;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.util.Random;

public class NoiseLayer {

    private String name;

    private int seed;

    private FastNoise primaryNoise;
    private FastNoise perturbNoise;
    private FastNoise lookupNoise;

    private GradientPerturb gradientPerturb;

    private boolean inverted = false;
    private boolean enabled = true;
    private float strength = 1;
    private Vector2f scale = new Vector2f(1, 1);

    // fastnoise changes the GradientPerturbAmp value when you set it, so when we get it, it won't be the same value.
    // So we'll keep a copy of what it's set to and get that, but we'll still set it on fastnoise.
    private float gradientPerturbAmp = 2.2f;

    // serialization
    public NoiseLayer() {
        this("New Noise Layer");
    }

    public NoiseLayer(String name) {
        this(name, new FastNoise());
    }

    public NoiseLayer(String name, int seed) {
        this(name, new FastNoise(seed));

        this.primaryNoise.SetGradientPerturbAmp(gradientPerturbAmp);
    }

    public NoiseLayer(String name, FastNoise fastNoise) {

        this.name = name;
        this.primaryNoise = fastNoise;

        lookupNoise = new FastNoise();
        lookupNoise.SetFrequency(0.2f);
        lookupNoise.SetNoiseType(FastNoise.NoiseType.Simplex);
        fastNoise.SetCellularNoiseLookup(lookupNoise);

        perturbNoise = new FastNoise();
        perturbNoise.SetFrequency(0.015f);
        perturbNoise.SetGradientPerturbAmp(gradientPerturbAmp);

        this.gradientPerturb = GradientPerturb.Off;

    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public FastNoise getPrimaryNoise() {
        return primaryNoise;
    }

    public FastNoise getPerturbNoise() { return perturbNoise; }
    public void setPerturbNoise(FastNoise perturbNoise) { this.perturbNoise = perturbNoise; }

    public FastNoise getLookupNoise() { return lookupNoise; }
    public void setLookupNoise(FastNoise lookupNoise) { this.lookupNoise = lookupNoise; }

    public float evaluate(Vector2f v) {

        if (!enabled) {
            return 0;
        }

        Vector2f f = v.clone();

        switch (gradientPerturb.ordinal())
        {
            case 1:
                perturbNoise.GradientPerturb(f);
                break;
            case 2:
                perturbNoise.GradientPerturbFractal(f);
                break;
        }

        float noise = primaryNoise.GetNoise(f.x * scale.x, f.y * scale.y);

        if (inverted) {
            noise = -noise;
        }

        return noise;

    }

    private boolean get3d;
    private float zPos = 0.5f;

    public boolean isGet3d() {
        return get3d;
    }

    public void setGet3d(boolean get3d) {
        this.get3d = get3d;
    }

    public Texture2D generateTexture(int size) {

        ByteBuffer buffer = BufferUtils.createByteBuffer(size * size * 4);
        Image result = new Image(Image.Format.RGB8, size, size, buffer, ColorSpace.sRGB);
        ImageRaster imageRaster = ImageRaster.create(result);



        int halfSize = size / 2;

        float avg = 0;
        float maxN = 0;
        float minN = 0;

        int index = 0;

        long current = 0;

        // boolean get3d = false;

        float noise = 0;

        if (!primaryNoise.GetNoiseType().toString().toLowerCase().endsWith("perturb")) {

            float[] noiseValues = new float[size * size];
            int warpIndex = gradientPerturb.ordinal();

            if (get3d) {

                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {

                        Vector3f f = new Vector3f(x - halfSize, y - halfSize, zPos);

                        switch (warpIndex) {
                            case 1:
                                perturbNoise.GradientPerturb(f);
                                break;
                            case 2:
                                perturbNoise.GradientPerturbFractal(f);
                                break;
                        }
                        //noise = fNoise.GetNoise(xf, yf, zf);
                        noise = primaryNoise.GetNoise(f.x, f.y, f.z);

                        avg += noise;
                        maxN = Math.max(maxN, noise);
                        minN = Math.min(minN, noise);
                        noiseValues[index++] = noise;
                    }

                }
            }

            else {

                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {

                        // float xf = (float) (x - halfSize);
                        // float yf = (float) (y - halfSize);
                        Vector2f f = new Vector2f((float) (x - halfSize), (float) (y - halfSize));

                        switch (warpIndex)
                        {
                            case 1:
                                perturbNoise.GradientPerturb(f);
                                break;
                            case 2:
                                perturbNoise.GradientPerturbFractal(f);
                                break;
                        }

                        noise = primaryNoise.GetNoise(f.x, f.y);

                        avg += noise;
                        maxN = Math.max(maxN, noise);
                        minN = Math.min(minN, noise);
                        noiseValues[index++] = noise;

                    }
                }
            }

            avg /= index - 1;
            index = 0;
            float scale = 255 / (maxN - minN);

            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {

                    noise = noiseValues[index++];

                    // unsigned char value = (unsigned char)fmax(0, fmin(255, (noise - minN) * scale));
                    int value = (int) Math.max(0, Math.min(255, (noise - minN) * scale));

                    if (inverted) {
                        value = 255 - value;
                    }

                    // bitmap->SetPixel(x, y, Color::FromArgb(255, value, value, value));
                    ColorRGBA color = new ColorRGBA(value / 255f, value / 255f, value / 255f, 1.0f);
                    imageRaster.setPixel(x, y, color);

                }
            }


        }
        else {

            float[] noiseValues = new float[size * size * 3];
            boolean fractal = primaryNoise.GetNoiseType().toString().toLowerCase().endsWith("fractal");

            if (get3d) {

            }
            else {

                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {

                        // float xf = (float)x;
                        // float yf = (float)y;

                        Vector2f f = new Vector2f(x, y);

                        if (fractal) {
                            primaryNoise.GradientPerturbFractal(f);
                        }
                        else {
                            primaryNoise.GradientPerturb(f);
                        }

                        f.x -= x;
                        f.y -= y;

                        //avg += f.x + f.y;

                        maxN = Math.max(maxN, Math.max(f.x, f.y));
                        minN = Math.min(minN, Math.min(f.x, f.y));

                        noiseValues[index++] = f.x;
                        noiseValues[index++] = f.y;
                    }
                }
            }

            if (get3d) {
                //avg /= (index - 1) * 3;
            }
            else {
                //avg /= (index - 1) * 2;
            }

            index = 0;
            float scale = 255 / (maxN - minN);

            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {

                    int red = 0;
                    int green = 0;
                    int blue = 0;

                    if (get3d) {

                    }
                    else {

                        HSV hsv = new HSV();
                        hsv.h = (int) ((noiseValues[index++] - minN) * scale);
                        hsv.s = 255;
                        hsv.v = (int) ((noiseValues[index++] - minN) * scale);

                        // RGB rgb = HSV2RGB(HSV{ (unsigned char)((noiseValues[index++] - minN) * scale), 255, (unsigned char)((noiseValues[index++] - minN) * scale) });
                        RGB rgb = HSV2RGB(hsv);

                        red = rgb.r;
                        green = rgb.g;
                        blue = rgb.b;
                    }

                    if (inverted) {
                        red = 255 - red;
                        green = 255 - green;
                        blue = 255 - blue;
                    }

                    ColorRGBA colorRGBA = new ColorRGBA(red / 255f, green / 255f, blue / 255f, 1.0f);
                    imageRaster.setPixel(x, y, colorRGBA);
                }
            }

        }

        return new Texture2D(result);
    }

    public void setSeed(int seed) {
        this.seed = seed;
        Random random = new Random(seed);

        primaryNoise.SetSeed(random.nextInt());
        perturbNoise.SetSeed(random.nextInt());
        lookupNoise.SetSeed(random.nextInt());
    }

    public int getSeed() {
        return seed;
    }

    public FastNoise.NoiseType getNoiseType() {
        return primaryNoise.GetNoiseType();
    }

    public void setNoiseType(FastNoise.NoiseType noiseType) {
        primaryNoise.SetNoiseType(noiseType);
    }

    public float getFrequency() {
        return primaryNoise.GetFrequency();
    }

    public void setFrequency(float frequency) {
        primaryNoise.SetFrequency(frequency);
    }

    public FastNoise.Interp getInterp() {
        return primaryNoise.GetInterp();
    }

    public void setInterp(FastNoise.Interp interp) {
        primaryNoise.SetInterp(interp);
    }

    public FastNoise.FractalType getFractalType() {
        return primaryNoise.GetFractalType();
    }

    public void setFractalType(FastNoise.FractalType fractalType) {
        primaryNoise.SetFractalType(fractalType);
    }

    public int getFractalOctaves() {
        return primaryNoise.GetFractalOctaves();
    }

    public void setFractalOctaves(int fractalOctaves) {
        primaryNoise.SetFractalOctaves(fractalOctaves);
    }

    public float getFractalLacunarity() {
        return primaryNoise.GetFractalLacunarity();
    }

    public void setFractalLacunarity(float fractalLacunarity) {
        primaryNoise.SetFractalLacunarity(fractalLacunarity);
    }

    public float getFractalGain() {
        return primaryNoise.GetFractalGain();
    }

    public void setFractalGain(float fractalGain) {
        primaryNoise.SetFractalGain(fractalGain);
    }

    public FastNoise.CellularDistanceFunction getCellularDistanceFunction() {
        return primaryNoise.GetCellularDistanceFunction();
    }

    public void setCellularDistanceFunction(FastNoise.CellularDistanceFunction cellularDistanceFunction) {
        primaryNoise.SetCellularDistanceFunction(cellularDistanceFunction);
    }

    public FastNoise.CellularReturnType getCellularReturnType() {
        return primaryNoise.GetCellularReturnType();
    }

    public void setCellularReturnType(FastNoise.CellularReturnType cellularReturnType) {
        primaryNoise.SetCellularReturnType(cellularReturnType);
    }

    public GradientPerturb getGradientPerturb() {
        return gradientPerturb;
    }

    public void setGradientPerturb(GradientPerturb gradientPerturb) {
        this.gradientPerturb = gradientPerturb;
    }

    public float getGradientPerturbAmp() {
        return gradientPerturbAmp;
    }

    public void setGradientPerturbAmp(float gradientPerturbAmp) {
        this.gradientPerturbAmp = gradientPerturbAmp;
        primaryNoise.SetGradientPerturbAmp(gradientPerturbAmp);
    }

    public boolean isInverted() {
        return inverted;
    }

    public void setInverted(boolean inverted) {
        this.inverted = inverted;
    }

    public float getStrength() {
        return strength;
    }

    public void setStrength(float strength) {
        this.strength = strength;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Vector2f getScale() {
        return scale;
    }

    public void setScale(Vector2f scale) {
        this.scale.set(scale);
    }

    public void setScale(float x, float y) {
        scale.set(x, y);
    }

    public void setScaleX(float x) {
        scale.setX(x);
    }

    public void setScaleY(float y) {
        scale.setY(y);
    }

    private class RGB {
        public int r, g, b;
    }

    private class HSV {
        public int h, s, v;
    }

    private RGB HSV2RGB(HSV hsv) {

        RGB rgb = new RGB();

        int region, remainder, p, q, t;

        if (hsv.s == 0)
        {
            rgb.r = hsv.v;
            rgb.g = hsv.v;
            rgb.b = hsv.v;
            return rgb;
        }

        region = hsv.h / 43;
        remainder = (hsv.h - (region * 43)) * 6;

        p = (hsv.v * (255 - hsv.s)) >> 8;
        q = (hsv.v * (255 - ((hsv.s * remainder) >> 8))) >> 8;
        t = (hsv.v * (255 - ((hsv.s * (255 - remainder)) >> 8))) >> 8;

        switch (region)
        {
            case 0:
                rgb.r = hsv.v; rgb.g = t; rgb.b = p;
                break;
            case 1:
                rgb.r = q; rgb.g = hsv.v; rgb.b = p;
                break;
            case 2:
                rgb.r = p; rgb.g = hsv.v; rgb.b = t;
                break;
            case 3:
                rgb.r = p; rgb.g = q; rgb.b = hsv.v;
                break;
            case 4:
                rgb.r = t; rgb.g = p; rgb.b = hsv.v;
                break;
            default:
                rgb.r = hsv.v; rgb.g = p; rgb.b = q;
                break;
        }

        return rgb;
    }

    @Override
    public String toString() {
        return name;
    }

}

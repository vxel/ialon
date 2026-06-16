/**
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

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.post.Filter;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;

/**
 * Full-screen underwater post-process : a gentle view ripple (refraction) plus a bluish
 * exponential distance fog that shortens the underwater view range. Backed by the
 * {@code Shaders/Underwater.j3md} material ; depth-aware (the fog reads the scene depth).
 *
 * The filter is owned by {@link UnderwaterState}, which attaches/detaches it from the main
 * viewport's {@code FilterPostProcessor} depending on whether the camera is submerged, and drives
 * {@link #setIntensity(float)} for a smooth fade around the water line. The ripple is animated from
 * an internal time accumulator updated in {@link #preFrame(float)} (independent of the day/night
 * clock), so it keeps moving even when the rest of the scene is paused.
 */
public class UnderwaterFilter extends Filter {

    private final ColorRGBA fogColor = new ColorRGBA(0.1f, 0.3f, 0.5f, 1f);
    private float fogDensity = 2.5f;
    private float fogDistance = 40f;
    private float distortionAmplitude = 0.008f;
    private float distortionSpeed = 1.5f;
    private float distortionFrequency = 18f;
    private float intensity = 1f;
    private boolean manualSrgb = false;
    private float time = 0f;

    // Scene camera + reusable matrix to feed the world-position reconstruction (no per-frame alloc).
    private Camera camera;
    private final Matrix4f viewProjInverse = new Matrix4f();

    public UnderwaterFilter() {
        super("UnderwaterFilter");
    }

    @Override
    protected boolean isRequiresDepthTexture() {
        return true;
    }

    @Override
    protected void initFilter(AssetManager manager, RenderManager renderManager, ViewPort vp, int w, int h) {
        this.camera = vp.getCamera();
        material = new Material(manager, "Shaders/Underwater.j3md");
        material.setColor("FogColor", fogColor);
        material.setFloat("FogDensity", fogDensity);
        material.setFloat("FogDistance", fogDistance);
        material.setFloat("DistortionAmplitude", distortionAmplitude);
        material.setFloat("DistortionSpeed", distortionSpeed);
        material.setFloat("DistortionFrequency", distortionFrequency);
        material.setFloat("Intensity", intensity);
        material.setFloat("Time", time);
        material.setBoolean("ManualSrgb", manualSrgb);
    }

    @Override
    protected void preFrame(float tpf) {
        time += tpf;
        if (material != null) {
            material.setFloat("Time", time);
            if (camera != null) {
                // Inverse view-projection of the scene camera, to unproject depth -> world position
                // in the shader (for the horizontal-only fog distance).
                viewProjInverse.set(camera.getViewProjectionMatrix());
                viewProjInverse.invertLocal();
                material.setMatrix4("ViewProjInverse", viewProjInverse);
                material.setVector3("CameraPosition", camera.getLocation());
            }
        }
    }

    @Override
    protected Material getMaterial() {
        return material;
    }

    public void setFogColor(ColorRGBA color) {
        fogColor.set(color);
        if (material != null) {
            material.setColor("FogColor", fogColor);
        }
    }

    public void setFogDensity(float fogDensity) {
        this.fogDensity = fogDensity;
        if (material != null) {
            material.setFloat("FogDensity", fogDensity);
        }
    }

    public void setFogDistance(float fogDistance) {
        this.fogDistance = fogDistance;
        if (material != null) {
            material.setFloat("FogDistance", fogDistance);
        }
    }

    public void setDistortionAmplitude(float distortionAmplitude) {
        this.distortionAmplitude = distortionAmplitude;
        if (material != null) {
            material.setFloat("DistortionAmplitude", distortionAmplitude);
        }
    }

    public void setDistortionSpeed(float distortionSpeed) {
        this.distortionSpeed = distortionSpeed;
        if (material != null) {
            material.setFloat("DistortionSpeed", distortionSpeed);
        }
    }

    public void setDistortionFrequency(float distortionFrequency) {
        this.distortionFrequency = distortionFrequency;
        if (material != null) {
            material.setFloat("DistortionFrequency", distortionFrequency);
        }
    }

    public void setIntensity(float intensity) {
        this.intensity = intensity;
        if (material != null) {
            material.setFloat("Intensity", intensity);
        }
    }

    public float getIntensity() {
        return intensity;
    }

    public void setManualSrgb(boolean manualSrgb) {
        this.manualSrgb = manualSrgb;
        if (material != null) {
            material.setBoolean("ManualSrgb", manualSrgb);
        }
    }
}

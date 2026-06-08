#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Blocks/Shaders/BlendFunctions.glsllib"

const float ATLAS_SIZE = 2048.0;
const float TEX_SIZE = 128.0;
const float NUM_TEXTURES = ATLAS_SIZE / TEX_SIZE;
const float UV_TEX_SIZE = 1.0 / NUM_TEXTURES;
const float UV_PADDING = 0.25 * UV_TEX_SIZE;
const float PADDED_UV_TEX_SIZE = UV_TEX_SIZE - 2.0 * UV_PADDING;

#ifdef DIFFUSEMAP
  uniform sampler2D m_DiffuseMap;
#endif

uniform float m_AlphaDiscardThreshold;

flat in vec2 wrapCoordMin;
flat in vec2 wrapCoordMax;

in vec2 texCoord;
in vec3 AmbientSum;
in vec4 DiffuseSum;
in float fogFactor;

void main() {

    vec4 diffuseColor;
    vec2 uv = texCoord;

    // Without a diffuse map (e.g. the flat-colour calm-water material) the whole sampling block is
    // compiled out : referencing m_DiffuseMap when DIFFUSEMAP is undefined is a GLSL compile error.
    #ifdef DIFFUSEMAP
    if (wrapCoordMin.y > 0.0) {
       if (uv.y >= wrapCoordMax.y) {
            uv.y = uv.y - PADDED_UV_TEX_SIZE;
        } else if (uv.y <= wrapCoordMin.y) {
            uv.y = uv.y + PADDED_UV_TEX_SIZE;
        }

        if (uv.x >= wrapCoordMax.x) {
            uv.x = uv.x - PADDED_UV_TEX_SIZE;
        } else if (uv.x <= wrapCoordMin.x) {
            uv.x = uv.x + PADDED_UV_TEX_SIZE;
        }
        diffuseColor = textureGrad(m_DiffuseMap, uv, dFdx(texCoord), dFdy(texCoord));

    } else {
        diffuseColor = texture2D(m_DiffuseMap, uv);
    }
    #else
    diffuseColor = vec4(1.0);
    #endif

#if defined(DIFFUSEMAP) && defined(MANUAL_SRGB)
    // Emulate the hardware sRGB texture decode on platforms without an sRGB framebuffer (Android
    // GLES) : bring the sRGB-authored atlas texel into linear space before the (linear) lighting.
    diffuseColor.rgb = pow(diffuseColor.rgb, vec3(2.2));
#endif

    float alpha = DiffuseSum.a * diffuseColor.a;

    if (alpha < m_AlphaDiscardThreshold){
        discard;
    }

    // Linear-space lighting : on desktop the texture is hardware-decoded sRGB->linear (sRGB atlas +
    // gamma correction) and the sRGB framebuffer re-encodes the output. Where the hardware sRGB
    // framebuffer is missing (Android GLES) MANUAL_SRGB does the decode (above) and the encode (below).
    gl_FragColor.rgb = (AmbientSum.rgb + DiffuseSum.rgb) * diffuseColor.rgb;
#ifdef MANUAL_SRGB
    gl_FragColor.rgb = pow(gl_FragColor.rgb, vec3(1.0 / 2.2));
#endif
    gl_FragColor.a = alpha;
    //gl_FragColor = mix(vec4(0.39, 0.67, 1.0, 1.0), gl_FragColor, fogFactor);
}

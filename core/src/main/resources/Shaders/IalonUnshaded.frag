#import "Common/ShaderLib/GLSLCompat.glsllib"

// Fork of Common/MatDefs/Misc/Unshaded.frag adding the MANUAL_SRGB path : when the platform has no
// hardware sRGB framebuffer (e.g. Android GLES, where setGammaCorrection is a no-op) this shader
// emulates it, so elements rendered with it (sky dome, ground plate) match the desktop hardware path.
// Inputs are treated exactly as on desktop : the sRGB ColorMap is decoded to linear, vertex/material
// colours are already authored linear (IalonConfig stores them via setAsSrgb), and the final colour
// is re-encoded to sRGB on output.

#if defined(HAS_LIGHTMAP) && !defined(SEPARATE_TEXCOORD)
    #define NEED_TEXCOORD1
#endif

#if defined(DISCARD_ALPHA)
    uniform float m_AlphaDiscardThreshold;
#endif

uniform vec4 m_Color;
uniform sampler2D m_ColorMap;
uniform sampler2D m_LightMap;

#ifdef DESATURATION
    uniform float m_DesaturationValue;
#endif

varying vec2 texCoord1;
varying vec2 texCoord2;

varying vec4 vertColor;

void main(){
    vec4 color = vec4(1.0);

    #ifdef HAS_COLORMAP
        vec4 texVal = texture2D(m_ColorMap, texCoord1);
        #ifdef MANUAL_SRGB
            // Emulate the hardware sRGB texture decode : bring the sRGB texel into linear space.
            texVal.rgb = pow(texVal.rgb, vec3(2.2));
        #endif
        color *= texVal;
    #endif

    #ifdef HAS_VERTEXCOLOR
        color *= vertColor;
    #endif

    #ifdef HAS_COLOR
        color *= m_Color;
    #endif

    #ifdef HAS_LIGHTMAP
        #ifdef SEPARATE_TEXCOORD
            color.rgb *= texture2D(m_LightMap, texCoord2).rgb;
        #else
            color.rgb *= texture2D(m_LightMap, texCoord1).rgb;
        #endif
    #endif

    #if defined(DISCARD_ALPHA)
        if(color.a < m_AlphaDiscardThreshold){
           discard;
        }
    #endif

    #ifdef DESATURATION
        vec3 gray = vec3(dot(vec3(0.2126,0.7152,0.0722), color.rgb));
        color.rgb = vec3(mix(color.rgb, gray, m_DesaturationValue));
    #endif

    #ifdef MANUAL_SRGB
        // Emulate the hardware sRGB framebuffer encode (linear -> sRGB) on platforms without one.
        color.rgb = pow(color.rgb, vec3(1.0 / 2.2));
    #endif

    gl_FragColor = color;
}

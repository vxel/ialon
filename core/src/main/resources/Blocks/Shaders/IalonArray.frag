#import "Common/ShaderLib/GLSLCompat.glsllib"

#ifdef GL_ES
precision highp float;
precision highp sampler2DArray;
#endif

#ifdef DIFFUSEARRAY
  uniform sampler2DArray m_DiffuseArray;
#endif

uniform float m_AlphaDiscardThreshold;

in vec2 texCoord;
flat in float layer;
in vec3 AmbientSum;
in vec4 DiffuseSum;

void main() {

    vec4 diffuseColor;

    #ifdef DIFFUSEARRAY
    diffuseColor = texture(m_DiffuseArray, vec3(texCoord, layer));
    #else
    diffuseColor = vec4(1.0);
    #endif

#if defined(DIFFUSEARRAY) && defined(MANUAL_SRGB)
    // Emulate the hardware sRGB texture decode on platforms without an sRGB framebuffer (Android GLES) :
    // bring the sRGB-authored texel into linear space before the (linear) lighting.
    diffuseColor.rgb = pow(diffuseColor.rgb, vec3(2.2));
#endif

    float alpha = DiffuseSum.a * diffuseColor.a;

    if (alpha < m_AlphaDiscardThreshold){
        discard;
    }

    gl_FragColor.rgb = (AmbientSum.rgb + DiffuseSum.rgb) * diffuseColor.rgb;
#ifdef MANUAL_SRGB
    gl_FragColor.rgb = pow(gl_FragColor.rgb, vec3(1.0 / 2.2));
#endif
    gl_FragColor.a = alpha;
}

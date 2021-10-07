#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Blocks/Shaders/BlendFunctions.glsllib"

varying vec2 texCoord;
varying vec3 AmbientSum;
varying vec4 DiffuseSum;

#ifdef DIFFUSEMAP
  uniform sampler2D m_DiffuseMap;
#endif

uniform float m_AlphaDiscardThreshold;

// overlay map and color
#ifdef OVERLAYCOLOR
    uniform sampler2D m_OverlayMap;
    uniform vec4 m_OverlayColor;
#endif

void main() {

    #ifdef DIFFUSEMAP
      vec4 diffuseColor = texture2D(m_DiffuseMap, texCoord);
    #else
      vec4 diffuseColor = vec4(1.0);
    #endif

    float alpha = DiffuseSum.a * diffuseColor.a;

    if (alpha < m_AlphaDiscardThreshold){
        discard;
    }

    // overlay map and color
    // use the m_OverlayMap texture to determine what pixels should use the overlay color.
    // all pixels that have an alpha value > 0 will blend with the overlay color
    #ifdef OVERLAYCOLOR
        float overlayFactor = texture2D(m_OverlayMap, texCoord).a;
        vec3 blendedColor = blend_overlay(diffuseColor, m_OverlayColor).rgb;
        diffuseColor.rgb = (1.0 - overlayFactor) * diffuseColor.rgb + overlayFactor * blendedColor;
    #endif

    gl_FragColor.rgb = AmbientSum.rgb * diffuseColor.rgb
                         + DiffuseSum.rgb  * diffuseColor.rgb;

    gl_FragColor.a = alpha;
}

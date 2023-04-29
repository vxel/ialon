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

void main() {

    vec4 diffuseColor;
    vec2 uv = texCoord;

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
        #ifdef DIFFUSEMAP
        diffuseColor = texture2D(m_DiffuseMap, uv);
        #else
        diffuseColor = vec4(1.0);
        #endif
    }

    float alpha = DiffuseSum.a * diffuseColor.a;

    if (alpha < m_AlphaDiscardThreshold){
        discard;
    }

    gl_FragColor.rgb = (AmbientSum.rgb + DiffuseSum.rgb) * diffuseColor.rgb;
    gl_FragColor.a = alpha;
}

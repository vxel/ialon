#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Instancing.glsllib"
#import "Common/ShaderLib/Lighting.glsllib"

#ifdef VERTEX_LIGHTING
    #import "Common/ShaderLib/BlinnPhongLighting.glsllib"
#endif

uniform vec4 m_Ambient;
uniform vec4 m_Diffuse;
uniform vec4 m_Specular;
uniform float m_Shininess;

#if defined(VERTEX_LIGHTING)
    uniform vec4 g_LightData[NB_LIGHTS];
#endif
uniform vec4 g_AmbientLightColor;

attribute vec3 inPosition;
attribute vec2 inTexCoord;
attribute vec3 inNormal;

// Animated texture
uniform float g_Time;
uniform float m_TextureScrollSpeedX;
uniform float m_TextureScrollSpeedY;

const float ATLAS_SIZE = 2048.0;
const float TEX_SIZE = 128.0;
const float NUM_TEXTURES = ATLAS_SIZE / TEX_SIZE;
const float UV_TEX_SIZE = 1.0 / NUM_TEXTURES;
const float UV_PADDING = 0.25 * UV_TEX_SIZE;
const float PADDED_UV_TEX_SIZE = UV_TEX_SIZE - 2.0 * UV_PADDING;

flat out vec2 wrapCoordMin;
flat out vec2 wrapCoordMax;

out vec3 AmbientSum;
out vec4 DiffuseSum;
out vec3 SpecularSum;

out vec2 texCoord;

#ifdef VERTEX_COLOR
    attribute vec4 inColor;
    const float lightDecay = 2.2;
    const float levels[16] = float[16](
        0.0,
        pow(1.0 / 15.0, lightDecay),
        pow(2.0 / 15.0, lightDecay),
        pow(3.0 / 15.0, lightDecay),
        pow(4.0 / 15.0, lightDecay),
        pow(5.0 / 15.0, lightDecay),
        pow(6.0 / 15.0, lightDecay),
        pow(7.0 / 15.0, lightDecay),
        pow(8.0 / 15.0, lightDecay),
        pow(9.0 / 15.0, lightDecay),
        pow(10.0 / 15.0, lightDecay),
        pow(11.0 / 15.0, lightDecay),
        pow(12.0 / 15.0, lightDecay),
        pow(13.0 / 15.0, lightDecay),
        pow(14.0 / 15.0, lightDecay),
        1.0
    );
#endif

void main() {
    vec4 modelSpacePos = vec4(inPosition, 1.0);
    vec3 modelSpaceNorm = inNormal;

    gl_Position = TransformWorldViewProjection(modelSpacePos);

    if (abs(m_TextureScrollSpeedX) > 0.0 || abs(m_TextureScrollSpeedY) > 0.0) {
        // Texture scrolling

        // Index (x, y) of the tile in the texture atlas. Example : (4, 2)
        vec2 tileAtlasIndex = floor(inTexCoord / UV_TEX_SIZE);

        // UV offset of the tile in the texture atlas. Example : (0.08, 0.04)
        vec2 tileOffset = tileAtlasIndex * UV_TEX_SIZE;

        // Coordinate of the texture for this vertex, relative to the tile offset, e.g. (0, 0)
        vec2 tileUV = inTexCoord - tileOffset;
        wrapCoordMin = tileOffset + UV_PADDING;
        wrapCoordMax = wrapCoordMin + PADDED_UV_TEX_SIZE;

        // Scroll against time, modulo the (padded) texture size
        vec2 delta = vec2(mod(g_Time * m_TextureScrollSpeedX, PADDED_UV_TEX_SIZE), mod(g_Time * m_TextureScrollSpeedY, PADDED_UV_TEX_SIZE));

        texCoord = tileOffset + fract(tileUV) - delta;

    } else {
        wrapCoordMin = vec2(0.0, 0.0);
        texCoord = inTexCoord;
    }

    vec3 wvPosition = TransformWorldView(modelSpacePos).xyz;
    vec3 wvNormal  = normalize(TransformNormal(modelSpaceNorm));
    vec3 viewDir = normalize(-wvPosition);

    #ifdef MATERIAL_COLORS
         AmbientSum  = m_Ambient.rgb * g_AmbientLightColor.rgb;
         SpecularSum = m_Specular.rgb;
         DiffuseSum = m_Diffuse;
    #else
         // Defaults: Ambient and diffuse are white, specular is black.
         AmbientSum  = g_AmbientLightColor.rgb;
         SpecularSum = vec3(0.0);
         DiffuseSum = vec4(1.0);
    #endif

    #ifdef VERTEX_COLOR
         int SunIntensity = (int(inColor.a) >> 4) & 0xF;
         int TorchIntensity = (int(inColor.a)) & 0xF;

         // Adapt the sunlight to the current ambient level (i.e. night or day)
         float lum = AmbientSum.r * 0.3 + AmbientSum.g * 0.59 + AmbientSum.b * 0.11;
         float lightLevel = levels[SunIntensity] * lum;

         // Get the maximum light between Sun and Torch lights
         lightLevel = max(lightLevel, levels[TorchIntensity]);

         AmbientSum.xyz = lightLevel * inColor.rgb;
         DiffuseSum *= vec4(AmbientSum.r, AmbientSum.g, AmbientSum.b, 1);
    #endif

    #ifdef VERTEX_LIGHTING
         int i = 0;
         vec3 diffuseAccum  = vec3(0.0);
         vec3 specularAccum = vec3(0.0);
         vec4 diffuseColor;
         vec3 specularColor;
         vec4 lightColor = g_LightData[0];
         vec4 lightData1 = g_LightData[1];
         #ifdef MATERIAL_COLORS
             diffuseColor  = m_Diffuse * vec4(lightColor.rgb, 1.0);
             specularColor = m_Specular.rgb * lightColor.rgb;
         #else
             diffuseColor  = vec4(lightColor.rgb, 1.0);
             specularColor = vec3(0.0);
         #endif

         vec4 lightDir;
         vec3 lightVec;
         lightComputeDir(wvPosition, lightColor.w, lightData1, lightDir, lightVec);

         float spotFallOff = 1.0;
         #if __VERSION__ >= 110
         // allow use of control flow
         if (lightColor.w > 1.0) {
         #endif
              vec4 lightDirection = g_LightData[2];
              spotFallOff = computeSpotFalloff(lightDirection, lightVec);
         #if __VERSION__ >= 110
         }
         #endif
         vec2 light = computeLighting(wvNormal, viewDir, lightDir.xyz, lightDir.w  * spotFallOff, m_Shininess);

         diffuseAccum  += light.x * diffuseColor.rgb;
         specularAccum += light.y * specularColor;

         DiffuseSum.rgb  *= diffuseAccum.rgb;
         SpecularSum.rgb *= specularAccum.rgb;
     #endif

}
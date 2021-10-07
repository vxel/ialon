#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Instancing.glsllib"
#import "Common/ShaderLib/Skinning.glsllib"
#import "Common/ShaderLib/Lighting.glsllib"
#import "Common/ShaderLib/MorphAnim.glsllib"

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

varying vec3 AmbientSum;
varying vec4 DiffuseSum;
varying vec3 SpecularSum;

attribute vec3 inPosition;
attribute vec2 inTexCoord;
attribute vec3 inNormal;

// Animated texture
uniform float g_Time;
uniform float m_TextureScrollSpeedX;
uniform float m_TextureScrollSpeedY;
out vec2 tileOffset;
out vec2 tileCoord;
varying vec2 texCoord;

const float ATLAS_SIZE = 2048.0;
const float TEX_SIZE = 128.0;
const float NUM_TEXTURES = ATLAS_SIZE / TEX_SIZE;
const float NORM_TEX_SIZE = 1.0 / NUM_TEXTURES;

const float UV_PADDING = 0.25;
const float UV_PADDING_FACTOR = 1.0 / (1.0 - 2.0 * UV_PADDING);

#ifdef VERTEX_COLOR
    attribute vec4 inColor;
    const float levels[16] = float[16](
        0.0,
        1.0 / 255.0,
        2.0 * 2.0 / 255.0,
        3.0 * 3.0 / 255.0,
        4.0 * 4.0 / 255.0,
        5.0 * 5.0 / 255.0,
        6.0 * 6.0 / 255.0,
        7.0 * 7.0 / 255.0,
        8.0 * 8.0 / 255.0,
        9.0 * 9.0 / 255.0,
        10.0 * 10.0 / 255.0,
        11.0 * 11.0 / 255.0,
        12.0 * 12.0 / 255.0,
        13.0 * 13.0 / 255.0,
        14.0 * 14.0 / 255.0,
        1.0
    );
#endif

void main() {
    vec4 modelSpacePos = vec4(inPosition, 1.0);
    vec3 modelSpaceNorm = inNormal;

    gl_Position = TransformWorldViewProjection(modelSpacePos);

    //if (abs(m_TextureScrollSpeedX) > 0 || abs(m_TextureScrollSpeedY) > 0) {
        tileOffset = floor(inTexCoord / NORM_TEX_SIZE);
        tileCoord.x = mod(inTexCoord.x, NORM_TEX_SIZE) - mod(g_Time * m_TextureScrollSpeedX, NORM_TEX_SIZE / 4.0);
        tileCoord.y = mod(inTexCoord.y, NORM_TEX_SIZE) - mod(g_Time * m_TextureScrollSpeedY, NORM_TEX_SIZE / 4.0);
        //tileCoord.x = mod(inTexCoord.x + g_Time * m_TextureScrollSpeedX, NORM_TEX_SIZE);
        //tileCoord.y = mod(inTexCoord.y + g_Time * m_TextureScrollSpeedY, NORM_TEX_SIZE);
        //texCoord.x = tileOffset.x * NORM_TEX_SIZE + tileCoord.x;
        //texCoord.y = tileOffset.y * NORM_TEX_SIZE + tileCoord.y;
    //} else {
    //    texCoord = inTexCoord;
    //}

    //deltaUvX = mod(texCoord.x + g_Time * m_TextureScrollSpeedX, NORM_TEX_SIZE) / 2;
    //deltaUvY = mod(texCoord.y + g_Time * m_TextureScrollSpeedY, NORM_TEX_SIZE) / 2;

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
         int SunIntensity = (int(inColor.r) >> 4) & 0xF;
         int TorchIntensity = (int(inColor.r)) & 0xF;
         float lightLevel = min(levels[SunIntensity], AmbientSum.r);
         lightLevel = max(lightLevel, levels[TorchIntensity]);
         AmbientSum = vec3(lightLevel, lightLevel, lightLevel);
         DiffuseSum *= vec4(AmbientSum.r, AmbientSum.g, AmbientSum.b, inColor.a);
    #endif

    #ifdef VERTEX_LIGHTING
         int i = 0;
         vec3 diffuseAccum  = vec3(0.0);
         vec3 specularAccum = vec3(0.0);
         vec4 diffuseColor;
         vec3 specularColor;
         vec4 lightColor = g_LightData[1];
         vec4 lightData1 = g_LightData[2];
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
              vec4 lightDirection = g_LightData[3];
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
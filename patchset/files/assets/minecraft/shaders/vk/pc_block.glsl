// Shared push-constant block — identical in every program so one VkPipelineLayout
// serves all pipelines (push constants survive pipeline binds). std430, 240 bytes.
// Keep offsets in sync with plutonium.gpu.vk.VkUniformBlock.
layout(push_constant) uniform PC {
    mat4 u_MVP;            // byte offset 0
    vec4 u_ColorModifier;  // 64
    vec4 u_OverlayColor;   // 80
    vec4 u_FogColor;       // 96
    vec4 u_Light0Dir;      // 112 (xyz used)
    vec4 u_Light1Dir;      // 128
    vec4 u_LightDiffuse;   // 144
    vec4 u_LightAmbient;   // 160
    vec2 u_LightmapCoord;  // 176
    vec2 u_TexelSize;      // 184 (blur)
    vec2 u_Direction;      // 192 (blur)
    float u_AlphaDiscard;  // 200
    int u_FogEnabled;      // 204
    int u_FogMode;         // 208
    float u_FogStart;      // 212
    float u_FogEnd;        // 216
    float u_FogDensity;    // 220
    int u_LightingEnabled; // 224
};

float computeFog(float dist) {
    if (u_FogMode == 2048) {
        return exp(-u_FogDensity * dist);
    } else if (u_FogMode == 2049) {
        return exp(-pow(u_FogDensity * dist, 2.0));
    } else {
        return clamp((u_FogEnd - dist) / (u_FogEnd - u_FogStart), 0.0, 1.0);
    }
}

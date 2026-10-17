#version 330 core

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 4) in vec3 Normal;

uniform mat4 u_MVP;
uniform mat4 u_TextureMatrix;
uniform int u_LightingEnabled;
uniform vec3 u_Light0Dir;
uniform vec3 u_Light1Dir;
uniform vec3 u_LightDiffuse;
uniform vec3 u_LightAmbient;

out vec4 v_Color;
out vec2 v_TexCoord;
out float v_FogDist;

void main() {
    gl_Position = u_MVP * vec4(Position, 1.0);
    v_FogDist = gl_Position.w;   // eye-space depth (perspective clip.w == -eye.z)
    // Sample the entity/item texture (unit 0) with raw UVs. The shared texture
    // matrix (mode 5890) is polluted by enableLightmap()'s lightmap scale and is
    // only meant for the lightmap unit, so applying it here collapses all UVs to
    // a single texel. Matches block.vsh, which also uses UV0 directly.
    v_TexCoord = UV0;

    if (u_LightingEnabled != 0) {
        vec3 norm = normalize(Normal);
        float diff0 = max(dot(norm, normalize(u_Light0Dir)), 0.0);
        float diff1 = max(dot(norm, normalize(u_Light1Dir)), 0.0);
        vec3 lighting = u_LightAmbient + u_LightDiffuse * (diff0 + diff1);
        v_Color = Color * vec4(min(lighting, vec3(1.0)), 1.0);
    } else {
        v_Color = Color;
    }
}

#version 450
#extension GL_GOOGLE_include_directive : require
#include "pc_block.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 4) in vec3 Normal;

layout(location = 0) out vec4 v_Color;
layout(location = 1) out vec2 v_TexCoord;
layout(location = 2) out float v_FogDist;

void main() {
    gl_Position = u_MVP * vec4(Position, 1.0);
    // GL clip z (-w..w) -> Vulkan (0..w), applied post-transform so depth ties
    // between baked-modelview batches and matrix-composed draws break exactly as GL.
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
    v_FogDist = gl_Position.w;   // eye-space depth (perspective clip.w == -eye.z)
    // Raw UVs; the GL texture matrix was lightmap-only pollution (see entity.vsh history).
    v_TexCoord = UV0;

    if (u_LightingEnabled != 0) {
        vec3 norm = normalize(Normal);
        float diff0 = max(dot(norm, normalize(u_Light0Dir.xyz)), 0.0);
        float diff1 = max(dot(norm, normalize(u_Light1Dir.xyz)), 0.0);
        vec3 lighting = u_LightAmbient.rgb + u_LightDiffuse.rgb * (diff0 + diff1);
        v_Color = Color * vec4(min(lighting, vec3(1.0)), 1.0);
    } else {
        v_Color = Color;
    }
}

#version 450
#extension GL_GOOGLE_include_directive : require
#include "pc_block.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;

layout(location = 0) out vec4 v_Color;
layout(location = 1) out vec2 v_TexCoord;
layout(location = 2) out vec2 v_LightCoord;
layout(location = 3) out float v_FogDist;

void main() {
    gl_Position = u_MVP * vec4(Position, 1.0);
    // GL clip z (-w..w) -> Vulkan (0..w), applied post-transform so depth ties
    // between baked-modelview batches and matrix-composed draws break exactly as GL.
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
    v_Color = Color;
    v_TexCoord = UV0;
    v_LightCoord = vec2(UV1) / 256.0;
    // Eye-space depth: for a perspective projection clip.w == -eye.z, i.e. the planar
    // view distance GL fixed-function fog used. (Ortho/GUI gives w==1, but fog is off there.)
    v_FogDist = gl_Position.w;
}

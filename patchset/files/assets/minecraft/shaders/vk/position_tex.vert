#version 450
#extension GL_GOOGLE_include_directive : require
#include "pc_block.glsl"

layout(location = 0) in vec3 Position;
layout(location = 2) in vec2 UV0;

layout(location = 0) out vec2 v_TexCoord;

void main() {
    gl_Position = u_MVP * vec4(Position, 1.0);
    // GL clip z (-w..w) -> Vulkan (0..w), applied post-transform so depth ties
    // between baked-modelview batches and matrix-composed draws break exactly as GL.
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
    v_TexCoord = UV0;
}

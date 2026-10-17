#version 450
#extension GL_GOOGLE_include_directive : require
#include "pc_block.glsl"

// Attribute-less fullscreen triangle: ids 0,1,2 -> (-1,-1),(3,-1),(-1,3).
// No vertex bindings at all, so the pipeline declares no vertex input.
layout(location = 0) out vec2 v_TexCoord;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    v_TexCoord = uv;
}

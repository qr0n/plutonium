#version 330 core

// Attribute-less fullscreen triangle: ids 0,1,2 -> (-1,-1),(3,-1),(-1,3)
out vec2 v_TexCoord;

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    v_TexCoord = uv;
}

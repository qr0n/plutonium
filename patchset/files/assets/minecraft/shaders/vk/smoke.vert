#version 450

layout(location = 0) out vec3 v_Color;

// GL-convention Y-up positions; the engine renders with a negative-height
// viewport so winding/cull semantics match the old GL pipeline.
vec2 POS[3] = vec2[](vec2(0.0, 0.6), vec2(0.6, -0.6), vec2(-0.6, -0.6));
vec3 COL[3] = vec3[](vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0));

void main() {
    gl_Position = vec4(POS[gl_VertexIndex], 0.0, 1.0);
    v_Color = COL[gl_VertexIndex];
}

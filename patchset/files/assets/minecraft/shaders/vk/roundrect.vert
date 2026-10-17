#version 450
#extension GL_GOOGLE_include_directive : require
#include "pc_block.glsl"

// Anti-aliased rounded rectangle / ring primitive (see RoundRectBatch). All-float
// stride-72 layout, attributes fed by VkDrawDispatch.drawRoundRect.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aLocal;
layout(location = 2) in vec2 aHalf;
layout(location = 3) in vec3 aParams; // radius, softness, borderWidth
layout(location = 4) in vec4 aFill;
layout(location = 5) in vec4 aBorder;

layout(location = 0) out vec2 v_Local;
layout(location = 1) out vec2 v_Half;
layout(location = 2) out vec3 v_Params;
layout(location = 3) out vec4 v_Fill;
layout(location = 4) out vec4 v_Border;

void main() {
    gl_Position = u_MVP * vec4(aPos, 1.0);
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
    v_Local  = aLocal;
    v_Half   = aHalf;
    v_Params = aParams;
    v_Fill   = aFill;
    v_Border = aBorder;
}

#version 330 core

// Anti-aliased rounded rectangle / ring primitive (see RoundRectBatch).
// Geometry is a quad expanded past the rect bounds so the AA + soft-shadow
// falloff has room. aPos already has the modelview baked in (drawn under the
// GUI projection only); aLocal is the GUI-space offset from the rect centre and
// drives the SDF independently, so scaling/translation never distorts the shape.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aLocal;
layout(location = 2) in vec2 aHalf;
layout(location = 3) in vec3 aParams; // radius, softness, borderWidth
layout(location = 4) in vec4 aFill;
layout(location = 5) in vec4 aBorder;

uniform mat4 u_MVP;

out vec2 v_Local;
out vec2 v_Half;
out vec3 v_Params;
out vec4 v_Fill;
out vec4 v_Border;

void main() {
    gl_Position = u_MVP * vec4(aPos, 1.0);
    v_Local  = aLocal;
    v_Half   = aHalf;
    v_Params = aParams;
    v_Fill   = aFill;
    v_Border = aBorder;
}

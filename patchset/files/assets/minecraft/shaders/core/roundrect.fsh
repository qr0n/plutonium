#version 330 core

in vec2 v_Local;
in vec2 v_Half;
in vec3 v_Params; // radius, softness, borderWidth
in vec4 v_Fill;
in vec4 v_Border;

out vec4 fragColor;

// Signed distance to a rounded box centred at the origin with half-extent b and
// corner radius r. Negative inside, zero on the edge, positive outside.
float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

void main() {
    float radius  = v_Params.x;
    float aa      = max(v_Params.y, 0.6); // edge softness (GUI px); doubles as shadow blur
    float borderW = v_Params.z;

    float d = sdRoundBox(v_Local, v_Half, radius);
    float outer = 1.0 - smoothstep(-aa, aa, d);

    vec4 col;
    if (borderW > 0.5) {
        // ring: outer edge minus an inner edge inset by borderW
        float inner = 1.0 - smoothstep(-aa, aa, d + borderW);
        col = v_Border;
        col.a *= (outer - inner);
    } else {
        col = v_Fill;
        col.a *= outer;
    }

    if (col.a <= 0.0) discard;
    fragColor = col;
}

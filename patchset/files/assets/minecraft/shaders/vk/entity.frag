#version 450
#extension GL_GOOGLE_include_directive : require
#include "pc_block.glsl"

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in float v_FogDist;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 0) uniform sampler2D Sampler0;
layout(set = 0, binding = 1) uniform sampler2D Sampler1;  // lightmap gradient

void main() {
    vec4 texColor = texture(Sampler0, v_TexCoord);
    vec4 color = texColor * v_Color * u_ColorModifier;
    if (color.a < u_AlphaDiscard) discard;
    // Environmental block+sky light at this entity's coord (raw 0..240, /256 like block UV1).
    color.rgb *= texture(Sampler1, u_LightmapCoord / 256.0).rgb;
    // Hurt flash / colour multiplier: blend toward the overlay tint (no-op when a == 0).
    color.rgb = mix(color.rgb, u_OverlayColor.rgb, u_OverlayColor.a);
    if (u_FogEnabled != 0) {
        float f = clamp(computeFog(v_FogDist), 0.0, 1.0);
        color.rgb = mix(u_FogColor.rgb, color.rgb, f);
    }
    fragColor = color;
}

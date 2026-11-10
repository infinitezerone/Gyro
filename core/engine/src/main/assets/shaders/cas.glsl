//!HOOK MAIN
//!BIND HOOKED
//!DESC AMD FidelityFX Contrast Adaptive Sharpening (CAS)

vec4 hook() {
    vec2 pos = HOOKED_pos;
    vec2 pt = HOOKED_pt;

    // Sample 3x3 cross neighborhood
    vec3 b = HOOKED_tex(pos + vec2(0.0, -pt.y)).rgb;
    vec3 d = HOOKED_tex(pos + vec2(-pt.x, 0.0)).rgb;
    vec3 e = HOOKED_tex(pos).rgb;
    vec3 f = HOOKED_tex(pos + vec2(pt.x, 0.0)).rgb;
    vec3 h = HOOKED_tex(pos + vec2(0.0, pt.y)).rgb;

    // Diagonal samples for diagonal ringing suppression
    vec3 a = HOOKED_tex(pos + vec2(-pt.x, -pt.y)).rgb;
    vec3 c = HOOKED_tex(pos + vec2(pt.x, -pt.y)).rgb;
    vec3 g = HOOKED_tex(pos + vec2(-pt.x, pt.y)).rgb;
    vec3 i = HOOKED_tex(pos + vec2(pt.x, pt.y)).rgb;

    // Soft min and max among direct cross neighbors
    vec3 min_rgb = min(min(min(d, e), min(f, b)), h);
    vec3 max_rgb = max(max(max(d, e), max(f, b)), h);

    // Include corners softly for robust boundary bounds
    min_rgb = min(min_rgb, min(min(a, c), min(g, i)));
    max_rgb = max(max_rgb, max(max(a, c), max(g, i)));

    // Smooth reciprocal to compute adaptive contrast weight
    vec3 rcp_max = 1.0 / (max_rgb + 0.0001);
    vec3 amp = clamp(min(min_rgb, 2.0 - max_rgb) * rcp_max, 0.0, 1.0);
    amp = sqrt(amp);

    // Optimized mobile sharpness factor
    float sharpness = 0.65;
    vec3 w = amp * (-sharpness);
    vec3 rcp_w = 1.0 / (1.0 + 4.0 * w);

    vec3 res = (b * w + d * w + f * w + h * w + e) * rcp_w;
    return vec4(clamp(res, 0.0, 1.0), HOOKED_tex(pos).a);
}

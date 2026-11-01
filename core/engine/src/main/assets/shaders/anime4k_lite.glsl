//!HOOK MAIN
//!BIND HOOKED
//!DESC Anime4K Lite Line Art & Edge Refinement

vec4 hook() {
    vec2 pos = HOOKED_pos;
    vec2 pt = HOOKED_pt;

    vec4 center = HOOKED_tex(pos);
    vec4 top    = HOOKED_tex(pos - vec2(0.0, pt.y));
    vec4 bottom = HOOKED_tex(pos + vec2(0.0, pt.y));
    vec4 left   = HOOKED_tex(pos - vec2(pt.x, 0.0));
    vec4 right  = HOOKED_tex(pos + vec2(pt.x, 0.0));

    // Spatial discrete 2D Laplacian high-pass operator
    vec4 laplacian = 4.0 * center - (top + bottom + left + right);
    vec4 sharpened = center + 0.35 * laplacian;

    // Neighbor clamp to completely prevent haloing and ringing artifacts
    vec4 min_val = min(min(top, bottom), min(left, right));
    vec4 max_val = max(max(top, bottom), max(left, right));

    return clamp(sharpened, min_val, max_val);
}

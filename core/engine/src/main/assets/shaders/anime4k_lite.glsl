//!HOOK MAIN
//!BIND HOOKED
//!DESC Anime4K Real-Time Anime Line Art Enhancement & Adaptive Sharpening

// Standard BT.709 Luma calculation
float getLuma(vec4 color) {
    return dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
}

vec4 hook() {
    vec2 pos = HOOKED_pos;
    vec2 pt = HOOKED_pt;

    // Sample 3x3 surrounding spatial neighborhood
    vec4 c  = HOOKED_tex(pos);
    vec4 tl = HOOKED_tex(pos + vec2(-pt.x, -pt.y));
    vec4 tc = HOOKED_tex(pos + vec2(0.0,   -pt.y));
    vec4 tr = HOOKED_tex(pos + vec2( pt.x, -pt.y));
    vec4 ml = HOOKED_tex(pos + vec2(-pt.x,  0.0));
    vec4 mr = HOOKED_tex(pos + vec2( pt.x,  0.0));
    vec4 bl = HOOKED_tex(pos + vec2(-pt.x,  pt.y));
    vec4 bc = HOOKED_tex(pos + vec2(0.0,    pt.y));
    vec4 br = HOOKED_tex(pos + vec2( pt.x,  pt.y));

    // Calculate luminance for edge detection and line darkening
    float lumaC  = getLuma(c);
    float lumaTL = getLuma(tl);
    float lumaTC = getLuma(tc);
    float lumaTR = getLuma(tr);
    float lumaML = getLuma(ml);
    float lumaMR = getLuma(mr);
    float lumaBL = getLuma(bl);
    float lumaBC = getLuma(bc);
    float lumaBR = getLuma(br);

    // 1. Sobel Gradient Magnitude (isolates anime line art from flat color fills)
    float gx = (lumaTR + 2.0 * lumaMR + lumaBR) - (lumaTL + 2.0 * lumaML + lumaBL);
    float gy = (lumaBL + 2.0 * lumaBC + lumaBR) - (lumaTL + 2.0 * lumaTC + lumaTR);
    float edgeStrength = sqrt(gx * gx + gy * gy);

    // Non-linear edge response curve: suppress flat background noise while sharpening outlines
    float lineWeight = smoothstep(0.03, 0.35, edgeStrength);

    // 2. 8-Neighbor High-Pass Contrast Operator
    vec4 neighborAvg = (tc + bc + ml + mr) * 0.175 + (tl + tr + bl + br) * 0.075;
    vec4 highPass = c - neighborAvg;

    // High dynamic sharpen gain (1.35x on sharp edges, 0.15x baseline)
    float sharpenGain = mix(0.15, 1.35, lineWeight);
    vec4 sharpened = c + highPass * sharpenGain;

    // 3. Anime Line Art Darkening (reconstructs faded, washed-out character outlines)
    float minNeighborLuma = min(min(min(lumaTC, lumaBC), min(lumaML, lumaMR)),
                                min(min(lumaTL, lumaTR), min(lumaBL, lumaBR)));
    if (minNeighborLuma < lumaC) {
        float darkenFactor = (1.0 - minNeighborLuma / (lumaC + 0.001)) * lineWeight * 0.50;
        sharpened.rgb = mix(sharpened.rgb, sharpened.rgb * (1.0 - darkenFactor), lineWeight);
    }

    // 4. Soft Boundary Limiter (prevents ringing halos without obliterating sharpened details)
    vec4 localMin = min(min(min(tl, tr), min(bl, br)), min(min(tc, bc), min(ml, mr)));
    vec4 localMax = max(max(max(tl, tr), max(bl, br)), max(max(tc, bc), max(ml, mr)));
    vec4 delta = abs(localMax - localMin) * 0.20;
    vec4 softMin = localMin - delta;
    vec4 softMax = localMax + delta;

    return clamp(sharpened, max(vec4(0.0), softMin), min(vec4(1.0), softMax));
}

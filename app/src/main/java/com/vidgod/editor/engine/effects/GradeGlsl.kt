package com.vidgod.editor.engine.effects

/** GLSL implementation of [com.vidgod.editor.engine.catalog.GradeParams]. */
object GradeGlsl {
    /**
     * `vec3 grade(vec3 c, vec4 p[7] packed as 7 arguments, vec2 uv, vec2 res, float seed)`.
     * Sharpening is done by the caller because it needs neighbouring texels.
     */
    const val FUNCTIONS = """
vec3 overlayBlend(vec3 base, vec3 blend) {
  return mix(2.0 * base * blend, 1.0 - 2.0 * (1.0 - base) * (1.0 - blend), step(0.5, base));
}
vec3 grade(vec3 c, vec4 p0, vec4 p1, vec4 p2, vec4 p3, vec4 p4, vec4 p5, vec4 p6, vec2 uv, vec2 res, float seed) {
  // exposure (stops) and brightness
  c = c * exp2(p0.x);
  c = c + p0.y * 0.2;
  // contrast around mid grey
  c = (c - 0.5) * max(0.0, 1.0 + p0.z) + 0.5;
  // highlights & shadows
  float l = luma(clamp(c, 0.0, 1.0));
  float sh = 1.0 - sstep(0.0, 0.55, l);
  float hi = sstep(0.45, 1.0, l);
  c += vec3(p2.y * 0.25 * sh);
  c += vec3(p2.x * 0.25 * hi);
  // temperature & tint
  c.r += p1.y * 0.10;
  c.b -= p1.y * 0.10;
  c.g += p1.z * 0.08;
  c.rb -= vec2(p1.z * 0.03);
  // saturation
  l = luma(c);
  c = mix(vec3(l), c, max(0.0, 1.0 + p0.w));
  // vibrance
  float mx = max(c.r, max(c.g, c.b));
  float mn = min(c.r, min(c.g, c.b));
  float sat = clamp(mx - mn, 0.0, 1.0);
  c = mix(vec3(luma(c)), c, 1.0 + p1.x * (1.0 - sat) * 1.5);
  // hue rotation
  if (abs(p1.w) > 0.001) {
    vec3 h = rgb2hsv(clamp(c, 0.0, 1.0));
    h.x = fract(h.x + p1.w * 0.5);
    c = hsv2rgb(h);
  }
  // teal & orange
  if (p6.w > 0.001) {
    float lt = luma(clamp(c, 0.0, 1.0));
    vec3 teal = vec3(0.0, 0.5, 0.55);
    vec3 orange = vec3(1.0, 0.6, 0.3);
    vec3 target = mix(teal, orange, sstep(0.2, 0.8, lt));
    c = mix(c, overlayBlend(clamp(c, 0.0, 1.0), target), p6.w * 0.5);
  }
  // split toning
  l = luma(clamp(c, 0.0, 1.0));
  c = mix(c, overlayBlend(clamp(c, 0.0, 1.0), p4.rgb), p4.a * (1.0 - sstep(0.0, 0.6, l)));
  c = mix(c, overlayBlend(clamp(c, 0.0, 1.0), p5.rgb), p5.a * sstep(0.4, 1.0, l));
  // per channel gamma
  c = pow(clamp(c, 0.0, 1.0), vec3(1.0) / max(p6.rgb, vec3(0.01)));
  // monochrome & sepia
  l = luma(c);
  c = mix(c, vec3(l), clamp(p3.z, 0.0, 1.0));
  vec3 sep = vec3(l * 1.07, l * 0.87, l * 0.68) + vec3(0.05, 0.02, 0.0);
  c = mix(c, sep, clamp(p3.w, 0.0, 1.0));
  // fade (lifted blacks)
  c = c * (1.0 - p2.z * 0.25) + vec3(p2.z * 0.12);
  // vignette
  vec2 d = (uv - 0.5) * vec2(res.x / max(res.y, 1.0), 1.0);
  float dist = length(d) / length(vec2(res.x / max(res.y, 1.0), 1.0) * 0.5);
  c *= 1.0 - clamp(p2.w, -1.0, 1.0) * sstep(0.35, 1.05, dist);
  // film grain
  if (p3.x > 0.001) {
    float n = hash12(uv * res + vec2(seed * 61.0, seed * 37.0)) - 0.5;
    c += vec3(n * p3.x * 0.18);
  }
  return clamp(c, 0.0, 1.0);
}
"""
}

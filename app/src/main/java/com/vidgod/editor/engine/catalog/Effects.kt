package com.vidgod.editor.engine.catalog

/**
 * A full-frame video effect. [glsl] defines `vec4 fx(vec2 uv)` and may use
 * `src(uv)`, `uTime` (seconds since the effect started), `uProgress` (0..1 over the effect),
 * `uIntensity` (0..1), `uSpeed` and `uRes` (output size in pixels).
 */
data class FxDef(val id: String, val name: String, val category: String, val glsl: String, val swatch: Long)

object Effects {
    const val HEADER = """
uniform sampler2D uTex;
uniform float uTime;
uniform float uProgress;
uniform float uIntensity;
uniform float uSpeed;
uniform vec2 uRes;
vec4 src(vec2 uv) { return texture2D(uTex, clamp(uv, 0.0, 1.0)); }
float aspect() { return uRes.x / max(uRes.y, 1.0); }
float noise2(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  float a = hash12(i);
  float b = hash12(i + vec2(1.0, 0.0));
  float c = hash12(i + vec2(0.0, 1.0));
  float d = hash12(i + vec2(1.0, 1.0));
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}
"""

    val all: List<FxDef> = listOf(
        // ---------- Basic ----------
        FxDef("shake", "Shake", "Basic", """
vec4 fx(vec2 uv) {
  float t = uTime * uSpeed;
  vec2 off = vec2(noise2(vec2(t * 18.0, 1.3)) - 0.5, noise2(vec2(3.7, t * 18.0)) - 0.5) * 0.06 * uIntensity;
  vec2 c = 0.5 + (uv - 0.5) * (1.0 - 0.06 * uIntensity) + off;
  return src(c);
}""", 0xFFFF7043),
        FxDef("zoom_pulse", "Heartbeat", "Basic", """
vec4 fx(vec2 uv) {
  float beat = pow(abs(sin(uTime * uSpeed * 3.14159 * 1.2)), 10.0);
  float s = 1.0 + 0.12 * beat * uIntensity;
  return src(0.5 + (uv - 0.5) / s);
}""", 0xFFE91E63),
        FxDef("zoom_in_slow", "Slow zoom", "Basic", """
vec4 fx(vec2 uv) {
  float s = 1.0 + 0.25 * uProgress * uIntensity;
  return src(0.5 + (uv - 0.5) / s);
}""", 0xFF7E57C2),
        FxDef("flash", "Flash", "Basic", """
vec4 fx(vec2 uv) {
  float f = pow(max(0.0, sin(uTime * uSpeed * 3.14159 * 2.0)), 6.0) * uIntensity;
  vec4 c = src(uv);
  return vec4(mix(c.rgb, vec3(1.0), f), c.a);
}""", 0xFFFFF59D),
        FxDef("strobe", "Strobe", "Basic", """
vec4 fx(vec2 uv) {
  float on = step(0.5, fract(uTime * uSpeed * 6.0));
  vec4 c = src(uv);
  return vec4(mix(c.rgb, c.rgb * 0.15, on * uIntensity), c.a);
}""", 0xFF9E9E9E),
        FxDef("blur", "Blur", "Basic", """
vec4 fx(vec2 uv) {
  vec2 r = 6.0 * uIntensity / uRes;
  vec4 acc = vec4(0.0);
  for (int x = -3; x <= 3; x++) {
    for (int y = -3; y <= 3; y++) {
      acc += src(uv + vec2(float(x), float(y)) * r);
    }
  }
  return acc / 49.0;
}""", 0xFF90CAF9),
        FxDef("zoom_blur", "Zoom blur", "Basic", """
vec4 fx(vec2 uv) {
  vec2 d = uv - 0.5;
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 16; i++) {
    float s = 1.0 - 0.15 * uIntensity * float(i) / 16.0;
    acc += src(0.5 + d * s);
  }
  return acc / 16.0;
}""", 0xFF64B5F6),
        FxDef("motion_blur", "Motion blur", "Basic", """
vec4 fx(vec2 uv) {
  vec4 acc = vec4(0.0);
  for (int i = -8; i <= 8; i++) {
    acc += src(uv + vec2(float(i) * 2.0 * uIntensity / uRes.x, 0.0));
  }
  return acc / 17.0;
}""", 0xFF4FC3F7),
        FxDef("vignette_pulse", "Spotlight", "Basic", """
vec4 fx(vec2 uv) {
  vec2 d = (uv - 0.5) * vec2(aspect(), 1.0);
  float r = 0.45 + 0.1 * sin(uTime * uSpeed * 3.0);
  float v = smoothstep(r, r - 0.35, length(d));
  vec4 c = src(uv);
  return vec4(c.rgb * mix(1.0, v, uIntensity), c.a);
}""", 0xFF5D4037),
        FxDef("invert", "Invert", "Basic", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  return vec4(mix(c.rgb, 1.0 - c.rgb, uIntensity), c.a);
}""", 0xFF00BCD4),
        FxDef("bw_flash", "B&W flash", "Basic", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  float on = step(0.5, fract(uTime * uSpeed * 2.0));
  return vec4(mix(c.rgb, vec3(luma(c.rgb)), on * uIntensity), c.a);
}""", 0xFF757575),
        FxDef("fade_black", "Fade to black", "Basic", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  return vec4(c.rgb * (1.0 - uProgress * uIntensity), c.a);
}""", 0xFF212121),
        FxDef("fade_white", "Fade to white", "Basic", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  return vec4(mix(c.rgb, vec3(1.0), uProgress * uIntensity), c.a);
}""", 0xFFFAFAFA),
        // ---------- Retro ----------
        FxDef("glitch", "Glitch", "Retro", """
vec4 fx(vec2 uv) {
  float t = floor(uTime * uSpeed * 15.0);
  float band = floor(uv.y * 30.0);
  float r = hash12(vec2(band, t));
  float shift = step(0.85, r) * (hash12(vec2(t, band + 7.0)) - 0.5) * 0.2 * uIntensity;
  vec2 u = vec2(uv.x + shift, uv.y);
  float split = 0.012 * uIntensity * (0.5 + 0.5 * sin(uTime * 13.0));
  vec4 c = src(u);
  c.r = src(u + vec2(split, 0.0)).r;
  c.b = src(u - vec2(split, 0.0)).b;
  return c;
}""", 0xFFE040FB),
        FxDef("vhs", "VHS", "Retro", """
vec4 fx(vec2 uv) {
  float t = uTime * uSpeed;
  float wobble = (noise2(vec2(uv.y * 8.0, t * 4.0)) - 0.5) * 0.008 * uIntensity;
  vec2 u = vec2(uv.x + wobble, uv.y);
  vec4 c = src(u);
  c.r = src(u + vec2(0.004 * uIntensity, 0.0)).r;
  c.b = src(u - vec2(0.004 * uIntensity, 0.0)).b;
  float scan = 0.92 + 0.08 * sin(uv.y * uRes.y * 1.5);
  float noiseLine = step(0.995, hash12(vec2(floor(uv.y * 200.0), floor(t * 20.0))));
  vec3 col = c.rgb * mix(1.0, scan, uIntensity) + noiseLine * 0.3 * uIntensity;
  col += (hash12(uv * uRes + t) - 0.5) * 0.08 * uIntensity;
  float tracking = smoothstep(0.02, 0.0, abs(fract(uv.y - t * 0.15) - 0.5)) * 0.25 * uIntensity;
  return vec4(col + tracking, c.a);
}""", 0xFF5C6BC0),
        FxDef("old_film", "Old film", "Retro", """
vec4 fx(vec2 uv) {
  float t = uTime * uSpeed;
  vec2 jitter = vec2(0.0, (hash12(vec2(floor(t * 12.0), 3.0)) - 0.5) * 0.004);
  vec4 c = src(uv + jitter);
  float l = luma(c.rgb);
  vec3 sep = vec3(l * 1.05, l * 0.9, l * 0.7);
  float flicker = 0.9 + 0.1 * hash12(vec2(floor(t * 24.0), 1.0));
  float scratch = step(0.997, hash12(vec2(floor(uv.x * 300.0), floor(t * 8.0)))) * 0.5;
  float grain = (hash12(uv * uRes + t * 100.0) - 0.5) * 0.15;
  vec2 d = (uv - 0.5) * vec2(aspect(), 1.0);
  float vig = smoothstep(0.9, 0.3, length(d));
  vec3 col = (sep * flicker + grain + scratch) * vig;
  return vec4(mix(c.rgb, col, uIntensity), c.a);
}""", 0xFFA1887F),
        FxDef("grain", "Film grain", "Retro", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  float g = hash12(uv * uRes + fract(uTime * 7.3) * 100.0) - 0.5;
  return vec4(c.rgb + g * 0.22 * uIntensity, c.a);
}""", 0xFFBDBDBD),
        FxDef("crt", "CRT", "Retro", """
vec4 fx(vec2 uv) {
  vec2 d = uv - 0.5;
  vec2 u = 0.5 + d * (1.0 + 0.12 * uIntensity * dot(d, d));
  if (u.x < 0.0 || u.x > 1.0 || u.y < 0.0 || u.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);
  vec4 c = src(u);
  float scan = 0.85 + 0.15 * sin(u.y * uRes.y * 3.14159);
  float mask = 0.9 + 0.1 * sin(u.x * uRes.x * 2.0);
  return vec4(c.rgb * mix(1.0, scan * mask, uIntensity) * 1.1, c.a);
}""", 0xFF26A69A),
        FxDef("rgb_split", "RGB split", "Retro", """
vec4 fx(vec2 uv) {
  float a = 0.015 * uIntensity * (0.6 + 0.4 * sin(uTime * uSpeed * 4.0));
  vec4 c = src(uv);
  return vec4(src(uv + vec2(a, 0.0)).r, c.g, src(uv - vec2(a, 0.0)).b, c.a);
}""", 0xFFEF5350),
        FxDef("chromatic", "Chromatic", "Retro", """
vec4 fx(vec2 uv) {
  vec2 d = uv - 0.5;
  float a = 0.03 * uIntensity;
  vec4 c = src(uv);
  return vec4(src(uv + d * a).r, c.g, src(uv - d * a).b, c.a);
}""", 0xFFAB47BC),
        // ---------- Dreamy ----------
        FxDef("glow", "Dreamy glow", "Dreamy", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  vec3 acc = vec3(0.0);
  for (int x = -2; x <= 2; x++) {
    for (int y = -2; y <= 2; y++) {
      vec3 s = src(uv + vec2(float(x), float(y)) * 6.0 / uRes).rgb;
      acc += max(s - 0.55, 0.0);
    }
  }
  acc /= 25.0;
  return vec4(c.rgb + acc * 2.2 * uIntensity, c.a);
}""", 0xFFF8BBD0),
        FxDef("light_leak", "Light leak", "Dreamy", """
vec4 fx(vec2 uv) {
  float t = uTime * uSpeed * 0.4;
  vec2 p1 = vec2(0.15 + 0.3 * sin(t), 0.8);
  vec2 p2 = vec2(0.9, 0.2 + 0.3 * cos(t * 1.3));
  float l1 = smoothstep(0.7, 0.0, distance(uv, p1));
  float l2 = smoothstep(0.6, 0.0, distance(uv, p2));
  vec3 leak = vec3(1.0, 0.45, 0.15) * l1 + vec3(1.0, 0.2, 0.5) * l2;
  vec4 c = src(uv);
  return vec4(1.0 - (1.0 - c.rgb) * (1.0 - leak * 0.8 * uIntensity), c.a);
}""", 0xFFFF8A65),
        FxDef("bokeh", "Bokeh", "Dreamy", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  vec3 acc = vec3(0.0);
  vec2 p = uv * vec2(aspect(), 1.0);
  for (int i = 0; i < 14; i++) {
    float fi = float(i);
    vec2 center = vec2(hash12(vec2(fi, 1.0)) * aspect(), fract(hash12(vec2(fi, 2.0)) + uTime * uSpeed * 0.03 * (0.5 + hash12(vec2(fi, 3.0)))));
    float r = 0.04 + 0.06 * hash12(vec2(fi, 4.0));
    float d = smoothstep(r, r * 0.8, distance(p, center));
    vec3 col = hsv2rgb(vec3(hash12(vec2(fi, 5.0)), 0.5, 1.0));
    acc += col * d * 0.35;
  }
  return vec4(c.rgb + acc * uIntensity, c.a);
}""", 0xFFFFD54F),
        FxDef("sparkle", "Sparkle", "Dreamy", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  vec2 g = uv * vec2(aspect(), 1.0) * 18.0;
  vec2 id = floor(g);
  vec2 f = fract(g) - 0.5;
  float rnd = hash12(id);
  float tw = pow(max(0.0, sin(uTime * uSpeed * 4.0 + rnd * 40.0)), 12.0);
  vec2 o = vec2(hash12(id + 1.7), hash12(id + 3.1)) - 0.5;
  vec2 q = f - o * 0.6;
  float star = max(0.0, 1.0 - abs(q.x * q.y) * 300.0) * smoothstep(0.35, 0.0, length(q));
  float s = star * tw * step(0.7, rnd);
  return vec4(c.rgb + s * 1.5 * uIntensity, c.a);
}""", 0xFFFFF176),
        FxDef("snow", "Snow", "Dreamy", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  float acc = 0.0;
  for (int layer = 0; layer < 3; layer++) {
    float fl = float(layer);
    float scale = 10.0 + fl * 8.0;
    vec2 p = uv * vec2(aspect(), 1.0) * scale;
    p.y += uTime * uSpeed * (1.5 + fl);
    p.x += sin(uTime * 0.7 + p.y * 0.3 + fl) * 0.5;
    vec2 id = floor(p);
    vec2 f = fract(p) - 0.5;
    float r = hash12(id + fl * 10.0);
    vec2 o = vec2(hash12(id + 5.0), hash12(id + 9.0)) - 0.5;
    float flake = smoothstep(0.12 - fl * 0.02, 0.0, length(f - o * 0.6)) * step(0.6, r);
    acc += flake;
  }
  return vec4(mix(c.rgb, vec3(1.0), clamp(acc, 0.0, 1.0) * uIntensity), c.a);
}""", 0xFFE3F2FD),
        FxDef("rain", "Rain", "Dreamy", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  vec2 p = uv * vec2(aspect() * 60.0, 6.0);
  p.y += uTime * uSpeed * 12.0;
  vec2 id = floor(p);
  float r = hash12(vec2(id.x, 0.0));
  float y = fract(p.y + r * 10.0);
  float drop = smoothstep(0.0, 0.2, y) * smoothstep(0.5, 0.2, y) * smoothstep(0.08, 0.0, abs(fract(p.x) - 0.5)) * step(0.7, r);
  return vec4(c.rgb * (1.0 - 0.15 * uIntensity) + drop * 0.5 * uIntensity, c.a);
}""", 0xFF78909C),
        FxDef("stars", "Starry", "Dreamy", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  vec2 g = uv * vec2(aspect(), 1.0) * 40.0;
  vec2 id = floor(g);
  float r = hash12(id);
  float tw = 0.5 + 0.5 * sin(uTime * uSpeed * 3.0 + r * 60.0);
  float d = smoothstep(0.08, 0.0, length(fract(g) - 0.5)) * step(0.92, r) * tw;
  return vec4(c.rgb + d * uIntensity, c.a);
}""", 0xFF283593),
        FxDef("rainbow", "Rainbow", "Dreamy", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  vec3 h = rgb2hsv(c.rgb);
  h.x = fract(h.x + uTime * uSpeed * 0.25);
  h.y = min(1.0, h.y + 0.25);
  return vec4(mix(c.rgb, hsv2rgb(h), uIntensity), c.a);
}""", 0xFFFF4081),
        FxDef("disco", "Disco", "Dreamy", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  vec2 p = (uv - 0.5) * vec2(aspect(), 1.0);
  float a = atan(p.y, p.x) + uTime * uSpeed;
  float beams = 0.5 + 0.5 * sin(a * 8.0);
  vec3 col = hsv2rgb(vec3(fract(a / 6.28318 + uTime * 0.2), 0.8, 1.0));
  return vec4(c.rgb + col * beams * 0.35 * uIntensity, c.a);
}""", 0xFFD500F9),
        // ---------- Lens ----------
        FxDef("fisheye", "Fisheye", "Lens", """
vec4 fx(vec2 uv) {
  vec2 d = (uv - 0.5) * 2.0;
  float r = length(d);
  float k = 0.45 * uIntensity;
  vec2 u = d * (1.0 - k + k * r * r) * 0.5 + 0.5;
  return src(u);
}""", 0xFF4DD0E1),
        FxDef("bulge", "Bulge", "Lens", """
vec4 fx(vec2 uv) {
  vec2 d = (uv - 0.5) * vec2(aspect(), 1.0);
  float r = length(d);
  float k = smoothstep(0.5, 0.0, r) * 0.5 * uIntensity * (0.8 + 0.2 * sin(uTime * uSpeed * 3.0));
  vec2 u = d * (1.0 - k) / vec2(aspect(), 1.0) + 0.5;
  return src(u);
}""", 0xFF29B6F6),
        FxDef("pinch", "Pinch", "Lens", """
vec4 fx(vec2 uv) {
  vec2 d = (uv - 0.5) * vec2(aspect(), 1.0);
  float r = length(d);
  float k = smoothstep(0.5, 0.0, r) * 0.6 * uIntensity;
  vec2 u = d * (1.0 + k) / vec2(aspect(), 1.0) + 0.5;
  return src(u);
}""", 0xFF0097A7),
        FxDef("swirl", "Swirl", "Lens", """
vec4 fx(vec2 uv) {
  vec2 d = (uv - 0.5) * vec2(aspect(), 1.0);
  float r = length(d);
  float a = (0.6 - r) * 4.0 * uIntensity * sin(uTime * uSpeed);
  a = r < 0.6 ? a : 0.0;
  float cs = cos(a), sn = sin(a);
  d = vec2(cs * d.x - sn * d.y, sn * d.x + cs * d.y);
  return src(d / vec2(aspect(), 1.0) + 0.5);
}""", 0xFF7C4DFF),
        FxDef("wave", "Wave", "Lens", """
vec4 fx(vec2 uv) {
  vec2 u = uv + vec2(sin(uv.y * 20.0 + uTime * uSpeed * 4.0), cos(uv.x * 20.0 + uTime * uSpeed * 3.0)) * 0.01 * uIntensity;
  return src(u);
}""", 0xFF26C6DA),
        FxDef("ripple", "Water", "Lens", """
vec4 fx(vec2 uv) {
  vec2 d = (uv - 0.5) * vec2(aspect(), 1.0);
  float r = length(d);
  vec2 u = uv + normalize(d + 0.0001) * sin(r * 50.0 - uTime * uSpeed * 8.0) * 0.006 * uIntensity;
  return src(u);
}""", 0xFF039BE5),
        FxDef("kaleidoscope", "Kaleidoscope", "Lens", """
vec4 fx(vec2 uv) {
  vec2 d = (uv - 0.5) * vec2(aspect(), 1.0);
  float a = atan(d.y, d.x) + uTime * uSpeed * 0.3;
  float r = length(d);
  float seg = 6.28318 / 6.0;
  a = mod(a, seg);
  a = abs(a - seg * 0.5);
  vec2 u = vec2(cos(a), sin(a)) * r / vec2(aspect(), 1.0) + 0.5;
  return mix(src(uv), src(u), uIntensity);
}""", 0xFFFFAB40),
        FxDef("mirror_h", "Mirror", "Lens", """
vec4 fx(vec2 uv) {
  vec2 u = vec2(uv.x < 0.5 ? uv.x : 1.0 - uv.x, uv.y);
  return src(u);
}""", 0xFF8D6E63),
        FxDef("mirror_v", "Mirror vertical", "Lens", """
vec4 fx(vec2 uv) {
  vec2 u = vec2(uv.x, uv.y > 0.5 ? uv.y : 1.0 - uv.y);
  return src(u);
}""", 0xFF6D4C41),
        FxDef("mirror_quad", "Mirror 4", "Lens", """
vec4 fx(vec2 uv) {
  vec2 u = abs(uv - 0.5) * 2.0;
  u = 1.0 - u;
  return src(0.5 + (u - 0.5) * 1.0);
}""", 0xFF4E342E),
        FxDef("split2", "Split 2", "Split", """
vec4 fx(vec2 uv) {
  vec2 u = vec2(uv.x, fract(uv.y * 2.0));
  u.y = 0.25 + u.y * 0.5;
  return src(u);
}""", 0xFF66BB6A),
        FxDef("split3", "Split 3", "Split", """
vec4 fx(vec2 uv) {
  vec2 u = vec2(uv.x, fract(uv.y * 3.0));
  u.y = 1.0 / 3.0 + u.y / 3.0;
  return src(u);
}""", 0xFF43A047),
        FxDef("split4", "Split 4", "Split", """
vec4 fx(vec2 uv) {
  return src(fract(uv * 2.0));
}""", 0xFF2E7D32),
        FxDef("split9", "Split 9", "Split", """
vec4 fx(vec2 uv) {
  return src(fract(uv * 3.0));
}""", 0xFF1B5E20),
        FxDef("pixelate", "Pixelate", "Lens", """
vec4 fx(vec2 uv) {
  vec2 cells = vec2(aspect(), 1.0) * mix(200.0, 18.0, uIntensity);
  return src((floor(uv * cells) + 0.5) / cells);
}""", 0xFF8BC34A),
        FxDef("tilt_shift", "Miniature", "Lens", """
vec4 fx(vec2 uv) {
  float b = smoothstep(0.1, 0.4, abs(uv.y - 0.5)) * 8.0 * uIntensity;
  vec4 acc = vec4(0.0);
  for (int x = -2; x <= 2; x++) {
    for (int y = -2; y <= 2; y++) {
      acc += src(uv + vec2(float(x), float(y)) * b / uRes);
    }
  }
  vec4 c = acc / 25.0;
  vec3 h = rgb2hsv(c.rgb);
  h.y = min(1.0, h.y * 1.3);
  return vec4(hsv2rgb(h), c.a);
}""", 0xFF9CCC65),
        // ---------- Art ----------
        FxDef("neon", "Neon edges", "Art", """
vec4 fx(vec2 uv) {
  vec2 t = 1.0 / uRes;
  float tl = luma(src(uv + vec2(-t.x, t.y)).rgb), tr = luma(src(uv + vec2(t.x, t.y)).rgb);
  float bl = luma(src(uv + vec2(-t.x, -t.y)).rgb), br = luma(src(uv + vec2(t.x, -t.y)).rgb);
  float gx = (tr + br) - (tl + bl);
  float gy = (tl + tr) - (bl + br);
  float e = clamp(length(vec2(gx, gy)) * 4.0, 0.0, 1.0);
  vec3 col = hsv2rgb(vec3(fract(uv.x * 0.5 + uv.y * 0.5 + uTime * uSpeed * 0.2), 0.9, 1.0)) * e;
  vec4 c = src(uv);
  return vec4(mix(c.rgb, col + c.rgb * 0.15, uIntensity), c.a);
}""", 0xFF00E5FF),
        FxDef("sketch", "Sketch", "Art", """
vec4 fx(vec2 uv) {
  vec2 t = 1.5 / uRes;
  float c0 = luma(src(uv).rgb);
  float dx = luma(src(uv + vec2(t.x, 0.0)).rgb) - luma(src(uv - vec2(t.x, 0.0)).rgb);
  float dy = luma(src(uv + vec2(0.0, t.y)).rgb) - luma(src(uv - vec2(0.0, t.y)).rgb);
  float e = 1.0 - clamp(length(vec2(dx, dy)) * 5.0, 0.0, 1.0);
  vec3 paper = vec3(0.97, 0.95, 0.9) * e;
  vec4 c = src(uv);
  return vec4(mix(c.rgb, paper, uIntensity), c.a);
}""", 0xFFEEEEEE),
        FxDef("cartoon", "Cartoon", "Art", """
vec4 fx(vec2 uv) {
  vec2 t = 1.0 / uRes;
  vec4 c = src(uv);
  vec3 q = floor(c.rgb * 6.0 + 0.5) / 6.0;
  float dx = luma(src(uv + vec2(t.x, 0.0)).rgb) - luma(src(uv - vec2(t.x, 0.0)).rgb);
  float dy = luma(src(uv + vec2(0.0, t.y)).rgb) - luma(src(uv - vec2(0.0, t.y)).rgb);
  float e = step(0.12, length(vec2(dx, dy)));
  vec3 col = q * (1.0 - e);
  return vec4(mix(c.rgb, col, uIntensity), c.a);
}""", 0xFFFFCA28),
        FxDef("halftone", "Halftone", "Art", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  float s = 90.0;
  vec2 p = uv * vec2(aspect(), 1.0) * s;
  vec2 cell = fract(p) - 0.5;
  float l = luma(src((floor(p) + 0.5) / (vec2(aspect(), 1.0) * s)).rgb);
  float dotv = smoothstep(0.5 * (1.0 - l) + 0.05, 0.5 * (1.0 - l), length(cell));
  vec3 col = vec3(1.0 - dotv);
  return vec4(mix(c.rgb, col, uIntensity), c.a);
}""", 0xFF616161),
        FxDef("posterize", "Posterize", "Art", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  float levels = mix(12.0, 3.0, uIntensity);
  return vec4(floor(c.rgb * levels + 0.5) / levels, c.a);
}""", 0xFFFF7043),
        FxDef("thermal", "Thermal", "Art", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  float l = luma(c.rgb);
  vec3 col = l < 0.5 ? mix(vec3(0.0, 0.0, 0.6), vec3(1.0, 0.0, 0.3), l * 2.0) : mix(vec3(1.0, 0.0, 0.3), vec3(1.0, 1.0, 0.2), (l - 0.5) * 2.0);
  return vec4(mix(c.rgb, col, uIntensity), c.a);
}""", 0xFFFF1744),
        FxDef("night_vision", "Night vision", "Art", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  float l = luma(c.rgb) * 1.6;
  float n = hash12(uv * uRes + uTime * 50.0) * 0.15;
  vec2 d = (uv - 0.5) * vec2(aspect(), 1.0);
  float vig = smoothstep(0.8, 0.3, length(d));
  vec3 col = vec3(0.1, 1.0, 0.2) * (l + n) * vig * (0.9 + 0.1 * sin(uv.y * uRes.y));
  return vec4(mix(c.rgb, col, uIntensity), c.a);
}""", 0xFF00C853),
        FxDef("emboss", "Emboss", "Art", """
vec4 fx(vec2 uv) {
  vec2 t = 1.5 / uRes;
  vec3 a = src(uv - t).rgb;
  vec3 b = src(uv + t).rgb;
  float e = luma(b - a) * 2.0 + 0.5;
  vec4 c = src(uv);
  return vec4(mix(c.rgb, vec3(e), uIntensity), c.a);
}""", 0xFF9E9E9E),
        FxDef("letterbox", "Cinema bars", "Frame", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  float bar = 0.12 * uIntensity * smoothstep(0.0, 0.15, uProgress) ;
  float m = step(bar, uv.y) * step(uv.y, 1.0 - bar);
  return vec4(c.rgb * m, c.a);
}""", 0xFF000000),
        FxDef("blur_edges", "Soft frame", "Frame", """
vec4 fx(vec2 uv) {
  vec4 c = src(uv);
  vec2 d = abs(uv - 0.5) * 2.0;
  float m = smoothstep(1.0, 0.75, max(d.x, d.y));
  vec4 acc = vec4(0.0);
  for (int x = -2; x <= 2; x++) {
    for (int y = -2; y <= 2; y++) {
      acc += src(uv + vec2(float(x), float(y)) * 8.0 / uRes);
    }
  }
  acc /= 25.0;
  return mix(c, mix(acc, c, m), uIntensity);
}""", 0xFFB0BEC5),
    )

    private val byId = all.associateBy { it.id }
    fun get(id: String?) = id?.let { byId[it] }
    val categories = all.map { it.category }.distinct()
}

package com.vidgod.editor.engine.catalog

/** A transition between two main-track clips, implemented as a GLSL `transition(uv)` function. */
data class TransitionDef(val id: String, val name: String, val category: String, val glsl: String)

object Transitions {
    const val HEADER = """
uniform sampler2D uFrom;
uniform sampler2D uTo;
uniform float progress;
uniform float ratio;
vec4 getFromColor(vec2 uv) { return texture2D(uFrom, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTo, uv); }
bool inside01(vec2 uv) { return uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0; }
"""

    private fun push(dx: String, dy: String) = """
vec4 transition(vec2 uv) {
  vec2 dir = vec2($dx, $dy);
  float p = sstep(0.0, 1.0, progress);
  vec2 fromUv = uv + dir * p;
  vec2 toUv = uv + dir * (p - 1.0);
  if (inside01(toUv)) return getToColor(toUv);
  return getFromColor(fromUv);
}"""

    private fun slide(dx: String, dy: String) = """
vec4 transition(vec2 uv) {
  vec2 dir = vec2($dx, $dy);
  float p = sstep(0.0, 1.0, progress);
  vec2 toUv = uv + dir * (p - 1.0);
  if (inside01(toUv)) return getToColor(toUv);
  return getFromColor(uv) * (1.0 - 0.4 * p);
}"""

    private fun wipe(expr: String) = """
vec4 transition(vec2 uv) {
  float edge = 0.08;
  float x = $expr;
  float m = sstep(progress * (1.0 + edge) - edge, progress * (1.0 + edge), x);
  return mix(getToColor(uv), getFromColor(uv), m);
}"""

    val all: List<TransitionDef> = listOf(
        TransitionDef("dissolve", "Dissolve", "Basic", """
vec4 transition(vec2 uv) { return mix(getFromColor(uv), getToColor(uv), progress); }"""),
        TransitionDef("dip_black", "Black fade", "Basic", """
vec4 transition(vec2 uv) {
  if (progress < 0.5) return mix(getFromColor(uv), vec4(0.0, 0.0, 0.0, 1.0), progress * 2.0);
  return mix(vec4(0.0, 0.0, 0.0, 1.0), getToColor(uv), (progress - 0.5) * 2.0);
}"""),
        TransitionDef("dip_white", "White flash", "Basic", """
vec4 transition(vec2 uv) {
  if (progress < 0.5) return mix(getFromColor(uv), vec4(1.0), progress * 2.0);
  return mix(vec4(1.0), getToColor(uv), (progress - 0.5) * 2.0);
}"""),
        TransitionDef("push_left", "Push left", "Slide", push("1.0", "0.0")),
        TransitionDef("push_right", "Push right", "Slide", push("-1.0", "0.0")),
        TransitionDef("push_up", "Push up", "Slide", push("0.0", "-1.0")),
        TransitionDef("push_down", "Push down", "Slide", push("0.0", "1.0")),
        TransitionDef("slide_left", "Slide left", "Slide", slide("1.0", "0.0")),
        TransitionDef("slide_right", "Slide right", "Slide", slide("-1.0", "0.0")),
        TransitionDef("slide_up", "Slide up", "Slide", slide("0.0", "-1.0")),
        TransitionDef("wipe_right", "Wipe right", "Wipe", wipe("1.0 - uv.x")),
        TransitionDef("wipe_left", "Wipe left", "Wipe", wipe("uv.x")),
        TransitionDef("wipe_up", "Wipe up", "Wipe", wipe("1.0 - uv.y")),
        TransitionDef("wipe_down", "Wipe down", "Wipe", wipe("uv.y")),
        TransitionDef("circle_open", "Circle open", "Shape", """
vec4 transition(vec2 uv) {
  vec2 d = (uv - 0.5) * vec2(ratio, 1.0);
  float r = progress * length(vec2(ratio, 1.0)) * 0.55;
  float m = 1.0 - sstep(r - 0.02, r + 0.02, length(d));
  return mix(getFromColor(uv), getToColor(uv), m);
}"""),
        TransitionDef("circle_close", "Circle close", "Shape", """
vec4 transition(vec2 uv) {
  vec2 d = (uv - 0.5) * vec2(ratio, 1.0);
  float r = (1.0 - progress) * length(vec2(ratio, 1.0)) * 0.55;
  float m = 1.0 - sstep(r - 0.02, r + 0.02, length(d));
  return mix(getToColor(uv), getFromColor(uv), m);
}"""),
        TransitionDef("diamond", "Diamond", "Shape", """
vec4 transition(vec2 uv) {
  vec2 d = abs(uv - 0.5) * vec2(ratio, 1.0);
  float r = progress * (ratio + 1.0) * 0.55;
  float m = 1.0 - sstep(r - 0.02, r + 0.02, d.x + d.y);
  return mix(getFromColor(uv), getToColor(uv), m);
}"""),
        TransitionDef("heart", "Heart", "Shape", """
float heartSdf(vec2 p) {
  p.y = -p.y + 0.15;
  p.x = abs(p.x);
  float a = atan(p.x, p.y) / 3.14159;
  float r = length(p);
  float h = abs(a);
  float d = (13.0 * h - 22.0 * h * h + 10.0 * h * h * h) / (6.0 - 5.0 * h);
  return r - d * 0.35;
}
vec4 transition(vec2 uv) {
  vec2 p = (uv - 0.5) * vec2(ratio, 1.0) / max(progress * 2.2, 0.0001);
  float m = 1.0 - sstep(-0.01, 0.01, heartSdf(p));
  if (progress >= 0.999) m = 1.0;
  return mix(getFromColor(uv), getToColor(uv), m);
}"""),
        TransitionDef("radial", "Clock", "Shape", """
vec4 transition(vec2 uv) {
  vec2 d = uv - 0.5;
  float a = atan(d.x, d.y) / 6.28318 + 0.5;
  float m = sstep(progress - 0.02, progress + 0.02, a);
  return mix(getToColor(uv), getFromColor(uv), m);
}"""),
        TransitionDef("zoom_in", "Zoom in", "Camera", """
vec4 transition(vec2 uv) {
  float p = sstep(0.0, 1.0, progress);
  vec2 fromUv = 0.5 + (uv - 0.5) / (1.0 + p * 1.5);
  vec2 toUv = 0.5 + (uv - 0.5) * (1.6 - 0.6 * p);
  vec4 to = inside01(toUv) ? getToColor(toUv) : vec4(0.0, 0.0, 0.0, 1.0);
  return mix(getFromColor(fromUv), to, sstep(0.35, 0.75, progress));
}"""),
        TransitionDef("zoom_out", "Zoom out", "Camera", """
vec4 transition(vec2 uv) {
  float p = sstep(0.0, 1.0, progress);
  vec2 fromUv = 0.5 + (uv - 0.5) * (1.0 + p * 1.2);
  vec4 from = inside01(fromUv) ? getFromColor(fromUv) : vec4(0.0, 0.0, 0.0, 1.0);
  vec2 toUv = 0.5 + (uv - 0.5) / (1.6 - 0.6 * p);
  return mix(from, getToColor(toUv), sstep(0.35, 0.75, progress));
}"""),
        TransitionDef("cross_zoom", "Zoom blur", "Camera", """
vec4 transition(vec2 uv) {
  float strength = sin(progress * 3.14159) * 0.4;
  vec2 dir = uv - 0.5;
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 12; i++) {
    float s = 1.0 - strength * float(i) / 12.0;
    vec2 suv = 0.5 + dir * s;
    acc += mix(getFromColor(suv), getToColor(suv), sstep(0.4, 0.6, progress));
  }
  return acc / 12.0;
}"""),
        TransitionDef("spin", "Spin", "Camera", """
vec4 transition(vec2 uv) {
  float p = progress;
  float ang = p * 6.28318;
  float s = 1.0 + sin(p * 3.14159) * 1.5;
  vec2 d = (uv - 0.5) * vec2(ratio, 1.0);
  float c = cos(ang), sn = sin(ang);
  d = vec2(c * d.x - sn * d.y, sn * d.x + c * d.y) * s;
  vec2 ruv = d / vec2(ratio, 1.0) + 0.5;
  vec4 col = p < 0.5 ? getFromColor(ruv) : getToColor(ruv);
  return inside01(ruv) ? col : vec4(0.0, 0.0, 0.0, 1.0);
}"""),
        TransitionDef("shake", "Shake", "Camera", """
vec4 transition(vec2 uv) {
  float amp = sin(progress * 3.14159) * 0.05;
  vec2 off = vec2(sin(progress * 90.0), cos(progress * 77.0)) * amp;
  vec2 suv = clamp(uv + off, 0.0, 1.0);
  return mix(getFromColor(suv), getToColor(suv), sstep(0.3, 0.7, progress));
}"""),
        TransitionDef("glitch", "Glitch", "Effect", """
float rnd(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
vec4 transition(vec2 uv) {
  float amt = sin(progress * 3.14159);
  float block = floor(uv.y * 24.0);
  float shift = (rnd(vec2(block, floor(progress * 20.0))) - 0.5) * 0.25 * amt;
  vec2 u = vec2(fract(uv.x + shift), uv.y);
  vec4 a = progress < 0.5 ? getFromColor(u) : getToColor(u);
  vec2 off = vec2(0.02 * amt, 0.0);
  float r = (progress < 0.5 ? getFromColor(u + off) : getToColor(u + off)).r;
  float b = (progress < 0.5 ? getFromColor(u - off) : getToColor(u - off)).b;
  return vec4(r, a.g, b, 1.0);
}"""),
        TransitionDef("pixelate", "Pixelate", "Effect", """
vec4 transition(vec2 uv) {
  float d = min(progress, 1.0 - progress);
  float steps = max(1.0, floor(d * 2.0 * 60.0));
  vec2 cells = vec2(ratio, 1.0) * (120.0 / steps);
  vec2 puv = steps > 1.0 ? (floor(uv * cells) + 0.5) / cells : uv;
  return mix(getFromColor(puv), getToColor(puv), step(0.5, progress));
}"""),
        TransitionDef("blur", "Blur", "Effect", """
vec4 transition(vec2 uv) {
  float r = sin(progress * 3.14159) * 0.02;
  vec4 acc = vec4(0.0);
  for (int x = -3; x <= 3; x++) {
    for (int y = -3; y <= 3; y++) {
      vec2 o = vec2(float(x), float(y)) * r / 3.0;
      acc += mix(getFromColor(uv + o), getToColor(uv + o), progress);
    }
  }
  return acc / 49.0;
}"""),
        TransitionDef("swirl", "Swirl", "Effect", """
vec4 transition(vec2 uv) {
  float amt = sin(progress * 3.14159) * 6.0;
  vec2 d = (uv - 0.5) * vec2(ratio, 1.0);
  float r = length(d);
  float a = amt * max(0.0, 0.7 - r);
  float c = cos(a), s = sin(a);
  d = vec2(c * d.x - s * d.y, s * d.x + c * d.y);
  vec2 suv = d / vec2(ratio, 1.0) + 0.5;
  return mix(getFromColor(suv), getToColor(suv), sstep(0.35, 0.65, progress));
}"""),
        TransitionDef("ripple", "Ripple", "Effect", """
vec4 transition(vec2 uv) {
  vec2 d = uv - 0.5;
  float r = length(d * vec2(ratio, 1.0));
  float amp = sin(progress * 3.14159) * 0.03;
  vec2 off = normalize(d + 0.0001) * sin(r * 60.0 - progress * 30.0) * amp;
  return mix(getFromColor(uv + off), getToColor(uv + off), progress);
}"""),
        TransitionDef("squeeze", "Squeeze", "Effect", """
vec4 transition(vec2 uv) {
  if (progress < 0.5) {
    float s = 1.0 - progress * 2.0;
    vec2 fuv = vec2((uv.x - 0.5) / max(s, 0.001) + 0.5, uv.y);
    return inside01(fuv) ? getFromColor(fuv) : vec4(0.0, 0.0, 0.0, 1.0);
  }
  float s = (progress - 0.5) * 2.0;
  vec2 tuv = vec2((uv.x - 0.5) / max(s, 0.001) + 0.5, uv.y);
  return inside01(tuv) ? getToColor(tuv) : vec4(0.0, 0.0, 0.0, 1.0);
}"""),
        TransitionDef("doorway", "Doorway", "Effect", """
vec4 transition(vec2 uv) {
  float p = sstep(0.0, 1.0, progress);
  float hw = 0.5 * p;
  if (uv.x < 0.5 - hw) return getFromColor(vec2(uv.x + hw, uv.y));
  if (uv.x > 0.5 + hw) return getFromColor(vec2(uv.x - hw, uv.y));
  vec2 tuv = 0.5 + (uv - 0.5) / (0.6 + 0.4 * p);
  return inside01(tuv) ? getToColor(tuv) : vec4(0.0, 0.0, 0.0, 1.0);
}"""),
        TransitionDef("burn", "Film burn", "Effect", """
vec4 transition(vec2 uv) {
  float n = fract(sin(dot(floor(uv * 9.0), vec2(12.9898, 78.233))) * 43758.5453);
  float glow = sin(progress * 3.14159);
  vec4 c = mix(getFromColor(uv), getToColor(uv), sstep(0.3, 0.7, progress + (n - 0.5) * 0.3));
  return c + vec4(1.0, 0.55, 0.2, 0.0) * glow * (0.6 + 0.4 * uv.x);
}"""),
    )

    private val byId = all.associateBy { it.id }
    fun get(id: String?) = id?.let { byId[it] }
    val categories = all.map { it.category }.distinct()
}

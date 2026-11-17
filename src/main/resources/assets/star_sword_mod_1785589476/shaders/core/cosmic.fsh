#version 150

// 星辉剑的"宇宙"着色器。
//
// 思路来自 Avaritia 的 cosmic.frag：物品贴图只当遮罩，真正画的是天球——
// 把片元对应的视线方向按玩家 yaw / pitch 旋转，再按方向采样；
// 于是转动视角时看到的是"同一个天球的不同部分"，也就是从剑身中心往外看出去。
//
// 与 Avaritia 的差别：
//   1. 那边用 10 张宇宙贴图拼图集，这里全部程序化生成（原版顶点格式只给一个 Sampler0，
//      正好拿来做遮罩）；
//   2. 主视觉是**星云**（大面积、有颜色的气团），星点只是点缀——
//      16 像素的物品上，细星点根本立不住，必须靠大块色团撑住画面。

uniform sampler2D Sampler0;

uniform float Yaw;
uniform float Pitch;
uniform float ExternalScale;
uniform float Opacity;

in vec3 vPosition;
in vec2 vUv;
in vec4 vColor;

out vec4 fragColor;

const float PI = 3.14159265358979;

// ── 基础数学 ────────────────────────────────────────────────────────────
mat3 rotAxis(vec3 axis, float angle) {
    axis = normalize(axis);
    float s = sin(angle);
    float c = cos(angle);
    float oc = 1.0 - c;
    return mat3(
        oc * axis.x * axis.x + c,          oc * axis.x * axis.y - axis.z * s, oc * axis.z * axis.x + axis.y * s,
        oc * axis.x * axis.y + axis.z * s, oc * axis.y * axis.y + c,          oc * axis.y * axis.z - axis.x * s,
        oc * axis.z * axis.x - axis.y * s, oc * axis.y * axis.z + axis.x * s, oc * axis.z * axis.z + c
    );
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

// 三维值噪声：星云必须是"团"，所以得用真正有空间连续性的噪声，
// 单纯叠 sin 会得到规则条纹。
float noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash13(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
    return mix(
        mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
        mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y),
        f.z);
}

// 四层 fbm：星云的大尺度形状
float fbm(vec3 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * noise3(p);
        p *= 2.03;
        amp *= 0.5;
    }
    return sum;
}

void main() {
    vec4 mask = texture(Sampler0, vUv);

    // 视线：从剑身中心射向片元，取反方向当"看出去"的方向
    vec3 dir = normalize(-vPosition);

    // 先按俯仰、再按偏航旋转 —— 与 Avaritia 一致的两个旋转
    float sb = sin(Pitch);
    float cb = cos(Pitch);
    dir = normalize(vec3(dir.x, dir.y * cb - dir.z * sb, dir.y * sb + dir.z * cb));

    float sa = sin(-Yaw);
    float ca = cos(-Yaw);
    dir = normalize(vec3(dir.z * sa + dir.x * ca, dir.y, dir.z * ca - dir.x * sa));

    // ── 星云：主视觉 ────────────────────────────────────────────────────
    // 域扭曲（domain warp）：先拿一层低频噪声去挪采样坐标，
    // 出来的才是"翻卷的气体"，否则 fbm 只会是均匀的棉絮。
    vec3 q = dir * 2.1 * ExternalScale;
    float warp = noise3(q * 1.6 + 11.3);
    float neb = fbm(q + warp * 0.85);
    float neb2 = fbm(q * 2.7 - warp * 0.45 + 5.1);

    // 底色：深空蓝黑
    vec3 col = vec3(0.015, 0.020, 0.045);

    // 青色主气团
    col = mix(col, vec3(0.03, 0.24, 0.32), smoothstep(0.42, 0.82, neb));
    // 暖褐色尘埃：原图里那层棕色雾状物
    col = mix(col, vec3(0.36, 0.20, 0.12), smoothstep(0.48, 0.86, neb2) * 0.80);
    // 亮丝：星云边缘的高光
    col += vec3(0.10, 0.34, 0.40) * pow(smoothstep(0.60, 0.95, neb), 2.0);
    col += vec3(0.16, 0.10, 0.06) * pow(smoothstep(0.70, 1.00, neb2), 2.0);

    // ── 星点：点缀，不再抢戏 ────────────────────────────────────────────
    // 密度放低（24），半径放大（0.45），数量少但每颗都看得见。
    for (int i = 0; i < 6; i++) {
        float fi = float(i);
        vec3 axis = normalize(vec3(
            sin(fi * 12.9898 + 1.3),
            cos(fi * 78.233 + 2.7),
            sin(fi * 37.719 + 0.9)));
        vec3 ray = rotAxis(axis, fi * 2.39996) * dir;

        float u = 0.5 + atan(ray.z, ray.x) / (2.0 * PI);
        float v = 0.5 + asin(clamp(ray.y, -1.0, 1.0)) / PI;

        float scale = fi * 0.7 + 3.0;
        vec2 uv = vec2(u, v) * scale * ExternalScale;
        vec2 g = uv * 24.0;
        vec2 cell = floor(g);
        float h = hash21(cell + vec2(fi * 17.0, fi * 5.0));
        if (h > 0.992) {
            vec2 f = fract(g) - 0.5;
            float d = length(f);
            float star = smoothstep(0.45, 0.0, d);
            star *= star;
            col += vec3(0.88, 0.95, 1.0) * star * (0.45 + 0.55 * h);
        }
    }

    // 光照：沿用物品原本的亮度（手里变亮、暗处变暗），不做纯自发光
    col *= mix(vec3(1.0), vColor.rgb * 1.35, 0.55);

    // ── 贴图 RGB：哪些像素保留原色 ──────────────────────────────────────
    // 被涂成纯黑的格子 = 星空/星云，其余 = 原图颜色。
    // 用 step 而不是 smoothstep，避免中间调也被掺进来（那样整把剑都会发花）。
    float lum = dot(mask.rgb, vec3(0.299, 0.587, 0.114));
    float keep = step(0.02, lum);
    col = mix(col, mask.rgb * (0.55 + 0.55 * max(vColor.r, 0.35)), keep);

    // ── 形状：遮罩决定 ──────────────────────────────────────────────────
    // 用 discard 而不是只靠混合：半透明下 alpha=0 仍会写深度，
    // 而且一旦 alpha 在某个环节丢失，整块 quad 就会变成一个方块。
    float a = mask.a * Opacity;
    if (a < 0.5) {
        discard;
    }
    fragColor = vec4(clamp(col, 0.0, 1.0), a);
}

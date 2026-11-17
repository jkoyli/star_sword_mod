#version 150

// 星辉剑的"宇宙"着色器。
//
// 思路直接来自 Avaritia 的 cosmic.frag：物品贴图只当 alpha 遮罩，
// 真正画出来的是**球面星空**——把片元对应的视线方向按玩家的 yaw / pitch 旋转，
// 再按球面坐标（atan/asin）采样；叠十几层各自随机旋转的星空，
// 于是转动视角时看到的是"同一个天球的不同部分"，也就是从剑身中心往外看出去的效果。
//
// 与 Avaritia 的差别：那边用 10 张宇宙贴图拼图集，这里用哈希直接算星点，
// 省掉第二个采样器——原版顶点格式只给一个 Sampler0（正好拿来做遮罩）。

uniform sampler2D Sampler0;

uniform float Yaw;
uniform float Pitch;
uniform float ExternalScale;
uniform float Opacity;

// 星点密度：越大星点越细。剑在屏幕上一个像素太小，20 会显得"放大过度"；
// 48 是一档，96 是现在的值。想更细就继续往上调（128 / 160），
// 但注意 16 像素的物品上过密会出现闪烁（摩尔纹）。
const float STAR_DENSITY = 96.0;

in vec3 vPosition;
in vec2 vUv;
in vec4 vColor;

out vec4 fragColor;

const float PI = 3.14159265358979;

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

void main() {
    vec4 mask = texture(Sampler0, vUv);

    // 视线：从剑身中心（模型原点附近）射向片元，取反方向当"看出去"的方向
    vec3 dir = normalize(-vPosition);

    // 先按俯仰、再按偏航旋转 —— 与 Avaritia 完全一致的两个旋转
    float sb = sin(Pitch);
    float cb = cos(Pitch);
    dir = normalize(vec3(dir.x, dir.y * cb - dir.z * sb, dir.y * sb + dir.z * cb));

    float sa = sin(-Yaw);
    float ca = cos(-Yaw);
    dir = normalize(vec3(dir.z * sa + dir.x * ca, dir.y, dir.z * ca - dir.x * sa));

    // 深空底色：一点点蓝，避免纯黑
    vec3 col = vec3(0.012, 0.020, 0.038);

    // 12 层星空，每层随机旋转 + 不同尺度，叠出深度
    for (int i = 0; i < 12; i++) {
        float fi = float(i);

        vec3 axis = normalize(vec3(
            sin(fi * 12.9898 + 1.3),
            cos(fi * 78.233 + 2.7),
            sin(fi * 37.719 + 0.9)));
        vec3 ray = rotAxis(axis, fi * 2.39996) * dir;

        float u = 0.5 + atan(ray.z, ray.x) / (2.0 * PI);
        float v = 0.5 + asin(clamp(ray.y, -1.0, 1.0)) / PI;

        float scale = fi * 0.55 + 2.6;
        vec2 uv = vec2(u, v) * scale * ExternalScale;

        // 星点：格点哈希，格子内一个柔和的亮点
        vec2 g = uv * STAR_DENSITY;
        vec2 cell = floor(g);
        float h = hash21(cell + vec2(fi * 31.7, fi * 11.3));
        if (h > 0.985) {
            vec2 f = fract(g) - 0.5;
            float bright = (h - 0.985) / 0.015;
            // 收成"一个亮点 + 很薄的晕"：小星不能靠大半径的软圆，否则就是一团糊
            float d = length(f);
            float star = (smoothstep(0.20, 0.0, d) + 0.35 * smoothstep(0.42, 0.0, d)) * bright;
            // 远处（层号小）的星更小更暗，近处更大更亮
            float atten = 0.30 + 0.70 / (1.0 + fi * 0.55);
            col += vec3(0.72, 0.90, 1.0) * star * atten;
        }

        // 星云：低频哈希块，偏青，压得很淡
        vec2 nc = floor(uv * 3.0);
        float nh = hash21(nc + vec2(fi * 5.1, 2.3));
        if (nh > 0.62) {
            vec2 nf = fract(uv * 3.0) - 0.5;
            float haze = smoothstep(0.62, 0.0, length(nf)) * (nh - 0.62) / 0.38;
            col += vec3(0.05, 0.16, 0.20) * haze * (0.35 + 0.65 / (1.0 + fi));
        }
    }

    // ── 棕色的星际尘埃 ──────────────────────────────────────────────
    // 原图里那层暖褐色雾状物：低频、几乎不随时间变，只跟着视线方向走，
    // 所以转动视角时它和星空一起"转"，但本身不会闪烁。
    float dust = 0.0;
    dust += 0.55 * sin(3.1 * dir.x + 1.7) * sin(2.7 * dir.y + 0.4) * sin(3.3 * dir.z + 2.2);
    dust += 0.30 * sin(7.3 * dir.x - 0.9) * sin(6.1 * dir.y + 3.1) * sin(7.7 * dir.z + 1.3);
    dust += 0.15 * sin(13.1 * dir.x + 2.4) * sin(11.7 * dir.y - 1.8) * sin(12.3 * dir.z + 0.7);
    dust = clamp(dust * 0.5 + 0.5, 0.0, 1.0);
    dust = pow(dust, 2.2);
    // 暖褐色：比黑色亮一档，作为"背景雾"垫在星点下面
    col = mix(col, vec3(0.26, 0.16, 0.10), dust * 0.55);

    // 光照：沿用物品原本的亮度（手里变亮、暗处变暗），不做纯自发光
    col *= mix(vec3(1.0), vColor.rgb * 1.35, 0.55);

    // 贴图 RGB 决定"哪些像素保留原色"：
    // 最外圈被画成原图的颜色（亮），内部是纯黑。
    // 所以按亮度混合——亮的地方用贴图色（= 原图的剑身轮廓），黑的地方铺星空。
    // 现在是**二值**的：贴图里被涂成纯黑的格子 = 星空，其余 = 原图颜色。
    // 用 step 而不是 smoothstep，避免中间调也被掺进星星（那样整把剑都会发花）。
    float lum = dot(mask.rgb, vec3(0.299, 0.587, 0.114));
    float keep = step(0.02, lum);
    col = mix(col, mask.rgb * (0.55 + 0.55 * max(vColor.r, 0.35)), keep);

    // 遮罩决定形状：贴图 alpha 之外一律不画。
    // 这里用 discard 而不是只靠混合 —— 半透明混合下 alpha=0 的像素仍会写深度/影响排序，
    // 而且一旦某个环节把 alpha 弄丢，整块 quad 就会变成一个方块（就是之前看到的白方框）。
    float a = mask.a * Opacity;
    if (a < 0.5) {
        discard;
    }
    fragColor = vec4(clamp(col, 0.0, 1.0), a);
}

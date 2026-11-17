# 星辉剑视觉特效（星空流转 / 界面黑洞）

这一层是**纯客户端装饰**：不改任何同步数据、不发包、不影响服务端判定。
所有开关都是系统属性，出问题可以单项关掉。

## 1. 剑的星空流转 + 边缘泛光

- `assets/star_sword_mod_1785589476/textures/item/star_sword.png`
  是一张**竖向帧序列**（40 宽 × 40×96 高），旁边带
  `star_sword.png.mcmeta`（`frametime=1`、`interpolate=true`）。
- 画布 40×40：原 16×16 图**最近邻放大 2 倍**（32×32）居中，四周留 4 像素空白专给泛光。
  这个比例是有意的——原版把贴图映射到 16×16 的模型空间，画布超过 ~40 就会在常见
  GUI 缩放下被 mipmap 压得太狠，细节（尤其是那圈边缘光）会被抹平。
- **原图最外一圈（放大后 2 texel）逐字节锁死**，生成器每次自检；泛光只能画在留白里。
- 三段效果叠在一起：
  1. **刃口高光**：贴着轮廓的最外 1-2 texel 往冷白提亮；
  2. **彗星头**：一道高光沿剑身从剑柄扫到剑尖，一圈走一次，首尾用 `frac` 对接保证无缝；
  3. **外圈泛光**：留白里的软光，alpha 跟着彗星头一起明暗。
- 生成器自检输出必须全是 0：`borderMismatch=0 strayPixels=0 seamDelta=0`。

### 为什么还要开附魔光晕

贴图只有 alpha 混合：压在浅灰的物品栏背景上，青色光晕只会"变淡"，不会"发亮"。
1.20.1 里唯一真正按**加色混合**逐帧滚动的物品层就是原版 glint，
所以 `StarSwordItem#isFoil` 默认返回 true（`-Dstar_sword.fx.glint=false` 关闭）。
外观上会带一层和附魔物品一样的流光；不想要就关掉，贴图那三层仍然生效。

## 2. 介绍框里的黑洞（进阶档 + 实时星云）

`client/render/BlackHoleCard.java` 接管 `RenderTooltipEvent.Pre`，把星辉剑的
tooltip 换成一张卡片：左边逐字彩色文字，右边一颗实时黑洞。

| 层 | 来源 | 混合 | 作用 |
|---|---|---|---|
| 0 | `NebulaField` 动态纹理 | 加色 | **会流动的星云**（差速旋转剪出螺旋） |
| 1 | `blackhole_halo` | 加色 | 周围辉光 |
| 2 | `blackhole_disk` | 加色 | 盘的远侧（压扁 0.38 当倾角） |
| 3 | `blackhole_core` | alpha | 事件视界，正圆，遮住背后的盘 |
| 4 | `blackhole_ring` | 加色 | 光子环 |
| 5 | `blackhole_disk` | 加色 + 剪刀 | 盘的近侧，只留中心线以下，形成前后关系 |
| 6 | `blackhole_streak` | 加色 | 被拉成弧的星光 |

### 流动感从哪来

静态贴图无论怎么旋转都只是"整块在转"。`NebulaField` 每 80ms 在 CPU 上重算一张
160×160 的 RGBA 并上传，靠两件事做出流动：

1. **差速旋转** `shear = 1.35*t / (0.16 + r²)`：内圈角速度远大于外圈，
   同一团气体被越拧越长的螺旋剪切；
2. **噪声场本身随 t 演化**，不是整体平移。

再加一层大尺度扇区调制，星云才有明暗块而不是一圈圈均匀的弧。
开销约 0.2ms/次，只在介绍框打开时跑；任何异常都会永久关掉这一层，不影响 tooltip 其余部分。

文字区会压一条从左到右渐隐的底色（32 条竖带拼的横向渐变，1.20.1 只有纵向
`fillGradient`），保证星云溢过来时文字对比度不被吃掉。

关掉整个特效：`-Dstar_sword.fx.tooltip=false`（退回原来的纯色边框介绍框）。

## 3. 离线预览（不启动游戏）

```
java tools/PreviewTooltip.java
```

把层顺序 / 压扁 / 旋转 / 加色 / 双线性采样在 CPU 上复刻一遍，输出
`tools/preview/tooltip_preview.png`。**改效果时两边要一起改**：
`PreviewTooltip.drawBlackHole` 与 `BlackHoleCard.drawBlackHole`、
`PreviewTooltip.nebulaPixels` 与 `NebulaField.fill` 必须保持一致。

`tools/preview/sword_ingame.png` 是把帧序列按 16px / 32px 实际显示尺寸重采样后的样子，
用来判断"在游戏里到底看不看得见"——比放大看帧序列可靠。

## 4. 已知取舍

- 物品图标这一层仍是**多帧动画贴图 + 烘焙泛光**，不是实时 shader：
  Forge 1.20.1 没有"给物品加一层覆盖"的钩子，实时版本必须用 `builtin/entity` +
  `BlockEntityWithoutLevelRenderer` 接管**所有上下文**（背包 / 快捷栏 / 手持 / 掉落 / GUI）
  的图标绘制，还要自己复刻 `item/handheld` 的 display 变换，画错就是图标变歪或变黑。
- 黑洞没有做真正的引力透镜后处理（要读主 framebuffer + 全屏 UV 扭曲），
  现在的层次感是靠"盘的后半 / 视界 / 盘的前半"三段叠出来的。

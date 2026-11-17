# 星辉剑视觉特效（星空流转 / 界面黑洞）

这一层是**纯客户端装饰**：不改任何同步数据、不发包、不影响服务端判定。
所有开关都是系统属性，出问题可以单项关掉。

## 1. 剑的星空流转

- `assets/star_sword_mod_1785589476/textures/item/star_sword.png`
  是一张**竖向帧序列**（64 宽 × 64×96 高），旁边带
  `star_sword.png.mcmeta`（`frametime=1`、`interpolate=true`）。
- 原版把动画贴图按"帧尺寸 = 宽度"切分后只把当前帧 stitch 进图集，
  所以整条 64×6144 不会被当成一张超大 sprite 处理。
- **外圈 4 像素逐字节保持原图**（原 16×16 的最外一圈按 4 倍最近邻放大），
  星云只在剑身内部流动，剑的轮廓不会糊掉。
- 贴图由 `tools/GenFx.java` 程序化生成，可重复运行；重新生成后
  在游戏里 `F3+T` 即可看到，不必重启客户端。

## 2. 介绍框里的黑洞（进阶档）

`client/render/BlackHoleCard.java` 接管 `RenderTooltipEvent.Pre`，把星辉剑的
tooltip 换成一张卡片：左边逐字彩色文字，右边一颗实时黑洞。

黑洞由 5 层预生成贴图叠加，**没有实时后处理**（不读主 framebuffer、不做 PostChain），
因此在光影包 / Sodium / 低配显卡下行为一致：

| 层 | 贴图 | 混合 | 作用 |
|---|---|---|---|
| 1 | `blackhole_halo` | 加色 | 周围辉光 |
| 2 | `blackhole_disk` | 加色 | 盘的远侧（压扁成椭圆并旋转） |
| 3 | `blackhole_core` | alpha | 事件视界，遮住背后的盘 |
| 4 | `blackhole_ring` | 加色 | 光子环 |
| 5 | `blackhole_disk` | 加色 + 剪刀矩形 | 盘的近侧，只保留中心线以下，形成前后关系 |
| 6 | `blackhole_streak` | 加色 | 被拉成弧的星光 |

关掉：`-Dstar_sword.fx.tooltip=false`（退回原来的纯色边框介绍框）。

## 3. 离线预览（不启动游戏）

```
java tools/PreviewTooltip.java
```

把 `BlackHoleCard` 的层顺序 / 压扁 / 旋转 / 加色在 CPU 上复刻一遍，
输出 `tools/preview/tooltip_preview.png`。**改效果时两边要一起改**：
`PreviewTooltip.drawBlackHole` 与 `BlackHoleCard.drawBlackHole` 的参数必须一致。

## 4. 已知取舍

- 物品图标这一层用的是"多帧动画贴图"，不是实时的 UV 滚动着色器。
  原因：Forge 1.20.1 没有"给物品加一层覆盖"的钩子，实时版本必须用
  `builtin/entity` + `BlockEntityWithoutLevelRenderer` 接管**所有上下文**
  （背包 / 快捷栏 / 手持 / 掉落 / GUI）的图标绘制，需要自己复刻
  `item/handheld` 的 display 变换，画错就是"物品图标变歪/变黑"。
  动画贴图在各处都 100% 正确，代价是帧率固定为 20fps 的插值流动。
- 实时黑洞后处理（真引力透镜）没有做，见 README 的能力分级。

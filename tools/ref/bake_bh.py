"""把史瓦西渲染器跑成一张带 alpha 的 PNG：只保留"盘 + 阴影 + 光子环"，背景透明。

复用 jkoyli/schwarzschild-shader 的 cpu/render.py，不重写物理：

    u'' = -u + 1.5u²   （零测地线，r_s = 1）

输出：`tools/ref/out/bh_sprite.png`（RGBA，160×160）

用法：`python tools/ref/bake_bh.py`
"""
import math
import os
import struct
import sys
import time
import zlib

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import render as R  # noqa: E402
from schwarzschild import SHADOW_RADIUS  # noqa: E402

# ── 覆盖渲染参数 ────────────────────────────────────────────────────────────
R.WIDTH = 192
R.HEIGHT = 192
R.SUPERSAMPLE = 2
R.DPHI = 0.012
R.MAX_PHI = 16.0
R.DISK_OUTER = 6.5
R.DISK_OPACITY = 0.90
R.CAM_DIST = 45.0
R.CAM_ELEV_DEG = 9.0
R.FOV_Y = 40.0

# 背景只留黑：盘和阴影之外的地方必须透明，才能在游戏里透出深空照片
R.starfield = lambda direction, pixel_angle=None: np.zeros(3)


def write_rgba(path, rgba):
    h, w, _ = rgba.shape
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw.extend(rgba[y].tobytes())

    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        c += struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)
        return c

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


def main():
    t0 = time.time()
    print('渲染 %dx%d  ss=%d  dphi=%.3f' % (R.WIDTH, R.HEIGHT, R.SUPERSAMPLE, R.DPHI))
    img, _ = R.render()

    rgb = R.tonemap(img, 1.15)                      # H*W*3 uint8

    h, w, _ = rgb.shape
    rgba = np.zeros((h, w, 4), dtype=np.uint8)
    rgba[:, :, :3] = rgb
    lum = rgb.astype(np.int32).sum(axis=2)
    rgba[:, :, 3] = np.where(lum > 3, 255, 0)

    # 阴影内部渲染出来是全黑（alpha=0），会被照片透出来 —— 必须手动补成不透明黑圆。
    half_h = math.tan(math.radians(R.FOV_Y) * 0.5)
    r_px = (SHADOW_RADIUS / R.CAM_DIST) / half_h * (R.HEIGHT * 0.5)
    cy, cx = (h - 1) * 0.5, (w - 1) * 0.5
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    inside = d <= r_px + 0.75
    rgba[inside, 0:3] = 0
    rgba[inside, 3] = 255

    out_dir = os.path.join(HERE, 'out')
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, 'bh_sprite.png')
    write_rgba(path, rgba)
    print('写出 %s  (阴影半径 %.1f px, 用时 %.1f s)' % (path, r_px, time.time() - t0))


if __name__ == '__main__':
    main()

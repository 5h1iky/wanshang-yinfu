# -*- coding: utf-8 -*-
"""rect-probe.py —— 量某个矩形区域内的像素构成（软件内问题 ③c 取证）。

量三样（都是数值，不靠眼睛）：
  ① 药丸底（bg_control #CC262626 叠在黑底上 ≈ rgb(30,30,30)）的像素数与**上下沿**
  ② 文字亮像素的 bbox（上下沿）—— 与按钮 bounds 比对，看有没有被裁
  ③ 该矩形里出现最多的几种颜色

用法：
  python tools/rect-probe.py <in.png> <x0> <y0> <x1> <y1> [标签]
"""
import sys
from collections import Counter

from PIL import Image

PILL_LO, PILL_HI = 20, 60      # 药丸底叠黑底后的灰阶范围（#CC262626 → ≈30）
TEXT_MIN = 300                 # 文字：亮像素（三通道和）


def main():
    if len(sys.argv) < 6:
        print(__doc__)
        return 2
    path = sys.argv[1]
    x0, y0, x1, y1 = (int(v) for v in sys.argv[2:6])
    label = sys.argv[6] if len(sys.argv) > 6 else ""
    im = Image.open(path).convert("RGB")
    px = im.load()

    pill_ys, text_ys, text_xs, hist = [], [], [], Counter()
    for y in range(y0, y1):
        for x in range(x0, x1):
            c = px[x, y]
            s = sum(c)
            hist[c] += 1
            if PILL_LO <= s <= PILL_HI and abs(c[0] - c[1]) < 6 and abs(c[1] - c[2]) < 6:
                pill_ys.append(y)
            if s >= TEXT_MIN:
                text_ys.append(y)
                text_xs.append(x)

    print("== %s  矩形 [%d,%d][%d,%d] ==" % (label, x0, y0, x1, y1))
    if pill_ys:
        print("  药丸底: y %d..%d（高 %d px）  像素数 %d"
              % (min(pill_ys), max(pill_ys), max(pill_ys) - min(pill_ys) + 1, len(pill_ys)))
    else:
        print("  药丸底: 未检测到（该矩形内没有 bg_control 灰阶）")
    if text_ys:
        print("  文字亮像素: y %d..%d（高 %d px）, x %d..%d  像素数 %d"
              % (min(text_ys), max(text_ys), max(text_ys) - min(text_ys) + 1,
                 min(text_xs), max(text_xs), len(text_ys)))
    else:
        print("  文字亮像素: 未检测到")
    print("  主要颜色: " + ", ".join("rgb%s×%d" % (c, n) for c, n in hist.most_common(4)))
    return 0


if __name__ == "__main__":
    sys.exit(main())

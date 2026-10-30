# -*- coding: utf-8 -*-
"""crop-zoom.py —— 把手表截屏的某块区域裁出来放大，便于肉眼看渲染细节（软件内问题 ③c 取证）。

用法：
  python tools/crop-zoom.py <in.png> <out.png> <x0> <y0> <x1> <y1> [scale]
再配合 read_image 看细节；量化仍以 edge-probe.py / dump bounds 为准。
"""
import sys

from PIL import Image


def main():
    if len(sys.argv) < 7:
        print(__doc__)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    x0, y0, x1, y1 = (int(v) for v in sys.argv[3:7])
    scale = int(sys.argv[7]) if len(sys.argv) > 7 else 3
    im = Image.open(src).convert("RGB").crop((x0, y0, x1, y1))
    im = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
    im.save(dst)
    print("已写出 %s（%dx%d，放大 %dx）" % (dst, im.width, im.height, scale))
    return 0


if __name__ == "__main__":
    sys.exit(main())

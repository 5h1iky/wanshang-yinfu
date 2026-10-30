# -*- coding: utf-8 -*-
"""edge-probe.py —— 从手表截屏里**数**出某行的左边缘 x 坐标（软件内问题 ② 实时预览的判据）。

为什么不用眼睛看截图：纪律要求结论只认可证伪的数值。这里做的是像素扫描——
在给定 y 行上从左往右扫，找第一个与"页面底色"不同的像素，就是那一行的左边缘。

用法：
  python tools/edge-probe.py <shot.png> <y> [<y2> ...]
页面底色取 (0, y) 的像素（屏幕最左边缘，必定是页面背景，不会被行背景盖住）。
"""
import sys

from PIL import Image


def scan(path, ys):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    print("图片尺寸: %dx%d" % (w, h))
    for y in ys:
        if y < 0 or y >= h:
            print("  y=%d 越界" % y)
            continue
        bg = px[0, y]
        edge = None
        for x in range(w):
            if px[x, y] != bg:
                edge = x
                break
        print("  y=%-4d 底色=%s 左边缘 x=%s" % (y, bg, edge if edge is not None else "无(整行同色)"))
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    sys.exit(scan(sys.argv[1], [int(v) for v in sys.argv[2:]]))

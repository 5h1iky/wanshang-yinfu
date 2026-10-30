# -*- coding: utf-8 -*-
"""video-area.py —— 量截屏里"视频画面"占了多少（软件内问题 ⑤ 的判据）。

为什么要它：方案 §5.1 的核心论点是"光全屏不会让横屏视频变大，真正变大靠旋转"。
这个论点必须用**像素**证伪，不能靠看：视频上下是黑边（letterbox），
所以"画面高度"= 非黑边区域的高度。

⚠️ 第一版按**单列**找最长非黑段，结果被画面内部的暗部骗了（游戏场景有整段暗色的行，
   会把一段画面切成两段，量出来偏小）。现在改成**多列聚合**：
   在一堆列上逐行取最大亮度，再找最长的连续"有亮像素"行段——
   视频行几乎不可能整行全黑，而黑边行是整行纯黑，所以这个判据稳。

用法：
  python tools/video-area.py <shot.png> [阈值] [列步长]
输出：画面 y 区间、高度、占屏高百分比（以及上下黑边各多高）。
"""
import sys

from PIL import Image


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else None
    if not path:
        print(__doc__)
        return 2
    thr = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    step = int(sys.argv[3]) if len(sys.argv) > 3 else 8

    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    # 只看中间 84% 宽度：左右边缘在圆屏上本来就会被裁，也可能有控件
    x0, x1 = int(w * 0.08), int(w * 0.92)

    lit_rows = []
    for y in range(h):
        best = 0
        for x in range(x0, x1, step):
            s = sum(px[x, y])
            if s > best:
                best = s
                if best > thr:
                    break
        lit_rows.append(best > thr)

    runs, start = [], None
    for y, lit in enumerate(lit_rows):
        if lit and start is None:
            start = y
        elif not lit and start is not None:
            runs.append((start, y - 1))
            start = None
    if start is not None:
        runs.append((start, h - 1))

    print("图 %dx%d（列 %d..%d，步长 %d，阈值 sum>%d）" % (w, h, x0, x1, step, thr))
    if not runs:
        print("  整屏全黑：没有画面")
        return 0
    runs.sort(key=lambda r: r[1] - r[0], reverse=True)
    a, b = runs[0]
    print("  画面: y=%d..%d  高=%dpx  占屏高 %.1f%%" % (a, b, b - a + 1, (b - a + 1) * 100.0 / h))
    print("  上黑边 %dpx / 下黑边 %dpx" % (a, h - 1 - b))
    if len(runs) > 1:
        print("  （另有 %d 段更短的亮区，多为控件/文字）" % (len(runs) - 1))
    return 0


if __name__ == "__main__":
    sys.exit(main())

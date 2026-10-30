# -*- coding: utf-8 -*-
"""col-runs.py —— 沿某一列统计颜色分段（软件内问题 ③c 取证用）。

为什么要它：快捷回复条"看着被遮挡"是**控件内部**的渲染问题（MaterialButton 的 6dp inset
把文字挤在 28dp 高度里裁掉），两个 View 的 bounds 并不重叠 —— 台账原来那条
"快捷条下边界 ≤ 输入行上边界"的判据**抓不住真因**。所以改成量控件内部的像素：
  · 药丸底（bg_control）实际绘制高度应该是满高 28dp≈33px
  · 文字像素的上下沿必须落在按钮 bounds 内，不能被裁

用法：
  python tools/col-runs.py <in.png> <x> <y0> <y1> [--quiet-same]
输出：该列上连续同色的分段（y 区间 + 颜色 + 高度），便于直接读数值。
"""
import sys

from PIL import Image


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        return 2
    path, x, y0, y1 = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
    im = Image.open(path).convert("RGB")
    px = im.load()
    runs = []
    cur = None
    for y in range(y0, y1):
        c = px[x, y]
        if cur and cur[2] == c:
            cur[1] = y
            cur[3] += 1
        else:
            if cur:
                runs.append(cur)
            cur = [y, y, c, 1]
    if cur:
        runs.append(cur)

    print("列 x=%d，y=%d..%d" % (x, y0, y1))
    for a, b, c, n in runs:
        print("  y=%3d..%3d  h=%3d  rgb%s" % (a, b, n, c))

    # 亮像素（文字）与 bg_control 近似像素的上下沿
    def extent(pred):
        ys = [y for y in range(y0, y1) if pred(px[x, y])]
        return (min(ys), max(ys), len(ys)) if ys else None

    text = extent(lambda c: sum(c) > 330)                      # 偏亮 = 文字/emoji
    pill = extent(lambda c: 40 < sum(c) < 260)                 # 中间调 = 药丸底/描边
    print("  亮像素(文字) y 范围: %s" % (text,))
    print("  中间调(药丸底) y 范围: %s" % (pill,))
    return 0


if __name__ == "__main__":
    sys.exit(main())

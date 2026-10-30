# -*- coding: utf-8 -*-
"""M5 真机走查辅助：按颜色统计 + 连通块找悬浮球/卡片等目标。"""
import sys
from PIL import Image


def _match(px, want, tol):
    r, g, b = px[:3]
    return abs(r - want[0]) <= tol and abs(g - want[1]) <= tol and abs(b - want[2]) <= tol


def components(path, hexcolor, tol=10, region=None):
    img = Image.open(path).convert("RGB")
    want = tuple(int(hexcolor[i:i + 2], 16) for i in (0, 2, 4))
    px = img.load()
    w, h = img.size
    x0, y0, x1, y1 = region or (0, 0, w, h)
    seen = set()
    found = []
    for y in range(y0, y1):
        for x in range(x0, x1):
            if (x, y) in seen or not _match(px[x, y], want, tol):
                continue
            stack = [(x, y)]
            seen.add((x, y))
            minx = maxx = x
            miny = maxy = y
            count = 0
            while stack:
                cx, cy = stack.pop()
                count += 1
                minx, maxx = min(minx, cx), max(maxx, cx)
                miny, maxy = min(miny, cy), max(maxy, cy)
                for nx, ny in ((cx + 1, cy), (cx - 1, cy), (cx, cy + 1), (cx, cy - 1)):
                    if x0 <= nx < x1 and y0 <= ny < y1 and (nx, ny) not in seen \
                            and _match(px[nx, ny], want, tol):
                        seen.add((nx, ny))
                        stack.append((nx, ny))
            found.append((count, minx, miny, maxx, maxy))
    found.sort(reverse=True)
    return found


if __name__ == "__main__":
    path = sys.argv[1]
    color = sys.argv[2]
    tol = int(sys.argv[3]) if len(sys.argv) > 3 else 10
    region = None
    if len(sys.argv) > 7:
        region = tuple(int(v) for v in sys.argv[4:8])
    for item in components(path, color, tol, region)[:4]:
        count, minx, miny, maxx, maxy = item
        print(f"px={count} bbox=({minx},{miny})-({maxx},{maxy}) size={maxx - minx + 1}x{maxy - miny + 1}")

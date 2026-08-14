#!/usr/bin/env python3
"""SVG path arc(A/a) → cubic bezier converter (Android VectorDrawable compatible).
关键: arc 的 flag 参数必须按单字符解析（SVG 允许 014.21 这种粘连写法）。
"""
import re, math, sys

NUM = re.compile(r'[\s,]*([-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?)')

def read_num(s, i):
    m = NUM.match(s, i)
    if not m: raise ValueError(f'num expected at {i}: {s[i:i+20]!r}')
    return float(m.group(1)), m.end()

def read_flag(s, i):
    while i < len(s) and s[i] in ' \t\r\n,': i += 1
    if i >= len(s) or s[i] not in '01': raise ValueError(f'flag expected at {i}')
    return int(s[i]), i + 1

def arc_to_cubics(x0, y0, rx, ry, phi_deg, large, sweep, x, y):
    if rx == 0 or ry == 0:
        return [[x0, y0, x, y, x, y]]
    phi = math.radians(phi_deg % 360)
    cph, sph = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x0 - x) / 2.0, (y0 - y) / 2.0
    x1p = cph * dx2 + sph * dy2
    y1p = -sph * dx2 + cph * dy2
    lam = x1p**2 / rx**2 + y1p**2 / ry**2
    if lam > 1:
        s = math.sqrt(lam); rx *= s; ry *= s
    num = rx**2 * ry**2 - rx**2 * y1p**2 - ry**2 * x1p**2
    den = rx**2 * y1p**2 + ry**2 * x1p**2
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large != sweep: co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cph * cxp - sph * cyp + (x0 + x) / 2.0
    cy = sph * cxp + cph * cyp + (y0 + y) / 2.0

    def ang(ux, uy, vx, vy):
        dot = ux * vx + uy * vy
        n = math.hypot(ux, uy) * math.hypot(vx, vy)
        a = math.acos(max(-1, min(1, dot / n))) if n else 0.0
        if ux * vy - uy * vx < 0: a = -a
        return a

    th1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dth > 0: dth -= 2 * math.pi
    if sweep and dth < 0: dth += 2 * math.pi

    nseg = max(1, int(math.ceil(abs(dth) / (math.pi / 2))))
    delta = dth / nseg
    k = 4.0 / 3.0 * math.tan(delta / 4)
    out = []
    th = th1
    for _ in range(nseg):
        th2 = th + delta
        c1, s1 = math.cos(th), math.sin(th)
        c2, s2 = math.cos(th2), math.sin(th2)
        def pt(tc, ts):
            return (cx + rx * tc * cph - ry * ts * sph,
                    cy + rx * tc * sph + ry * ts * cph)
        e1 = pt(c1 - k * s1, s1 + k * c1)
        e2 = pt(c2 + k * s2, s2 - k * c2)
        ep = pt(c2, s2)
        out.append([e1[0], e1[1], e2[0], e2[1], ep[0], ep[1]])
        th = th2
    if out:
        out[-1][4] = x; out[-1][5] = y
    return out

def convert(d):
    # 按命令分段: 字母 + 后续非字母串
    segs = re.findall(r'([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)', d)
    out = []
    cx = cy = 0.0
    prev_cmd = None
    start = (0.0, 0.0)
    for cmd, rest in segs:
        i = 0
        s = rest
        if cmd in 'Zz':
            out.append('Z'); cx, cy = start; continue
        if cmd in 'Mm':
            x, i = read_num(s, i); y, i = read_num(s, i)
            if cmd == 'M': cx, cy = x, y; start = (cx, cy); out.append(f'M {x:.3f} {y:.3f}')
            else: cx += x; cy += y; start = (cx, cy); out.append(f'L {cx:.3f} {cy:.3f}')
            prev_cmd = 'L'
        elif cmd in 'Ll':
            while True:
                try:
                    x, i = read_num(s, i); y, i = read_num(s, i)
                except ValueError: break
                if cmd == 'L': cx, cy = x, y
                else: cx += x; cy += y
                out.append(f'L {cx:.3f} {cy:.3f}')
        elif cmd in 'Hh':
            while True:
                try: x, i = read_num(s, i)
                except ValueError: break
                cx = x if cmd == 'H' else cx + x
                out.append(f'L {cx:.3f} {cy:.3f}')
        elif cmd in 'Vv':
            while True:
                try: y, i = read_num(s, i)
                except ValueError: break
                cy = y if cmd == 'V' else cy + y
                out.append(f'L {cx:.3f} {cy:.3f}')
        elif cmd in 'Cc':
            while True:
                try:
                    n1, i = read_num(s, i); n2, i = read_num(s, i)
                    n3, i = read_num(s, i); n4, i = read_num(s, i)
                    n5, i = read_num(s, i); n6, i = read_num(s, i)
                except ValueError: break
                if cmd == 'C':
                    out.append(f'C {n1:.3f} {n2:.3f} {n3:.3f} {n4:.3f} {n5:.3f} {n6:.3f}')
                    cx, cy = n5, n6
                else:
                    out.append(f'c {n1:.3f} {n2:.3f} {n3:.3f} {n4:.3f} {n5:.3f} {n6:.3f}')
                    cx += n5; cy += n6
        elif cmd in 'Aa':
            while True:
                try:
                    rx, i = read_num(s, i); ry, i = read_num(s, i); rot, i = read_num(s, i)
                    laf, i = read_flag(s, i); sf, i = read_flag(s, i)
                    x, i = read_num(s, i); y, i = read_num(s, i)
                except ValueError: break
                if cmd == 'A':
                    seg = arc_to_cubics(cx, cy, rx, ry, rot, laf, sf, x, y); cx, cy = x, y
                else:
                    seg = arc_to_cubics(cx, cy, rx, ry, rot, laf, sf, cx + x, cy + y); cx += x; cy += y
                for p in seg:
                    out.append('C ' + ' '.join(f'{v:.3f}' for v in p))
        else:
            out.append(cmd + ' ' + s.strip())
    return ' '.join(out)

if __name__ == '__main__':
    src = sys.stdin.read()
    out = re.sub(r'd="([^"]+)"', lambda m: 'd="' + convert(m.group(1)) + '"', src)
    print(out)

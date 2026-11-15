"""Turn the downloaded product photos into straight-on cutouts at true proportions.

Front photos are trimmed to a clean circle or rounded square; three-quarter photos are
un-tilted by fitting the trim's ellipse; the spot's empty hole gets an MR16 lamp so it
looks installed. Output: app/src/main/assets/www/products/*.webp
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SRC = "photos/cut"; RAW = "photos/raw"
OUT = "app/src/main/assets/www/products"
os.makedirs(OUT, exist_ok=True)
S = 900  # working size for spots

def A(im): return np.asarray(im.convert("RGBA")).astype(np.float32)
def I(a): return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")

def lamp(d):
    """An MR16 LED lamp face seen from below, d pixels across."""
    y, x = np.mgrid[0:d, 0:d].astype(np.float32)
    c = (d - 1) / 2; r = np.hypot(x - c, y - c) / (d / 2)
    ang = np.arctan2(y - c, x - c)
    img = np.zeros((d, d, 4), np.float32)
    # dark gap between frame and lamp
    img[..., :3] = 28; img[..., 3] = 255
    # aluminium lamp rim
    rim = (r < 0.94)
    shade = 150 + 60 * np.cos(ang - 2.3)
    for k in range(3): img[..., k] = np.where(rim, shade - (k == 2) * -6, img[..., k])
    # reflector cup: bright, faceted
    cup = r < 0.84
    facets = 0.5 + 0.5 * np.cos(ang * 18)
    cupv = 205 + 35 * (1 - r) + 14 * facets * (r > 0.45)
    img[..., 0] = np.where(cup, cupv, img[..., 0]); img[..., 1] = np.where(cup, cupv - 2, img[..., 1]); img[..., 2] = np.where(cup, cupv - 8, img[..., 2])
    # frosted lens with a warm centre
    lens = r < 0.5
    lv = 238 + 14 * (1 - r / 0.5)
    img[..., 0] = np.where(lens, lv, img[..., 0]); img[..., 1] = np.where(lens, lv - 6, img[..., 1]); img[..., 2] = np.where(lens, lv - 26, img[..., 2])
    # specular highlight
    hl = np.exp(-(((x - c * 0.7) ** 2 + (y - c * 0.62) ** 2) / (2 * (d * 0.06) ** 2)))
    img[..., :3] += hl[..., None] * 40
    noise = np.random.default_rng(3).normal(0, 3, (d, d, 1)); img[..., :3] += noise
    img[..., 3] = np.where(r <= 1.0, 255, 0)
    return I(img).filter(ImageFilter.GaussianBlur(d / 600))

def hole_radius(a, cx, cy):
    """Distance from the centre to the first opaque pixel, smallest over many directions."""
    best = 1e9
    for t in np.linspace(0, 2 * math.pi, 72, endpoint=False):
        for rr in range(2, int(min(a.shape[:2]) / 2)):
            x = int(cx + rr * math.cos(t)); y = int(cy + rr * math.sin(t))
            if not (0 <= x < a.shape[1] and 0 <= y < a.shape[0]): break
            if a[y, x, 3] > 128:
                best = min(best, rr); break
    return best

def drop_blue_orange(a):
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    blue = (b > r + 35) & (b > g + 20)
    orange = (r > g + 50) & (r > b + 80)
    a = a.copy(); a[..., 3] = np.where(blue | orange, 0, a[..., 3]); return a

def fit_ellipse_top(a):
    """Fit the trim's outline (axis-aligned ellipse) from its upper boundary, where clips and side walls are absent.
    Direct least-squares conic fit (Fitzgibbon) on centred coordinates."""
    al = a[..., 3] > 128
    ys = np.where(al.any(1))[0]; y0, y1 = ys[0], ys[-1]
    pts = []
    for y in range(y0 + 2, int(y0 + 0.40 * (y1 - y0))):
        xs = np.where(al[y])[0]
        if len(xs) > 2: pts += [(xs[0], y), (xs[-1], y)]
    p = np.array(pts, np.float64)
    mx, my = p.mean(0); sc = p.std()
    x = (p[:, 0] - mx) / sc; y = (p[:, 1] - my) / sc
    D = np.stack([x * x, x * y, y * y, x, y, np.ones_like(x)], 1)
    Sm = D.T @ D
    C = np.zeros((6, 6)); C[0, 2] = C[2, 0] = 2; C[1, 1] = -1
    ev, evec = np.linalg.eig(np.linalg.inv(Sm) @ C)
    k = np.argmax(ev.real > 0) if np.any(ev.real > 0) else 0
    Aq, Bq, Cq, Dq, Eq, Fq = evec[:, k].real
    # centre
    den = Bq * Bq - 4 * Aq * Cq
    cx = (2 * Cq * Dq - Bq * Eq) / den; cy = (2 * Aq * Eq - Bq * Dq) / den
    # axes (ignore small rotation)
    val = Aq * cx * cx + Bq * cx * cy + Cq * cy * cy + Dq * cx + Eq * cy + Fq
    ax = math.sqrt(-val / Aq); by = math.sqrt(-val / Cq)
    return cx * sc + mx, cy * sc + my, ax * sc, by * sc

def circle_mask(size, inset=0.0):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([inset, inset, size - 1 - inset, size - 1 - inset], fill=255)
    return m

def square_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([1, 1, size - 2, size - 2], radius=radius, fill=255)
    return m

def finish_spot(face, lamp_r_frac=None, lamp_on_top=False):
    """face: square RGBA, frame centred. Puts a lamp in the hole."""
    a = A(face); c = (face.width - 1) / 2
    hr = hole_radius(a, c, c) if lamp_r_frac is None else lamp_r_frac * face.width
    hr = min(hr, face.width * 0.36)
    d = int(hr * 2 * (0.97 if lamp_on_top else 1.06)); d += d % 2
    L = lamp(d)
    base = Image.new("RGBA", face.size, (0, 0, 0, 0))
    off = (int(c - d / 2 + 0.5), int(c - d / 2 + 0.5))
    if lamp_on_top:
        base.alpha_composite(face); base.alpha_composite(L, off)
    else:
        base.alpha_composite(L, off); base.alpha_composite(face)
    return base

def save(im, name, size=700):
    im = im.copy(); im.thumbnail((size, size), Image.LANCZOS)
    im.save(f"{OUT}/{name}.webp", "WEBP", quality=90, method=6)
    return im.size

report = {}

def round_front(name):
    im = Image.open(f"{SRC}/{name}.png").convert("RGBA")
    a = A(im); al = a[..., 3] > 128
    ys, xs = np.where(al); x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    d = max(x1 - x0, y1 - y0)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    face = im.crop((int(cx - d / 2), int(cy - d / 2), int(cx + d / 2), int(cy + d / 2))).resize((S, S), Image.LANCZOS)
    face.putalpha(Image.fromarray(np.minimum(A(face)[..., 3], np.asarray(circle_mask(S, 3))).astype(np.uint8)))
    report[name] = save(finish_spot(face), name)

def round_tilted(name):
    im = Image.open(f"{SRC}/{name}.png").convert("RGBA")
    a = drop_blue_orange(A(im))
    cx, cy, ax, by = fit_ellipse_top(a)
    im2 = I(a)
    sy = ax / by
    big = im2.resize((im2.width, int(im2.height * sy)), Image.LANCZOS)
    cy2 = cy * sy
    face = big.crop((int(cx - ax), int(cy2 - ax), int(cx + ax), int(cy2 + ax))).resize((S, S), Image.LANCZOS)
    m = np.minimum(A(face)[..., 3], np.asarray(circle_mask(S, 4)))
    face.putalpha(Image.fromarray(m.astype(np.uint8)))
    report[name] = save(finish_spot(face), name)

def square_front(name, from_raw=False):
    if from_raw:
        im = Image.open(f"{RAW}/{name}.png").convert("RGBA")
        a = A(im); dark = (a[..., :3].min(2) < 236)
        ys, xs = np.where(dark)
    else:
        im = Image.open(f"{SRC}/{name}.png").convert("RGBA")
        a = A(im); ys, xs = np.where(a[..., 3] > 128)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    d = max(x1 - x0, y1 - y0); cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    face = im.crop((int(cx - d / 2), int(cy - d / 2), int(cx + d / 2), int(cy + d / 2))).resize((S, S), Image.LANCZOS)
    fa = A(face)
    alpha = np.asarray(square_mask(S, S * 0.035)).astype(np.float32)
    if not from_raw: alpha = np.minimum(alpha, fa[..., 3])
    face.putalpha(Image.fromarray(alpha.astype(np.uint8)))
    if from_raw:
        # hole is white background: find the inner edge of the coloured ring and lay the lamp over it
        f = A(face); r, g, b = f[..., 0], f[..., 1], f[..., 2]
        gold = (r - b > 45) & (r > 120)
        yy, xx = np.where(gold); c = (S - 1) / 2
        rr = np.hypot(xx - c, yy - c); inner = np.percentile(rr, 3)
        report[name] = save(finish_spot(face, lamp_r_frac=inner / S, lamp_on_top=True), name)
    else:
        report[name] = save(finish_spot(face), name)

def plain_cut(name, size=700):
    im = Image.open(f"{SRC}/{name}.png").convert("RGBA")
    report[name] = save(im, name, size)

def box_face(name):
    im = Image.open(f"{SRC}/{name}.png").convert("RGBA")
    a = A(im); al = a[..., 3] > 128
    ys, xs = np.where(al)
    t = np.argmin(ys); T = (xs[ys == ys[t]].mean(), ys[t])
    lx = xs.min(); L = (lx, ys[xs <= lx + 2].min())
    rx = xs.max(); R = (rx, ys[xs >= rx - 2].min())
    F = (L[0] + R[0] - T[0], L[1] + R[1] - T[1])
    # rectangle: top edge L->T (back), bottom edge F->R (front)
    w = int(max(math.dist(L, T), math.dist(F, R))); h = int(max(math.dist(L, F), math.dist(T, R)))
    quad = [L[0], L[1], F[0], F[1], R[0], R[1], T[0], T[1]]  # PIL order: UL, LL, LR, UR
    face = im.transform((w, h), Image.QUAD, quad, Image.BICUBIC)
    face.putalpha(Image.fromarray(np.asarray(square_mask(max(w, h), 18).resize((w, h))).astype(np.uint8)))
    report[name] = save(face, name)
    report[name + "_corners"] = [list(map(int, p)) for p in (L, T, R, F)]

def panel(name):
    im = Image.open(f"{SRC}/{name}.png").convert("RGBA")
    a = A(im)
    cx, cy, ax, by = fit_ellipse_top(a)
    sy = ax / by if by < ax else 1
    big = im.resize((im.width, int(im.height * sy)), Image.LANCZOS); cy2 = cy * sy
    face = big.crop((int(cx - ax), int(cy2 - ax), int(cx + ax), int(cy2 + ax))).resize((S, S), Image.LANCZOS)
    face.putalpha(Image.fromarray(np.minimum(A(face)[..., 3], np.asarray(circle_mask(S, 4))).astype(np.uint8)))
    report[name] = save(face, name)

for n in ["sp-round__White_White", "sp-round__Black_Black", "sp-round__Black_Gold", "sp-round__Champagne_Gold"]: round_front(n)
# Only the anti-glare frame straightens well; the other three-quarter photos stay out (see derived colours below).
round_tilted("sp-anti__Black_Black")

def derived_round(name, ring_src, tint=None):
    """Same frame model, other colour: White body from the white photo, ring copied from a front photo."""
    base = A(Image.open(f"{OUT}/sp-round__White_White.webp").convert("RGBA").resize((700, 700)))
    ring = A(Image.open(f"{OUT}/{ring_src}.webp").convert("RGBA").resize((700, 700)))
    r, g, b = ring[..., 0], ring[..., 1], ring[..., 2]
    gold = (r - b > 50) & (r > 110)
    yy, xx = np.where(gold); c = 349.5
    rr = np.hypot(xx - c, yy - c); rin, rout = np.percentile(rr, 4), np.percentile(rr, 97)
    y, x = np.mgrid[0:700, 0:700]; R = np.hypot(x - c, y - c)
    out = base.copy()
    if tint is not None:
        body = R > rout
        for k in range(3): out[..., k] = np.where(body, out[..., k] * tint[k], out[..., k])
    ann = (R >= rin - 1) & (R <= rout + 1)
    out[ann] = ring[ann]
    I(out).save(f"{OUT}/{name}.webp", "WEBP", quality=90, method=6)
    report[name] = "derived"

derived_round("sp-round__White_Gold", "sp-round__Black_Gold")
derived_round("sp-round__Pearl_Gold", "sp-round__Black_Gold", tint=(1.0, 0.975, 0.93))
for n in ["sp-square__Black_Black", "sp-square__Black_Gold", "sp-square__Champagne_Gold", "sp-square__White_Silver"]: square_front(n)

def silver_square():
    im = Image.open(f"{SRC}/sp-square__Silver_Silver.png").convert("RGBA")
    a = A(im); ys, xs = np.where(a[..., 3] > 128)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max(); d = max(x1 - x0, y1 - y0); cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    face = im.crop((int(cx - d / 2), int(cy - d / 2), int(cx + d / 2), int(cy + d / 2))).resize((S, S), Image.LANCZOS)
    face.putalpha(Image.fromarray(np.minimum(A(face)[..., 3], np.asarray(square_mask(S, S * 0.035))).astype(np.uint8)))
    f = A(face); lum = f[..., :3].mean(2); c = (S - 1) / 2
    y, x = np.mgrid[0:S, 0:S]; R = np.hypot(x - c, y - c)
    # the blown-out white disc in the middle is the hole
    hole = (lum > 248) & (R < S * 0.3)
    hr = np.percentile(R[hole], 98) if hole.any() else S * 0.2
    report["sp-square__Silver_Silver"] = save(finish_spot(face, lamp_r_frac=hr / S * 1.02, lamp_on_top=True), "sp-square__Silver_Silver")
silver_square()
square_front("sp-square__White_Gold", from_raw=True)
for n in ["tr-spot", "tr-rflood", "tr-rspot", "tr-rfold", "tr-lflood", "tr-corner", "tr-psu", "mb-box", "mb-pro"]: plain_cut(n)

def holder_without_caption():
    im = Image.open(f"{SRC}/lh-flower.png").convert("RGBA"); a = A(im)
    cnt = (a[..., 3] > 128).sum(1); start = int(np.argmax(cnt > 0.25 * cnt.max()))
    # step back to the top of the holder itself
    while start > 0 and cnt[start - 1] > 0.02 * cnt.max(): start -= 1
    im = im.crop((0, start, im.width, im.height)); bb = im.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox()
    report["lh-flower"] = save(im.crop(bb), "lh-flower")
holder_without_caption()
for n in ["db-bird", "db-ding"]: box_face(n)
try: panel("pn-smd26")
except Exception:
    im = Image.open(f"{SRC}/pn-smd26.png").convert("RGBA"); a = A(im); ys, xs = np.where(a[..., 3] > 128)
    d = min(xs.max() - xs.min(), ys.max() - ys.min()); x0, y0 = xs.min(), ys.min()
    face = im.crop((x0, y0, x0 + d, y0 + d)).resize((S, S), Image.LANCZOS)
    face.putalpha(Image.fromarray(np.minimum(A(face)[..., 3], np.asarray(circle_mask(S, 4))).astype(np.uint8)))
    report["pn-smd26"] = save(face, "pn-smd26")
logo = Image.open(f"{RAW}/logo.png").convert("RGBA"); logo.save(f"{OUT}/logo.png", optimize=True)
json.dump(report, open(f"{OUT}/sizes.json", "w"), indent=1)
print(json.dumps(report))

"""Download product photos from panda-electric.com, cut the background, and build a contact sheet."""
import io, json, os, urllib.parse, urllib.request
from PIL import Image, ImageDraw

m = json.load(open("tools/photos_manifest.json", encoding="utf-8"))
os.makedirs("photos/raw", exist_ok=True)
os.makedirs("photos/cut", exist_ok=True)
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) PandaRealSize/1.0"}

def get(path):
    tries = [path]
    stem, ext = os.path.splitext(path)
    if stem.endswith("-scaled"):
        tries.append(stem[:-7] + ext)
    tries += [stem + "-1200x800" + ext, stem + "-700x700" + ext, stem + "-700x467" + ext]
    for p in tries:
        url = m["base"] + urllib.parse.quote(p)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return r.read(), p
        except Exception as e:
            print("  miss", p, e)
    return None, None

from rembg import remove, new_session
sess = new_session("isnet-general-use")
report = []
for name, path, mode in m["items"]:
    data, used = get(path)
    if not data:
        report.append((name, "DOWNLOAD FAILED")); continue
    im = Image.open(io.BytesIO(data)); im.load()
    raw = im.convert("RGBA")
    raw.save(f"photos/raw/{name}.png")
    if mode == "keep":
        report.append((name, f"kept {raw.size}")); continue
    if max(raw.size) > 1600:
        s = 1600 / max(raw.size); raw = raw.resize((round(raw.width * s), round(raw.height * s)), Image.LANCZOS)
    cut = remove(raw, session=sess, post_process_mask=True)
    bbox = cut.getchannel("A").point(lambda a: 255 if a > 24 else 0).getbbox()
    if bbox: cut = cut.crop(bbox)
    cut.save(f"photos/cut/{name}.png", optimize=True)
    report.append((name, f"src {used} -> cut {cut.size}"))

names = [n for n, _, _ in m["items"]]
cell = 220
sheet = Image.new("RGB", (cell * 4, cell * ((len(names) + 1) // 2)), (128, 134, 140))
d = ImageDraw.Draw(sheet)
for i, n in enumerate(names):
    x0 = (i % 2) * cell * 2; y0 = (i // 2) * cell
    for j, folder in enumerate(["raw", "cut"]):
        f = f"photos/{folder}/{n}.png"
        if not os.path.exists(f): continue
        t = Image.open(f).convert("RGBA"); t.thumbnail((cell - 12, cell - 30))
        bg = Image.new("RGBA", (cell, cell), (150, 156, 162, 255) if j else (200, 200, 200, 255))
        bg.alpha_composite(t, ((cell - t.width) // 2, 4))
        sheet.paste(bg.convert("RGB"), (x0 + j * cell, y0))
    d.text((x0 + 4, y0 + cell - 18), n, fill=(255, 255, 255))
sheet.save("photos/sheet.jpg", quality=88)
open("photos/report.txt", "w").write("\n".join(f"{a}: {b}" for a, b in report))
print("\n".join(f"{a}: {b}" for a, b in report))

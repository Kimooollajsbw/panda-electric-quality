---
name: app-promo-video
description: Break down a reference video with rea-video, then rebuild an app promo / UI-concept motion-graphics video in code (HTML + Playwright + ffmpeg), landscape or vertical Shorts, English or Arabic. Use when the user sends a reference video and says "اعمل زيه" / "make one like this", or wants to edit the YouTube 2.0 promo in promo-video/.
---

# App promo video: break down a reference, rebuild it in code

The user talks Egyptian Arabic. Reply in Egyptian Arabic, short and plain.
Existing work lives in `promo-video/` (read its README first). The two finished videos are in `promo-video/output/`.

## 1. Break down the reference video with rea-video

```bash
git clone --depth 1 https://github.com/JuneYaooo/rea-video.git ~/rea-video
python3 -m venv ~/rea-venv && ~/rea-venv/bin/pip install ~/rea-video
~/rea-venv/bin/rea-video decompose ref.mp4 -o out/ref --lang en   # Arabic isn't supported for the report: use en, answer in Arabic
~/rea-venv/bin/rea-video summary out/ref/breakdown.json
```

Then do the visual review yourself (it is required, the tool alone is ~67% confident on platform downloads):
look at `out/ref/storyboard.jpg` and `out/ref/shots/shot_XXX.jpg`, zoom with
`rea-video tiles ref.mp4 --start A --end B --every 0.5 -o out/ref/check.jpg`,
run `rea-video review out/ref`, fill `out/ref/review_answers.json`, then `rea-video apply-review out/ref --by claude`.
Full workflow: `~/rea-video/skills/rea-video/SKILL.md`.

Ignore the last ~2 s of TikTok downloads: it's the platform's auto-added outro card.

## 2. Rebuild it in code (when the breakdown says "code / template render")

Pattern (see `promo-video/landscape/scene.html`):
- One `scene.html` sized 1920x1080 (landscape) or 1080x1920 (vertical) via `html,body{width;height}`.
- Each scene is an absolutely positioned layer; a single `window.render(t)` sets every style from time `t`
  (deterministic, no CSS animations), using `seg(t,a,b)` + easing (`oC`, `ioC`, `oB` back-out for pops).
- Transitions like the reference: push-in zooms, whip-pans = big translate + `filter: blur(30-45px)`,
  elements "pop" in staggered (opacity + blur 10→0 + back-out translate).
- Render: `node promo-video/tools/render.js <folder> --preview` (check `preview.jpg` before every full render),
  then `node promo-video/tools/render.js <folder> --out promo-video/output/<name>.mp4`.
- Music: `audio.py` in the folder synthesises a bed with numpy (BPM/key from the breakdown) + whooshes on the transition times.
- Always look at the preview sheet and fix overlap, wrapping and off-centre zooms before rendering the full video.

## 3. Design rules the user cares about (learned the hard way)

The user liked the first landscape version a lot and hated a later vertical one. Keep:
- Near-black background (#09090b) with one moving radial glow in the single accent colour (YouTube version: #ff1f3d).
- Inter for Latin/numbers. For Arabic use **IBM Plex Sans Arabic** (`vertical-ar/fonts/`), weights 500-700. **Not Cairo, not 800/900**: heavy fonts looked cheap.
- Headlines: one line, ~74px (vertical) / 84px (landscape), weight 700, only the key phrase in the accent colour. No two-line giant text, no orphaned words.
- Lots of empty dark space around cards; don't fill the frame edge to edge (scale UI scenes ~0.86 in vertical).
- Don't use code-drawn illustrations as thumbnails; they look cheap. Use clean gradients, or real photos the user sends (put them in `img/`).
- Arabic layout: `dir="rtl"`, send button on the left, highlight pill starts on the right.
- Keep the honest disclaimer under the logo ("Independent creative concept. Not an official … release." / "مشروع إبداعي مستقل…"), as in the reference.

## 4. Images

If the user wants real thumbnails: ask them to send photos first (easiest). AI generation (e.g. ElevenLabs connector) costs credits; say the cost before generating.

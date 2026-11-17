# فيديو بروموشن «YouTube 2.0» (كونسبت)

فيديو موشن جرافيك لتطبيق، معمول بالكود بالكامل، على نفس أسلوب فيديو inDrive 2.0 اللي اتفكك بأداة [rea-video](https://github.com/JuneYaooo/rea-video).

## الملفات
| المجلد | فيه إيه |
|---|---|
| `output/1-landscape-en.mp4` | النسخة الأولى: عرضي 16:9، إنجليزي (اللي عجبتك أكتر) |
| `output/2-vertical-ar.mp4` | النسخة العمودي 9:16 للشورتس، عربي |
| `landscape/` | الكود بتاع النسخة العرضي: `scene.html` (التصميم والحركة) + `audio.py` (المزيكا) |
| `vertical-ar/` | الكود بتاع النسخة العمودي العربي + الخطوط |
| `tools/render.js` | بيحوّل أي `scene.html` لفيديو MP4 |

## التشغيل على الكمبيوتر
محتاج: Node.js 18+، Python 3 + numpy، و ffmpeg.

```bash
cd promo-video
npm i playwright && npx playwright install chromium
pip install numpy

# معاينة سريعة (16 صورة في preview.jpg)
node tools/render.js vertical-ar --preview

# الفيديو الكامل
node tools/render.js vertical-ar --out output/vertical-ar-new.mp4
node tools/render.js landscape   --out output/landscape-new.mp4
```

## التعديل
- **النصوص والأسماء:** جوه `scene.html` على طول (الجمل التلاتة، أسماء القنوات، التعليقات).
- **اللون:** `--red` في أول الـ CSS.
- **التوقيت:** كل مشهد ليه وقت بداية ونهاية بالثواني جوه `window.render(t)`.
- **صور الفيديوهات:** حط صورك في مجلد `img/` وغيّر `background-image` في كروت `.thumb` لـ `url(img/اسم-الصورة.jpg), ...`.
- **المزيكا:** `audio.py` بيولّدها بالكود؛ أو حط ملف `music.wav` بتاعك واحذف `audio.py`.

> مشروع إبداعي مستقل، مش إصدار رسمي من يوتيوب. الاسم والشعار ملك أصحابهم.

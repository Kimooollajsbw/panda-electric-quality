// Render a scene.html (which exposes window.render(t)) to an MP4.
//
//   node tools/render.js <scene folder> [--preview] [--seconds 17] [--fps 30] [--out file.mp4]
//
// --preview  renders 16 stills into <folder>/preview.jpg (fast check, no video)
// Needs: Node 18+, `npm i playwright` + `npx playwright install chromium`, ffmpeg, Python 3 + numpy.
const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const args = process.argv.slice(2);
const dir = path.resolve(args[0] || '.');
const opt = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : def; };
const preview = args.includes('--preview');
const seconds = Number(opt('--seconds', 17));
const fps = Number(opt('--fps', 30));
const out = path.resolve(opt('--out', path.join(dir, 'video.mp4')));
const framesDir = path.join(dir, preview ? '.preview' : '.frames');

function run(cmd, a) { execFileSync(cmd, a, { stdio: 'inherit', cwd: dir }); }
function python(a) {
  for (const py of ['python3', 'python']) { try { run(py, a); return; } catch (e) { if (e.code !== 'ENOENT') throw e; } }
  throw new Error('Python not found');
}

(async () => {
  fs.rmSync(framesDir, { recursive: true, force: true });
  fs.mkdirSync(framesDir, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto('file://' + path.join(dir, 'scene.html'));
  // the page decides its own size (1920x1080 or 1080x1920)
  const size = await page.evaluate(() => ({ w: document.body.offsetWidth, h: document.body.offsetHeight }));
  await page.setViewportSize({ width: size.w, height: size.h });
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(300);

  const times = preview
    ? Array.from({ length: 16 }, (_, i) => +(0.6 + i * (seconds - 1) / 15).toFixed(2))
    : Array.from({ length: Math.round(seconds * fps) }, (_, i) => i / fps);
  let i = 0;
  for (const t of times) {
    await page.evaluate(tt => window.render(tt), t);
    await page.screenshot({ path: path.join(framesDir, `f${String(i++).padStart(4, '0')}.png`) });
    if (!preview && i % 60 === 0) console.log(`frame ${i}/${times.length}`);
  }
  await browser.close();

  if (preview) {
    const w = size.w > size.h ? 480 : 270;
    run('ffmpeg', ['-y', '-loglevel', 'error', '-i', path.join(framesDir, 'f%04d.png'),
      '-vf', `scale=${w}:-1,tile=${size.w > size.h ? '4x4' : '8x2'}`, '-frames:v', '1', path.join(dir, 'preview.jpg')]);
    console.log('preview -> ' + path.join(dir, 'preview.jpg'));
    return;
  }
  if (fs.existsSync(path.join(dir, 'audio.py'))) python(['audio.py']);
  const a = ['-y', '-loglevel', 'error', '-framerate', String(fps), '-i', path.join(framesDir, 'f%04d.png')];
  const wav = path.join(dir, 'music.wav');
  if (fs.existsSync(wav)) a.push('-i', wav, '-c:a', 'aac', '-b:a', '192k', '-shortest');
  a.push('-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '18', '-preset', 'medium', out);
  run('ffmpeg', a);
  fs.rmSync(framesDir, { recursive: true, force: true });
  console.log('done -> ' + out);
})();

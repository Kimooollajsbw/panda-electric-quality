# باندا مقاس حقيقي (Panda Real Size) — Android

App that shows Panda Electric products (spots, panels, magnetic track, bells, lamp holders) at their real size on a photo or live camera view of the customer's own ceiling or wall.

Owner: Hakim (engineer at Panda, Port Said). Talk to him in Egyptian Arabic.

## Layout
- `app/src/main/assets/www/index.html` — the whole photo-mode editor (one HTML file, vanilla JS, RTL Arabic). Runs inside a WebView.
- `app/src/main/assets/www/products/` — real product photos (straight-on cutouts, WebP) + `logo.png`.
- `app/src/main/assets/www/fonts/` — Changa + IBM Plex Sans Arabic (offline, OFL).
- `app/src/main/java/com/panda/realsize/MainActivity.kt` — WebView host + JS bridge `window.AndroidApp`: `isApp`, `arStatus`, `startAr(json)`, `saveImage(b64,name)`, `copy`, `share`. Back button calls `window.__androidBack()`.
- `ArActivity.kt` — live camera mode on SceneView 2.3.0 (`ARSceneView`, Filament PBR engine) + ARCore 1.48: horizontal + vertical planes, Environmental HDR light estimate (main light direction, ambient SH, reflection cubemap), shadows on detected planes, MSAA, bloom, optional depth occlusion ("الحجب" chip, off by default). Each item is a plain `Node` placed every frame from its ARCore anchor (x right, y up along the surface, z = surface normal); while dragging it follows the hit pose smoothly and gets one new anchor on release. Products arrive from the page as `cacheDir/ar_products.json` (`id,name,w,h,cat,shape,light,variant,variants[{label,png}]`, pre-rendered WebP per colour).
- `ProductModels.kt` — builds each product's 3D model (GLB) at run time from its photo: contact shadow, sides (stacked alpha-masked slices of the photo = exact silhouette, depth per category in `depthMm`), face with a metal/roughness map guessed from the photo colours, lit lens + glare when the light is on. Also the red selection outline.
- `GlbWriter.kt` — minimal in-memory glTF 2.0 binary writer (meshes, materials, PNG textures).
- `tools/fetch_photos.py` + `.github/workflows/photos.yml` — download product photos from panda-electric.com and remove background (rembg). Runs on GitHub (the site is reachable there).
- `tools/process_photos.py` — turn raw photos into front-view cutouts, add an MR16 lamp in spot holes.
- `.github/workflows/build-apk.yml` — every push to `android-app` builds the APK and publishes a GitHub Release `apk-<run>`.

## Key concepts in index.html
- Calibration `state.cal`: `kind:'quad'` (4 points on a known rectangle → homography `solveH`, surface in mm) or `kind:'line'` (2 points, flat scale). `homo()`, `toPlane`, `toImgPt`, `jac` (local affine per item), `onSurface` (rejects points past the vanishing line).
- Items `{pid,color,light,x,y,rot,tilt,gid}` are stored in photo px; distances/snapping/grid are computed in surface mm.
- Lighting: `light` per product (lens radius fraction); states warm/white/off; ambient tint sampled from the photo around each item (`ambientOf`).
- Catalogue `BASE_LIST`; colours with real photos: `ROUND_REAL`, `SQUARE_REAL`, `ANTI_REAL`. Sizes are typical/approximate (`approx:true`) — panda-electric.com lists no dimensions. User-measured sizes are saved in localStorage (`realsize.store.v2`).
- "منتجاتي": user photographs a product; `cutBackground` removes a plain background (flood fill from edges).

## Build
- Local: open in Android Studio (JDK 17, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 24), Run. Or `gradle :app:assembleDebug` (Gradle 8.11.1; no wrapper committed).
- Quick test of the editor without a phone: serve `app/src/main/assets/www` with any static server and open in a mobile-size browser.
- Signing: `keystore/panda-debug.jks` (debug key, password `pandadebug`) so updates install over each other. Not for Google Play.

## Open items
- Real product dimensions (all marked تقريبي).
- Front-view photos for missing colours (round spot silver×silver, white×silver, black×silver; track products are side views).
- Live camera mode not yet tested on a real phone (3D engine version: tune shadow strength, glow, emissive and bloom on a device).
- Product depths in `ProductModels.depthMm` are guesses per category, like the sizes.
- Repo is public (contains logo, photos, debug key); early commits carry a personal email — owner to decide on making it private / rewriting history.

package com.panda.realsize

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix as GMatrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live camera mode. ARCore finds walls and ceilings; each product is drawn as a flat picture
 * of its real width and height (millimetres from the catalogue) pinned to the surface.
 */
class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    // ---------- catalogue handed over by the photo-mode page ----------
    class Variant(val label: String, val png: ByteArray) {
        val bitmap: Bitmap? by lazy { BitmapFactory.decodeByteArray(png, 0, png.size) }
    }

    class Product(val id: String, val name: String, val wMm: Float, val hMm: Float, val variants: List<Variant>)

    class Item(
        val product: Product,
        var variant: Int,
        var anchor: Anchor,
        var up: FloatArray?,          // fixed "up" direction for floors/ceilings, null on walls
        var rot: Int = 0,
        val model: FloatArray = FloatArray(16),
        var visible: Boolean = false
    )

    private var products: List<Product> = emptyList()
    private var current = 0
    private val variantOf = HashMap<String, Int>()

    // ---------- AR + GL state ----------
    private lateinit var surface: GLSurfaceView
    private var session: Session? = null
    private var installRequested = false
    private var textureGiven = false
    private var camTex = 0
    private var viewW = 1
    private var viewH = 1
    private val items = ArrayList<Item>()
    private var selected: Item? = null
    private var dragging: Item? = null
    private val glQueue = ConcurrentLinkedQueue<() -> Unit>()
    private val textures = HashMap<String, Int>()
    @Volatile private var captureRequested = false

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    private var bgProg = 0
    private var colProg = 0
    private var texProg = 0
    private val bgPos: FloatBuffer = GlUtil.floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val bgTexIn: FloatBuffer = GlUtil.floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val bgTex: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val quadUv: FloatBuffer = GlUtil.floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private var scratch: FloatBuffer = ByteBuffer.allocateDirect(4096 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // ---------- views ----------
    private lateinit var hint: TextView
    private lateinit var info: TextView
    private lateinit var strip: LinearLayout
    private lateinit var btnColor: TextView
    private var lastHint = ""
    private var lastInfo = ""
    private var lastUiMs = 0L

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) {
            Toast.makeText(this, "الكاميرا اللايف محتاجة إذن الكاميرا.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        products = loadProducts()
        buildUi()
        if (products.isEmpty()) setHint("ما وصلتش قائمة المنتجات. ارجع وافتح الكاميرا اللايف تاني.")
    }

    // ---------- catalogue ----------
    private fun loadProducts(): List<Product> {
        return try {
            val j = JSONObject(File(cacheDir, "ar_products.json").readText())
            val arr = j.getJSONArray("products")
            val out = ArrayList<Product>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val vs = p.getJSONArray("variants")
                val list = ArrayList<Variant>()
                for (k in 0 until vs.length()) {
                    val v = vs.getJSONObject(k)
                    list.add(Variant(v.optString("label"), Base64.decode(v.getString("png"), Base64.DEFAULT)))
                }
                val w = p.getDouble("w").toFloat()
                val h = p.getDouble("h").toFloat()
                if (list.isNotEmpty() && w > 0 && h > 0) {
                    out.add(Product(p.getString("id"), p.optString("name"), w, h, list))
                    variantOf[p.getString("id")] = p.optInt("variant", 0).coerceIn(0, list.size - 1)
                }
            }
            val start = j.optString("start")
            current = out.indexOfFirst { it.id == start }.coerceAtLeast(0)
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun pill(bg: Int, stroke: Int = 0, radius: Float = 14f) = GradientDrawable().apply {
        setColor(bg); cornerRadius = dp(radius)
        if (stroke != 0) setStroke(dp(2f).toInt(), stroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = FrameLayout(this)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ArActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))

        val ink = ContextCompat.getColor(this, R.color.ink)
        val laser = ContextCompat.getColor(this, R.color.laser)

        hint = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            background = pill(0xCC0F1114.toInt())
            setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
            text = "جاري تشغيل الكاميرا…"
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(16f).toInt(); leftMargin = dp(16f).toInt(); rightMargin = dp(16f).toInt()
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6181A1E.toInt())
            setPadding(0, dp(8f).toInt(), 0, dp(10f).toInt())
        }
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f
            setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), dp(6f).toInt())
            typeface = Typeface.DEFAULT_BOLD
            text = "اختار منتج من تحت، وبعدين دوس على الحيطة أو السقف."
        }
        bottom.addView(info)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), dp(8f).toInt())
        }
        fun action(label: String, accent: Boolean = false, onClick: () -> Unit): TextView {
            val b = TextView(this).apply {
                text = label; textSize = 13.5f; gravity = Gravity.CENTER
                setTextColor(if (accent) Color.WHITE else ink)
                typeface = Typeface.DEFAULT_BOLD
                background = pill(if (accent) laser else Color.WHITE, 0, 10f)
                minHeight = dp(44f).toInt()
                setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
                setOnClickListener { onClick() }
            }
            actions.addView(b, LinearLayout.LayoutParams(0, dp(44f).toInt(), 1f).apply {
                marginStart = dp(3f).toInt(); marginEnd = dp(3f).toInt()
            })
            return b
        }
        action("لف 90°") { glQueue.add { selected?.let { it.rot = (it.rot + 90) % 360 } } }
        btnColor = action("اللون") { cycleColor() }
        action("امسح") { glQueue.add { selected?.let { removeItem(it) } } }
        action("امسح الكل") { glQueue.add { for (it in ArrayList(items)) removeItem(it) } }
        action("صوّر", accent = true) { captureRequested = true }
        bottom.addView(actions)

        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
        }
        scroller.addView(strip)
        bottom.addView(scroller)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        renderStrip()

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var moved = false
        surface.setOnTouchListener { _, e ->
            val x = e.x
            val y = e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y; moved = false
                    glQueue.add { onDown(x, y) }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(x - downX, y - downY) > slop) moved = true
                    if (moved) glQueue.add { onMove(x, y) }
                }
                MotionEvent.ACTION_UP -> {
                    val wasMoved = moved
                    glQueue.add { onUp(x, y, wasMoved) }
                }
                MotionEvent.ACTION_CANCEL -> glQueue.add { dragging = null }
            }
            true
        }
    }

    private fun mmText(p: Product): String {
        fun cm(v: Float) = if (abs(v / 10f - (v / 10f).roundToInt()) < 0.05f) "${(v / 10f).roundToInt()}" else String.format(java.util.Locale.US, "%.1f", v / 10f)
        return if (abs(p.wMm - p.hMm) < 0.5f) "${cm(p.wMm)} سم" else "${cm(p.wMm)} × ${cm(p.hMm)} سم"
    }

    private fun renderStrip() {
        strip.removeAllViews()
        val ink = ContextCompat.getColor(this, R.color.ink)
        val laser = ContextCompat.getColor(this, R.color.laser)
        products.forEachIndexed { i, p ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = pill(Color.WHITE, if (i == current) laser else 0, 10f)
                setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
                setOnClickListener { current = i; renderStrip(); updateColorButton() }
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val v = p.variants[variantOf[p.id] ?: 0]
                setImageBitmap(aspectBitmap(p, v))
                setBackgroundColor(0xFFA3ABB4.toInt())
            }
            box.addView(img, LinearLayout.LayoutParams(dp(84f).toInt(), dp(44f).toInt()))
            box.addView(TextView(this).apply {
                text = p.name; textSize = 11.5f; setTextColor(ink); gravity = Gravity.CENTER
                maxLines = 2; typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(dp(84f).toInt(), -2))
            box.addView(TextView(this).apply {
                text = mmText(p); textSize = 10.5f; setTextColor(0xFF56606B.toInt()); gravity = Gravity.CENTER
            })
            strip.addView(box, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8f).toInt() })
        }
        updateColorButton()
    }

    /** The page sends square-ish power-of-two pictures; this restores the real proportions for the thumbnail. */
    private fun aspectBitmap(p: Product, v: Variant): Bitmap? {
        val b = v.bitmap ?: return null
        val long = 200f
        val w = if (p.wMm >= p.hMm) long else long * p.wMm / p.hMm
        val h = if (p.hMm > p.wMm) long else long * p.hMm / p.wMm
        return Bitmap.createScaledBitmap(b, max(8, w.roundToInt()), max(8, h.roundToInt()), true)
    }

    private fun updateColorButton() {
        val p = products.getOrNull(current)
        val n = p?.variants?.size ?: 0
        btnColor.alpha = if (n > 1) 1f else 0.4f
        btnColor.isEnabled = n > 1
        if (p != null && n > 1) btnColor.text = p.variants[variantOf[p.id] ?: 0].label.ifBlank { "اللون" }
        else btnColor.text = "اللون"
    }

    private fun cycleColor() {
        val p = products.getOrNull(current) ?: return
        if (p.variants.size < 2) return
        val next = ((variantOf[p.id] ?: 0) + 1) % p.variants.size
        variantOf[p.id] = next
        glQueue.add {
            for (it in items) if (it.product === p && (selected == null || it === selected || it.product === selected?.product)) it.variant = next
        }
        renderStrip()
    }

    // ---------- lifecycle ----------
    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            askCamera.launch(Manifest.permission.CAMERA)
            return
        }
        if (session == null) {
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    else -> {}
                }
                val s = Session(this)
                val c = Config(s)
                c.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                c.focusMode = Config.FocusMode.AUTO
                c.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                c.lightEstimationMode = Config.LightEstimationMode.DISABLED
                if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) c.depthMode = Config.DepthMode.AUTOMATIC
                s.configure(c)
                session = s
                textureGiven = false
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "محتاج تثبّت خدمات Google Play للواقع المعزز (ARCore) من المتجر."
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "الكاميرا اللايف محتاجة تثبيت ARCore. تقدر تستخدم وضع الصورة."
            } catch (e: UnavailableApkTooOldException) {
                message = "حدّث خدمات Google Play للواقع المعزز (ARCore) من المتجر."
            } catch (e: UnavailableSdkTooOldException) {
                message = "التطبيق محتاج تحديث."
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "الموبايل ده مش بيدعم الكاميرا اللايف (ARCore). استخدم وضع الصورة."
            } catch (e: Exception) {
                message = "الكاميرا اللايف ما اشتغلتش: ${e.message}"
            }
            if (message != null) {
                setHint(message)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            setHint("الكاميرا مشغولة. اقفل أي تطبيق تاني بيستخدمها وارجع.")
            session = null
            return
        }
        surface.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            surface.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    // ---------- GL ----------
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        camTex = GlUtil.externalTexture()
        textureGiven = false
        textures.clear()
        bgProg = GlUtil.program(
            """
            attribute vec4 a_Pos; attribute vec2 a_Uv; varying vec2 v_Uv;
            void main(){ gl_Position = a_Pos; v_Uv = a_Uv; }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 v_Uv; uniform samplerExternalOES u_Tex;
            void main(){ gl_FragColor = texture2D(u_Tex, v_Uv); }
            """.trimIndent()
        )
        colProg = GlUtil.program(
            """
            uniform mat4 u_Mvp; attribute vec4 a_Pos;
            void main(){ gl_Position = u_Mvp * a_Pos; gl_PointSize = 6.0; }
            """.trimIndent(),
            """
            precision mediump float; uniform vec4 u_Color;
            void main(){ gl_FragColor = u_Color; }
            """.trimIndent()
        )
        texProg = GlUtil.program(
            """
            uniform mat4 u_Mvp; attribute vec4 a_Pos; attribute vec2 a_Uv; varying vec2 v_Uv;
            void main(){ gl_Position = u_Mvp * a_Pos; v_Uv = a_Uv; }
            """.trimIndent(),
            """
            precision mediump float; varying vec2 v_Uv; uniform sampler2D u_Tex;
            void main(){ gl_FragColor = texture2D(u_Tex, v_Uv); }
            """.trimIndent()
        )
    }

    @Suppress("DEPRECATION")
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES20.glViewport(0, 0, width, height)
        val rotation = windowManager.defaultDisplay.rotation
        session?.setDisplayGeometry(rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!textureGiven) {
            s.setCameraTextureName(camTex)
            @Suppress("DEPRECATION")
            s.setDisplayGeometry(windowManager.defaultDisplay.rotation, viewW, viewH)
            textureGiven = true
        }
        val frame: Frame = try {
            s.update()
        } catch (e: Exception) {
            return
        }
        val camera = frame.camera
        drawBackground(frame)

        camera.getProjectionMatrix(proj, 0, 0.05f, 60f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val tracking = camera.trackingState == TrackingState.TRACKING
        lastFrame = frame
        while (true) {
            val job = glQueue.poll() ?: break
            runCatching { job.invoke() }
        }

        val planes = s.getAllTrackables(Plane::class.java).filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
        if (tracking && !captureRequested) drawPlanes(planes)
        if (tracking) drawItems(camera)

        if (captureRequested) {
            captureRequested = false
            capture()
        }
        updateTexts(camera, planes.size)
    }

    private fun drawBackground(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            bgTexIn.position(0)
            bgTex.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, bgTexIn,
                Coordinates2d.TEXTURE_NORMALIZED, bgTex
            )
        }
        if (frame.timestamp == 0L) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(bgProg, "u_Tex"), 0)
        val aPos = GLES20.glGetAttribLocation(bgProg, "a_Pos")
        val aUv = GLES20.glGetAttribLocation(bgProg, "a_Uv")
        bgPos.position(0); bgTex.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, bgPos)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, bgTex)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glDepthMask(true)
    }

    private fun scratchOf(n: Int): FloatBuffer {
        if (scratch.capacity() < n) scratch = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        scratch.clear()
        return scratch
    }

    private fun drawColor(model: FloatArray, xyz: FloatArray, count: Int, mode: Int, r: Float, g: Float, b: Float, a: Float) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUseProgram(colProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colProg, "u_Mvp"), 1, false, mvp, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(colProg, "u_Color"), r, g, b, a)
        val buf = scratchOf(count * 3)
        buf.put(xyz, 0, count * 3).position(0)
        val aPos = GLES20.glGetAttribLocation(colProg, "a_Pos")
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glDrawArrays(mode, 0, count)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun drawPlanes(planes: List<Plane>) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        val model = FloatArray(16)
        for (p in planes) {
            val poly = p.polygon ?: continue
            val n = poly.limit() / 2
            if (n < 3) continue
            p.centerPose.toMatrix(model, 0)
            val fan = FloatArray((n + 2) * 3)
            for (i in 0..n) {
                val k = i % n
                fan[(i + 1) * 3] = poly.get(k * 2)
                fan[(i + 1) * 3 + 2] = poly.get(k * 2 + 1)
            }
            val vertical = p.type == Plane.Type.VERTICAL
            if (vertical) drawColor(model, fan, n + 2, GLES20.GL_TRIANGLE_FAN, 0.85f, 0.12f, 0.16f, 0.16f)
            else drawColor(model, fan, n + 2, GLES20.GL_TRIANGLE_FAN, 1f, 1f, 1f, 0.14f)
            val loop = FloatArray(n * 3)
            for (i in 0 until n) { loop[i * 3] = poly.get(i * 2); loop[i * 3 + 2] = poly.get(i * 2 + 1) }
            GLES20.glLineWidth(3f)
            drawColor(model, loop, n, GLES20.GL_LINE_LOOP, 0.85f, 0.12f, 0.16f, 0.75f)
            // a 10 cm dot grid so the scale of the surface reads at a glance
            val dots = ArrayList<Float>()
            val ext = max(p.extentX, p.extentZ) / 2f
            var gx = -ext
            while (gx <= ext && dots.size < 3000) {
                var gz = -ext
                while (gz <= ext && dots.size < 3000) {
                    if (insidePoly(poly, n, gx, gz)) { dots.add(gx); dots.add(0f); dots.add(gz) }
                    gz += 0.1f
                }
                gx += 0.1f
            }
            if (dots.isNotEmpty()) drawColor(model, dots.toFloatArray(), dots.size / 3, GLES20.GL_POINTS, 1f, 1f, 1f, 0.55f)
        }
    }

    private fun insidePoly(poly: FloatBuffer, n: Int, x: Float, z: Float): Boolean {
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly.get(i * 2); val zi = poly.get(i * 2 + 1)
            val xj = poly.get(j * 2); val zj = poly.get(j * 2 + 1)
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun textureFor(it: Item): Int {
        val v = it.product.variants[it.variant.coerceIn(0, it.product.variants.size - 1)]
        val key = it.product.id + "#" + it.variant
        textures[key]?.let { t -> return t }
        val bmp = v.bitmap ?: return 0
        val t = GlUtil.bitmapTexture(bmp)
        textures[key] = t
        return t
    }

    // ---------- item placement maths ----------
    private fun norm(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (l < 1e-6f) v else floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
    )

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** Builds the item's model matrix: x = right, y = up along the surface, z = surface normal. */
    private fun computeModel(it: Item) {
        val pose = it.anchor.pose
        val n = norm(pose.yAxis)
        val upHint = it.up ?: floatArrayOf(0f, 1f, 0f)
        var u = floatArrayOf(upHint[0] - n[0] * dot(upHint, n), upHint[1] - n[1] * dot(upHint, n), upHint[2] - n[2] * dot(upHint, n))
        if (sqrt(dot(u, u)) < 1e-3f) u = norm(cross(n, floatArrayOf(1f, 0f, 0f))) else u = norm(u)
        var r = norm(cross(u, n))
        val a = Math.toRadians(it.rot.toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val r2 = floatArrayOf(r[0] * ca + u[0] * sa, r[1] * ca + u[1] * sa, r[2] * ca + u[2] * sa)
        val u2 = floatArrayOf(-r[0] * sa + u[0] * ca, -r[1] * sa + u[1] * ca, -r[2] * sa + u[2] * ca)
        r = r2; u = u2
        val t = pose.translation
        val m = it.model
        m[0] = r[0]; m[1] = r[1]; m[2] = r[2]; m[3] = 0f
        m[4] = u[0]; m[5] = u[1]; m[6] = u[2]; m[7] = 0f
        m[8] = n[0]; m[9] = n[1]; m[10] = n[2]; m[11] = 0f
        m[12] = t[0] + n[0] * 0.002f; m[13] = t[1] + n[1] * 0.002f; m[14] = t[2] + n[2] * 0.002f; m[15] = 1f
    }

    private fun halfSize(it: Item): Pair<Float, Float> = Pair(it.product.wMm / 2000f, it.product.hMm / 2000f)

    private fun drawItems(camera: Camera) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        for (it in items) {
            it.visible = it.anchor.trackingState == TrackingState.TRACKING
            if (!it.visible) continue
            computeModel(it)
            val (hw, hh) = halfSize(it)
            val tex = textureFor(it)
            if (tex == 0) continue
            Matrix.multiplyMM(mvp, 0, vp, 0, it.model, 0)
            GLES20.glUseProgram(texProg)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(texProg, "u_Mvp"), 1, false, mvp, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(texProg, "u_Tex"), 0)
            val pos = GlUtil.floats(-hw, hh, 0f, hw, hh, 0f, -hw, -hh, 0f, hw, -hh, 0f)
            quadUv.position(0)
            val aPos = GLES20.glGetAttribLocation(texProg, "a_Pos")
            val aUv = GLES20.glGetAttribLocation(texProg, "a_Uv")
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, pos)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, quadUv)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
            if (it === selected && !captureRequested) {
                val pad = 0.006f
                val box = floatArrayOf(-hw - pad, hh + pad, 0.001f, hw + pad, hh + pad, 0.001f, hw + pad, -hh - pad, 0.001f, -hw - pad, -hh - pad, 0.001f)
                GLES20.glLineWidth(4f)
                drawColor(it.model, box, 4, GLES20.GL_LINE_LOOP, 0.85f, 0.12f, 0.16f, 1f)
            }
        }
    }

    // ---------- touch handling (runs on the GL thread) ----------
    private fun project(m: FloatArray, x: Float, y: Float): FloatArray? {
        val out = FloatArray(4)
        Matrix.multiplyMM(mvp, 0, vp, 0, m, 0)
        Matrix.multiplyMV(out, 0, mvp, 0, floatArrayOf(x, y, 0f, 1f), 0)
        if (out[3] <= 0f) return null
        return floatArrayOf((out[0] / out[3] + 1f) / 2f * viewW, (1f - out[1] / out[3]) / 2f * viewH)
    }

    private fun itemAt(x: Float, y: Float): Item? {
        var best: Item? = null
        var bestD = Float.MAX_VALUE
        for (it in items) {
            if (!it.visible) continue
            val (hw, hh) = halfSize(it)
            val c = project(it.model, 0f, 0f) ?: continue
            val corners = listOf(project(it.model, -hw, hh), project(it.model, hw, hh), project(it.model, hw, -hh), project(it.model, -hw, -hh))
            var reach = dp(36f)
            if (corners.all { c2 -> c2 != null }) {
                for (c2 in corners) reach = max(reach, hypot(c2!![0] - c[0], c2[1] - c[1]))
            }
            val d = hypot(x - c[0], y - c[1])
            if (d <= reach && d < bestD) { bestD = d; best = it }
        }
        return best
    }

    private fun goodHit(frame: Frame, x: Float, y: Float): HitResult? {
        for (hit in frame.hitTest(x, y)) {
            val tr = hit.trackable
            val ok = when (tr) {
                is Plane -> tr.isPoseInPolygon(hit.hitPose) && cameraInFront(hit, frame)
                is DepthPoint -> true
                is Point -> tr.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
            if (ok) return hit
        }
        return null
    }

    /** True when the phone is on the side of the surface its normal points to (in front of the wall, under the ceiling). */
    private fun cameraInFront(hit: HitResult, frame: Frame): Boolean {
        val n = hit.hitPose.yAxis
        val t = hit.hitPose.translation
        val c = frame.camera.pose.translation
        return (c[0] - t[0]) * n[0] + (c[1] - t[1]) * n[1] + (c[2] - t[2]) * n[2] > 0f
    }

    private var lastFrame: Frame? = null

    private fun currentFrame(): Frame? = lastFrame

    private fun upFor(hit: HitResult, frame: Frame): FloatArray? {
        val n = norm(hit.hitPose.yAxis)
        if (abs(n[1]) < 0.7f) return null
        // floors and ceilings: the picture's top points away from the person holding the phone
        val z = frame.camera.pose.zAxis
        val fwd = floatArrayOf(-z[0], 0f, -z[2])
        val camUp = frame.camera.pose.yAxis
        val pick = if (hypot(fwd[0], fwd[2]) > 0.2f) fwd else floatArrayOf(camUp[0], 0f, camUp[2])
        return norm(pick)
    }

    private fun onDown(x: Float, y: Float) {
        dragging = itemAt(x, y)
        if (dragging != null) selected = dragging
    }

    private fun onMove(x: Float, y: Float) {
        val it = dragging ?: return
        val frame = currentFrame() ?: return
        val hit = goodHit(frame, x, y) ?: return
        val old = it.anchor
        it.anchor = hit.createAnchor()
        it.up = upFor(hit, frame)
        old.detach()
    }

    private fun onUp(x: Float, y: Float, moved: Boolean) {
        val wasDragging = dragging
        dragging = null
        if (moved || wasDragging != null) return
        val frame = currentFrame() ?: return
        val p = products.getOrNull(current) ?: return
        val hit = goodHit(frame, x, y)
        if (hit == null) {
            uiToast("مفيش سطح متعرّف عليه هنا. حرّك الموبايل لحد ما تظهر الشبكة على الحيطة أو السقف.")
            return
        }
        val it = Item(p, variantOf[p.id] ?: 0, hit.createAnchor(), upFor(hit, frame))
        items.add(it)
        selected = it
    }

    private fun removeItem(it: Item) {
        it.anchor.detach()
        items.remove(it)
        if (selected === it) selected = null
    }

    // ---------- status texts ----------
    private fun setHint(t: String) = runOnUiThread {
        hint.text = t
        hint.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun uiToast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    private fun updateTexts(camera: Camera, planeCount: Int) {
        val now = System.currentTimeMillis()
        if (now - lastUiMs < 250) return
        lastUiMs = now
        val h = when {
            camera.trackingState != TrackingState.TRACKING -> when (camera.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "النور ضعيف. نوّر المكان أكتر."
                TrackingFailureReason.EXCESSIVE_MOTION -> "حرّك الموبايل أبطأ."
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "السطح سادة أوي. صوّب على حتة فيها تفاصيل (برواز، فيشة، ركن)، أو الصق شريط لزق على الحيطة، أو استخدم وضع الصورة."
                TrackingFailureReason.CAMERA_UNAVAILABLE -> "الكاميرا مش متاحة دلوقتي."
                else -> "حرّك الموبايل ببطء يمين وشمال…"
            }
            planeCount == 0 && items.isEmpty() -> "وجّه الكاميرا للحيطة أو السقف وحرّكها ببطء لحد ما تظهر شبكة نقط."
            items.isEmpty() -> "دوس على الشبكة عشان تحط المنتج."
            else -> ""
        }
        if (h != lastHint) { lastHint = h; setHint(h) }

        val sel = selected
        val text = if (sel == null) {
            val p = products.getOrNull(current)
            if (p == null) "" else "${p.name} · ${mmText(p)} — دوس على الحيطة أو السقف"
        } else {
            val t = sel.anchor.pose.translation
            var near = Float.MAX_VALUE
            for (o in items) {
                if (o === sel) continue
                val q = o.anchor.pose.translation
                near = minOf(near, sqrt((t[0] - q[0]) * (t[0] - q[0]) + (t[1] - q[1]) * (t[1] - q[1]) + (t[2] - q[2]) * (t[2] - q[2])))
            }
            val c = camera.pose.translation
            val far = sqrt((t[0] - c[0]) * (t[0] - c[0]) + (t[1] - c[1]) * (t[1] - c[1]) + (t[2] - c[2]) * (t[2] - c[2]))
            val label = sel.product.variants.getOrNull(sel.variant)?.label.orEmpty()
            buildString {
                append(sel.product.name)
                if (label.isNotBlank() && sel.product.variants.size > 1) append(" · ").append(label)
                append(" · ").append(mmText(sel.product))
                if (near < Float.MAX_VALUE) append("\nلأقرب قطعة: ").append(lenText(near))
                append("   بعدك عنه: ").append(lenText(far))
                append("   على الصورة: ").append(items.size)
            }
        }
        if (text != lastInfo) { lastInfo = text; runOnUiThread { info.text = text } }
    }

    private fun lenText(m: Float): String = if (m >= 1f) String.format(java.util.Locale.US, "%.2f م", m) else "${(m * 100f).roundToInt()} سم"

    // ---------- capture ----------
    private fun capture() {
        val w = viewW
        val h = viewH
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val counts = LinkedHashMap<String, Int>()
        for (it in items) {
            val label = it.product.variants.getOrNull(it.variant)?.label.orEmpty()
            val key = it.product.name + (if (label.isNotBlank() && it.product.variants.size > 1) "، $label" else "") + "، " + mmText(it.product)
            counts[key] = (counts[key] ?: 0) + 1
        }
        Thread {
            try {
                buf.position(0)
                val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(buf)
                val flip = GMatrix().apply { preScale(1f, -1f) }
                val shot = Bitmap.createBitmap(raw, 0, 0, w, h, flip, false)
                raw.recycle()
                val out = withList(shot, counts)
                val bos = ByteArrayOutputStream()
                out.compress(Bitmap.CompressFormat.JPEG, 92, bos)
                val where = MainActivity.saveJpeg(this, bos.toByteArray(), "panda_live_${System.currentTimeMillis()}.jpg")
                uiToast("الصورة اتحفظت في $where")
            } catch (e: Exception) {
                uiToast("الصورة ما اتحفظتش: ${e.message}")
            }
        }.start()
    }

    private fun withList(shot: Bitmap, counts: Map<String, Int>): Bitmap {
        if (counts.isEmpty()) return shot
        val w = shot.width
        val fs = max(28f, w * 0.032f)
        val lh = fs * 1.6f
        val pad = w * 0.04f
        val band = (pad * 2 + fs * 1.5f + counts.size * lh).toInt()
        val out = Bitmap.createBitmap(w, shot.height + band, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(shot, 0f, 0f, null)
        c.drawColor(Color.WHITE, android.graphics.PorterDuff.Mode.DST_OVER)
        val bg = Paint().apply { color = Color.WHITE }
        c.drawRect(0f, shot.height.toFloat(), w.toFloat(), out.height.toFloat(), bg)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF15181D.toInt(); textSize = fs * 1.1f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF15181D.toInt(); textSize = fs; textAlign = Paint.Align.RIGHT }
        var y = shot.height + pad + fs
        c.drawText("المنتجات على الصورة", w - pad, y, title)
        y += fs * 0.4f
        for ((k, n) in counts) {
            y += lh
            c.drawText("$n × $k", w - pad, y, line)
        }
        shot.recycle()
        return out
    }

}

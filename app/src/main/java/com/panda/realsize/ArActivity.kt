package com.panda.realsize

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live camera mode on a physically based 3D engine (Filament through SceneView).
 *
 * ARCore finds walls and ceilings. Each product is a real 3D model built from its photo at its
 * catalogue size (see [ProductModels]): it has thickness, casts a soft shadow, reflects the room
 * (ARCore Environmental HDR light estimation), and its lamp glows when the light is on.
 */
class ArActivity : AppCompatActivity() {

    // ---------- catalogue handed over by the photo-mode page ----------
    class Variant(val label: String, private val imageBytes: ByteArray) {
        val bitmap: Bitmap? by lazy { BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) }
    }

    class Product(
        val id: String,
        val name: String,
        val widthMm: Float,
        val heightMm: Float,
        val category: String,
        val round: Boolean,
        val lensFraction: Float,
        val variants: List<Variant>,
        val modelAsset: String            // ready-made GLB in assets/www/models (real CAD model), or blank
    ) {
        val spec = ProductModels.Spec(widthMm, heightMm, category, round, lensFraction)
    }

    class PlacedItem(
        val product: Product,
        var variant: Int,
        var anchor: Anchor?,
        var upHint: FloatArray?,           // fixed "up" direction on floors and ceilings, null on walls
        val rootNode: Node,
        var rotationDegrees: Float = 0f,
        var modelNode: ModelNode? = null,
        var modelKey: String = "",
        var frameNode: ModelNode? = null,
        var shadowNode: ModelNode? = null,
        var dragPose: Pose? = null,
        var shownTranslation: FloatArray? = null,
        val worldMatrix: FloatArray = FloatArray(16),
        var onScreen: Boolean = false
    )

    private var products: List<Product> = emptyList()
    private val variantOf = HashMap<String, Int>()
    private var currentProduct = 0
    private var lightState = ProductModels.LightState.WARM
    private val lightNames = mapOf(
        ProductModels.LightState.WARM to "نور وورم",
        ProductModels.LightState.WHITE to "نور أبيض",
        ProductModels.LightState.OFF to "مطفي"
    )

    // ---------- AR scene ----------
    private lateinit var sceneView: ARSceneView
    private val items = ArrayList<PlacedItem>()
    private var selectedItem: PlacedItem? = null
    private var draggedItem: PlacedItem? = null
    private var lastFrame: Frame? = null
    private var depthSupported = false
    private var occlusionOn = false
    private var capturing = false
    private var activityGone = false

    private val modelBuilder: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelBytesCache = HashMap<String, ByteArray>()
    private val modelsBeingBuilt = HashSet<String>()

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)

    // ---------- views ----------
    private lateinit var hintView: TextView
    private lateinit var infoView: TextView
    private lateinit var productStrip: LinearLayout
    private lateinit var colorButton: TextView
    private lateinit var lightButton: TextView
    private lateinit var occlusionChip: TextView
    private var lastHint = ""
    private var lastInfo = ""
    private var lastTextUpdateMs = 0L

    // ---------- touch state ----------
    private var touchSlopPx = 0
    private var downX = 0f
    private var downY = 0f
    private var fingerMoved = false
    private var twoFingerAngle: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        products = loadProducts()
        touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop
        buildUi()
        if (products.isEmpty()) setHint("ما وصلتش قائمة المنتجات. ارجع وافتح الكاميرا اللايف تاني.")
    }

    override fun onDestroy() {
        activityGone = true
        modelBuilder.shutdownNow()
        super.onDestroy()
    }

    // ---------- catalogue ----------
    private fun loadProducts(): List<Product> {
        return try {
            val payload = JSONObject(File(cacheDir, "ar_products.json").readText())
            val productArray = payload.getJSONArray("products")
            val loaded = ArrayList<Product>()
            for (productIndex in 0 until productArray.length()) {
                val entry = productArray.getJSONObject(productIndex)
                val variantArray = entry.getJSONArray("variants")
                val variants = ArrayList<Variant>()
                for (variantIndex in 0 until variantArray.length()) {
                    val variantEntry = variantArray.getJSONObject(variantIndex)
                    variants.add(Variant(variantEntry.optString("label"), Base64.decode(variantEntry.getString("png"), Base64.DEFAULT)))
                }
                val widthMm = entry.getDouble("w").toFloat()
                val heightMm = entry.getDouble("h").toFloat()
                if (variants.isEmpty() || widthMm <= 0f || heightMm <= 0f) continue
                val id = entry.getString("id")
                loaded.add(
                    Product(
                        id = id,
                        name = entry.optString("name"),
                        widthMm = widthMm,
                        heightMm = heightMm,
                        category = entry.optString("cat", "mine"),
                        round = entry.optString("shape") == "round",
                        lensFraction = entry.optDouble("light", 0.0).toFloat(),
                        variants = variants,
                        modelAsset = entry.optString("model")
                    )
                )
                variantOf[id] = entry.optInt("variant", 0).coerceIn(0, variants.size - 1)
            }
            lightState = when (payload.optString("light")) {
                "white" -> ProductModels.LightState.WHITE
                "off" -> ProductModels.LightState.OFF
                else -> ProductModels.LightState.WARM
            }
            val startId = payload.optString("start")
            currentProduct = loaded.indexOfFirst { it.id == startId }.coerceAtLeast(0)
            loaded
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- UI ----------
    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun pill(background: Int, stroke: Int = 0, radius: Float = 14f) = GradientDrawable().apply {
        setColor(background); cornerRadius = dp(radius)
        if (stroke != 0) setStroke(dp(2f).toInt(), stroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = FrameLayout(this)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        sceneView = ARSceneView(
            context = this,
            sharedLifecycle = lifecycle,
            sharedActivity = this,
            sessionConfiguration = { session, config -> configureSession(session, config) },
            onSessionCreated = { session -> onSessionReady(session) },
            onSessionFailed = { exception -> onSessionError(exception) },
            onSessionUpdated = { _, frame -> onArFrame(frame) }
        )
        setUpRendering()
        root.addView(sceneView, FrameLayout.LayoutParams(-1, -1))

        val ink = ContextCompat.getColor(this, R.color.ink)
        val laser = ContextCompat.getColor(this, R.color.laser)

        hintView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            background = pill(0xCC0F1114.toInt())
            setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
            text = "جاري تشغيل الكاميرا…"
        }
        root.addView(hintView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(16f).toInt(); leftMargin = dp(16f).toInt(); rightMargin = dp(16f).toInt()
        })

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6181A1E.toInt())
            setPadding(0, dp(8f).toInt(), 0, dp(10f).toInt())
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), dp(6f).toInt())
        }
        fun chip(label: String, onClick: () -> Unit): TextView {
            val chipView = TextView(this).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = pill(0x33FFFFFF, 0, 16f)
                setPadding(dp(12f).toInt(), dp(5f).toInt(), dp(12f).toInt(), dp(5f).toInt())
                setOnClickListener { onClick() }
            }
            chipRow.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8f).toInt() })
            return chipView
        }
        chip("امسح الكل") { for (item in ArrayList(items)) removeItem(item) }
        occlusionChip = chip("الحجب: مقفول") { toggleOcclusion() }
        occlusionChip.visibility = View.GONE
        bottomPanel.addView(chipRow)

        infoView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f
            setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), dp(6f).toInt())
            typeface = Typeface.DEFAULT_BOLD
            text = "اختار منتج من تحت، وبعدين دوس على الحيطة أو السقف."
        }
        bottomPanel.addView(infoView)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), dp(8f).toInt())
        }
        fun action(label: String, accent: Boolean = false, onClick: () -> Unit): TextView {
            val button = TextView(this).apply {
                text = label; textSize = 13.5f; gravity = Gravity.CENTER
                setTextColor(if (accent) Color.WHITE else ink)
                typeface = Typeface.DEFAULT_BOLD
                background = pill(if (accent) laser else Color.WHITE, 0, 10f)
                minHeight = dp(44f).toInt()
                setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
                setOnClickListener { onClick() }
            }
            actionRow.addView(button, LinearLayout.LayoutParams(0, dp(44f).toInt(), 1f).apply {
                marginStart = dp(3f).toInt(); marginEnd = dp(3f).toInt()
            })
            return button
        }
        action("لف 90°") { selectedItem?.let { item -> item.rotationDegrees = (item.rotationDegrees + 90f) % 360f } }
        colorButton = action("اللون") { cycleColor() }
        lightButton = action(lightNames.getValue(lightState)) { cycleLight() }
        action("امسح") { selectedItem?.let { item -> removeItem(item) } }
        action("صوّر", accent = true) { capture() }
        bottomPanel.addView(actionRow)

        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        productStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
        }
        scroller.addView(productStrip)
        bottomPanel.addView(scroller)
        root.addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // keep the hint and the buttons clear of the status and navigation bars
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (hintView.layoutParams as FrameLayout.LayoutParams).topMargin = dp(16f).toInt() + bars.top
            hintView.requestLayout()
            bottomPanel.setPadding(0, dp(8f).toInt(), 0, dp(10f).toInt() + bars.bottom)
            insets
        }
        setContentView(root)
        renderStrip()

        sceneView.onTouchEvent = { event, _ ->
            handleTouch(event)
            true
        }
    }

    private fun mmText(product: Product): String {
        fun centimetres(valueMm: Float): String {
            val valueCm = valueMm / 10f
            return if (abs(valueCm - valueCm.roundToInt()) < 0.05f) "${valueCm.roundToInt()}" else String.format(Locale.US, "%.1f", valueCm)
        }
        return if (abs(product.widthMm - product.heightMm) < 0.5f) "${centimetres(product.widthMm)} سم"
        else "${centimetres(product.widthMm)} × ${centimetres(product.heightMm)} سم"
    }

    private fun renderStrip() {
        productStrip.removeAllViews()
        val ink = ContextCompat.getColor(this, R.color.ink)
        val laser = ContextCompat.getColor(this, R.color.laser)
        products.forEachIndexed { productIndex, product ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = pill(Color.WHITE, if (productIndex == currentProduct) laser else 0, 10f)
                setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
                setOnClickListener { currentProduct = productIndex; renderStrip() }
            }
            val thumbnail = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(aspectBitmap(product, product.variants[variantOf[product.id] ?: 0]))
                setBackgroundColor(0xFFA3ABB4.toInt())
            }
            box.addView(thumbnail, LinearLayout.LayoutParams(dp(84f).toInt(), dp(44f).toInt()))
            box.addView(TextView(this).apply {
                text = product.name; textSize = 11.5f; setTextColor(ink); gravity = Gravity.CENTER
                maxLines = 2; typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(dp(84f).toInt(), -2))
            box.addView(TextView(this).apply {
                text = mmText(product); textSize = 10.5f; setTextColor(0xFF56606B.toInt()); gravity = Gravity.CENTER
            })
            productStrip.addView(box, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8f).toInt() })
        }
        updateColorButton()
    }

    /** The page sends square-ish power-of-two pictures; this restores the real proportions for the thumbnail. */
    private fun aspectBitmap(product: Product, variant: Variant): Bitmap? {
        val source = variant.bitmap ?: return null
        val longSide = 200f
        val thumbWidth = if (product.widthMm >= product.heightMm) longSide else longSide * product.widthMm / product.heightMm
        val thumbHeight = if (product.heightMm > product.widthMm) longSide else longSide * product.heightMm / product.widthMm
        return Bitmap.createScaledBitmap(source, max(8, thumbWidth.roundToInt()), max(8, thumbHeight.roundToInt()), true)
    }

    private fun updateColorButton() {
        val product = products.getOrNull(currentProduct)
        val variantCount = product?.variants?.size ?: 0
        colorButton.alpha = if (variantCount > 1) 1f else 0.4f
        colorButton.isEnabled = variantCount > 1
        colorButton.text = if (product != null && variantCount > 1) {
            product.variants[variantOf[product.id] ?: 0].label.ifBlank { "اللون" }
        } else "اللون"
    }

    private fun cycleColor() {
        val product = products.getOrNull(currentProduct) ?: return
        if (product.variants.size < 2) return
        val nextVariant = ((variantOf[product.id] ?: 0) + 1) % product.variants.size
        variantOf[product.id] = nextVariant
        selectedItem?.let { item ->
            if (item.product === product) {
                item.variant = nextVariant
                requestModel(item)
            }
        }
        renderStrip()
    }

    private fun cycleLight() {
        lightState = when (lightState) {
            ProductModels.LightState.WARM -> ProductModels.LightState.WHITE
            ProductModels.LightState.WHITE -> ProductModels.LightState.OFF
            ProductModels.LightState.OFF -> ProductModels.LightState.WARM
        }
        lightButton.text = lightNames.getValue(lightState)
        for (item in items) requestModel(item)
    }

    private fun toggleOcclusion() {
        occlusionOn = !occlusionOn
        sceneView.cameraStream?.isDepthOcclusionEnabled = occlusionOn
        occlusionChip.text = if (occlusionOn) "الحجب: شغال" else "الحجب: مقفول"
        if (occlusionOn) uiToast("دلوقتي أي حاجة قدّام المنتج (زي إيدك) بتغطيه.")
    }

    // ---------- AR session and rendering ----------
    private fun configureSession(session: Session, config: Config) {
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
    }

    /** Shadows, anti-aliased edges and a soft bloom around lit lamps. */
    private fun setUpRendering() {
        sceneView.planeRenderer.planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_ALL
        sceneView.planeRenderer.isShadowReceiver = true
        sceneView.view.apply {
            setShadowingEnabled(true)
            multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
                enabled = true
                sampleCount = 4
            }
            bloomOptions = bloomOptions.apply {
                enabled = true
                strength = 0.12f
                threshold = true
            }
        }
    }

    private fun onSessionReady(session: Session) {
        depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        runOnUiThread {
            if (::occlusionChip.isInitialized) occlusionChip.visibility = if (depthSupported) View.VISIBLE else View.GONE
        }
    }

    private fun onSessionError(exception: Exception) {
        val message = when (exception) {
            is UnavailableArcoreNotInstalledException -> "محتاج تثبّت خدمات Google Play للواقع المعزز (ARCore) من المتجر."
            is UnavailableUserDeclinedInstallationException -> "الكاميرا اللايف محتاجة تثبيت ARCore. تقدر تستخدم وضع الصورة."
            is UnavailableApkTooOldException -> "حدّث خدمات Google Play للواقع المعزز (ARCore) من المتجر."
            is UnavailableSdkTooOldException -> "التطبيق محتاج تحديث."
            is UnavailableDeviceNotCompatibleException -> "الموبايل ده مش بيدعم الكاميرا اللايف (ARCore). استخدم وضع الصورة."
            is CameraNotAvailableException -> "الكاميرا مشغولة. اقفل أي تطبيق تاني بيستخدمها وارجع."
            is SecurityException -> "الكاميرا اللايف محتاجة إذن الكاميرا."
            else -> "الكاميرا اللايف ما اشتغلتش: ${exception.message}"
        }
        setHint(message)
        uiToast(message)
    }

    /** Runs once per camera frame on the main thread, before the scene is drawn. */
    private fun onArFrame(frame: Frame) {
        lastFrame = frame
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        if (tracking) {
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.05f, 60f)
            Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0)
        }
        for (item in items) updateItemPlacement(item, tracking)
        // the dotted surfaces help while placing; once something is placed they would spoil the picture
        sceneView.planeRenderer.isVisible = !capturing && (items.isEmpty() || draggedItem != null)
        updateTexts(frame)
    }

    private fun updateItemPlacement(item: PlacedItem, cameraTracking: Boolean) {
        val anchor = item.anchor
        val pose = item.dragPose ?: anchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.pose
        val visible = cameraTracking && pose != null
        item.onScreen = visible
        item.rootNode.isVisible = visible
        if (pose == null) return
        computeWorldMatrix(item, pose)
        item.rootNode.worldTransform = Mat4(
            Float4(item.worldMatrix[0], item.worldMatrix[1], item.worldMatrix[2], 0f),
            Float4(item.worldMatrix[4], item.worldMatrix[5], item.worldMatrix[6], 0f),
            Float4(item.worldMatrix[8], item.worldMatrix[9], item.worldMatrix[10], 0f),
            Float4(item.worldMatrix[12], item.worldMatrix[13], item.worldMatrix[14], 1f)
        )
        item.frameNode?.isVisible = item === selectedItem && !capturing
    }

    // ---------- placement maths ----------
    private fun normalize(vector: FloatArray): FloatArray {
        val length = sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])
        return if (length < 1e-6f) vector else floatArrayOf(vector[0] / length, vector[1] / length, vector[2] / length)
    }

    private fun cross(first: FloatArray, second: FloatArray) = floatArrayOf(
        first[1] * second[2] - first[2] * second[1],
        first[2] * second[0] - first[0] * second[2],
        first[0] * second[1] - first[1] * second[0]
    )

    private fun dot(first: FloatArray, second: FloatArray) = first[0] * second[0] + first[1] * second[1] + first[2] * second[2]

    /** Model axes: x = right, y = up along the surface, z = surface normal (towards the camera). */
    private fun computeWorldMatrix(item: PlacedItem, pose: Pose) {
        val normal = normalize(pose.yAxis)
        val upHint = item.upHint ?: floatArrayOf(0f, 1f, 0f)
        val alongNormal = dot(upHint, normal)
        var up = floatArrayOf(upHint[0] - normal[0] * alongNormal, upHint[1] - normal[1] * alongNormal, upHint[2] - normal[2] * alongNormal)
        up = if (sqrt(dot(up, up)) < 1e-3f) normalize(cross(normal, floatArrayOf(1f, 0f, 0f))) else normalize(up)
        var right = normalize(cross(up, normal))
        val angle = Math.toRadians(item.rotationDegrees.toDouble())
        val cosine = cos(angle).toFloat()
        val sine = sin(angle).toFloat()
        val rotatedRight = floatArrayOf(right[0] * cosine + up[0] * sine, right[1] * cosine + up[1] * sine, right[2] * cosine + up[2] * sine)
        val rotatedUp = floatArrayOf(-right[0] * sine + up[0] * cosine, -right[1] * sine + up[1] * cosine, -right[2] * sine + up[2] * cosine)
        right = rotatedRight
        up = rotatedUp

        val target = pose.translation
        val shown = item.shownTranslation
        val translation = if (item.dragPose != null && shown != null) {
            // follow the finger smoothly instead of jumping with every noisy hit test
            floatArrayOf(
                shown[0] + (target[0] - shown[0]) * 0.45f,
                shown[1] + (target[1] - shown[1]) * 0.45f,
                shown[2] + (target[2] - shown[2]) * 0.45f
            )
        } else target
        item.shownTranslation = translation

        val matrix = item.worldMatrix
        matrix[0] = right[0]; matrix[1] = right[1]; matrix[2] = right[2]; matrix[3] = 0f
        matrix[4] = up[0]; matrix[5] = up[1]; matrix[6] = up[2]; matrix[7] = 0f
        matrix[8] = normal[0]; matrix[9] = normal[1]; matrix[10] = normal[2]; matrix[11] = 0f
        matrix[12] = translation[0]; matrix[13] = translation[1]; matrix[14] = translation[2]; matrix[15] = 1f
    }

    private fun goodHit(frame: Frame, x: Float, y: Float): HitResult? {
        for (hit in frame.hitTest(x, y)) {
            val acceptable = when (val trackable = hit.trackable) {
                is Plane -> trackable.isPoseInPolygon(hit.hitPose) && cameraInFront(hit, frame)
                is DepthPoint -> true
                is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
            if (acceptable) return hit
        }
        return null
    }

    /** True when the phone is on the side of the surface its normal points to (in front of the wall, under the ceiling). */
    private fun cameraInFront(hit: HitResult, frame: Frame): Boolean {
        val normal = hit.hitPose.yAxis
        val hitPoint = hit.hitPose.translation
        val cameraPoint = frame.camera.pose.translation
        return (cameraPoint[0] - hitPoint[0]) * normal[0] + (cameraPoint[1] - hitPoint[1]) * normal[1] + (cameraPoint[2] - hitPoint[2]) * normal[2] > 0f
    }

    private fun upFor(hit: HitResult, frame: Frame): FloatArray? {
        val normal = normalize(hit.hitPose.yAxis)
        if (abs(normal[1]) < 0.7f) return null
        // floors and ceilings: the picture's top points away from the person holding the phone
        val cameraBack = frame.camera.pose.zAxis
        val forward = floatArrayOf(-cameraBack[0], 0f, -cameraBack[2])
        val cameraUp = frame.camera.pose.yAxis
        val pick = if (hypot(forward[0], forward[2]) > 0.2f) forward else floatArrayOf(cameraUp[0], 0f, cameraUp[2])
        return normalize(pick)
    }

    // ---------- models ----------
    private fun modelKey(item: PlacedItem): String {
        if (item.product.modelAsset.isNotBlank()) return "asset#${item.product.modelAsset}"
        val lamp = if (item.product.lensFraction > 0f) lightState.name else "-"
        return "${item.product.id}#${item.variant}#$lamp"
    }

    /** Builds (off the main thread) and shows the 3D model matching the item's colour and the light state. */
    private fun requestModel(item: PlacedItem) {
        val key = modelKey(item)
        if (item.modelKey == key && item.modelNode != null) return
        val cached = modelBytesCache[key]
        if (cached != null) {
            applyModel(item, key, cached)
            return
        }
        if (!modelsBeingBuilt.add(key)) return
        val product = item.product
        val variant = product.variants[item.variant.coerceIn(0, product.variants.size - 1)]
        val lamp = lightState
        modelBuilder.execute {
            var shadowBytes: ByteArray? = null
            val bytes = runCatching {
                val photo = variant.bitmap ?: throw IllegalStateException("photo")
                if (product.modelAsset.isNotBlank()) {
                    // real CAD model shipped with the app; only its soft shadow is made here
                    shadowBytes = runCatching { ProductModels.buildContactShadow(product.spec, photo, blurMm = 9f, opacity = 0.55f) }.getOrNull()
                    assets.open("www/models/${product.modelAsset}").use { stream -> stream.readBytes() }
                } else {
                    ProductModels.build(product.spec, photo, variant.label, lamp)
                }
            }.getOrNull()
            mainHandler.post {
                modelsBeingBuilt.remove(key)
                if (activityGone) return@post
                if (bytes == null) {
                    uiToast("ما قدرتش أجهّز شكل ${product.name}.")
                    return@post
                }
                shadowBytes?.let { modelBytesCache["$key#shadow"] = it }
                modelBytesCache[key] = bytes
                for (waiting in items) if (modelKey(waiting) == key) applyModel(waiting, key, bytes)
            }
        }
    }

    private fun applyModel(item: PlacedItem, key: String, bytes: ByteArray) {
        if (item !in items || item.modelKey == key && item.modelNode != null) return
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        buffer.flip()
        val instance = runCatching { sceneView.modelLoader.createModelInstance(buffer) }.getOrNull() ?: return
        val newNode = ModelNode(modelInstance = instance)
        val realModel = item.product.modelAsset.isNotBlank()
        for (part in newNode.renderableNodes) {
            part.isShadowCaster = realModel || part.name == "sides" || part.name == "face"
            part.isShadowReceiver = false
        }
        if (realModel) {
            // glTF is Y-up; the item frame has z = surface normal and y = up along the surface
            newNode.transform = Mat4(
                Float4(1f, 0f, 0f, 0f),
                Float4(0f, 0f, 1f, 0f),
                Float4(0f, -1f, 0f, 0f),
                Float4(0f, 0f, 0f, 1f)
            )
        }
        item.modelNode?.let { oldNode -> discardModel(item.rootNode, oldNode) }
        item.rootNode.addChildNode(newNode)
        item.modelNode = newNode
        item.modelKey = key

        if (realModel && item.shadowNode == null) {
            modelBytesCache["$key#shadow"]?.let { shadowBytes ->
                val shadowBuffer = ByteBuffer.allocateDirect(shadowBytes.size).put(shadowBytes)
                shadowBuffer.flip()
                runCatching { sceneView.modelLoader.createModelInstance(shadowBuffer) }.getOrNull()?.let { shadowInstance ->
                    val shadowNode = ModelNode(modelInstance = shadowInstance)
                    shadowNode.isShadowCaster = false
                    shadowNode.isShadowReceiver = false
                    item.rootNode.addChildNode(shadowNode)
                    item.shadowNode = shadowNode
                }
            }
        }

        if (item.frameNode == null) {
            val frameKey = "frame#${item.product.id}"
            val frameBytes = modelBytesCache.getOrPut(frameKey) { ProductModels.buildSelectionFrame(item.product.spec) }
            val frameBuffer = ByteBuffer.allocateDirect(frameBytes.size).put(frameBytes)
            frameBuffer.flip()
            runCatching { sceneView.modelLoader.createModelInstance(frameBuffer) }.getOrNull()?.let { frameInstance ->
                val frameNode = ModelNode(modelInstance = frameInstance)
                frameNode.isShadowCaster = false
                frameNode.isShadowReceiver = false
                item.rootNode.addChildNode(frameNode)
                item.frameNode = frameNode
            }
        }
    }

    private fun discardModel(parent: Node, modelNode: ModelNode) {
        parent.removeChildNode(modelNode)
        runCatching { sceneView.modelLoader.destroyModel(modelNode.model) }
    }

    private fun placeItem(product: Product, hit: HitResult, frame: Frame) {
        val anchor = runCatching { hit.createAnchor() }.getOrNull() ?: return
        val rootNode = Node(engine = sceneView.engine)
        val item = PlacedItem(product, variantOf[product.id] ?: 0, anchor, upFor(hit, frame), rootNode)
        items.add(item)
        sceneView.addChildNode(rootNode)
        updateItemPlacement(item, cameraTracking = true)
        selectItem(item)
        requestModel(item)
    }

    private fun removeItem(item: PlacedItem) {
        item.anchor?.detach()
        item.anchor = null
        item.modelNode?.let { modelNode -> discardModel(item.rootNode, modelNode) }
        item.frameNode?.let { frameNode -> discardModel(item.rootNode, frameNode) }
        item.shadowNode?.let { shadowNode -> discardModel(item.rootNode, shadowNode) }
        item.modelNode = null
        item.frameNode = null
        item.shadowNode = null
        sceneView.removeChildNode(item.rootNode)
        runCatching { item.rootNode.destroy() }
        items.remove(item)
        if (selectedItem === item) selectedItem = null
        if (draggedItem === item) draggedItem = null
    }

    private fun selectItem(item: PlacedItem?) {
        selectedItem = item
        for (other in items) other.frameNode?.isVisible = other === item && !capturing
        if (item != null) {
            val productIndex = products.indexOf(item.product)
            if (productIndex >= 0 && productIndex != currentProduct) {
                currentProduct = productIndex
                renderStrip()
            }
        }
    }

    // ---------- touch ----------
    private fun projectToScreen(item: PlacedItem, localX: Float, localY: Float): FloatArray? {
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, item.worldMatrix, 0, floatArrayOf(localX, localY, 0f, 1f), 0)
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, viewProjection, 0, world, 0)
        if (clip[3] <= 0f) return null
        return floatArrayOf((clip[0] / clip[3] + 1f) / 2f * sceneView.width, (1f - clip[1] / clip[3]) / 2f * sceneView.height)
    }

    private fun itemAt(x: Float, y: Float): PlacedItem? {
        var best: PlacedItem? = null
        var bestDistance = Float.MAX_VALUE
        for (item in items) {
            if (!item.onScreen) continue
            val halfWidth = item.product.widthMm / 2000f
            val halfHeight = item.product.heightMm / 2000f
            val centre = projectToScreen(item, 0f, 0f) ?: continue
            var reach = dp(36f)
            for ((cornerX, cornerY) in listOf(-halfWidth to halfHeight, halfWidth to halfHeight, halfWidth to -halfHeight, -halfWidth to -halfHeight)) {
                val corner = projectToScreen(item, cornerX, cornerY) ?: continue
                reach = max(reach, hypot(corner[0] - centre[0], corner[1] - centre[1]))
            }
            val distance = hypot(x - centre[0], y - centre[1])
            if (distance <= reach && distance < bestDistance) {
                bestDistance = distance
                best = item
            }
        }
        return best
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; fingerMoved = false; twoFingerAngle = null
                draggedItem = itemAt(event.x, event.y)
                draggedItem?.let { item -> selectItem(item) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && selectedItem != null) {
                    finishDrag(event.x, event.y)
                    twoFingerAngle = fingerAngle(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val startAngle = twoFingerAngle
                val item = selectedItem
                if (startAngle != null && event.pointerCount >= 2 && item != null) {
                    // twist with two fingers to turn the product on the surface
                    val angle = fingerAngle(event)
                    var delta = angle - startAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    item.rotationDegrees = (item.rotationDegrees - delta + 360f) % 360f
                    twoFingerAngle = angle
                    fingerMoved = true
                    return
                }
                if (!fingerMoved && hypot(event.x - downX, event.y - downY) > touchSlopPx) fingerMoved = true
                val dragged = draggedItem ?: return
                if (!fingerMoved) return
                val frame = lastFrame ?: return
                val hit = goodHit(frame, event.x, event.y) ?: return
                if (dragged.anchor != null) {
                    dragged.anchor?.detach()
                    dragged.anchor = null
                }
                dragged.dragPose = hit.hitPose
                dragged.upHint = upFor(hit, frame)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) twoFingerAngle = null
            }
            MotionEvent.ACTION_UP -> {
                val wasDragging = draggedItem != null
                finishDrag(event.x, event.y)
                if (fingerMoved || wasDragging) return
                val frame = lastFrame ?: return
                val product = products.getOrNull(currentProduct) ?: return
                val hit = goodHit(frame, event.x, event.y)
                if (hit == null) {
                    if (selectedItem != null) selectItem(null)
                    else uiToast("مفيش سطح متعرّف عليه هنا. حرّك الموبايل لحد ما تظهر النقط على الحيطة أو السقف.")
                    return
                }
                placeItem(product, hit, frame)
            }
            MotionEvent.ACTION_CANCEL -> finishDrag(event.x, event.y)
        }
    }

    /** Pins a dragged item to the surface where the finger let go: one anchor per drag, not per movement. */
    private fun finishDrag(x: Float, y: Float) {
        val item = draggedItem ?: return
        draggedItem = null
        val lastPose = item.dragPose ?: return
        val frame = lastFrame
        val hit = frame?.let { goodHit(it, x, y) }
        val anchor = runCatching { hit?.createAnchor() }.getOrNull()
            ?: runCatching { sceneView.session?.createAnchor(lastPose) }.getOrNull()
        if (hit != null && frame != null) item.upHint = upFor(hit, frame)
        item.anchor = anchor
        item.dragPose = if (anchor == null) lastPose else null
        item.shownTranslation = null
    }

    private fun fingerAngle(event: MotionEvent): Float {
        val deltaX = event.getX(1) - event.getX(0)
        val deltaY = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()
    }

    // ---------- status texts ----------
    private fun setHint(text: String) = runOnUiThread {
        hintView.text = text
        hintView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun uiToast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    private fun updateTexts(frame: Frame) {
        val now = System.currentTimeMillis()
        if (now - lastTextUpdateMs < 250) return
        lastTextUpdateMs = now
        val camera = frame.camera
        val planeCount = sceneView.session?.getAllTrackables(Plane::class.java)?.count { plane ->
            plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null
        } ?: 0
        val hint = when {
            camera.trackingState != TrackingState.TRACKING -> when (camera.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "النور ضعيف. نوّر المكان أكتر."
                TrackingFailureReason.EXCESSIVE_MOTION -> "حرّك الموبايل أبطأ."
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "السطح سادة أوي. صوّب على حتة فيها تفاصيل (برواز، فيشة، ركن)، أو الصق شريط لزق على الحيطة، أو استخدم وضع الصورة."
                TrackingFailureReason.CAMERA_UNAVAILABLE -> "الكاميرا مش متاحة دلوقتي."
                else -> "حرّك الموبايل ببطء يمين وشمال…"
            }
            planeCount == 0 && items.isEmpty() -> "وجّه الكاميرا للحيطة أو السقف وحرّكها ببطء لحد ما تظهر نقط على السطح."
            items.isEmpty() -> "دوس على السطح عشان تحط المنتج."
            else -> ""
        }
        if (hint != lastHint) { lastHint = hint; setHint(hint) }

        val selected = selectedItem
        val text = if (selected == null) {
            val product = products.getOrNull(currentProduct)
            if (product == null) "" else "${product.name} · ${mmText(product)} — دوس على الحيطة أو السقف"
        } else {
            val position = translationOf(selected)
            var nearest = Float.MAX_VALUE
            for (other in items) {
                if (other === selected) continue
                nearest = minOf(nearest, distance(position, translationOf(other)))
            }
            val away = distance(position, camera.pose.translation)
            val label = selected.product.variants.getOrNull(selected.variant)?.label.orEmpty()
            buildString {
                append(selected.product.name)
                if (label.isNotBlank() && selected.product.variants.size > 1) append(" · ").append(label)
                append(" · ").append(mmText(selected.product))
                if (nearest < Float.MAX_VALUE) append("\nلأقرب قطعة: ").append(lengthText(nearest))
                append("   بعدك عنه: ").append(lengthText(away))
                append("   على الصورة: ").append(items.size)
            }
        }
        if (text != lastInfo) { lastInfo = text; infoView.text = text }
    }

    private fun translationOf(item: PlacedItem): FloatArray =
        floatArrayOf(item.worldMatrix[12], item.worldMatrix[13], item.worldMatrix[14])

    private fun distance(first: FloatArray, second: FloatArray): Float {
        val deltaX = first[0] - second[0]
        val deltaY = first[1] - second[1]
        val deltaZ = first[2] - second[2]
        return sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)
    }

    private fun lengthText(metres: Float): String =
        if (metres >= 1f) String.format(Locale.US, "%.2f م", metres) else "${(metres * 100f).roundToInt()} سم"

    // ---------- capture ----------
    /** Hides the selection outline and the surface dots, waits for a clean frame, then copies the screen. */
    private fun capture() {
        if (capturing) return
        if (sceneView.width <= 0 || sceneView.height <= 0) return
        capturing = true
        for (item in items) item.frameNode?.isVisible = false
        sceneView.planeRenderer.isVisible = false
        val counts = LinkedHashMap<String, Int>()
        for (item in items) {
            val label = item.product.variants.getOrNull(item.variant)?.label.orEmpty()
            val key = item.product.name + (if (label.isNotBlank() && item.product.variants.size > 1) "، $label" else "") + "، " + mmText(item.product)
            counts[key] = (counts[key] ?: 0) + 1
        }
        mainHandler.postDelayed({
            val shot = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(sceneView, shot, { copyResult ->
                    capturing = false
                    selectItem(selectedItem)
                    if (copyResult != PixelCopy.SUCCESS) {
                        uiToast("الصورة ما اتحفظتش.")
                        return@request
                    }
                    saveShot(shot, counts)
                }, mainHandler)
            } catch (e: Exception) {
                capturing = false
                selectItem(selectedItem)
                uiToast("الصورة ما اتحفظتش: ${e.message}")
            }
        }, 150)
    }

    private fun saveShot(shot: Bitmap, counts: Map<String, Int>) {
        Thread {
            try {
                val finalPicture = withList(shot, counts)
                val stream = ByteArrayOutputStream()
                finalPicture.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                val where = MainActivity.saveJpeg(this, stream.toByteArray(), "panda_live_${System.currentTimeMillis()}.jpg")
                uiToast("الصورة اتحفظت في $where")
            } catch (e: Exception) {
                uiToast("الصورة ما اتحفظتش: ${e.message}")
            }
        }.start()
    }

    private fun withList(shot: Bitmap, counts: Map<String, Int>): Bitmap {
        if (counts.isEmpty()) return shot
        val width = shot.width
        val fontSize = max(28f, width * 0.032f)
        val lineHeight = fontSize * 1.6f
        val padding = width * 0.04f
        val bandHeight = (padding * 2 + fontSize * 1.5f + counts.size * lineHeight).toInt()
        val picture = Bitmap.createBitmap(width, shot.height + bandHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(picture)
        canvas.drawBitmap(shot, 0f, 0f, null)
        canvas.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
        canvas.drawRect(0f, shot.height.toFloat(), width.toFloat(), picture.height.toFloat(), Paint().apply { color = Color.WHITE })
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF15181D.toInt(); textSize = fontSize * 1.1f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF15181D.toInt(); textSize = fontSize; textAlign = Paint.Align.RIGHT }
        var baseline = shot.height + padding + fontSize
        canvas.drawText("المنتجات على الصورة", width - padding, baseline, titlePaint)
        baseline += fontSize * 0.4f
        for ((name, count) in counts) {
            baseline += lineHeight
            canvas.drawText("$count × $name", width - padding, baseline, linePaint)
        }
        shot.recycle()
        return picture
    }
}

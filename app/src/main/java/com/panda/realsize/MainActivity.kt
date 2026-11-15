package com.panda.realsize

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.google.ar.core.ArCoreApk
import java.io.File

/**
 * Photo mode: the editor page runs in a WebView served from the app's own assets.
 * The page talks to the phone through [Bridge] (camera, gallery save, clipboard, live AR).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: Uri? = null
    private var pendingCapture = false

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val cb = fileCallback
        fileCallback = null
        val uri = cameraFile
        cb?.onReceiveValue(if (ok && uri != null) arrayOf(uri) else null)
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (pendingCapture) {
            pendingCapture = false
            if (granted) launchCamera() else {
                toast("محتاج إذن الكاميرا عشان تصوّر. اختار صورة من المعرض بدل كده.")
                launchGallery()
            }
        }
    }

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        // Android 15 draws apps under the status and navigation bars: keep the page clear of them and of the keyboard.
        val shell = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
            addView(web, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(shell)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                if (params.isCaptureEnabled) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchCamera()
                    } else {
                        pendingCapture = true
                        askCamera.launch(Manifest.permission.CAMERA)
                    }
                } else {
                    launchGallery()
                }
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidApp")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__androidBack ? window.__androidBack() : false") { r ->
                    if (r != "true") finish()
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // Warm up the ARCore availability check so the answer is ready when the page asks.
        runCatching { ArCoreApk.getInstance().checkAvailability(this) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "shots").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        cameraFile = uri
        try {
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("الكاميرا ما فتحتش. اختار صورة من المعرض.")
            launchGallery()
        }
    }

    private fun launchGallery() {
        try {
            pickImage.launch("image/*")
        } catch (e: Exception) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
        }
    }

    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }

    inner class Bridge {
        @JavascriptInterface
        fun isApp(): Boolean = true

        @JavascriptInterface
        fun arStatus(): String {
            return try {
                val a = ArCoreApk.getInstance().checkAvailability(this@MainActivity)
                when {
                    a.isSupported -> "supported"
                    a.isTransient -> "checking"
                    else -> "unsupported"
                }
            } catch (e: Exception) {
                "unsupported"
            }
        }

        /** The page hands over the products (sizes in mm and pre-drawn PNGs); AR reads them from a file. */
        @JavascriptInterface
        fun startAr(json: String) {
            try {
                File(cacheDir, "ar_products.json").writeText(json)
                runOnUiThread { startActivity(Intent(this@MainActivity, ArActivity::class.java)) }
            } catch (e: Exception) {
                toast("وضع الكاميرا اللايف ما اشتغلش: ${e.message}")
            }
        }

        @JavascriptInterface
        fun saveImage(base64: String, name: String): String {
            return try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val where = saveJpeg(this@MainActivity, bytes, name)
                toast("الصورة اتحفظت في $where")
                "saved"
            } catch (e: Exception) {
                toast("الصورة ما اتحفظتش: ${e.message}")
                "error"
            }
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Panda", text))
                Toast.makeText(this@MainActivity, "القائمة اتنسخت.", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun share(text: String) {
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(i, "ابعت القائمة"))
            }
        }
    }

    companion object {
        /** Saves a JPEG to the phone's Pictures/Panda folder and returns a readable location. */
        fun saveJpeg(ctx: Context, bytes: ByteArray, name: String): String {
            val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "panda.jpg" }
            val file = if (safe.endsWith(".jpg")) safe else "$safe.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Panda")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore")
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("stream")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return "الصور ← Pictures/Panda"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Panda")
                dir.mkdirs()
                val f = File(dir, file)
                f.writeBytes(bytes)
                @Suppress("DEPRECATION")
                ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)))
                return "Pictures/Panda"
            }
        }
    }
}

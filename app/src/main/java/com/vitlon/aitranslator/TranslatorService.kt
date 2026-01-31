package com.vitlon.aitranslator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import io.noties.markwon.Markwon
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.max

class TranslatorService : Service() {
    // --- SYSTEM & VIEW ---
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var translatorView: View // View chính (Thay cho expandedView cũ)
    private lateinit var scannerView: View

    // --- BIẾN KÍCH THƯỚC & VỊ TRÍ (Mặc định) ---
    private var transX = 0
    private var transY = 100
    private var transW = 600
    private var transH = 800

    // --- BIẾN TRẠNG THÁI ---
    private var isExpanded = false
    private var isScannerVisible = false
    private var scannerX = 0; private var scannerY = 0
    private var bubbleX = 0; private var bubbleY = 100
    private var isProcessing = false

    // --- CÔNG CỤ HỖ TRỢ ---
    private val markwon by lazy { Markwon.create(this) }
    private val fullChatHistory = StringBuilder()

    // Client Groq
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // DANH SÁCH MODEL (Tự động xoay tua để không bị Limit)
    private val models = listOf(
        "openai/gpt-oss-20b",
        "openai/gpt-oss-120b"
    )
    private var currentModelIndex = 0

    // ML Kit (OCR & Offline Translate)
    private val textRecognizer by lazy {
        com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
    }
    private val offlineTranslator by lazy {
        val options = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
            .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.CHINESE)
            .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.VIETNAMESE)
            .build()
        com.google.mlkit.nl.translate.Translation.getClient(options)
    }
    private var isOfflineTranslatorReady = false

    // --- SCREEN CAPTURE ---
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: android.media.ImageReader? = null
    private var screenRealWidth = 0; private var screenRealHeight = 0; private var screenDensity = 0

    companion object {
        private var permissionResultCode = 0
        private var permissionResultData: Intent? = null
        fun setMediaProjectionIntent(code: Int, data: Intent) {
            permissionResultCode = code
            permissionResultData = data
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenRealWidth = metrics.widthPixels
        screenRealHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Tải trước model offline
        offlineTranslator.downloadModelIfNeeded().addOnSuccessListener { isOfflineTranslatorReady = true }

        initViews()
        startMyForeground()
    }

    private fun initViews() {
        // 1. BUBBLE (Bong bóng)
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_bubble, null)
        setDragListener(floatingView.findViewById(R.id.bubble_image), floatingView, isBubble = true)

        // 2. TRANSLATOR WINDOW (Giao diện mới - Resize được)
        // Lưu ý: Phải tạo file layout_translator.xml như mình đã gửi ở bài trước nhé!
        translatorView = LayoutInflater.from(this).inflate(R.layout.layout_translator, null)

        // Setup Header (Kéo di chuyển)
        setDragListener(translatorView.findViewById(R.id.layout_header_drag), translatorView, isBubble = false)

        // Setup Resize Handle (Góc dưới phải)
        setResizeListener(translatorView.findViewById(R.id.btn_resize_window), translatorView)

        // Các nút chức năng
        translatorView.findViewById<View>(R.id.btn_minimize).setOnClickListener { switchToBubble() }
        translatorView.findViewById<View>(R.id.btn_clear).setOnClickListener {
            fullChatHistory.clear()
            translatorView.findViewById<TextView>(R.id.tv_log).text = "Sẵn sàng dịch..."
        }

        // Nút Quét nhanh (trong footer)
        translatorView.findViewById<View>(R.id.btn_quick_scan).setOnClickListener { performOCR() }

        // --- LOGIC CÀI ĐẶT (ẨN/HIỆN SETTINGS) ---
        val layoutSettings = translatorView.findViewById<View>(R.id.layout_settings_panel)
        val btnSettings = translatorView.findViewById<View>(R.id.btn_settings)
        val edtKey = translatorView.findViewById<EditText>(R.id.edt_api_key)
        val btnSaveKey = translatorView.findViewById<Button>(R.id.btn_save_key)

        // Load key cũ
        val prefs = getSharedPreferences("WikiPrefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("user_groq_key", "")
        if (!savedKey.isNullOrEmpty()) edtKey.setText(savedKey)

        btnSettings.setOnClickListener {
            layoutSettings.visibility = if (layoutSettings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnSaveKey.setOnClickListener {
            val inputKey = edtKey.text.toString().trim()
            if (inputKey.isNotEmpty()) {
                prefs.edit().putString("user_groq_key", inputKey).apply()
                Toast.makeText(this, "Đã lưu Key!", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().remove("user_groq_key").apply()
                Toast.makeText(this, "Đã xóa Key cá nhân!", Toast.LENGTH_SHORT).show()
            }
            layoutSettings.visibility = View.GONE
        }

        // Hiện Bubble trước
        val params = getLayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, bubbleX, bubbleY)
        windowManager.addView(floatingView, params)
    }

    // =========================================================
    // LOGIC RESIZE & DRAG (CỐT LÕI CỦA APP MỚI)
    // =========================================================

    private fun setResizeListener(handle: View, targetView: View) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var initW = 0; private var initH = 0
            private var initX = 0f; private var initY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = targetView.layoutParams as WindowManager.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initW = params.width; initH = params.height
                        initX = event.rawX; initY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Tính kích thước mới
                        var newW = initW + (event.rawX - initX).toInt()
                        var newH = initH + (event.rawY - initY).toInt()

                        // Giới hạn nhỏ nhất (để không bị mất view)
                        newW = max(300, newW)
                        newH = max(200, newH)

                        params.width = newW; params.height = newH
                        windowManager.updateViewLayout(targetView, params)

                        // Lưu lại để lần sau mở lên vẫn giữ cỡ này
                        transW = newW; transH = newH
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setDragListener(touchView: View, moveView: View, isBubble: Boolean) {
        touchView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            private var startTime = 0L
            private var isMoved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = moveView.layoutParams as WindowManager.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        startTime = System.currentTimeMillis(); isMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(event.rawX - initialTouchX) > 10 || Math.abs(event.rawY - initialTouchY) > 10) {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(moveView, params)
                            isMoved = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moveView == floatingView) {
                            bubbleX = params.x; bubbleY = params.y
                            if (!isMoved && System.currentTimeMillis() - startTime < 200) switchToExpanded()
                        } else if (moveView == translatorView) {
                            transX = params.x; transY = params.y
                        } else if (::scannerView.isInitialized && moveView == scannerView) {
                            scannerX = params.x; scannerY = params.y
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    // =========================================================
    // LOGIC HIỂN THỊ (SWITCH VIEW)
    // =========================================================

    private fun switchToExpanded() {
        try { windowManager.removeView(floatingView) } catch (e: Exception) {}

        val params = getLayoutParams(transW, transH, transX, transY)
        // Cho phép chạm xuyên qua game, nhưng vẫn giữ SoftInput để nhập Key
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        if (!translatorView.isAttachedToWindow) windowManager.addView(translatorView, params)
        else windowManager.updateViewLayout(translatorView, params)
        isExpanded = true
    }

    private fun switchToBubble() {
        try {
            if (isExpanded) windowManager.removeView(translatorView)
            if (::scannerView.isInitialized && isScannerVisible) {
                windowManager.removeView(scannerView)
                isScannerVisible = false
            }
        } catch (e: Exception) {}

        val params = getLayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, bubbleX, bubbleY)
        if (!floatingView.isAttachedToWindow) windowManager.addView(floatingView, params)
        isExpanded = false
    }

    // =========================================================
    // LOGIC DỊCH THUẬT & API (ĐA NĂNG & BẤT TỬ)
    // =========================================================

    private fun performOCR() {
        if (isProcessing) return
        isProcessing = true
        updateScreenOrientation()

        if (permissionResultData == null) {
            val intent = Intent(this, PermissionActivity::class.java); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent)
            isProcessing = false; return
        }
        if (mediaProjection == null) {
            mediaProjection = mediaProjectionManager?.getMediaProjection(permissionResultCode, permissionResultData!!)
            createVirtualDisplay()
        }

        // Tự động mở khung quét nếu chưa mở
        if (!isScannerVisible) { toggleScanner(); isProcessing = false; return }

        // Tính toán vùng cắt
        val location = IntArray(2)
        scannerView.getLocationOnScreen(location)
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()

        CoroutineScope(Dispatchers.Main).launch {
            // Ẩn view để chụp
            translatorView.alpha = 0f
            scannerView.alpha = 0f
            delay(100)

            var bmp: android.graphics.Bitmap? = null
            withContext(Dispatchers.IO) { try { bmp = captureScreen() } catch (e: Exception) {} }

            translatorView.alpha = 1f
            scannerView.alpha = 1f

            if (bmp != null) {
                // Nếu đang ở Bubble thì mở Translator lên để hiện kết quả
                if (!isExpanded) switchToExpanded()
                processBitmap(bmp, location[0] + padding, location[1] + padding, scannerView.width - padding*2, scannerView.height - padding*2)
            } else {
                Toast.makeText(applicationContext, "Lỗi chụp!", Toast.LENGTH_SHORT).show()
                isProcessing = false
            }
        }
    }

    private fun processBitmap(fullBmp: android.graphics.Bitmap, x: Int, y: Int, w: Int, h: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val safeX = max(0, x); val safeY = max(0, y)
                var safeW = w; var safeH = h
                if (safeX + safeW > fullBmp.width) safeW = fullBmp.width - safeX
                if (safeY + safeH > fullBmp.height) safeH = fullBmp.height - safeY

                if (safeW <= 0 || safeH <= 0) { fullBmp.recycle(); isProcessing = false; return@launch }

                val crop = android.graphics.Bitmap.createBitmap(fullBmp, safeX, safeY, safeW, safeH)
                fullBmp.recycle()

                val image = com.google.mlkit.vision.common.InputImage.fromBitmap(crop, 0)
                textRecognizer.process(image)
                    .addOnSuccessListener {
                        val text = it.text
                        if (text.isBlank()) Toast.makeText(applicationContext, "Không có chữ!", Toast.LENGTH_SHORT).show()
                        else doTranslate(text)
                        isProcessing = false
                    }
                    .addOnFailureListener { isProcessing = false }
            } catch (e: Exception) { isProcessing = false }
        }
    }

    private fun doTranslate(text: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val tvLog = translatorView.findViewById<TextView>(R.id.tv_log)
            tvLog.text = "⏳ Đang dịch..." // Xóa text cũ, hiện trạng thái

            try {
                val result = callGroqAPI(text)
                appendLog(text, result)
            } catch (e: Exception) {
                tvLog.text = "Lỗi: ${e.message}"
                // Fallback offline nếu cần
            }
        }
    }

    private suspend fun callGroqAPI(originalText: String): String {
        return withContext(Dispatchers.IO) {
            val apiKey = getCurrentApiKey()
            var lastError: Exception? = null

            // VÒNG LẶP THỬ CÁC MODEL (Cơ chế Bất tử)
            for (i in currentModelIndex until models.size) {
                val modelName = models[i]
                try {
                    val jsonBody = JSONObject()
                    jsonBody.put("model", modelName)

                    val messages = JSONArray()
                    val sys = JSONObject(); sys.put("role", "system")
                    // PROMPT ĐA NĂNG: Dịch mọi thứ
                    sys.put("content", "Bạn là trợ lý dịch thuật màn hình. Nhiệm vụ: Tự động nhận diện ngôn ngữ nguồn và dịch sang tiếng Việt. Văn phong tự nhiên, ngắn gọn. Giữ nguyên số liệu. Chỉ trả về kết quả dịch.")
                    messages.put(sys)

                    val user = JSONObject(); user.put("role", "user"); user.put("content", originalText)
                    messages.put(user)
                    jsonBody.put("messages", messages)

                    val request = Request.Builder().url("https://api.groq.com/openai/v1/chat/completions")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(jsonBody.toString().toRequestBody("application/json".toMediaType())).build()

                    val response = client.newCall(request).execute()

                    if (response.isSuccessful) {
                        currentModelIndex = i // Lưu lại model đang dùng tốt
                        val res = JSONObject(response.body?.string() ?: "")
                        return@withContext res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    } else if (response.code == 429) {
                        response.close()
                        continue // Thử model tiếp theo
                    } else {
                        throw Exception("Code ${response.code}")
                    }
                } catch (e: Exception) { lastError = e }
            }
            currentModelIndex = 0 // Reset
            throw lastError ?: Exception("Busy!")
        }
    }

    private fun appendLog(original: String, translated: String) {
        val tvLog = translatorView.findViewById<TextView>(R.id.tv_log)
        val scrollView = translatorView.findViewById<ScrollView>(R.id.scroll_log)

        val newEntry = """
            
            ---
            **📜 Gốc:** $original
            **🚀 Dịch:** $translated
        """.trimIndent()

        fullChatHistory.append(newEntry)
        markwon.setMarkdown(tvLog, fullChatHistory.toString())
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun getCurrentApiKey(): String {
        // 1. Ưu tiên Key người dùng nhập trong Cài đặt (nếu có)
        val prefs = getSharedPreferences("WikiPrefs", Context.MODE_PRIVATE)
        val userKey = prefs.getString("user_groq_key", "")?.trim()

        if (!userKey.isNullOrEmpty()) {
            // Xử lý nếu người dùng nhập nhiều key (Key1,Key2...)
            if (userKey.contains(",")) {
                return userKey.split(",").random().trim()
            }
            return userKey
        }

        // 2. Nếu không có Key riêng -> Dùng Key mặc định trong Code (BuildConfig)
        // Lưu ý: BuildConfig.GROQ_KEY là cái lấy từ local.properties
        return BuildConfig.GROQ_KEY
    }

    // --- CÁC HÀM CƠ BẢN (KHÔNG ĐỔI) ---
    private fun startMyForeground() { val cId = "trans"; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(cId, "Translator", NotificationManager.IMPORTANCE_LOW)) }; startForeground(102, NotificationCompat.Builder(this, cId).setContentTitle("Translator Active").setSmallIcon(R.mipmap.ic_launcher).build()) }
    private fun getLayoutParams(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams { val p = WindowManager.LayoutParams(w, h, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT); p.gravity = Gravity.TOP or Gravity.START; p.x = x; p.y = y; return p }

    // --- SCANNER UI ---
    private fun toggleScanner() { if (isScannerVisible) { try { windowManager.removeView(scannerView) } catch (e: Exception) {}; isScannerVisible = false } else { scannerView = LayoutInflater.from(this).inflate(R.layout.layout_scanner_box, null); val m = resources.displayMetrics; val w = (m.widthPixels * 0.7).toInt(); val h = (w * 0.5).toInt(); scannerX = (m.widthPixels - w) / 2; scannerY = (m.heightPixels - h) / 2; setDragListener(scannerView.findViewById(R.id.btn_move_handle), scannerView, isBubble = false); setResizeListener(scannerView.findViewById(R.id.btn_resize_handle), scannerView); scannerView.findViewById<View>(R.id.btn_close_scanner).setOnClickListener { toggleScanner() }; scannerView.findViewById<View>(R.id.btn_scan_action).setOnClickListener { performOCR() }; val p = getLayoutParams(w, h, scannerX, scannerY); p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS; windowManager.addView(scannerView, p); isScannerVisible = true } }

    // --- SCREEN CAPTURE UTILS ---
    private fun createVirtualDisplay() { imageReader?.close(); virtualDisplay?.release(); imageReader = android.media.ImageReader.newInstance(screenRealWidth, screenRealHeight, PixelFormat.RGBA_8888, 2); virtualDisplay = mediaProjection?.createVirtualDisplay("Trans", screenRealWidth, screenRealHeight, screenDensity, android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null) }
    private fun captureScreen(): android.graphics.Bitmap? { val img = imageReader?.acquireLatestImage() ?: return null; val planes = img.planes; val buffer = planes[0].buffer; val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride; val rowPadding = rowStride - pixelStride * screenRealWidth; val bmp = android.graphics.Bitmap.createBitmap(screenRealWidth + rowPadding / pixelStride, screenRealHeight, android.graphics.Bitmap.Config.ARGB_8888); bmp.copyPixelsFromBuffer(buffer); img.close(); val clean = android.graphics.Bitmap.createBitmap(bmp, 0, 0, screenRealWidth, screenRealHeight); if (clean != bmp) bmp.recycle(); return clean }
    private fun updateScreenOrientation() { val m = DisplayMetrics(); windowManager.defaultDisplay.getRealMetrics(m); if (m.widthPixels != screenRealWidth || m.heightPixels != screenRealHeight) { screenRealWidth = m.widthPixels; screenRealHeight = m.heightPixels; screenDensity = m.densityDpi; createVirtualDisplay() } }

    override fun onDestroy() { super.onDestroy(); try { if (::floatingView.isInitialized) windowManager.removeView(floatingView); if (::translatorView.isInitialized) windowManager.removeView(translatorView); if (::scannerView.isInitialized) windowManager.removeView(scannerView) } catch (e: Exception) {} }
}
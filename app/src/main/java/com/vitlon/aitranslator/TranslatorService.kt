package com.vitlon.aitranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

// 👇 Nếu namespace của bạn khác, hãy import BuildConfig ở đây.
// Nếu cùng là com.vitlon.aitranslator thì không cần dòng này.
// import com.vitlon.aitranslator.BuildConfig

class TranslatorService : Service() {
    // --- SYSTEM & VIEW ---
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var translatorView: View
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

    // DANH SÁCH MODEL (Tự động xoay tua)
    private val models = listOf(
        "openai/gpt-oss-20b", // Sửa lại tên model cho đúng nếu cần (ví dụ: gemma-7b-it, llama3-8b-8192)
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
        // --- SỬA LỖI CRASH THEME (InflateException) ---
        // Tạo môi trường có Theme để View hiểu được các thuộc tính ?attr/...
        val themeContext = androidx.appcompat.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
        val inflater = LayoutInflater.from(themeContext)

        // 1. BUBBLE (Bong bóng)
        floatingView = inflater.inflate(R.layout.layout_bubble, null)
        setDragListener(floatingView.findViewById(R.id.bubble_image), floatingView, isBubble = true)

        // Sự kiện click vào bong bóng -> Mở rộng
        floatingView.findViewById<View>(R.id.bubble_image).setOnClickListener {
            switchToExpanded()
        }

        // 2. TRANSLATOR WINDOW
        translatorView = inflater.inflate(R.layout.layout_translator, null)

        // Setup Header (Kéo di chuyển)
        setDragListener(translatorView.findViewById(R.id.layout_header_drag), translatorView, isBubble = false)

        // Setup Resize Handle (Góc dưới phải)
        val btnResize = translatorView.findViewById<View>(R.id.btn_resize_window)
        if (btnResize != null) {
            setResizeListener(btnResize, translatorView)
        }

        // --- CÁC NÚT CHỨC NĂNG ---

        // Nút Thu nhỏ (-) -> Về bong bóng
        translatorView.findViewById<View>(R.id.btn_minimize).setOnClickListener {
            switchToBubble()
        }

        // 👇 NÚT CON MẮT (MỚI) -> Chế độ cuộn rèm (Ẩn nội dung, giữ header)
        val btnHideUI = translatorView.findViewById<ImageView>(R.id.btn_hide_ui)
        btnHideUI?.setOnClickListener {
            toggleUI() // Gọi hàm mới
        }

        // Nút Xóa log
        translatorView.findViewById<View>(R.id.btn_clear).setOnClickListener {
            fullChatHistory.clear()
            val tvLog = translatorView.findViewById<TextView>(R.id.tv_log)
            if (tvLog != null) tvLog.text = "Sẵn sàng dịch..."
        }

        // Nút Quét nhanh
        translatorView.findViewById<View>(R.id.btn_quick_scan).setOnClickListener { performOCR() }

        // --- LOGIC CÀI ĐẶT ---
        setupSettingsPanel()

        // Hiện Bubble trước khi khởi động
        val params = getLayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, bubbleX, bubbleY)
        windowManager.addView(floatingView, params)
    }

    // Hàm tách riêng logic settings cho gọn
    private fun setupSettingsPanel() {
        val layoutSettings = translatorView.findViewById<View>(R.id.layout_settings_panel) ?: return
        val btnSettings = translatorView.findViewById<View>(R.id.btn_settings) ?: return
        val edtKey = translatorView.findViewById<EditText>(R.id.edt_api_key)
        val btnSaveKey = translatorView.findViewById<Button>(R.id.btn_save_key)

        val prefs = getSharedPreferences("WikiPrefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("user_groq_key", "")
        if (!savedKey.isNullOrEmpty()) edtKey?.setText(savedKey)

        btnSettings.setOnClickListener {
            layoutSettings.visibility = if (layoutSettings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnSaveKey?.setOnClickListener {
            val inputKey = edtKey?.text.toString().trim()
            if (inputKey.isNotEmpty()) {
                prefs.edit().putString("user_groq_key", inputKey).apply()
                Toast.makeText(this, "Đã lưu Key!", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().remove("user_groq_key").apply()
                Toast.makeText(this, "Đã xóa Key cá nhân!", Toast.LENGTH_SHORT).show()
            }
            layoutSettings.visibility = View.GONE
        }
    }

    // =========================================================
    // LOGIC ẨN/HIỆN GIAO DIỆN (MỚI)
    // =========================================================

    private fun toggleUI() {
        try {
            // Tìm container chứa nội dung (ID phải khớp với file layout_translator.xml mới sửa)
            val container = translatorView.findViewById<View>(R.id.content_container)
            val btnEye = translatorView.findViewById<ImageView>(R.id.btn_hide_ui)

            if (container == null) return

            if (container.visibility == View.VISIBLE) {
                // Đang hiện -> Ẩn đi (Chế độ cuộn rèm)
                container.visibility = View.GONE
                // Đổi icon nếu muốn (ví dụ mắt gạch chéo)
                // btnEye?.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            } else {
                // Đang ẩn -> Hiện lại
                container.visibility = View.VISIBLE
                // btnEye?.setImageResource(android.R.drawable.ic_menu_view)
            }

            // Cập nhật lại kích thước cửa sổ để nó co lại hoặc giãn ra
            val params = translatorView.layoutParams as WindowManager.LayoutParams
            windowManager.updateViewLayout(translatorView, params)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun switchToExpanded() {
        try {
            if (floatingView.parent != null) windowManager.removeView(floatingView)
        } catch (e: Exception) {}

        val params = getLayoutParams(transW, transH, transX, transY)
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        if (translatorView.parent == null) {
            windowManager.addView(translatorView, params)
        } else {
            translatorView.visibility = View.VISIBLE
            // Reset lại container về trạng thái hiện (nếu trước đó đang ẩn)
            val container = translatorView.findViewById<View>(R.id.content_container)
            container?.visibility = View.VISIBLE

            windowManager.updateViewLayout(translatorView, params)
        }
        isExpanded = true
    }

    private fun switchToBubble() {
        try {
            if (isExpanded && translatorView.parent != null) {
                translatorView.visibility = View.GONE // Chỉ ẩn đi chứ không remove để giữ trạng thái
            }

            // Ẩn luôn scanner nếu đang bật
            if (::scannerView.isInitialized && isScannerVisible) {
                windowManager.removeView(scannerView)
                isScannerVisible = false
            }
        } catch (e: Exception) {}

        val params = getLayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, bubbleX, bubbleY)

        if (floatingView.parent == null) {
            windowManager.addView(floatingView, params)
        } else {
            floatingView.visibility = View.VISIBLE
            windowManager.updateViewLayout(floatingView, params)
        }
        isExpanded = false
    }

    // =========================================================
    // LOGIC RESIZE & DRAG
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

                        // ✨ Nếu đang chỉnh kích thước Khung Quét -> Làm mờ Bảng Dịch
                        if (::scannerView.isInitialized && targetView == scannerView && ::translatorView.isInitialized) {
                            translatorView.animate().alpha(0.1f).setDuration(100).start()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        var newW = initW + (event.rawX - initX).toInt()
                        var newH = initH + (event.rawY - initY).toInt()

                        // Giới hạn kích thước tối thiểu tùy theo view
                        if (targetView == translatorView) {
                            newW = max(300, newW)
                            newH = max(100, newH)
                        } else {
                            // Khung quét có thể nhỏ hơn
                            newW = max(100, newW)
                            newH = max(50, newH)
                        }

                        params.width = newW; params.height = newH
                        try {
                            windowManager.updateViewLayout(targetView, params)
                        } catch (e: Exception) {}

                        if (targetView == translatorView) {
                            transW = newW; transH = newH
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // ✨ Thả tay ra -> Hiện lại
                        if (::scannerView.isInitialized && targetView == scannerView && ::translatorView.isInitialized) {
                            translatorView.animate().alpha(1.0f).setDuration(200).start()
                        }
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

                        // ✨ TÍNH NĂNG MỚI: "Xuyên thấu"
                        // Nếu đang chạm vào Khung Quét (Scanner) -> Làm mờ Bảng Dịch
                        if (::scannerView.isInitialized && moveView == scannerView && ::translatorView.isInitialized) {
                            // Chỉnh alpha xuống 0.1 (mờ 90%) hoặc 0.0 (tàng hình luôn)
                            translatorView.animate().alpha(0.1f).setDuration(100).start()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(event.rawX - initialTouchX) > 10 || Math.abs(event.rawY - initialTouchY) > 10) {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            try {
                                windowManager.updateViewLayout(moveView, params)
                            } catch (e: Exception) {}
                            isMoved = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // ✨ Thả tay ra -> Bảng dịch hiện lại rõ nét
                        if (::scannerView.isInitialized && moveView == scannerView && ::translatorView.isInitialized) {
                            translatorView.animate().alpha(1.0f).setDuration(200).start()
                        }

                        // Lưu vị trí
                        if (moveView == floatingView) {
                            bubbleX = params.x; bubbleY = params.y
                            if (!isMoved && System.currentTimeMillis() - startTime < 200) {
                                v.performClick()
                            }
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
    // LOGIC DỊCH THUẬT & API
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

        if (!isScannerVisible) { toggleScanner(); isProcessing = false; return }

        val location = IntArray(2)
        scannerView.getLocationOnScreen(location)
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()

        CoroutineScope(Dispatchers.Main).launch {
            translatorView.alpha = 0f
            scannerView.alpha = 0f
            delay(100)

            var bmp: android.graphics.Bitmap? = null
            withContext(Dispatchers.IO) { try { bmp = captureScreen() } catch (e: Exception) {} }

            translatorView.alpha = 1f
            scannerView.alpha = 1f

            if (bmp != null) {
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
            if (tvLog != null) tvLog.text = "⏳ Đang dịch..."

            try {
                val result = callGroqAPI(text)
                appendLog(text, result)
            } catch (e: Exception) {
                if (tvLog != null) tvLog.text = "Lỗi: ${e.message}"
            }
        }
    }

    private suspend fun callGroqAPI(originalText: String): String {
        return withContext(Dispatchers.IO) {
            val apiKey = getCurrentApiKey()
            var lastError: Exception? = null

            for (i in currentModelIndex until models.size) {
                val modelName = models[i]
                try {
                    val jsonBody = JSONObject()
                    jsonBody.put("model", modelName)
                    val messages = JSONArray()
                    val sys = JSONObject(); sys.put("role", "system")
                    sys.put("content", """
                        |1. Bạn là trợ lý dịch thuật màn hình. Dịch sang tiếng Việt tự nhiên.
                        |2. Đối với văn bản tiếng Trung hãy dịch 100% sang tiếng Việt, nếu có tên riêng hãy dịch chúng sang phiên âm Hán - Việt.
                        |3. Văn bản gốc có thể sai sót nên hãy dựa vào ngữ cảnh để sửa lại cho hợp lý.
                        |""".trimMargin())
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
                        currentModelIndex = i
                        val res = JSONObject(response.body?.string() ?: "")
                        return@withContext res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    } else {
                        response.close()
                        // Nếu lỗi thì thử model tiếp theo
                    }
                } catch (e: Exception) { lastError = e }
            }
            currentModelIndex = 0
            throw lastError ?: Exception("Busy!")
        }
    }

    private fun appendLog(original: String, translated: String) {
        val tvLog = translatorView.findViewById<TextView>(R.id.tv_log) ?: return
        val scrollView = translatorView.findViewById<ScrollView>(R.id.scroll_log)

        // 1. Dọn dẹp văn bản đầu vào (xóa \n ảo, xóa khoảng trắng thừa 2 đầu)
        val cleanOriginal = original.replace("\\n", "\n").replace("\\\n", "\n").trim()
        val cleanTranslated = translated.replace("\\n", "\n").replace("\\\n", "\n").trim()

        // 2. Kiểm tra xem đây có phải là đoạn chat đầu tiên không
        // Nếu không phải đầu tiên -> Thêm dấu gạch ngang phân cách
        val separator = if (fullChatHistory.isNotEmpty()) "\n\n───\n\n" else ""

        // 3. Tạo nội dung mới (VIẾT SÁT LỀ TRÁI ĐỂ TRÁNH LỖI KHUNG XÁM)
        // Lưu ý: Không dùng trimIndent() ở đây nữa để kiểm soát chính xác khoảng trắng
        val newEntry = """
$separator**🗒️ GỐC:**
$cleanOriginal

**📝 DỊCH:**
> $cleanTranslated"""

        // 4. Nối vào lịch sử
        fullChatHistory.append(newEntry)

        // 5. Render lại
        markwon.setMarkdown(tvLog, fullChatHistory.toString())

        // 6. Cuộn xuống đáy
        scrollView?.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun getCurrentApiKey(): String {
        val prefs = getSharedPreferences("WikiPrefs", Context.MODE_PRIVATE)
        val userKey = prefs.getString("user_groq_key", "")?.trim()
        if (!userKey.isNullOrEmpty()) {
            if (userKey.contains(",")) return userKey.split(",").random().trim()
            return userKey
        }
        // 👇 KHẮC PHỤC LỖI NẾU KHÔNG TÌM THẤY BUILDCONFIG
        return try {
            BuildConfig.GROQ_KEY
        } catch (e: Exception) {
            "" // Trả về rỗng nếu lỗi
        }
    }

    // --- CÁC HÀM CƠ BẢN ---
    private fun startMyForeground() {
        val cId = "trans_channel"
        val channelName = "AI Screen Translator"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(cId, channelName, NotificationManager.IMPORTANCE_LOW)
            chan.lightColor = android.graphics.Color.BLUE
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }
        val notificationBuilder = NotificationCompat.Builder(this, cId)
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AI Translator đang chạy")
            .setContentText("Chạm vào bong bóng để dịch")
            .setPriority(NotificationManager.IMPORTANCE_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(102, notificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(102, notificationBuilder.build())
        }
    }

    private fun getLayoutParams(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(w, h, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x
        p.y = y
        return p
    }

    // --- SCANNER UI ---
    private fun toggleScanner() {
        if (isScannerVisible) {
            try { windowManager.removeView(scannerView) } catch (e: Exception) {}; isScannerVisible = false
        } else {
            // Dùng ThemeWrapper cho Scanner luôn để đỡ lỗi
            val themeContext = androidx.appcompat.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
            scannerView = LayoutInflater.from(themeContext).inflate(R.layout.layout_scanner_box, null)

            val m = resources.displayMetrics
            val w = (m.widthPixels * 0.7).toInt()
            val h = (w * 0.5).toInt()
            scannerX = (m.widthPixels - w) / 2
            scannerY = (m.heightPixels - h) / 2

            setDragListener(scannerView.findViewById(R.id.btn_move_handle), scannerView, isBubble = false)
            setResizeListener(scannerView.findViewById(R.id.btn_resize_handle), scannerView)
            scannerView.findViewById<View>(R.id.btn_close_scanner).setOnClickListener { toggleScanner() }
            scannerView.findViewById<View>(R.id.btn_scan_action).setOnClickListener { performOCR() }

            val p = getLayoutParams(w, h, scannerX, scannerY)
            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            windowManager.addView(scannerView, p)
            isScannerVisible = true
        }
    }

    // --- SCREEN CAPTURE UTILS ---
    private fun createVirtualDisplay() { imageReader?.close(); virtualDisplay?.release(); imageReader = android.media.ImageReader.newInstance(screenRealWidth, screenRealHeight, PixelFormat.RGBA_8888, 2); virtualDisplay = mediaProjection?.createVirtualDisplay("Trans", screenRealWidth, screenRealHeight, screenDensity, android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null) }
    private fun captureScreen(): android.graphics.Bitmap? { val img = imageReader?.acquireLatestImage() ?: return null; val planes = img.planes; val buffer = planes[0].buffer; val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride; val rowPadding = rowStride - pixelStride * screenRealWidth; val bmp = android.graphics.Bitmap.createBitmap(screenRealWidth + rowPadding / pixelStride, screenRealHeight, android.graphics.Bitmap.Config.ARGB_8888); bmp.copyPixelsFromBuffer(buffer); img.close(); val clean = android.graphics.Bitmap.createBitmap(bmp, 0, 0, screenRealWidth, screenRealHeight); if (clean != bmp) bmp.recycle(); return clean }
    private fun updateScreenOrientation() { val m = DisplayMetrics(); windowManager.defaultDisplay.getRealMetrics(m); if (m.widthPixels != screenRealWidth || m.heightPixels != screenRealHeight) { screenRealWidth = m.widthPixels; screenRealHeight = m.heightPixels; screenDensity = m.densityDpi; createVirtualDisplay() } }

    override fun onDestroy() { super.onDestroy(); try { if (::floatingView.isInitialized) windowManager.removeView(floatingView); if (::translatorView.isInitialized) windowManager.removeView(translatorView); if (::scannerView.isInitialized) windowManager.removeView(scannerView) } catch (e: Exception) {} }
}
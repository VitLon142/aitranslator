package com.vitlon.aitranslator

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btn_start_service)

        // NÚT BẮT ĐẦU
        btnStart.setOnClickListener {
            if (!checkOverlayPermission()) {
                requestOverlayPermission()
            } else {
                startTranslatorService()
            }
        }

        // Kiểm tra update (Nhớ đổi link repo mới của bạn nhé)
        checkAppUpdate()
    }

    // --- 1. XỬ LÝ QUYỀN VẼ LÊN MÀN HÌNH (OVERLAY) ---
    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, 123)
            Toast.makeText(this, "Vui lòng cấp quyền 'Hiển thị trên ứng dụng khác'!", Toast.LENGTH_LONG).show()
        }
    }

    // --- 2. KHỞI ĐỘNG SERVICE DỊCH ---
    private fun startTranslatorService() {
        val intent = Intent(this, TranslatorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        findViewById<TextView>(R.id.tv_status).apply {
            text = "Trạng thái: Đang chạy 🚀"
            setTextColor(android.graphics.Color.GREEN)
        }

        // Tự động thoát giao diện chính để đỡ vướng (Optional)
        // finish()
    }

    // --- 3. KIỂM TRA CẬP NHẬT (GIỮ NGUYÊN TỪ CŨ) ---
    private fun checkAppUpdate() {
        // 👇 LƯU Ý: Đổi đường dẫn này sang Repo mới của App Translator nhé!
        val configUrl = "https://raw.githubusercontent.com/USERNAME/REPO_MOI_CUA_BAN/main/version.json"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(configUrl).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val jsonStr = response.body?.string()
                    if (jsonStr != null) {
                        val json = JSONObject(jsonStr)
                        val latestVersionCode = json.getInt("versionCode")

                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        val currentVersionCode = if (Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode.toInt() else pInfo.versionCode

                        if (latestVersionCode > currentVersionCode) {
                            val downloadUrl = json.getString("downloadUrl")
                            val notes = json.getString("releaseNotes")
                            withContext(Dispatchers.Main) { showUpdateDialog(notes, downloadUrl) }
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showUpdateDialog(notes: String, url: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("🚀 Có bản cập nhật mới!")
            .setMessage(notes)
            .setCancelable(false)
            .setPositiveButton("Cập nhật") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {}
            }
            .setNegativeButton("Để sau", null)
            .show()
    }
}
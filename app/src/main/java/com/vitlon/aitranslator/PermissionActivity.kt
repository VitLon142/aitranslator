package com.vitlon.aitranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class PermissionActivity : Activity() {
    companion object {
        private const val REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ngay khi mở lên -> Gọi hệ thống xin quyền luôn
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // Nếu người dùng đồng ý -> Gửi kết quả về cho Service
                TranslatorService.setMediaProjectionIntent(resultCode, data)
            }
            // Xin xong (dù đồng ý hay từ chối) thì tắt luôn
            finish()
        }
    }
}
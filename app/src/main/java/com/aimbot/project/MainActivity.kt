package com.aimbot.project

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var projectionManager: android.media.projection.MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager =
            getSystemService(android.media.projection.MediaProjectionManager::class.java)

        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val startBtn = Button(this).apply { text = "▶️  Start AimBot" }
        val stopBtn = Button(this).apply { text = "⏹  Stop AimBot" }
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)

        startBtn.setOnClickListener {
            askShizuku()
            startActivityForResult(
                projectionManager.createScreenCaptureIntent(), 1001)
        }

        stopBtn.setOnClickListener {
            startService(Intent(this, AimBotService::class.java).putExtra("action", "stop"))
            Toast.makeText(this, "AimBot stopped", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            startService(Intent(this, AimBotService::class.java)
                .putExtra("action", "start"))
            startService(Intent(this, AimBotService::class.java)
                .putExtra("resultCode", resultCode)
                .putExtra("data", data))
            Toast.makeText(this, "AimBot + Detection ON ✅", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun askShizuku() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
            Shizuku.requestPermission(100)
        } catch (_: Exception) { }
    }
}

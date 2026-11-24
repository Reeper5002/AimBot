package com.aimbot.project

import android.util.Log
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

object ShizukuInjector {

    private var inputManager: Any? = null

    fun init(): Boolean {
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("")
        } catch (_: Exception) { }
        return refresh()
    }

    fun refresh(): Boolean {
        return try {
            val binder = Shizuku.getBinder() ?: return false
            val wrapper = ShizukuBinderWrapper(binder)
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stub
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, wrapper)
            Log.i("Injector", "InputManager hooked via Shizuku")
            inputManager != null
        } catch (e: Exception) {
            Log.w("Injector", "Shizuku init failed: ${e.message}")
            false
        }
    }

    fun inject(event: MotionEvent) {
        val im = inputManager ?: return
        try {
            im.javaClass
                .getMethod("injectInputEvent",
                    android.view.InputEvent::class.java, Integer.TYPE)
                .invoke(im, event, 0)
        } catch (e: Exception) {
            Log.w("Injector", "inject failed: ${e.message}")
        }
    }
}

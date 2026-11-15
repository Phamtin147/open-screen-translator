package com.openscreentranslator.app.translation

import android.content.Context

object LlamaBridge {
    private var libLoaded = false
    private var initAttempted = false

    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        if (!libLoaded) {
            try {
                System.loadLibrary("hymt_jni")
                libLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                return false
            }
        }
        if (!initAttempted) {
            nativeInit(context.applicationInfo.nativeLibraryDir)
            initAttempted = true
        }
        return true
    }

    external fun nativeInit(nativeLibDir: String)
    external fun nativeLoadModel(modelPath: String): Boolean
    external fun nativeIsModelLoaded(): Boolean
    external fun nativeTranslate(prompt: String, maxTokens: Int, temperature: Float): String?
    external fun nativeCancel()
    external fun nativeUnloadModel()
    external fun nativeShutdown()
}

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
    external fun nativeTranslateBytes(prompt: String, maxTokens: Int, temperature: Float): ByteArray?
    external fun nativeCancel()
    external fun nativeUnloadModel()
    external fun nativeShutdown()

    fun nativeTranslate(prompt: String, maxTokens: Int, temperature: Float): String? {
        if (!libLoaded) return null
        val bytes = nativeTranslateBytes(prompt, maxTokens, temperature) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    fun isLoadedSafe(): Boolean {
        if (!libLoaded) return false
        return try {
            nativeIsModelLoaded()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun unloadIfLoaded() {
        if (!libLoaded) return
        try {
            nativeUnloadModel()
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    fun cancelIfLoaded() {
        if (!libLoaded) return
        try {
            nativeCancel()
        } catch (_: UnsatisfiedLinkError) {
        }
    }
}

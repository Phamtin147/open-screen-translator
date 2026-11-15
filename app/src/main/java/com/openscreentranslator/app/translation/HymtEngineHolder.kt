package com.openscreentranslator.app.translation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface HymtEngineState {
    data object Unloaded : HymtEngineState
    data class Loading(val variantId: String, val displayName: String) : HymtEngineState
    data class Ready(val variantId: String, val displayName: String) : HymtEngineState
    data class Error(val message: String) : HymtEngineState
}

/**
 * Process-wide holder for the loaded Hy-MT2-1.8B GGUF model so MainActivity
 * and FloatingBubbleService share the native llama.cpp context without loading RAM twice.
 */
object HymtEngineHolder {
    private val _engineState = MutableStateFlow<HymtEngineState>(HymtEngineState.Unloaded)
    val engineState: StateFlow<HymtEngineState> = _engineState.asStateFlow()

    @Volatile
    var loadedVariantId: String? = null
        private set

    fun ensureLoaded(context: Context, preferredVariantId: String): Boolean {
        val appContext = context.applicationContext
        val installed = ModelManager.getAnyInstalledVariant(appContext, preferredVariantId) ?: run {
            _engineState.value = HymtEngineState.Error("Chưa tải model Hy-MT2-1.8B GGUF.")
            loadedVariantId = null
            return false
        }

        if (loadedVariantId == installed.id && LlamaBridge.nativeIsModelLoaded()) {
            _engineState.value = HymtEngineState.Ready(installed.id, installed.displayName)
            return true
        }

        if (!LlamaBridge.ensureInitialized(appContext)) {
            _engineState.value = HymtEngineState.Error("Không thể khởi tạo thư viện native llama.cpp.")
            return false
        }

        _engineState.value = HymtEngineState.Loading(installed.id, installed.displayName)
        val modelPath = ModelManager.getModelFile(appContext, installed.id).absolutePath
        val ok = LlamaBridge.nativeLoadModel(modelPath)
        return if (ok) {
            loadedVariantId = installed.id
            _engineState.value = HymtEngineState.Ready(installed.id, installed.displayName)
            true
        } else {
            loadedVariantId = null
            _engineState.value = HymtEngineState.Error("Không thể nạp model ${installed.quantLabel} vào bộ nhớ.")
            false
        }
    }

    fun unload() {
        LlamaBridge.nativeUnloadModel()
        loadedVariantId = null
        _engineState.value = HymtEngineState.Unloaded
    }
}

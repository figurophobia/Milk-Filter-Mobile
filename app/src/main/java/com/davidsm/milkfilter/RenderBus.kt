package com.davidsm.milkfilter

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single-process channel between [RenderService] and the UI. The service publishes render
 * progress/results here; [MainActivity] collects it. Survives the Activity being destroyed
 * (e.g. user leaves the app while a render runs in the foreground service).
 */
object RenderBus {
    sealed class State {
        object Idle : State()
        data class Running(val progress: Int) : State()
        data class Done(val path: String) : State()
        data class Failed(val reason: String? = null) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
}

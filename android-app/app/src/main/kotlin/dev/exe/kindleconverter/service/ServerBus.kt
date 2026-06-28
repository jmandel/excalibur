package dev.exe.kindleconverter.service

import kotlinx.coroutines.flow.MutableStateFlow

/** Live server state, observed by the UI. */
object ServerBus {
    data class Info(val running: Boolean = false, val port: Int = 0)
    val state = MutableStateFlow(Info())
}

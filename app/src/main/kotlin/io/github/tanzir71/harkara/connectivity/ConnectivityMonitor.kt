package io.github.tanzir71.harkara.connectivity

import io.github.tanzir71.harkara.core.ConnectivityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Source of truth for the connectivity pill and sheet. */
interface ConnectivityMonitor {
    val state: StateFlow<ConnectivityState>
}

/**
 * Used until carriers are wired: nothing can reach people yet, so the pill honestly says
 * "Saved, sends later". Tests push other states through [set].
 */
class StaticConnectivityMonitor(initial: ConnectivityState = ConnectivityState.Saved) : ConnectivityMonitor {
    private val mutable = MutableStateFlow(initial)
    override val state: StateFlow<ConnectivityState> = mutable.asStateFlow()

    fun set(state: ConnectivityState) {
        mutable.value = state
    }
}

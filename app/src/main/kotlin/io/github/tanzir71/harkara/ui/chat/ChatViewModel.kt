package io.github.tanzir71.harkara.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tanzir71.harkara.core.mesh.MeshTransport
import io.github.tanzir71.harkara.core.nostr.Bech32
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.wire.FrameType
import io.github.tanzir71.harkara.core.wire.MeshFrame
import io.github.tanzir71.harkara.identity.shortNpub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Minimal public mesh chat on events: an area-channel message ([Kinds.AREA_CHAT], `g`-tagged) signed by
 * [keys], carried as a binary event frame. Incoming frames are decoded and verified; forged ones are dropped.
 */
class ChatViewModel(
    private val keys: KeyPair,
    private val transport: MeshTransport,
    private val geohash: String,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = externalScope ?: viewModelScope
    private val localMessages = MutableStateFlow<List<ChatMessageUi>>(emptyList())

    /** Messages rendered by the chat screen. */
    val messages: StateFlow<List<ChatMessageUi>> = localMessages

    init {
        scope.launch {
            transport.incoming.collect { frame ->
                if (frame.type != FrameType.EVENT) return@collect
                val event = runCatching { frame.event() }.getOrNull() ?: return@collect
                if (event.kind == Kinds.AREA_CHAT && event.validate()) append(event)
            }
        }
    }

    /** Signs and sends one public area message. */
    fun sendBroadcast(text: String) {
        val event = keys.signEvent(Kinds.AREA_CHAT, listOf(listOf("g", geohash)), text)
        scope.launch { transport.send(MeshFrame.event(event)) }
    }

    private fun append(event: Event) {
        localMessages.update { current ->
            if (current.any { it.id == event.id }) current
            else current + ChatMessageUi(id = event.id, sender = shortNpub(Bech32.encodeNpub(event.pubkey)), body = event.content)
        }
    }
}

/** Renderable chat message. */
data class ChatMessageUi(
    /** Event id. */
    val id: String,
    /** Sender's short npub. */
    val sender: String,
    /** Message body. */
    val body: String,
)

package io.github.tanzir71.harkara.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tanzir71.harkara.core.IdentityKeyPair
import io.github.tanzir71.harkara.core.MeshPacket
import io.github.tanzir71.harkara.core.MeshTransport
import io.github.tanzir71.harkara.core.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** UI model for public local mesh chat. */
class ChatViewModel(
    private val identity: IdentityKeyPair,
    private val transport: MeshTransport,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = externalScope ?: viewModelScope
    private val localMessages = MutableStateFlow<List<ChatMessageUi>>(emptyList())

    /** Messages rendered by the chat screen. */
    val messages: StateFlow<List<ChatMessageUi>> = localMessages

    init {
        scope.launch {
            transport.incoming.collect { packet ->
                if (packet.type == PacketType.CHAT_BROADCAST) {
                    append(packet)
                }
            }
        }
    }

    /** Sends one public broadcast chat message. */
    fun sendBroadcast(text: String) {
        val packet = MeshPacket.chat(
            msgId = ids.incrementAndGet(),
            senderId = identity.nearId,
            payload = text.encodeToByteArray(),
            now = Instant.now(),
        )
        scope.launch { transport.send(packet) }
    }

    private fun append(packet: MeshPacket) {
        localMessages.update { current ->
            current + ChatMessageUi(
                msgId = packet.msgId,
                sender = packet.senderId.toBase32(),
                body = packet.payload.decodeToString(),
            )
        }
    }

    private companion object {
        val ids = AtomicLong(0)
    }
}

/** Renderable chat message. */
data class ChatMessageUi(
    /** Stable packet id. */
    val msgId: Long,
    /** Sender NearID. */
    val sender: String,
    /** Message body. */
    val body: String,
)

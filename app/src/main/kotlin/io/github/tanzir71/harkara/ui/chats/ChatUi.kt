package io.github.tanzir71.harkara.ui.chats

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.ui.components.Delivery
import io.github.tanzir71.harkara.ui.components.IouUi
import io.github.tanzir71.harkara.ui.components.PinUi
import io.github.tanzir71.harkara.ui.components.TaskUi

/** What kind of chat a row or thread is. */
enum class ChatKind { DM, GROUP, AREA, WIFI }

/** One row in the Chats list. */
data class ConversationRowUi(
    val id: String,
    val kind: ChatKind,
    val title: String,
    val initials: String,
    val preview: String,
    val time: String,
    val unread: Int = 0,
)

/** The two always-there rooms at the top of the list. */
data class RoomRowUi(val kind: ChatKind, val title: String, val subtitle: String, val unread: Int = 0, val id: String? = null)

/** Thread header: avatar initials, title and the line under it. */
data class ThreadHeaderUi(val kind: ChatKind, val title: String, val initials: String, val subtitle: String)

/** What a message shows. */
sealed interface ChatBody {
    data class Text(val text: String) : ChatBody
    data class Voice(val duration: String) : ChatBody
    data class Location(val title: String, val detail: String, val plusCode: String) : ChatBody
    data class Task(val task: TaskUi) : ChatBody
    data class Iou(val iou: IouUi) : ChatBody
    data class CheckIn(val title: String, val detail: String, val carrierLabel: String) : ChatBody
    data class Pin(val pin: PinUi) : ChatBody
}

/** One line of a thread: a message (ours or theirs) or a quiet system line. */
sealed interface ThreadItemUi {
    val id: String

    data class Message(
        override val id: String,
        val body: ChatBody,
        val time: String,
        val outgoing: Boolean,
        val carrier: Carrier,
        val delivery: Delivery? = null,
        /** Shown above incoming messages in groups and rooms. */
        val sender: String? = null,
        /** Text for Copy and "Make it a task". */
        val copyText: String = "",
        /** Author key (hex), for Block. */
        val author: String? = null,
    ) : ThreadItemUi

    data class System(override val id: String, val text: String) : ThreadItemUi
}

/** The input bar while a voice note is being recorded (or is ready to send). */
data class RecordingUi(val elapsed: String, val done: Boolean)

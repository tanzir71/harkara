package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.ui.chats.ChatBody
import io.github.tanzir71.harkara.ui.chats.ChatKind
import io.github.tanzir71.harkara.ui.chats.ChatThreadScreen
import io.github.tanzir71.harkara.ui.chats.ChatsListScreen
import io.github.tanzir71.harkara.ui.chats.ConversationRowUi
import io.github.tanzir71.harkara.ui.chats.LocationShareSheetContent
import io.github.tanzir71.harkara.ui.chats.MessageActionsSheetContent
import io.github.tanzir71.harkara.ui.chats.NewChatSheetContent
import io.github.tanzir71.harkara.ui.chats.RecordingUi
import io.github.tanzir71.harkara.ui.chats.RoomRowUi
import io.github.tanzir71.harkara.ui.chats.ThreadHeaderUi
import io.github.tanzir71.harkara.ui.chats.ThreadItemUi
import io.github.tanzir71.harkara.ui.components.Delivery
import io.github.tanzir71.harkara.ui.components.HarkaraTab
import io.github.tanzir71.harkara.ui.components.SheetSurface
import io.github.tanzir71.harkara.ui.components.TabBar
import io.github.tanzir71.harkara.ui.components.TaskUi
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Content of reference screen 04 (Road 7 building) and the Chats list. */
object ChatSampleData {
    val meshSix = ConnectivityState(available = setOf(Carrier.MESH, Carrier.QR, Carrier.SAVED), meshPeers = 6, relaysConfigured = 5)

    val header = ThreadHeaderUi(ChatKind.GROUP, "Road 7 building", "R7", "18 members · 6 nearby")

    val thread: List<ThreadItemUi> = listOf(
        ThreadItemUi.Message("m1", ChatBody.Text("Pharmacy on Road 8 has insulin, I just checked."), "21:14", outgoing = false, carrier = Carrier.MESH, sender = "Rafi"),
        ThreadItemUi.Message("m2", ChatBody.Text("Great, can someone pick up two pens?"), "21:14", outgoing = true, carrier = Carrier.ONLINE, delivery = Delivery.Read),
        ThreadItemUi.System("s1", "You made that message a task · ৳120"),
        ThreadItemUi.Message(
            "m3",
            ChatBody.Task(TaskUi("Task · Pickup", "৳120", "Two insulin pens from the Road 8 pharmacy to House 12", 1, "Accepted 21:16", "Sumon · 38 done")),
            "", outgoing = true, carrier = Carrier.ONLINE,
        ),
        ThreadItemUi.Message("m4", ChatBody.Location("On my way", "Road 8 pharmacy · about 100 m", "7MMG+QX Dhaka"), "", outgoing = false, carrier = Carrier.MESH, sender = "Sumon"),
        ThreadItemUi.Message("m5", ChatBody.CheckIn("I'm safe", "Home · 21:40", "by SMS"), "", outgoing = false, carrier = Carrier.SMS, sender = "Ma"),
    )

    val rooms = listOf(
        RoomRowUi(ChatKind.WIFI, "Everyone on this Wi-Fi", "3 phones here · no internet needed"),
        RoomRowUi(ChatKind.AREA, "Dhanmondi 27 channel", "Anyone nearby · no names, gone after a day", unread = 2),
    )

    val conversations = listOf(
        ConversationRowUi("c1", ChatKind.GROUP, "Road 7 building", "R7", "I'm safe · Home · 21:40", "21:40", unread = 2),
        ConversationRowUi("c2", ChatKind.DM, "Sumon", "Su", "On my way · 7MMG+QX Dhaka", "21:20"),
        ConversationRowUi("c3", ChatKind.DM, "Ammu", "Am", "Voice note", "19:02"),
        ConversationRowUi("c4", ChatKind.GROUP, "Hall 3 canteen", "H3", "Khichuri is ready, come before 2", "1 d"),
    )
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class ChatScreenshotTest {
    @Test
    fun chatThread() = captureDayNight("screen-04-chat-thread", padded = false) {
        ChatThreadScreen(
            ChatSampleData.header,
            ChatSampleData.thread,
            ChatSampleData.meshSix,
            hint = "Message · goes by mesh",
            pillLabel = "Nearby · 6",
        )
    }

    @Test
    fun chatsList() = captureDayNight("screen-04b-chats-list", padded = false) {
        Column(Modifier.fillMaxSize()) {
            ChatsListScreen(ChatSampleData.rooms, ChatSampleData.conversations, SampleData.online, Modifier.weight(1f))
            TabBar(HarkaraTab.Chats, {})
        }
    }

    @Test
    fun chatsListEmpty() = captureDayNight("screen-04c-chats-empty", padded = false) {
        Column(Modifier.fillMaxSize()) {
            ChatsListScreen(
                listOf(
                    RoomRowUi(ChatKind.WIFI, "Everyone on this Wi-Fi", "No one else here yet · no internet needed"),
                    RoomRowUi(ChatKind.AREA, "Dhanmondi 27 channel", "Anyone nearby · no names, gone after a day"),
                ),
                emptyList(),
                ConnectivityState.Saved,
                Modifier.weight(1f),
            )
            TabBar(HarkaraTab.Chats, {})
        }
    }

    @Test
    fun wifiRoomRecording() = captureDayNight("screen-04d-wifi-room", padded = false) {
        ChatThreadScreen(
            ThreadHeaderUi(ChatKind.DM, "Sumon", "Su", "Encrypted · npub180cv…h6w6"),
            listOf(
                ThreadItemUi.Message("v1", ChatBody.Voice("0:12"), "20:05", outgoing = false, carrier = Carrier.NODE),
                ThreadItemUi.Message("t1", ChatBody.Text("Power is out on Road 7, the hall Wi-Fi still works"), "20:06", outgoing = true, carrier = Carrier.NODE, delivery = Delivery.Delivered),
                ThreadItemUi.Message("t2", ChatBody.Text("Saved until a route opens"), "20:07", outgoing = true, carrier = Carrier.SAVED, delivery = Delivery.Saved),
            ),
            ConnectivityState(available = setOf(Carrier.NODE, Carrier.QR, Carrier.SAVED)),
            hint = "Message · goes by local Wi-Fi",
            recording = RecordingUi("0:04", done = false),
        )
    }

    @Test
    fun newChatSheet() = captureDayNight("screen-chats-new", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface {
                NewChatSheetContent(SampleData.npub, emptyList(), { _, _ -> true }, { _, _ -> }, {}, Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
        }
    }

    @Test
    fun newGroupSheet() = captureDayNight("screen-chats-new-group", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface {
                NewChatSheetContent(
                    SampleData.npub,
                    listOf("a" to "Sumon", "b" to "Ma", "c" to "Rafi", "d" to "npub1x7q2…9kfd"),
                    { _, _ -> true }, { _, _ -> }, {},
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    initialTab = 1,
                )
            }
        }
    }

    @Test
    fun messageActionsAndLocation() = captureDayNight("screen-chats-sheets", padded = false) {
        Box(Modifier.fillMaxSize().background(HarkaraTheme.colors.bgCanvas)) {
            SheetSurface(Modifier.align(Alignment.TopCenter)) { MessageActionsSheetContent({}, {}, {}, Modifier.padding(bottom = 8.dp)) }
            SheetSurface(Modifier.align(Alignment.BottomCenter)) { LocationShareSheetContent({ _, _, _ -> }, Modifier.padding(bottom = 8.dp)) }
        }
    }
}

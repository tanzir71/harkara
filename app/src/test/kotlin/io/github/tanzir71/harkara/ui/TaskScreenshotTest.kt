package io.github.tanzir71.harkara.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.tasks.Handoff
import io.github.tanzir71.harkara.core.tasks.Reputation
import io.github.tanzir71.harkara.core.tasks.TaskOffers
import io.github.tanzir71.harkara.core.tasks.TaskStateMachine
import io.github.tanzir71.harkara.core.tasks.TaskSteps
import io.github.tanzir71.harkara.core.tasks.TaskTypes
import io.github.tanzir71.harkara.tasks.HandoffDisplay
import io.github.tanzir71.harkara.tasks.TaskAction
import io.github.tanzir71.harkara.ui.components.HarkaraTab
import io.github.tanzir71.harkara.ui.components.SheetSurface
import io.github.tanzir71.harkara.ui.components.TabBar
import io.github.tanzir71.harkara.ui.screens.HandoffEntrySheetContent
import io.github.tanzir71.harkara.ui.screens.TaskComposeSheetContent
import io.github.tanzir71.harkara.ui.screens.TaskDetailScreen
import io.github.tanzir71.harkara.ui.screens.TasksScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Screen 05 (task handoff), the Tasks list and the task composer, Day and Night. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class TaskScreenshotTest {
    private val types = TaskTypes.parse(File("../data/task-types.json").readText())
    private val requester = KeyPair.fromHex("11".repeat(32))
    private val runner = KeyPair.fromHex("22".repeat(32))
    // 20:58, 21:16 and 21:30 in Dhaka on 25 Nov 2026.
    private val posted = 1_764_082_680L

    init {
        Handoff.scryptN = 1 shl 8
    }

    private val offerEvent = TaskOffers.build(
        requester, "insulin", "Two insulin pens from the Road 8 pharmacy to House 12", "pickup", 120, 23.7465, 90.376, "", posted,
        mapOf("from" to "Road 8 pharmacy", "to" to "House 12"), expiresAt = null,
    )
    private val offer = TaskOffers.parse(offerEvent)!!
    private val accept = TaskSteps.accept(runner, offer, posted + 60, "Sumon Ahmed")
    private val confirm = TaskSteps.confirm(requester, offer, accept, Handoff.commitment("482913", offer.address, runner.pubkeyHex), posted + 18 * 60)
    private val picked = TaskSteps.pickedUp(runner, offer, posted + 32 * 60)
    private val state = TaskStateMachine.reduce(offerEvent, listOf(accept, confirm, picked), posted + 3600)!!

    /** A fixed (unverified) token so the QR and digits are identical on every run. */
    private val token = Event("ab".repeat(32), requester.pubkeyHex, posted, Kinds.HANDOFF_TOKEN, emptyList(), "", "cd".repeat(64))

    @Test
    fun taskHandoff() = captureDayNight("screen-05-task-handoff", padded = false) {
        TaskDetailScreen(
            state = state,
            me = requester.pubkeyHex,
            types = types,
            connectivity = SampleData.online,
            next = TaskAction.RECORD_PAYMENT,
            handoff = HandoffDisplay(token, "482913"),
            reputation = { Reputation(38, 30, 4.8) },
            mutualContacts = { 2 },
        )
    }

    @Test
    fun tasksList() = captureDayNight("screen-tasks-list", padded = false) {
        val open = TaskStateMachine.reduce(
            TaskOffers.build(runner, "copies", "2 sets of photocopies, Nilkhet to TSC", "print", 60, 23.7330, 90.3880, "", posted,
                mapOf("shop" to "Nilkhet", "copies" to "2 sets", "to" to "TSC"), expiresAt = null), emptyList(), posted + 60,
        )!!
        Column(Modifier.fillMaxSize()) {
            TasksScreen(listOf(state, open), requester.pubkeyHex, types, SampleData.online, Modifier.weight(1f), reputation = { Reputation(38, 30, 4.8) })
            TabBar(HarkaraTab.Tasks, {})
        }
    }

    @Test
    fun taskCompose() = captureDayNight("screen-task-compose", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface { TaskComposeSheetContent(types, { _, _, _, _, _ -> }, initialTitle = "Two insulin pens", initialFields = mapOf("from" to "Road 8 pharmacy")) }
        }
    }

    @Test
    fun handoffEntry() = captureDayNight("screen-task-code-entry", padded = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SheetSurface { HandoffEntrySheetContent({}, wrong = false) }
        }
    }
}

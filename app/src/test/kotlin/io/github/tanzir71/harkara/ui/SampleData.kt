package io.github.tanzir71.harkara.ui

import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.ConnectivityState
import io.github.tanzir71.harkara.ui.components.IouUi
import io.github.tanzir71.harkara.ui.components.PinUi
import io.github.tanzir71.harkara.ui.components.PostUi
import io.github.tanzir71.harkara.ui.components.TaskUi

/** Content from the Figma reference screens, used by screenshot and UI tests. */
object SampleData {
    val posts = listOf(
        PostUi(
            id = "p1",
            category = Category.TRAFFIC,
            place = "Mirpur Rd · 350 m · 12 min",
            text = "Gridlocked from Science Lab to Kalabagan. Green Road is moving, take that.",
            detail = "Gridlock",
            expiryLeft = "2 h 48 m left",
            score = 24,
            comments = 6,
            carrier = Carrier.ONLINE,
            carrierLabel = "via relays",
        ),
        PostUi(
            id = "p2",
            category = Category.STOCK,
            place = "Satmasjid Rd · 1.1 km · 25 min",
            text = "Octane available at the pump on Satmasjid Road. About 15 bikes in the queue.",
            detail = "Fuel · available",
            expiryLeft = "23 h left",
            score = 41,
            comments = 9,
            carrier = Carrier.BRIDGE,
            carrierLabel = "via a nearby phone",
        ),
        PostUi(
            id = "p3",
            category = Category.ALERT,
            place = "Road 27 · 600 m · 40 min",
            text = "Waterlogging near Rapa Plaza after the rain, ankle deep. Rickshaws still crossing.",
            detail = "Waterlogging",
            expiryLeft = "11 h left",
            score = 17,
            comments = 3,
            carrier = Carrier.MESH,
            carrierLabel = "via mesh · 2 hops",
        ),
    )

    val task = TaskUi(
        type = "Task · Pickup",
        price = "৳120",
        title = "Medicine from Lazz Pharma, Kalabagan to House 12, Road 7",
        stepsDone = 2,
        status = "Picked up",
        runner = "Sumon · 38 done",
    )

    val iou = IouUi(
        amount = "৳500",
        incoming = true,
        what = "Rafi owes you · Lunch at Star Kabab",
        status = "Waiting for Rafi to sign",
        date = "Today",
        agreed = false,
    )

    val pin = PinUi(
        title = "Water point",
        detail = "Tubewell behind the lake mosque, open 7:00 to 10:00",
        visibility = "Public",
        yes = 4,
        no = 1,
    )

    val online = ConnectivityState(
        available = setOf(Carrier.ONLINE, Carrier.QR, Carrier.SAVED),
        relaysReachable = 3,
        relaysConfigured = 5,
        waiting = 4,
    )

    /** The NIP-19 example npub, so screenshots need no crypto. */
    const val npub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"

    /** The NIP-49 spec vector, a real ncryptsec for the QR golden. */
    const val ncryptsec = "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"

    val meshOnly = ConnectivityState(
        available = setOf(Carrier.MESH, Carrier.QR, Carrier.SAVED),
        meshPeers = 12,
        relaysConfigured = 5,
    )
}

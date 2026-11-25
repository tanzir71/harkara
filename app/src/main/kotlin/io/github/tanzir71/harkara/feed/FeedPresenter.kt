package io.github.tanzir71.harkara.feed

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.Carrier
import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.feed.DetailTags
import io.github.tanzir71.harkara.core.geo.GeoMath
import io.github.tanzir71.harkara.data.DhakaAreas
import io.github.tanzir71.harkara.ui.components.PostUi

/** String resources for the chip values from loka's composer. */
object DetailLabels {
    val stockItems = listOf(
        "fuel" to R.string.detail_fuel, "gas" to R.string.detail_gas, "power" to R.string.detail_power,
        "water" to R.string.detail_water, "cash" to R.string.detail_cash, "medicine" to R.string.detail_medicine,
    )
    val stockStatus = listOf("available" to R.string.detail_available, "low" to R.string.detail_low, "out" to R.string.detail_out)
    val traffic = listOf("1" to R.string.detail_traffic_1, "2" to R.string.detail_traffic_2, "3" to R.string.detail_traffic_3)
    val crowd = listOf("1" to R.string.detail_crowd_1, "2" to R.string.detail_crowd_2, "3" to R.string.detail_crowd_3)
    val alert = listOf(
        "accident" to R.string.detail_accident, "protest" to R.string.detail_protest, "flood" to R.string.detail_flood,
        "closure" to R.string.detail_closure, "safety" to R.string.detail_safety,
    )

    /** Detail chip groups the composer offers for a category: tag name to (value, label). */
    fun groupsFor(category: Category?): List<Pair<String, List<Pair<String, Int>>>> = when (category) {
        Category.STOCK -> listOf(DetailTags.STOCK_ITEM to stockItems, DetailTags.STOCK_STATUS to stockStatus)
        Category.TRAFFIC -> listOf(DetailTags.TRAFFIC to traffic)
        Category.CROWD -> listOf(DetailTags.CROWD to crowd)
        Category.ALERT -> listOf(DetailTags.ALERT to alert)
        else -> emptyList()
    }

    @StringRes
    fun categoryLabel(category: Category): Int = when (category) {
        Category.TRAFFIC -> R.string.category_traffic
        Category.FOOD -> R.string.category_food
        Category.CROWD -> R.string.category_crowd
        Category.STOCK -> R.string.category_stock
        Category.ALERT -> R.string.category_alert
        Category.CHATTER -> R.string.category_chatter
    }
}

/** Turns feed items into card data: place, distance, age, detail tag, time left and how it travelled. */
class FeedPresenter(private val label: (Int, Array<out Any>) -> String, private val areas: DhakaAreas?) {
    fun age(seconds: Long): String = when {
        seconds < 60 -> label(R.string.age_now, emptyArray())
        seconds < 3600 -> label(R.string.age_minutes, arrayOf((seconds / 60).toInt()))
        seconds < 86_400 -> label(R.string.age_hours, arrayOf((seconds / 3600).toInt()))
        else -> label(R.string.age_days, arrayOf((seconds / 86_400).toInt()))
    }

    fun timeLeft(seconds: Long): String {
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        return when {
            h >= 10 || (h > 0 && m == 0) -> label(R.string.expiry_left_h, arrayOf(h))
            h > 0 -> label(R.string.expiry_left_hm, arrayOf(h, m))
            else -> label(R.string.expiry_left_m, arrayOf(m.coerceAtLeast(1)))
        }
    }

    fun detail(item: FeedItem): String? {
        val d = item.post.details
        fun find(list: List<Pair<String, Int>>, value: String?) = list.firstOrNull { it.first == value }?.let { label(it.second, emptyArray()) }
        return when (item.post.category) {
            Category.STOCK -> listOfNotNull(find(DetailLabels.stockItems, d[DetailTags.STOCK_ITEM]), find(DetailLabels.stockStatus, d[DetailTags.STOCK_STATUS]))
                .joinToString(" · ").ifEmpty { null }
            Category.TRAFFIC -> find(DetailLabels.traffic, d[DetailTags.TRAFFIC])
            Category.CROWD -> find(DetailLabels.crowd, d[DetailTags.CROWD])
            Category.ALERT -> find(DetailLabels.alert, d[DetailTags.ALERT])
            else -> null
        }
    }

    fun carrierLabel(carrier: Carrier): String = label(
        when (carrier) {
            Carrier.ONLINE -> R.string.via_relays
            Carrier.BRIDGE -> R.string.via_bridge
            Carrier.NODE -> R.string.via_node
            Carrier.MESH -> R.string.via_mesh
            Carrier.SMS -> R.string.via_sms
            Carrier.QR -> R.string.via_qr
            Carrier.SAVED -> R.string.via_saved
        },
        emptyArray(),
    )

    fun toUi(item: FeedItem, nowSeconds: Long): PostUi {
        val loc = item.post.location
        val place = areas?.nearest(loc.lat, loc.lng)?.name
        return PostUi(
            id = item.post.id,
            category = item.post.category,
            place = listOfNotNull(place, GeoMath.distanceLabel(item.distanceMeters), age(nowSeconds - item.post.createdAt)).joinToString(" · "),
            text = item.post.text,
            detail = detail(item),
            expiryLeft = item.post.expiresAt?.let { timeLeft(it - nowSeconds) },
            score = item.moderation.score,
            comments = 0,
            carrier = item.carrier,
            carrierLabel = carrierLabel(item.carrier),
        )
    }
}

@Composable
fun rememberFeedPresenter(areas: DhakaAreas?): FeedPresenter {
    val context = LocalContext.current
    return FeedPresenter({ id, args -> context.getString(id, *args) }, areas)
}

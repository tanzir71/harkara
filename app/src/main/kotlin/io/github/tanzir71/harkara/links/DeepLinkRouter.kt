package io.github.tanzir71.harkara.links

import android.content.Intent
import io.github.tanzir71.harkara.core.links.HarkaraLink
import io.github.tanzir71.harkara.core.links.HarkaraLinks

/**
 * Reads what an Intent hands the app: `harkara:` links and `https://tanzir71.github.io/harkara/o/#…` links
 * (VIEW), and text shared or pasted into the app (SEND text/plain: a forwarded SMS with an HK1 payload, a QR
 * text, a link). The grammar lives in core ([HarkaraLinks]); [LinkInbox] acts on the result.
 */
object DeepLinkRouter {
    fun parse(intent: Intent?): HarkaraLink? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString?.let(HarkaraLinks::parse)
        Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(HarkaraLinks::parse)
        else -> null
    }

    fun parse(text: String): HarkaraLink? = HarkaraLinks.parse(text)
}

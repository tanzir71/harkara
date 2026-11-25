package io.github.tanzir71.harkara.core.lan

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** One plain HTTP answer from [LanRelayServer]. Bodies are streamed, so an 18 MB APK never sits in memory. */
class HttpResponse(
    val status: Int,
    val contentType: String,
    val length: Long,
    val headers: Map<String, String> = emptyMap(),
    val open: () -> InputStream,
) {
    val reason: String
        get() = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Status"
        }

    companion object {
        fun bytes(body: ByteArray, type: String, status: Int = 200): HttpResponse =
            HttpResponse(status, type, body.size.toLong()) { ByteArrayInputStream(body) }

        fun text(body: String, type: String = "text/plain; charset=utf-8", status: Int = 200): HttpResponse =
            bytes(body.encodeToByteArray(), type, status)

        fun file(file: File, type: String, downloadName: String? = null): HttpResponse = HttpResponse(
            200, type, file.length(),
            headers = downloadName?.let { mapOf("Content-Disposition" to "attachment; filename=\"$it\"") }.orEmpty(),
        ) { file.inputStream() }
    }
}

/** Answers plain HTTP GETs on a [LanRelayServer]; null falls through to the server's own page. */
fun interface HttpHandler {
    fun handle(path: String): HttpResponse?
}

/**
 * What a phone serves to people next to it, over the Wi-Fi or its hotspot: a small bilingual download page,
 * the APK it is running (phone-to-phone install), `latest.json` for peers' update cards, and, on a
 * neighbourhood node, the offline map packs and the emergency handbook. Paths are fixed names, never file
 * paths from the request.
 */
class NodeSite(
    private val app: () -> AppFile?,
    private val mapPacks: () -> List<File> = { emptyList() },
    private val handbook: ((String) -> ByteArray?)? = null,
    private val node: Boolean = false,
) : HttpHandler {
    /** The installed APK and what it is. */
    data class AppFile(val file: File, val versionName: String, val versionCode: Int, val sha256: String)

    override fun handle(path: String): HttpResponse? {
        val p = path.substringBefore('?').substringBefore('#')
        val apk = app()
        return when {
            p == "/" || p == "/index.html" -> HttpResponse.text(page(apk), "text/html; charset=utf-8")
            p == APK_PATH -> apk?.let { HttpResponse.file(it.file, APK_TYPE, "harkara-${it.versionName}.apk") } ?: notFound()
            p == "/latest.json" -> apk?.let { HttpResponse.text(latestJson(it), "application/json") } ?: notFound()
            node && p.startsWith("/maps/") -> {
                val name = p.removePrefix("/maps/")
                mapPacks().firstOrNull { it.name == name && SAFE_NAME.matches(name) && ".." !in name }?.let { HttpResponse.file(it, "application/octet-stream", it.name) }
                    ?: notFound()
            }
            node && p.startsWith("/handbook/") -> {
                val name = p.removePrefix("/handbook/")
                if (!SAFE_PATH.matches(name) || ".." in name) return notFound()
                handbook?.invoke(name)?.let { HttpResponse.bytes(it, if (name.endsWith(".json")) "application/json" else "text/markdown; charset=utf-8") }
                    ?: notFound()
            }
            else -> notFound()
        }
    }

    private fun notFound() = HttpResponse.text("Not found\n", status = 404)

    private fun latestJson(apk: AppFile): String =
        """{"version":"${apk.versionName}","versionCode":${apk.versionCode},"bytes":${apk.file.length()},"sha256":"${apk.sha256}","url":"$APK_PATH"}"""

    /** The download page: Bangla first, English below, the same tokens as the app and the landing site. */
    fun page(apk: AppFile?): String {
        val mb = apk?.let { "%.1f MB".format(java.util.Locale.ROOT, it.file.length() / 1_048_576.0) } ?: ""
        val packs = if (node) mapPacks() else emptyList()
        val extra = buildString {
            if (packs.isNotEmpty()) {
                append("<h2>ম্যাপ প্যাক · Map packs</h2><ul>")
                packs.forEach { append("<li><a href=\"/maps/${esc(it.name)}\">${esc(it.name)}</a> · ${"%.1f".format(java.util.Locale.ROOT, it.length() / 1_048_576.0)} MB</li>") }
                append("</ul>")
            }
            if (node && handbook != null) {
                append("<h2>জরুরি হ্যান্ডবুক · Emergency handbook</h2><p><a href=\"/handbook/index.json\">index.json</a></p>")
            }
        }
        val download = if (apk == null) {
            "<p>APK not available on this phone.</p>"
        } else {
            """<a class="btn" href="$APK_PATH">Harkara ${esc(apk.versionName)} · $mb</a>
<p class="mono">SHA-256 ${esc(apk.sha256)}</p>"""
        }
        return """<!doctype html>
<html lang="bn"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Harkara · হরকরা</title>
<style>
:root{--bg:#F4F2ED;--surface:#FFFFFF;--ink:#18191C;--muted:#5F6064;--line:#E2DED5;--accent:#E6AE19;--mesh:#6A50D2}
@media (prefers-color-scheme:dark){:root{--bg:#101113;--surface:#18191C;--ink:#F4F2ED;--muted:#9A978F;--line:#2C2E33;--mesh:#8A73E6}}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,sans-serif}
main{max-width:520px;margin:0 auto;padding:24px 16px}
.card{background:var(--surface);border:1px solid var(--line);border-radius:16px;padding:16px;margin:16px 0}
.btn{display:block;text-align:center;background:var(--ink);color:var(--bg);border-radius:999px;padding:14px 16px;text-decoration:none;font-weight:600}
.mono{font-family:ui-monospace,monospace;font-size:12px;color:var(--muted);word-break:break-all}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--mesh);margin-right:6px}
h1{font-size:24px;margin:0}h2{font-size:16px;margin:16px 0 4px}p,li{color:var(--muted)}a{color:var(--ink)}
</style></head><body><main>
<p><span class="dot"></span>${if (node) "Neighbourhood node · পাড়ার নোড" else "Phone to phone · ফোন থেকে ফোনে"}</p>
<h1>Harkara · হরকরা</h1>
<div class="card">$download
<h2>কীভাবে ইনস্টল করবেন</h2>
<ol><li>উপরের বোতাম চেপে APK নামান।</li><li>Settings-এ এই ব্রাউজারকে "Install unknown apps" অনুমতি দিন।</li><li>APK খুলে Install চাপুন।</li></ol>
<h2>How to install</h2>
<ol><li>Tap the button above to download the APK.</li><li>Allow this browser to "Install unknown apps" in Settings.</li><li>Open the APK and tap Install.</li></ol>
<p>Check the SHA-256 against the one on tanzir71.github.io/harkara when you are online.</p>
</div>$extra
</main></body></html>
"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        const val APK_PATH = "/harkara.apk"
        const val APK_TYPE = "application/vnd.android.package-archive"
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,80}")
        private val SAFE_PATH = Regex("(?:[A-Za-z0-9_-]{1,40}/)?[A-Za-z0-9_-][A-Za-z0-9._-]{0,79}")
    }
}

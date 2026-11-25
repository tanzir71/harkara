package io.github.tanzir71.harkara.tools

import android.Manifest
import android.app.Application
import android.content.Context
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.core.links.HarkaraLink
import io.github.tanzir71.harkara.core.links.HarkaraLinks
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import io.github.tanzir71.harkara.core.wire.QrTransport
import io.github.tanzir71.harkara.core.wire.QrAssembler
import io.github.tanzir71.harkara.ui.screens.MAX_SCANNED_CHARACTERS
import io.github.tanzir71.harkara.ui.screens.PasteLinkSheetContent
import io.github.tanzir71.harkara.ui.screens.QrScanControl
import io.github.tanzir71.harkara.ui.screens.QrScannerPreview
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import io.github.tanzir71.harkara.ui.screenshotOptions
import io.github.tanzir71.harkara.ui.components.SheetSurface
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** No camera: a fake adapter/permission result and QR pixels generated in memory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w360dp-h800dp-mdpi")
class QrScanTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val permissions = Permissions()
    private val factory = CameraFactory()

    private class Camera(context: Context) : QrCamera {
        override val view = View(context)
        var starts = 0
        var running = false
        var read: (String) -> Unit = {}
        var error: () -> Unit = {}
        override fun start(onRead: (String) -> Unit, onError: () -> Unit) {
            starts++; running = true; read = onRead; error = onError
        }
        override fun stop() { running = false }
    }

    private class CameraFactory : QrCameraFactory {
        val created = mutableListOf<Camera>()
        override fun create(context: Context): QrCamera = Camera(context).also { created += it }
        val camera get() = created.last()
    }

    private class Permissions : ActivityResultRegistryOwner {
        var asked = 0
        var code = 0
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertEquals(Manifest.permission.CAMERA, input)
                asked++; code = requestCode
            }
        }
        fun reply(granted: Boolean) { activityResultRegistry.dispatchResult(code, granted) }
    }

    @Before fun denyCamera() { shadowOf(app).denyPermissions(Manifest.permission.CAMERA) }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    fun `scanner layout in Day and Night uses a fake camera`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val night = mutableStateOf(false)
        compose.setContent { HarkaraTheme(night = night.value) { SheetSurface { QrScanControl({}, cameraFactory = factory) } } }
        compose.onNodeWithText(app.getString(R.string.qr_scan_start)).performClick()
        for (dark in listOf(false, true)) {
            compose.runOnIdle { night.value = dark }
            compose.onRoot().captureRoboImage("src/test/screenshots/screen-qr-scan-${if (dark) "night" else "day"}.png", roborazziOptions = screenshotOptions)
        }
    }

    @Test fun `permission denial creates no camera and leaves paste usable, grant scans before explicit import`() {
        val event = KeyPair.generate().signEvent(Kinds.SOS, content = "Need help", createdAt = 1_795_586_400)
        val code = QrTransport.encode(event).single()
        var imported: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides permissions) {
                HarkaraTheme { PasteLinkSheetContent({ imported = it }, cameraFactory = factory) }
            }
        }
        assertEquals(0, permissions.asked)
        assertTrue(factory.created.isEmpty())
        compose.onNodeWithText(app.getString(R.string.qr_scan_start)).performClick()
        compose.runOnIdle { permissions.reply(false) }
        compose.onNodeWithText(app.getString(R.string.qr_scan_permission)).assertIsDisplayed()
        assertTrue(factory.created.isEmpty())
        compose.onNodeWithText(app.getString(R.string.link_paste_label)).assertExists()
        compose.onNodeWithText(app.getString(R.string.qr_scan_start)).performClick()
        compose.runOnIdle { shadowOf(app).grantPermissions(Manifest.permission.CAMERA); permissions.reply(true) }
        compose.runOnIdle {
            assertTrue(factory.camera.running)
            factory.camera.read(code)
        }
        compose.runOnIdle { assertFalse(factory.camera.running); assertNull(imported) }
        compose.onNodeWithText(app.getString(R.string.link_paste_read)).performClick()
        compose.runOnIdle {
            assertEquals(code, imported)
            assertEquals(event, (HarkaraLinks.parse(imported!!) as HarkaraLink.EventLink).event)
        }
    }

    @Test fun `pause disposal and success stop the camera, stale or duplicate callbacks are ignored`() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        }
        val visible = mutableStateOf(true)
        val values = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) QrScannerPreview({ values += it }, { fail("unexpected camera failure") }, factory, Modifier)
            }
        }
        compose.runOnIdle {
            val oldRead = factory.camera.read
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            assertFalse(factory.camera.running)
            oldRead("paused")
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            assertTrue(factory.camera.running)
            assertEquals(2, factory.camera.starts)
            oldRead("stale after resume")
            assertTrue(values.isEmpty())
            factory.camera.read("harkara:g:wh0r0")
            factory.camera.read("duplicate")
            assertEquals(listOf("harkara:g:wh0r0"), values)
            assertFalse(factory.camera.running)
            visible.value = false
        }
        compose.runOnIdle {
            factory.camera.read("after disposal")
            assertFalse(factory.camera.running)
            assertEquals(1, values.size)
        }
    }

    @Test fun `module off and stop release the camera, oversized results and hardware errors preserve paste fallback`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val enabled = mutableStateOf(false)
        var scanned: String? = null
        compose.setContent { HarkaraTheme { if (enabled.value) QrScanControl({ scanned = it }, cameraFactory = factory) } }
        assertTrue(factory.created.isEmpty())
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText(app.getString(R.string.qr_scan_start)).performClick()
        compose.onNodeWithText(app.getString(R.string.qr_scan_stop)).performClick()
        compose.runOnIdle { assertFalse(factory.camera.running); assertNull(scanned) }
        compose.onNodeWithText(app.getString(R.string.qr_scan_start)).performClick()
        compose.runOnIdle { factory.camera.read("x".repeat(MAX_SCANNED_CHARACTERS + 1)) }
        compose.onNodeWithText(app.getString(R.string.qr_scan_failed)).assertIsDisplayed()
        assertNull(scanned)
        compose.onNodeWithText(app.getString(R.string.qr_scan_start)).performClick()
        compose.runOnIdle { factory.camera.error() }
        compose.onNodeWithText(app.getString(R.string.qr_scan_failed)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.qr_scan_start)).performClick()
        compose.runOnIdle { enabled.value = false }
        compose.runOnIdle { assertFalse(factory.camera.running); assertNull(scanned) }
    }

    @Test fun `camera decoder reads generated QR pixels and preserves multipart signed event bytes`() {
        val event = KeyPair.generate().signEvent(Kinds.SOS, content = "পানি বাড়ছে " + (1..80).joinToString(" ") { "%08x".format(it * 2_654_435_761L % 4_294_967_296L) }, createdAt = 1_795_586_400)
        val parts = QrTransport.encode(event, maxChars = 220)
        assertTrue(parts.size > 1)
        val reassembly = QrAssembler()
        var decoded: io.github.tanzir71.harkara.core.nostr.Event? = null
        parts.reversed().forEach { text ->
            val pixels = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 640, 640)
            val source = RGBLuminanceSource(640, 640, IntArray(640 * 640) { i -> if (pixels[i % 640, i / 640]) 0xff000000.toInt() else 0xffffffff.toInt() })
            val result = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE)).createDecoder(emptyMap<com.google.zxing.DecodeHintType, Any>()).decode(source)
            assertEquals(text, result.text)
            // The same verified reassembly used by LinkInbox receives the camera's decoded text.
            (reassembly.add(result.text) as? QrAssembler.Result.Complete)?.let { decoded = it.event }
        }
        assertEquals(event, decoded)
        assertTrue(decoded!!.verify())
    }
}

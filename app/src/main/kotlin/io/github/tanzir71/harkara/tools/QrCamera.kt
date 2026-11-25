package io.github.tanzir71.harkara.tools

import android.content.Context
import android.view.View
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/** Camera hardware boundary. Creating a camera never starts its preview or decoder. */
interface QrCamera {
    val view: View
    fun start(onRead: (String) -> Unit, onError: () -> Unit)
    fun stop()
}

fun interface QrCameraFactory {
    fun create(context: Context): QrCamera

    companion object {
        val Android = QrCameraFactory { ZxingQrCamera(it) }
    }
}

/** ZXing owns camera frames in memory; Harkara saves no camera image or recording. */
private class ZxingQrCamera(context: Context) : QrCamera {
    override val view = BarcodeView(context).apply {
        decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
    }
    private var error: (() -> Unit)? = null

    init {
        view.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() = Unit
            override fun previewStopped() = Unit
            override fun cameraClosed() = Unit
            override fun cameraError(error: Exception) { this@ZxingQrCamera.error?.invoke() }
        })
    }

    override fun start(onRead: (String) -> Unit, onError: () -> Unit) {
        error = onError
        view.decodeSingle { result -> result.text?.let(onRead) }
        view.resume()
    }

    override fun stop() {
        error = null
        view.stopDecoding()
        view.pause()
    }
}

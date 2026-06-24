package com.yourcompany.floatscan

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import java.util.EnumMap

class ScanActivity : AppCompatActivity() {

    private lateinit var barcodeView: DecoratedBarcodeView
    private var scanned = false

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            if (scanned || result == null) return
            scanned = true
            val text = result.text ?: return
            ScanResultBus.deliverScanResult(text)
            moveTaskToBack(true)
            finish()
            overridePendingTransition(0, 0)
        }

        override fun possibleResultPoints(resultPoints: MutableList<com.google.zxing.ResultPoint>?) {
            // no-op
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        barcodeView = findViewById(R.id.barcodeView)
        barcodeView.statusView?.visibility = android.view.View.GONE
        barcodeView.viewFinder?.visibility = android.view.View.GONE

        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            this[DecodeHintType.POSSIBLE_FORMATS] = listOf(
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.EAN_13,
                BarcodeFormat.ITF,
                BarcodeFormat.CODABAR
            )
            this[DecodeHintType.CHARACTER_SET] = "UTF-8"
            this[DecodeHintType.TRY_HARDER] = true
        }

        val formats = hints[DecodeHintType.POSSIBLE_FORMATS] as List<BarcodeFormat>
        barcodeView.barcodeView.decoderFactory = DefaultDecoderFactory(
            formats,
            hints,
            "UTF-8",
            0
        )
        barcodeView.decodeContinuous(callback)
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }
}

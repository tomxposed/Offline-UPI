package com.tom.offlineupi

import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * @Author: Tom
 * @Date: 18-05-2025
 */
internal class QRCodeAnalyzer(
    private val barcodeFormats: IntArray,
    private val onSuccess: ((String) -> Unit),
    private val onFailure: ((Exception) -> Unit),
) : ImageAnalysis.Analyzer {

    private val barcodeScanner by lazy {
        val optionsBuilder = if (barcodeFormats.size > 1) {
            BarcodeScannerOptions.Builder().setBarcodeFormats(barcodeFormats.first(), *barcodeFormats.drop(1).toIntArray())
        } else {
            BarcodeScannerOptions.Builder().setBarcodeFormats(barcodeFormats.firstOrNull() ?: Barcode.FORMAT_UNKNOWN)
        }
        try {
            BarcodeScanning.getClient(optionsBuilder.build())
        } catch (e: Exception) { // catch if for some reason MlKitContext has not been initialized
            onFailure(e)
            null
        }
    }

    @Volatile
    private var scanFailure = false
    private var failureTimestamp = 0L

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (imageProxy.image == null) return

        if (scanFailure && System.currentTimeMillis() - failureTimestamp < 1000L) {
            imageProxy.close()
            return
        }

        scanFailure = false
        barcodeScanner?.let { scanner ->
            scanner.process(imageProxy.toInputImage())
                .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it }?.let { barcode ->
                    val rawValue = barcode?.rawValue
                    rawValue?.let {
                        Log.d("@@VK: Barcode : ", it)
                        onSuccess(it)
                    }
                } }
                .addOnFailureListener {
                    scanFailure = true
                    failureTimestamp = System.currentTimeMillis()
                    onFailure(it)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    @ExperimentalGetImage
    @Suppress("UnsafeCallOnNullableType")
    private fun ImageProxy.toInputImage() = InputImage.fromMediaImage(image!!, imageInfo.rotationDegrees)
}
package com.tom.offlineupi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.tom.offlineupi.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.net.toUri
import java.net.URLDecoder


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        analysisExecutor = Executors.newSingleThreadExecutor()
        initCamera()
        initCallPermission()
    }


    private fun initCamera() {
        requestCameraPermissionIfMissing { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this@MainActivity, "permission missing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initCallPermission() {
        requestCallPermissionIfMissing { granted ->
            if (!granted) {
                Toast.makeText(this@MainActivity, "permission missing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = try {
            ProcessCameraProvider.getInstance(this)
        } catch (e: Exception) {
            return
        }

        cameraProviderFuture.addListener({
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                return@addListener
            }

            val preview = Preview.Builder().build()
                .also { it.surfaceProvider = binding.included.previewView.surfaceProvider }

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    ).build()
                )
                .build()

            val qrCodeAnalyzer = QRCodeAnalyzer(
                barcodeFormats = intArrayOf(Barcode.FORMAT_QR_CODE),
                onSuccess = { barcode ->
                    runOnUiThread {
                        imageAnalysis.clearAnalyzer() // prevent further scans
                        onSuccess(barcode)
                    }
                },
                onFailure = { exception -> onFailure(exception) }
            )

            imageAnalysis.setAnalyzer(analysisExecutor, qrCodeAnalyzer)

            cameraProvider.unbindAll()
            try {
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                binding.included.overlayView.setViewFinder()
            } catch (e: Exception) {
                binding.included.overlayView.visibility = View.INVISIBLE
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFailure(exception: Exception) {

    }


    private fun getPaymentAddressFromURL(upiUrl: String): String? {
        // Check if it's a valid UPI URL
        if (!upiUrl.startsWith("upi://pay", ignoreCase = true)) {
            return null
        }

        val uri = upiUrl.substringAfter("?", "")
        val params = uri.split("&")

        for (param in params) {
            val pair = param.split("=")
            if (pair.size == 2 && pair[0] == "pa") {
                return URLDecoder.decode(pair[1], "UTF-8")
            }
        }
        return null // Return null if "pa" not found
    }

    private fun getPaymentAddressFromEMV(payload: String): String? {
        var index = 0
        while (index < payload.length - 4) {
            val id = payload.substring(index, index + 2)
            val length = payload.substring(index + 2, index + 4).toIntOrNull() ?: return null
            val value = payload.substring(index + 4, index + 4 + length)

            if (id == "26") {
                // Search inside ID 26 for UPI ID field (sub-fields start with 00, 01, etc.)
                var subIndex = 0
                while (subIndex < value.length - 4) {
                    val subId = value.substring(subIndex, subIndex + 2)
                    val subLength = value.substring(subIndex + 2, subIndex + 4).toIntOrNull() ?: return null
                    val subValue = value.substring(subIndex + 4, subIndex + 4 + subLength)

                    // UPI ID is usually under subfield "01"
                    if (subId == "01" || subId == "02") {
                        return subValue
                    }

                    subIndex += 4 + subLength
                }
            }

            index += 4 + length
        }
        return null
    }

    private fun identifyUpiPayloadType(payload: String) = when {
        payload.startsWith("000201") -> "EMV"
        payload.startsWith("upi://pay", true) -> "URL"
        else -> "Unknown"
    }

    private fun onSuccess(result: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val paymentAddress = when (identifyUpiPayloadType(result)) {
            "URL" -> getPaymentAddressFromURL(result)
            else -> getPaymentAddressFromEMV(result)
        }

        if (paymentAddress.isNullOrBlank()) {
            Toast.makeText(this, "Invalid QR", Toast.LENGTH_SHORT).show()
            return
        }

        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("UPI Id", paymentAddress))
        Toast.makeText(this, "Copied: $paymentAddress", Toast.LENGTH_SHORT).show()

        requestCallPermissionIfMissing { granted ->
            if (granted) {
                try {
                    val ussd = "*99*1*3#"
                    val uri = ("tel:" + ussd.replace("#", Uri.encode("#"))).toUri()
                    startActivity(Intent(Intent.ACTION_CALL, uri))
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to call USSD", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "CALL_PHONE permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        // Optionally restart camera after a delay or event
        // initCamera()
    }

    private fun requestCameraPermissionIfMissing(onResult: ((Boolean) -> Unit)) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
        } else {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    private fun requestCallPermissionIfMissing(onResult: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
        } else {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                onResult(it)
            }.launch(Manifest.permission.CALL_PHONE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
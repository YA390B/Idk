package com.ya39ob.gesture

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : ComponentActivity() {

    private lateinit var landmarker: HandLandmarker
    private lateinit var preview: PreviewView
    private lateinit var label: TextView
    private val exec = Executors.newSingleThreadExecutor()
    private var cur: String? = null
    private var count = 0
    private var lastFire = 0L

    private val askCam = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else label.text = "Khass permission dyal camera"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        preview = PreviewView(this)
        root.addView(preview, ViewGroup.LayoutParams(-1, -1))
        label = TextView(this).apply {
            textSize = 26f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            gravity = Gravity.CENTER
            setPadding(24, 32, 24, 48)
            text = "Wer 2idek l-camera"
        }
        root.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        landmarker = HandLandmarker.createFromOptions(
            this,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .build()
        )
        askCam.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()
            val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(exec) { proxy ->
                try {
                    val bmp = proxy.toBitmap()
                    val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
                    val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    val res = landmarker.detect(BitmapImageBuilder(rot).build())
                    handle(res)
                } finally {
                    proxy.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, prev, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun d(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                  b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float =
        hypot(a.x() - b.x(), a.y() - b.y())

    private fun classify(res: HandLandmarkerResult): String? {
        if (res.landmarks().isEmpty()) return null
        val p = res.landmarks()[0]
        val w = p[0]
        fun up(t: Int, pip: Int) = d(p[t], w) > d(p[pip], w) * 1.1f
        val idx = up(8, 6); val mid = up(12, 10); val ring = up(16, 14); val pink = up(20, 18)
        val thumbOut = d(p[4], p[17]) > d(p[3], p[17]) * 1.05f && d(p[4], p[5]) > d(p[2], p[5]) * 0.9f
        val n = listOf(idx, mid, ring, pink).count { it }
        return when {
            n == 0 -> if (thumbOut && p[4].y() < p[0].y() - 0.12f && p[4].y() < p[8].y() - 0.05f) "thumb" else "fist"
            n == 4 -> if (thumbOut) "open" else "four"
            n == 1 && idx -> "one"
            n == 2 && idx && mid -> "two"
            n == 3 && idx && mid && ring -> "three"
            else -> null
        }
    }

    private fun handle(res: HandLandmarkerResult) {
        val g = classify(res)
        if (g != null && g == cur) count++ else { cur = g; count = 0 }
        val now = System.currentTimeMillis()
        if (g != null && count >= 5 && now - lastFire > 1800) {
            lastFire = now; count = 0
            runOnUiThread { fire(g) }
        } else if (g == null) {
            runOnUiThread { label.text = "Wer 2idek l-camera" }
        }
    }

    private fun fire(g: String) {
        val (txt, intent) = when (g) {
            "open" -> "🖐️ Jahez" to null
            "fist" -> "✊ Dar" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            "one" -> "☝️ YouTube" to pkg("com.google.android.youtube")
            "two" -> "✌️ WhatsApp" to pkg("com.whatsapp")
            "three" -> "🤟 Maps" to Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
            "four" -> "🖖 Settings" to Intent(Settings.ACTION_SETTINGS)
            "thumb" -> "👍 Telephone" to Intent(Intent.ACTION_DIAL)
            else -> return
        }
        label.text = txt
        (getSystemService(VIBRATOR_SERVICE) as Vibrator)
            .vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        try { intent?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (_: Exception) {}
    }

    private fun pkg(name: String): Intent? = packageManager.getLaunchIntentForPackage(name)

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
        landmarker.close()
    }
}

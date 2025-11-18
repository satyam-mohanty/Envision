package com.yourname.envision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private lateinit var viewFinder: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var vibrator: Vibrator
    private lateinit var progressBar: ProgressBar
    private var imageCapture: ImageCapture? = null

    private val analysisHandler = Handler(Looper.getMainLooper())
    private var isAnalyzing = false
    private val ANALYSIS_INTERVAL_MS = 5000L // analyze every 5 seconds

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) startCamera()
            else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.view_finder)
        progressBar = findViewById(R.id.progress_bar)
        tts = TextToSpeech(this, this)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) startCamera()
        else activityResultLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, imageCapture)
                analysisHandler.post(analysisRunnable)
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val analysisRunnable = object : Runnable {
        override fun run() {
            if (!isAnalyzing) takePhotoAndAnalyze()
            analysisHandler.postDelayed(this, ANALYSIS_INTERVAL_MS)
        }
    }

    private fun takePhotoAndAnalyze() {
        val capture = imageCapture ?: return
        isAnalyzing = true
        runOnUiThread { progressBar.visibility = View.VISIBLE }

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                @androidx.annotation.OptIn(ExperimentalGetImage::class)
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxy.toBitmap()
                    if (bitmap != null) analyzeSceneWithGemini(bitmap)
                    else {
                        Log.e(TAG, "Failed to convert image")
                        isAnalyzing = false
                        runOnUiThread { progressBar.visibility = View.GONE }
                    }
                    imageProxy.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    isAnalyzing = false
                    runOnUiThread { progressBar.visibility = View.GONE }
                }
            }
        )
    }

    private fun analyzeSceneWithGemini(bitmap: Bitmap) {
        lifecycleScope.launch {
            try {
                // ↓ Resize to make upload lighter ↓
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 640, 480, true)
                val baos = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                val prompt = "Describe this image briefly for a person with low vision."

                val parts = JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", base64)))
                }

                val contents = JSONArray().put(JSONObject().put("parts", parts))
                val payload = JSONObject().put("contents", contents).toString()

                Log.d(TAG, "Payload size: ${payload.length} characters")

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()

                val requestBody = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$YOUR_API_KEY")
                    .post(requestBody)
                    .build()

                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }

                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val root = JSONObject(body)
                    val text = root.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "")
                        ?.trim()

                    if (!text.isNullOrEmpty()) {
                        Log.d(TAG, "Gemini says: $text")
                        speakOut(text)
                        triggerHapticPulse()
                    } else speakOut("No description returned.")
                } else {
                    Log.e(TAG, "HTTP ${response.code}: $body")
                    speakOut("Analysis failed. HTTP ${response.code}.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Gemini REST call failed", e)
                speakOut("Analysis failed: ${e.localizedMessage}")
            } finally {
                isAnalyzing = false
                runOnUiThread { progressBar.visibility = View.GONE }
            }
        }
    }

    private fun triggerHapticPulse() {
        if (!vibrator.hasVibrator()) return
        val duration = 100L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator.vibrate(duration)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
                Log.e(TAG, "TTS language not supported.")
        } else Log.e(TAG, "TTS initialization failed.")
    }

    private fun speakOut(text: String) {
        runOnUiThread { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "") }
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
        val image = this.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) return null

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        cameraExecutor.shutdown()
        vibrator.cancel()
        analysisHandler.removeCallbacks(analysisRunnable)
    }

    companion object {
        private const val TAG = "Envision"
        private const val YOUR_API_KEY = "Dump your API Key Here"
    }
}

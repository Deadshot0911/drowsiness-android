package com.example.drowsinessapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvEar: TextView
    private lateinit var tvMar: TextView
    private lateinit var tvSessionStats: TextView
    private lateinit var calibrationOverlay: android.widget.LinearLayout
    private lateinit var calibProgress: ProgressBar
    private lateinit var faceLandmarker: FaceLandmarker
    
    // Calibration & Detection
    private var isCalibrating = true
    private var calibFrames = 0
    private val CALIBRATION_FRAMES = 60
    private var baselineEar = 0.30f
    private var earThreshold = 0.25f
    
    private var earCounter = 0
    private var yawnCounter = 0
    private val CONSEC_FRAMES = 15
    private val MAR_THRESHOLD = 0.60f
    private val YAWN_CONSEC_FRAMES = 15

    // Session Data
    private var sessionDrowsyCount = 0
    private var sessionYawnCount = 0
    private var sessionStartTime = 0L

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        tvStatus = findViewById(R.id.tvStatus)
        tvEar = findViewById(R.id.tvEar)
        tvMar = findViewById(R.id.tvMar)
        tvSessionStats = findViewById(R.id.tvSessionStats)
        calibrationOverlay = findViewById(R.id.calibrationOverlay)
        calibProgress = findViewById(R.id.calibProgress)
        
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        sessionStartTime = System.currentTimeMillis()

        // Stop Session Button
        findViewById<Button>(R.id.btnStopSession).setOnClickListener {
            endSession()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        } else {
            setupMediaPipe()
            startCamera()
        }
    }

    private fun endSession() {
        val durationMillis = System.currentTimeMillis() - sessionStartTime
        val minutes = (durationMillis / 1000) / 60
        val seconds = (durationMillis / 1000) % 60
        val durationStr = String.format("%02d:%02d", minutes, seconds)
        
        SessionManager.saveSession(this, sessionDrowsyCount, sessionYawnCount, durationStr)
        
        // Go back to dashboard
        val intent = Intent(this, DashboardActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupMediaPipe()
            startCamera()
        }
    }

    private fun setupMediaPipe() {
        val baseOptions = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ -> onResults(result) }
            .build()
        faceLandmarker = FaceLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val bitmap = imageProxy.toBitmap()
                val mpImage = BitmapImageBuilder(bitmap).build()
                faceLandmarker.detectAsync(mpImage, imageProxy.imageInfo.timestamp)
                imageProxy.close()
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onResults(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isEmpty()) return
        val landmarks = result.faceLandmarks()[0]
        
        val leftEyeIndices = listOf(362, 385, 387, 263, 373, 380)
        val rightEyeIndices = listOf(33, 160, 158, 133, 153, 144)
        val mouthTop = landmarks[13]; val mouthBottom = landmarks[14]
        val mouthLeft = landmarks[78]; val mouthRight = landmarks[308]
        
        fun dist(pA: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, pB: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
            return sqrt((pA.x() - pB.x()).let { it * it } + (pA.y() - pB.y()).let { it * it })
        }

        fun calcEAR(indices: List<Int>): Float {
            val p1 = landmarks[indices[0]]; val p2 = landmarks[indices[1]]; val p3 = landmarks[indices[2]]
            val p4 = landmarks[indices[3]]; val p5 = landmarks[indices[4]]; val p6 = landmarks[indices[5]]
            return (dist(p2, p6) + dist(p3, p5)) / (2.0f * dist(p1, p4))
        }

        val ear = (calcEAR(leftEyeIndices) + calcEAR(rightEyeIndices)) / 2.0f
        val mar = dist(mouthTop, mouthBottom) / dist(mouthLeft, mouthRight)

        runOnUiThread {
            tvEar.text = String.format("EAR: %.3f", ear)
            tvMar.text = String.format("MAR: %.3f", mar)

            // 1. CALIBRATION PHASE
            if (isCalibrating) {
                calibProgress.progress = calibFrames
                baselineEar = ((baselineEar * calibFrames) + ear) / (calibFrames + 1)
                calibFrames++
                
                if (calibFrames >= CALIBRATION_FRAMES) {
                    isCalibrating = false
                    earThreshold = maxOf(0.18f, baselineEar * 0.75f)
                    calibrationOverlay.visibility = android.view.View.GONE // Hide overlay
                }
                return@runOnUiThread
            }

            // 2. DETECTION PHASE
            var statusText = "ALERT"
            var color = android.graphics.Color.GREEN
            var playAlarm = false

            if (ear < earThreshold) {
                earCounter++
                if (earCounter >= CONSEC_FRAMES) {
                    statusText = "DROWSY!"
                    color = android.graphics.Color.RED
                    playAlarm = true
                    if (earCounter == CONSEC_FRAMES) sessionDrowsyCount++
                } else {
                    statusText = "EYES CLOSING"
                    color = android.graphics.Color.YELLOW
                }
            } else {
                earCounter = 0
            }

            if (mar > MAR_THRESHOLD) {
                yawnCounter++
                if (yawnCounter >= YAWN_CONSEC_FRAMES) {
                    statusText = "YAWNING"
                    color = android.graphics.Color.rgb(255, 165, 0)
                    if (yawnCounter == YAWN_CONSEC_FRAMES) sessionYawnCount++
                }
            } else {
                yawnCounter = 0
            }

            tvStatus.text = statusText
            tvStatus.setTextColor(color)
            tvSessionStats.text = "Alerts: $sessionDrowsyCount\nYawns: $sessionYawnCount"

            if (playAlarm) {
                if (earCounter % 15 == 0) toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
            } else {
                toneGenerator?.stopTone()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
    }
}

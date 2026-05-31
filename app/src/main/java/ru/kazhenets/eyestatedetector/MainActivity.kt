package ru.kazhenets.eyestatedetector

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import ru.kazhenets.eyestatedetector.analyzer.EyeStateAnalyzer
import ru.kazhenets.eyestatedetector.databinding.ActivityMainBinding
import ru.kazhenets.eyestatedetector.detector.DrowsinessDetector
import ru.kazhenets.eyestatedetector.model.AnalysisResult
import ru.kazhenets.eyestatedetector.model.EyeState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var drowsinessDetector: DrowsinessDetector

    private var ringtone: Ringtone? = null
    private var alertAnimator: ObjectAnimator? = null
    private var isDrowsyActive = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, getString(R.string.camera_permission_denied), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Не давать экрану гаснуть во время мониторинга
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraExecutor = Executors.newSingleThreadExecutor()

        drowsinessDetector = DrowsinessDetector(
            closedThresholdMs = 1_500L,
            onDrowsinessDetected = { runOnUiThread { showDrowsinessAlert() } },
            onDrowsinessCleared  = { runOnUiThread { hideDrowsinessAlert() } }
        )

        if (hasCameraPermission()) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor, EyeStateAnalyzer { result ->
                        drowsinessDetector.process(result.eyeState)
                        val closedMs = drowsinessDetector.closedDurationMs
                        runOnUiThread { updateUI(result, closedMs) }
                    })
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.camera_error), Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateUI(result: AnalysisResult, closedMs: Long) {
        if (!result.faceDetected) {
            binding.tvEyeState.text = getString(R.string.face_not_detected)
            binding.tvEarValue.text = "EAR: —"
            binding.tvProbLeft.text = ""
            binding.tvProbRight.text = ""
            binding.tvTimer.text = ""
            if (!isDrowsyActive) setStatusColor(R.color.status_unknown)
            return
        }

        binding.tvEyeState.text = when (result.eyeState) {
            EyeState.OPEN        -> getString(R.string.eyes_open)
            EyeState.HALF_CLOSED -> getString(R.string.eyes_half_closed)
            EyeState.CLOSED      -> getString(R.string.eyes_closed)
            EyeState.UNKNOWN     -> getString(R.string.face_not_detected)
        }

        binding.tvEarValue.text = if (result.ear >= 0f)
            "EAR: ${"%.3f".format(result.ear)}" else "EAR: —"

        if (result.leftEyeProb >= 0f) {
            binding.tvProbLeft.text  = getString(R.string.eye_left,  (result.leftEyeProb  * 100).toInt())
            binding.tvProbRight.text = getString(R.string.eye_right, (result.rightEyeProb * 100).toInt())
        }

        binding.tvTimer.text = if (closedMs > 0)
            getString(R.string.closed_timer, closedMs / 1000f) else ""

        if (!isDrowsyActive) {
            setStatusColor(when (result.eyeState) {
                EyeState.OPEN        -> R.color.status_open
                EyeState.HALF_CLOSED -> R.color.status_half
                EyeState.CLOSED      -> R.color.status_closed
                EyeState.UNKNOWN     -> R.color.status_unknown
            })
        }
    }

    private fun setStatusColor(colorRes: Int) {
        binding.statusBar.setBackgroundColor(ContextCompat.getColor(this, colorRes))
    }

    private fun showDrowsinessAlert() {
        isDrowsyActive = true
        binding.alertOverlay.visibility = View.VISIBLE
        binding.tvAlert.visibility = View.VISIBLE

        alertAnimator?.cancel()
        alertAnimator = ObjectAnimator.ofFloat(binding.alertOverlay, View.ALPHA, 0.25f, 0.75f).apply {
            duration = 400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        playAlertSound()
        vibrate()
        Toast.makeText(this, getString(R.string.drowsiness_toast), Toast.LENGTH_LONG).show()
    }

    private fun hideDrowsinessAlert() {
        isDrowsyActive = false
        alertAnimator?.cancel()
        binding.alertOverlay.visibility = View.GONE
        binding.tvAlert.visibility = View.GONE
        stopAlertSound()
    }

    private fun playAlertSound() {
        stopAlertSound()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        runCatching {
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        }
    }

    private fun stopAlertSound() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        val pattern = longArrayOf(0, 600, 200, 600, 200, 600)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopAlertSound()
        alertAnimator?.cancel()
    }
}

package ru.kazhenets.eyestatedetector.analyzer

import android.graphics.PointF
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import ru.kazhenets.eyestatedetector.model.AnalysisResult
import ru.kazhenets.eyestatedetector.model.EyeState
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * CameraX-анализатор, детектирует лицо через ML Kit и рассчитывает EAR.
 *
 * EAR (Eye Aspect Ratio) = вертикальный диаметр глаза / горизонтальный диаметр.
 * Вычисляется по 16 контурным точкам каждого глаза из FaceContour.LEFT_EYE / RIGHT_EYE.
 * Чем меньше EAR — тем сильнее закрыт глаз:
 *   > EAR_OPEN    → OPEN
 *   > EAR_HALF    → HALF_CLOSED
 *   иначе         → CLOSED
 *
 * Алгоритм устойчив к прозрачным очкам: ML Kit обучен на разнообразных данных,
 * а вычисление EAR опирается на крайние точки контура, не зависящие от оправы.
 */
class EyeStateAnalyzer(
    private val onResult: (AnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        // Пороги для геометрического EAR (высота / ширина глаза)
        private const val EAR_OPEN = 0.22f
        private const val EAR_HALF = 0.14f

        // Пороги для вероятности из классификатора ML Kit (резервный метод)
        private const val PROB_OPEN = 0.95f
        private const val PROB_HALF = 0.35f

        private const val SMOOTHING_WINDOW = 5
    }

    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
    )

    private val earBuffer = ArrayDeque<Float>(SMOOTHING_WINDOW)
    private val probBuffer = ArrayDeque<Float>(SMOOTHING_WINDOW)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                val result = if (faces.isEmpty()) {
                    earBuffer.clear()
                    probBuffer.clear()
                    noFaceResult()
                } else {
                    processFace(faces[0])
                }
                onResult(result)
            }
            .addOnFailureListener { onResult(noFaceResult()) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun processFace(face: Face): AnalysisResult {
        val leftProb = face.leftEyeOpenProbability ?: 1f
        val rightProb = face.rightEyeOpenProbability ?: 1f

        val leftContour = face.getContour(FaceContour.LEFT_EYE)
        val rightContour = face.getContour(FaceContour.RIGHT_EYE)

        val hasContours = leftContour != null && rightContour != null
        val rawEar = if (hasContours) {
            val l = calculateEAR(leftContour!!.points)
            val r = calculateEAR(rightContour!!.points)
            (l + r) / 2f
        } else -1f

        val smoothEar = if (rawEar >= 0f) addSmoothed(earBuffer, rawEar) else -1f
        val smoothProb = addSmoothed(probBuffer, (leftProb + rightProb) / 2f)

        // ML Kit's eyeOpenProbability is the reliable primary metric.
        // Geometric EAR from CONTOUR_MODE reflects orbital anatomy and stays nearly
        // constant when eyes close — it is calculated for display only.
        val eyeState = when {
            smoothProb > PROB_OPEN -> EyeState.OPEN
            smoothProb > PROB_HALF -> EyeState.HALF_CLOSED
            else -> EyeState.CLOSED
        }

        return AnalysisResult(
            eyeState = eyeState,
            leftEyeProb = leftProb,
            rightEyeProb = rightProb,
            ear = if (smoothEar >= 0f) smoothEar else smoothProb,
            faceDetected = true
        )
    }

    /**
     * Вычисляет EAR как отношение вертикального размера глазного проёма
     * к горизонтальному размеру по контурным точкам.
     *
     * Метод не зависит от порядка точек в контуре (clockwise/counter-clockwise):
     * находим экстремальные точки геометрически, что даёт тот же результат,
     * что и классическая формула EAR Соукала по 6 точкам.
     */
    private fun calculateEAR(points: List<PointF>): Float {
        if (points.size < 6) return 0.3f

        val left = points.minByOrNull { it.x } ?: return 0.3f
        val right = points.maxByOrNull { it.x } ?: return 0.3f
        val horizontal = dist(left, right)
        if (horizontal < 1f) return 0f

        // Используем среднюю треть по X для вертикального замера —
        // так получаем максимальное раскрытие, а не угол глаза
        val midX = (left.x + right.x) / 2f
        val window = horizontal / 3f
        val central = points.filter { abs(it.x - midX) < window }

        val (top, bot) = if (central.size >= 2) {
            central.minByOrNull { it.y }!! to central.maxByOrNull { it.y }!!
        } else {
            points.minByOrNull { it.y }!! to points.maxByOrNull { it.y }!!
        }

        return dist(top, bot) / horizontal
    }

    private fun addSmoothed(buffer: ArrayDeque<Float>, value: Float): Float {
        buffer.addLast(value)
        if (buffer.size > SMOOTHING_WINDOW) buffer.removeFirst()
        return buffer.average().toFloat()
    }

    private fun dist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun noFaceResult() = AnalysisResult(
        eyeState = EyeState.UNKNOWN,
        leftEyeProb = -1f,
        rightEyeProb = -1f,
        ear = -1f,
        faceDetected = false
    )
}

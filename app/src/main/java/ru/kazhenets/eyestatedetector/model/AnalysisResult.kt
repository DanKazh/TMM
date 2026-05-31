package ru.kazhenets.eyestatedetector.model

data class AnalysisResult(
    val eyeState: EyeState,
    val leftEyeProb: Float,
    val rightEyeProb: Float,
    val ear: Float,
    val faceDetected: Boolean
)

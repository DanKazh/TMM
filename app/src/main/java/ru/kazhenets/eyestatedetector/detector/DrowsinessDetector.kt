package ru.kazhenets.eyestatedetector.detector

import ru.kazhenets.eyestatedetector.model.EyeState

class DrowsinessDetector(
    private val closedThresholdMs: Long = 1_500L,
    private val onDrowsinessDetected: () -> Unit,
    private val onDrowsinessCleared: () -> Unit
) {
    private var closedStartTime: Long = 0L
    private var isDrowsy = false

    val closedDurationMs: Long
        get() = if (closedStartTime > 0L) System.currentTimeMillis() - closedStartTime else 0L

    fun process(eyeState: EyeState) {
        when (eyeState) {
            EyeState.CLOSED -> {
                if (closedStartTime == 0L) closedStartTime = System.currentTimeMillis()
                if (!isDrowsy && closedDurationMs >= closedThresholdMs) {
                    isDrowsy = true
                    onDrowsinessDetected()
                }
            }
            EyeState.OPEN, EyeState.HALF_CLOSED -> {
                closedStartTime = 0L
                if (isDrowsy) {
                    isDrowsy = false
                    onDrowsinessCleared()
                }
            }
            EyeState.UNKNOWN -> {
                closedStartTime = 0L
            }
        }
    }

    fun reset() {
        closedStartTime = 0L
        isDrowsy = false
    }
}

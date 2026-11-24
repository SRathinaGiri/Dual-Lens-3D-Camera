package com.example.duallens3dcamera.camera

import android.util.Size

/**
 * What the main screen needs from a stereo camera. Two implementations:
 *  - [StereoCameraController]: one paired (logical multi-) camera streaming both lenses. Used on
 *    every phone where that works.
 *  - [SeparateCameraController]: the main and ultrawide opened as two independent cameras, for
 *    phones without a working paired camera.
 * The data types (Callback, StartParams, modes, ...) live in [StereoCameraController].
 */
interface StereoCamera {
    fun start(params: StereoCameraController.StartParams): StereoCameraController.PreviewConfig
    fun stop()

    fun setVideoConfig(requestedRecordSize: Size, requestedRecordFps: Int, videoBitrateBps: Int, eisEnabled: Boolean)
    fun setVideoProcessingModes(noiseReductionMode: Int, distortionCorrectionMode: Int, edgeMode: Int)
    fun setDebugConfig(enableStereoRecordingLog: Boolean, stereoLogFramesOnly: Boolean,
                       enablePhotoJsonLog: Boolean, enablePhotoSyncToast: Boolean)
    fun setPhotoSaveIndividualLensImages(enabled: Boolean)
    fun setPreview3dMode(mode: StereoCameraController.Preview3dMode)
    fun setStereoOutput(correction: Boolean, crossEyed: Boolean, swapEyes: Boolean, colourAverage: Boolean = true)
    fun setPrimeUwOnActive(enabled: Boolean)

    fun setMainFocusMode(mode: StereoCameraController.MainFocusMode)
    fun setProExposure(iso: Int?, exposureNs: Long?)
    fun proSupported(): Boolean
    fun commonIsoRange(): IntRange?
    fun commonExposureRangeNs(): LongRange?
    fun lockFocusForSequence()
    fun unlockFocusForSequence()

    fun setTorch(enabled: Boolean)
    fun setPhotoOutputRaw(raw: Boolean)
    fun setZoom2x(enabled: Boolean)

    fun captureStereoPhoto(timestampForFilename: String, raw: Boolean)
    fun startRecording(timestampForFilename: String)
    fun stopRecording()
}

package com.example.duallens3dcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ExifInterface as PlatformExif
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.example.duallens3dcamera.encoding.AudioEncoder
import com.example.duallens3dcamera.encoding.Mp4Muxer
import com.example.duallens3dcamera.encoding.VideoEncoder
import com.example.duallens3dcamera.image.toNv21
import com.example.duallens3dcamera.media.ImageWriters
import com.example.duallens3dcamera.media.MediaStoreUtils
import com.example.duallens3dcamera.settings.AppSettings
import com.example.duallens3dcamera.util.SizeSelector
import com.example.duallens3dcamera.util.StereoSbs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Stereo capture for phones without a usable paired (logical multi-) camera: the main and the
 * ultrawide are opened as two independent cameras, each with its own session.
 *
 * Deliberately separate from [StereoCameraController] so phones whose paired camera works keep
 * running exactly the same code. Supported here: preview (main lens), 3D photos (one still from
 * each camera, requested at the same moment; same alignment and SBS code as paired phones),
 * two-file video for "Make 3D video", torch, timer and time-lapse (driven by the main screen).
 * Not supported yet: RAW, 2x, Pro, live 3D preview, focus lock, debug logs.
 *
 * The two cameras are not synchronised in hardware, so frames can be a few ms to a frame apart.
 */
class SeparateCameraController(
    private val context: Context,
    private val callback: StereoCameraController.Callback,
    private val pair: LensDiscovery.SeparatePair
) : StereoCamera {

    companion object {
        private const val TAG = "SeparateCamera"
        private const val YUV_MAX_IMAGES = 3
        private const val IFRAME_INTERVAL_SEC = 1
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 1
        private const val AUDIO_BITRATE_BPS = 128_000
        private const val STILL_TIMEOUT_MS = 4000L
        private val KEEPALIVE_SIZE = Size(640, 480)
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val sbsExecutor = Executors.newSingleThreadExecutor()
    private val encodingExecutor = Executors.newSingleThreadExecutor()

    private val mainChars: CameraCharacteristics = cameraManager.getCameraCharacteristics(pair.mainId)
    private val ultraChars: CameraCharacteristics = cameraManager.getCameraCharacteristics(pair.ultraId)
    private val mainMap: StreamConfigurationMap? = mainChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    private val ultraMap: StreamConfigurationMap? = ultraChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    private val sensorOrientation: Int = mainChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    private var mainDevice: CameraDevice? = null
    private var ultraDevice: CameraDevice? = null
    private var mainSession: CameraCaptureSession? = null
    private var ultraSession: CameraCaptureSession? = null
    private var mainRepeating: CaptureRequest.Builder? = null
    private var ultraRepeating: CaptureRequest.Builder? = null
    /** Bumped whenever sessions are rebuilt, so late callbacks of old sessions are ignored. */
    private var sessionGeneration = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var mainStillReader: ImageReader? = null
    private var ultraStillReader: ImageReader? = null
    // The ultrawide needs a repeating target of its own to keep its auto-exposure running.
    private var ultraKeepAlive: ImageReader? = null

    private var displayRotation = pair.assumedDisplayRotation
    private var previewSize = Size(800, 600)
    private var recordSize = Size(1440, 1080)
    private var stillSize = Size(1600, 1200)
    private var previewFps = 30

    @Volatile private var torchOn = false
    @Volatile private var isRecording = false
    private var requestedRecordSize = Size(1440, 1080)
    private var requestedRecordFps = 30
    private var videoBitrateBps = 50_000_000
    private var requestedPreviewSize = Size(800, 600)
    private var photoNoiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_OFF
    private var photoDistortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
    private var photoEdgeMode = CaptureRequest.EDGE_MODE_OFF
    private var photoStillResolutionMode = StereoCameraController.StillResolutionMode.LARGEST_COMMON
    private var videoNoiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_OFF
    private var videoDistortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
    private var videoEdgeMode = CaptureRequest.EDGE_MODE_OFF
    @Volatile private var enablePhotoSyncToast = false
    @Volatile private var saveIndividualLensImages = false
    @Volatile private var stereoCorrectionEnabled = true
    @Volatile private var stereoCrossEyed = false
    @Volatile private var stereoSwapEyes = false
    @Volatile private var stereoColourAverage = true
    // Verified layouts so far (Redmi hidden paired camera, Inzamam's Xiaomi build): held in
    // landscape, the main lens is the left eye on non-Pixels. "Swap eyes" corrects other phones.
    private val wideIsLeftByDefault = !pair.phoneIsAPixel

    // Recording
    private var wideEncoder: VideoEncoder? = null
    private var ultraEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var wideMuxer: Mp4Muxer? = null
    private var ultraMuxer: Mp4Muxer? = null
    private var wideVideoUri: Uri? = null
    private var ultraVideoUri: Uri? = null

    // Photo in progress (camera thread only)
    private class Photo(
        val id: String,
        val exifDt: String,
        val rotationDegrees: Int,
        val wideUri: Uri?,
        val ultraUri: Uri?,
        val sbsUri: Uri,
        var wide: Image? = null,
        var ultra: Image? = null,
        var done: Boolean = false
    )
    private var photo: Photo? = null

    // ------------------------------------------------------------------ lifecycle

    override fun start(params: StereoCameraController.StartParams): StereoCameraController.PreviewConfig {
        surfaceTexture = params.surfaceTexture
        torchOn = params.torchOn
        requestedRecordSize = params.video.requestedRecordSize
        requestedRecordFps = params.video.requestedRecordFps
        videoBitrateBps = params.video.videoBitrateBps
        requestedPreviewSize = params.preview.requestedPreviewSize
        previewFps = params.preview.previewTargetFps
        photoNoiseReductionMode = params.photo.noiseReductionMode
        photoDistortionCorrectionMode = params.photo.distortionCorrectionMode
        photoEdgeMode = params.photo.edgeMode
        photoStillResolutionMode = params.photo.stillResolutionMode
        if (params.rawMode) callback.onStatus("RAW isn't available in separate-camera mode; using JPG.")
        if (params.zoom2xEnabled) callback.onStatus("2x isn't available in separate-camera mode.")

        thread = HandlerThread("SeparateCamera").also { it.start() }
        handler = Handler(requireNotNull(thread).looper)

        computeSizes()
        params.surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(params.surfaceTexture)
        Log.i(TAG, "Start: main=${pair.mainId} ultra=${pair.ultraId} preview=$previewSize record=$recordSize still=$stillSize")

        handler?.post { openBoth() }
        return StereoCameraController.PreviewConfig(previewSize, sensorOrientation, recordSize)
    }

    override fun stop() {
        val h = handler ?: return
        val latch = CountDownLatch(1)
        h.post {
            try { safeStopRecording(deleteFiles = false) } catch (_: Exception) {}
            failPhoto(null)
            closeSessions()
            try { ultraDevice?.close() } catch (_: Exception) {}
            try { mainDevice?.close() } catch (_: Exception) {}
            ultraDevice = null; mainDevice = null
            closeReaders()
            try { previewSurface?.release() } catch (_: Exception) {}
            previewSurface = null; surfaceTexture = null
            latch.countDown()
        }
        latch.await()
        thread?.quitSafely()
        try { thread?.join() } catch (_: Exception) {}
        thread = null; handler = null
    }

    @SuppressLint("MissingPermission") // MainActivity only starts the camera with permission.
    private fun openBoth() {
        val h = handler ?: return
        try {
            cameraManager.openCamera(pair.mainId, deviceCallback(isMain = true), h)
        } catch (e: Exception) {
            callback.onError("Could not open the main camera: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceCallback(isMain: Boolean): CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (isMain) {
                mainDevice = camera
                // Open the ultrawide only once the main is open: that is the order the
                // Compatibility test checks.
                try {
                    cameraManager.openCamera(pair.ultraId, deviceCallback(isMain = false), handler)
                } catch (e: Exception) {
                    callback.onError("Could not open the ultrawide: ${e.message}")
                }
            } else {
                ultraDevice = camera
                createPreviewSessions()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            try { camera.close() } catch (_: Exception) {}
            if (isMain) mainDevice = null else ultraDevice = null
            callback.onError(if (isMain) "Main camera disconnected." else
                "The phone closed the ultrawide (it may allow only one rear camera at a time).")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            try { camera.close() } catch (_: Exception) {}
            if (isMain) mainDevice = null else ultraDevice = null
            val what = when (error) {
                ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
                ERROR_CAMERA_IN_USE -> "camera in use"
                ERROR_CAMERA_DISABLED -> "camera disabled"
                else -> "error $error"
            }
            callback.onError("${if (isMain) "Main camera" else "Ultrawide"}: $what.")
        }
    }

    // ------------------------------------------------------------------ sizes

    private fun computeSizes() {
        val wMap = mainMap ?: return
        val uMap = ultraMap ?: return

        val previews = (wMap.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()).toList()
        previewSize = previews.firstOrNull { it == requestedPreviewSize }
            ?: previews.filter { SizeSelector.isFourByThree(it) }.ifEmpty { previews }
                .minByOrNull { abs(it.width.toLong() * it.height - requestedPreviewSize.width.toLong() * requestedPreviewSize.height) }
            ?: requestedPreviewSize

        val (rec, msg) = SizeSelector.chooseCommonPrivateSizeAtFps(
            wideMap = wMap, ultraMap = uMap, preferred = requestedRecordSize,
            maxW = requestedRecordSize.width, maxH = requestedRecordSize.height, targetFps = requestedRecordFps
        )
        recordSize = rec
        msg?.let { callback.onFallbackSizeUsed(it) }

        val cap = AppSettings.prefs(context).getInt(AppSettings.KEY_STILL_MAX_EDGE, 0).let { if (it <= 0) Int.MAX_VALUE else it }
        stillSize = SizeSelector.chooseLargestCommonYuvFourByThree(wMap, uMap, cap)
            ?: SizeSelector.chooseLargestYuvFourByThree(wMap, cap)
    }

    private fun ultraStillSize(): Size {
        val uMap = ultraMap ?: return stillSize
        val sizes = uMap.getOutputSizes(ImageFormat.YUV_420_888) ?: return stillSize
        return if (sizes.contains(stillSize)) stillSize
        else SizeSelector.chooseLargestYuvFourByThree(uMap, maxOf(stillSize.width, stillSize.height))
    }

    private fun keepAliveSize(): Size {
        val sizes = ultraMap?.getOutputSizes(ImageFormat.YUV_420_888) ?: return KEEPALIVE_SIZE
        return sizes.filter { it.width <= 800 }.maxByOrNull { it.width * it.height } ?: sizes.minBy { it.width * it.height }
    }

    // ------------------------------------------------------------------ sessions

    private fun closeSessions() {
        sessionGeneration++
        try { mainSession?.close() } catch (_: Exception) {}
        try { ultraSession?.close() } catch (_: Exception) {}
        mainSession = null; ultraSession = null
        mainRepeating = null; ultraRepeating = null
    }

    private fun closeReaders() {
        listOf(mainStillReader, ultraStillReader, ultraKeepAlive).forEach { try { it?.close() } catch (_: Exception) {} }
        mainStillReader = null; ultraStillReader = null; ultraKeepAlive = null
    }

    /** Creates a session on [camera]; calls [onReady] on success, or reports [what] failing. */
    private fun configure(camera: CameraDevice, outputs: List<Surface>, what: String, onReady: (CameraCaptureSession) -> Unit) {
        val gen = sessionGeneration
        try {
            camera.createCaptureSessionByOutputConfigurations(outputs.map { OutputConfiguration(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != sessionGeneration) { s.close(); return }
                        onReady(s)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (gen == sessionGeneration) callback.onError("The $what session was refused.")
                    }
                }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "$what session failed: ${e.message}", e)
            callback.onError("The $what session failed: ${e.message}")
        }
    }

    private fun createPreviewSessions() {
        val main = mainDevice ?: return
        val ultra = ultraDevice ?: return
        val preview = previewSurface ?: return
        val h = handler ?: return
        closeSessions()
        closeReaders()
        surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

        val uStill = ultraStillSize()
        mainStillReader = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.YUV_420_888, YUV_MAX_IMAGES).apply {
            setOnImageAvailableListener({ onStill(isWide = true, reader = it) }, h)
        }
        ultraStillReader = ImageReader.newInstance(uStill.width, uStill.height, ImageFormat.YUV_420_888, YUV_MAX_IMAGES).apply {
            setOnImageAvailableListener({ onStill(isWide = false, reader = it) }, h)
        }
        val ka = keepAliveSize()
        ultraKeepAlive = ImageReader.newInstance(ka.width, ka.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ r -> try { r.acquireLatestImage()?.close() } catch (_: Exception) {} }, h)
        }
        val mainReader = requireNotNull(mainStillReader)
        val ultraReader = requireNotNull(ultraStillReader)
        val keep = requireNotNull(ultraKeepAlive)

        configure(main, listOf(preview, mainReader.surface), "main camera") { s ->
            mainSession = s
            try {
                val b = main.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    commonSettings(this, mainChars, previewFps, video = false)
                    applyTorch(this)
                }
                mainRepeating = b
                s.setRepeatingRequest(b.build(), null, h)
            } catch (e: Exception) {
                callback.onError("Main preview failed: ${e.message}")
            }
        }
        configure(ultra, listOf(keep.surface, ultraReader.surface), "ultrawide") { s ->
            ultraSession = s
            try {
                val b = ultra.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(keep.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    commonSettings(this, ultraChars, previewFps, video = false)
                }
                ultraRepeating = b
                s.setRepeatingRequest(b.build(), null, h)
            } catch (e: Exception) {
                callback.onError("Ultrawide preview failed: ${e.message}")
            }
        }
    }

    private fun commonSettings(b: CaptureRequest.Builder, chars: CameraCharacteristics, fps: Int, video: Boolean) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (ois?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        if (video) {
            chooseFpsRange(chars, fps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            try { b.set(CaptureRequest.NOISE_REDUCTION_MODE, videoNoiseReductionMode) } catch (_: Exception) {}
            try { b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, videoDistortionCorrectionMode) } catch (_: Exception) {}
            try { b.set(CaptureRequest.EDGE_MODE, videoEdgeMode) } catch (_: Exception) {}
        } else {
            try { b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF) } catch (_: Exception) {}
            try { b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) } catch (_: Exception) {}
            try { b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF) } catch (_: Exception) {}
        }
    }

    private fun chooseFpsRange(chars: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: return null
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        return ranges.filter { it.lower <= fps && it.upper >= fps }.minByOrNull { it.upper - it.lower }
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun applyTorch(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    // ------------------------------------------------------------------ settings

    override fun setVideoConfig(requestedRecordSize: Size, requestedRecordFps: Int, videoBitrateBps: Int, eisEnabled: Boolean) {
        handler?.post {
            this.requestedRecordSize = requestedRecordSize
            this.requestedRecordFps = requestedRecordFps
            this.videoBitrateBps = videoBitrateBps
            if (!isRecording) computeSizes()
        } ?: run {
            this.requestedRecordSize = requestedRecordSize
            this.requestedRecordFps = requestedRecordFps
            this.videoBitrateBps = videoBitrateBps
        }
    }

    override fun setVideoProcessingModes(noiseReductionMode: Int, distortionCorrectionMode: Int, edgeMode: Int) {
        videoNoiseReductionMode = noiseReductionMode
        videoDistortionCorrectionMode = distortionCorrectionMode
        videoEdgeMode = edgeMode
    }

    override fun setDebugConfig(enableStereoRecordingLog: Boolean, stereoLogFramesOnly: Boolean,
                                enablePhotoJsonLog: Boolean, enablePhotoSyncToast: Boolean) {
        this.enablePhotoSyncToast = enablePhotoSyncToast
    }

    override fun setPhotoSaveIndividualLensImages(enabled: Boolean) { saveIndividualLensImages = enabled }

    override fun setPreview3dMode(mode: StereoCameraController.Preview3dMode) {
        if (mode != StereoCameraController.Preview3dMode.OFF) callback.onPreview3dUnavailable()
    }

    override fun setStereoOutput(correction: Boolean, crossEyed: Boolean, swapEyes: Boolean, colourAverage: Boolean) {
        stereoCorrectionEnabled = correction
        stereoCrossEyed = crossEyed
        stereoSwapEyes = swapEyes
        stereoColourAverage = colourAverage
    }

    override fun setPrimeUwOnActive(enabled: Boolean) {}
    override fun setMainFocusMode(mode: StereoCameraController.MainFocusMode) {}
    override fun setProExposure(iso: Int?, exposureNs: Long?) {}
    override fun proSupported(): Boolean = false
    override fun commonIsoRange(): IntRange? = null
    override fun commonExposureRangeNs(): LongRange? = null
    override fun lockFocusForSequence() {}
    override fun unlockFocusForSequence() {}

    override fun setTorch(enabled: Boolean) {
        torchOn = enabled
        handler?.post {
            val b = mainRepeating ?: return@post
            applyTorch(b)
            try { mainSession?.setRepeatingRequest(b.build(), null, handler) } catch (_: Exception) {}
        }
    }

    override fun setPhotoOutputRaw(raw: Boolean) {
        if (raw) callback.onStatus("RAW isn't available in separate-camera mode; using JPG.")
    }

    override fun setZoom2x(enabled: Boolean) {
        if (enabled) callback.onStatus("2x isn't available in separate-camera mode.")
    }

    // ------------------------------------------------------------------ photos

    override fun captureStereoPhoto(timestampForFilename: String, raw: Boolean) {
        val h = handler ?: run { callback.onError("Camera not running."); callback.onPhotoCaptureDone(); return }
        h.post {
            val main = mainDevice; val ultra = ultraDevice
            val ms = mainSession; val us = ultraSession
            val mr = mainStillReader; val ur = ultraStillReader
            if (isRecording || main == null || ultra == null || ms == null || us == null || mr == null || ur == null || photo != null) {
                callback.onError(if (isRecording) "Cannot take photos while recording." else "Camera not ready.")
                callback.onPhotoCaptureDone()
                return@post
            }
            if (raw) callback.onStatus("RAW isn't available in separate-camera mode; using JPG.")
            val nowMs = System.currentTimeMillis()
            try {
                val wideUri = if (saveIndividualLensImages) MediaStoreUtils.createPendingImage(context, "${timestampForFilename}_wide.jpg", "image/jpeg", nowMs) else null
                val ultraUri = if (saveIndividualLensImages) MediaStoreUtils.createPendingImage(context, "${timestampForFilename}_ultrawide.jpg", "image/jpeg", nowMs) else null
                val sbsUri = MediaStoreUtils.createPendingImage(context, "${timestampForFilename}_sbs.jpg", "image/jpeg", nowMs)
                    ?: throw IllegalStateException("Could not create the photo file")
                val p = Photo(timestampForFilename, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(nowMs)),
                    orientationHint(), wideUri, ultraUri, sbsUri)
                photo = p

                fun still(cam: CameraDevice, target: Surface, chars: CameraCharacteristics, torch: Boolean) =
                    cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(target)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                        try { set(CaptureRequest.NOISE_REDUCTION_MODE, photoNoiseReductionMode) } catch (_: Exception) {}
                        try { set(CaptureRequest.DISTORTION_CORRECTION_MODE, photoDistortionCorrectionMode) } catch (_: Exception) {}
                        try { set(CaptureRequest.EDGE_MODE, photoEdgeMode) } catch (_: Exception) {}
                        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        if (ois?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                        }
                        if (torch) applyTorch(this)
                    }.build()

                val failCb = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        val reason = if (failure.reason == CaptureFailure.REASON_ERROR) "error" else "flushed"
                        Log.e(TAG, "Still failed ($reason) on ${if (session === mainSession) "main" else "ultrawide"}")
                        failPhoto("Still capture failed ($reason).")
                    }
                }
                // Back to back, so both cameras expose as close together as their pipelines allow.
                ms.capture(still(main, mr.surface, mainChars, torch = true), failCb, h)
                us.capture(still(ultra, ur.surface, ultraChars, torch = false), failCb, h)

                h.postDelayed({
                    val now = photo
                    if (now === p && !p.done) {
                        Log.e(TAG, "Still timeout: wide=${p.wide != null} ultra=${p.ultra != null}")
                        failPhoto("Still capture timed out.")
                    }
                }, STILL_TIMEOUT_MS)
            } catch (e: Exception) {
                if (photo != null) failPhoto("Photo capture failed: ${e.message}")
                else { callback.onError("Photo capture failed: ${e.message}"); callback.onPhotoCaptureDone() }
            }
        }
    }

    private fun onStill(isWide: Boolean, reader: ImageReader) {
        val img = try { reader.acquireNextImage() } catch (_: Exception) { null } ?: return
        val p = photo
        if (p == null || p.done) { img.close(); return }
        if (isWide) { p.wide?.close(); p.wide = img } else { p.ultra?.close(); p.ultra = img }
        val wide = p.wide ?: return
        val ultra = p.ultra ?: return
        p.done = true
        photo = null

        val gapMs = abs(wide.timestamp - ultra.timestamp) / 1e6
        Log.i(TAG, "Still pair: gap %.1f ms".format(gapMs))
        if (enablePhotoSyncToast) callback.onStatus("Lens time gap: %.1f ms".format(gapMs))

        val wideFrame = StereoSbs.Nv21Frame(nv21 = try { wide.toNv21() } finally { wide.close() },
            width = wide.width, height = wide.height, rotationDegrees = p.rotationDegrees)
        val ultraFrame = StereoSbs.Nv21Frame(nv21 = try { ultra.toNv21() } finally { ultra.close() },
            width = ultra.width, height = ultra.height, rotationDegrees = p.rotationDegrees)
        callback.onPhotoCaptureDone()

        val resolver = context.contentResolver
        if (p.wideUri != null && p.ultraUri != null) {
            val wUri = p.wideUri; val uUri = p.ultraUri
            ioExecutor.execute {
                try {
                    ImageWriters.writeJpegWithExif(context, wUri, requireNotNull(StereoSbs.yuvToJpegBytes(wideFrame, jpegQuality = 100)),
                        p.exifDt, PlatformExif.ORIENTATION_NORMAL)
                    ImageWriters.writeJpegWithExif(context, uUri, requireNotNull(StereoSbs.yuvToJpegBytes(ultraFrame, jpegQuality = 100)),
                        p.exifDt, PlatformExif.ORIENTATION_NORMAL)
                    MediaStoreUtils.finalizePending(resolver, wUri)
                    MediaStoreUtils.finalizePending(resolver, uUri)
                } catch (e: Exception) {
                    Log.e(TAG, "Saving lens JPEGs failed: ${e.message}", e)
                    MediaStoreUtils.delete(resolver, wUri); MediaStoreUtils.delete(resolver, uUri)
                }
            }
        }
        sbsExecutor.execute {
            try {
                val corrected = if (stereoCorrectionEnabled) StereoSbs.correctAndCreateSbsJpegBytes(
                    wide = wideFrame, ultra = ultraFrame,
                    wideIsLeft = wideIsLeftByDefault != stereoSwapEyes,
                    crossEyed = stereoCrossEyed, colourAverage = stereoColourAverage,
                    expectedScale = pair.expectedScale
                ) else null
                val bytes = corrected ?: StereoSbs.alignAndCreateSbsJpegBytes(
                    wideRight = wideFrame, ultraLeft = ultraFrame, zoom2xEnabled = false,
                    fallbackUltraOverlapFraction = (1.0 / pair.expectedScale).toFloat()
                )
                if (bytes == null) { MediaStoreUtils.delete(resolver, p.sbsUri); return@execute }
                ImageWriters.writeJpegWithExif(context, p.sbsUri, bytes, p.exifDt, PlatformExif.ORIENTATION_NORMAL)
                MediaStoreUtils.finalizePending(resolver, p.sbsUri)
            } catch (e: Exception) {
                Log.e(TAG, "SBS failed: ${e.message}", e)
                MediaStoreUtils.delete(resolver, p.sbsUri)
            }
        }
    }

    /** Drops the photo in progress (if any); reports [message] when not null. */
    private fun failPhoto(message: String?) {
        val p = photo ?: return
        photo = null
        p.done = true
        try { p.wide?.close() } catch (_: Exception) {}
        try { p.ultra?.close() } catch (_: Exception) {}
        val resolver = context.contentResolver
        p.wideUri?.let { MediaStoreUtils.delete(resolver, it) }
        p.ultraUri?.let { MediaStoreUtils.delete(resolver, it) }
        MediaStoreUtils.delete(resolver, p.sbsUri)
        if (message != null) callback.onError(message)
        callback.onPhotoCaptureDone()
    }

    // ------------------------------------------------------------------ video

    override fun startRecording(timestampForFilename: String) {
        val h = handler ?: run { callback.onError("Camera not running."); return }
        h.post {
            val main = mainDevice; val ultra = ultraDevice; val preview = previewSurface
            if (isRecording || main == null || ultra == null || preview == null) {
                callback.onError(if (isRecording) "Already recording." else "Camera not ready.")
                return@post
            }
            failPhoto(null)
            isRecording = true
            closeSessions()
            closeReaders()
            val nowMs = System.currentTimeMillis()
            try {
                wideVideoUri = MediaStoreUtils.createPendingVideo(context, "${timestampForFilename}_wide.mp4", nowMs)
                ultraVideoUri = MediaStoreUtils.createPendingVideo(context, "${timestampForFilename}_ultrawide.mp4", nowMs)
                val wPfd = context.contentResolver.openFileDescriptor(requireNotNull(wideVideoUri), "rw")
                    ?: throw IllegalStateException("Failed to open wide video file")
                val uPfd = context.contentResolver.openFileDescriptor(requireNotNull(ultraVideoUri), "rw")
                    ?: throw IllegalStateException("Failed to open ultrawide video file")
                val orient = orientationHint()
                wideMuxer = Mp4Muxer("WideMuxer", wPfd, orient)
                ultraMuxer = Mp4Muxer("UltraMuxer", uPfd, orient)
                audioEncoder = AudioEncoder(
                    tag = "AudioEncoder", muxers = listOf(requireNotNull(wideMuxer), requireNotNull(ultraMuxer)),
                    sampleRate = AUDIO_SAMPLE_RATE, channelCount = AUDIO_CHANNELS, bitrateBps = AUDIO_BITRATE_BPS
                ).also { it.start() }
                wideEncoder = VideoEncoder("WideVideoEncoder", recordSize.width, recordSize.height, requestedRecordFps,
                    videoBitrateBps, IFRAME_INTERVAL_SEC, requireNotNull(wideMuxer)).also { it.start() }
                ultraEncoder = VideoEncoder("UltraVideoEncoder", recordSize.width, recordSize.height, requestedRecordFps,
                    videoBitrateBps, IFRAME_INTERVAL_SEC, requireNotNull(ultraMuxer)).also { it.start() }
            } catch (e: Exception) {
                callback.onError("Start recording failed: ${e.message}")
                safeStopRecording(deleteFiles = true)
                return@post
            }
            val wEnc = requireNotNull(wideEncoder); val uEnc = requireNotNull(ultraEncoder)

            // Configure both sessions first, then start both streams back to back so the two
            // files begin as close together as possible.
            var mainReady: CameraCaptureSession? = null
            var ultraReady: CameraCaptureSession? = null
            fun startIfBoth() {
                val ms = mainReady ?: return
                val us = ultraReady ?: return
                try {
                    val mb = main.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(preview); addTarget(wEnc.inputSurface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        commonSettings(this, mainChars, requestedRecordFps, video = true)
                        applyTorch(this)
                    }
                    val ub = ultra.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(uEnc.inputSurface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        commonSettings(this, ultraChars, requestedRecordFps, video = true)
                    }
                    mainRepeating = mb; ultraRepeating = ub
                    ms.setRepeatingRequest(mb.build(), null, h)
                    us.setRepeatingRequest(ub.build(), null, h)
                    try { audioEncoder?.startCapturing() } catch (e: Exception) { callback.onError("Audio start failed: ${e.message}") }
                    callback.onRecordingStarted()
                } catch (e: Exception) {
                    callback.onError("Recording failed to start: ${e.message}")
                    safeStopRecording(deleteFiles = true)
                    createPreviewSessions()
                }
            }
            configure(main, listOf(preview, wEnc.inputSurface), "main recording") { s -> mainSession = s; mainReady = s; startIfBoth() }
            configure(ultra, listOf(uEnc.inputSurface), "ultrawide recording") { s -> ultraSession = s; ultraReady = s; startIfBoth() }
        }
    }

    override fun stopRecording() {
        val h = handler ?: run { callback.onError("Camera not running."); return }
        h.post {
            if (!isRecording) { callback.onRecordingStopped(); return@post }
            listOf(mainSession, ultraSession).forEach {
                try { it?.stopRepeating() } catch (_: Exception) {}
                try { it?.abortCaptures() } catch (_: Exception) {}
            }
            closeSessions()
            val wEnc = wideEncoder; val uEnc = ultraEncoder; val aEnc = audioEncoder
            val wMux = wideMuxer; val uMux = ultraMuxer
            val wUri = wideVideoUri; val uUri = ultraVideoUri
            wideEncoder = null; ultraEncoder = null; audioEncoder = null
            wideMuxer = null; ultraMuxer = null; wideVideoUri = null; ultraVideoUri = null
            isRecording = false
            val resolver = context.contentResolver
            encodingExecutor.execute {
                try { wEnc?.signalEndOfInputStream() } catch (_: Exception) {}
                try { uEnc?.signalEndOfInputStream() } catch (_: Exception) {}
                try { aEnc?.stopAndRelease() } catch (_: Exception) {}
                try { wEnc?.stopAndRelease() } catch (_: Exception) {}
                try { uEnc?.stopAndRelease() } catch (_: Exception) {}
                try { wMux?.stopAndRelease() } catch (_: Exception) {}
                try { uMux?.stopAndRelease() } catch (_: Exception) {}
                wUri?.let { MediaStoreUtils.finalizePending(resolver, it) }
                uUri?.let { MediaStoreUtils.finalizePending(resolver, it) }
                handler?.post {
                    createPreviewSessions()
                    callback.onRecordingStopped()
                }
            }
        }
    }

    /** Synchronous teardown on the camera thread (errors and stop()). */
    private fun safeStopRecording(deleteFiles: Boolean) {
        if (wideEncoder == null && ultraEncoder == null && audioEncoder == null && wideVideoUri == null) {
            isRecording = false
            return
        }
        listOf(mainSession, ultraSession).forEach {
            try { it?.stopRepeating() } catch (_: Exception) {}
            try { it?.abortCaptures() } catch (_: Exception) {}
        }
        closeSessions()
        try { wideEncoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { ultraEncoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { audioEncoder?.stopAndRelease() } catch (_: Exception) {}
        try { wideEncoder?.stopAndRelease() } catch (_: Exception) {}
        try { ultraEncoder?.stopAndRelease() } catch (_: Exception) {}
        try { wideMuxer?.stopAndRelease() } catch (_: Exception) {}
        try { ultraMuxer?.stopAndRelease() } catch (_: Exception) {}
        val resolver = context.contentResolver
        listOfNotNull(wideVideoUri, ultraVideoUri).forEach {
            if (deleteFiles) MediaStoreUtils.delete(resolver, it) else MediaStoreUtils.finalizePending(resolver, it)
        }
        wideEncoder = null; ultraEncoder = null; audioEncoder = null
        wideMuxer = null; ultraMuxer = null; wideVideoUri = null; ultraVideoUri = null
        isRecording = false
    }

    // ------------------------------------------------------------------ helpers

    private fun orientationHint(): Int {
        val deg = when (displayRotation) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        return (sensorOrientation - deg + 360) % 360
    }
}

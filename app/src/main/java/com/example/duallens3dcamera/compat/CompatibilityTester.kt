package com.example.duallens3dcamera.compat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.example.duallens3dcamera.camera.LensDiscovery
import com.example.duallens3dcamera.camera.LivePreview3d
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Automated check of whether this phone can do dual-lens stereo with this app:
 * camera IDs (including hidden ones), the rear logical pair, a simultaneous photo pair, stereo
 * video into real encoders (with the hidden-camera workaround if needed) and the 4-stream live
 * 3D preview. Blocking; run off the main thread. [log] receives human-readable lines.
 */
class CompatibilityTester(private val context: Context, private val log: (String) -> Unit) {

    enum class Support { YES, YES_WITH_WORKAROUND, NO, NOT_TESTED }

    data class Verdict(
        val photos: Support = Support.NOT_TESTED,
        val video: Support = Support.NOT_TESTED,
        val livePreview: Support = Support.NOT_TESTED,
        val notes: List<String> = emptyList()
    )

    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("CompatTest").apply { start() }
    private val handler = Handler(thread.looper)

    fun run(): Verdict {
        val notes = ArrayList<String>()
        try {
            log("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.HARDWARE}")
            log("")

            // 1) Cameras
            log("[1] Cameras")
            val listed = cm.cameraIdList.toList()
            log("  Listed IDs: ${listed.joinToString()}")
            val hidden = (0..15).map { it.toString() }.filter { it !in listed && charsOrNull(it) != null }
            log("  Hidden IDs that answer when asked by number: ${if (hidden.isEmpty()) "none" else hidden.joinToString()}")
            for (id in listed + hidden) {
                val c = charsOrNull(id) ?: continue
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"; CameraCharacteristics.LENS_FACING_FRONT -> "front"; else -> "external"
                }
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val phys = c.physicalCameraIds
                val logical = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
                log("  ID $id${if (id in hidden) " (hidden)" else ""}: $facing, ${focal?.let { "%.2f mm".format(it) } ?: "?"}, " +
                    "sensor ${sensor?.let { "%.2fx%.2f mm".format(it.width, it.height) } ?: "?"}" +
                    if (logical) ", paired camera of ${phys.joinToString("+")}" else "")
            }
            try {
                val sets = cm.concurrentCameraIds
                log("  Concurrent camera sets: ${if (sets.isEmpty()) "none advertised" else sets.joinToString { it.sorted().joinToString("+", "{", "}") }}")
            } catch (_: Exception) {}
            log("")

            // 2) Stereo pair
            log("[2] Rear stereo pair")
            val rig = LensDiscovery.discoverStereoRig(cm)
            if (rig == null) {
                log("  ✗ No rear paired (logical multi-) camera with two lenses was found.")
                log("")
                log("[2b] Two separate rear cameras at once")
                if (testSeparatePair(listed + hidden)) {
                    notes += "No paired camera, but the main and ultrawide cameras ran at the same time as separate cameras. " +
                        "The app does not use this yet; please report this result so a fallback mode can be added."
                } else {
                    notes += "This phone does not expose two rear lenses together to apps. A mirror adapter on the main camera is the alternative."
                }
                log("")
                return Verdict(Support.NO, Support.NO, Support.NO, notes)
            }
            val logicalChars = cm.getCameraCharacteristics(rig.logicalRearId)
            val wideChars = cm.getCameraCharacteristics(rig.wide1xId)
            val ultraChars = cm.getCameraCharacteristics(rig.ultraId)
            val sync = when (logicalChars.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)) {
                CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED -> "calibrated (hardware)"
                CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE -> "approximate"
                else -> "unknown"
            }
            log("  ✓ Paired camera ${rig.logicalRearId}${if (rig.logicalIsHidden) " (hidden from the list)" else ""}: " +
                "main = ${rig.wide1xId}, ultrawide = ${rig.ultraId}, sensor sync $sync")
            if (rig.logicalIsHidden) notes += "Uses a hidden paired camera (found by probing IDs)."
            log("")

            // Each step gets a freshly opened camera: a failed session can close the device
            // (e.g. "Function not implemented (-38)" on the Redmi Note 10 Pro), and later steps
            // must not inherit that.
            // 3) Photo pair
            log("[3] Stereo photo (both lenses in one capture)")
            val photos = withCamera(rig.logicalRearId) { testPhoto(it, rig, wideChars, ultraChars) }
            log("")

            // 4) Video
            log("[4] Stereo video (two encoders, ~1.5 s)")
            var video = withCamera(rig.logicalRearId) { testVideo(it, rig, wideChars, ultraChars, wideViaLogical = false) }
            if (video == Support.NO) {
                log("  Retrying with the main-lens video taken from the paired camera's own stream...")
                video = if (withCamera(rig.logicalRearId) { testVideo(it, rig, wideChars, ultraChars, wideViaLogical = true) } == Support.YES) {
                    notes += "Video needs the workaround (main lens from the paired stream); the app applies it automatically for hidden paired cameras."
                    Support.YES_WITH_WORKAROUND
                } else Support.NO
            }
            log("")

            // 5) Live 3D preview
            log("[5] Live 3D preview (4 streams)")
            val live = withCamera(rig.logicalRearId) { testLivePreview(it, rig, wideChars, ultraChars) }
            log("")

            // The paired camera exists but cannot deliver both lenses: try them as separate cameras.
            if (photos == Support.NO && video == Support.NO) {
                log("[2b] Two separate rear cameras at once (paired camera did not work)")
                if (testSeparatePair(listed + hidden)) {
                    notes += "The paired camera could not stream both lenses, but the main and ultrawide cameras ran at the same time as separate cameras. " +
                        "The app does not use this yet; please report this result so a fallback mode can be added."
                }
                log("")
            }
            return Verdict(photos, video, live, notes)
        } catch (e: Exception) {
            log("  ✗ Test stopped: ${e.javaClass.simpleName}: ${e.message}")
            return Verdict(notes = notes + "Test stopped early: ${e.message}")
        } finally {
            thread.quitSafely()
        }
    }

    /** Opens [id], runs [step] on it and closes it. Open failures and step exceptions count as NO. */
    private fun withCamera(id: String, step: (CameraDevice) -> Support): Support {
        val opened = openDetailed(id)
        val camera = opened.device ?: run { log("  ✗ Could not open camera $id: ${opened.error}"); return Support.NO }
        return try {
            step(camera)
        } catch (e: Exception) {
            log("  ✗ Step stopped: ${e.javaClass.simpleName}: ${e.message}")
            Support.NO
        } finally {
            camera.close()
        }
    }

    // ------------------------------------------------------------------ steps

    private fun testPhoto(camera: CameraDevice, rig: LensDiscovery.StereoRig,
                          wideChars: CameraCharacteristics, ultraChars: CameraCharacteristics): Support {
        val wSize = pickSize(wideChars, ImageFormat.YUV_420_888, 1600)
        val uSize = pickSize(ultraChars, ImageFormat.YUV_420_888, 1600)
        if (wSize == null || uSize == null) { log("  ✗ No YUV sizes on the lenses."); return Support.NO }
        val wr = ImageReader.newInstance(wSize.width, wSize.height, ImageFormat.YUV_420_888, 2)
        val ur = ImageReader.newInstance(uSize.width, uSize.height, ImageFormat.YUV_420_888, 2)
        val latch = CountDownLatch(2)
        val stats = arrayOfNulls<Pair<Long, Double>>(2)
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)  // only count the still, not warm-up frames
        fun listen(i: Int) = ImageReader.OnImageAvailableListener { r ->
            r.acquireLatestImage()?.use { img ->
                if (armed.get() && stats[i] == null) { stats[i] = img.timestamp to lumaStd(img); latch.countDown() }
            }
        }
        wr.setOnImageAvailableListener(listen(0), handler)
        ur.setOnImageAvailableListener(listen(1), handler)
        try {
            val session = configure(camera, listOf(
                OutputConfiguration(wr.surface).apply { setPhysicalCameraId(rig.wide1xId) },
                OutputConfiguration(ur.surface).apply { setPhysicalCameraId(rig.ultraId) }
            )) ?: run { log("  ✗ The phone refused a session with both lenses."); return Support.NO }
            try {
                // Let exposure settle with a short preview of both lenses, wait for in-flight frames
                // to land, then take one still that targets both.
                val prev = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(wr.surface); addTarget(ur.surface)
                }
                session.setRepeatingRequest(prev.build(), null, handler)
                Thread.sleep(800)
                session.stopRepeating()
                session.abortCaptures()
                Thread.sleep(400)
                val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(wr.surface); addTarget(ur.surface)
                }
                handler.post { armed.set(true) }  // after any already-queued listener calls
                Thread.sleep(50)
                session.capture(still.build(), null, handler)
                if (!latch.await(4, TimeUnit.SECONDS) || stats[0] == null || stats[1] == null) {
                    log("  ✗ Only ${listOfNotNull(stats[0], stats[1]).size} of 2 lens images arrived.")
                    return Support.NO
                }
                val (tw, sw) = stats[0]!!; val (tu, su) = stats[1]!!
                val skewMs = abs(tw - tu) / 1e6
                log("  Main ${wSize.width}x${wSize.height} detail %.1f, ultrawide ${uSize.width}x${uSize.height} detail %.1f, time gap %.2f ms".format(sw, su, skewMs))
                if (sw < 2.0 || su < 2.0) { log("  ✗ One image is blank."); return Support.NO }
                log("  ✓ Both lenses captured together${if (skewMs > 5) " (gap is large; fast motion may not line up)" else ""}.")
                return Support.YES
            } finally {
                session.close()
            }
        } finally {
            wr.close(); ur.close()
        }
    }

    private fun testVideo(camera: CameraDevice, rig: LensDiscovery.StereoRig, wideChars: CameraCharacteristics,
                          ultraChars: CameraCharacteristics, wideViaLogical: Boolean): Support {
        val size = commonPrivateSize(wideChars, ultraChars) ?: run { log("  ✗ No common video size."); return Support.NO }
        val encW = Encoder(size); val encU = Encoder(size)
        // Same layout as the app's recording session: a preview stream from the main lens plus
        // one encoder per lens. (Without the preview, some phones pass here but fail in the app.)
        val previewTex = SurfaceTexture(false).apply { setDefaultBufferSize(640, 480) }
        val previewSurface = Surface(previewTex)
        try {
            val session = configure(camera, listOf(
                OutputConfiguration(previewSurface).apply { setPhysicalCameraId(rig.wide1xId) },
                OutputConfiguration(encW.surface).apply { if (!wideViaLogical) setPhysicalCameraId(rig.wide1xId) },
                OutputConfiguration(encU.surface).apply { setPhysicalCameraId(rig.ultraId) }
            )) ?: run { log("  ✗ The phone refused a video session with both lenses."); return Support.NO }
            try {
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface); addTarget(encW.surface); addTarget(encU.surface)
                }
                session.setRepeatingRequest(req.build(), null, handler)
                Thread.sleep(1500)
                session.stopRepeating()
                Thread.sleep(150)
            } finally {
                session.close()
            }
        } finally {
            encW.finish(); encU.finish()
            previewSurface.release(); previewTex.release()
        }
        val wAvg = encW.avgBytes(); val uAvg = encU.avgBytes()
        log("  ${size.width}x${size.height}: main ${encW.frames} frames (~${wAvg} bytes/frame), ultrawide ${encU.frames} frames (~${uAvg} bytes/frame)")
        // Blank frames compress to almost nothing; real 720p frames are tens of kB.
        val wOk = encW.frames >= 8 && wAvg > 2500
        val uOk = encU.frames >= 8 && uAvg > 2500
        return when {
            wOk && uOk -> { log("  ✓ Both lenses recorded real video."); Support.YES }
            !wOk && uOk -> { log("  ✗ The main lens recorded blank or no frames."); Support.NO }
            wOk && !uOk -> { log("  ✗ The ultrawide recorded blank or no frames."); Support.NO }
            else -> { log("  ✗ Neither lens recorded video."); Support.NO }
        }
    }

    private fun testLivePreview(camera: CameraDevice, rig: LensDiscovery.StereoRig,
                                wideChars: CameraCharacteristics, ultraChars: CameraCharacteristics): Support {
        val ws = LivePreview3d.pickSmallSize(wideChars.yuvSizes()); val us = LivePreview3d.pickSmallSize(ultraChars.yuvSizes())
        val wStill = pickSize(wideChars, ImageFormat.YUV_420_888, 4096); val uStill = pickSize(ultraChars, ImageFormat.YUV_420_888, 4096)
        if (ws == null || us == null || wStill == null || uStill == null) { log("  ✗ Missing sizes."); return Support.NO }
        val readers = listOf(
            ImageReader.newInstance(ws.width, ws.height, ImageFormat.YUV_420_888, 2),
            ImageReader.newInstance(us.width, us.height, ImageFormat.YUV_420_888, 2),
            ImageReader.newInstance(wStill.width, wStill.height, ImageFormat.YUV_420_888, 2),
            ImageReader.newInstance(uStill.width, uStill.height, ImageFormat.YUV_420_888, 2)
        )
        val got = arrayOf(AtomicLong(), AtomicLong())
        readers[0].setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); got[0].incrementAndGet() }, handler)
        readers[1].setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); got[1].incrementAndGet() }, handler)
        try {
            val ids = listOf(rig.wide1xId, rig.ultraId, rig.wide1xId, rig.ultraId)
            val session = configure(camera, readers.mapIndexed { i, r -> OutputConfiguration(r.surface).apply { setPhysicalCameraId(ids[i]) } })
                ?: run { log("  ✗ The phone refused the 4-stream setup; the app will keep the normal 2D preview."); return Support.NO }
            try {
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(readers[0].surface); addTarget(readers[1].surface)
                }
                session.setRepeatingRequest(req.build(), null, handler)
                Thread.sleep(1000)
                session.stopRepeating()
            } finally {
                session.close()
            }
        } finally {
            readers.forEach { it.close() }
        }
        log("  Frames in 1 s: main ${got[0].get()}, ultrawide ${got[1].get()}")
        return if (got[0].get() >= 5 && got[1].get() >= 5) { log("  ✓ Live 3D preview works."); Support.YES }
        else { log("  ✗ Streams did not deliver frames."); Support.NO }
    }

    /**
     * For phones without a logical multi-camera: open the main and ultrawide rear cameras as two
     * independent devices and check that both stream at the same time. Logs open errors, frame
     * counts, picture detail and the typical time gap between the two cameras' frames.
     * Returns true when both delivered real frames together.
     */
    private fun testSeparatePair(ids: List<String>): Boolean {
        data class Rear(val id: String, val focal: Float, val chars: CameraCharacteristics)
        val rears = ids.mapNotNull { id ->
            val c = charsOrNull(id) ?: return@mapNotNull null
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) return@mapNotNull null
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@mapNotNull null
            Rear(id, focal, c)
        }
        // The main camera is the first rear camera listed (Android convention); the ultrawide is
        // the rear camera with the shortest focal length, if clearly shorter than the main's.
        val main = rears.firstOrNull()
        val ultra = if (main == null) null
            else rears.filter { it.id != main.id && it.focal < main.focal * 0.8f }.minByOrNull { it.focal }
        if (main == null || ultra == null) {
            log("  ✗ Could not find separate main and ultrawide rear cameras.")
            return false
        }
        log("  Main = ID ${main.id} (%.2f mm), ultrawide = ID ${ultra.id} (%.2f mm)".format(main.focal, ultra.focal))

        val first = openDetailed(main.id)
        val mainCam = first.device ?: run { log("  ✗ Could not open main camera ${main.id}: ${first.error}"); return false }
        try {
            val second = openDetailed(ultra.id)
            val ultraCam = second.device ?: run {
                log("  ✗ The phone refused to open the ultrawide while the main camera was open: ${second.error}")
                return false
            }
            try {
                Thread.sleep(200)  // let any eviction of the first camera arrive
                if (first.lost.get()) {
                    log("  ✗ Opening the ultrawide closed the main camera (${first.lostReason}); only one rear camera can be open at a time.")
                    return false
                }
                log("  ✓ Both cameras opened.")
                return streamBoth(mainCam, main.chars, ultraCam, ultra.chars, first, second)
            } finally {
                ultraCam.close()
            }
        } finally {
            mainCam.close()
        }
    }

    private fun streamBoth(mainCam: CameraDevice, mainChars: CameraCharacteristics,
                           ultraCam: CameraDevice, ultraChars: CameraCharacteristics,
                           mainOpen: OpenResult, ultraOpen: OpenResult): Boolean {
        val ms = pickSize(mainChars, ImageFormat.YUV_420_888, 1600)
        val us = pickSize(ultraChars, ImageFormat.YUV_420_888, 1600)
        if (ms == null || us == null) { log("  ✗ No YUV sizes."); return false }
        val readers = listOf(
            ImageReader.newInstance(ms.width, ms.height, ImageFormat.YUV_420_888, 3),
            ImageReader.newInstance(us.width, us.height, ImageFormat.YUV_420_888, 3)
        )
        // Only touched on [handler].
        val stamps = listOf(ArrayList<Long>(), ArrayList<Long>())
        val detail = DoubleArray(2)
        readers.forEachIndexed { i, r ->
            r.setOnImageAvailableListener({ rr ->
                rr.acquireLatestImage()?.use { img ->
                    stamps[i].add(img.timestamp)
                    if (stamps[i].size % 5 == 0) detail[i] = lumaStd(img)
                }
            }, handler)
        }
        try {
            val s1 = configure(mainCam, listOf(OutputConfiguration(readers[0].surface)))
                ?: run { log("  ✗ The main camera refused its session."); return false }
            try {
                val s2 = configure(ultraCam, listOf(OutputConfiguration(readers[1].surface)))
                    ?: run { log("  ✗ The ultrawide refused its session while the main camera was set up."); return false }
                try {
                    for ((cam, s, r) in listOf(Triple(mainCam, s1, readers[0]), Triple(ultraCam, s2, readers[1]))) {
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }
                        s.setRepeatingRequest(req.build(), null, handler)
                    }
                    Thread.sleep(2000)
                    s1.stopRepeating(); s2.stopRepeating()
                    Thread.sleep(150)
                } finally {
                    s2.close()
                }
            } finally {
                s1.close()
            }
        } catch (e: Exception) {
            log("  ✗ Streaming stopped: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        // Copy on the handler thread, after any queued listener calls, then close the readers.
        val copy = CountDownLatch(1)
        var a: List<Long> = emptyList(); var b: List<Long> = emptyList()
        val d = DoubleArray(2)
        handler.post { a = stamps[0].toList(); b = stamps[1].toList(); detail.copyInto(d); copy.countDown() }
        copy.await(1, TimeUnit.SECONDS)
        readers.forEach { it.close() }
        log("  ${ms.width}x${ms.height} + ${us.width}x${us.height} in 2 s: main ${a.size} frames (detail %.1f), ultrawide ${b.size} frames (detail %.1f)"
            .format(d[0], d[1]))
        if (mainOpen.lost.get()) { log("  ✗ The phone closed the main camera during the test (${mainOpen.lostReason})."); return false }
        if (ultraOpen.lost.get()) { log("  ✗ The phone closed the ultrawide during the test (${ultraOpen.lostReason})."); return false }
        if (a.size < 10 || b.size < 10) { log("  ✗ Not both cameras delivered frames."); return false }
        if (d[0] < 2.0 || d[1] < 2.0) { log("  ✗ One camera delivered blank frames."); return false }
        // Typical gap from each main frame to the nearest ultrawide frame (both use the same clock).
        val sortedB = b.sorted()
        val gaps = a.map { t ->
            val i = sortedB.binarySearch(t).let { if (it < 0) -it - 1 else it }
            listOfNotNull(sortedB.getOrNull(i - 1), sortedB.getOrNull(i)).minOf { abs(it - t) } / 1e6
        }.sorted()
        log("  Time gap to the nearest ultrawide frame: median %.1f ms, worst %.1f ms (no hardware sync)"
            .format(gaps[gaps.size / 2], gaps.last()))
        log("  ✓ Both rear cameras streamed at the same time as separate cameras.")
        return true
    }

    private class OpenResult {
        @Volatile var device: CameraDevice? = null
        @Volatile var error: String? = null
        val lost = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var lostReason: String = ""
    }

    /** Opens a camera, reporting why opening failed and whether the phone later takes the camera away. */
    @SuppressLint("MissingPermission") // The activity checks CAMERA permission before running.
    private fun openDetailed(id: String): OpenResult {
        val result = OpenResult()
        val latch = CountDownLatch(1)
        fun fail(camera: CameraDevice, reason: String) {
            if (result.device != null) { result.lostReason = reason; result.lost.set(true) }
            else result.error = reason
            camera.close(); latch.countDown()
        }
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { result.device = camera; latch.countDown() }
                override fun onDisconnected(camera: CameraDevice) = fail(camera, "disconnected")
                override fun onError(camera: CameraDevice, error: Int) = fail(camera, errorName(error))
            }, handler)
        } catch (e: Exception) {
            result.error = "${e.javaClass.simpleName}: ${e.message}"
            return result
        }
        if (!latch.await(5, TimeUnit.SECONDS)) result.error = "timed out"
        if (result.device == null && result.error == null) result.error = "unknown"
        return result
    }

    private fun errorName(code: Int) = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "camera in use"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "camera disabled"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "camera device error"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera service error"
        else -> "error $code"
    }

    // ------------------------------------------------------------------ helpers

    private fun charsOrNull(id: String): CameraCharacteristics? =
        try { cm.getCameraCharacteristics(id) } catch (_: Exception) { null }


    private fun configure(camera: CameraDevice, outputs: List<OutputConfiguration>): CameraCaptureSession? {
        val latch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        try {
            camera.createCaptureSessionByOutputConfigurations(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; latch.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { latch.countDown() }
            }, handler)
        } catch (e: Exception) {
            log("  (session error: ${e.message})")
            return null
        }
        latch.await(5, TimeUnit.SECONDS)
        return session
    }

    private fun CameraCharacteristics.yuvSizes(): Array<Size>? =
        get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)

    /** Largest 4:3-ish size with width <= maxWidth. */
    private fun pickSize(c: CameraCharacteristics, format: Int, maxWidth: Int): Size? {
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(format) ?: return null
        return sizes.filter { it.width <= maxWidth && abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.05 }
            .maxByOrNull { it.width * it.height } ?: sizes.minByOrNull { abs(it.width - maxWidth) }
    }

    private fun commonPrivateSize(a: CameraCharacteristics, b: CameraCharacteristics): Size? {
        fun sizes(c: CameraCharacteristics) =
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()
        val common = sizes(a).intersect(sizes(b))
        return common.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: common.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height }
    }

    /** Standard deviation of a 64x64 sample grid of the luma plane (0 for a blank frame). */
    private fun lumaStd(img: Image): Double {
        val p = img.planes[0]; val buf = p.buffer
        var sum = 0.0; var sq = 0.0; var n = 0
        for (gy in 0 until 64) for (gx in 0 until 64) {
            val x = gx * img.width / 64; val y = gy * img.height / 64
            val idx = y * p.rowStride + x * p.pixelStride
            if (idx >= buf.limit()) continue
            val v = (buf.get(idx).toInt() and 0xFF).toDouble()
            sum += v; sq += v * v; n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sqrt((sq / n - mean * mean).coerceAtLeast(0.0))
    }

    /** Surface-input H.264 encoder that counts output frames and bytes. */
    private class Encoder(size: Size) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val surface: Surface
        @Volatile var frames = 0; private set
        @Volatile private var bytes = 0L
        @Volatile private var running = true
        private val drainer: Thread

        init {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            drainer = Thread {
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val idx = try { codec.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
                    if (idx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            frames++; bytes += info.size
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (!running && idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // keep waiting for EOS
                    }
                }
            }.apply { name = "CompatEncoderDrain"; start() }
        }

        fun avgBytes(): Long = if (frames == 0) 0 else bytes / frames

        fun finish() {
            running = false
            try { codec.signalEndOfInputStream() } catch (_: Exception) {}
            drainer.join(2000)
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            surface.release()
        }
    }
}

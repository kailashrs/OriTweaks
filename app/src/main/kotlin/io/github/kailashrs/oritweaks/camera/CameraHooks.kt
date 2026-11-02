package io.github.kailashrs.oritweaks.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.util.Size
import android.view.Surface
import io.github.kailashrs.oritweaks.core.Hooks
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Full-resolution capture for third-party camera apps, hooked on the framework classes inside the app's
 * own process so it works regardless of the app's (often obfuscated) code.
 *
 * With exposeFullSizeForQCFA=TRUE in camxoverridesettings.txt (plus the matching sensor-mode selector
 * rules) the HAL advertises ULTRA_HIGH_RESOLUTION_SENSOR and 50 MP outputs in the maximum-resolution
 * stream map. Apps written against other phones get the pixel-mode rules wrong (PhotonCamera puts
 * SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION on its preview and mixes preview and full-size targets), which
 * cameraserver rejects. Each request is made consistent with its targets: full-size outputs are
 * declared for maximum-resolution mode, requests that hit them run in that mode and drop the other
 * targets, every other request runs in default mode, and full-size bursts are cut to a memory budget.
 * Outputs are classified by size as soon as their ImageReader hands out a surface, because apps build
 * capture requests before configuring the session that will run them.
 */
object CameraHooks {
    private const val MAX = CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
    private const val DEFAULT = CaptureRequest.SENSOR_PIXEL_MODE_DEFAULT

    /** Raw frames a full-size burst may hold at once (one 50 MP RAW16 frame is ~100 MB). */
    private const val BURST_BUDGET_BYTES = 800L * 1024 * 1024

    /** Output sizes ("format:WxH") of every camera the app looked at, split by pixel mode. */
    private val normalSizes = ConcurrentHashMap.newKeySet<String>()
    private val fullSizes = ConcurrentHashMap.newKeySet<String>()

    /** Size of each ImageReader surface that can only be filled at full resolution. */
    private val fullSizeOnly = Collections.synchronizedMap(WeakHashMap<Surface, Size>())

    /** Active arrays (normal, full resolution) of the camera opened last; crop regions are rescaled with them. */
    @Volatile
    private var activeArrays: Pair<Rect, Rect>? = null
    private val targets = Collections.synchronizedMap(WeakHashMap<CaptureRequest.Builder, MutableSet<Surface>>())
    private val hooked = ConcurrentHashMap.newKeySet<Any>()

    fun install() {
        // CameraDevice and CameraCaptureSession are hidden implementation classes; reach them through
        // the app's own callbacks and hook their public API methods.
        Hooks.hookAll(CameraManager::class.java, "openCamera") { chain ->
            // Apps may have cached the characteristics before the hooks below saw them.
            (chain.args.firstOrNull() as? String)?.let { id ->
                runCatching {
                    val chars = (chain.thisObject as CameraManager).getCameraCharacteristics(id)
                    learnSizes(chars)
                    val normal = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val full = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)
                    activeArrays = if (normal != null && full != null) normal to full else null
                }.onFailure { Hooks.error("camera $id: size scan failed", it) }
            }
            chain.args.filterIsInstance<CameraDevice.StateCallback>().firstOrNull()?.let { callback ->
                hookOnce(callback.javaClass, "onOpened") { opened ->
                    Hooks.hook(opened) { inner ->
                        (inner.args[0] as? CameraDevice)?.let { hookDevice(it.javaClass) }
                        inner.proceed()
                    }
                }
            }
            chain.proceed()
        }
        Hooks.hook(CameraManager::class.java.getMethod("getCameraCharacteristics", String::class.java)) { chain ->
            val chars = chain.proceed() as CameraCharacteristics?
            if (chars != null) runCatching { learnSizes(chars) }.onFailure { Hooks.error("camera: size scan failed", it) }
            chars
        }
        // Apps size full-resolution RAW from the maximum pixel array, but the HP9 only streams RAW at
        // 8160x6144 (its 16320x12288 output exists for processed formats only): report the largest
        // full-resolution RAW size instead so RAW apps ask for a stream the camera can deliver.
        Hooks.hook(CameraCharacteristics::class.java.getMethod("get", CameraCharacteristics.Key::class.java)) { chain ->
            val value = chain.proceed()
            if (chain.args[0] != CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION || value !is Size) return@hook value
            val chars = chain.thisObject as CameraCharacteristics
            val raw = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
            if (raw != null && raw.width.toLong() * raw.height < value.width.toLong() * value.height) raw else value
        }
        Hooks.hook(ImageReader::class.java.getMethod("getSurface")) { chain ->
            val surface = chain.proceed() as Surface?
            val reader = chain.thisObject as ImageReader
            if (surface != null) {
                val key = sizeKey(reader.imageFormat, reader.width, reader.height)
                if (key in fullSizes && key !in normalSizes) fullSizeOnly[surface] = Size(reader.width, reader.height)
                else fullSizeOnly.remove(surface)
            }
            surface
        }
        Hooks.hook(CaptureRequest.Builder::class.java.getMethod("addTarget", Surface::class.java)) { chain ->
            val builder = chain.thisObject as CaptureRequest.Builder
            targets.getOrPut(builder) { Collections.synchronizedSet(LinkedHashSet()) }.add(chain.args[0] as Surface)
            chain.proceed()
        }
        Hooks.hook(CaptureRequest.Builder::class.java.getMethod("removeTarget", Surface::class.java)) { chain ->
            targets[chain.thisObject as CaptureRequest.Builder]?.remove(chain.args[0] as Surface)
            chain.proceed()
        }
        Hooks.hook(CaptureRequest.Builder::class.java.getMethod("build")) { chain ->
            runCatching { fixPixelMode(chain.thisObject as CaptureRequest.Builder) }
                .onFailure { Hooks.error("camera: pixel mode fix failed", it) }
            chain.proceed()
        }
    }

    private fun sizeKey(format: Int, width: Int, height: Int) = "$format:${width}x$height"

    private fun learnSizes(chars: CameraCharacteristics) {
        fun add(map: StreamConfigurationMap?, into: MutableSet<String>) {
            map ?: return
            for (format in map.outputFormats) {
                map.getOutputSizes(format)?.forEach { into += sizeKey(format, it.width, it.height) }
            }
        }
        add(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), normalSizes)
        add(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION), fullSizes)
    }

    private fun hookDevice(type: Class<*>) {
        if (!hooked.add(type)) return
        Hooks.hook(type.getMethod("createCaptureSession", SessionConfiguration::class.java)) { chain ->
            val config = chain.args[0] as SessionConfiguration
            for (output in config.outputConfigurations) {
                if (output.surface?.let { it in fullSizeOnly } == true) output.addSensorPixelModeUsed(MAX)
            }
            hookOnce(config.stateCallback.javaClass, "onConfigured") { configured ->
                Hooks.hook(configured) { inner ->
                    (inner.args[0] as? CameraCaptureSession)?.let { hookSession(it.javaClass) }
                    inner.proceed()
                }
            }
            chain.proceed()
        }
    }

    private fun fixPixelMode(builder: CaptureRequest.Builder) {
        val surfaces = targets[builder]?.toList() ?: return
        val full = surfaces.filter { it in fullSizeOnly }
        val requested = builder.get(CaptureRequest.SENSOR_PIXEL_MODE)
        when {
            full.isEmpty() -> if (requested == MAX) builder.set(CaptureRequest.SENSOR_PIXEL_MODE, DEFAULT)
            full.size == surfaces.size -> if (requested != MAX) builder.set(CaptureRequest.SENSOR_PIXEL_MODE, MAX)
            requested == MAX -> surfaces.filter { it !in fullSizeOnly }.forEach { builder.removeTarget(it) }
            else -> full.forEach { builder.removeTarget(it) }
        }
        if (builder.get(CaptureRequest.SENSOR_PIXEL_MODE) == MAX) {
            val frame = full.mapNotNull { fullSizeOnly[it] }.maxByOrNull { it.width.toLong() * it.height } ?: return
            fixCropRegion(builder, frame)
        }
    }

    /**
     * At full resolution the crop region is read against the full-resolution active array (16320x12288
     * on the HP9). Apps compute it against the normal active array or their RAW size (8160x6144), which
     * selects the top-left part of the sensor, so rescale it from whichever frame it fits.
     */
    private fun fixCropRegion(builder: CaptureRequest.Builder, frame: Size) {
        val crop = builder.get(CaptureRequest.SCALER_CROP_REGION) ?: return
        val (normal, full) = activeArrays ?: return
        val from = when {
            crop.right <= normal.width() && crop.bottom <= normal.height() && normal.width() < full.width() -> normal.width() to normal.height()
            crop.right <= frame.width && crop.bottom <= frame.height && frame.width < full.width() -> frame.width to frame.height
            else -> return
        }
        val sx = full.width().toDouble() / from.first
        val sy = full.height().toDouble() / from.second
        fun even(value: Double) = (value / 2).toInt() * 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(
            full.left + even(crop.left * sx), full.top + even(crop.top * sy),
            full.left + even(crop.right * sx), full.top + even(crop.bottom * sy)))
    }

    private fun hookSession(type: Class<*>) {
        if (!hooked.add(type)) return
        for (method in type.methods) {
            if (method.name != "captureBurst" && method.name != "captureBurstRequests") continue
            Hooks.hook(method) { chain ->
                @Suppress("UNCHECKED_CAST")
                val requests = chain.args[0] as? List<CaptureRequest> ?: return@hook chain.proceed()
                val limit = burstLimit(requests)
                if (limit >= requests.size) return@hook chain.proceed()
                val args = chain.args.toTypedArray()
                args[0] = ArrayList(requests.subList(0, limit))
                chain.proceed(args)
            }
        }
    }

    private fun burstLimit(requests: List<CaptureRequest>): Int {
        if (requests.none { it.get(CaptureRequest.SENSOR_PIXEL_MODE) == MAX }) return requests.size
        val pixels = synchronized(fullSizeOnly) { fullSizeOnly.values.maxOfOrNull { it.width.toLong() * it.height } } ?: return requests.size
        return (BURST_BUDGET_BYTES / (pixels * 2)).toInt().coerceIn(1, requests.size)
    }

    private fun hookOnce(type: Class<*>, name: String, install: (java.lang.reflect.Method) -> Unit) {
        val method = generateSequence(type) { it.superclass }
            .mapNotNull { cls -> cls.declaredMethods.firstOrNull { it.name == name && !java.lang.reflect.Modifier.isAbstract(it.modifiers) } }
            .firstOrNull() ?: return
        if (!hooked.add(method)) return
        install(method)
    }
}

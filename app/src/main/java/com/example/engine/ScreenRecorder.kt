package com.example.engine

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.io.File

class ScreenRecorder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val useHevc: Boolean,
    private val outputFile: File,
    private val mediaProjection: MediaProjection,
    private val dpi: Int
) {
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    
    private var isRecording = false
    private var drainThread: Thread? = null
    private var videoTrackIndex = -1
    private var isMuxerStarted = false

    companion object {
        private const val TAG = "ScreenRecorder"
        private const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * Calculates the ideal Bitrate based on the total pixel density and frame rate.
         */
        fun calculateIdealBitrate(width: Int, height: Int, fps: Int, useHevc: Boolean): Int {
            val factor = if (useHevc) 0.045 else 0.075
            val calculated = (width * height * fps * factor).toInt()
            // Coerce between 1 Mbps and 50 Mbps
            return calculated.coerceIn(1_000_000, 50_000_000)
        }

        /**
         * Validates and adjusts width, height, and FPS using MediaCodecList capabilities.
         * Ensures we don't crash when using ultra-high resolutions or framerates on lower-end devices.
         */
        fun getSupportedConfiguration(
            useHevc: Boolean,
            requestedWidth: Int,
            requestedHeight: Int,
            requestedFps: Int
        ): Triple<Int, Int, Int> {
            val mime = if (useHevc) MIME_HEVC else MIME_AVC
            try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (info in codecList.codecInfos) {
                    if (!info.isEncoder) continue
                    if (info.supportedTypes.contains(mime)) {
                        val caps = info.getCapabilitiesForType(mime)
                        val videoCaps = caps.videoCapabilities ?: continue

                        // MediaCodec sizes usually require being multiples of 16 (or 2)
                        val alignedWidth = (requestedWidth / 16) * 16
                        val alignedHeight = (requestedHeight / 16) * 16

                        if (videoCaps.isSizeSupported(alignedWidth, alignedHeight)) {
                            val fpsRange = videoCaps.getSupportedFrameRatesFor(alignedWidth, alignedHeight)
                            val finalFps = requestedFps.toDouble().coerceIn(fpsRange.lower, fpsRange.upper).toInt()
                            return Triple(alignedWidth, alignedHeight, finalFps)
                        } else {
                            // Search for closest supported bounds
                            val supportedWidths = videoCaps.supportedWidths
                            val supportedHeights = videoCaps.supportedHeights
                            val targetWidth = alignedWidth.coerceIn(supportedWidths.lower, supportedWidths.upper)
                            val targetHeight = alignedHeight.coerceIn(supportedHeights.lower, supportedHeights.upper)

                            val finalWidth = ((targetWidth / 16) * 16).coerceAtLeast(16)
                            val finalHeight = ((targetHeight / 16) * 16).coerceAtLeast(16)

                            val fpsRange = videoCaps.getSupportedFrameRatesFor(finalWidth, finalHeight)
                            val finalFps = requestedFps.toDouble().coerceIn(fpsRange.lower, fpsRange.upper).toInt()
                            return Triple(finalWidth, finalHeight, finalFps)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving codec capabilities", e)
            }
            // Standard safe fallback
            return Triple(1280, 720, 30)
        }
    }

    fun start() {
        if (isRecording) return
        
        val mime = if (useHevc) MIME_HEVC else MIME_AVC
        
        // Resolve closest supported configuration to prevent codec crash
        val (finalWidth, finalHeight, finalFps) = getSupportedConfiguration(useHevc, width, height, fps)
        val finalBitrate = if (bitrate <= 0) calculateIdealBitrate(finalWidth, finalHeight, finalFps, useHevc) else bitrate

        Log.d(TAG, "Starting screen recorder setup. Final resolved config: ${finalWidth}x${finalHeight} @ ${finalFps}fps, Bitrate: ${finalBitrate} bps")

        val format = MediaFormat.createVideoFormat(mime, finalWidth, finalHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, finalBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, finalFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between keyframes for high responsiveness
            setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / finalFps)
        }

        try {
            encoder = MediaCodec.createEncoderByType(mime).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }
            
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                finalWidth,
                finalHeight,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )
            
            isRecording = true
            startDrainLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up screen recording pipelines", e)
            stop()
            throw e
        }
    }

    private fun startDrainLoop() {
        drainThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            val localEncoder = encoder ?: return@Thread
            val localMuxer = muxer ?: return@Thread

            try {
                while (isRecording) {
                    val outputBufferIndex = localEncoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (isMuxerStarted) {
                            throw IllegalStateException("Muxer format changed multiple times")
                        }
                        val newFormat = localEncoder.outputFormat
                        videoTrackIndex = localMuxer.addTrack(newFormat)
                        localMuxer.start()
                        isMuxerStarted = true
                        Log.d(TAG, "MediaMuxer started writing video track index: $videoTrackIndex")
                    } else if (outputBufferIndex >= 0) {
                        val encodedData = localEncoder.getOutputBuffer(outputBufferIndex) ?: continue
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0) {
                            if (!isMuxerStarted) {
                                throw IllegalStateException("Muxer write occurred before track initialization")
                            }
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            localMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        localEncoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "MediaCodec drain loop saw End Of Stream")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during media processing loop", e)
            }
        }, "ScreenRecorderDrainThread")
        drainThread?.start()
    }

    fun stop() {
        Log.d(TAG, "Initiating ScreenRecorder shutdown")
        isRecording = false
        
        try {
            drainThread?.join(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Draining thread join interrupted", e)
        }
        
        try {
            encoder?.let {
                it.stop()
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed stopping MediaCodec encoder cleanly", e)
        } finally {
            encoder = null
        }

        try {
            if (isMuxerStarted) {
                muxer?.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed stopping MediaMuxer cleanly", e)
        } finally {
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed releasing MediaMuxer", e)
            }
            muxer = null
            isMuxerStarted = false
        }

        virtualDisplay?.release()
        virtualDisplay = null

        inputSurface?.release()
        inputSurface = null
    }
}

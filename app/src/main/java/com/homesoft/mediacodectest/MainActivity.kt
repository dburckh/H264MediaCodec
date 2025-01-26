package com.homesoft.mediacodectest

import android.content.Context
import android.graphics.Camera
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG
import android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
import android.media.MediaCodec.LinearBlock
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.homesoft.mediacodectest.NalUnitFinder.Companion.NAL_TYPE_IDR
import com.homesoft.mediacodectest.NalUnitFinder.Companion.NAL_TYPE_SPS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private var camera:Camera = Camera(this)
    private var scope = CoroutineScope(Dispatchers.IO)
    private lateinit var initJob:Job
    private lateinit var delayText: TextView
    private var job: Job? = null

    private var imageReader : ImageReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        initJob = scope.launch {
            camera.init()
        }
        delayText = findViewById(R.id.delay)
        val surfaceView = findViewById<SurfaceView>(R.id.surfaceView)
        surfaceView.holder.addCallback(this)
        //testImageReader()
    }

    private fun testImageReader() {
        ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.PRIVATE, 1).let {
            imageReader = it
            it.setOnImageAvailableListener({ reader->
                reader.acquireNextImage()?.close()
            }, Handler(Looper.getMainLooper()))
            testRender(it.surface)
        }
    }

    private fun testRender(surface: Surface) {
        val renderer = Renderer(surface)
        job = scope.launch {
            initJob.join()
            val imageChannel = camera.start(this)
            launch(Dispatchers.Main) {
                while (true) {
                    delay(500)
                    delayText.text = getString(R.string.delay, renderer.averageDelayMs)
                }
            }
            renderer.start(camera.sps, camera.pps, imageChannel)
        }.also {
            it.invokeOnCompletion {
                renderer.stop()
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        testRender(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        //
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        job?.cancel()
    }

    /**
     * Simulate a camera operating at 30 fps
     */
    class Camera(private val context: Context) {
        private val frameMs = TimeUnit.SECONDS.toMillis(1) / 30
        lateinit var sps:ByteBuffer
        lateinit var pps:ByteBuffer

        private lateinit var nalUnitFinder: NalUnitFinder
        private var idrStart = 0

        fun init() {
            context.resources.assets.open("video.h264").use { inputStream ->
                val array = ByteArray(inputStream.available())
                inputStream.read(array)
                nalUnitFinder = NalUnitFinder(array)
                val spsStart = nalUnitFinder.findNext(-1)
                val ppsStart = nalUnitFinder.findNext(spsStart)
                idrStart = nalUnitFinder.findNext(ppsStart)
                sps = ByteBuffer.allocateDirect(ppsStart - spsStart)
                nalUnitFinder.put(sps, spsStart, ppsStart)
                pps = ByteBuffer.allocateDirect(idrStart - ppsStart)
                nalUnitFinder.put(pps, ppsStart, idrStart)
            }
        }

        fun start(scope: CoroutineScope):ReceiveChannel<ByteBuffer> {
            val channel = Channel<ByteBuffer>(Channel.UNLIMITED)
            scope.launch {
                // Send the SPS/PPS only once
                val configBuffer = ByteBuffer.allocateDirect(idrStart)
                nalUnitFinder.put(configBuffer, 0, idrStart)
                channel.send(configBuffer)

                var startIndex = idrStart
                while (true) {
                    val nextIndex = nalUnitFinder.findNext(startIndex)
                    val byteBuffer = ByteBuffer.allocateDirect(nextIndex - startIndex)
                    nalUnitFinder.put(byteBuffer, startIndex, nextIndex)
                    startIndex = if (nalUnitFinder.isEnd(nextIndex)) {
                        idrStart
                    } else {
                        nextIndex
                    }
                    channel.send(byteBuffer)
                    // This will drift slightly over time
                    delay(frameMs)
                }
            }
            return channel
        }
    }

    class Renderer(private val surface: Surface):MediaCodec.Callback() {
        var averageDelayMs: Long = 0
            private set
        private val inputBuffers = Channel<Int>(Channel.UNLIMITED)
        private lateinit var mediaCodec: MediaCodec
        private val frameUs = TimeUnit.SECONDS.toMicros(1) / 30
        private val deque = ArrayDeque<Long>()

        suspend fun start(sps:ByteBuffer, pps:ByteBuffer, imageChannel: ReceiveChannel<ByteBuffer>) {
            val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)

            mediaFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
            mediaFormat.setByteBuffer("csd-0", sps)
            mediaFormat.setByteBuffer("csd-1", pps)

            val mediaCodecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val decoderName = mediaCodecList.findDecoderForFormat(mediaFormat)

            mediaCodec = MediaCodec.createByCodecName(decoderName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaCodec.CONFIGURE_FLAG_USE_BLOCK_MODEL else 0
            mediaCodec.setCallback(this)
            mediaCodec.configure(mediaFormat, surface, null, flags)
            mediaCodec.start()

            if (flags == MediaCodec.CONFIGURE_FLAG_USE_BLOCK_MODEL) {
                loop30(imageChannel, arrayOf(decoderName))
            } else {
                loop21(imageChannel)
            }
        }

        private fun getFlags(imageBuffer:ByteBuffer):Int {
            return when (NalUnitFinder.getNalType(imageBuffer.get(4))) {
                NAL_TYPE_IDR -> BUFFER_FLAG_KEY_FRAME
                NAL_TYPE_SPS -> BUFFER_FLAG_CODEC_CONFIG
                else -> 0
            }
        }

        /**
         * Check for input buffer starvation
         */
        private suspend fun getInputBufferIndex():Int {
            val result = inputBuffers.tryReceive()
            if (result.isSuccess) {
                return result.getOrThrow()
            } else {
                val start = SystemClock.uptimeMillis()
                val index = inputBuffers.receive()
                Log.w(TAG, "Input starvation: ms=${SystemClock.uptimeMillis() - start}")
                return index
            }
        }

        private suspend fun loop21(imageChannel: ReceiveChannel<ByteBuffer>) {
            var frameTimeUs = 0L
            while (true) {
                val imageBuffer = imageChannel.receive()
                val now = SystemClock.uptimeMillis()
                val inputIndex = getInputBufferIndex()
                val wait = SystemClock.uptimeMillis() - now
                if (wait > 1) {
                    Log.w(TAG, "Waited: $wait")
                }
                mediaCodec.getInputBuffer(inputIndex)?.let { codecBuffer ->
                    codecBuffer.put(imageBuffer)
                    val flags = getFlags(imageBuffer)
                    mediaCodec.queueInputBuffer(
                        inputIndex,
                        0,
                        imageBuffer.position(),
                        frameTimeUs,
                        flags
                    )
                    if (flags == BUFFER_FLAG_KEY_FRAME) {
                        frameTimeUs = System.nanoTime() / 1000
                    } else {
                        frameTimeUs += frameUs
                    }
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private suspend fun loop30(imageChannel: ReceiveChannel<ByteBuffer>, codecArray:Array<String>) {
            while (true) {
                val imageBuffer = imageChannel.receive()
                val inputIndex = getInputBufferIndex()
                val request = mediaCodec.getQueueRequest(inputIndex)
                LinearBlock.obtain(imageBuffer.capacity(),codecArray)?.let { linearBlock ->
                    val flags = getFlags(imageBuffer)
                    val codecBuffer = linearBlock.map()
                    codecBuffer.put(imageBuffer)
                    request.setLinearBlock(linearBlock, 0, imageBuffer.capacity())
                    request.setPresentationTimeUs(System.nanoTime() / 1000)
                    request.setFlags(flags)
                    request.queue()
                }
            }
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            inputBuffers.trySend(index)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            val delayMs = (System.nanoTime() / 1000L - info.presentationTimeUs) / 1000
            deque.addLast(delayMs)
            if (deque.size > 16) {
                deque.removeFirst()
            }
            var total = 0L
            for (dMs in deque) {
                total += dMs
            }
            averageDelayMs = total / deque.size
            Log.d(TAG, "onOutputBufferAvailable() delayMs=${delayMs}, avgDelayMs=${averageDelayMs} flags=${info.flags}")
            codec.releaseOutputBuffer(index, true)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "onError()",e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.d(TAG, "onOutputFormatChanged: $format")
        }

        fun stop() {
            mediaCodec.release()
        }
    }
    companion object {
        const val TAG = "H264Tester"
        const val WIDTH = 640
        const val HEIGHT = 480
    }
}
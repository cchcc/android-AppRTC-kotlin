package cchcc.apprtc

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame

class MediaPlayerCapturer(private val mediaPlayer: MediaPlayer) : VideoCapturer {
    private lateinit var applicationContext: Context
    private lateinit var surfaceTextureHelper: SurfaceTextureHelper
    private lateinit var capturerObserver: VideoCapturer.CapturerObserver

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: VideoCapturer.CapturerObserver
    ) {
        this.applicationContext = applicationContext
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        Log.d("capturer", "startCapture")

        // set surface
        surfaceTextureHelper.surfaceTexture.setDefaultBufferSize(width, height)
        val surface = Surface(surfaceTextureHelper.surfaceTexture)
        mediaPlayer.setSurface(surface)
        mediaPlayer.isLooping = true
        mediaPlayer.start()

        capturerObserver.onCapturerStarted(true)
        surfaceTextureHelper.startListening { oesTextureId, transformMatrix, timestampNs ->
            val buffer = surfaceTextureHelper.createTextureBuffer(
                mediaPlayer.videoWidth, mediaPlayer.videoHeight
                , RendererCommon.convertMatrixToAndroidGraphicsMatrix(transformMatrix)
            )
            val frame = VideoFrame(buffer,0, timestampNs)
            capturerObserver.onFrameCaptured(frame)
            frame.release()

        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        Log.d("capturer", "changeCaptureFormat")
        mediaPlayer.stop()

        // set surface
        surfaceTextureHelper.surfaceTexture.setDefaultBufferSize(width, height)
        val surface = Surface(surfaceTextureHelper.surfaceTexture)

        mediaPlayer.setSurface(surface)
        mediaPlayer.start()
    }

    override fun stopCapture() {
        Log.d("capturer", "stopCapture")
        CoroutineScope(Dispatchers.Main).launch {
            surfaceTextureHelper.stopListening()
            capturerObserver.onCapturerStopped()

            mediaPlayer.stop()
        }
    }



    override fun dispose() {
        mediaPlayer.release()
    }

    override fun isScreencast(): Boolean = false
}
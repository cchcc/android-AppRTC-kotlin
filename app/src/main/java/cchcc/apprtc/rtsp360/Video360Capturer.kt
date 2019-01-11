package cchcc.apprtc.rtsp360

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame

class Video360Capturer(val uri: Uri) : VideoCapturer {
    private lateinit var applicationContext: Context
    private lateinit var surfaceTextureHelper: SurfaceTextureHelper
    private lateinit var capturerObserver: VideoCapturer.CapturerObserver

    private lateinit var mediaPlayer: MediaPlayer

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

        // set surface
        mediaPlayer = MediaPlayer.create(applicationContext, uri)
        surfaceTextureHelper.surfaceTexture.setDefaultBufferSize(width, height)
        val surface = Surface(surfaceTextureHelper.surfaceTexture)

        mediaPlayer.setSurface(surface)
        mediaPlayer.isLooping = true
        mediaPlayer.start()

        capturerObserver.onCapturerStarted(true)
        surfaceTextureHelper.startListening { oesTextureId, transformMatrix, timestampNs ->
            val buffer = surfaceTextureHelper.createTextureBuffer(
                width, height
                , RendererCommon.convertMatrixToAndroidGraphicsMatrix(transformMatrix)
            )
            val frame = VideoFrame(buffer,0, timestampNs)
            capturerObserver.onFrameCaptured(frame)
            frame.release()

        }
    }

    override fun stopCapture() {
        CoroutineScope(Dispatchers.Main).launch {
            surfaceTextureHelper.stopListening()
            capturerObserver.onCapturerStopped()

            mediaPlayer.stop()
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {

        mediaPlayer.stop()

        // set surface
        surfaceTextureHelper.surfaceTexture.setDefaultBufferSize(width, height)
        val surface = Surface(surfaceTextureHelper.surfaceTexture)

        mediaPlayer.setSurface(surface)
        mediaPlayer.start()
    }

    override fun dispose() {
        mediaPlayer.release()
    }

    override fun isScreencast(): Boolean = false
}
package cchcc.apprtc

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class AppRTC(
    val context: Context
    , val svr_video_full: SurfaceViewRenderer
    , val svr_video_pip: SurfaceViewRenderer
) {
    val rootEglBase = EglBase.create()

    class ProxyVideoSink : VideoSink {
        var target: VideoSink? = null
        override fun onFrame(frame: VideoFrame) {
            target?.onFrame(frame)
        }
    }

    private val localProxyVideoSink by lazy(LazyThreadSafetyMode.NONE) { ProxyVideoSink() }

    class ProxyRenderer : VideoRenderer.Callbacks {
        var target: VideoRenderer.Callbacks? = null
        override fun renderFrame(frame: VideoRenderer.I420Frame) {
            if (target == null)
                VideoRenderer.renderFrameDone(frame)

            target?.renderFrame(frame)
        }
    }

    private val remoteProxyRenderer by lazy(LazyThreadSafetyMode.NONE) { ProxyRenderer() }
    private val audioManager: AudioManager by lazy(LazyThreadSafetyMode.NONE) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var savedAudioMode = AudioManager.MODE_INVALID

    private val okHttpClient by lazy(LazyThreadSafetyMode.NONE) { OkHttpClient() }

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private var videoCapturer: VideoCapturer? = null

    private var disconnect: (() -> Unit)? = null

    fun start(roomName: String, rtspUri: Uri) {
        svr_video_full.init(rootEglBase.eglBaseContext, null)
        svr_video_full.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        svr_video_full.setEnableHardwareScaler(false)
        svr_video_full.setMirror(true)

        svr_video_pip.init(rootEglBase.eglBaseContext, null)
        svr_video_pip.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        svr_video_pip.setZOrderMediaOverlay(true)
        svr_video_pip.setEnableHardwareScaler(true)
        svr_video_pip.setMirror(false)

        setSwappedFeeds(true)


        AudioDeviceModule.setBlacklistDeviceForOpenSLESUsage(false)
        AudioDeviceModule.setWebRtcBasedAcousticEchoCanceler(false)
        AudioDeviceModule.setWebRtcBasedNoiseSuppressor(false)
        AudioDeviceModule.setErrorCallback(object : AudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                Log.d(LOG_TAG, "AudioDeviceModule: onWebRtcAudioRecordInitError: $errorMessage")
            }

            override fun onWebRtcAudioRecordStartError(
                errorCode: AudioDeviceModule.AudioRecordStartErrorCode?,
                errorMessage: String?
            ) {
                Log.d(LOG_TAG, "AudioDeviceModule: onWebRtcAudioRecordStartError: $errorCode,$errorMessage")
            }

            override fun onWebRtcAudioRecordError(errorMessage: String?) {
                Log.d(LOG_TAG, "AudioDeviceModule: onWebRtcAudioRecordError: $errorMessage")
            }
        })
        AudioDeviceModule.setErrorCallback(object : AudioDeviceModule.AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                Log.d(LOG_TAG, "AudioDeviceModule: onWebRtcAudioTrackInitError: $errorMessage")
            }

            override fun onWebRtcAudioTrackStartError(
                errorCode: AudioDeviceModule.AudioTrackStartErrorCode?,
                errorMessage: String?
            ) {
                Log.d(LOG_TAG, "AudioDeviceModule: onWebRtcAudioTrackStartError: $errorCode, $errorMessage")
            }

            override fun onWebRtcAudioTrackError(errorMessage: String?) {
                Log.d(LOG_TAG, "AudioDeviceModule: onWebRtcAudioTrackError: $errorMessage")
            }
        })

        savedAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("WebRTC-IntelVP8/Enabled/WebRTC-ExternalAndroidAudioDevice/Enabled/")
                .setEnableVideoHwAcceleration(true)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        Logging.enableLogToDebugOutput(Logging.Severity.LS_NONE) //

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, false)
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        @Suppress("DEPRECATION")
        val peerConnectionFactory = PeerConnectionFactory(options, encoderFactory, decoderFactory)

        ioScope.launch {
            val url = urlJoin(roomName)
            val request_join = Request.Builder().url(url).post(RequestBody.create(null, "")).build()
            Log.d(LOG_TAG, "request:\n$url")
            val response_join = okHttpClient.newCall(request_join).execute()

            if (!response_join.isSuccessful) {
                Log.d(LOG_TAG, "request join failed ${response_join.code()}:${response_join.body()?.string()}")
                return@launch
            }

            val responseJson = JSONObject(response_join.body()!!.string())
            Log.d(LOG_TAG, "response join:\n$responseJson")
            val result = responseJson.getString("result")
            if (result != "SUCCESS") {
                Log.d(LOG_TAG, "response join result failed: $url")
                return@launch
            }

            val params = responseJson.getString("params")
            val roomJson = JSONObject(params)
            val roomId = roomJson.getString("room_id")
            val clientId = roomJson.getString("client_id")
            val wssUrl = roomJson.getString("wss_url")
            val wssPostUrl = roomJson.getString("wss_post_url") // for channel
            val initiator = roomJson.getBoolean("is_initiator")
            val iceCandidates = mutableListOf<IceCandidate>()
            var offerSdp: SessionDescription? = null
            if (!initiator) {
                val messagesString = roomJson.getString("messages")
                val messages = JSONArray(messagesString)
                (0..(messages.length() - 1)).forEach {
                    val message = messages.getString(it)
                    val messageJson = JSONObject(message)
                    val type = messageJson.getString("type")
                    if (type == "offer") {
                        offerSdp = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type)
                            , messageJson.getString("sdp")
                        )
                    } else if (type == "candidate") {
                        iceCandidates.add(
                            IceCandidate(
                                messageJson.getString("id")
                                , messageJson.getInt("label")
                                , messageJson.getString("candidate")
                            )
                        )
                    }

                }
            }

            val iceServers = mutableListOf<PeerConnection.IceServer>()
            val pc_configJson = JSONObject(roomJson.getString("pc_config"))
            val iceServersJson = pc_configJson.getJSONArray("iceServers")
            (0..(iceServersJson.length() - 1)).forEach {
                val serverJson = iceServersJson.getJSONObject(it)
                val urls = serverJson.getString("urls")
                val credential = serverJson.optString("credential")
                val iceServer = PeerConnection.IceServer.builder(urls)
                    .setPassword(credential)
                    .createIceServer()
                iceServers.add(iceServer)
            }

            val isTurnPresent = iceServers.any { it.urls.any { url -> url.startsWith("turn:") } }

            val ice_server_url = roomJson.optString("ice_server_url")
            if (isTurnPresent && ice_server_url.isNotEmpty()) {
                val request_ice_server = Request.Builder()
                    .url(ice_server_url)
                    .addHeader("REFERER", "https://appr.tc")
                    .post(RequestBody.create(null, ""))
                    .build()

                Log.d(LOG_TAG, "request:\n$ice_server_url")
                val response_ice_server = okHttpClient.newCall(request_ice_server).execute()
                if (!response_ice_server.isSuccessful) {
                    Log.d(
                        LOG_TAG,
                        "request ice server failed: ${response_ice_server.code()}\n${response_ice_server.body()?.string()}"
                    )
                    return@launch
                }

                val response_ice_serverJson = JSONObject(response_ice_server.body()!!.string())
                val turnServers = response_ice_serverJson.getJSONArray("iceServers")
                (0..(turnServers.length() - 1)).forEach {
                    val server = turnServers.getJSONObject(it)
                    val turnUrls = server.getJSONArray("urls")
                    val username = server.optString("username")
                    val credential = server.optString("credential")

                    (0..(turnUrls.length() - 1)).forEach { idxUrl ->
                        val turnUrl = turnUrls.getString(idxUrl)

                        val turnServer = PeerConnection.IceServer.builder(turnUrl)
                            .setUsername(username)
                            .setPassword(credential)
                            .createIceServer()

                        iceServers.add(turnServer)
                    }

                }

            }

//            videoCapturer = mainScope.async {
//                Camera1Enumerator(false).let videoCapturer@{
//                    for (deviceName in it.deviceNames) {
//                        if (it.isFrontFacing(deviceName)) {
//                            val videoCapturer = it.createCapturer(deviceName, null)
//                            if (videoCapturer != null) {
//                                return@videoCapturer videoCapturer
//                            }
//                        }
//                    }
//
//                    for (deviceName in it.deviceNames) {
//                        if (!it.isFrontFacing(deviceName)) {
//                            val videoCapturer = it.createCapturer(deviceName, null)
//                            if (videoCapturer != null) {
//                                return@videoCapturer videoCapturer
//                            }
//                        }
//                    }
//
//                    throw Exception("failed to create CameraVideoCapturer")
//                }
//            }.await()

            videoCapturer = mainScope.async {
                val mediaPlayer = MediaPlayer.create(svr_video_full.context, rtspUri)
                MediaPlayerCapturer(mediaPlayer)
            }.await()


            peerConnectionFactory.setVideoHwAccelerationOptions(rootEglBase.eglBaseContext, rootEglBase.eglBaseContext)
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                keyType = PeerConnection.KeyType.ECDSA
                enableDtlsSrtp = true
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            lateinit var webSocket: WebSocket
            lateinit var peerConnection: PeerConnection

            peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    Log.d(LOG_TAG, "PeerConnection.Observer onIceConnectionChange")
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            setSwappedFeeds(false)
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            mainScope.launch {
                                disconnect?.invoke()
                                disconnect = null
                            }
                        }
                        else -> {

                        }

                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(LOG_TAG, "PeerConnection.Observer onIceCandidate")
                    val json = JSONObject().apply {
                        put("type", "candidate")
                        put("label", candidate.sdpMLineIndex)
                        put("id", candidate.sdpMid)
                        put("candidate", candidate.sdp)
                    }

                    if (initiator) {
                        val requestBody =
                            RequestBody.create(MediaType.get("text/plain; charset=utf-8"), json.toString())
                        val request = Request.Builder().url(
                            urlMessage(
                                roomId,
                                clientId,
                                ""
                            )
                        )
                            .post(requestBody).build()
                        val response = okHttpClient.newCall(request).execute()
                        Log.d(LOG_TAG, "sendLocalIceCandidate response:\n${response.body()?.string()}")
                    } else {
                        webSocket.send(json.toString())
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                    Log.d(LOG_TAG, "PeerConnection.Observer onIceCandidatesRemoved")
                    val json = JSONObject().apply {
                        put("type", "remove-candidates")
                        val jsonArray = JSONArray()
                        for (candidate in candidates) {
                            jsonArray.put(candidate.toJson())
                        }
                        put("candidates", jsonArray)
                    }

                    if (initiator) {
                        val requestBody =
                            RequestBody.create(MediaType.get("text/plain; charset=utf-8"), json.toString())
                        val request = Request.Builder().url(
                            urlMessage(
                                roomId,
                                clientId,
                                ""
                            )
                        )
                            .post(requestBody).build()
                        val response = okHttpClient.newCall(request).execute()
                        Log.d(LOG_TAG, "sendLocalIceCandidate response:\n${response.body()?.string()}")
                    } else {
                        webSocket.send(json.toString())
                    }
                }

                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(dataChannel: DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
            })!!

            val videoSource = peerConnectionFactory.createVideoSource(videoCapturer)
            videoCapturer!!.startCapture(0, 0, 0)
            val localVideoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", videoSource).apply {
                setEnabled(true)
                addSink(localProxyVideoSink)
            }

            peerConnection.addTrack(localVideoTrack, listOf("ARDAMS"))
            val remoteVideoTrack: VideoTrack = peerConnection.transceivers.asSequence()
                .map { it.receiver.track() }
                .first { it is VideoTrack } as VideoTrack

            remoteVideoTrack.setEnabled(true)
            remoteVideoTrack.addRenderer(VideoRenderer(remoteProxyRenderer))

            val audioConstraints = MediaConstraints()
            val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
            val localAudioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource).apply {
                setEnabled(true)
            }
            peerConnection.addTrack(localAudioTrack, listOf("ARDAMS"))

            val sdpMediaConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }

            var localSdp: SessionDescription? = null
            val sdpObserver = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    Log.d(LOG_TAG, "sdpObserver onCreateSuccess ${sdp.type}")

                    if (localSdp != null)
                        return
                    val sd = preferCodec(sdp.description, "H264", false)
                    localSdp = SessionDescription(sdp.type, sd)
                    peerConnection.setLocalDescription(this, localSdp)
                }

                override fun onSetSuccess() {
                    Log.d(LOG_TAG, "sdpObserver onSetSuccess")
                    if (initiator) {
                        if (peerConnection.remoteDescription == null) {

                            // sendOfferSdp
                            val json = JSONObject().apply {
                                put("sdp", localSdp!!.description)
                                put("type", "offer")
                            }

                            val requestBody =
                                RequestBody.create(MediaType.get("text/plain; charset=utf-8"), json.toString())
                            val request = Request.Builder().url(
                                urlMessage(
                                    roomId,
                                    clientId,
                                    ""
                                )
                            )
                                .post(requestBody).build()
                            val response = okHttpClient.newCall(request).execute()
                            Log.d(LOG_TAG, "sendOfferSdp ${response.body()?.string()}")

                        } else {
                            iceCandidates.forEach {
                                peerConnection.addIceCandidate(it)
                            }
                        }
                    } else {
                        if (peerConnection.localDescription != null) {
//                            sendAnswerSdp
                            val json = JSONObject().apply {
                                val msg = JSONObject().apply {
                                    put("sdp", localSdp!!.description)
                                    put("type", "answer")
                                }
                                put("cmd", "send")
                                put("msg", msg.toString())
                            }

                            webSocket.send(json.toString())
                            Log.d(LOG_TAG, "sendAnswerSdp $json")
                        }
                    }
                }

                override fun onCreateFailure(error: String) {
                    Log.d(LOG_TAG, "SdpObserver onCreateFailure $error")
                }

                override fun onSetFailure(error: String) {
                    Log.d(LOG_TAG, "SdpObserver onSetFailure $error")
                }
            }

            disconnect = {
                remoteProxyRenderer.target = null
                localProxyVideoSink.target = null

                ioScope.launch {
                    val request_leave = Request.Builder().url(
                        urlLeave(
                            roomId,
                            clientId,
                            ""
                        )
                    )
                        .post(RequestBody.create(null, "")).build()
                    val response_leave = okHttpClient.newCall(request_leave).execute()
                    Log.d(LOG_TAG, "leave response:\n${response_leave.body()?.string()}")

                    val json = JSONObject().apply {
                        put("cmd", "send")
                        put("msg", "{\"type\": \"bye\"}")
                    }
                    webSocket.send(json.toString())
                    webSocket.close(1000, null)

                    val request_delete = Request.Builder().url("$wssPostUrl/$roomId/$clientId")
                        .delete().build()
                    val response_delete = okHttpClient.newCall(request_delete).execute()
                    Log.d(LOG_TAG, "delete response:\n${response_delete.body()?.string()}")

                }

                svr_video_pip.release()
                svr_video_full.release()
                audioManager.mode = savedAudioMode

                peerConnection.dispose()
                audioSource.dispose()
                videoCapturer?.stopCapture()
                videoCapturer = null
                videoSource.dispose()
                peerConnectionFactory.dispose()
                rootEglBase.release()
                PeerConnectionFactory.stopInternalTracingCapture()
                PeerConnectionFactory.shutdownInternalTracer()
            }

            if (initiator) {
                peerConnection.createOffer(sdpObserver, sdpMediaConstraints)
            }


            val request_signal = Request.Builder().url(wssUrl).header("Origin", wssUrl).build()
            val okWSHttpClient = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            webSocket = okWSHttpClient.newWebSocket(request_signal, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(LOG_TAG, "webSocket onOpen")


                    val json = JSONObject().apply {
                        put("cmd", "register")
                        put("roomid", roomId)
                        put("clientid", clientId)
                    }
                    webSocket.send(json.toString())

                    if (!initiator) {
                        val sd = preferCodec(offerSdp!!.description, "VP8", false)
                        peerConnection.setRemoteDescription(sdpObserver, SessionDescription(offerSdp!!.type, sd))
                        peerConnection.createAnswer(sdpObserver, sdpMediaConstraints)

                        iceCandidates.forEach {
                            peerConnection.addIceCandidate(it)
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.d(LOG_TAG, "websock onFailure\n$t\n${response?.body()?.string()}")
                    t.printStackTrace()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(LOG_TAG, "websock onClosing\n$code\n$reason")
                    webSocket.close(1000, null)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(LOG_TAG, "websock onMessage $text")

                    val json = JSONObject(text)
                    val msgText = json.optString("msg")
                    val errorText = json.optString("error")

                    if (msgText.isNotEmpty()) {
                        val msgJson = JSONObject(msgText)
                        val type = msgJson.optString("type")
                        when (type) {
                            "candidate" -> {
                                peerConnection.addIceCandidate(
                                    IceCandidate(
                                        msgJson.getString("id"),
                                        msgJson.getInt("label"),
                                        msgJson.getString("candidate")
                                    )
                                )
                            }
                            "remove-candidates" -> {
                                val candidates = msgJson.getJSONArray("candidates")
                                val arr = Array(candidates.length()) {
                                    val c = candidates.getJSONObject(it)
                                    IceCandidate(
                                        c.getString("id"),
                                        c.getInt("label"),
                                        c.getString("candidate")
                                    )
                                }

                                peerConnection.removeIceCandidates(arr)
                            }
                            "answer" -> {
                                if (initiator) {
                                    val sdp = SessionDescription(
                                        SessionDescription.Type.fromCanonicalForm(type), msgJson.getString("sdp")
                                    )
                                    peerConnection.setRemoteDescription(sdpObserver, sdp)
                                }
                            }
                            "offer" -> {
                                if (!initiator) {
                                    val sdp = SessionDescription(
                                        SessionDescription.Type.fromCanonicalForm(type), msgJson.getString("sdp")
                                    )
                                    peerConnection.setRemoteDescription(sdpObserver, sdp)
                                    peerConnection.createAnswer(sdpObserver, sdpMediaConstraints)
                                }
                            }
                            "bye" -> {
                                mainScope.launch {
                                    disconnect?.invoke()
                                    disconnect = null
                                }
                            }
                        }

                    }

                    if (errorText.isNotEmpty()) {
                        Log.d(LOG_TAG, "websock onMessage: error $errorText")
                    }

                }

//                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
//                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(LOG_TAG, "websock onClosed\n$code\n$reason")
                }
            })
            okWSHttpClient.dispatcher().executorService().shutdown()

        }
    }

    fun end() {
        disconnect?.invoke()
        disconnect = null
    }

    // android lifecycle interface
    fun onStart() {
        videoCapturer?.startCapture(0, 0, 0)
    }

    // android lifecycle interface
    fun onStop() {
        videoCapturer?.stopCapture()
    }


    private fun setSwappedFeeds(isSwappedFeeds: Boolean) {
        localProxyVideoSink.target = if (isSwappedFeeds) svr_video_full else svr_video_pip
        remoteProxyRenderer.target = if (isSwappedFeeds) svr_video_pip else svr_video_full
        svr_video_full.setMirror(isSwappedFeeds)
        svr_video_pip.setMirror(!isSwappedFeeds)
    }

    fun IceCandidate.toJson() = JSONObject().apply {
        put("label", sdpMLineIndex)
        put("id", sdpMid)
        put("candidate", sdp)
    }


    companion object {
        const val LOG_TAG = "AppRTC"

        fun urlJoin(roomId: String) = "https://appr.tc/join/$roomId"
        fun urlMessage(roomId: String, clientId: String, queryString: String)
                : String =
            "https://appr.tc/message/$roomId/$clientId${if (queryString.isEmpty()) "" else "?$queryString"}"

        fun urlLeave(roomId: String, clientId: String, queryString: String)
                : String =
            "https://appr.tc/leave/$roomId/$clientId${if (queryString.isEmpty()) "" else "?$queryString"}"

        private fun preferCodec(sdpDescription: String, codec: String, isAudio: Boolean): String {
            fun joinString(
                s: Iterable<CharSequence>, delimiter: String, delimiterAtEnd: Boolean
            ): String {
                val iter = s.iterator()
                if (!iter.hasNext()) {
                    return ""
                }
                val buffer = StringBuilder(iter.next())
                while (iter.hasNext()) {
                    buffer.append(delimiter).append(iter.next())
                }
                if (delimiterAtEnd) {
                    buffer.append(delimiter)
                }
                return buffer.toString()
            }

            fun findMediaDescriptionLine(isAudio: Boolean, sdpLines: Array<String>): Int {
                val mediaDescription = if (isAudio) "m=audio " else "m=video "
                for (i in sdpLines.indices) {
                    if (sdpLines[i].startsWith(mediaDescription)) {
                        return i
                    }
                }
                return -1
            }


            val lines = sdpDescription.split("\r\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val mLineIndex = findMediaDescriptionLine(isAudio, lines)
            if (mLineIndex == -1) {
                return sdpDescription
            }
            // A list with all the payload types with name |codec|. The payload types are integers in the
            // range 96-127, but they are stored as strings here.
            val codecPayloadTypes = ArrayList<String>()
            // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
            val codecPattern = Pattern.compile("^a=rtpmap:(\\d+) $codec(/\\d+)+[\r]?$")
            for (line in lines) {
                val codecMatcher = codecPattern.matcher(line)
                if (codecMatcher.matches()) {
                    codecPayloadTypes.add(codecMatcher.group(1))
                }
            }
            if (codecPayloadTypes.isEmpty()) {
                return sdpDescription
            }

            fun movePayloadTypesToFront(preferredPayloadTypes: List<String>, mLine: String): String? {
                // The format of the media description line should be: m=<media> <port> <proto> <fmt> ...
                val origLineParts =
                    Arrays.asList(*mLine.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())
                if (origLineParts.size <= 3) {
                    return null
                }
                val header = origLineParts.subList(0, 3)
                val unpreferredPayloadTypes = ArrayList(origLineParts.subList(3, origLineParts.size))
                unpreferredPayloadTypes.removeAll(preferredPayloadTypes)
                // Reconstruct the line with |preferredPayloadTypes| moved to the beginning of the payload
                // types.
                val newLineParts = ArrayList<String>()
                newLineParts.addAll(header)
                newLineParts.addAll(preferredPayloadTypes)
                newLineParts.addAll(unpreferredPayloadTypes)
                return joinString(newLineParts, " ", false /* delimiterAtEnd */)
            }


            val newMLine = movePayloadTypesToFront(codecPayloadTypes, lines[mLineIndex]) ?: return sdpDescription
            lines[mLineIndex] = newMLine
            return joinString(Arrays.asList(*lines), "\r\n", true /* delimiterAtEnd */)
        }
    }
}
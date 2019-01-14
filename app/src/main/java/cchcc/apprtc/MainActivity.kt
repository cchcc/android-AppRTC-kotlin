package cchcc.apprtc

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import org.webrtc.RendererCommon

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val requiredPermissions = arrayOf(
            android.Manifest.permission.CAMERA
            , android.Manifest.permission.RECORD_AUDIO
        )

        val notGrantedPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED
        }

        val isGranted = notGrantedPermissions.isEmpty()
        if (isGranted) {
            showInputRoomNameDialog()
        } else {
            ActivityCompat.requestPermissions(this, notGrantedPermissions.toTypedArray(), 1)
        }

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                showInputRoomNameDialog()
            } else {
                finish()
            }
        }
    }

    private val appRTC: AppRTC by lazy { AppRTC(this, svr_video_full, svr_video_pip) }

    private fun showInputRoomNameDialog() {
        val tv_roomNameDesc = TextView(this).apply {
            text = "5 or more characters and include only letters, numbers, underscore and hyphen"
        }
        val et_roomName = EditText(this).apply {
            hint = "room name"
        }

        val tv_rtspDesc = TextView(this).apply {
            text = "rtsp url"
        }
        val et_rtsp = EditText(this).apply {
            setText("rtsp://184.72.239.149/vod/mp4:BigBuckBunny_175k.mov")
        }

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 0, 50, 0)
            addView(tv_roomNameDesc)
            addView(et_roomName)
            addView(tv_rtspDesc)
            addView(et_rtsp)
        }

        AlertDialog.Builder(this)
            .setTitle("Please enter a room name.")
            .setView(view)
            .setPositiveButton("Join") { _, _ ->
                val roomName = et_roomName.text.toString()


                bt_end.setOnClickListener { appRTC!!.end() }

                val url = et_rtsp.text.toString()

                appRTC.start(roomName, Uri.parse(url))
            }
            .setNegativeButton("Finish") { _, _ -> finish() }
            .show()
    }

    override fun onStart() {
        super.onStart()
        appRTC?.onStart()
    }

    override fun onStop() {
        appRTC?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        appRTC?.end()
        super.onDestroy()
    }
}

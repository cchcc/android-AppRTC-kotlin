package cchcc.apprtc

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*

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
            }
            else {
                finish()
            }
        }
    }

    private var appRTC: AppRTC? = null

    private fun showInputRoomNameDialog() {
        val et_roomName = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Please enter a room name.\n5 or more characters and include only letters, numbers, underscore and hyphen")
            .setView(et_roomName)
            .setPositiveButton("Join") { _, _ ->
                val roomName = et_roomName.text.toString()

                appRTC = AppRTC(this, svr_video_full, svr_video_pip)
                bt_end.setOnClickListener { appRTC!!.end() }
                appRTC!!.start(roomName)
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

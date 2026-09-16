package com.mdkdw1.splayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberCapturePermissionLauncher(
    onGranted: (resultCode: Int, data: Intent) -> Unit,
    onDenied: () -> Unit
) = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
        onGranted(result.resultCode, result.data!!)
    } else {
        onDenied()
    }
}

fun buildCaptureIntent(context: Context): Intent {
    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
        as MediaProjectionManager
    return mpm.createScreenCaptureIntent()
}

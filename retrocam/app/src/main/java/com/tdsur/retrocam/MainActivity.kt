package com.tdsur.retrocam

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.tdsur.retrocam.ui.CameraScreen
import com.tdsur.retrocam.ui.EditorScreen
import com.tdsur.retrocam.ui.RetroCamTheme
import com.tdsur.retrocam.ui.StudioState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RetroCamTheme { RetroCamApp() }
        }
    }
}

@Composable
private fun RetroCamApp() {
    val state = remember { StudioState() }
    var editing by rememberSaveable { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) editing = uri
    }
    val openGallery = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val current = editing
    if (current == null) {
        CameraScreen(state = state, onOpenGallery = openGallery)
    } else {
        BackHandler { editing = null }
        EditorScreen(uri = current, state = state, onClose = { editing = null })
    }
}

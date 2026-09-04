package app.gridfix.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.gridfix.android.ui.GridFixApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything else, so a crash during startup is caught as well.
        CrashLog.install(this)
        enableEdgeToEdge()
        setContent {
            GridFixApp()
        }
    }
}

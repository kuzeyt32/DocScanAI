package com.docscanai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.docscanai.ui.navigation.AppNavHost
import com.docscanai.ui.theme.DocScanTheme

/** Single Activity hosting the whole Compose UI (ARCHITECTURE.md §1). */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DocScanTheme {
                AppNavHost()
            }
        }
    }
}

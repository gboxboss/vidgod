package com.vidgod.editor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vidgod.editor.ui.editor.EditorScreen
import com.vidgod.editor.ui.home.HomeScreen
import com.vidgod.editor.ui.home.HomeViewModel
import com.vidgod.editor.ui.theme.VG
import com.vidgod.editor.ui.theme.VidGodTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Project created from a "share to VidGod" intent, to be opened by the nav graph. */
    private val sharedProject = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VidGodTheme {
                AppNav(sharedProject)
            }
        }
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val vm = HomeViewModel(application)
            vm.create(uris, slideshow = false)?.let { sharedProject.value = it }
        }
    }
}

@Composable
private fun AppNav(sharedProject: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    LaunchedEffect(Unit) {
        sharedProject.collect { id ->
            if (id != null) {
                nav.navigate("editor/$id?action=") { launchSingleTop = true }
                sharedProject.value = null
            }
        }
    }
    NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize().background(VG.Bg)) {
        composable("home") {
            HomeScreen(openEditor = { id, action ->
                // Ignore a second tap while the first navigation is running.
                if (nav.currentDestination?.route == "home") {
                    nav.navigate("editor/$id?action=${action.orEmpty()}") { launchSingleTop = true }
                }
            })
        }
        composable(
            "editor/{id}?action={action}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("action") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val action = entry.arguments?.getString("action")?.ifBlank { null }
            // Only pop while this editor is on top, so a double tap on Close cannot pop Home too.
            EditorScreen(id, action, onBack = { if (nav.currentBackStackEntry == entry) nav.popBackStack() })
        }
    }
}

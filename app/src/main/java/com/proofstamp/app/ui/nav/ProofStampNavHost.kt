package com.proofstamp.app.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.ViewTimeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.proofstamp.app.R
import com.proofstamp.app.di.AppContainer
import com.proofstamp.app.ui.camera.CameraScreen
import com.proofstamp.app.ui.detail.PhotoDetailScreen
import com.proofstamp.app.ui.gallery.GalleryScreen
import com.proofstamp.app.ui.presets.PresetsScreen
import com.proofstamp.app.ui.sessions.SessionDetailScreen
import com.proofstamp.app.ui.sessions.SessionsScreen
import com.proofstamp.app.ui.settings.SettingsScreen
import com.proofstamp.app.ui.theme.PsColors
import com.proofstamp.app.ui.verify.VerifyScreen

object Routes {
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val SESSIONS = "sessions"
    const val VERIFY = "verify"
    const val SETTINGS = "settings"
    const val PRESETS = "presets"
    const val PHOTO = "photo/{id}"
    const val SESSION = "session/{id}"

    fun photo(id: String) = "photo/$id"
    fun session(id: String) = "session/$id"
}

private data class Tab(val route: String, val icon: ImageVector, val label: Int)

private val tabs = listOf(
    Tab(Routes.CAMERA, Icons.Outlined.CameraAlt, R.string.nav_camera),
    Tab(Routes.GALLERY, Icons.Outlined.Collections, R.string.nav_gallery),
    Tab(Routes.SESSIONS, Icons.Outlined.ViewTimeline, R.string.nav_sessions),
    Tab(Routes.VERIFY, Icons.Outlined.VerifiedUser, R.string.nav_verify),
    Tab(Routes.SETTINGS, Icons.Outlined.Settings, R.string.nav_settings),
)

@Composable
fun ProofStampNavHost(container: AppContainer, navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute != null && currentRoute != Routes.CAMERA && tabs.any { it.route == currentRoute }

    Scaffold(
        containerColor = PsColors.Bg,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = PsColors.Surface, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PsColors.OnAccent,
                                selectedTextColor = PsColors.Text,
                                indicatorColor = PsColors.Accent,
                                unselectedIconColor = PsColors.TextDim,
                                unselectedTextColor = PsColors.TextDim,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CAMERA,
            modifier = if (showBar) Modifier.padding(padding) else Modifier,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(Routes.CAMERA) {
                CameraScreen(
                    container = container,
                    onOpenGallery = { navController.navigateTab(Routes.GALLERY) },
                    onOpenSettings = { navController.navigateTab(Routes.SETTINGS) },
                    onOpenPhoto = { navController.navigate(Routes.photo(it)) },
                )
            }
            composable(Routes.GALLERY) {
                GalleryScreen(container = container, onOpenPhoto = { navController.navigate(Routes.photo(it)) })
            }
            composable(Routes.SESSIONS) {
                SessionsScreen(container = container, onOpenSession = { navController.navigate(Routes.session(it)) })
            }
            composable(Routes.VERIFY) {
                VerifyScreen(container = container, onOpenPhoto = { navController.navigate(Routes.photo(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(container = container, onOpenPresets = { navController.navigate(Routes.PRESETS) })
            }
            composable(Routes.PRESETS) {
                PresetsScreen(container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.PHOTO, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                val id = it.arguments?.getString("id").orEmpty()
                PhotoDetailScreen(container = container, photoId = id, onBack = { navController.popBackStack() })
            }
            composable(Routes.SESSION, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                val id = it.arguments?.getString("id").orEmpty()
                SessionDetailScreen(
                    container = container,
                    sessionId = id,
                    onBack = { navController.popBackStack() },
                    onOpenPhoto = { navController.navigate(Routes.photo(it)) },
                )
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

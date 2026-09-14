package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.trip.TripScreen
import com.example.ui.theme.CaravanTheme
import com.example.ui.viewmodel.CaravanViewModel
import org.maplibre.android.MapLibre

object CaravanRoutes {
    const val HOME = "home"
    const val TRIP = "trip"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            MapLibre.getInstance(this)
        } catch (e: Exception) {
            android.util.Log.e("Caravan", "Failed to initialize MapLibre", e)
        }
        enableEdgeToEdge()

        setContent {
            val viewModel: CaravanViewModel = viewModel()
            val isDarkMode by viewModel.isDarkMode.collectAsState()

            CaravanTheme(darkTheme = isDarkMode) {
                val view = LocalView.current
                val backgroundColor = MaterialTheme.colorScheme.background
                SideEffect {
                    val window = (view.context as ComponentActivity).window
                    window.statusBarColor = backgroundColor.toArgb()
                    WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !isDarkMode
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CaravanApp(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun CaravanApp(viewModel: CaravanViewModel = viewModel()) {
    val navController = rememberNavController()

    // Request permissions on start
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permission result only — GPS overlay stays off until the user taps the GPS button.
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    NavHost(
        navController = navController,
        startDestination = CaravanRoutes.HOME
    ) {
        composable(CaravanRoutes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToTrip = {
                    navController.navigate(CaravanRoutes.TRIP)
                },
                onNavigateToSettings = {
                    navController.navigate(CaravanRoutes.SETTINGS)
                }
            )
        }

        composable(CaravanRoutes.TRIP) {
            TripScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToSettings = {
                    navController.navigate(CaravanRoutes.SETTINGS)
                }
            )
        }

        composable(CaravanRoutes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.camera.CameraScreen
import com.example.ui.editor.EditorScreen
import com.example.ui.editor.EditorViewModel
import com.example.ui.gallery.GalleryScreen
import com.example.ui.gallery.GalleryViewModel
import com.example.ui.theme.MyApplicationTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          AppNavigation()
        }
      }
    }
  }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = "camera") {
        composable("camera") {
            CameraScreen(
                onImageCaptured = { uriStr ->
                    val encoded = URLEncoder.encode(uriStr, StandardCharsets.UTF_8.toString())
                    navController.navigate("editor/$encoded")
                },
                onNavigateToGallery = {
                    navController.navigate("gallery")
                }
            )
        }
        
        composable(
            route = "editor/{imageUri}",
            arguments = listOf(navArgument("imageUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val imageUri = backStackEntry.arguments?.getString("imageUri") ?: ""
            val context = androidx.compose.ui.platform.LocalContext.current
            val application = context.applicationContext as ProductApplication
            val repository = application.repository
            
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return EditorViewModel(repository, application) as T
                }
            }
            val viewModel: EditorViewModel = viewModel(factory = factory)
            
            EditorScreen(
                imageUriStr = imageUri,
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToGallery = { 
                    navController.navigate("gallery") {
                        popUpTo("camera") { inclusive = false }
                    }
                }
            )
        }
        
        composable("gallery") {
            val context = androidx.compose.ui.platform.LocalContext.current
            val application = context.applicationContext as ProductApplication
            val repository = application.repository
            
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return GalleryViewModel(repository) as T
                }
            }
            val viewModel: GalleryViewModel = viewModel(factory = factory)
            
            GalleryScreen(
                viewModel = viewModel,
                onNavigateBack = { 
                    if (!navController.popBackStack()) {
                        navController.navigate("camera") {
                            popUpTo(0)
                        }
                    }
                },
                onNavigateToCamera = {
                    navController.navigate("camera") {
                        popUpTo(0)
                    }
                },
                onNavigateToEditor = { uriStr ->
                    val encoded = URLEncoder.encode(uriStr, StandardCharsets.UTF_8.toString())
                    navController.navigate("editor/$encoded")
                }
            )
        }
    }
}

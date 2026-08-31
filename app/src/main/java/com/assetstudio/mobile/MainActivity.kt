package com.assetstudio.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.assetstudio.mobile.ui.browse.HomeScreen
import com.assetstudio.mobile.ui.detail.AssetDetailScreen
import com.assetstudio.mobile.ui.list.AssetListScreen
import com.assetstudio.mobile.ui.theme.AssetStudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AssetStudioTheme {
                AssetStudioApp()
            }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val LIST = "list"
    const val DETAIL = "detail/{assetId}"
    fun detail(assetId: String) = "detail/${android.net.Uri.encode(assetId)}"
}

@Composable
fun AssetStudioApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onOpenList = { navController.navigate(Routes.LIST) }
            )
        }
        composable(Routes.LIST) {
            AssetListScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenAsset = { assetId -> navController.navigate(Routes.detail(assetId)) }
            )
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("assetId") { type = NavType.StringType })
        ) { entry ->
            val assetId = entry.arguments?.getString("assetId") ?: return@composable
            AssetDetailScreen(
                viewModel = viewModel,
                assetId = assetId,
                onBack = { navController.popBackStack() },
                // 详情页内点击关联资产（如 SkinnedMeshRenderer 引用的 Mesh）跳转
                onOpenAsset = { targetId -> navController.navigate(Routes.detail(targetId)) }
            )
        }
    }
}

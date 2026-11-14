package com.fairystream.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            FairyTheme {
                val vm: FairyViewModel = viewModel()
                BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }

                AnimatedContent(
                    targetState = vm.screen,
                    transitionSpec = {
                        (slideInHorizontally(tween(320)) { it / 5 } + fadeIn(tween(320))) togetherWith (slideOutHorizontally(tween(320)) { -it / 5 } + fadeOut(tween(220)))
                    },
                    contentKey = { screen -> screenKey(screen) },
                    label = "screen"
                ) { screen ->
                    when (screen) {
                        Screen.Home -> HomeScreen(
                            series = vm.series,
                            favorites = vm.favorites,
                            history = vm.history,
                            loading = vm.homeLoading,
                            error = vm.homeError,
                            onOpen = vm::openSeries,
                            onToggleFavorite = vm::toggleFavorite,
                            onRefresh = vm::loadHome
                        )
                        is Screen.Detail -> DetailScreen(
                            series = screen.series,
                            episodes = vm.episodes,
                            loading = vm.detailLoading,
                            error = vm.detailError,
                            resumeId = vm.history.firstOrNull { it.series.slug == screen.series.slug }?.watchId,
                            favorite = screen.series.slug in vm.favorites,
                            onBack = vm::back,
                            onToggleFavorite = { vm.toggleFavorite(screen.series.slug) },
                            onPlay = vm::play,
                            onRetry = { vm.openSeries(screen.series) }
                        )
                        is Screen.Watch -> PlayerScreen(
                            series = screen.series,
                            episodes = vm.episodes,
                            index = screen.index,
                            onBack = vm::back,
                            onEpisode = vm::play
                        )
                    }
                }
            }
        }
    }
}

private fun screenKey(screen: Screen): String = when (screen) {
    Screen.Home -> "home"
    is Screen.Detail -> "detail:${screen.series.slug}"
    is Screen.Watch -> "watch:${screen.series.slug}"
}

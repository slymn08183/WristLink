package com.wristlink.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.wristlink.ui.health.EditFieldScreen
import com.wristlink.ui.health.HealthHubScreen
import com.wristlink.ui.health.HeartScreen
import com.wristlink.ui.health.ProfileScreen
import com.wristlink.ui.health.SleepScreen
import com.wristlink.ui.health.TodayScreen
import com.wristlink.ui.theme.WristLinkTheme

/**
 * Fifth launcher icon, "Health": WristLink's answer to Samsung Health on a watch that has no
 * Samsung phone. Tiles, complications and alerts open it straight at their screen
 * ([EXTRA_SCREEN]).
 */
class HealthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_HUB = "hub"
        const val SCREEN_TODAY = "today"
        const val SCREEN_HEART = "heart"
        const val SCREEN_SLEEP = "sleep"
        const val SCREEN_PROFILE = "profile"

        fun intent(context: Context, screen: String): Intent =
            Intent(context, HealthActivity::class.java)
                .putExtra(EXTRA_SCREEN, screen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra(EXTRA_SCREEN) ?: SCREEN_HUB

        setContent {
            WristLinkTheme {
                val nav = rememberSwipeDismissableNavController()
                SwipeDismissableNavHost(navController = nav, startDestination = SCREEN_HUB) {
                    composable(SCREEN_HUB) {
                        HealthHubScreen(
                            onOpen = { nav.navigate(it) },
                            onWorkout = { openWorkout() }
                        )
                    }
                    composable(SCREEN_TODAY) { TodayScreen() }
                    composable(SCREEN_HEART) { HeartScreen() }
                    composable(SCREEN_SLEEP) { SleepScreen() }
                    composable(SCREEN_PROFILE) { ProfileScreen(onEdit = { nav.navigate("edit/$it") }) }
                    composable("edit/{field}") { entry ->
                        EditFieldScreen(
                            field = entry.arguments?.getString("field").orEmpty(),
                            onDone = { nav.popBackStack() }
                        )
                    }
                }
                // Opened from a tile or notification: land on that screen, Back goes to the hub
                if (start != SCREEN_HUB) {
                    androidx.compose.runtime.LaunchedEffect(Unit) { nav.navigate(start) }
                }
            }
        }
    }

    /** The workout app is its own launcher entry; open it if this build has it. */
    private fun openWorkout() {
        runCatching {
            startActivity(Intent().setClassName(packageName, "com.wristlink.workout.WorkoutActivity"))
        }
    }
}

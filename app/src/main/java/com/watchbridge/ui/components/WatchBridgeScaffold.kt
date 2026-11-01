package com.watchbridge.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.scrollAway

@Composable
fun WatchBridgeScaffold(
    listState: ScalingLazyListState,
    showTimeText: Boolean = true,
    content: @Composable () -> Unit
) {
    Scaffold(
        timeText = {
            if (showTimeText) {
                // Slides the clock off as the list scrolls, so it never sits on top of list items
                TimeText(modifier = Modifier.scrollAway(listState, itemIndex = 0))
            }
        },
        vignette = {
            Vignette(vignettePosition = VignettePosition.TopAndBottom)
        },
        positionIndicator = {
            PositionIndicator(scalingLazyListState = listState)
        },
        content = content
    )
}

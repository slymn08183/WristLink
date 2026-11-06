package com.wristlink.watchface

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.RenderParameters
import androidx.wear.watchface.editor.EditorSession
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import com.wristlink.ui.components.WristLinkScaffold
import com.wristlink.ui.theme.WristLinkTheme
import kotlinx.coroutines.launch

/**
 * "Customize" for both WristLink faces: a live preview, a row per slot that opens the watch's own
 * data source picker, and the accent colors. Changes are saved when the editor closes.
 */
class FaceEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WristLinkTheme { Loading() } }
        lifecycleScope.launch {
            val editor = EditorSession.createOnWatchEditorSession(this@FaceEditorActivity)
            setContent { WristLinkTheme { FaceEditorScreen(editor, onDone = ::finish) } }
        }
    }
}

/** What each slot is called in the editor, by the ids the two faces use. */
private fun slotName(id: Int): String = when (id) {
    10 -> "Top left"
    11 -> "Top right"
    12 -> "Bottom right"
    13 -> "Bottom left"
    20 -> "Under the time"
    WristLinkWatchFaceService.LEFT_SLOT -> "Left"
    WristLinkWatchFaceService.RIGHT_SLOT -> "Right"
    else -> "Slot $id"
}

@Composable
private fun FaceEditorScreen(editor: EditorSession, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val userStyle by editor.userStyle.collectAsState()
    val previewData by editor.complicationsPreviewData.collectAsState()
    val sources by editor.complicationsDataSourceInfo.collectAsState()
    val slots by editor.complicationSlotsState.collectAsState()
    val colors = editor.userStyleSchema.rootUserStyleSettings
        .firstOrNull { it.id.value == FaceTheme.SETTING_ID } as? UserStyleSetting.ListUserStyleSetting

    // Redrawn whenever the colors or the slots' data change
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(userStyle, previewData) {
        preview = editor.renderWatchFaceToBitmap(
            RenderParameters(DrawMode.INTERACTIVE, WatchFaceLayer.ALL_WATCH_FACE_LAYERS, null),
            editor.previewReferenceInstant,
            previewData
        )
    }

    val listState = rememberScalingLazyListState()
    WristLinkScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 24.dp, start = 10.dp, end = 10.dp, bottom = 40.dp),
            autoCentering = AutoCenteringParams(itemIndex = 0)
        ) {
            item {
                Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                    preview?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Preview",
                            modifier = Modifier.size(150.dp).clip(CircleShape)
                        )
                    } ?: CircularProgressIndicator()
                }
            }

            item { SectionHeader("DATA") }
            for (id in slots.filterValues { it.isEnabled }.keys.sorted()) {
                item {
                    Chip(
                        onClick = { scope.launch { editor.openComplicationDataSourceChooser(id) } },
                        label = { Text(slotName(id), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            Text(sources[id]?.name ?: "Empty", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (colors != null) {
                item { SectionHeader("COLOR") }
                for (option in colors.options.filterIsInstance<UserStyleSetting.ListUserStyleSetting.ListOption>()) {
                    item {
                        ToggleChip(
                            checked = userStyle[colors] == option,
                            onCheckedChange = {
                                editor.userStyle.value = userStyle.toMutableUserStyle()
                                    .apply { this[colors] = option }
                                    .toUserStyle()
                            },
                            label = { Text(option.displayName.toString(), maxLines = 1) },
                            toggleControl = {
                                androidx.wear.compose.material.Icon(
                                    imageVector = ToggleChipDefaults.radioIcon(checked = userStyle[colors] == option),
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item {
                Chip(
                    onClick = onDone,
                    label = { Text("Done") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.caption1.copy(letterSpacing = 1.5.sp),
        color = MaterialTheme.colors.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

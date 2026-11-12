package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlin.math.roundToInt

/**
 * RCTV (seek preview, as YouTube TV): the floating window over the timeline while the remote moves
 * it: the time there and, when the movie gives pictures, the picture of that moment in a rounded
 * window. It sits right over the timeline's dot and follows it, without taking any room of its own
 * (the timeline and the controls stay exactly as they are). Put it over the timeline with
 * [Modifier.matchParentSize]; [fraction] is where the dot is (0 to 1).
 */
@Composable
internal fun SeekPreviewWindow(
    preview: SeekPreviewUi,
    fraction: Float,
    modifier: Modifier = Modifier
) {
    Layout(
        modifier = modifier,
        content = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = SeekPreviewSources.timeLabel(preview.positionMs),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), blurRadius = 8f)
                    )
                )
                if (preview.showsPictures) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        modifier = Modifier
                            .size(width = SEEK_PREVIEW_WIDTH, height = SEEK_PREVIEW_HEIGHT)
                            .shadow(elevation = 12.dp, shape = shape)
                            .clip(shape)
                            .background(Color(0xFF101010))
                            .border(width = 2.dp, color = Color.White.copy(alpha = 0.9f), shape = shape)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Crossfade(
                            targetState = preview.image,
                            animationSpec = tween(120),
                            label = "seekPreviewPicture"
                        ) { image ->
                            if (image != null) {
                                Image(
                                    bitmap = image,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    ) { measurables, constraints ->
        val window = measurables.first().measure(Constraints())
        val width = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: window.width
        val height = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: 0
        val gap = 10.dp.roundToPx()
        layout(width, height) {
            val center = (width * fraction.coerceIn(0f, 1f)).roundToInt()
            val x = (center - window.width / 2).coerceIn(0, (width - window.width).coerceAtLeast(0))
            // Above the timeline: drawn outside its own room, so nothing else moves.
            window.place(x, -window.height - gap)
        }
    }
}

private val SEEK_PREVIEW_WIDTH = 224.dp
private val SEEK_PREVIEW_HEIGHT = 126.dp

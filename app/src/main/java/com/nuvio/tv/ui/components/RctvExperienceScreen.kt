package com.nuvio.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.account.GlowBackground

private val PageBackground = Color(0xFF060816)
private val BarTrack = Color.White.copy(alpha = 0.10f)
private val BarStart = Color(0xFF2563EB)
private val BarEnd = Color(0xFF22D3EE)

/**
 * RCTV, as the app opens: "Creating Your Experience". Home's rows, the Live TV channels and guide
 * and the sports load behind it, so when its bar is full everything opens at once instead of
 * filling in while the person watches. The logo and the background are the activation page's.
 */
@Composable
fun RctvExperienceScreen(progress: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "experienceProgress"
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        GlowBackground()
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.rctv_activation_logo),
                contentDescription = "RCTV",
                modifier = Modifier.width(340.dp)
            )
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = stringResource(R.string.rctv_experience_title),
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(34.dp))
            ExperienceBar(
                progress = shown,
                modifier = Modifier
                    .width(560.dp)
                    .height(30.dp)
            )
        }
    }
}

/** A rounded bar that fills in blue, with a soft glow around what's filled. */
@Composable
private fun ExperienceBar(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val barHeight = 12.dp.toPx()
        val top = (size.height - barHeight) / 2f
        val radius = CornerRadius(barHeight / 2f)
        drawRoundRect(color = BarTrack, topLeft = Offset(0f, top), size = Size(size.width, barHeight), cornerRadius = radius)
        val filled = size.width * progress
        if (filled <= 0f) return@Canvas
        // The glow: a few wider, fainter bars behind the filled part.
        for (step in 3 downTo 1) {
            val grow = step * 3.dp.toPx()
            drawRoundRect(
                color = BarEnd.copy(alpha = 0.07f),
                topLeft = Offset(-grow, top - grow),
                size = Size(filled + grow * 2, barHeight + grow * 2),
                cornerRadius = CornerRadius(barHeight / 2f + grow)
            )
        }
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(BarStart, BarEnd), startX = 0f, endX = filled.coerceAtLeast(1f)),
            topLeft = Offset(0f, top),
            size = Size(filled, barHeight),
            cornerRadius = radius
        )
        // A thin shine along the top.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.22f),
            topLeft = Offset(barHeight / 2f, top + 2.dp.toPx()),
            size = Size((filled - barHeight).coerceAtLeast(0f), 2.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx())
        )
    }
}

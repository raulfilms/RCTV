package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * A small label on a title's card ("NEW", "PREMIERE", "COMING SOON"), see [com.nuvio.tv.domain.model.MetaPreview.badge].
 * Titles still to come get a dark label; the others a light one.
 */
@Composable
fun TitleBadge(
    text: String,
    upcoming: Boolean,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = if (upcoming) Color.White else Color.Black,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        maxLines = 1,
        modifier = modifier
            .background(
                color = if (upcoming) Color.Black.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.92f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

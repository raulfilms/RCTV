package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.diagnostics.CrashLog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.SourcePrivacy

/**
 * RCTV: the app closed on an error last time; here is what it was (once), to take a picture of
 * and send, so it can be fixed.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CrashReportDialog(trace: String, onDismiss: () -> Unit) {
    val okRequester = remember { FocusRequester() }
    val summary = remember(trace) { SourcePrivacy.scrubCrash(CrashLog.summary(trace)) }
    LaunchedEffect(Unit) { runCatching { okRequester.requestFocus() } }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.crash_report_title),
        subtitle = stringResource(R.string.crash_report_message),
        width = 860.dp,
        usePlatformDefaultWidth = false
    ) {
        Text(
            text = summary,
            color = NuvioTheme.colors.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(okRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.crash_report_ok))
        }
    }
}

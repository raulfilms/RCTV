package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.bugreport.BugReportCategory
import com.nuvio.tv.core.bugreport.BugReports
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * RCTV (owner): the bug report's list. The person picks what went wrong (one press) and that's it:
 * the app gathers the details and sends them itself ([BugReports.send]), then shows the report's
 * number ("RC-7K3QX2") to read out if they call. Nothing here shows a source, a server or a
 * provider's name.
 */
@Composable
fun BugReportDialog(
    onPick: suspend (BugReportCategory) -> BugReports.Result,
    onDismiss: () -> Unit
) {
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BugReports.Result?>(null) }
    val scope = rememberCoroutineScope()

    val done = result
    if (done != null) {
        val okRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { okRequester.requestFocus() } }
        NuvioDialog(
            onDismiss = onDismiss,
            title = stringResource(R.string.bug_report_thanks_title),
            subtitle = stringResource(
                if (done.sent) R.string.bug_report_sent_body else R.string.bug_report_saved_body,
                done.reportId
            )
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(okRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.bug_report_ok))
                }
            }
        }
        return
    }

    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRequester.requestFocus() } }
    NuvioDialog(
        // Back while it's sending: the report still goes (it's kept on the TV first).
        onDismiss = onDismiss,
        title = stringResource(R.string.bug_report_title),
        subtitle = stringResource(if (sending) R.string.bug_report_sending else R.string.bug_report_subtitle),
        width = 760.dp,
        usePlatformDefaultWidth = false
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(BugReportCategory.entries, key = { it.key }) { category ->
                Button(
                    onClick = {
                        if (sending) return@Button
                        sending = true
                        scope.launch {
                            result = try {
                                onPick(category)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            sending = false
                            // Couldn't even keep it: the list stays, to try again.
                        }
                    },
                    // Kept pressable while it sends (a disabled button would drop the remote's focus).
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (category == BugReportCategory.entries.first()) {
                                Modifier.focusRequester(firstRequester)
                            } else {
                                Modifier
                            }
                        ),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = stringResource(category.titleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

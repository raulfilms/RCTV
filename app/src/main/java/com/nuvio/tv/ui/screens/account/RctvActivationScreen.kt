@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.rctvcontrol.RctvActivation
import com.nuvio.tv.core.rctvcontrol.RctvCodes
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

/**
 * RCTV Control: the page a TV shows until it's activated with its account's 8-character code
 * (the code the owner gives from the website). The account's plan then decides the app's menus.
 */
@Composable
fun RctvActivationScreen(
    onActivate: suspend (String) -> RctvActivation,
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    val submit: () -> Unit = submit@{
        if (busy || !RctvCodes.isValid(code)) return@submit
        busy = true
        errorRes = null
        scope.launch {
            val result = onActivate(code)
            errorRes = when (result) {
                is RctvActivation.Activated -> null
                RctvActivation.WrongCode -> R.string.rctv_activation_wrong_code
                RctvActivation.TooManyTries -> R.string.rctv_activation_too_many
                RctvActivation.NoConnection -> R.string.rctv_activation_no_connection
            }
            busy = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .background(
                    color = NuvioTheme.colors.BackgroundElevated,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(NuvioTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.rctv_activation_title),
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Text(
                text = stringResource(R.string.rctv_activation_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))
            Text(
                text = stringResource(R.string.rctv_activation_code_label),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            InputField(
                value = code,
                onValueChange = {
                    code = RctvCodes.normalize(it)
                    errorRes = null
                },
                placeholder = "ABCD2346",
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
                onImeAction = submit,
                modifier = Modifier.focusRequester(fieldFocus)
            )
            errorRes?.let { res ->
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF44336),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))
            Button(
                onClick = submit,
                enabled = !busy && RctvCodes.isValid(code),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(if (busy) R.string.rctv_activation_busy else R.string.rctv_activation_button),
                    modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

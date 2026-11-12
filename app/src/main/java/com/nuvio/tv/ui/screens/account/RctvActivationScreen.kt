@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.rctvcontrol.RctvActivation
import com.nuvio.tv.core.rctvcontrol.RctvCodes
import com.nuvio.tv.ui.components.NuvioDialog
import kotlinx.coroutines.launch

/** The owner's website, where accounts, plans and TVs are managed. */
private const val ADMIN_WEBSITE = "https://rctv-control.netlify.app"

private val PageBackground = Color(0xFF060816)
private val Purple = Color(0xFF5B21E6)
private val PurpleFocused = Color(0xFF7C3AED)
private val FieldBorder = Color(0xFF8B8FB8)

/**
 * RCTV Control: the page a TV shows until it's activated with its account's 8-character code
 * (the code the owner gives from the website). The account's plan then decides the app's menus.
 * At the bottom, "Admin login" shows where the owner manages everything (the website), with a QR.
 */
@Composable
fun RctvActivationScreen(
    onActivate: suspend (String) -> RctvActivation,
    onBackPress: () -> Unit
) {
    var showAdmin by remember { mutableStateOf(false) }
    BackHandler { if (showAdmin) showAdmin = false else onBackPress() }

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
            .background(PageBackground)
    ) {
        GlowBackground()

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(440.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.rctv_activation_logo),
                contentDescription = "RCTV",
                modifier = Modifier.width(300.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.rctv_activation_subtitle),
                color = Color(0xFFD5D7E3),
                fontSize = 15.sp,
                letterSpacing = 5.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(34.dp))
            CodeField(
                value = code,
                onValueChange = {
                    code = RctvCodes.normalize(it)
                    errorRes = null
                },
                onDone = submit,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = submit,
                enabled = !busy && RctvCodes.isValid(code),
                colors = ButtonDefaults.colors(
                    containerColor = Purple,
                    contentColor = Color.White,
                    focusedContainerColor = PurpleFocused,
                    focusedContentColor = Color.White,
                    disabledContainerColor = Purple.copy(alpha = 0.55f),
                    disabledContentColor = Color.White.copy(alpha = 0.8f)
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                scale = ButtonDefaults.scale(focusedScale = 1.03f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(if (busy) R.string.rctv_activation_busy else R.string.rctv_activation_button),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            errorRes?.let { res ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(res),
                    color = Color(0xFFFF6B7A),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(26.dp))
            Text(
                text = stringResource(R.string.rctv_activation_no_code),
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )
        }

        // At the very bottom: where the owner manages everything.
        Surface(
            onClick = { showAdmin = true },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White.copy(alpha = 0.12f),
                contentColor = Color.White.copy(alpha = 0.55f),
                focusedContentColor = Color.White
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
        ) {
            Text(
                text = stringResource(R.string.rctv_activation_admin),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }

    if (showAdmin) AdminDialog(onDismiss = { showAdmin = false })
}

/** The code box: OK starts typing (the TV's keyboard opens); Done logs in. */
@Composable
private fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { textFocus.requestFocus() }
            keyboard?.show()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = { editing = true },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.04f),
            focusedContainerColor = Color.White.copy(alpha = 0.08f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.5.dp, FieldBorder), shape = shape),
            focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier.height(56.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Key,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp)
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 19.sp, letterSpacing = 3.sp),
                cursorBrush = SolidColor(if (editing) Color.White else Color.Transparent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    editing = false
                    keyboard?.hide()
                    onDone()
                }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(textFocus)
                    .onFocusChanged {
                        if (!it.isFocused && editing) {
                            editing = false
                            keyboard?.hide()
                        }
                    },
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.rctv_activation_placeholder),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 19.sp
                        )
                    }
                    inner()
                }
            )
        }
    }
}

/** "Admin login": the website where the owner manages accounts, plans and TVs, with a QR for the phone. */
@Composable
private fun AdminDialog(onDismiss: () -> Unit) {
    val qr = remember { runCatching { QrCodeGenerator.generate(ADMIN_WEBSITE, 400).asImageBitmap() }.getOrNull() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.rctv_activation_admin),
        subtitle = stringResource(R.string.rctv_activation_admin_hint),
        width = 460.dp
    ) {
        qr?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier
                    .size(200.dp)
                    .align(Alignment.CenterHorizontally)
            )
        }
        Text(
            text = ADMIN_WEBSITE.removePrefix("https://"),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.colors(
                containerColor = Purple,
                contentColor = Color.White,
                focusedContainerColor = PurpleFocused,
                focusedContentColor = Color.White
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.rctv_activation_close))
        }
    }
}

/**
 * The page's purple and pink glows and soft curves (as in the owner's design). Also behind
 * "Creating Your Experience" ([com.nuvio.tv.ui.components.RctvExperienceScreen]).
 */
@Composable
internal fun GlowBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color(0xFF6D28D9).copy(alpha = 0.55f), Color(0xFF3B0F8C).copy(alpha = 0.18f), Color.Transparent),
                center = Offset(w * 0.05f, h * 0.02f),
                radius = w * 0.42f
            )
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color(0xFFE11D48).copy(alpha = 0.5f), Color(0xFF7C1D6F).copy(alpha = 0.2f), Color.Transparent),
                center = Offset(w * 0.98f, h * 1.0f),
                radius = w * 0.45f
            )
        )
        // Top-left curves.
        val topWave = Path().apply {
            moveTo(0f, h * 0.36f)
            cubicTo(w * 0.10f, h * 0.24f, w * 0.20f, h * 0.10f, w * 0.30f, 0f)
        }
        drawPath(
            topWave,
            Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9).copy(alpha = 0f)), start = Offset(w * 0.3f, 0f), end = Offset(0f, h * 0.36f)),
            style = Stroke(width = 3f)
        )
        val topWave2 = Path().apply {
            moveTo(0f, h * 0.50f)
            cubicTo(w * 0.14f, h * 0.34f, w * 0.26f, h * 0.14f, w * 0.40f, 0f)
        }
        drawPath(
            topWave2,
            Brush.linearGradient(listOf(Color(0xFF7C3AED).copy(alpha = 0.6f), Color.Transparent), start = Offset(w * 0.4f, 0f), end = Offset(0f, h * 0.5f)),
            style = Stroke(width = 2f)
        )
        // Bottom-right curves.
        val bottomWave = Path().apply {
            moveTo(w * 0.58f, h)
            cubicTo(w * 0.74f, h * 0.90f, w * 0.88f, h * 0.76f, w, h * 0.64f)
        }
        drawPath(
            bottomWave,
            Brush.linearGradient(listOf(Color(0xFFF43F5E).copy(alpha = 0f), Color(0xFFF43F5E)), start = Offset(w * 0.58f, h), end = Offset(w, h * 0.64f)),
            style = Stroke(width = 3f)
        )
    }
}

package com.nuvio.tv.ui.screens.livetv

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.ui.components.AppleFilterMenuButton
import com.nuvio.tv.ui.components.AppleFilterOption
import com.nuvio.tv.ui.components.AppleFilterSection
import com.nuvio.tv.ui.components.AppleGlassButton
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvRadius
import com.nuvio.tv.ui.components.AppleTvSpacing
import com.nuvio.tv.ui.components.AppleTvType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Timeline guide (EPG grid): the times across the top with a red "Now" marker and line,
 * channel logos down the left, each channel's programs as blocks sized by their length,
 * and a panel on the right with the focused program. Left/right moves between programs
 * (the timeline slides along), up/down between channels at the same time.
 */

// 4 dp per minute: an hour is 240 dp, a half-hour show 120 dp.
private val MinuteWidth = 4.dp
private val RowHeight = 54.dp
private val RowSpacing = 6.dp
private val ChannelColumnWidth = 96.dp
private val ColumnGap = 6.dp
private val BlockGap = 4.dp
private val RulerHeight = 26.dp
private val PreviewWidth = 220.dp
private val BlockShape = RoundedCornerShape(AppleTvRadius.Poster)

private const val SLOT_MS = 30L * 60_000L
private const val MINUTE_MS = 60_000L

private val GuideTop = Color(0xFF141416)
private val GuideBottom = Color(0xFF0A0A0B)
private val BlockFill = AppleTvColors.Surface1
private val BlockFillAiring = Color(0xFF242427)
private val BlockFocused = Color(0xFF4A4A4F)
private val FocusEdge = Color.White.copy(alpha = 0.55f)
// Bright red for the "Now" line; a deeper red behind white badge text so it reads (5:1).
private val NowLine = AppleTvColors.Destructive
private val BadgeRed = Color(0xFFD42A20)

private const val SECTION_LISTS = "lists"
private const val SECTION_CATEGORIES = "categories"
private const val SECTION_COUNTRY = "country"
private val CustomStar = Color(0xFFFFD60A)

/** "1:00 – 2:00 PM" (the AM/PM once when both times share it), or "13:00 – 14:00". */
private fun formatTimeRange(format: java.text.DateFormat, startMs: Long, endMs: Long): String {
    val start = format.format(Date(startMs))
    val end = format.format(Date(endMs))
    val suffix = start.takeLastWhile { !it.isDigit() }
    val shortStart = if (suffix.isNotBlank() && end.endsWith(suffix)) start.dropLast(suffix.length) else start
    return "$shortStart – $end"
}

private fun floorToSlot(timeMs: Long): Long = timeMs - Math.floorMod(timeMs, SLOT_MS)
private fun ceilToSlot(timeMs: Long): Long = floorToSlot(timeMs + SLOT_MS - 1)

private fun blockKey(channel: LiveTvChannel, program: EpgProgram?): String =
    "${channel.id}|${program?.startMs ?: "empty"}"

private val bracketTags = Regex("""\s*\[\s*(new|live)\s*]""", RegexOption.IGNORE_CASE)

/** "🏈🎓 [NCAAF] College Football [NEW]" -> "[NCAAF] College Football": badges say new/live instead. */
internal fun cleanProgramTitle(raw: String): String {
    val withoutTags = raw.replace(bracketTags, "").trim()
    var index = 0
    while (index < withoutTags.length) {
        val codePoint = withoutTags.codePointAt(index)
        if (Character.isLetterOrDigit(codePoint) || codePoint == '['.code || codePoint == '('.code ||
            codePoint == '"'.code || codePoint == '¡'.code || codePoint == '¿'.code
        ) {
            break
        }
        index += Character.charCount(codePoint)
    }
    return withoutTags.substring(index).trim().ifBlank { raw.trim() }
}

@Composable
internal fun LiveTvTimelineGuide(
    uiState: LiveTvUiState,
    topInset: Dp,
    onSetBrowse: (GuideBrowse) -> Unit,
    onSetCountry: (String?) -> Unit,
    onToggleCustom: (LiveTvChannel) -> Unit,
    onShowChannels: () -> Unit,
    onAddIptv: (() -> Unit)?,
    onProgramClick: (LiveTvChannel, EpgProgram?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val nowMs = uiState.nowMs
    val nowMinute = nowMs / MINUTE_MS

    fun programsOf(channel: LiveTvChannel): List<EpgProgram> =
        channel.epgChannelId?.let { uiState.epgByChannel[it] }.orEmpty()

    // First menu (Live Now, A to Z, Custom or a category) and the country filter.
    val channels = remember(
        uiState.channels,
        uiState.channelCategories,
        uiState.guideBrowse,
        uiState.guideCountry,
        uiState.customChannelIds
    ) {
        val inCountry = uiState.channels.filter { uiState.guideCountry == null || it.country == uiState.guideCountry }
        when (val browse = uiState.guideBrowse) {
            GuideBrowse.LIVE_NOW -> inCountry
            GuideBrowse.A_TO_Z -> {
                val collator = java.text.Collator.getInstance(Locale.getDefault())
                inCountry.sortedWith { a, b -> collator.compare(a.name, b.name) }
            }
            GuideBrowse.CUSTOM -> inCountry.filter { it.id in uiState.customChannelIds }
            else -> inCountry.filter { browse in uiState.channelCategories[it.id].orEmpty() }
        }
    }
    val countryCounts = remember(uiState.channels) {
        uiState.channels.mapNotNull { it.country }.groupingBy { it }.eachCount()
    }

    // Visible time window.
    var viewStartMs by remember { mutableLongStateOf(floorToSlot(System.currentTimeMillis())) }
    var timelineWidthPx by remember { mutableIntStateOf(0) }
    val timelineWidth = with(density) { timelineWidthPx.toDp() }
    val spanMs = if (timelineWidthPx > 0) {
        (timelineWidth.value / MinuteWidth.value * MINUTE_MS).toLong()
    } else {
        2 * 60 * MINUTE_MS
    }
    val viewEndMs = viewStartMs + spanMs
    val minViewStartMs = floorToSlot(nowMs) - SLOT_MS

    // Focus: which block has it, the time up/down aims at, and focus moves we asked for.
    val listState = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }
    var focusedRow by remember { mutableIntStateOf(-1) }
    var focusedStart by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var anchorMs by remember { mutableLongStateOf(nowMs) }
    var pendingKey by remember { mutableStateOf<String?>(null) }
    var pendingRow by remember { mutableIntStateOf(0) }
    var pendingSerial by remember { mutableIntStateOf(0) }
    var previewChannel by remember { mutableStateOf<LiveTvChannel?>(null) }
    var previewProgram by remember { mutableStateOf<EpgProgram?>(null) }

    fun focusBlock(row: Int, program: EpgProgram?) {
        val channel = channels.getOrNull(row) ?: return
        pendingKey = blockKey(channel, program)
        pendingRow = row
        pendingSerial++
    }

    LaunchedEffect(pendingSerial) {
        val target = pendingKey ?: return@LaunchedEffect
        repeat(12) { attempt ->
            withFrameNanos { }
            val requester = requesters[target]
            if (requester != null && runCatching { requester.requestFocus() }.getOrDefault(false)) {
                return@LaunchedEffect
            }
            // The row isn't laid out yet: bring it on screen first.
            if (attempt == 2 && listState.layoutInfo.visibleItemsInfo.none { it.index == pendingRow }) {
                runCatching { listState.scrollToItem((pendingRow - 2).coerceAtLeast(0)) }
            }
        }
    }

    // Opening the guide: focus what's on now on the first channel.
    LaunchedEffect(channels.isNotEmpty()) {
        val first = channels.firstOrNull() ?: return@LaunchedEffect
        val current = programsOf(first).firstOrNull { it.isAiringAt(nowMs) }
        previewChannel = first
        previewProgram = current
        focusBlock(0, current)
    }

    fun onDirection(key: Key): Boolean {
        val row = focusedRow
        if (row !in channels.indices) return false
        val programs = programsOf(channels[row])
        val index = programs.indexOfFirst { it.startMs == focusedStart }
        return when (key) {
            Key.DirectionRight -> {
                val next = if (index >= 0) programs.getOrNull(index + 1) else programs.firstOrNull { it.startMs >= viewEndMs }
                if (next != null) {
                    val spanSlots = (spanMs / SLOT_MS).coerceAtLeast(1) * SLOT_MS
                    if (next.startMs >= viewEndMs - 20 * MINUTE_MS || next.endMs > viewEndMs) {
                        // Slide just enough to show it whole (or start it at the left if it's long).
                        viewStartMs = maxOf(viewStartMs, minOf(floorToSlot(next.startMs), ceilToSlot(next.endMs) - spanSlots))
                    }
                    anchorMs = maxOf(next.startMs, viewStartMs)
                    focusBlock(row, next)
                }
                true
            }
            Key.DirectionLeft -> {
                val previous = if (index > 0) programs[index - 1] else if (index < 0) programs.lastOrNull { it.endMs <= viewStartMs } else null
                if (previous == null || previous.endMs <= minViewStartMs) {
                    // Nothing earlier: slide back to the start, then let focus leave (the menu opens).
                    if (viewStartMs > minViewStartMs) {
                        viewStartMs = maxOf(minViewStartMs, viewStartMs - SLOT_MS)
                        true
                    } else {
                        false
                    }
                } else {
                    if (previous.startMs < viewStartMs) {
                        viewStartMs = maxOf(minViewStartMs, floorToSlot(previous.startMs))
                    }
                    anchorMs = maxOf(previous.startMs, viewStartMs)
                    focusBlock(row, previous)
                    true
                }
            }
            Key.DirectionDown, Key.DirectionUp -> {
                val target = if (key == Key.DirectionDown) row + 1 else row - 1
                if (target !in channels.indices) {
                    // Up from the first channel goes to the filters above.
                    key == Key.DirectionDown
                } else {
                    val targetPrograms = programsOf(channels[target])
                    val program = targetPrograms.firstOrNull { it.startMs <= anchorMs && it.endMs > anchorMs }
                        ?: targetPrograms.firstOrNull { it.endMs > viewStartMs && it.startMs < viewEndMs }
                    focusBlock(target, program)
                    true
                }
            }
            else -> false
        }
    }

    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    fun timeRange(program: EpgProgram): String = formatTimeRange(timeFormat, program.startMs, program.endMs)

    val liveLabel = stringResource(R.string.livetv_guide_live_badge)
    val nowLabel = stringResource(R.string.livetv_guide_now)
    val noInfoLabel = stringResource(R.string.livetv_guide_no_info)
    val logoLooks = remember { mutableStateMapOf<String, Boolean>() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GuideTop, GuideBottom)))
            .padding(start = AppleTvSpacing.SafeX, end = AppleTvSpacing.SafeX, top = topInset)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            GuideHeader(
                uiState = uiState,
                countryCounts = countryCounts,
                onSetBrowse = onSetBrowse,
                onSetCountry = onSetCountry,
                onShowChannels = onShowChannels,
                onAddIptv = onAddIptv
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxSize()) {
                // Ruler + grid.
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TimeRuler(
                            viewStartMs = viewStartMs,
                            viewEndMs = viewEndMs,
                            nowMs = nowMs,
                            nowLabel = nowLabel,
                            timeFormat = timeFormat,
                            onTimelineWidth = { timelineWidthPx = it }
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        if (channels.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(
                                        when {
                                            uiState.guideBrowse == GuideBrowse.CUSTOM -> R.string.livetv_guide_custom_empty
                                            uiState.guideBrowse.isCategory -> R.string.livetv_guide_category_empty
                                            else -> R.string.livetv_no_channels_match
                                        }
                                    ),
                                    style = AppleTvType.Body,
                                    color = AppleTvColors.LabelSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 40.dp)
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(RowSpacing),
                                contentPadding = PaddingValues(bottom = 24.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onFocusChanged { if (!it.hasFocus) focusedRow = -1 }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) false else onDirection(event.key)
                                    }
                            ) {
                                itemsIndexed(channels, key = { _, channel -> channel.id }) { rowIndex, channel ->
                                    GuideRow(
                                        channel = channel,
                                        isCustom = channel.id in uiState.customChannelIds,
                                        programs = programsOf(channel),
                                        viewStartMs = viewStartMs,
                                        viewEndMs = viewEndMs,
                                        nowMs = nowMs,
                                        logoLooks = logoLooks,
                                        liveLabel = liveLabel,
                                        noInfoLabel = noInfoLabel,
                                        timeRange = ::timeRange,
                                        requesterFor = ::requesterFor,
                                        onBlockFocused = { program ->
                                            val key = blockKey(channel, program)
                                            // Our own moves keep the time up/down aims at; others reset it.
                                            if (key != pendingKey) {
                                                anchorMs = maxOf(program?.startMs ?: viewStartMs, viewStartMs)
                                            }
                                            pendingKey = null
                                            focusedRow = rowIndex
                                            focusedStart = program?.startMs ?: Long.MIN_VALUE
                                            previewChannel = channel
                                            previewProgram = program
                                        },
                                        onBlockClick = { program -> onProgramClick(channel, program) },
                                        onBlockLongClick = {
                                            val added = channel.id !in uiState.customChannelIds
                                            onToggleCustom(channel)
                                            android.widget.Toast.makeText(
                                                context,
                                                context.getString(
                                                    if (added) R.string.livetv_guide_custom_added else R.string.livetv_guide_custom_removed,
                                                    channel.name
                                                ),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // The red "Now" line across every channel.
                    if (nowMs in viewStartMs until viewEndMs && channels.isNotEmpty()) {
                        val lineX = ChannelColumnWidth + ColumnGap + MinuteWidth * ((nowMs - viewStartMs) / MINUTE_MS.toFloat())
                        Box(
                            modifier = Modifier
                                .offset(x = lineX - 1.dp)
                                .padding(top = RulerHeight)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(NowLine)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                GuidePreviewPanel(
                    channel = previewChannel,
                    program = previewProgram,
                    nowMs = nowMs,
                    liveLabel = liveLabel,
                    nowLabel = nowLabel,
                    timeRange = ::timeRange,
                    modifier = Modifier
                        .width(PreviewWidth)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun GuideHeader(
    uiState: LiveTvUiState,
    countryCounts: Map<String, Int>,
    onSetBrowse: (GuideBrowse) -> Unit,
    onSetCountry: (String?) -> Unit,
    onShowChannels: () -> Unit,
    onAddIptv: (() -> Unit)?
) {
    val context = LocalContext.current
    val countryTitle = stringResource(R.string.livetv_guide_country)
    val allCountries = stringResource(R.string.livetv_guide_all_countries)
    val categoriesTitle = stringResource(R.string.livetv_guide_categories)

    // Live Now / A to Z / Custom, then the categories, each with its number of channels.
    val browseSections = remember(
        uiState.guideBrowse,
        uiState.channels,
        uiState.channelCategories,
        uiState.customChannelIds,
        categoriesTitle,
        context
    ) {
        val categoryCounts = HashMap<GuideBrowse, Int>()
        uiState.channels.forEach { channel ->
            uiState.channelCategories[channel.id]?.forEach { categoryCounts[it] = (categoryCounts[it] ?: 0) + 1 }
        }
        val customCount = uiState.channels.count { it.id in uiState.customChannelIds }
        fun option(browse: GuideBrowse, count: Int?) =
            AppleFilterOption(context.getString(browse.labelRes), browse.name, count)
        listOf(
            AppleFilterSection(
                key = SECTION_LISTS,
                title = "",
                options = listOf(
                    option(GuideBrowse.LIVE_NOW, null),
                    option(GuideBrowse.A_TO_Z, null),
                    option(GuideBrowse.CUSTOM, customCount)
                ),
                selectedValue = uiState.guideBrowse.name
            ),
            AppleFilterSection(
                key = SECTION_CATEGORIES,
                title = categoriesTitle,
                options = GuideBrowse.entries
                    .filter { it.isCategory }
                    .map { option(it, categoryCounts[it] ?: 0) },
                selectedValue = uiState.guideBrowse.name
            )
        )
    }
    val countrySection = remember(countryCounts, uiState.guideCountry, allCountries, countryTitle) {
        AppleFilterSection(
            key = SECTION_COUNTRY,
            title = countryTitle,
            options = listOf(AppleFilterOption(allCountries, null)) +
                countryCounts.entries
                    .map { (code, count) -> AppleFilterOption(LiveTvCountry.displayName(code), code, count) }
                    .sortedBy { it.label },
            selectedValue = uiState.guideCountry
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.livetv_guide_header),
            style = AppleTvType.Title3,
            color = AppleTvColors.Label,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(18.dp))
        AppleFilterMenuButton(
            label = stringResource(uiState.guideBrowse.labelRes),
            sections = browseSections,
            hasActiveFilter = false,
            clearLabel = "",
            onSelect = { _, option ->
                option.value
                    ?.let { value -> GuideBrowse.entries.firstOrNull { it.name == value } }
                    ?.let(onSetBrowse)
            },
            onClearAll = {},
            leadingIcon = null,
            closeOnSelect = true
        )
        Spacer(modifier = Modifier.width(10.dp))
        AppleFilterMenuButton(
            label = uiState.guideCountry?.let { LiveTvCountry.displayName(it) } ?: countryTitle,
            sections = listOf(countrySection),
            hasActiveFilter = false,
            clearLabel = "",
            onSelect = { _, option -> onSetCountry(option.value) },
            onClearAll = {},
            leadingIcon = null,
            closeOnSelect = true
        )
        Spacer(modifier = Modifier.weight(1f))
        if (onAddIptv != null) {
            GuidePillButton(label = stringResource(R.string.livetv_preview_add_iptv), onClick = onAddIptv)
            Spacer(modifier = Modifier.width(10.dp))
        }
        GuidePillButton(
            label = stringResource(R.string.livetv_guide_channels),
            onClick = onShowChannels,
            showGridIcon = true
        )
    }
}

@Composable
private fun GuidePillButton(label: String, onClick: () -> Unit, showGridIcon: Boolean = false) {
    AppleGlassButton(
        onClick = onClick,
        shape = AppleTvRadius.Pill,
        modifier = Modifier.height(40.dp)
    ) { color ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showGridIcon) {
                Icon(
                    imageVector = Icons.Filled.GridView,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(AppleTvSpacing.Space1 + 2.dp))
            }
            Text(text = label, color = color, style = AppleTvType.Callout, maxLines = 1)
        }
    }
}

/** Date over the logos, then the half-hour marks and the red "Now" pill. */
@Composable
private fun TimeRuler(
    viewStartMs: Long,
    viewEndMs: Long,
    nowMs: Long,
    nowLabel: String,
    timeFormat: java.text.DateFormat,
    onTimelineWidth: (Int) -> Unit
) {
    val todayLabel = stringResource(R.string.livetv_guide_today)
    val dateText = remember(viewStartMs / (60 * MINUTE_MS), todayLabel) {
        val view = Calendar.getInstance().apply { timeInMillis = viewStartMs }
        val today = Calendar.getInstance()
        val isToday = view.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            view.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        // "Today, Oct 10" or "Sun, Oct 11": short enough for the logo column.
        val skeleton = if (isToday) "MMMd" else "EEEMMMd"
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        val formatted = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(viewStartMs))
        if (isToday) "$todayLabel, $formatted" else formatted
    }
    val density = LocalDensity.current
    val nowX = MinuteWidth * ((nowMs - viewStartMs) / MINUTE_MS.toFloat())
    val nowVisible = nowMs in viewStartMs until viewEndMs

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RulerHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dateText,
            style = AppleTvType.Caption1,
            color = AppleTvColors.LabelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(ChannelColumnWidth)
        )
        Spacer(modifier = Modifier.width(ColumnGap))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
                .onSizeChanged { onTimelineWidth(it.width) },
            contentAlignment = Alignment.CenterStart
        ) {
            var slot = floorToSlot(viewStartMs)
            while (slot < viewEndMs) {
                val x = MinuteWidth * ((slot - viewStartMs) / MINUTE_MS.toFloat())
                // Leave room for the "Now" pill.
                val clearOfNow = !nowVisible || (x - nowX).value !in -72f..22f
                if (slot >= viewStartMs && clearOfNow) {
                    Text(
                        text = timeFormat.format(Date(slot)),
                        style = AppleTvType.Caption1,
                        color = AppleTvColors.LabelSecondary,
                        maxLines = 1,
                        modifier = Modifier.offset(x = x + 4.dp)
                    )
                }
                slot += SLOT_MS
            }
            if (nowVisible) {
                val nowXPx = with(density) { nowX.roundToPx() }
                Text(
                    text = nowLabel,
                    style = AppleTvType.Caption2,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                val x = (nowXPx - placeable.width / 2)
                                    .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                                placeable.place(x, (constraints.maxHeight - placeable.height) / 2)
                            }
                        }
                        .clip(RoundedCornerShape(4.dp))
                        .background(BadgeRed)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: LiveTvChannel,
    isCustom: Boolean,
    programs: List<EpgProgram>,
    viewStartMs: Long,
    viewEndMs: Long,
    nowMs: Long,
    logoLooks: MutableMap<String, Boolean>,
    liveLabel: String,
    noInfoLabel: String,
    timeRange: (EpgProgram) -> String,
    requesterFor: (String) -> FocusRequester,
    onBlockFocused: (EpgProgram?) -> Unit,
    onBlockClick: (EpgProgram?) -> Unit,
    onBlockLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
    ) {
        ChannelLogoTile(
            channel = channel,
            isCustom = isCustom,
            logoLooks = logoLooks,
            modifier = Modifier
                .width(ChannelColumnWidth)
                .fillMaxHeight()
        )
        Spacer(modifier = Modifier.width(ColumnGap))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            val visible = programs.filter { it.endMs > viewStartMs && it.startMs < viewEndMs }
            if (visible.isEmpty()) {
                GuideBlock(
                    title = noInfoLabel,
                    timeText = null,
                    isAiring = false,
                    isLive = false,
                    liveLabel = liveLabel,
                    compact = false,
                    focusRequester = requesterFor(blockKey(channel, null)),
                    onFocused = { onBlockFocused(null) },
                    onClick = { onBlockClick(null) },
                    onLongClick = onBlockLongClick,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                visible.forEach { program ->
                    androidx.compose.runtime.key(program.startMs) {
                        val left = maxOf(program.startMs, viewStartMs)
                        val right = minOf(program.endMs, viewEndMs)
                        val x = MinuteWidth * ((left - viewStartMs) / MINUTE_MS.toFloat())
                        val width = (MinuteWidth * ((right - left) / MINUTE_MS.toFloat()) - BlockGap).coerceAtLeast(2.dp)
                        GuideBlock(
                            title = cleanProgramTitle(program.title),
                            timeText = timeRange(program),
                            isAiring = program.isAiringAt(nowMs),
                            isLive = program.isLive,
                            liveLabel = liveLabel,
                            compact = width < 64.dp,
                            focusRequester = requesterFor(blockKey(channel, program)),
                            onFocused = { onBlockFocused(program) },
                            onClick = { onBlockClick(program) },
                            onLongClick = onBlockLongClick,
                            modifier = Modifier
                                .offset(x = x)
                                .width(width)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideBlock(
    title: String,
    timeText: String?,
    isAiring: Boolean,
    isLive: Boolean,
    liveLabel: String,
    compact: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        // Press and hold: add the channel to (or take it out of) the Custom list.
        onLongClick = onLongClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(BlockShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isAiring) BlockFillAiring else BlockFill,
            contentColor = AppleTvColors.Label,
            focusedContainerColor = BlockFocused,
            focusedContentColor = AppleTvColors.Label
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(1.5.dp, FocusEdge), shape = BlockShape)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = AppleTvType.Body,
                    color = AppleTvColors.Label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isLive && isAiring && !compact) {
                    Spacer(modifier = Modifier.width(6.dp))
                    LiveBadge(text = liveLabel)
                }
            }
            if (timeText != null && !compact) {
                Text(
                    text = timeText,
                    style = AppleTvType.Caption2,
                    color = AppleTvColors.LabelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LiveBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = AppleTvType.Caption2,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BadgeRed)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/**
 * Channel logo on a dark tile. Dark one-color logos (meant for light backgrounds, like
 * many guide feeds' logos) are drawn white so they read on the dark guide.
 */
@Composable
private fun ChannelLogoTile(
    channel: LiveTvChannel,
    isCustom: Boolean,
    logoLooks: MutableMap<String, Boolean>,
    modifier: Modifier = Modifier
) {
    val url = channel.logoUrl?.takeIf { it.isNotBlank() }
    Box(
        modifier = modifier
            .clip(BlockShape)
            .background(AppleTvColors.Surface1),
        contentAlignment = Alignment.Center
    ) {
        if (url == null) {
            Text(
                text = channel.name,
                style = AppleTvType.Caption1,
                color = AppleTvColors.Label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        } else {
            val context = LocalContext.current
            val request = remember(url) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size(240, 120)
                    .allowHardware(false) // to read its pixels once
                    .crossfade(true)
                    .build()
            }
            val tintWhite = logoLooks[url] == true
            AsyncImage(
                model = request,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                colorFilter = if (tintWhite) ColorFilter.tint(Color.White, BlendMode.SrcIn) else null,
                onSuccess = { state ->
                    if (url !in logoLooks) {
                        val bitmap = (state.result.image as? BitmapImage)?.bitmap
                        logoLooks[url] = bitmap != null && isDarkOneColorLogo(bitmap)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        if (isCustom) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = CustomStar,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(11.dp)
            )
        }
    }
}

/**
 * True for a logo that is mostly see-through with dark, even-toned marks (navy, black or gray
 * lettering): white keeps it readable on a dark tile. Two-tone or bright logos stay as they are.
 */
private fun isDarkOneColorLogo(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return false
    val step = maxOf(1, minOf(width, height) / 24)
    var total = 0
    var clear = 0
    var sum = 0.0
    var sumSquares = 0.0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = runCatching { bitmap.getPixel(x, y) }.getOrDefault(0)
            total++
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 40) {
                clear++
            } else {
                val r = ((pixel ushr 16) and 0xFF) / 255.0
                val g = ((pixel ushr 8) and 0xFF) / 255.0
                val b = (pixel and 0xFF) / 255.0
                val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
                sum += luminance
                sumSquares += luminance * luminance
            }
            x += step
        }
        y += step
    }
    val opaque = total - clear
    if (opaque <= 0 || clear < total * 0.2) return false
    val mean = sum / opaque
    val variance = (sumSquares / opaque) - mean * mean
    return mean < 0.32 && variance < 0.02
}

/** The focused program: artwork, live/now badge, title, times and description. */
@Composable
private fun GuidePreviewPanel(
    channel: LiveTvChannel?,
    program: EpgProgram?,
    nowMs: Long,
    liveLabel: String,
    nowLabel: String,
    timeRange: (EpgProgram) -> String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(top = RulerHeight + 6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(BlockShape)
                .background(AppleTvColors.Surface1),
            contentAlignment = Alignment.Center
        ) {
            val art = program?.imageUrl
            if (!art.isNullOrBlank()) {
                AsyncImage(
                    model = art,
                    contentDescription = program?.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (!channel?.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel?.logoUrl,
                    contentDescription = channel?.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp)
                )
            }
            if (program != null && program.isAiringAt(nowMs)) {
                LiveBadge(
                    text = if (program.isLive) liveLabel else nowLabel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }
        }

        if (program != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = cleanProgramTitle(program.title),
                style = AppleTvType.Headline,
                color = AppleTvColors.Label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = listOfNotNull(timeRange(program), program.category).joinToString(" · "),
                style = AppleTvType.Caption1,
                color = AppleTvColors.LabelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (program.isAiringAt(nowMs)) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AppleTvColors.LabelTertiary)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(program.progressAt(nowMs))
                            .fillMaxHeight()
                            .background(AppleTvColors.Accent)
                    )
                }
            }
            program.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = AppleTvType.Caption1,
                    color = AppleTvColors.Label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            program.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = description,
                    style = AppleTvType.Caption1,
                    color = AppleTvColors.LabelSecondary,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }
            channel?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it.name,
                    style = AppleTvType.Caption2,
                    color = AppleTvColors.LabelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else if (channel != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = channel.name,
                style = AppleTvType.Headline,
                color = AppleTvColors.Label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

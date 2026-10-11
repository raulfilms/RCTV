package com.nuvio.tv.data.channelprofile

import android.content.Context
import android.util.Log
import com.nuvio.tv.ui.screens.channelprofile.ChannelBrands

/**
 * The channel list matched to TMDB (assets/channel-tmdb.csv, made with tmdb_match.py from
 * the guide's channels): each channel's guide ids and names, its TMDB network (its TV shows)
 * and its TMDB company (its movies). Columns are found by name, so the script's own output
 * (canales_tmdb.csv) can be dropped in as it is; rows without ids are simply not used.
 */
internal object ChannelTmdbMap {

    private const val TAG = "ChannelTmdbMap"
    private const val ASSET = "channel-tmdb.csv"

    data class Entry(val channel: String, val networkIds: List<Int>, val companyIds: List<Int>)

    data class Ids(val networks: List<Int>, val companies: List<Int>)

    @Volatile private var loaded = false
    @Volatile private var byGuideId: Map<String, Entry> = emptyMap()
    @Volatile private var byName: Map<String, Entry> = emptyMap()

    /** Reads the file once. Slow the first time: call off the main thread. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val text = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
                parse(text)
            }.onFailure { Log.w(TAG, "Channel TMDB list unavailable", it) }
            loaded = true
        }
    }

    /** TMDB networks and companies of the channels with these guide ids or names. */
    fun idsFor(guideIds: Collection<String>, names: Collection<String>): Ids {
        val entries = LinkedHashSet<Entry>()
        guideIds.forEach { id -> byGuideId[id.trim()]?.let(entries::add) }
        names.forEach { name -> byName[ChannelBrands.normalize(name)]?.let(entries::add) }
        return Ids(
            networks = entries.flatMap { it.networkIds }.distinct(),
            companies = entries.flatMap { it.companyIds }.distinct()
        )
    }

    private fun parse(text: String) {
        val rows = parseCsv(text.removePrefix("﻿"))
        val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return
        fun column(name: String) = header.indexOf(name)
        val channelColumn = column("canal")
        val networkColumn = column("network_id")
        val companyColumn = column("company_id")
        val guideIdsColumn = column("epg_ids")
        val feedsColumn = column("feeds_en_epg")
        val altNamesColumn = column("nombres_alternos")
        if (channelColumn < 0) return

        val guide = HashMap<String, Entry>()
        val names = HashMap<String, Entry>()
        for (row in rows.drop(1)) {
            fun cell(index: Int) = row.getOrNull(index)?.trim().orEmpty()
            val entry = Entry(
                channel = cell(channelColumn),
                networkIds = ids(cell(networkColumn)),
                companyIds = ids(cell(companyColumn))
            )
            if (entry.channel.isEmpty() || (entry.networkIds.isEmpty() && entry.companyIds.isEmpty())) continue
            cell(guideIdsColumn).split(' ', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { guide.putIfAbsent(it, entry) }
            val allNames = listOf(entry.channel) +
                cell(feedsColumn).split('|') +
                cell(altNamesColumn).split('|', ';')
            allNames.map { ChannelBrands.normalize(it) }.filter { it.isNotEmpty() }
                .forEach { names.putIfAbsent(it, entry) }
        }
        byGuideId = guide
        byName = names
    }

    private val number = Regex("""\d+""")

    /** "19" or "19; 1035" -> [19, 1035]. */
    private fun ids(cell: String): List<Int> = number.findAll(cell).mapNotNull { it.value.toIntOrNull() }.toList()

    /** Plain CSV: commas, quoted fields with "" for a quote, CRLF or LF lines. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (quoted) {
                if (c == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    ',' -> {
                        row.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> Unit
                    '\n' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        if (row.any { it.isNotEmpty() }) rows.add(row)
                        row = ArrayList()
                    }
                    else -> field.append(c)
                }
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotEmpty() }) rows.add(row)
        }
        return rows
    }
}

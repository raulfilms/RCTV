package com.nuvio.tv.data.livetv

import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the Live TV player shows beside the picture, from the Guide: its channels and programs
 * (what's on, the "Networks" row) and how to get a channel's playable address. The Guide (and a
 * channel's page) keeps it up to date; the player only reads it.
 */
@Singleton
class LiveTvPlayerSession @Inject constructor() {

    data class Guide(
        val channels: List<LiveTvChannel> = emptyList(),
        /** Programs per EPG channel id, each list sorted by start time. */
        val epgByChannel: Map<String, List<EpgProgram>> = emptyMap(),
        /** The Guide's "Custom" channels (shown first in the player's Networks row). */
        val customChannelIds: Set<String> = emptySet(),
        /** The built-in sample guide: its channels have no stream. */
        val isPreview: Boolean = false
    ) {
        fun programsOf(channel: LiveTvChannel): List<EpgProgram> =
            channel.epgChannelId?.let { epgByChannel[it] }.orEmpty()

        fun programAt(channel: LiveTvChannel, timeMs: Long): EpgProgram? =
            programsOf(channel).firstOrNull { it.isAiringAt(timeMs) }
    }

    private val _guide = MutableStateFlow(Guide())
    val guide: StateFlow<Guide> = _guide.asStateFlow()

    fun publish(guide: Guide) {
        _guide.value = guide
    }

    /** A channel's playable address (Stalker portals hand out short-lived ones); set by the Guide. */
    @Volatile
    var resolver: (suspend (LiveTvChannel) -> String)? = null

    suspend fun playableUrl(channel: LiveTvChannel): String = resolver?.invoke(channel) ?: channel.streamUrl
}

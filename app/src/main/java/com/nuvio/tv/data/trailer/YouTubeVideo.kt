package com.nuvio.tv.data.trailer

import java.net.URI

/**
 * RCTV Smart Player: a YouTube video is known only by its id. RCTV never asks YouTube for the
 * video's own stream addresses: every YouTube video plays in YouTube's official IFrame player
 * ([com.nuvio.tv.ui.components.YouTubeIframePlayer]), which a trailer, a recap or a live game
 * reaches through its watch address.
 */
object YouTubeVideo {

    /** The id's own shape: 11 letters, digits, '-' or '_'. */
    fun isId(value: String?): Boolean =
        value != null && value.length == 11 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }

    /** The address that opens [id] on YouTube (its watch page, or the YouTube app on a TV). */
    fun watchUrl(id: String): String = "https://www.youtube.com/watch?v=$id"

    /**
     * The video id in a YouTube address (watch?v=, youtu.be/, /embed/, /shorts/, /live/, /v/), or
     * the id itself; null for anything else, including every non-YouTube address.
     */
    fun idOf(input: String?): String? {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (isId(text)) return text
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val candidate = when {
            host == "youtu.be" || host.endsWith(".youtu.be") -> path.trim('/').substringBefore('/')
            host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") -> {
                val fromQuery = uri.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("v=") }
                    ?.removePrefix("v=")
                fromQuery ?: listOf("/embed/", "/shorts/", "/live/", "/v/")
                    .firstOrNull { path.startsWith(it) }
                    ?.let { prefix -> path.removePrefix(prefix).substringBefore('/') }
            }
            else -> null
        }
        return candidate?.takeIf { isId(it) }
    }
}

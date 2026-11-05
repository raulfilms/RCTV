package com.nuvio.tv.core.smartscraper

import java.util.Locale

/**
 * RCTV (owner's rule): a movie is the movie, an episode is the episode. A featurette, a making-of,
 * a trailer, a sample, deleted scenes, an interview (often a file inside the movie's own torrent)
 * is never played for it. A word that's in the title itself ("Interview with the Vampire",
 * "Trailer Park Boys") doesn't count.
 */
object ExtraContent {

    private val WORDS = listOf(
        "featurette", "featurettes", "behind the scenes", "making of", "deleted scene", "deleted scenes",
        "blooper", "bloopers", "gag reel", "outtake", "outtakes", "trailer", "trailers", "teaser",
        "sample", "extras", "bonus", "special features", "tv spot", "tv spots", "vfx breakdown",
        "screen test", "screen tests", "interview", "interviews", "promo"
    )

    /** The extra's word in [source]'s names and file name; null when it's the title itself. */
    fun wordIn(source: NormalizedSource, request: SmartSourceRequest): String? {
        val stream = source.stream
        val text = " " + normalize(
            listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename, source.release.releaseName)
                .joinToString(" ")
        ) + " "
        val own = " " + normalize((listOfNotNull(request.title) + request.alternateTitles).joinToString(" ")) + " "
        return WORDS.firstOrNull { word -> text.contains(" $word ") && !own.contains(" $word ") }
    }

    /** "The.Making-of_Interstellar" → "the making of interstellar". */
    internal fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
}

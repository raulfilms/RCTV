package com.nuvio.tv.ui.components

import androidx.annotation.DrawableRes
import com.nuvio.tv.R

/**
 * The genre cards' thumbnails (their name is on the picture), on Home's "Browse by Genre" and on
 * the streaming services' genre rows. A genre without one keeps its colored card with its name.
 */
object GenreThumbnails {

    /** By TMDB genre id (movie and series genres). */
    @DrawableRes
    fun forGenreId(genreId: Int): Int? = when (genreId) {
        28 -> R.drawable.genre_action
        12 -> R.drawable.genre_adventure
        16 -> R.drawable.genre_animation
        35 -> R.drawable.genre_comedy
        80 -> R.drawable.genre_crime
        99 -> R.drawable.genre_documentary
        18 -> R.drawable.genre_drama
        10751 -> R.drawable.genre_family
        14 -> R.drawable.genre_fantasy
        36 -> R.drawable.genre_history
        27 -> R.drawable.genre_horror
        10402 -> R.drawable.genre_music
        9648 -> R.drawable.genre_mystery
        10749 -> R.drawable.genre_romance
        878 -> R.drawable.genre_science_fiction
        10770 -> R.drawable.genre_tv_movie
        53 -> R.drawable.genre_thriller
        10752 -> R.drawable.genre_war
        37 -> R.drawable.genre_western
        // Series-only genres.
        10759 -> R.drawable.genre_action_adventure
        10762 -> R.drawable.genre_kids
        10763 -> R.drawable.genre_news
        10764 -> R.drawable.genre_reality
        10765 -> R.drawable.genre_sci_fi_fantasy
        10766 -> R.drawable.genre_soap
        10767 -> R.drawable.genre_talk
        10768 -> R.drawable.genre_war_politics
        else -> null
    }

    /** By a service page's genre card ("action", "scifi", "documentaries"...). */
    @DrawableRes
    fun forKey(key: String): Int? = when (key.lowercase()) {
        "action" -> R.drawable.genre_action
        "adventure" -> R.drawable.genre_adventure
        "action_adventure" -> R.drawable.genre_action_adventure
        "animation" -> R.drawable.genre_animation
        "comedy" -> R.drawable.genre_comedy
        "crime" -> R.drawable.genre_crime
        "documentary", "documentaries" -> R.drawable.genre_documentary
        "drama" -> R.drawable.genre_drama
        "family" -> R.drawable.genre_family
        "fantasy" -> R.drawable.genre_fantasy
        "history" -> R.drawable.genre_history
        "horror" -> R.drawable.genre_horror
        "kids" -> R.drawable.genre_kids
        "music" -> R.drawable.genre_music
        "mystery" -> R.drawable.genre_mystery
        "news" -> R.drawable.genre_news
        "reality" -> R.drawable.genre_reality
        "romance" -> R.drawable.genre_romance
        "scifi", "science_fiction" -> R.drawable.genre_science_fiction
        "scifi_fantasy" -> R.drawable.genre_sci_fi_fantasy
        "soap" -> R.drawable.genre_soap
        "talk" -> R.drawable.genre_talk
        "thriller" -> R.drawable.genre_thriller
        "tv_movie" -> R.drawable.genre_tv_movie
        "war" -> R.drawable.genre_war
        "war_politics" -> R.drawable.genre_war_politics
        "western" -> R.drawable.genre_western
        else -> null
    }
}

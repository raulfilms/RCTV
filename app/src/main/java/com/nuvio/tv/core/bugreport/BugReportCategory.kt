package com.nuvio.tv.core.bugreport

import androidx.annotation.StringRes
import com.nuvio.tv.R

/**
 * RCTV (owner): what the person says went wrong, from the bug report's list. [key] and [label] go
 * in the report as they are (English, whatever the app's language); [titleRes] is what the list
 * shows. [area] says which details matter most for it (the report always carries all of them).
 */
enum class BugReportCategory(
    val key: String,
    val label: String,
    @StringRes val titleRes: Int,
    val area: Area
) {
    MOVIE_WONT_PLAY("movie_wont_play", "Movie won't play", R.string.bug_report_movie_wont_play, Area.PLAYBACK),
    EPISODE_WONT_PLAY("episode_wont_play", "TV show / episode won't play", R.string.bug_report_episode_wont_play, Area.PLAYBACK),
    LIVE_CHANNEL("live_channel_not_working", "Live TV channel not working", R.string.bug_report_live_channel, Area.LIVE_TV),
    SPORTS_STREAM("sports_stream_not_working", "Sports stream not working", R.string.bug_report_sports_stream, Area.PLAYBACK),
    BUFFERING("buffering_freezing", "Buffering / Freezing", R.string.bug_report_buffering, Area.PLAYBACK),
    NO_AUDIO_VIDEO("no_audio_no_video", "No audio / No video", R.string.bug_report_no_audio_video, Area.PLAYBACK),
    SUBTITLES("subtitles_not_working", "Subtitles not working", R.string.bug_report_subtitles, Area.PLAYBACK),
    WRONG_TITLE("wrong_movie_or_episode", "Wrong movie or episode", R.string.bug_report_wrong_title, Area.PLAYBACK),
    CONTENT_NOT_FOUND("content_not_found", "Content not found", R.string.bug_report_content_not_found, Area.CONTENT),
    APP_SLOW("app_slow", "App is slow", R.string.bug_report_app_slow, Area.APP),
    APP_CRASHED("app_crashed", "App crashed / Closed unexpectedly", R.string.bug_report_app_crashed, Area.APP),
    LOGIN_ACCOUNT("login_account", "Login / Account issue", R.string.bug_report_login_account, Area.ACCOUNT),
    SEARCH("search_not_working", "Search not working", R.string.bug_report_search, Area.CONTENT),
    IMAGES("images_not_loading", "Thumbnails / Posters not loading", R.string.bug_report_images, Area.CONTENT),
    AUDIO_SYNC("audio_out_of_sync", "Audio out of sync", R.string.bug_report_audio_sync, Area.PLAYBACK),
    VIDEO_QUALITY("video_quality", "Video quality issue", R.string.bug_report_video_quality, Area.PLAYBACK),
    PLAYER_CONTROLS("player_controls", "Player controls not working", R.string.bug_report_player_controls, Area.PLAYBACK),
    PLAYBACK_STOPS("playback_stops", "Playback stops unexpectedly", R.string.bug_report_playback_stops, Area.PLAYBACK),
    WRONG_AUDIO_LANGUAGE("wrong_audio_language", "Wrong audio language", R.string.bug_report_wrong_audio_language, Area.PLAYBACK),
    GUIDE("epg_guide_not_working", "EPG / TV Guide not working", R.string.bug_report_guide, Area.LIVE_TV),
    FAVORITES("favorites_not_working", "Favorites not working", R.string.bug_report_favorites, Area.CONTENT),
    WATCH_HISTORY("watch_history_not_updating", "Watch history not updating", R.string.bug_report_watch_history, Area.CONTENT),
    NAVIGATION("navigation_remote", "Navigation / Remote control issue", R.string.bug_report_navigation, Area.APP),
    OTHER("other", "Other issue", R.string.bug_report_other, Area.APP);

    enum class Area { PLAYBACK, LIVE_TV, CONTENT, APP, ACCOUNT }

    companion object {
        fun fromKey(key: String?): BugReportCategory? = entries.firstOrNull { it.key == key }
    }
}

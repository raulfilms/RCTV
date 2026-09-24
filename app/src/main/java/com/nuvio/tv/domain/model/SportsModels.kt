package com.nuvio.tv.domain.model

/** A sport as exposed by TheSportsDB's `strSport` field (e.g. "Soccer", "Basketball"). */
data class Sport(
    val id: String,
    val name: String,
    val iconUrl: String? = null
)

/** A league/competition (e.g. "English Premier League", "NBA"). */
data class SportsLeague(
    val id: String,
    val name: String,
    val sportName: String,
    val badgeUrl: String? = null,
    val country: String? = null
)

/** A team belonging to one or more leagues. */
data class SportsTeam(
    val id: String,
    val name: String,
    val shortName: String? = null,
    val sportName: String? = null,
    val leagueId: String? = null,
    val leagueName: String? = null,
    val badgeUrl: String? = null
)

enum class SportsEventStatus { LIVE, UPCOMING, FINISHED }

/**
 * A single game/match. TheSportsDB's free tier has no real live-score feed, so [status] is
 * derived locally by comparing [startTimeMs] (and a rough estimated duration) against the
 * device clock rather than coming from the API as a genuine live state.
 */
data class SportsEvent(
    val id: String,
    val name: String,
    val sportName: String?,
    val leagueId: String?,
    val leagueName: String?,
    val homeTeamId: String?,
    val homeTeamName: String,
    val homeTeamBadgeUrl: String?,
    val awayTeamId: String?,
    val awayTeamName: String,
    val awayTeamBadgeUrl: String?,
    val homeScore: Int?,
    val awayScore: Int?,
    val startTimeMs: Long?,
    val venue: String?,
    val status: SportsEventStatus,
    /** Free-form progress text when known/estimated, e.g. "4th Quarter", "76'", "Final". */
    val statusDetail: String? = null
)

/** A lightweight "sports talk" article/discussion item. No live API for this yet; curated/mock. */
data class SportsTalkItem(
    val id: String,
    val title: String,
    val summary: String,
    val imageUrl: String? = null,
    val sourceName: String? = null
)

/** An item the user was watching (a game or sports VOD) and can resume. */
data class SportsContinueWatchingItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val progress: Float,
    val event: SportsEvent? = null
)

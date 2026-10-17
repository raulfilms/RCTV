package com.nuvio.tv.domain.model

/** A team page's header: its names, colors, logo, record and standing (ESPN's team endpoint). */
data class SportsTeamProfile(
    val id: String,
    val leagueId: String,
    /** The city or state ("Buffalo", "New Orleans"); a soccer club's whole name ("Real Madrid"). */
    val location: String?,
    /** The team's own name ("Bills", "Saints"). */
    val nickname: String?,
    /** "Buffalo Bills", "Real Madrid". */
    val displayName: String,
    /** "BUF". */
    val abbreviation: String?,
    /** Main color ("00338d", no #). */
    val color: String?,
    val alternateColor: String?,
    val logoUrl: String?,
    /** "13-3"; soccer: wins-draws-losses ("5-0-2"). */
    val recordSummary: String?,
    /** "1st in AFC East", "4th in Spanish LALIGA". */
    val standingSummary: String?,
    /** The team's seed in its conference (NFL, NBA...), when ESPN has it. */
    val playoffSeed: Int?,
    /** League points (soccer). */
    val points: Int?,
    val venueName: String?,
    /** "Orchard Park, NY". */
    val venueCity: String?,
    val venueImageUrl: String?
)

/** One game of a team's season, as its schedule lists it. */
data class SportsTeamGame(
    val event: SportsEvent,
    /** "Week 5", "Wild Card" (NFL and other leagues played in weeks); null otherwise. */
    val weekLabel: String?,
    /** The week's number, when the game is in a numbered week. */
    val weekNumber: Int?,
    /** "Preseason", "Regular Season", "Postseason". */
    val seasonTypeLabel: String?,
    /** ESPN's season type: 1 preseason, 2 regular season, 3 postseason. */
    val seasonType: Int?,
    /** "CBS", "ESPN"... */
    val broadcast: String?,
    /** The players who led each team (by team id), when the schedule has them (games played). */
    val leaders: Map<String, List<SportsAthlete>> = emptyMap()
)

/** A player: on a team's roster, or one of a game's leaders. */
data class SportsAthlete(
    val id: String,
    val name: String,
    val shortName: String? = null,
    val jersey: String? = null,
    /** "QB", "PG", "FW". */
    val position: String? = null,
    /** ESPN's photo of the player (a cut-out on a clear background). */
    val headshotUrl: String? = null,
    val age: Int? = null,
    val height: String? = null,
    val weight: String? = null,
    val experienceYears: Int? = null,
    val college: String? = null,
    /** The roster's group ("Offense", "Defense", "Pitchers"...), when it has groups. */
    val group: String? = null
)

/** One group of a team's season stats ("Passing": Completions 344, Yards 4,120...). */
data class SportsTeamStatCategory(val name: String, val stats: List<SportsStandingStat>)

/** A team's history: Wikipedia's summary of the team, and its picture. */
data class SportsTeamHistory(
    val title: String,
    val summary: String,
    val imageUrl: String?,
    val pageUrl: String?
)

package com.nuvio.tv.domain.model

/**
 * RCTV (game center, as YouTube TV): a live game's information beside the player: its score by
 * period, the teams' stats, the game's leaders, each team's box score and its key plays. Read from
 * ESPN's game summary; every list can be empty (a sport or a game ESPN gives less for).
 */
data class GameCenter(
    val eventId: String,
    val sportPath: String,
    val leaguePath: String,
    val status: SportsEventStatus,
    /** ESPN's own short line ("2nd - 0:21", "Halftime", "Final", "Top 5th"). */
    val statusDetail: String?,
    /** While it's played: the period as the sport says it ("Q2", "P3", "H1"), and its clock ("0:03"). */
    val periodLabel: String?,
    val clock: String?,
    /** The team shown on the left, then the right (US sports: away, home; soccer: home, away). */
    val left: GameCenterTeam,
    val right: GameCenterTeam,
    /** The period columns ("1ST", "2ND"... or "1".."9" in baseball). */
    val periodLabels: List<String>,
    val teamStats: List<GameCenterTeamStat>,
    val leaders: List<GameCenterLeader>,
    /** The left team's first. */
    val boxScores: List<GameCenterBoxScore>,
    /** The newest first. */
    val keyPlays: List<GameCenterPlay>
)

data class GameCenterTeam(
    val id: String?,
    /** As the game shows it ("Vikings"). */
    val name: String,
    /** "MIN". */
    val abbreviation: String,
    val logoUrl: String?,
    /** "4F2683" (no #). */
    val color: String?,
    /** "4-0". */
    val record: String?,
    val score: String?,
    /** Its score in each period so far ("3", "3"); fewer than [GameCenter.periodLabels] until they're played. */
    val periodScores: List<String>
)

/** A stat of both teams ("Passing yards": 143 / 37); [leftShare] is the left team's part of the bar (0 to 1). */
data class GameCenterTeamStat(
    val label: String,
    val leftValue: String,
    val rightValue: String,
    val leftShare: Float
)

/** A leader of the game ("Kyler Murray", "Vikings Passing", Cmp 15 · Yds 143 · TD 0 · Int 0). */
data class GameCenterLeader(
    val name: String,
    val headshotUrl: String?,
    val teamName: String,
    val teamColor: String?,
    val category: String,
    val columns: List<Pair<String, String>>
)

/** A team's box score: its sections (Passing, Rushing, Receiving...), each a small table. */
data class GameCenterBoxScore(
    val team: GameCenterTeam,
    val sections: List<GameCenterBoxSection>
)

data class GameCenterBoxSection(
    val title: String,
    val columns: List<String>,
    val rows: List<GameCenterBoxRow>
)

data class GameCenterBoxRow(
    val jersey: String?,
    /** "K. Murray". */
    val name: String,
    val values: List<String>
)

/** A key play: what it was ("Touchdown"), what happened, when ("Q2 0:21"), and the score after it. */
data class GameCenterPlay(
    val title: String?,
    val text: String,
    val time: String?,
    val teamLogoUrl: String?,
    val teamAbbreviation: String?,
    /** "MIN 6 - NO 7" (left team first). */
    val score: String?
)

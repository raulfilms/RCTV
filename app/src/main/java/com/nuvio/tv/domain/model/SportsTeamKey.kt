package com.nuvio.tv.domain.model

/**
 * RCTV: how a team is kept in "My Teams". A team's number alone isn't enough: the same number is a
 * different team in another league (the Knicks are 18 in the NBA, San Lorenzo is 18 in soccer), so
 * a team is its league and its number ("basketball:nba/18"). Soccer teams keep one number in every
 * competition (Inter Miami in MLS, the Leagues Cup or the CONCACAF), so they're the sport and the
 * number ("soccer/20232").
 *
 * "My Teams" saved before this have the number alone; until they're named again (the Sports page
 * does it, [SportsTeamKey.legacyResolution]) they match any team with that number, as they did.
 */
object SportsTeamKey {
    private const val SOCCER = "soccer"

    fun of(team: SportsTeam): String = of(team.leagueId, team.id)

    fun of(leagueId: String?, teamId: String): String {
        val league = leagueId?.trim()?.takeIf { it.isNotEmpty() } ?: return teamId
        val sport = league.substringBefore(':')
        return if (sport == SOCCER) "$SOCCER/$teamId" else "$league/$teamId"
    }

    /** The team's number in a key ("18" in "basketball:nba/18"). */
    fun teamIdOf(key: String): String = key.substringAfterLast('/')

    /** A key saved before teams had their league (the number alone). */
    fun isLegacy(key: String): Boolean = '/' !in key

    /**
     * The keys for "My Teams" saved with the number alone, as the Sports page names them ([teams]
     * in its order: the first team with that number). Numbers it doesn't have stay as they are.
     */
    fun legacyResolution(favorites: Set<String>, teams: List<SportsTeam>): Map<String, String> {
        val legacy = favorites.filter(::isLegacy).toSet()
        if (legacy.isEmpty()) return emptyMap()
        val resolved = LinkedHashMap<String, String>()
        teams.forEach { team -> if (team.id in legacy && team.id !in resolved) resolved[team.id] = of(team) }
        return resolved
    }
}

/** Whether this team is in "My Teams" (the keys of [SportsTeamKey]). */
fun Set<String>.hasTeam(team: SportsTeam): Boolean = hasTeam(team.leagueId, team.id)

fun Set<String>.hasTeam(leagueId: String?, teamId: String?): Boolean {
    if (teamId.isNullOrBlank()) return false
    return SportsTeamKey.of(leagueId, teamId) in this || teamId in this
}

package com.nuvio.tv.data.remote.espn

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

/** A team as ESPN's teams and groups answers list it (only what the app keeps of it). */
internal data class EspnTeamEntry(
    val id: String,
    val displayName: String?,
    val name: String?,
    val abbreviation: String?,
    val color: String?,
    /** Its `logo`, else the first of its `logos`. */
    val logo: String?
)

/** A group of ESPN's groups answer (a division, a conference): its name, its teams, the groups in it. */
internal class EspnGroupNode(
    val name: String?,
    val teams: List<EspnTeamEntry>,
    val children: List<EspnGroupNode>
)

/**
 * RCTV: ESPN's teams and groups answers read as they come in, keeping only each team's few fields.
 * The NCAA's answers list a thousand teams, each with all its links and logos: read whole into a JSON
 * tree they took tens of megabytes at once, and with the rest of Sports in memory the TV closed the
 * app when Teams opened.
 */
internal object EspnTeamsJson {

    /** `.../teams`: the teams of its first sport's first league (sports[0].leagues[0].teams[].team). */
    fun readTeams(reader: JsonReader): List<EspnTeamEntry> {
        val teams = ArrayList<EspnTeamEntry>()
        fields(reader) { key ->
            if (key != "sports") return@fields reader.skipValue()
            first(reader) {
                fields(reader) { sportKey ->
                    if (sportKey != "leagues") return@fields reader.skipValue()
                    first(reader) {
                        fields(reader) { leagueKey ->
                            if (leagueKey != "teams") return@fields reader.skipValue()
                            each(reader) {
                                fields(reader) { entryKey ->
                                    if (entryKey == "team" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                                        readTeam(reader)?.let(teams::add)
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return teams
    }

    /** `.../groups`: its groups, each with its teams and the groups in it. */
    fun readGroups(reader: JsonReader): List<EspnGroupNode> {
        val groups = ArrayList<EspnGroupNode>()
        fields(reader) { key ->
            if (key == "groups") each(reader) { readGroup(reader)?.let(groups::add) } else reader.skipValue()
        }
        return groups
    }

    private fun readGroup(reader: JsonReader): EspnGroupNode? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var name: String? = null
        val teams = ArrayList<EspnTeamEntry>()
        val children = ArrayList<EspnGroupNode>()
        fields(reader) { key ->
            when (key) {
                "name" -> name = string(reader)
                "teams" -> each(reader) {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) readTeam(reader)?.let(teams::add) else reader.skipValue()
                }
                "children" -> each(reader) { readGroup(reader)?.let(children::add) }
                else -> reader.skipValue()
            }
        }
        return EspnGroupNode(name, teams, children)
    }

    /** A team object; null when it has no id. */
    private fun readTeam(reader: JsonReader): EspnTeamEntry? {
        var id: String? = null
        var displayName: String? = null
        var name: String? = null
        var abbreviation: String? = null
        var color: String? = null
        var logo: String? = null
        var firstLogo: String? = null
        fields(reader) { key ->
            when (key) {
                "id" -> id = string(reader)
                "displayName" -> displayName = string(reader)
                "name" -> name = string(reader)
                "abbreviation" -> abbreviation = string(reader)
                "color" -> color = string(reader)
                "logo" -> logo = string(reader)
                "logos" -> first(reader) {
                    fields(reader) { logoKey ->
                        if (logoKey == "href") firstLogo = string(reader) else reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        val teamId = id ?: return null
        return EspnTeamEntry(teamId, displayName, name, abbreviation, color, logo ?: firstLogo)
    }

    /** A text or number value (null for an empty one, "null", or anything else, which is skipped). */
    private fun string(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString().takeIf { it.isNotBlank() && it != "null" }
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }
        else -> {
            reader.skipValue()
            null
        }
    }

    /** Each field of an object ([onField] reads or skips its value); anything else is skipped. */
    private inline fun fields(reader: JsonReader, onField: (String) -> Unit) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        reader.beginObject()
        while (reader.hasNext()) onField(reader.nextName())
        reader.endObject()
    }

    /** Each value of an array ([onItem] reads or skips it); anything else is skipped. */
    private inline fun each(reader: JsonReader, onItem: () -> Unit) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        while (reader.hasNext()) onItem()
        reader.endArray()
    }

    /** An array's first value ([onFirst] reads it); the rest are skipped. */
    private inline fun first(reader: JsonReader, onFirst: () -> Unit) {
        var index = 0
        each(reader) {
            if (index++ == 0) onFirst() else reader.skipValue()
        }
    }
}

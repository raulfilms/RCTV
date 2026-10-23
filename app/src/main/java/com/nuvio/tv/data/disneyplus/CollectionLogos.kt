package com.nuvio.tv.data.disneyplus

import com.nuvio.tv.domain.model.TmdbCollectionMediaType

/**
 * RCTV: the title whose logo heads a collection's page instead of its name, like the title pages:
 * a film series ("John Wick", "Fast & Furious", "James Bond") or a series' universe ("Dexter").
 */
sealed interface CollectionLogo {
    /** A TMDB franchise collection: its film's logo (the film named like it, else the first one). */
    data class Franchise(val query: String) : CollectionLogo

    /** One series' (or movie's) logo: a Paramount+ universe. */
    data class Title(val title: CuratedTitle) : CollectionLogo
}

object CollectionLogos {

    /**
     * Disney+ and HBO Max collections keep their names: they're the services' own collections, with
     * many titles each (Home's rows are listed under Disney+ but aren't its collections).
     */
    private fun keepsName(hub: DisneyPlusHub): Boolean =
        hub.service == HubService.HBO_MAX || (hub.service == HubService.DISNEY && hub != DisneyPlusHub.HOME)

    /**
     * A list of one film series' movies (Home's Franchises, a service's Collections, a studio's film
     * series): its logo. Null for any other list.
     */
    fun forList(hub: DisneyPlusHub, spec: DisneyRowSpec): CollectionLogo? {
        if (keepsName(hub)) return null
        val franchise = spec.sources.singleOrNull() as? DisneyRowSource.TmdbCollection ?: return null
        return CollectionLogo.Franchise(franchise.query)
    }

    /**
     * A Paramount+ universe of one series or film series: its logo. The universes of a person
     * (Taylor Sheridan, Tyler Perry) or of two film series (A Quiet Place & Scream) keep their names.
     */
    fun forPage(hub: DisneyPlusHub): CollectionLogo? = when (hub) {
        DisneyPlusHub.PU_STAR_TREK -> series("Star Trek", 1966)
        DisneyPlusHub.PU_MISSION_IMPOSSIBLE -> CollectionLogo.Franchise("Mission: Impossible")
        DisneyPlusHub.PU_DEXTER -> series("Dexter", 2006)
        DisneyPlusHub.PU_NCIS -> series("NCIS", 2003)
        DisneyPlusHub.PU_CSI -> series("CSI: Crime Scene Investigation", 2000)
        DisneyPlusHub.PU_CRIMINAL_MINDS -> series("Criminal Minds", 2005)
        DisneyPlusHub.PU_SPONGEBOB -> series("SpongeBob SquarePants", 1999)
        DisneyPlusHub.PU_SOUTH_PARK -> series("South Park", 1997)
        DisneyPlusHub.PU_CHALLENGE -> series("The Challenge", 1998)
        else -> null
    }

    private fun series(name: String, year: Int) =
        CollectionLogo.Title(CuratedTitle(name, year, TmdbCollectionMediaType.TV))
}

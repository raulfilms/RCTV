package com.nuvio.tv.domain.model

/**
 * A live/upcoming event as exposed directly by an installed addon's catalog (detected as
 * sports-related by `SportsAddonCatalogClassifier`), carrying enough to fetch its streams via
 * [com.nuvio.tv.domain.repository.StreamRepository.getStreamsFromAddon].
 *
 * Kept separate from [SportsEvent]: that one comes from TheSportsDB, is metadata/schedule-only
 * (no stream ever attached to it), and is used for the browsing rows (scores, schedules, team
 * badges). This one comes from the user's own installed addons and is what's actually playable -
 * ARVIO's addon-driven sports catalog approach, ported onto RCTV's existing addon system rather
 * than porting ARVIO's own addon layer.
 */
data class SportsAddonEvent(
    val id: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val description: String?,
    val addonId: String,
    val addonName: String,
    val catalogId: String,
    val type: ContentType
)

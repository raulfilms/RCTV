package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.MetaPreview

/**
 * Finds the artwork for horizontal title cards that show the title's name on the picture, like
 * Disney+: a 16:9 backdrop with the title already on it, or at least the title's logo to lay over a
 * plain backdrop.
 */
interface TitleArtResolver {
    /** The title with its art when that's already known (no network call); null when it isn't yet. */
    fun cached(item: MetaPreview): MetaPreview?

    /** The title with its art. Returns the title unchanged when nothing could be found. */
    suspend fun resolve(item: MetaPreview): MetaPreview
}

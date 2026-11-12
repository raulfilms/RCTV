package com.nuvio.tv.domain.model

data class UserProfile(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val usesPrimaryPlugins: Boolean = false,
    val avatarId: String? = null,
    val avatarUrl: String? = null,
    val profileBackgroundId: String? = null,
    val profileBackgroundUrl: String? = null,
    /**
     * RCTV: a Kids profile only shows movies and shows for children up to 13, and only family and
     * kids channels (see KidsContentGate).
     */
    val isKids: Boolean = false
) {
    val isPrimary: Boolean get() = id == 1
}

package com.nuvio.tv.ui.components

import androidx.annotation.StringRes
import com.nuvio.tv.R

/**
 * RCTV's own profile pictures, kept in the app (assets/profile-avatars), in rows like Disney+'s
 * "Choose Avatar": Featured, Characters, Cartoons, Sketches, Cats, Dogs, Monsters, Robots, Emoji,
 * Pixel & Voxel and Space. A profile keeps the one chosen as its avatar address
 * ([urlOf] → "file:///android_asset/profile-avatars/cats/cats_03.webp"), so it shows everywhere the
 * profile's picture does.
 *
 * The pictures are made with DiceBear's avatar styles (CC0 1.0, CC BY 4.0, and Pablo Stanley's
 * Avataaars and Bottts, free for personal and commercial use); the credits are in Settings ›
 * Licenses & Attributions.
 */
object RctvAvatars {
    const val ASSET_ROOT = "file:///android_asset/profile-avatars/"

    /** One row of the avatar gallery. [paths] are "cats/cats_03" (folder/name, no extension). */
    data class AvatarCategory(val id: String, @StringRes val titleRes: Int, val paths: List<String>) {
        val urls: List<String> get() = paths.map(::urlOf)
    }

    fun urlOf(path: String): String = "$ASSET_ROOT$path.webp"

    /** Whether a profile's avatar address is one of these pictures. */
    fun isBundled(url: String?): Boolean = url?.startsWith(ASSET_ROOT) == true

    val categories: List<AvatarCategory> = listOf(
        AvatarCategory(
            id = "featured",
            titleRes = R.string.avatar_category_featured,
            paths = listOf(
            "characters/avataaars_03",
            "cats/cats_03",
            "robots/bottts_01",
            "characters/toon-head_01",
            "monsters/critters_04",
            "cartoon/adventurer_04",
            "dogs/dogs_03",
            "emoji/fun-emoji_04",
            "characters/micah_04",
            "pixel/voxel-art_02",
            "space/planets_05",
            "cartoon/big-smile_03",
            "robots/voxel-bot_02",
            "sketch/lorelei_05"
            )
        ),
        AvatarCategory(
            id = "characters",
            titleRes = R.string.avatar_category_characters,
            paths = listOf(
            "characters/avataaars_01",
            "characters/avataaars_02",
            "characters/avataaars_03",
            "characters/avataaars_04",
            "characters/avataaars_05",
            "characters/avataaars_06",
            "characters/avataaars_07",
            "characters/avataaars_08",
            "characters/avataaars_09",
            "characters/toon-head_01",
            "characters/toon-head_02",
            "characters/toon-head_03",
            "characters/toon-head_04",
            "characters/toon-head_05",
            "characters/toon-head_06",
            "characters/micah_01",
            "characters/micah_02",
            "characters/micah_03",
            "characters/micah_04",
            "characters/micah_05",
            "characters/personas_01",
            "characters/personas_02",
            "characters/personas_03",
            "characters/personas_04",
            "characters/personas_05"
            )
        ),
        AvatarCategory(
            id = "cartoon",
            titleRes = R.string.avatar_category_cartoons,
            paths = listOf(
            "cartoon/adventurer_01",
            "cartoon/adventurer_02",
            "cartoon/adventurer_03",
            "cartoon/adventurer_04",
            "cartoon/adventurer_05",
            "cartoon/adventurer_06",
            "cartoon/big-smile_01",
            "cartoon/big-smile_02",
            "cartoon/big-smile_03",
            "cartoon/big-smile_04",
            "cartoon/big-smile_05",
            "cartoon/big-smile_06",
            "cartoon/open-peeps_01",
            "cartoon/open-peeps_02",
            "cartoon/open-peeps_03",
            "cartoon/open-peeps_04",
            "cartoon/open-peeps_05",
            "cartoon/miniavs_01",
            "cartoon/miniavs_02",
            "cartoon/miniavs_03"
            )
        ),
        AvatarCategory(
            id = "sketch",
            titleRes = R.string.avatar_category_sketches,
            paths = listOf(
            "sketch/lorelei_01",
            "sketch/lorelei_02",
            "sketch/lorelei_03",
            "sketch/lorelei_04",
            "sketch/lorelei_05",
            "sketch/lorelei_06",
            "sketch/notionists_01",
            "sketch/notionists_02",
            "sketch/notionists_03",
            "sketch/notionists_04",
            "sketch/notionists_05",
            "sketch/notionists_06"
            )
        ),
        AvatarCategory(
            id = "cats",
            titleRes = R.string.avatar_category_cats,
            paths = listOf(
            "cats/cats_01",
            "cats/cats_02",
            "cats/cats_03",
            "cats/cats_04",
            "cats/cats_05",
            "cats/cats_06",
            "cats/cats_07",
            "cats/cats_08",
            "cats/cats_09",
            "cats/cats_10",
            "cats/cats_11",
            "cats/cats_12"
            )
        ),
        AvatarCategory(
            id = "dogs",
            titleRes = R.string.avatar_category_dogs,
            paths = listOf(
            "dogs/dogs_01",
            "dogs/dogs_02",
            "dogs/dogs_03",
            "dogs/dogs_04",
            "dogs/dogs_05",
            "dogs/dogs_06",
            "dogs/dogs_07",
            "dogs/dogs_08",
            "dogs/dogs_09",
            "dogs/dogs_10",
            "dogs/dogs_11",
            "dogs/dogs_12"
            )
        ),
        AvatarCategory(
            id = "monsters",
            titleRes = R.string.avatar_category_monsters,
            paths = listOf(
            "monsters/critters_01",
            "monsters/critters_02",
            "monsters/critters_03",
            "monsters/critters_04",
            "monsters/critters_05",
            "monsters/critters_06",
            "monsters/critters_07",
            "monsters/critters_08",
            "monsters/clay_01",
            "monsters/clay_02",
            "monsters/clay_03",
            "monsters/clay_04",
            "monsters/clay_05",
            "monsters/clay_06",
            "monsters/clay_07",
            "monsters/clay_08"
            )
        ),
        AvatarCategory(
            id = "robots",
            titleRes = R.string.avatar_category_robots,
            paths = listOf(
            "robots/bottts_01",
            "robots/bottts_02",
            "robots/bottts_03",
            "robots/bottts_04",
            "robots/bottts_05",
            "robots/bottts_06",
            "robots/bottts_07",
            "robots/bottts_08",
            "robots/voxel-bot_01",
            "robots/voxel-bot_02",
            "robots/voxel-bot_03",
            "robots/voxel-bot_04",
            "robots/voxel-bot_05",
            "robots/voxel-bot_06"
            )
        ),
        AvatarCategory(
            id = "emoji",
            titleRes = R.string.avatar_category_emoji,
            paths = listOf(
            "emoji/fun-emoji_01",
            "emoji/fun-emoji_02",
            "emoji/fun-emoji_03",
            "emoji/fun-emoji_04",
            "emoji/fun-emoji_05",
            "emoji/fun-emoji_06",
            "emoji/fun-emoji_07",
            "emoji/moods_01",
            "emoji/moods_02",
            "emoji/moods_03",
            "emoji/moods_04",
            "emoji/moods_05",
            "emoji/moods_06",
            "emoji/thumbs_01",
            "emoji/thumbs_02",
            "emoji/thumbs_03"
            )
        ),
        AvatarCategory(
            id = "pixel",
            titleRes = R.string.avatar_category_pixel,
            paths = listOf(
            "pixel/pixel-art_01",
            "pixel/pixel-art_02",
            "pixel/pixel-art_03",
            "pixel/pixel-art_04",
            "pixel/pixel-art_05",
            "pixel/pixel-art_06",
            "pixel/pixel-art_07",
            "pixel/voxel-art_01",
            "pixel/voxel-art_02",
            "pixel/voxel-art_03",
            "pixel/voxel-art_04",
            "pixel/voxel-art_05",
            "pixel/voxel-art_06",
            "pixel/voxel-art_07"
            )
        ),
        AvatarCategory(
            id = "space",
            titleRes = R.string.avatar_category_space,
            paths = listOf(
            "space/planets_01",
            "space/planets_02",
            "space/planets_03",
            "space/planets_04",
            "space/planets_05",
            "space/planets_06",
            "space/planets_07",
            "space/planets_08",
            "space/planets_09",
            "space/planets_10"
            )
        )
    )
}

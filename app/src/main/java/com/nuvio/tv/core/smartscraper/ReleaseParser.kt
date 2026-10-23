package com.nuvio.tv.core.smartscraper

import java.util.Calendar
import java.util.Locale

/**
 * Reads what a release is from its name ("Movie.Title.2022.2160p.WEB-DL.DDP5.1.Atmos.H.265") and
 * from what its addon wrote about it: title, year, season/episode or season pack, resolution,
 * source, codec, HDR, audio, channels, Atmos, IMAX, size, languages; each with how sure it is.
 * The many ways releases are named come to the same values ("4K", "UHD", "3840x2160" → 2160p;
 * "EAC3", "DDP", "DD+" → Dolby Digital Plus). What isn't there stays UNKNOWN: nothing is guessed.
 */
object ReleaseParser {

    /** What a stream says about itself. */
    data class Input(
        /** File and torrent names, the playable file's first, then its pack's. */
        val names: List<String>,
        /** Everything else its addon wrote (its name, title, description, quality). */
        val text: String = "",
        /** The file's size from the addon's data (not from text), when given. */
        val sizeBytes: Long? = null,
        /** The picture's height from the addon's data, when given. */
        val qualityHeight: Int? = null,
        /** Season and episode from the addon's data, when given. */
        val season: Int? = null,
        val episode: Int? = null
    )

    fun parse(input: Input): ParsedRelease {
        val names = input.names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val cleanNames = names.map(::cleanName).filter { it.isNotBlank() }
        val spacedNames = cleanNames.map(::spaced)
        val namesLower = cleanNames.joinToString("\n") { it.lowercase(Locale.ROOT) }
        val textLower = input.text.lowercase(Locale.ROOT)

        // ---------------------------------------------------------------- Title, year, episodes
        // The title from the first name that reads like a release (a file called "movie.mkv" doesn't).
        val namesRead = spacedNames.filter { it.isNotBlank() }.map(::titleAndYear)
        val titleYear = namesRead.firstOrNull { it.fromRelease && it.title != null } ?: namesRead.firstOrNull()
        val episodeInfo = episodeInfo(spacedNames, input.season, input.episode)
        val packYear = if (titleYear?.year == null) namesRead.firstNotNullOfOrNull { it.year } else null
        val packTitle = if (titleYear?.title == null) namesRead.firstNotNullOfOrNull { it.title } else null

        // ---------------------------------------------------------------- Picture
        val resolution: Pair<VideoResolution, Float> = when {
            (input.qualityHeight ?: 0) > 0 -> VideoResolution.ofHeight(input.qualityHeight!!) to 1f
            else -> resolutionIn(namesLower)?.let { it to 0.9f }
                ?: resolutionIn(textLower)?.let { it to 0.85f }
                ?: (if (SD_HINT.containsMatchIn(namesLower)) VideoResolution.SD to 0.6f else VideoResolution.UNKNOWN to 0f)
        }
        val source = sourceIn(namesLower)?.let { it to 0.9f }
            ?: sourceIn(textLower, original = input.text)?.let { it to 0.75f }
            ?: (ReleaseSource.UNKNOWN to 0f)
        val codec = codecIn(namesLower)?.let { it to 0.9f }
            ?: codecIn(textLower)?.let { it to 0.8f }
            ?: (VideoCodec.UNKNOWN to 0f)
        val hdrNames = hdrIn(namesLower)
        val hdr = if (hdrNames.isNotEmpty()) hdrNames to 0.9f else hdrIn(textLower).let { it to if (it.isEmpty()) 0f else 0.8f }

        // ---------------------------------------------------------------- Sound
        val audioNames = audioCodecIn(namesLower)
        val audio = audioNames?.let { it to 0.9f } ?: audioCodecIn(textLower)?.let { it to 0.8f } ?: (AudioCodec.UNKNOWN to 0f)
        val channelsNames = channelsIn(namesLower)
        val channels = channelsNames?.let { it to 0.9f } ?: channelsIn(textLower)?.let { it to 0.8f } ?: (AudioChannels.UNKNOWN to 0f)
        val atmos = ATMOS.containsMatchIn(namesLower) || ATMOS.containsMatchIn(textLower)

        // ---------------------------------------------------------------- Size
        val textSize = sizeIn(input.text)
        val size: Pair<Long?, Float> = when {
            (input.sizeBytes ?: 0L) > 0L -> input.sizeBytes to 1f
            textSize != null -> textSize to 0.8f
            else -> null to 0f
        }

        val all = namesLower + "\n" + textLower
        return ParsedRelease(
            releaseName = names.firstOrNull(),
            title = titleYear?.title ?: packTitle,
            year = titleYear?.year ?: packYear,
            seasons = episodeInfo.seasons,
            episodes = episodeInfo.episodes,
            isSeasonPack = episodeInfo.isPack,
            resolution = resolution.first,
            source = source.first,
            codec = codec.first,
            hdr = hdr.first,
            audioCodec = audio.first,
            channels = channels.first,
            atmos = atmos,
            imax = IMAX.containsMatchIn(all),
            is3d = THREE_D.containsMatchIn(namesLower),
            tenBit = TEN_BIT.containsMatchIn(all),
            sizeBytes = size.first,
            sizeIsPack = episodeInfo.isPack && size.first != null,
            languages = languagesIn(names.joinToString(" ") + " " + input.text),
            confidence = FieldConfidence(
                title = when {
                    titleYear?.title == null && packTitle == null -> 0f
                    titleYear?.fromRelease == true -> 0.9f
                    else -> 0.6f
                },
                year = if ((titleYear?.year ?: packYear) != null) 0.9f else 0f,
                episode = episodeInfo.confidence,
                resolution = resolution.second,
                source = source.second,
                codec = codec.second,
                hdr = hdr.second,
                audio = if (audio.first != AudioCodec.UNKNOWN) audio.second else if (atmos) 0.7f else 0f,
                channels = channels.second,
                size = size.second
            )
        )
    }

    /** A single release name (a file's or a torrent's), nothing else. */
    fun parseName(name: String): ParsedRelease = parse(Input(names = listOf(name)))

    // ==================================================================== Names

    /** "www.Site.org - [YTS.MX] The.Batman.2022.2160p.mkv" → "The.Batman.2022.2160p". */
    fun cleanName(name: String): String {
        var base = name.trim().substringAfterLast('/').substringAfterLast('\\')
        // Emoji and marks some addons put before a file's name ("📁 The.Batman...").
        base = base.replace(LEADING_MARKS, "")
        base = base.replace(EXTENSION, "")
        base = base.replace(WEBSITE_PREFIX, "")
        base = base.replace(LEADING_TAG, "")
        return base.trim()
    }

    /** "The.Batman.2022.2160p.WEB-DL" → "the batman 2022 2160p web-dl" (dots, underscores and brackets as spaces). */
    fun spaced(name: String): String =
        name.lowercase(Locale.ROOT)
            .replace(SEPARATORS, " ")
            .replace(BRACKET_CHARS, " ")
            .replace(SPACES, " ")
            .trim()

    /** Whether a line of an addon's text looks like a release's name (not a label like "💾 7.4 GB"). */
    fun looksLikeReleaseName(line: String): Boolean {
        val clean = cleanName(line)
        if (clean.length !in 6..260) return false
        if (!clean.any { it.isLetter() }) return false
        val lower = clean.lowercase(Locale.ROOT)
        val separators = clean.count { it == '.' || it == '_' }
        val hasTag = STRONG_TAG.containsMatchIn(spaced(clean))
        val hasYear = YEAR.containsMatchIn(lower)
        val hasEpisode = SXXEYY.containsMatchIn(spaced(clean))
        return (separators >= 2 && (hasTag || hasYear || hasEpisode)) || (hasTag && (hasYear || hasEpisode))
    }

    private data class TitleYear(val title: String?, val year: Int?, val fromRelease: Boolean)

    /**
     * The title and year of a spaced release name: the title is what comes before its year (the
     * last year before its tags: "blade runner 2049 2017 2160p" is "blade runner 2049" of 2017), or
     * before its season/episode or first tag when it has no year.
     */
    private fun titleAndYear(spacedName: String): TitleYear {
        val tagStart = STRONG_TAG.find(spacedName)?.range?.first
        val episodeStart = (SXXEYY.find(spacedName) ?: SEASON_WORD.find(spacedName) ?: SEASON_SHORT.find(spacedName))
            ?.range?.first
        val searchEnd = listOfNotNull(tagStart, episodeStart).minOrNull() ?: spacedName.length
        val maxYear = Calendar.getInstance().get(Calendar.YEAR) + 1
        val year = YEAR.findAll(spacedName)
            .filter { it.range.first > 0 && it.range.first < searchEnd }
            .filter { it.value.toInt() in 1900..maxYear }
            .lastOrNull()
        val end = year?.range?.first ?: searchEnd
        val title = spacedName.substring(0, end.coerceAtMost(spacedName.length))
            .trim(' ', '-', '–', ':', '(', ',', '+')
            .takeIf { it.isNotBlank() && it.any(Char::isLetterOrDigit) }
        return TitleYear(
            title = title,
            year = year?.value?.toIntOrNull(),
            fromRelease = year != null || tagStart != null || episodeStart != null
        )
    }

    // ==================================================================== Seasons and episodes

    private data class EpisodeInfo(
        val seasons: List<Int>,
        val episodes: List<Int>,
        val isPack: Boolean,
        val confidence: Float
    )

    private fun episodeInfo(spacedNames: List<String>, season: Int?, episode: Int?): EpisodeInfo {
        if (season != null && episode != null) return EpisodeInfo(listOf(season), listOf(episode), false, 1f)
        // The playable file first: its episode is what plays (even inside a whole season's pack).
        for (name in spacedNames) {
            episodesIn(name)?.let { (s, eps) ->
                return EpisodeInfo(listOfNotNull(s), eps, false, if (s != null) 0.9f else 0.6f)
            }
        }
        for (name in spacedNames) {
            packSeasonsIn(name)?.let { seasons -> return EpisodeInfo(seasons, emptyList(), true, 0.8f) }
        }
        return EpisodeInfo(emptyList(), emptyList(), false, 0f)
    }

    /** The season and episodes a name is ("s01e01e02", "s01e01-03", "1x05", "cap 105"); null when none. */
    private fun episodesIn(spacedName: String): Pair<Int?, List<Int>>? {
        SXXEYY.find(spacedName)?.let { match ->
            val season = match.groupValues[1].toInt()
            val first = match.groupValues[2].toInt()
            val rest = spacedName.substring(match.range.last + 1)
            val more = MORE_EPISODES.find(rest)
            val episodes = if (more != null && more.range.first == 0) {
                val last = more.groupValues[2].toInt()
                if (more.groupValues[1].contains('-') && last > first && last - first < 40) (first..last).toList()
                else listOf(first, last).distinct()
            } else {
                listOf(first)
            }
            return season to episodes
        }
        CROSS_EPISODE.find(spacedName)?.let { match ->
            return match.groupValues[1].toInt() to listOf(match.groupValues[2].toInt())
        }
        SPANISH_CHAPTER.find(spacedName)?.let { match ->
            val digits = match.groupValues[1]
            val season = digits.dropLast(2).toIntOrNull() ?: return@let
            return season to listOf(digits.takeLast(2).toInt())
        }
        EPISODE_WORD.find(spacedName)?.let { match ->
            return null to listOf(match.groupValues[1].toInt())
        }
        return null
    }

    /** The seasons a whole-season pack has ("season 5", "s01-s05", "temporada 2", "complete"); null when it isn't one. */
    private fun packSeasonsIn(spacedName: String): List<Int>? {
        val word = SEASON_WORD.find(spacedName)
        val short = SEASON_SHORT.find(spacedName)
        val match = word ?: short
        if (match != null) {
            val from = match.groupValues[1].toIntOrNull() ?: return null
            val to = match.groupValues[2].toIntOrNull()
            return if (to != null && to > from && to - from < 50) (from..to).toList() else listOf(from)
        }
        if (COMPLETE.containsMatchIn(spacedName)) return emptyList()
        return null
    }

    // ==================================================================== Picture

    private fun resolutionIn(text: String): VideoResolution? {
        val found = buildList {
            if (RES_2160.containsMatchIn(text)) add(VideoResolution.UHD_2160)
            if (RES_1440.containsMatchIn(text)) add(VideoResolution.QHD_1440)
            if (RES_1080.containsMatchIn(text)) add(VideoResolution.FHD_1080)
            if (RES_720.containsMatchIn(text)) add(VideoResolution.HD_720)
            if (RES_SD.containsMatchIn(text)) add(VideoResolution.SD)
            if (RES_LOW.containsMatchIn(text)) add(VideoResolution.LOW)
        }
        return found.maxByOrNull { it.level }
    }

    /**
     * [original]: an addon's own words (not a release name), where the short tags only count in
     * capitals ("TS", "CAM": not the "ts" or "cam" of other words).
     */
    private fun sourceIn(text: String, original: String? = null): ReleaseSource? = when {
        (if (original != null) CAM_STRICT.containsMatchIn(original) else CAM.containsMatchIn(text)) -> ReleaseSource.CAM
        (if (original != null) TELESYNC_STRICT.containsMatchIn(original) else TELESYNC.containsMatchIn(text)) -> ReleaseSource.TELESYNC
        (if (original != null) TELECINE_STRICT.containsMatchIn(original) else TELECINE.containsMatchIn(text)) -> ReleaseSource.TELECINE
        (if (original != null) SCREENER_STRICT.containsMatchIn(original) else SCREENER.containsMatchIn(text)) -> ReleaseSource.SCREENER
        REMUX.containsMatchIn(text) -> ReleaseSource.REMUX
        BLURAY.containsMatchIn(text) -> ReleaseSource.BLURAY
        WEBRIP.containsMatchIn(text) -> ReleaseSource.WEBRIP
        WEB_DL.containsMatchIn(text) -> ReleaseSource.WEB_DL
        HDTV.containsMatchIn(text) -> ReleaseSource.HDTV
        DVD.containsMatchIn(text) -> ReleaseSource.DVD
        else -> null
    }

    private fun codecIn(text: String): VideoCodec? {
        val found = listOfNotNull(
            HEVC.find(text)?.let { it.range.first to VideoCodec.HEVC },
            AV1.find(text)?.let { it.range.first to VideoCodec.AV1 },
            AVC.find(text)?.let { it.range.first to VideoCodec.AVC },
            VP9.find(text)?.let { it.range.first to VideoCodec.VP9 },
            LEGACY_CODEC.find(text)?.let { it.range.first to VideoCodec.LEGACY }
        )
        return found.minByOrNull { it.first }?.second
    }

    private fun hdrIn(text: String): Set<HdrFormat> = buildSet {
        if (DOLBY_VISION.containsMatchIn(text)) add(HdrFormat.DOLBY_VISION)
        if (HDR10_PLUS.containsMatchIn(text)) add(HdrFormat.HDR10_PLUS)
        if (HDR10.containsMatchIn(text)) add(HdrFormat.HDR10)
        if (HDR_PLAIN.containsMatchIn(text) && none { it == HdrFormat.HDR10 || it == HdrFormat.HDR10_PLUS }) add(HdrFormat.HDR)
        if (HLG.containsMatchIn(text)) add(HdrFormat.HLG)
        if (isEmpty() && SDR.containsMatchIn(text)) add(HdrFormat.SDR)
    }

    // ==================================================================== Sound

    /** The best audio format named (a release with "TrueHD Atmos" and "AC3" is TrueHD). */
    private fun audioCodecIn(text: String): AudioCodec? {
        val found = buildList {
            if (TRUEHD.containsMatchIn(text)) add(AudioCodec.TRUEHD)
            if (DTS_X.containsMatchIn(text)) add(AudioCodec.DTS_X)
            if (DTS_HD_MA.containsMatchIn(text)) add(AudioCodec.DTS_HD_MA)
            if (DTS_HD.containsMatchIn(text)) add(AudioCodec.DTS_HD)
            if (DDP.containsMatchIn(text)) add(AudioCodec.DDP)
            if (DTS.containsMatchIn(text)) add(AudioCodec.DTS)
            if (DD.containsMatchIn(text)) add(AudioCodec.DD)
            if (LPCM.containsMatchIn(text)) add(AudioCodec.LPCM)
            if (FLAC.containsMatchIn(text)) add(AudioCodec.FLAC)
            if (AAC.containsMatchIn(text)) add(AudioCodec.AAC)
            if (OPUS.containsMatchIn(text)) add(AudioCodec.OPUS)
            if (MP3.containsMatchIn(text)) add(AudioCodec.MP3)
        }
        return found.minByOrNull { it.ordinal }
    }

    /** The most channels named ("7.1", "DDP5.1", "6ch", "stereo"), never a size ("7.1 GB"). */
    private fun channelsIn(text: String): AudioChannels? {
        val found = buildList {
            CHANNELS.findAll(text).forEach { match ->
                when ("${match.groupValues[1]}.${match.groupValues[2]}") {
                    "7.1" -> add(AudioChannels.CH_7_1)
                    "5.1" -> add(AudioChannels.CH_5_1)
                    "2.0" -> add(AudioChannels.CH_2_0)
                    "1.0" -> add(AudioChannels.CH_1_0)
                }
            }
            CHANNEL_COUNT.findAll(text).forEach { match ->
                when (match.groupValues[1]) {
                    "8" -> add(AudioChannels.CH_7_1)
                    "6" -> add(AudioChannels.CH_5_1)
                    "2" -> add(AudioChannels.CH_2_0)
                    "1" -> add(AudioChannels.CH_1_0)
                }
            }
            if (STEREO.containsMatchIn(text)) add(AudioChannels.CH_2_0)
            if (MONO.containsMatchIn(text)) add(AudioChannels.CH_1_0)
        }
        return found.maxByOrNull { it.count }
    }

    // ==================================================================== Size, languages

    /** "7.4 GB", "980 MB", "1,2 GiB" → bytes (the first one written). */
    fun sizeIn(text: String): Long? {
        val match = SIZE.find(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val multiplier = when {
            unit.startsWith("t") -> 1024.0 * 1024 * 1024 * 1024
            unit.startsWith("g") -> 1024.0 * 1024 * 1024
            else -> 1024.0 * 1024
        }
        return (value * multiplier).toLong().takeIf { it > 0 }
    }

    /** The flags a stream lists for its audio and the Spanish words for it ("Latino", "Castellano"). */
    fun languagesIn(text: String): List<String> {
        val flags = FLAG.findAll(text).map { it.value }.distinct().take(4).toList()
        val words = LANGUAGE_WORDS.mapNotNull { (pattern, label) -> label.takeIf { pattern.containsMatchIn(text) } }.distinct()
        return flags + words
    }

    // ==================================================================== Patterns

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /** Not a letter or digit before / after. */
    private const val B = "(?<![a-z0-9])"
    private const val E = "(?![a-z0-9])"

    private val LEADING_MARKS = Regex("^[^\\p{L}\\p{N}\\[(]+")
    private val EXTENSION = rx("\\.(mkv|mp4|avi|m4v|mov|ts|m2ts|wmv|webm|iso|mpg|mpeg)$")
    private val WEBSITE_PREFIX = rx("^\\s*(?:www\\.)?[a-z0-9-]+\\.(?:com|org|net|io|to|mx|se|cc|xyz|tv|me|lol|ws|li)\\s*[-–]\\s*")
    private val LEADING_TAG = Regex("^\\s*\\[[^\\]]{1,40}\\]\\s*")
    private val SEPARATORS = Regex("[._]+")
    private val BRACKET_CHARS = Regex("[\\[\\]{}()]")
    private val SPACES = Regex("\\s+")

    private val YEAR = Regex("(?<![0-9])(19\\d{2}|20\\d{2})(?![0-9])")
    private val STRONG_TAG = rx(
        "$B(2160[pi]|1440p|1080[pi]|720p|576p|480p|4k|uhd|blu ?-?ray|bdrip|brrip|bdremux|web ?-?dl|webdl|webrip|web ?-?rip|hdtv|remux|x ?26[45]|h ?26[45]|hevc|xvid|hdrip|dvdrip|cam ?rip|hdcam|telesync|hdts)$E"
    )
    private val SXXEYY = rx("${B}s(\\d{1,2}) ?e(\\d{1,3})(?![0-9])")
    private val MORE_EPISODES = rx("^( ?- ?e?| ?e)(\\d{1,3})(?![0-9])")
    private val CROSS_EPISODE = rx("(?<![0-9a-z])(\\d{1,2})x(\\d{2,3})(?![0-9])")
    private val SPANISH_CHAPTER = rx("${B}cap(?:itulo|ítulo)? ?(\\d{3,4})(?![0-9])")
    private val EPISODE_WORD = rx("${B}(?:episode|episodio|ep)\\.? ?(\\d{1,3})(?![0-9])")
    private val SEASON_WORD = rx("${B}(?:season|seasons|temporada|temporadas|saison|staffel) ?(\\d{1,2})(?: ?(?:-|to|a|al|y) ?(\\d{1,2}))?(?![0-9])")
    private val SEASON_SHORT = rx("${B}s(\\d{1,2})(?: ?- ?s?(\\d{1,2}))?(?![a-z0-9])(?! ?e\\d)")
    private val COMPLETE = rx("${B}(complete series|complete|all seasons|serie completa|temporada completa|integrale)$E")

    private val RES_2160 = rx("$B(2160[pi]?|4k|uhd|3840 ?x ?2160|4096 ?x ?2160)$E")
    private val RES_1440 = rx("$B(1440[pi]?|2k|2560 ?x ?1440)$E")
    private val RES_1080 = rx("$B(1080[pi]|fhd|full ?hd|1920 ?x ?1080)$E")
    private val RES_720 = rx("$B(720[pi]|1280 ?x ?720)$E")
    private val RES_SD = rx("$B(480[pi]|576[pi]|sd)$E")
    private val RES_LOW = rx("$B(360[pi]|240[pi])$E")
    private val SD_HINT = rx("$B(dvd ?rip|xvid|divx|vhs ?rip)$E")

    private val CAM = rx("$B(cam ?rip|hd ?cam|hq ?cam|cam)$E")
    private val TELESYNC = rx("$B(telesync|hd ?ts|ts ?rip|pdvd|ts)$E")
    private val TELECINE = rx("$B(telecine|hd ?tc|tc)$E")
    private val SCREENER = rx("$B(dvd ?scr|bd ?scr|web ?scr|screener|scr)$E")
    private val CAM_STRICT = Regex("(?<![A-Za-z0-9])(CAM|HDCAM|HQCAM|CAMRip|CamRip|[Cc]am[Rr]ip)(?![A-Za-z0-9])")
    private val TELESYNC_STRICT = Regex("(?<![A-Za-z0-9])(TS|HDTS|TSRip|TELESYNC|[Tt]elesync)(?![A-Za-z0-9])")
    private val TELECINE_STRICT = Regex("(?<![A-Za-z0-9])(TC|HDTC|TELECINE|[Tt]elecine)(?![A-Za-z0-9])")
    private val SCREENER_STRICT = Regex("(?<![A-Za-z0-9])(SCR|DVDSCR|DVDScr|BDSCR|WEBSCR|SCREENER|[Ss]creener)(?![A-Za-z0-9])")
    private val REMUX = rx("$B(remux|bd ?remux)$E")
    private val BLURAY = rx("$B(blu ?-?ray|bluray|bdrip|brrip|bd ?rip|bd25|bd50|bdmv|uhd ?bd)$E")
    private val WEBRIP = rx("$B(web ?-?rip)$E")
    private val WEB_DL = rx("$B(web ?-?dl|webdl|web|amzn|nf|dsnp|hmax|atvp|hulu|pcok)$E")
    private val HDTV = rx("$B(hdtv|pdtv|dsr|tv ?rip|sat ?rip)$E")
    private val DVD = rx("$B(dvd ?rip|dvd ?5|dvd ?9|dvd)$E")

    private val HEVC = rx("$B(x ?265|h ?\\.? ?265|hevc)$E")
    private val AV1 = rx("${B}av1$E")
    private val AVC = rx("$B(x ?264|h ?\\.? ?264|avc)$E")
    private val VP9 = rx("${B}vp9$E")
    private val LEGACY_CODEC = rx("$B(xvid|divx|mpeg ?-?2)$E")
    private val TEN_BIT = rx("$B(10 ?-?bits?|hi10p?)$E")

    private val DOLBY_VISION = rx("$B(dolby ?vision|dovi|dv)$E")
    private val HDR10_PLUS = rx("${B}hdr ?10 ?(\\+|plus)")
    private val HDR10 = rx("${B}hdr ?10(?! ?(\\+|plus))(?![0-9])")
    private val HDR_PLAIN = rx("${B}hdr$E")
    private val HLG = rx("${B}hlg$E")
    private val SDR = rx("${B}sdr$E")

    private val TRUEHD = rx("${B}true ?-?hd$E")
    private val DTS_X = rx("${B}dts ?[:\\-.]? ?x(?![a-z0-9])")
    private val DTS_HD_MA = rx("$B(dts ?-?hd ?[.\\- ]? ?ma|dts ?-?hd ?master|dts ?-?ma)(?![a-z])")
    private val DTS_HD = rx("${B}dts ?-?hd(?![a-z])")
    private val DDP = rx("$B(ddp|dd ?\\+|ddpa|e ?-?ac ?-?3|eac3|dolby ?digital ?plus)(?![a-z])")
    private val DTS = rx("${B}dts(?![a-z])")
    private val DD = rx("$B(dd|ac ?-?3|dolby ?digital)(?![a-z+])")
    private val LPCM = rx("$B(l?pcm)$E")
    private val FLAC = rx("${B}flac(?![a-z])")
    private val AAC = rx("${B}aac(?![a-z])")
    private val OPUS = rx("${B}opus$E")
    private val MP3 = rx("${B}mp3$E")
    private val ATMOS = rx("$B(atmos|ddpa)(?![a-z])")
    private val CHANNELS = rx("(?<![0-9])(?<![0-9][.,])([7521])[ .]([01])(?![0-9])(?!\\s?(?:gb|gib|mb|mib|tb|g|m)(?![a-z]))(?!\\s?/\\s?10)")
    private val CHANNEL_COUNT = rx("(?<![0-9])([8621]) ?ch(?:annels?)?$E")
    private val STEREO = rx("${B}(stereo|estereo|estéreo)$E")
    private val MONO = rx("${B}mono$E")

    private val IMAX = rx("${B}imax$E")
    private val THREE_D = rx("$B(3d|half ?-?sbs|h ?-?sbs|h ?-?ou|sbs)$E")
    private val SIZE = rx("(\\d+(?:[.,]\\d+)?)\\s?(tib|tb|gib|gb|mib|mb)(?![a-z])")
    /** A flag: two regional-indicator letters. */
    private val FLAG = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")
    private val LANGUAGE_WORDS = listOf(
        rx("${B}latino$E") to "Latino",
        rx("${B}castellano$E") to "Castellano",
        rx("$B(dual|multi)(\\s?audio)?$E") to "Multi"
    )
}

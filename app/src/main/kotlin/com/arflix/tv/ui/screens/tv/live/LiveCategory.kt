package com.arflix.tv.ui.screens.tv.live

import androidx.compose.ui.graphics.Color
import com.arflix.tv.data.model.IptvChannel

/** Broad channel genre derived from M3U group name. */
enum class Genre {
    Sports, Movies, Series, News, Kids, Music, Docs, General;
}

/** Picture quality tier derived from channel name / group. */
enum class Quality(val label: String) {
    SD("SD"), HD("HD"), FHD("FHD"), K4("4K");
}

/**
 * Enriched channel — wraps [IptvChannel] with fields parsed from its
 * M3U metadata so the spec-level sidebar / EPG / badges can render.
 *
 * We never mutate [IptvChannel]; all core IPTV code keeps working.
 */
data class EnrichedChannel(
    val source: IptvChannel,
    val number: Int,
    val country: String?, // ISO-ish 2-letter, uppercase, or null if unresolved
    val genre: Genre,
    val quality: Quality,
    val lang: String,     // 2-letter uppercase language hint
    val brandBg: Color,
    val brandFg: Color,
    val isAdult: Boolean,
) {
    val id: String get() = source.id
    val name: String get() = source.name
    val streamUrl: String get() = source.streamUrl
    val logo: String? get() = source.logo
}

private val ADULT_KEYWORDS = listOf("adult", "xxx", "18+", "erot", "nsfw")
private val TAG_RE = Regex("""[|\-:/,]+""")
private val TRIM_PUNCT = Regex("""^[\s\-|:•\u2022]+|[\s\-|:•\u2022]+$""")

// Bucketing codes. Real ISO countries plus language prefixes commonly used in
// IPTV playlists (EN, JA, KO, ZH, AR, SV, DA, EL, CS, HI, HE, FA) so groups
// like "EN | CHRISTMAS 1 4K" resolve instead of falling into the null bucket.
private val KNOWN_COUNTRIES = setOf(
    // ISO 3166-1 alpha-2 plus the UK/GB, USA aliases
    "NL", "UK", "GB", "US", "USA", "DE", "FR", "IT", "ES", "PT",
    "BE", "TR", "AR", "IN", "BR", "PL", "EX", "SE", "DK", "NO",
    "FI", "RU", "GR", "RO", "HU", "CZ", "AT", "CH", "IE", "JP",
    "KR", "CN", "TW", "HK", "MX", "CA", "AU", "NZ", "ZA", "AE",
    "SA", "EG", "MA", "UA", "BG", "HR", "RS", "SK", "SI", "LT",
    "LV", "EE", "IL",
    // Language codes used as playlist buckets
    "EN", "JA", "KO", "ZH", "SV", "DA", "EL", "CS", "HI", "HE",
    "FA", "AF",
)

private val COUNTRY_ALIASES = mapOf("GB" to "UK", "USA" to "US")

// When rendering a flag for a language/bucket code that isn't itself a valid
// regional-indicator country, substitute the most-representative country flag.
// "EN" → UK flag, "JA" → JP, etc. Keeps sidebar labels honest ("EN") while
// producing a recognisable flag glyph.
private val FLAG_SUBSTITUTES = mapOf(
    "EN" to "GB",
    "JA" to "JP",
    "KO" to "KR",
    "ZH" to "CN",
    "SV" to "SE",
    "DA" to "DK",
    "EL" to "GR",
    "CS" to "CZ",
    "HI" to "IN",
    "HE" to "IL",
    "FA" to "IR",
    "AR" to "SA",
    "AF" to "ZA",
)

fun countryFlag(code: String?): String {
    if (code.isNullOrBlank() || code.length != 2) return "🌐"
    val effective = FLAG_SUBSTITUTES[code.uppercase()] ?: code.uppercase()
    return effective.map { ch ->
        val off = 0x1F1E6 + (ch.code - 'A'.code)
        String(Character.toChars(off))
    }.joinToString("")
}

/** Human-readable country / language-bucket name. Matches the mockup
 *  ("NL Netherlands", "US United States", …). */
private val COUNTRY_NAMES = mapOf(
    "NL" to "Netherlands", "UK" to "UK", "GB" to "UK",
    "US" to "USA", "DE" to "Germany", "FR" to "France",
    "IT" to "Italy", "ES" to "Spain", "PT" to "Portugal", "BE" to "Belgium",
    "TR" to "Turkey", "IN" to "India", "BR" to "Brazil", "PL" to "Poland",
    "SE" to "Sweden", "DK" to "Denmark", "NO" to "Norway", "FI" to "Finland",
    "RU" to "Russia", "GR" to "Greece", "RO" to "Romania", "HU" to "Hungary",
    "CZ" to "Czechia", "AT" to "Austria", "CH" to "Switzerland",
    "IE" to "Ireland", "JP" to "Japan", "KR" to "South Korea", "CN" to "China",
    "TW" to "Taiwan", "HK" to "Hong Kong", "MX" to "Mexico", "CA" to "Canada",
    "AU" to "Australia", "NZ" to "New Zealand", "ZA" to "South Africa",
    "AE" to "UAE", "SA" to "Saudi Arabia", "EG" to "Egypt", "MA" to "Morocco",
    "UA" to "Ukraine", "BG" to "Bulgaria", "HR" to "Croatia", "RS" to "Serbia",
    "SK" to "Slovakia", "SI" to "Slovenia", "LT" to "Lithuania",
    "LV" to "Latvia", "EE" to "Estonia", "IL" to "Israel",
    // Language buckets
    "EN" to "English", "JA" to "Japanese", "KO" to "Korean", "ZH" to "Chinese",
    "AR" to "Arabic", "SV" to "Swedish", "DA" to "Danish", "EL" to "Greek",
    "CS" to "Czech", "HI" to "Hindi", "HE" to "Hebrew", "FA" to "Persian",
    "AF" to "Afrikaans",
)

fun countryName(code: String): String = COUNTRY_NAMES[code.uppercase()] ?: code

/** Parse a genre out of any slice of text (group name, channel name, etc). */
fun genreFromText(text: String): Genre {
    val t = text.lowercase()
    return when {
        "sport" in t || "espn" in t || "uefa" in t -> Genre.Sports
        "movie" in t || "cinema" in t || "film" in t -> Genre.Movies
        "series" in t || "show" in t -> Genre.Series
        "news" in t || "cnn" in t || "bbc news" in t -> Genre.News
        "kids" in t || "cartoon" in t || "child" in t || "family" in t -> Genre.Kids
        "music" in t || "mtv" in t || "hits" in t -> Genre.Music
        "doc" in t || "history" in t || "discovery" in t || "nat geo" in t -> Genre.Docs
        else -> Genre.General
    }
}

/** Parse a quality tier out of channel/group name. */
fun qualityFromText(text: String): Quality {
    val t = text.uppercase()
    return when {
        "4K" in t || "UHD" in t || "2160" in t -> Quality.K4
        "FHD" in t || "1080" in t -> Quality.FHD
        "HD" in t || "720" in t -> Quality.HD
        else -> Quality.SD
    }
}

/** Extract a 2-letter bucket code from a group/channel name. Accepts ISO
 *  country codes or language prefixes (EN, JA, …) commonly used in IPTV. */
fun countryFromText(text: String): String? {
    val tokens = text.split(TAG_RE).map { it.trim().trim('[', ']', '(', ')') }
    for (tok in tokens) {
        if (tok.length in 2..3) {
            val up = tok.uppercase()
            val canonical = COUNTRY_ALIASES[up] ?: up
            if (canonical.length == 2 && canonical in KNOWN_COUNTRIES) return canonical
        }
    }
    // Fallback: first two/three chars of the string — handles "UK|Sports"
    // and similar no-delimiter prefixes.
    val head = text.trim().take(3).uppercase()
    val two = head.take(2)
    if (two in KNOWN_COUNTRIES) return COUNTRY_ALIASES[two] ?: two
    if (head in COUNTRY_ALIASES) return COUNTRY_ALIASES[head]
    return null
}

fun isAdultGroup(group: String, name: String): Boolean {
    val t = (group + " " + name).lowercase()
    return ADULT_KEYWORDS.any { it in t }
}

fun brandForGenre(genre: Genre): LiveColors.Brand = when (genre) {
    Genre.News    -> LiveColors.BrandNews
    Genre.Sports  -> LiveColors.BrandSport
    Genre.Movies  -> LiveColors.BrandMovies
    Genre.Series  -> LiveColors.BrandSeries
    Genre.Kids    -> LiveColors.BrandKids
    Genre.Music   -> LiveColors.BrandMusic
    Genre.Docs    -> LiveColors.BrandDocs
    Genre.General -> LiveColors.BrandGeneral
}

fun IptvChannel.enrich(number: Int): EnrichedChannel {
    val combined = "$group | $name"
    val country = countryFromText(group) ?: countryFromText(name)
    val genre = genreFromText(combined)
    val quality = qualityFromText(name).takeUnless { it == Quality.SD } ?: qualityFromText(group)
    val lang = country ?: "EN"
    val brand = brandForGenre(genre)
    return EnrichedChannel(
        source = this,
        number = number,
        country = country,
        genre = genre,
        quality = quality,
        lang = lang,
        brandBg = brand.bg,
        brandFg = brand.fg,
        isAdult = isAdultGroup(group, name),
    )
}

// ─────────────────────────────────────────────────────────────────────────
// Category tree (spec §5)
// ─────────────────────────────────────────────────────────────────────────

data class LiveCategory(
    val id: String,
    val label: String,
    val count: Int,
    val iconToken: CategoryIcon,
    val flagEmoji: String? = null,
    val children: List<LiveCategory> = emptyList(),
) {
    val isGroup: Boolean get() = children.isNotEmpty()
}

enum class CategoryIcon { Favorite, Recent, All, Grid, Sport, Movie, News, Kids, Docs, Music, Lock, Country, SubEntry }

data class LiveSection(val id: String, val label: String, val categories: List<LiveCategory>)

data class LiveCategoryTree(
    val top: List<LiveCategory>,
    val global: LiveSection,
    val countries: LiveSection,
    val adult: LiveSection,
) {
    val allSections: List<LiveSection> = listOf(global, countries, adult)
    fun byId(id: String): LiveCategory? {
        top.firstOrNull { it.id == id }?.let { return it }
        for (section in allSections) {
            for (cat in section.categories) {
                if (cat.id == id) return cat
                cat.children.firstOrNull { it.id == id }?.let { return it }
            }
        }
        return null
    }
}

/**
 * Build the category tree from a list of enriched channels. Counts are
 * computed up front so the sidebar can render without filtering again.
 */
fun buildCategoryTree(
    channels: List<EnrichedChannel>,
    favoritesCount: Int,
    recentCount: Int,
): LiveCategoryTree {
    fun count(pred: (EnrichedChannel) -> Boolean) = channels.count(pred)

    val top = listOf(
        LiveCategory("fav", "Favorites", favoritesCount, CategoryIcon.Favorite),
        LiveCategory("recent", "Recent", recentCount, CategoryIcon.Recent),
        LiveCategory("all", "All Channels", channels.count { !it.isAdult }, CategoryIcon.All),
    )

    val global = LiveSection("global", "GLOBAL", listOf(
        LiveCategory("g-4k",     "4K | Ultra HD",      count { it.quality == Quality.K4 && !it.isAdult }, CategoryIcon.Grid),
        LiveCategory("g-sports", "Sports · Global",    count { it.genre == Genre.Sports && !it.isAdult }, CategoryIcon.Sport),
        LiveCategory("g-movies", "Movies · Global",    count { it.genre == Genre.Movies && !it.isAdult }, CategoryIcon.Movie),
        LiveCategory("g-news",   "News · Global",      count { it.genre == Genre.News && !it.isAdult },   CategoryIcon.News),
        LiveCategory("g-kids",   "Kids · Global",      count { it.genre == Genre.Kids && !it.isAdult },   CategoryIcon.Kids),
        LiveCategory("g-docs",   "Documentary",        count { it.genre == Genre.Docs && !it.isAdult },   CategoryIcon.Docs),
        LiveCategory("g-music",  "Music",              count { it.genre == Genre.Music && !it.isAdult },  CategoryIcon.Music),
    ).filter { it.count > 0 })

    val subTags = listOf(
        "general" to (Genre.General to null),
        "4k"      to (null to Quality.K4),
        "fhd"     to (null to Quality.FHD),
        "sports"  to (Genre.Sports to null),
        "movies"  to (Genre.Movies to null),
        "news"    to (Genre.News to null),
        "kids"    to (Genre.Kids to null),
        "entertainment" to (Genre.Series to null),
        "documentary"   to (Genre.Docs to null),
    )

    fun subMatch(ch: EnrichedChannel, genre: Genre?, quality: Quality?): Boolean =
        (genre == null || ch.genre == genre) && (quality == null || ch.quality == quality)

    val countryMap = channels
        .filterNot { it.isAdult }
        .groupBy { it.country }
        .filterKeys { !it.isNullOrBlank() }

    val countryCategories = countryMap
        .toList()
        .sortedByDescending { (_, v) -> v.size }
        .map { (cc, list) ->
            val code = cc!!
            val subs = subTags.mapNotNull { (tag, pair) ->
                val (genre, quality) = pair
                val n = list.count { subMatch(it, genre, quality) }
                if (n == 0) null else LiveCategory(
                    id = "$code-$tag",
                    label = "$code | ${tag.replaceFirstChar(Char::uppercase)}",
                    count = n,
                    iconToken = CategoryIcon.SubEntry,
                )
            }
            LiveCategory(
                id = code,
                label = countryName(code),
                count = list.size,
                iconToken = CategoryIcon.Country,
                flagEmoji = countryFlag(code),
                children = subs,
            )
        }

    val countries = LiveSection("countries", "COUNTRIES", countryCategories)

    val adultCategories = listOf(
        LiveCategory("adult", "Adult", count { it.isAdult }, CategoryIcon.Lock),
    ).filter { it.count > 0 }
    val adult = LiveSection("adult", "ADULT", adultCategories)

    return LiveCategoryTree(top = top, global = global, countries = countries, adult = adult)
}

/** Returns a predicate matching channels for [categoryId]. Implements spec §5. */
fun categoryMatcher(
    categoryId: String,
    favorites: Set<String>,
    recents: Set<String>,
): (EnrichedChannel) -> Boolean {
    return when {
        categoryId == "all"    -> { ch -> !ch.isAdult }
        categoryId == "fav"    -> { ch -> ch.id in favorites && !ch.isAdult }
        categoryId == "recent" -> { ch -> ch.id in recents && !ch.isAdult }
        categoryId == "adult"  -> { ch -> ch.isAdult }
        categoryId == "g-4k"      -> { ch -> ch.quality == Quality.K4 && !ch.isAdult }
        categoryId == "g-sports"  -> { ch -> ch.genre == Genre.Sports && !ch.isAdult }
        categoryId == "g-movies"  -> { ch -> ch.genre == Genre.Movies && !ch.isAdult }
        categoryId == "g-news"    -> { ch -> ch.genre == Genre.News && !ch.isAdult }
        categoryId == "g-kids"    -> { ch -> ch.genre == Genre.Kids && !ch.isAdult }
        categoryId == "g-docs"    -> { ch -> ch.genre == Genre.Docs && !ch.isAdult }
        categoryId == "g-music"   -> { ch -> ch.genre == Genre.Music && !ch.isAdult }
        categoryId.length == 2 && categoryId.all { it.isUpperCase() } ->
            { ch -> ch.country == categoryId && !ch.isAdult }
        "-" in categoryId -> {
            val (cc, tag) = categoryId.split("-", limit = 2)
            val (genre, quality) = when (tag) {
                "4k"  -> null to Quality.K4
                "fhd" -> null to Quality.FHD
                "sports" -> Genre.Sports to null
                "movies" -> Genre.Movies to null
                "news"   -> Genre.News to null
                "kids"   -> Genre.Kids to null
                "entertainment" -> Genre.Series to null
                "documentary"   -> Genre.Docs to null
                "general" -> Genre.General to null
                else -> null to null
            }
            { ch -> ch.country == cc && !ch.isAdult &&
                (genre == null || ch.genre == genre) &&
                (quality == null || ch.quality == quality) }
        }
        else -> { _ -> false }
    }
}

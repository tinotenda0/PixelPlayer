package com.theveloper.pixelplay.data.search

import com.theveloper.pixelplay.data.model.SearchResultItem
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Relevance ranking for search results — pure, deterministic, unit-tested.
 *
 * Goals (from real use):
 *  - An entity whose *own name* matches the query beats one that only matches
 *    incidentally: searching "daft" puts the artist "Daft Punk" above the song
 *    "Around the World" (which only matches because its artist is Daft Punk).
 *  - Among matching songs, ones the user has actually played rank first.
 *  - Spacing and typos are tolerated: "lowkey" finds "Low Key", "daftt" finds
 *    "Daft Punk".
 *
 * Scoring is tier-based (exact > prefix > word-prefix > substring > fuzzy) so
 * match strength always dominates; play history and type only reorder within a
 * tier.
 */
object SearchRanker {

    data class PlayStat(val playCount: Int, val lastPlayedTimestamp: Long)

    // Tier bases are spaced by 1000 so nothing below can leapfrog a stronger tier.
    private const val EXACT = 6000
    private const val PREFIX = 5000
    private const val WORD_PREFIX = 4000
    private const val SUBSTRING = 3000
    private const val TOKEN_SUBSET = 2500
    private const val FUZZY = 1500
    private const val FLOOR = 300 // retrieved but weakly matched — keep, don't drop

    private const val PRIMARY_FIELD_BONUS = 500 // matched the item's own name, not a secondary field
    private const val ARTIST_STRONG_BONUS = 300 // a strongly-matching artist edges out same-tier songs
    private const val ALBUM_STRONG_BONUS = 100
    private const val PLAY_BONUS_CAP = 400      // < 1000, so it never crosses a tier

    private const val FUZZY_ACCEPT = 0.84       // min similarity to count as a fuzzy match
    private const val RECENCY_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    private data class FieldMatch(val tier: Int, val quality: Double)

    fun rank(
        query: String,
        items: List<SearchResultItem>,
        playStats: Map<String, PlayStat>,
        nowMs: Long = System.currentTimeMillis()
    ): List<SearchResultItem> {
        val nq = normalize(query)
        if (nq.isEmpty()) return items
        // Normalized query words, for order-free multi-word matching.
        val qWords = tokenize(query).map { normalize(it) }.filter { it.isNotEmpty() }

        return items
            .withIndex()
            .map { (index, item) -> Triple(item, score(nq, qWords, item, playStats, nowMs), index) }
            // Tiebreak on the ORIGINAL order, not the name. Results arrive already ordered by
            // source relevance (the gateway returns best-match-first), and equal-scoring items are
            // common — an alphabetical tiebreak threw that relevance away and made results look
            // A-to-Z sorted rather than ranked by how well they match / how often they're played.
            .sortedWith(compareByDescending<Triple<SearchResultItem, Int, Int>> { it.second }
                .thenBy { it.third })
            .map { it.first }
    }

    private fun score(
        nq: String,
        qWords: List<String>,
        item: SearchResultItem,
        playStats: Map<String, PlayStat>,
        nowMs: Long
    ): Int {
        val primary = primaryName(item)
        val secondary = secondaryName(item)

        val primaryMatch = matchField(nq, qWords, primary)
        val secondaryMatch = secondary?.let { matchField(nq, qWords, it) }

        // Best match decides the tier; whether it came from the primary field
        // decides the field bonus (an artist-only match on a song scores lower).
        val bestFromPrimary: Boolean
        val best: FieldMatch?
        if (primaryMatch != null && (secondaryMatch == null || primaryMatch.tier >= secondaryMatch.tier)) {
            best = primaryMatch; bestFromPrimary = true
        } else if (secondaryMatch != null) {
            best = secondaryMatch; bestFromPrimary = false
        } else {
            best = null; bestFromPrimary = false
        }

        var s = best?.tier ?: FLOOR
        if (best != null && bestFromPrimary) s += PRIMARY_FIELD_BONUS

        // Type nudge: a strongly-matching artist (or album) name should sit
        // above songs that merely inherit the match — the "daft → Daft Punk" case.
        if (best != null && best.tier >= WORD_PREFIX && bestFromPrimary) {
            when (item) {
                is SearchResultItem.ArtistItem -> s += ARTIST_STRONG_BONUS
                is SearchResultItem.AlbumItem -> s += ALBUM_STRONG_BONUS
                else -> {}
            }
        }

        // Play-history boost for songs: played songs rank first among matches.
        if (item is SearchResultItem.SongItem) {
            s += playBonus(playStats[item.song.id], nowMs)
        }

        // Micro-ordering within a tier by closeness (0..99).
        s += ((best?.quality ?: 0.0) * 99).toInt().coerceIn(0, 99)
        return s
    }

    private fun playBonus(stat: PlayStat?, nowMs: Long): Int {
        if (stat == null || stat.playCount <= 0) return 0
        val countComponent = 100 + (60 * ln((stat.playCount + 1).toDouble())).toInt()
        val recencyComponent = if (
            stat.lastPlayedTimestamp > 0 &&
            nowMs - stat.lastPlayedTimestamp in 0..RECENCY_WINDOW_MS
        ) {
            val age = nowMs - stat.lastPlayedTimestamp
            (100 * (1.0 - age.toDouble() / RECENCY_WINDOW_MS)).toInt()
        } else 0
        return min(PLAY_BONUS_CAP, countComponent + recencyComponent)
    }

    /**
     * Compares a normalized query against one field, returning tier + closeness.
     * [nq] is the space-stripped query; [qWords] its normalized words (for
     * order-free multi-word matches like "around world" → "Around the World").
     */
    private fun matchField(nq: String, qWords: List<String>, rawField: String): FieldMatch? {
        val nf = normalize(rawField)
        if (nf.isEmpty()) return null
        val fieldWords = tokenize(rawField).map { normalize(it) }.filter { it.isNotEmpty() }

        if (nf == nq) return FieldMatch(EXACT, 1.0)
        if (nf.startsWith(nq)) return FieldMatch(PREFIX, lengthQuality(nq.length, nf.length))

        if (fieldWords.any { it.startsWith(nq) }) {
            return FieldMatch(WORD_PREFIX, lengthQuality(nq.length, nf.length))
        }

        // Every query word is a prefix of some field word (order-free).
        if (qWords.size > 1 &&
            qWords.all { qw -> fieldWords.any { it.startsWith(qw) } }
        ) {
            val covered = qWords.sumOf { it.length }
            return FieldMatch(WORD_PREFIX, lengthQuality(covered, nf.length))
        }

        if (nf.contains(nq)) return FieldMatch(SUBSTRING, lengthQuality(nq.length, nf.length))

        // Every query word appears somewhere in the field (order-free substring).
        if (qWords.size > 1 && qWords.all { nf.contains(it) }) {
            val covered = qWords.sumOf { it.length }
            return FieldMatch(TOKEN_SUBSET, lengthQuality(covered, nf.length))
        }

        val fuzzy = similarity(nq, nf)
        if (fuzzy >= FUZZY_ACCEPT) return FieldMatch(FUZZY, fuzzy)

        // Also try fuzzy against the closest single field word (helps long titles).
        val bestWord = fieldWords.maxOfOrNull { similarity(nq, it) } ?: 0.0
        if (bestWord >= FUZZY_ACCEPT) return FieldMatch(FUZZY, bestWord)

        return null
    }

    private fun lengthQuality(qLen: Int, fLen: Int): Double =
        if (fLen <= 0) 0.0 else (qLen.toDouble() / fLen).coerceIn(0.0, 1.0)

    // ─── Names ───────────────────────────────────────────────────────────────

    private fun primaryName(item: SearchResultItem): String = when (item) {
        is SearchResultItem.SongItem -> item.song.title
        is SearchResultItem.AlbumItem -> item.album.title
        is SearchResultItem.ArtistItem -> item.artist.name
        is SearchResultItem.PlaylistItem -> item.playlist.name
    }

    private fun secondaryName(item: SearchResultItem): String? = when (item) {
        is SearchResultItem.SongItem -> item.song.displayArtist
        is SearchResultItem.AlbumItem -> item.album.artist
        else -> null
    }

    // ─── Text normalization ────────────────────────────────────────────────────

    /** Lowercase, strip diacritics, keep only [a-z0-9] (drops spaces/punctuation). */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            when {
                c in 'A'..'Z' -> sb.append(c + 32)
                c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                // drop combining marks, spaces, punctuation
            }
        }
        return sb.toString()
    }

    private val WORD_REGEX = Regex("""[\p{L}\p{N}]+""")
    private fun tokenize(s: String): List<String> =
        WORD_REGEX.findAll(s).map { it.value }.toList()

    // ─── Fuzzy similarity ──────────────────────────────────────────────────────

    /** Max of Jaro-Winkler and normalized-Levenshtein — robust to typos + prefixes. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        return max(jaroWinkler(a, b), levenshteinRatio(a, b))
    }

    private fun levenshteinRatio(a: String, b: String): Double {
        val dist = levenshtein(a, b)
        val maxLen = max(a.length, b.length)
        return if (maxLen == 0) 1.0 else 1.0 - dist.toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    private fun jaroWinkler(a: String, b: String): Double {
        val j = jaro(a, b)
        if (j < 0.7) return j
        var prefix = 0
        val maxPrefix = min(4, min(a.length, b.length))
        while (prefix < maxPrefix && a[prefix] == b[prefix]) prefix++
        return j + prefix * 0.1 * (1 - j)
    }

    private fun jaro(a: String, b: String): Double {
        if (a == b) return 1.0
        val matchDistance = max(a.length, b.length) / 2 - 1
        val aMatches = BooleanArray(a.length)
        val bMatches = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val start = max(0, i - matchDistance)
            val end = min(i + matchDistance + 1, b.length)
            for (k in start until end) {
                if (bMatches[k] || a[i] != b[k]) continue
                aMatches[i] = true; bMatches[k] = true; matches++; break
            }
        }
        if (matches == 0) return 0.0
        var t = 0.0
        var k = 0
        for (i in a.indices) {
            if (!aMatches[i]) continue
            while (!bMatches[k]) k++
            if (a[i] != b[k]) t += 0.5
            k++
        }
        val m = matches.toDouble()
        return (m / a.length + m / b.length + (m - t) / m) / 3.0
    }
}

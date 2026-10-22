package com.lagradost.cloudstream3.ui.lite

import com.lagradost.cloudstream3.utils.Qualities
import java.util.Locale

data class LiteLinkInfo(
    val source: String,
    val server: String,
    val audioCode: String?,
    val subtitled: Boolean,
    val dubbed: Boolean,
    val subtitleCode: String? = null,
)

/** Only the two leading Nexo prefixes carry source/audio evidence. */
fun parseLiteLinkName(name: String): LiteLinkInfo {
    val prefix = Regex("""^\[([^\[\]]+)]\[([^\[\]]+)]\s*(.*)$""").matchEntire(name.trim())
        ?: return LiteLinkInfo("", name.trim(), null, false, false)
    val (source, rawTag, server) = prefix.destructured
    val tag = rawTag.uppercase(Locale.ROOT)
    val subtitled = tag == "SUB"
    val dubbed = tag == "DUB"
    val animeAv1 = source.equals("AnimeAv1", ignoreCase = true)
    val audio = when {
        animeAv1 && subtitled -> "JPN"
        animeAv1 && dubbed -> "SPA"
        else -> when (tag) {
            "ING", "ENG" -> "ENG"
            "ESP", "CAST" -> "CAST"
            "JAP", "JPN" -> "JPN"
            "COR", "KOR" -> "KOR"
            "LAT", "SPA" -> tag
            // Explicit legacy codes emitted by Pandrama and Doramasflix.
            "MAN", "TAI", "TAG", "CAN", "SUE", "HOL", "JEM", "MAY", "ESL" -> tag
            "SUB", "DUB", "UNK" -> null
            // Preserve explicit three-letter language codes, never infer plain es.
            else -> tag.takeIf { it.length == 3 && Locale.getISOLanguages().any { language ->
                Locale.forLanguageTag(language).isO3Language.equals(it, ignoreCase = true)
            } }
        }
    }
    return LiteLinkInfo(source, server, audio, subtitled, dubbed, if (animeAv1 && subtitled) "LAT" else null)
}

/** Quality comes exclusively from ExtractorLink. 400 is Cloudstream's unknown sentinel. */
fun liteLinkLabel(info: LiteLinkInfo, quality: Int?): String = buildList {
    add(when (info.audioCode) {
        "LAT" -> "Latino"
        "CAST" -> "Castellano"
        "SPA" -> "Español"
        "ENG" -> "Inglés"
        "JPN" -> "Japonés"
        "KOR" -> "Coreano"
        null -> "Audio desconocido"
        else -> info.audioCode
    })
    if (quality != null && quality >= 0) Qualities.getStringByInt(quality).takeIf { it.isNotBlank() }?.let { add(it) }
    if (info.server.isNotBlank()) add(info.server)
    if (info.subtitled) add(info.subtitleCode?.let { "Subtítulos $it" } ?: "Subtítulos")
    if (info.dubbed && info.audioCode == null) add("Doblado")
}.joinToString(" · ")

data class LiteCandidate(
    val id: String,
    val info: LiteLinkInfo,
    val quality: Int?,
    val failed: Boolean = false,
    val degraded: Boolean = false,
    val startupMs: Long? = null,
)

/** Retain every link for display; failure eligibility is handled by chooseLiteStart. */
fun rankLiteLinks(candidates: List<LiteCandidate>): List<LiteCandidate> {
    val groups = candidates.groupBy {
        Triple(liteAudioPriority(it.info.audioCode), it.degraded, liteQualityRank(it.quality))
    }
    val groupOrder = compareBy<Triple<Int, Boolean, Int>> { it.first }
        .thenBy { it.second }
        .thenByDescending { it.third }
    return groups.keys.sortedWith(groupOrder).flatMap { key ->
        val group = groups.getValue(key)
        // Pairwise fallback with missing metrics can create comparator cycles.
        val comparableStartup = group.all { it.startupMs != null }
        group.sortedWith(compareBy<LiteCandidate> { if (comparableStartup) it.startupMs else null }
            .thenBy { it.info.server }
            .thenBy { it.info.source }
            .thenBy { it.id })
    }
}

private fun liteAudioPriority(audioCode: String?): Int = when (audioCode) {
    "LAT" -> 0
    "CAST" -> 1
    "SPA" -> 2
    "ENG" -> 3
    "JPN" -> 4
    "KOR" -> 5
    null -> 7
    else -> 6
}

// Known resolutions precede Auto (0), then unknown (400, null or negative).
private fun liteQualityRank(quality: Int?): Int = when (quality) {
    null, 400 -> -1
    else -> quality.coerceAtLeast(-1)
}

/** Upgrade for an objective preference, never for an alphabetical server tie. */
fun chooseLiteUpgrade(candidates: List<LiteCandidate>, currentId: String): String? {
    val eligible = candidates.filterNot { it.failed }
    val current = eligible.firstOrNull { it.id == currentId } ?: return null
    val best = rankLiteLinks(eligible).firstOrNull() ?: return null
    if (best.id == current.id) return null
    fun group(candidate: LiteCandidate) = Triple(liteAudioPriority(candidate.info.audioCode),
        candidate.degraded, liteQualityRank(candidate.quality))
    if (group(best) != group(current)) return best.id
    val comparable = eligible.filter { group(it) == group(current) }.all { it.startupMs != null }
    return best.id.takeIf { comparable && best.startupMs!! < current.startupMs!! }
}

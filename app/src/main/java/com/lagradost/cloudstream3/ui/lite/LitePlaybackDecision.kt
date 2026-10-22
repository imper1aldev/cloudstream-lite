package com.lagradost.cloudstream3.ui.lite

/** The state owner must atomically confirm the returned identity before starting. */
fun chooseLiteStart(
    candidates: List<LiteCandidate>,
    extractionComplete: Boolean,
    alreadySelected: Boolean,
): String? {
    if (alreadySelected) return null
    return rankLiteLinks(candidates.filter { !it.failed })
        .firstOrNull { extractionComplete || it.info.audioCode == "LAT" }?.id
}

fun usesLitePlayback(phoneLayout: Boolean, apiName: String?): Boolean =
    phoneLayout && apiName == "NexoStream"

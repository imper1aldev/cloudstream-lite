package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.lite.LiteCandidate
import com.lagradost.cloudstream3.ui.lite.rankLiteLinks
import com.lagradost.cloudstream3.ui.lite.parseLiteLinkName
import com.lagradost.cloudstream3.ui.lite.liteLinkLabel
import org.junit.Assert.*
import org.junit.Test

class LiteLinkPolicyTest {
    @Test fun parsesLegacyTagsWithoutInventingAudio() {
        val english = parseLiteLinkName("[Pandrama][ING] Voe")
        assertEquals("Pandrama", english.source)
        assertEquals("ENG", english.audioCode)
        assertEquals("Voe", english.server)
        listOf("ESP" to "CAST", "JAP" to "JPN", "COR" to "KOR", "LAT" to "LAT", "SPA" to "SPA", "FRA" to "FRA").forEach { (tag, code) ->
            assertEquals(code, parseLiteLinkName("[A][$tag] S").audioCode)
        }
        val sub = parseLiteLinkName("[A][SUB]")
        assertNull(sub.audioCode)
        assertTrue(sub.subtitled)
        assertFalse(sub.dubbed)
        val dub = parseLiteLinkName("[A][DUB]")
        assertNull(dub.audioCode)
        assertTrue(dub.dubbed)
        assertFalse(dub.subtitled)
        listOf("[A][UNK] S", "texto libre", "[A][es] S", "[A][no identificado] S").forEach {
            assertNull(parseLiteLinkName(it).audioCode)
        }
        val bracketedServer = parseLiteLinkName("[A][UNK] Voe [LAT]")
        assertNull(bracketedServer.audioCode)
        assertEquals("Voe [LAT]", bracketedServer.server)
        assertEquals("texto libre", parseLiteLinkName("texto libre").server)
    }

    @Test fun preservesExplicitProductionLegacyLanguageCodes() {
        listOf("MAN", "TAI", "TAG", "CAN", "SUE", "HOL", "JEM", "MAY", "ESL", "FRA").forEach { code ->
            assertEquals(code, parseLiteLinkName("[Pandrama][$code] Voe").audioCode)
        }
        assertEquals("MAN", parseLiteLinkName("[Doramasflix][MAN] Voe").audioCode)
        listOf("UNK", "XYZ", "es").forEach { tag ->
            assertNull(parseLiteLinkName("[Pandrama][$tag] Voe").audioCode)
        }
    }

    @Test fun nestedPrefixBracketsDoNotCreateKnownAudio() {
        listOf("[[A][LAT] Voe", "[A][[LAT] Voe", "[A][LAT[ENG] Voe").forEach { malformed ->
            assertNull(parseLiteLinkName(malformed).audioCode)
        }
        assertEquals("LAT", parseLiteLinkName("[A][LAT] Voe").audioCode)
        val serverBracket = parseLiteLinkName("[A][UNK] Server [LAT]")
        assertNull(serverBracket.audioCode)
        assertEquals("Server [LAT]", serverBracket.server)
    }
    @Test fun animeAv1TagsUseSourceSpecificEvidence() {
        val sub = parseLiteLinkName("[AnimeAv1][SUB] HLS")
        assertEquals("JPN", sub.audioCode)
        assertEquals("LAT", sub.subtitleCode)
        assertTrue(sub.subtitled)
        val dub = parseLiteLinkName("[AnimeAv1][DUB] HLS")
        assertEquals("SPA", dub.audioCode)
        assertTrue(dub.dubbed)
        assertNull(dub.subtitleCode)
        assertNull(parseLiteLinkName("[Otro][DUB] HLS").audioCode)
        assertNull(parseLiteLinkName("[Otro][SUB] HLS").audioCode)
        assertNull(parseLiteLinkName("[Otro][SUB] HLS").subtitleCode)
    }

    @Test fun labelsUseMetadataQualityWithoutReadingResolutionFromTheName() {
        assertEquals("Latino · 1080p · Voe", liteLinkLabel(parseLiteLinkName("[A][LAT] Voe"), 1080))
        assertEquals("Castellano · S", liteLinkLabel(parseLiteLinkName("[A][ESP] S"), null))
        assertEquals("Audio desconocido · HLS 2160p", liteLinkLabel(parseLiteLinkName("[A][UNK] HLS 2160p"), 400))
        assertEquals("Español · Auto · HLS", liteLinkLabel(parseLiteLinkName("[AnimeAv1][DUB] HLS"), 0))
        assertEquals("Latino · 4K · Voe", liteLinkLabel(parseLiteLinkName("[A][LAT] Voe"), 2160))
        assertEquals("Japonés · 720p · HLS · Subtítulos LAT", liteLinkLabel(parseLiteLinkName("[AnimeAv1][SUB] HLS"), 720))
        assertEquals("Audio desconocido · S · Subtítulos", liteLinkLabel(parseLiteLinkName("[A][SUB] S"), -1))
        assertEquals("Audio desconocido · S · Doblado", liteLinkLabel(parseLiteLinkName("[A][DUB] S"), null))
    }

    @Test fun languagePriorityPrecedesQualityAndKeepsUnknownCandidates() {
        val tags = listOf("UNK", "FRA", "KOR", "JPN", "ENG", "SPA", "CAST", "LAT")
        val candidates = tags.map { tag ->
            LiteCandidate(tag, parseLiteLinkName("[A][$tag] Voe"), if (tag == "LAT") 720 else 1080)
        }
        val ranked = rankLiteLinks(candidates)
        assertEquals(listOf("LAT", "CAST", "SPA", "ENG", "JPN", "KOR", "FRA", "UNK"), ranked.map { it.id })
        assertNull(ranked.last().info.audioCode)
        assertEquals(candidates.toSet(), ranked.toSet())
    }

    @Test fun provenDegradationPrecedesQualityWithinEachLanguage() {
        val normal = LiteCandidate("normal", parseLiteLinkName("[A][LAT] Voe"), 720)
        val high = normal.copy(id = "high", quality = 1080)
        val degraded = normal.copy(id = "degraded", quality = 2160, degraded = true)
        val english = LiteCandidate("english", parseLiteLinkName("[A][ENG] Voe"), 2160)
        assertEquals(listOf("high", "normal", "degraded", "english"), rankLiteLinks(listOf(english, degraded, normal, high)).map { it.id })
    }

    @Test fun serverNamesAndOneSlowStartDoNotImplyDegradation() {
        val slow = LiteCandidate("slow", parseLiteLinkName("[A][LAT] Streamtape"), 1080, startupMs = 60000)
        val fast = LiteCandidate("fast", parseLiteLinkName("[A][LAT] Voe"), 720, startupMs = 100)
        assertEquals(listOf("slow", "fast"), rankLiteLinks(listOf(fast, slow)).map { it.id })
    }

    @Test fun unknownQualitySentinelNeverOutranksKnownLowResolutions() {
        val base = LiteCandidate("unknown", parseLiteLinkName("[A][LAT] Voe"), 400)
        val candidates = listOf(base, base.copy(id = "null", quality = null), base.copy(id = "negative", quality = -1), base.copy(id = "auto", quality = 0), base.copy(id = "144", quality = 144), base.copy(id = "240", quality = 240))
        assertEquals(listOf("240", "144", "auto", "negative", "null", "unknown"), rankLiteLinks(candidates).map { it.id })
    }

    @Test fun startupMetricsBreakTiesOnlyWhenTheWholeGroupIsComparable() {
        val slow = LiteCandidate("slow", parseLiteLinkName("[A][LAT] Alpha"), 1080, startupMs = 500)
        val fast = LiteCandidate("fast", parseLiteLinkName("[A][LAT] Zulu"), 1080, startupMs = 100)
        val otherQuality = slow.copy(id = "720", quality = 720, startupMs = null)
        assertEquals(listOf("fast", "slow", "720"), rankLiteLinks(listOf(slow, otherQuality, fast)).map { it.id })
    }

    @Test fun rankingIsStableAcrossPermutationsWithMissingMetrics() {
        val a = LiteCandidate("A", parseLiteLinkName("[A][LAT] Alpha"), 1080, startupMs = 500)
        val b = LiteCandidate("B", parseLiteLinkName("[A][LAT] Bravo"), 1080, startupMs = null)
        val c = LiteCandidate("C", parseLiteLinkName("[A][LAT] Charlie"), 1080, startupMs = 100)
        listOf(listOf(a, b, c), listOf(a, c, b), listOf(b, a, c), listOf(b, c, a), listOf(c, a, b), listOf(c, b, a)).forEach {
            assertEquals(listOf("A", "B", "C"), rankLiteLinks(it).map { candidate -> candidate.id })
        }
    }

    @Test fun tiesUseServerThenSourceThenIdentityAndRetainFailedLinks() {
        val base = LiteCandidate("z", parseLiteLinkName("[B][LAT] Bravo"), 1080)
        val sourceA = base.copy(id = "a", info = parseLiteLinkName("[A][LAT] Bravo"))
        val sourceASecond = sourceA.copy(id = "b", failed = true)
        val serverA = base.copy(id = "serverA", info = parseLiteLinkName("[Z][LAT] Alpha"))
        assertEquals(listOf("serverA", "a", "b", "z"), rankLiteLinks(listOf(base, sourceASecond, sourceA, serverA)).map { it.id })
        assertTrue(rankLiteLinks(listOf(sourceASecond)).single().failed)
        assertTrue(rankLiteLinks(emptyList()).isEmpty())
    }
}

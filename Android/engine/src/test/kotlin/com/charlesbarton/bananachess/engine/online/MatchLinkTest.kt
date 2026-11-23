package com.charlesbarton.bananachess.engine.online

import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MatchLinkTest {
    // Golden data

    @Test
    fun `every golden link decodes to the payload it was made from`() {
        for (sample in GoldenSamples.all) {
            assertTrue(sample.link.startsWith("bananachess://m/1/"), sample.label)

            val payload = MatchLink.payload(sample.link)

            assertEquals(MatchCodec.decode(sample.payloadJson), payload, sample.label)
            assertEquals(sample.payloadJson, MatchCodec.encode(payload), sample.label)
        }
    }

    @Test
    fun `every golden payload survives the trip through a link made here`() {
        for (sample in GoldenSamples.all) {
            val payload = MatchCodec.decode(sample.payloadJson)

            val link = MatchLink.url(payload)

            assertTrue(link.startsWith("bananachess://m/1/"), sample.label)
            assertTrue(
                link.substringAfterLast('/').all { it.isLetterOrDigit() || it == '-' || it == '_' },
                sample.label,
            )
            assertEquals(payload, MatchLink.payload(link), sample.label)
            // Long for a link, short for a message.
            assertTrue(link.length < 8_000, "${sample.label} made ${link.length} characters")
        }
    }

    @Test
    fun `links written by Swift for shapes the golden file lacks decode`() {
        val everything = SwiftSamples.EVERYTHING.replace(SwiftSamples.ID_PLACEHOLDER, SwiftSamples.AWKWARD_ID_ESCAPED)

        assertEquals(MatchCodec.decode(everything), MatchLink.payload(SwiftSamples.EVERYTHING_LINK))
        assertEquals(SwiftSamples.AWKWARD_ID, MatchLink.payload(SwiftSamples.EVERYTHING_LINK).matchId)
        assertEquals(MatchCodec.decode(SwiftSamples.FRESH), MatchLink.payload(SwiftSamples.FRESH_HTTPS_LINK))
    }

    // Ported from the "Match links" suite in OnlineTests.swift

    @Test
    fun `a match survives the round trip through a universal link`() {
        val payload = playedOut(turns = 40)

        val url = MatchLink.url(payload, host = "bananachess.example")

        assertTrue(url.startsWith("https://bananachess.example/m/1/"), url.take(40))
        assertEquals(payload, MatchLink.payload(url))
    }

    @Test
    fun `and through the scheme-only link, which needs nothing hosted`() {
        val payload = playedOut(turns = 40)

        val url = MatchLink.url(payload)

        assertTrue(url.startsWith("bananachess://m/1/"), url.take(40))
        assertEquals(payload, MatchLink.payload(url))
        assertEquals(payload, MatchLink.payload("  $url\n"), "pasted with whitespace around it")
        assertEquals(payload, MatchLink.payload("$url?source=share#top"))
    }

    @Test
    fun `every match setting survives the trip`() {
        for (setup in BoardSetup.entries) {
            val options = MatchOptions(RuleSet.Wildcard, setup, BoardWrap.All, blenderBattles = true)
            val payload = MatchPayload.newMatch(id = "m", options = options)

            val decoded = MatchLink.payload(MatchLink.url(payload))

            assertEquals(RuleSet.Wildcard, decoded.state.ruleSet)
            assertEquals(setup, decoded.state.boardSetup)
            assertEquals(BoardWrap.All, decoded.state.boardWrap)
            assertTrue(decoded.state.blenderBattles)
            assertEquals(payload.state.pieces, decoded.state.pieces, "including a random deal")
            assertEquals(payload.state.boardHistory, decoded.state.boardHistory)
        }
    }

    @Test
    fun `something that is not a match link is refused, not guessed at`() {
        val notLinks =
            listOf(
                "",
                "hello",
                "https://bananachess.example/",
                "https://bananachess.example/other/1/abc",
                "https://bananachess.example/m/one/abc",
                "https://bananachess.example/m/1/abc/extra",
                "bananachess://m/1/",
                "bananachess://m/1",
                "bananachess://other/1/abc",
                "bananachess:m/1/abc",
                "://m/1/abc",
            )

        for (text in notLinks) {
            val error = assertFailsWith<MatchLinkError.NotAMatchLink>(text) { MatchLink.payload(text) }
            assertEquals("That link isn't a Banana Chess match.", error.message)
        }
    }

    @Test
    fun `a link from a newer format says so rather than mangling it`() {
        val encoded = MatchLink.encode(MatchPayload.newMatch(id = "m"))

        for (url in listOf("https://x.example/m/2/$encoded", "bananachess://m/2/$encoded", "bananachess://m/900/x")) {
            val error = assertFailsWith<MatchLinkError.UnsupportedFormat>(url) { MatchLink.payload(url) }
            assertEquals("That match was sent from a newer version of Banana Chess.", error.message)
        }
        assertEquals(
            MatchLinkError.UnsupportedFormat(found = 2, supported = 1),
            assertFailsWith<MatchLinkError.UnsupportedFormat> { MatchLink.payload("bananachess://m/2/$encoded") },
        )
    }

    @Test
    fun `a link from an older rules build is refused by the payload, not the link`() {
        val older = RulesEngine.RULES_VERSION - 1
        val url = MatchLink.url(MatchPayload.newMatch(id = "m").copy(rulesVersion = older))

        val error = assertFailsWith<MatchCodecError.IncompatibleRules> { MatchLink.payload(url) }

        assertEquals(MatchCodecError.IncompatibleRules(older, RulesEngine.RULES_VERSION), error)
    }

    @Test
    fun `schema refusals come through a link too`() {
        val match = MatchPayload.newMatch(id = "m")
        val newer = MatchLink.url(match.copy(schemaVersion = MatchPayload.CURRENT_SCHEMA_VERSION + 1))
        val older = MatchLink.url(match.copy(schemaVersion = MatchPayload.CURRENT_SCHEMA_VERSION - 1))

        assertFailsWith<MatchCodecError.UnsupportedSchema> { MatchLink.payload(newer) }
        assertFailsWith<MatchCodecError.OutdatedSchema> { MatchLink.payload(older) }
    }

    // Input from outside the app

    @Test
    fun `a damaged blob is unreadable`() {
        val blob =
            GoldenSamples.all
                .first()
                .link
                .substringAfterLast('/')
        val damaged =
            listOf(
                "not-base64!!",
                "abc",
                "A",
                "%20%20",
                "🍌🍌🍌🍌",
                blob.take(blob.length / 2),
                blob.dropLast(1),
                blob.drop(4),
                blob.reversed(),
                // A zlib-wrapped and a gzip-wrapped stream: the wrong framing.
                "eJyrVspNLEnO8HRRslLKVdJRqlCyMqwFAEfnBhU",
                "H4sIAAAAAAAC_6tWyk0sSc7wdFGyUspV0lGqULIyrAUAHhudiBUAAAA",
            )

        for (text in damaged) {
            val error =
                assertFailsWith<MatchLinkError.Unreadable>(text) { MatchLink.payload("bananachess://m/1/$text") }
            assertEquals("That match link is damaged and can't be read.", error.message)
        }
    }

    @Test
    fun `no truncation or corruption of a real link escapes as anything but a link or codec error`() {
        val link = GoldenSamples.all.first { it.label.endsWith("step 5") }.link
        val candidates =
            (0 until link.length).map { link.take(it) } +
                (link.indexOf("/1/") + 3 until link.length).flatMap { index ->
                    listOf('A', '_', '9').map { link.replaceRange(index, index + 1, it.toString()) }
                }
        var refused = 0

        for (candidate in candidates) {
            try {
                MatchLink.payload(candidate)
            } catch (_: MatchLinkError) {
                refused += 1
            } catch (_: MatchCodecError) {
                refused += 1
            }
        }

        assertTrue(refused > candidates.size / 2, "only $refused of ${candidates.size} were refused")
    }

    @Test
    fun `valid compressed data that is not a match is malformed, not unreadable`() {
        assertFailsWith<MatchCodecError.Malformed> {
            MatchLink.payload(
                linkCarrying("not a match".encodeToByteArray()),
            )
        }
        assertFailsWith<MatchCodecError.Malformed> { MatchLink.payload(linkCarrying(byteArrayOf(0xC3.toByte(), 0x28))) }
    }

    @Test
    fun `a blob that inflates past the payload limit is refused without being inflated in full`() {
        val json = SwiftSamples.FRESH.encodeToByteArray()
        val atLimit = ByteArray(MatchCodec.MAX_PAYLOAD_BYTES) { ' '.code.toByte() }.also { json.copyInto(it) }
        val bomb = ByteArray(32 * 1024 * 1024) { ' '.code.toByte() }.also { json.copyInto(it) }

        assertEquals(MatchCodec.decode(SwiftSamples.FRESH), MatchLink.payload(linkCarrying(atLimit)))
        assertFailsWith<MatchLinkError.Unreadable> { MatchLink.payload(linkCarrying(atLimit + ' '.code.toByte())) }
        assertTrue(linkCarrying(bomb).length < MatchLink.MAX_LINK_LENGTH, "the bomb must get past the length check")
        assertFailsWith<MatchLinkError.Unreadable> { MatchLink.payload(linkCarrying(bomb)) }
    }

    @Test
    fun `a link longer than the limit is refused`() {
        val link = "bananachess://m/1/" + "A".repeat(MatchLink.MAX_LINK_LENGTH)

        assertFailsWith<MatchLinkError.Unreadable> { MatchLink.payload(link) }
    }

    /** Stands in for the CPU-driven games of the Swift suite: the CPU is a later slice. */
    private fun playedOut(turns: Int): MatchPayload {
        var state = MatchPayload.newMatch(id = "abc", options = MatchOptions(RuleSet.Wildcard, BoardSetup.Random)).state
        while (!state.status.isOver && state.turnNumber < turns) {
            val actions = RulesEngine.legalActions(state)
            if (actions.isEmpty()) break
            state = RulesEngine.apply(actions[(state.turnNumber * 7) % actions.size], state).state
        }
        return MatchPayload(matchId = "abc", moveNumber = state.history.size, state = state)
    }

    private fun linkCarrying(bytes: ByteArray): String {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed, Deflater(Deflater.BEST_COMPRESSION, true)).use { it.write(bytes) }
        return "bananachess://m/1/" + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed.toByteArray())
    }
}

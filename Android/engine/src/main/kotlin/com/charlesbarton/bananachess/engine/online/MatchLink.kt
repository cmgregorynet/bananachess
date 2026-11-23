package com.charlesbarton.bananachess.engine.online

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Why a link was refused. [message] is written for the player. */
sealed class MatchLinkError(
    override val message: String,
) : Exception(message) {
    data object NotAMatchLink : MatchLinkError("That link isn't a Banana Chess match.")

    data class UnsupportedFormat(
        val found: Int,
        val supported: Int,
    ) : MatchLinkError("That match was sent from a newer version of Banana Chess.")

    data object Unreadable : MatchLinkError("That match link is damaged and can't be read.")
}

/**
 * A whole match, in a URL.
 *
 * This works because a [MatchPayload] is already the entire truth of a match: versioned,
 * self-contained, and refused outright if it does not match this build. Nothing has to be
 * stored anywhere: the link *is* the game, so two people can play across any messaging
 * app with no server, no accounts and no matchmaking.
 */
object MatchLink {
    /**
     * Bumped if the *encoding* changes, independently of the payload's own schema. A
     * reader that cannot read the wrapper should say so rather than mangle the contents.
     */
    const val FORMAT: Int = 1

    const val PATH: String = "m"

    /** Works with no domain and no hosting. */
    const val SCHEME: String = "bananachess"

    /**
     * The longest link [payload] will look at. iOS measures eighty turns at about 2,700
     * characters, so this allows for a match far longer than any that gets played.
     */
    const val MAX_LINK_LENGTH: Int = 1 shl 16

    /** A universal link, for when the app has an associated domain to hand. */
    fun url(
        payload: MatchPayload,
        host: String,
    ): String = "https://$host/$PATH/$FORMAT/${encode(payload)}"

    /** The scheme-only form, which needs nothing hosted anywhere. */
    fun url(payload: MatchPayload): String = "$SCHEME://$PATH/$FORMAT/${encode(payload)}"

    /**
     * Reads either form. The payload's own version guards still apply on top of this: a
     * link from an older rules build decodes here and is then refused by [MatchCodec],
     * which is the correct order, because a readable link that cannot be played is a
     * clearer failure than an unreadable one.
     *
     * @throws MatchLinkError when [url] is not a readable match link.
     * @throws MatchCodecError when it is, but the match inside cannot be played here.
     */
    fun payload(url: String): MatchPayload {
        if (url.length > MAX_LINK_LENGTH) throw MatchLinkError.Unreadable
        val text = url.trim()
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) throw MatchLinkError.NotAMatchLink
        val rest = text.substring(schemeEnd + 3).substringBefore('#').substringBefore('?')
        val parts = rest.substringAfter('/', "").split('/').filterTo(mutableListOf()) { it.isNotEmpty() }
        // The scheme form has no host, so its first path element sits where one would be.
        val host = rest.substringBefore('/')
        if (text.substring(0, schemeEnd) == SCHEME && host.isNotEmpty()) parts.add(0, host)

        if (parts.size != 3 || parts[0] != PATH) throw MatchLinkError.NotAMatchLink
        val sent = parts[1].toIntOrNull() ?: throw MatchLinkError.NotAMatchLink
        if (sent > FORMAT) throw MatchLinkError.UnsupportedFormat(found = sent, supported = FORMAT)
        return decode(parts[2])
    }

    /**
     * Compressed before encoding, because the payload is JSON and JSON of this shape is
     * mostly repeated key names. What iOS calls `.zlib` is a raw DEFLATE stream with no
     * header or checksum, hence `nowrap`.
     */
    internal fun encode(payload: MatchPayload): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(MatchCodec.encodeToBytes(payload))
            deflater.finish()
            val compressed = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(compressed.toByteArray())
        } finally {
            deflater.end()
        }
    }

    internal fun decode(blob: String): MatchPayload {
        val compressed =
            try {
                Base64.getUrlDecoder().decode(blob)
            } catch (_: IllegalArgumentException) {
                throw MatchLinkError.Unreadable
            }
        return MatchCodec.decode(inflate(compressed) ?: throw MatchLinkError.Unreadable)
    }

    /** Null when the stream is damaged, cut short, or would inflate past what a payload can be. */
    private fun inflate(compressed: ByteArray): ByteArray? {
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val json = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                json.write(buffer, 0, count)
                if (json.size() > MatchCodec.MAX_PAYLOAD_BYTES) return null
            }
            return json.toByteArray()
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    private const val BUFFER_SIZE = 8192
}

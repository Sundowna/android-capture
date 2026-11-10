package org.brain.capture

import org.brain.capture.pipeline.NonSpeech
import org.brain.capture.transcribe.Chunker
import org.brain.capture.transcribe.LoopGuard
import org.brain.capture.vault.NoteBuilder
import org.brain.capture.vault.NoteText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.sin

class NoteTextTest {
    private val note = NoteBuilder.voiceNote(
        captureId = "abc-123", at = OffsetDateTime.of(2026, 11, 10, 8, 53, 12, 0, ZoneOffset.ofHours(1)),
        durationSec = 42.0, audioFile = "a.ogg", transcript = "Hello world.", model = "m", language = "de",
    )

    @Test fun readsCaptureId() = assertEquals("abc-123", NoteText.captureId(note))
    @Test fun noIdWithoutFrontmatter() = assertNull(NoteText.captureId("just text\ncapture_id: x"))

    @Test fun displayDropsFrontmatterAndEmbeds() =
        assertEquals("Hello world.", NoteText.display(note))

    @Test fun displayKeepsAdditions() {
        val more = note + NoteBuilder.appendBlock(OffsetDateTime.now(), "More.", "b.ogg", null)
        val d = NoteText.display(more)
        assertTrue(d.startsWith("Hello world."))
        assertTrue(d.endsWith("More."))
        assertFalse(d.contains("![["))
    }

    @Test fun withBodyKeepsFrontmatterExactly() {
        val edited = NoteText.withBody(note, "Changed.")
        assertEquals(NoteText.split(note).first, NoteText.split(edited).first)
        assertEquals("Changed.", NoteText.display(edited))
        assertEquals("abc-123", NoteText.captureId(edited))
    }

    @Test fun handlesWindowsLineEndings() =
        assertEquals("abc-123", NoteText.captureId(note.replace("\n", "\r\n")))
}

class NoteBuilderTest {
    private val at = OffsetDateTime.of(2026, 11, 10, 16, 38, 5, 0, ZoneOffset.UTC)

    @Test fun nameHasSecondsAndNoColons() {
        val n = NoteBuilder.baseName(at, "Voice", "Hello: there")
        assertEquals("2026-11-10 16.38.05 Voice – Hello there", n)
        assertFalse(n.contains(':'))
    }

    @Test fun nameStripsCharactersObsidianRejects() {
        val n = NoteBuilder.baseName(at, "Voice", """a/b\c*d?e"f<g>h|i#j^k[l]m""")
        assertFalse(n.substringAfter("– ").any { it in """/\*?"<>|#^[]""" })
    }

    @Test fun nameWithoutText() = assertEquals("2026-11-10 16.38.05 Voice", NoteBuilder.baseName(at, "Voice", null))

    @Test fun typedAdditionHasNoAudio() {
        val b = NoteBuilder.appendBlock(at, "typed", null, null, typed = true)
        assertTrue(b.contains("(typed)"))
        assertFalse(b.contains("![["))
    }
}

class NonSpeechTest {
    @Test fun blankAudioIsEmpty() = assertEquals("", NonSpeech.strip("[BLANK_AUDIO]"))
    @Test fun silenceIsEmpty() = assertEquals("", NonSpeech.strip("[ Silence ]"))
    @Test fun soundTagsAreEmpty() = assertEquals("", NonSpeech.strip("(heartbeat)"))
    @Test fun keepsSpeech() = assertEquals("Hello there.", NonSpeech.strip("[BLANK_AUDIO] Hello there."))
    @Test fun keepsParenthesesInSpeech() = assertEquals("I said (quietly) hello", NonSpeech.strip("I said (quietly) hello"))
}

class LoopGuardTest {
    @Test fun stopsRepeatsAndDropsThem() {
        val out = arrayListOf(1, 2, 3, 7, 8, 7, 8, 7, 8, 7, 8)
        assertTrue(LoopGuard.isLooping(out))
        assertEquals(listOf(1, 2, 3, 7, 8), out)
    }

    @Test fun normalTextIsNotALoop() = assertFalse(LoopGuard.isLooping(arrayListOf(1, 2, 3, 4, 5, 6, 7, 8, 9)))
}

class ChunkerTest {
    private val rate = 16_000

    private fun speech(sec: Double) = FloatArray((sec * rate).toInt()) { (0.3 * sin(2 * PI * 220 * it / rate)).toFloat() }
    private fun quiet(sec: Double) = FloatArray((sec * rate).toInt())

    @Test fun shortAudioIsOnePiece() = assertEquals(1, Chunker.split(speech(10.0)).size)

    @Test fun piecesStayWithinLimit() {
        val pieces = Chunker.split(speech(95.0))
        assertTrue(pieces.all { it.size <= 28 * rate })
        assertEquals(95 * rate, pieces.sumOf { it.size })
    }

    @Test fun cutsInsideThePause() {
        // 24 s speech, 1 s pause, 10 s speech: the cut must fall inside the pause (24–25 s).
        val audio = speech(24.0) + quiet(1.0) + speech(10.0)
        val first = Chunker.split(audio).first().size / rate.toDouble()
        assertTrue("cut at $first s", first in 24.0..25.0)
    }
}

class ShareParseTest {
    @Test fun firefoxLinkWithTitleAsSubject() {
        val l = org.brain.capture.share.ShareParse.link("https://example.org/a?b=1", "Example page")!!
        assertEquals("https://example.org/a?b=1", l.url)
        assertEquals("Example page", l.title)
        assertNull(l.extraText)
    }

    @Test fun titleAndLinkInText() {
        val l = org.brain.capture.share.ShareParse.link("Great article https://example.org/x.", null)!!
        assertEquals("https://example.org/x", l.url)
        assertEquals("Great article", l.title)
    }

    @Test fun quoteWithLinkKeepsQuote() {
        val l = org.brain.capture.share.ShareParse.link("First line of a quote\nsecond line\nhttps://example.org", "Title")!!
        assertEquals("Title", l.title)
        assertEquals("First line of a quote\nsecond line", l.extraText)
    }

    @Test fun plainTextIsNotALink() = assertNull(org.brain.capture.share.ShareParse.link("just some words", null))

    @Test fun domain() = assertEquals("example.org", org.brain.capture.share.ShareParse.domain("https://www.example.org/path?q"))

    @Test fun fileKinds() {
        assertEquals("PDF", org.brain.capture.share.ShareParse.fileKind(listOf("application/pdf")))
        assertEquals("Image", org.brain.capture.share.ShareParse.fileKind(listOf("image/jpeg", "image/png")))
        assertEquals("File", org.brain.capture.share.ShareParse.fileKind(listOf("image/jpeg", "application/pdf")))
    }
}

class ShareNoteTest {
    private val at = OffsetDateTime.of(2026, 11, 10, 19, 0, 0, 0, ZoneOffset.UTC)

    @Test fun linkNoteHasUrlTitleAndId() {
        val n = NoteBuilder.linkNote("id-1", at, "https://example.org", "A \"quoted\" title: yes", null, "org.mozilla.firefox")
        assertTrue(n.contains("url: \"https://example.org\""))
        assertTrue(n.contains("title: \"A \\\"quoted\\\" title: yes\""))
        assertTrue(n.contains("source_app: org.mozilla.firefox"))
        assertEquals("id-1", NoteText.captureId(n))
        assertTrue(NoteText.display(n).contains("<https://example.org>"))
    }

    @Test fun fileNoteEmbedsPdfAndLinksOthers() {
        val n = NoteBuilder.fileNote("id-2", at, listOf(
            NoteBuilder.SharedFile("doc.pdf", "application/pdf", 10, "aa"),
            NoteBuilder.SharedFile("data.csv", "text/csv", 5, "bb")), null, null)
        assertTrue(n.contains("![[doc.pdf]]"))
        assertTrue(n.contains("\n[[data.csv]]"))
    }
}

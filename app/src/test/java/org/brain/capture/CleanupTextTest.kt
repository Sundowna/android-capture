package org.brain.capture

import org.brain.capture.cleanup.CleanupText
import org.brain.capture.cleanup.CleanupText.Bullet
import org.brain.capture.cleanup.CleanupText.Result
import org.brain.capture.vault.NoteBuilder
import org.brain.capture.vault.NoteText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class CleanupTextTest {
    @Test fun splitsSentences() =
        assertEquals(listOf("Hello there.", "Call the dentist!", "Is it Monday?", "ok"),
            CleanupText.segments(" Hello there. Call the dentist!  Is it Monday? ok "))

    @Test fun cutsVeryLongSentences() {
        val long = (1..120).joinToString(" ") { "word$it" }
        val segs = CleanupText.segments(long)
        assertTrue(segs.size > 1)
        assertTrue(segs.all { it.length <= 280 })
        assertEquals(long, segs.joinToString(" "))
    }

    @Test fun promptNumbersSegments() {
        val p = CleanupText.prompt(listOf("A.", "B."))
        assertTrue(p.contains("[1] A.\n[2] B."))
    }

    @Test fun acceptsGoodAnswer() {
        val r = CleanupText.check("- Call the dentist on Monday [1, 2]\n* Buy milk [3].\n- Range [1-3]", 3)
        assertEquals(Result.Ok(listOf(
            Bullet("Call the dentist on Monday", listOf(1, 2)), Bullet("Buy milk", listOf(3)), Bullet("Range", listOf(1, 2, 3)),
        )), r)
    }

    @Test fun rejectsMissingRefs() =
        assertTrue(CleanupText.check("- Call the dentist\n- Buy milk [2]", 2) is Result.Rejected)

    @Test fun rejectsOutOfRange() =
        assertTrue(CleanupText.check("- Buy milk [4]", 3) is Result.Rejected)

    @Test fun rejectsProse() =
        assertTrue(CleanupText.check("Here is the cleaned note:\n- Buy milk [1]", 1) is Result.Rejected)

    @Test fun ignoresCodeFences() =
        assertTrue(CleanupText.check("```\n- Buy milk [1]\n```", 1) is Result.Ok)

    private val note = NoteBuilder.voiceNote(
        captureId = "abc", at = OffsetDateTime.of(2026, 11, 16, 9, 0, 0, 0, ZoneOffset.UTC),
        durationSec = 10.0, audioFile = "a.ogg", transcript = "Uh buy milk. And, uh, call mum.", model = "m", language = "en",
    )

    @Test fun rewritesNote() {
        val segs = CleanupText.segments("Uh buy milk. And, uh, call mum.")
        val block = CleanupText.cleanBlock(listOf(Bullet("Buy milk", listOf(1)), Bullet("Call mum", listOf(2))), segs)
        val out = CleanupText.rewrite(note, "Uh buy milk. And, uh, call mum.", block, "gemma-4-E2B-it")!!
        assertEquals("abc", NoteText.captureId(out))
        assertTrue(NoteText.split(out).first.endsWith("cleanup: gemma-4-E2B-it\n---\n"))
        assertTrue(out.contains("- Buy milk [1]\n- Call mum [2]\n\n> [!quote]- Exactly what I said\n> [1] Uh buy milk.\n> [2] And, uh, call mum."))
        assertTrue(out.trimEnd().endsWith("![[a.ogg]]"))
        // A second run leaves the cleaned note alone.
        assertNull(CleanupText.rewrite(out, "Uh buy milk. And, uh, call mum.", block, "gemma-4-E2B-it"))
    }

    @Test fun cleansAdditionInCleanedNote() {
        val main = "Uh buy milk. And, uh, call mum."
        val cleaned = CleanupText.rewrite(note, main,
            CleanupText.cleanBlock(listOf(Bullet("Buy milk", listOf(1)), Bullet("Call mum", listOf(2))), CleanupText.segments(main)), "g")!!
        val added = "Also, um, one coffee."
        val withAddition = cleaned + NoteBuilder.appendBlock(OffsetDateTime.of(2026, 11, 16, 9, 1, 0, 0, ZoneOffset.UTC), added, "b.ogg", null)
        val block = CleanupText.cleanBlock(listOf(Bullet("One coffee", listOf(1))), CleanupText.segments(added))
        // Without the addition flag an already cleaned note is left alone.
        assertNull(CleanupText.rewrite(withAddition, added, block, "g"))
        val out = CleanupText.rewrite(withAddition, added, block, "g", addition = true)!!
        assertTrue(out.contains("**Added 09:01:00**\n\n- One coffee [1]\n\n> [!quote]- Exactly what I said\n> [1] Also, um, one coffee.\n\n![[b.ogg]]"))
        assertEquals(1, Regex("(?m)^cleanup:").findAll(out).count())
        assertTrue(out.contains("- Buy milk [1]\n- Call mum [2]"))
        // The exact words now sit only inside the quote, so a second run changes nothing.
        assertNull(CleanupText.rewrite(out, added, block, "g", addition = true))
    }

    @Test fun additionNeedsWholeLine() =
        assertNull(CleanupText.rewrite(note, "buy milk.", "x", "m", addition = true))

    @Test fun readsTitleAndTasks() {
        val r = CleanupText.check("Title: Errands for Friday\n- [ ] Call the dentist (due 2026-11-20) [1]\n- [ ] Buy milk [2]", 2)
        assertEquals(Result.Ok(listOf(
            Bullet("Call the dentist (due 2026-11-20)", listOf(1), task = true), Bullet("Buy milk", listOf(2), task = true),
        ), "Errands for Friday"), r)
        val stored = CleanupText.stored("Errands for Friday", (r as Result.Ok).bullets)
        assertEquals("# Errands for Friday\n- [ ] Call the dentist (due 2026-11-20) [1]\n- [ ] Buy milk [2]", stored)
        val lines = CleanupText.parseStored(stored)
        assertTrue(lines[0].title)
        assertEquals(CleanupText.Line("Buy milk", listOf(2), task = true), lines[2])
    }

    @Test fun boldTitleAccepted() =
        assertEquals("Ideas", (CleanupText.check("**Title:** Ideas\n- One [1]", 1) as Result.Ok).title)

    @Test fun taskPromptHasToday() =
        assertTrue(CleanupText.prompt(listOf("x"), "task", "2026-11-16").contains("Today is 2026-11-16."))

    @Test fun cardHidesSourcesAndQuote() {
        val body = "- Buy milk [1]\n- [ ] Call mum [2]\n\n> [!quote]- Exactly what I said\n> [1] Uh buy milk.\n> [2] Call mum.\n\n**Added 09:01:00**\n\nMore."
        assertEquals("• Buy milk\n☐ Call mum\n\nAdded 09:01\n\nMore.", CleanupText.forDisplay(body))
    }

    @Test fun leavesEditedNoteAlone() =
        assertNull(CleanupText.rewrite(note.replace("call mum", "call Mum"), "Uh buy milk. And, uh, call mum.", "x", "m"))
}

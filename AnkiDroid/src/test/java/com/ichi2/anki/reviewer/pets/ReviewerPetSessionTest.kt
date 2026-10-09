// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer.pets

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReviewerPetSessionTest : RobolectricTest() {
    private lateinit var savedState: SavedStateHandle
    private lateinit var session: ReviewerPetSession
    private var now = 0L
    private val config =
        ReviewerPetConfig(
            enabled = true,
            flyProbabilityPercent = 100,
            flyCooldownSeconds = 30,
            reminderSeconds = 5,
            repeatReminderSeconds = 5,
            autoExpandSeconds = 5,
        )

    @Before
    fun createSession() {
        savedState = SavedStateHandle()
        session = ReviewerPetSession(savedState)
        session.configure(config, hasMedia = true)
        session.attach(GENERATION)
        session.beginVisit(1)
        now = 0
        report("rendered", "hidden")
    }

    @Test
    fun `reminder counts only effective foreground time`() {
        assertNull(session.tick(now, allowed = true))
        repeat(3) { assertNull(tickAfter(1000)) }
        assertNull(tickAfter(1000, allowed = false))
        assertNull(tickAfter(60000, allowed = false))
        assertNull(tickAfter(60000))
        assertEquals(3000L, snapshot.optLong("elapsed"))
        assertNull(tickAfter(1000))
        assertEquals("appear", tickAfter(1000))
    }

    @Test
    fun `pausing clears a queued flight and avoids background time`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 0)
        session.pause()
        assertNull(tickAfter(60000))
        assertEquals(0L, snapshot.optLong("elapsed"))
        assertNull(tickAfter(1000))
    }

    @Test
    fun `dismissal suppresses all further reminders during the same visit by default`() {
        appear()
        report("dismissed", "hidden")
        repeat(20) { assertNull(tickAfter(1000)) }
        assertTrue(snapshot.optBoolean("dismissed"))
    }

    @Test
    fun `repeat reminder waits for the configured interval after dismissal`() {
        session.configure(config.copy(repeatReminder = true), hasMedia = true)
        appear()
        report("dismissed", "hidden")
        repeat(4) { assertNull(tickAfter(1000)) }
        assertEquals("appear", tickAfter(1000))
    }

    @Test
    fun `revisiting the same card resets reminder eligibility`() {
        appear()
        report("dismissed", "hidden")
        val previousVisit = session.visitId
        session.beginVisit(1)
        report("rendered", session.visualState.optString("mode", "hidden"))
        assertEquals(previousVisit + 1, session.visitId)
        assertEquals(0L, snapshot.optLong("elapsed"))
        assertFalse(snapshot.optBoolean("dismissed"))
        assertFalse(snapshot.optBoolean("reminded"))
        session.tick(now, allowed = true)
        repeat(4) { assertNull(tickAfter(1000)) }
        assertEquals("appear", tickAfter(1000))
    }

    @Test
    fun `beginning another visit preserves the visible pet and geometry`() {
        report("collapsed", "collapsed", x = 0.9, y = 0.4)
        session.tick(now, allowed = true)
        repeat(3) { assertNull(tickAfter(1000)) }
        session.beginVisit(1)
        report("rendered", session.visualState.optString("mode", "hidden"))
        assertEquals("collapsed", session.visualState.optString("mode"))
        assertEquals(0.9, session.visualState.optDouble("x"))
        assertEquals(0.4, session.visualState.optDouble("y"))
        assertEquals(0L, snapshot.optLong("elapsed"))
        assertEquals(0L, snapshot.optLong("collapsedElapsed"))
    }

    @Test
    fun `saved session restores the single pet after WebView recreation`() {
        report("appeared", "resident", x = 0.2, y = 0.7)
        session.tick(now, allowed = true)
        tickAfter(1000)
        val restored = ReviewerPetSession(savedState)
        restored.configure(config, hasMedia = true)
        restored.attach("new-page")
        assertEquals(session.visitId, restored.visitId)
        assertEquals("resident", restored.visualState.optString("mode"))
        assertEquals(0.2, restored.visualState.optDouble("x"))
        assertEquals(0.7, restored.visualState.optDouble("y"))
        restored.report(
            JSONObject()
                .put("generation", "new-page")
                .put("visitId", restored.visitId)
                .put("action", "rendered")
                .put("state", restored.visualState),
            now,
        )
        assertNull(restored.tick(now + 60000, allowed = true))
        assertEquals(1000L, snapshot.optLong("elapsed"))
    }

    @Test
    fun `WebView recreation restores stable modes and allows a later rating`() {
        val transitions =
            listOf(
                Triple("moving", "resident", "resident"),
                Triple("flying", "collapsed", "collapsed"),
                Triple("exiting", "resident", "hidden"),
                Triple("moving", "invalid", "hidden"),
            )
        for ((previousMode, returnMode, expectedMode) in transitions) {
            val visual =
                JSONObject()
                    .put("mode", previousMode)
                    .put("returnMode", returnMode)
                    .put("x", 0.2)
                    .put("y", 0.7)
            savedState["reviewerPetSession"] =
                JSONObject()
                    .put("visitId", 1L)
                    .put("visual", visual)
                    .toString()
            session = ReviewerPetSession(savedState)
            session.configure(config.copy(reminderEnabled = false), hasMedia = true)
            session.attach(GENERATION)
            assertEquals(expectedMode, session.visualState.optString("mode"))
            assertEquals(0.2, session.visualState.optDouble("x"))
            assertEquals(0.7, session.visualState.optDouble("y"))
            report("rendered", expectedMode)
            session.onManualRating(now, randomPercent = 0)
            nextRenderedVisit()
            assertEquals("fly", tickAfter(100))
        }
    }

    @Test
    fun `flight waits for next visit rendering and ignores an old visit render report`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 0)
        val sourceVisit = session.visitId
        assertNull(tickAfter(100))
        session.beginVisit(2)
        assertNull(tickAfter(100))
        session.report(
            JSONObject()
                .put("generation", GENERATION)
                .put("visitId", sourceVisit)
                .put("action", "rendered")
                .put("state", JSONObject().put("mode", "hidden")),
            now,
        )
        assertNull(tickAfter(100))
        assertEquals(0L, snapshot.optLong("elapsed"))
        report("rendered", "hidden")
        assertEquals("fly", tickAfter(100))
    }

    @Test
    fun `a rendered card can schedule a flight before the first timer tick`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        assertEquals("fly", tickAfter(100))
    }

    @Test
    fun `a slow next card render does not expire the rating flight`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 0)
        session.beginVisit(2)
        assertNull(tickAfter(5000))
        report("rendered", "hidden")
        assertEquals("fly", tickAfter(100))
    }

    @Test
    fun `next card autoplay pauses presentation without losing its flight`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        now += 250
        assertNull(session.tick(now, allowed = false, active = true))
        now += 5000
        assertNull(session.tick(now, allowed = false, active = true))
        assertEquals(0L, snapshot.optLong("elapsed"))
        assertEquals("fly", tickAfter(250))
    }

    @Test
    fun `manual rating during audio can schedule the next visit flight`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.tick(now, allowed = false, active = true)
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        assertEquals("fly", tickAfter(100))
    }

    @Test
    fun `another card visit discards an unpresented flight`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        now += 250
        assertNull(session.tick(now, allowed = false, active = true))
        nextRenderedVisit()
        assertNull(tickAfter(100))
    }

    @Test
    fun `an extended occupied interval expires the flight without a delayed backlog`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        now += 250
        assertNull(session.tick(now, allowed = false, active = true))
        now += 60000
        assertNull(session.tick(now, allowed = false, active = true))
        assertNull(tickAfter(250))
        assertFalse(snapshot.has("lastFlight"))
    }

    @Test
    fun `geometry reports cannot release a queued flight and transient rejection retries once ready`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        assertEquals("fly", tickAfter(100))
        report("geometry", "resident")
        assertNull(tickAfter(100))
        report("skipped", "resident")
        assertNull(tickAfter(100))
        assertEquals("fly", tickAfter(500))
        report("flightStarted", "flying")
        report("flightEnded", "resident")
        assertNull(tickAfter(500))
    }

    @Test
    fun `a probability miss never spends flight cooldown`() {
        session.configure(config.copy(flyProbabilityPercent = 50, reminderEnabled = false), hasMedia = true)
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 50)
        assertNull(tickAfter(100))
        assertFalse(snapshot.has("lastFlight"))
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        assertEquals("fly", tickAfter(100))
        assertFalse(snapshot.has("lastFlight"))
    }

    @Test
    fun `skipped and expired flights do not spend cooldown`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 0)
        assertNull(tickAfter(2000))
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        assertEquals("fly", tickAfter(100))
        report("skipped", "hidden")
        assertFalse(snapshot.has("lastFlight"))
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        assertEquals("fly", tickAfter(100))
    }

    @Test
    fun `only a flightStarted report starts cooldown`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        assertEquals("fly", tickAfter(100))
        report("flightStarted", "flying")
        val flightStarted = now
        report("landed", "resident")
        session.onManualRating(now, randomPercent = 0)
        assertNull(tickAfter(100))
        now = flightStarted + 29999
        session.onManualRating(now, randomPercent = 0)
        assertNull(session.tick(now, allowed = true))
        now = flightStarted + 30000
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        assertEquals("fly", session.tick(now, allowed = true))
    }

    @Test
    fun `manual ratings require foreground and do not duplicate a reserved flight`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        session.onManualRating(now, randomPercent = 0)
        assertNull(session.tick(now, allowed = true))
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        assertEquals("fly", tickAfter(100))
        session.onManualRating(now, randomPercent = 0)
        assertNull(tickAfter(100))
        report("skipped", "hidden")
        assertNull(tickAfter(100))
        session.pause()
        session.onManualRating(now, randomPercent = 0)
        assertNull(tickAfter(100))
    }

    @Test
    fun `a report from an old WebView generation is ignored`() {
        report("appeared", "resident", x = 0.3, y = 0.6)
        session.attach("replacement-page")
        session.report(
            JSONObject()
                .put("generation", GENERATION)
                .put("visitId", session.visitId)
                .put("action", "dismissed")
                .put("state", JSONObject().put("mode", "hidden")),
            now,
        )
        assertEquals("resident", session.visualState.optString("mode"))
        assertEquals(0.3, session.visualState.optDouble("x"))
        assertFalse(snapshot.optBoolean("dismissed"))
    }

    @Test
    fun `disabling clears queued flight and hides the pet`() {
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        report("appeared", "resident")
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 0)
        session.configure(config.copy(enabled = false), hasMedia = true)
        assertEquals("hidden", session.visualState.optString("mode"))
        assertNull(tickAfter(100))
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        assertNull(tickAfter(100))
    }

    @Test
    fun `late reports cannot redisplay a disabled pet`() {
        report("appeared", "resident")
        session.configure(config.copy(enabled = false), hasMedia = true)
        report("appeared", "resident")
        assertEquals("hidden", session.visualState.optString("mode"))
        session.configure(config, hasMedia = false)
        report("flightStarted", "flying")
        assertEquals("hidden", session.visualState.optString("mode"))
        assertFalse(snapshot.has("lastFlight"))
    }

    @Test
    fun `losing all active media clears presentation and pending actions`() {
        session.tick(now, allowed = true)
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        assertEquals("fly", tickAfter(100))
        session.configure(config.copy(reminderEnabled = false), hasMedia = false)
        assertEquals("hidden", session.visualState.optString("mode"))
        assertNull(tickAfter(100))
        session.configure(config.copy(reminderEnabled = false), hasMedia = true)
        assertNull(tickAfter(100))
        session.onManualRating(now, randomPercent = 0)
        nextRenderedVisit()
        assertEquals("fly", tickAfter(100))
    }

    @Test
    fun `collapsed pet expands only after effective foreground waiting time`() {
        report("collapsed", "collapsed")
        session.tick(now, allowed = true)
        repeat(3) { assertNull(tickAfter(1000)) }
        assertNull(tickAfter(1000, allowed = false))
        assertNull(tickAfter(60000))
        assertNull(tickAfter(1000))
        assertEquals("expand", tickAfter(1000))
    }

    @Test
    fun `dragging the collapsed pet restarts the auto expand wait`() {
        report("collapsed", "collapsed")
        session.tick(now, allowed = true)
        repeat(4) { assertNull(tickAfter(1000)) }
        report("dragged", "collapsed", y = 0.8)
        repeat(4) { assertNull(tickAfter(1000)) }
        assertEquals(0.8, session.visualState.optDouble("y"))
        assertEquals("expand", tickAfter(1000))
    }

    @Test
    fun `auto expand can be disabled while edge position remains`() {
        session.configure(config.copy(autoExpand = false), hasMedia = true)
        report("collapsed", "collapsed", y = 0.8)
        session.tick(now, allowed = true)
        repeat(10) { assertNull(tickAfter(1000)) }
        assertEquals("collapsed", session.visualState.optString("mode"))
        assertEquals(0.8, session.visualState.optDouble("y"))
    }

    private val snapshot: JSONObject
        get() = JSONObject(savedState.get<String>("reviewerPetSession")!!)

    private fun tickAfter(
        milliseconds: Long,
        allowed: Boolean = true,
    ): String? {
        now += milliseconds
        return session.tick(now, allowed)
    }

    private fun nextRenderedVisit() {
        session.beginVisit(snapshot.optLong("cardId") + 1)
        report("rendered", session.visualState.optString("mode", "hidden"))
    }

    private fun appear() {
        session.tick(now, allowed = true)
        repeat(4) { assertNull(tickAfter(1000)) }
        assertEquals("appear", tickAfter(1000))
        report("appeared", "resident")
    }

    private fun report(
        action: String,
        mode: String,
        x: Double = 0.5,
        y: Double = 0.5,
    ) {
        session.report(
            JSONObject()
                .put("generation", GENERATION)
                .put("visitId", session.visitId)
                .put("action", action)
                .put("state", JSONObject().put("mode", mode).put("x", x).put("y", y)),
            now,
        )
    }

    companion object {
        private const val GENERATION = "page-1"
    }
}

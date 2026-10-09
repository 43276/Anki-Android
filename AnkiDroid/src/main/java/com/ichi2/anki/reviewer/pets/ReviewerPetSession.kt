// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer.pets

import androidx.lifecycle.SavedStateHandle
import org.json.JSONObject
import kotlin.random.Random

/** Review-session state survives card DOM replacement and recreation of the WebView. */
class ReviewerPetSession(
    private val savedState: SavedStateHandle,
) {
    private var config: ReviewerPetConfig? = null
    private var hasMedia = false
    private var lastTick: Long? = null
    private var quietUntil = 0L
    private var pendingFlightExpiresAt = 0L
    private var pendingFlightRemaining = 0L
    private var flightRetryAt = 0L
    private var flightSourceVisit = 0L
    private var rendered = false
    private var paused = true
    var isWebBusy = false
        private set
    private var awaitingPresentation = false
    private var lastCommand = 0L
    private var generation = ""
    private var state = JSONObject(savedState.get<String>(KEY) ?: "{}")

    val visitId: Long get() = state.optLong("visitId")
    val visualState: JSONObject get() = state.optJSONObject("visual") ?: JSONObject().put("mode", "hidden")
    private val elapsed: Long get() = state.optLong("elapsed")
    private val mode: String get() = visualState.optString("mode", "hidden")

    fun configure(
        config: ReviewerPetConfig,
        hasMedia: Boolean,
    ) {
        this.config = config
        this.hasMedia = hasMedia
        if (!config.enabled || !hasMedia) {
            isWebBusy = false
            clearPendingFlight()
            awaitingPresentation = false
            state.put("visual", JSONObject().put("mode", "hidden"))
            save()
        }
    }

    fun attach(generation: String) {
        this.generation = generation
        awaitingPresentation = false
        lastTick = null
        rendered = false
        isWebBusy = false
        paused = false
        val visual = visualState
        if (visual.optString("mode") in listOf("flying", "moving", "exiting")) {
            val returnMode = visual.optString("returnMode")
            val restoredMode =
                if (visual.optString("mode") != "exiting" && returnMode in listOf("hidden", "resident", "collapsed")) {
                    returnMode
                } else {
                    "hidden"
                }
            visual.put("mode", restoredMode)
            save()
        }
    }

    fun beginVisit(cardId: Long) {
        state.put("cardId", cardId)
        state.put("visitId", visitId + 1)
        if (flightSourceVisit != 0L && visitId > flightSourceVisit + 1) clearPendingFlight()
        state.put("elapsed", 0L)
        state.put("dismissed", false)
        state.put("reminded", false)
        state.put("collapsedElapsed", 0L)
        awaitingPresentation = false
        lastTick = null
        rendered = false
        save()
    }

    fun pause() {
        paused = true
        lastTick = null
        clearPendingFlight()
        awaitingPresentation = false
    }

    fun activity(now: Long) {
        quietUntil = now + 3000
    }

    /** Called only after the scheduler has successfully persisted a manual GOOD/EASY answer. */
    fun onManualRating(
        now: Long,
        randomPercent: Int = Random.nextInt(100),
    ) {
        val c = config ?: return
        if (!c.enabled || !hasMedia || !rendered || paused || state.optBoolean("dismissed") || awaitingPresentation) return
        if (mode !in listOf("hidden", "resident", "collapsed")) return
        val lastFlight = state.optLong("lastFlight", Long.MIN_VALUE)
        if (lastFlight != Long.MIN_VALUE && now - lastFlight < c.flyCooldownSeconds * 1000L) return
        if (randomPercent >= c.flyProbabilityPercent) return
        // Only the next visit may use this rating. Its render and autoplay can take longer than a timer tick.
        pendingFlightExpiresAt = 0L
        pendingFlightRemaining = FLIGHT_PRESENTATION_WINDOW_MS
        flightRetryAt = 0L
        flightSourceVisit = visitId
    }

    /** Advances only effective foreground time. Returning a command reserves it until JS responds. */
    fun tick(
        now: Long,
        allowed: Boolean,
        active: Boolean = allowed,
    ): String? {
        val c = config ?: return null
        if (pendingFlightExpiresAt != 0L && now > pendingFlightExpiresAt) clearPendingFlight()
        if (!active || !c.enabled || !hasMedia) {
            pause()
            quietUntil = now + 3000
            return null
        }
        paused = false
        if (!allowed || !rendered) {
            lastTick = null
            return null
        }
        val delta = lastTick?.let { (now - it).coerceIn(0, 1500) } ?: 0L
        lastTick = now
        state.put("elapsed", elapsed + delta)
        if (mode == "collapsed") state.put("collapsedElapsed", state.optLong("collapsedElapsed") + delta)
        if (flightSourceVisit != 0L && visitId == flightSourceVisit + 1) {
            pendingFlightRemaining -= delta
            if (pendingFlightRemaining <= 0L) clearPendingFlight()
        }
        save()
        if (awaitingPresentation && now - lastCommand >= 10000) awaitingPresentation = false
        if (awaitingPresentation) return null
        if (flightSourceVisit != 0L) {
            if (visitId <= flightSourceVisit) return null
            if (now >= flightRetryAt && mode in listOf("hidden", "resident", "collapsed")) {
                awaitingPresentation = true
                lastCommand = now
                return "fly"
            }
            return null
        }
        if (now < quietUntil) return null
        if (mode == "collapsed" && c.autoExpand && state.optLong("collapsedElapsed") >= c.autoExpandSeconds * 1000L) {
            awaitingPresentation = true
            lastCommand = now
            return "expand"
        }
        if (!c.reminderEnabled || mode != "hidden" || elapsed < c.reminderSeconds * 1000L) return null
        if (state.optBoolean("dismissed")) {
            if (!c.repeatReminder || elapsed - state.optLong("dismissedAt") < c.repeatReminderSeconds * 1000L) return null
        } else if (state.optBoolean("reminded")) {
            return null
        }
        awaitingPresentation = true
        lastCommand = now
        return "appear"
    }

    fun report(
        report: JSONObject,
        now: Long,
    ) {
        if (report.optString("generation") != generation) return
        if (config?.enabled != true || !hasMedia) return
        val action = report.optString("action")
        if (report.has("busy")) isWebBusy = report.optBoolean("busy")
        if (action == "rendered") {
            if (report.optLong("visitId") == visitId) {
                rendered = true
                if (flightSourceVisit != 0L && visitId == flightSourceVisit + 1 && pendingFlightExpiresAt == 0L) {
                    pendingFlightExpiresAt = now + FLIGHT_MAX_WAIT_MS
                }
            }
            return
        }
        if (action == "activity") {
            activity(now)
            return
        }
        if (action in listOf("flightStarted", "appeared", "moved", "skipped", "unavailable", "mediaFailed", "paused", "disabled")) {
            awaitingPresentation = false
        }
        if (action in listOf("skipped", "unavailable", "mediaFailed")) flightRetryAt = now + 500L
        if (action in listOf("unavailable", "skipped", "mediaFailed")) quietUntil = now + 10000
        val visual = report.optJSONObject("state") ?: return
        if (visual.optString("mode") !in listOf("hidden", "resident", "collapsed", "flying", "moving", "exiting")) return
        // Accept geometry only, never a filename or a filesystem path from the page.
        state.put(
            "visual",
            JSONObject()
                .put("mode", visual.optString("mode"))
                .put("x", visual.optDouble("x", 0.5).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.5)
                .put("y", visual.optDouble("y", 0.5).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.5)
                .put("edge", if (visual.optString("edge") == "left") "left" else "right")
                .put("media", visual.optInt("media", -1))
                .put("fallback", visual.optBoolean("fallback"))
                .put("returnMode", visual.optString("returnMode", "hidden")),
        )
        if (action == "flightStarted") {
            state.put("lastFlight", now)
            clearPendingFlight()
        }
        if (report.optLong("visitId") == visitId) {
            if (action == "dismissed") {
                state.put("dismissed", true)
                state.put("dismissedAt", elapsed)
                clearPendingFlight()
            }
            if (action == "appeared") {
                state.put("reminded", true)
                state.put("dismissed", false)
            }
            if (action in listOf("collapsed", "dragged", "held")) state.put("collapsedElapsed", 0L)
        }
        save()
    }

    private fun save() {
        savedState[KEY] = state.toString()
    }

    private fun clearPendingFlight() {
        flightSourceVisit = 0L
        pendingFlightExpiresAt = 0L
        pendingFlightRemaining = 0L
        flightRetryAt = 0L
    }

    companion object {
        private const val KEY = "reviewerPetSession"
        private const val FLIGHT_PRESENTATION_WINDOW_MS = 15000L
        private const val FLIGHT_MAX_WAIT_MS = 60000L
    }
}

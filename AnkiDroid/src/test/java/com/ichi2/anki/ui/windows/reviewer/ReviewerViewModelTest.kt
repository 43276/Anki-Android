// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import app.cash.turbine.test
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.reviewer.pets.ReviewerPetConfig
import com.ichi2.anki.ui.windows.reviewer.autoadvance.AnswerAction
import com.ichi2.anki.utils.ext.cardStateCustomizer
import kotlinx.coroutines.test.advanceUntilIdle
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReviewerViewModelTest : RobolectricTest() {
    private val viewModelStore = ViewModelStore()

    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    @Before
    fun disableAutoplay() {
        // stored in collection; no need for a reset
        updateDeckConfig(col.decks.selected()) { autoplay = false }
    }

    @After
    fun clearViewModels() {
        viewModelStore.clear()
    }

    @Test
    fun `successful manual GOOD rating schedules a pet flight`() =
        runTest {
            val card = addBasicNote().firstCard()
            addBasicNote("next", "answer")
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }
            viewModel.onPageFinished(false)
            advanceUntilIdle()
            val session = viewModel.reviewerPetSession
            session.configure(ReviewerPetConfig(enabled = true, flyProbabilityPercent = 100, reminderEnabled = false), hasMedia = true)
            session.attach("test-page")
            reportRendered(viewModel)
            session.tick(SystemClock.elapsedRealtime(), allowed = true)
            viewModel.onShowAnswer()
            advanceUntilIdle()

            viewModel.answerCard(Rating.GOOD)
            advanceUntilIdle()
            assertEquals(1, col.getCard(card.id).reps)
            reportRendered(viewModel)
            assertEquals("fly", session.tick(SystemClock.elapsedRealtime(), allowed = true))
        }

    @Test
    fun `successful manual EASY rating during audio waits for the rendered next card`() =
        runTest {
            val card = addBasicNote().firstCard()
            addBasicNote("next", "answer")
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }
            viewModel.onPageFinished(false)
            advanceUntilIdle()
            val session = viewModel.reviewerPetSession
            session.configure(ReviewerPetConfig(enabled = true, flyProbabilityPercent = 100, reminderEnabled = false), hasMedia = true)
            session.attach("test-page")
            reportRendered(viewModel)
            session.tick(SystemClock.elapsedRealtime(), allowed = false, active = true)
            viewModel.onShowAnswer()
            advanceUntilIdle()

            viewModel.answerCard(Rating.EASY)
            advanceUntilIdle()
            assertEquals(1, col.getCard(card.id).reps)
            reportRendered(viewModel)
            val renderedAt = SystemClock.elapsedRealtime()
            assertNull(session.tick(renderedAt + 5000, allowed = false, active = true))
            assertEquals("fly", session.tick(renderedAt + 5250, allowed = true, active = true))
        }

    @Test
    fun `automatic GOOD rating never schedules a pet flight`() =
        runTest {
            val card = addBasicNote().firstCard()
            addBasicNote("next", "answer")
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }
            viewModel.onPageFinished(false)
            advanceUntilIdle()
            val session = viewModel.reviewerPetSession
            session.configure(ReviewerPetConfig(enabled = true, flyProbabilityPercent = 100, reminderEnabled = false), hasMedia = true)
            session.attach("test-page")
            reportRendered(viewModel)
            session.tick(SystemClock.elapsedRealtime(), allowed = true)
            viewModel.onShowAnswer()
            advanceUntilIdle()

            viewModel.onAutoAdvanceAction(AnswerAction.ANSWER_GOOD)
            advanceUntilIdle()
            assertEquals(1, col.getCard(card.id).reps)
            reportRendered(viewModel)
            assertNull(session.tick(SystemClock.elapsedRealtime(), allowed = true))
        }

    @Test
    fun `manual rating waiting for scheduler mutation cannot trigger a pet flight`() =
        runTest {
            val card = addBasicNote().firstCard()
            addBasicNote("next", "answer")
            col.cardStateCustomizer = "await new Promise(resolve => setTimeout(resolve, 5000));"
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }
            viewModel.onShowAnswer()
            viewModel.onPageFinished(false)
            advanceUntilIdle()
            val session = viewModel.reviewerPetSession
            session.configure(ReviewerPetConfig(enabled = true, flyProbabilityPercent = 100, reminderEnabled = false), hasMedia = true)
            session.attach("test-page")
            reportRendered(viewModel)
            session.tick(SystemClock.elapsedRealtime(), allowed = true)

            viewModel.answerCard(Rating.GOOD)
            advanceUntilIdle()
            assertEquals(0, col.getCard(card.id).reps)
            assertNull(session.tick(SystemClock.elapsedRealtime(), allowed = true))

            viewModel.onStateMutationCallback()
            advanceUntilIdle()
            assertEquals(1, col.getCard(card.id).reps)
            reportRendered(viewModel)
            assertEquals("fly", session.tick(SystemClock.elapsedRealtime(), allowed = true))
        }

    @Test
    fun `answering waits for the custom scheduler of the first card`() =
        runTest {
            val card = addBasicNote().firstCard()
            col.cardStateCustomizer = "await new Promise(resolve => setTimeout(resolve, 5000));"
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }

            viewModel.onShowAnswer()
            viewModel.answerCard(Rating.GOOD)
            viewModel.onPageFinished(false)
            advanceUntilIdle()
            assertFalse(viewModel.showingAnswer.value)
            assertEquals(0, col.getCard(card.id).reps)

            viewModel.onStateMutationCallback()
            advanceUntilIdle()
            assertEquals(1, col.getCard(card.id).reps)
        }

    @Test
    fun `restored answer can be graded after process death`() =
        runTest {
            val card = addBasicNote().firstCard()
            val savedStateHandle = SavedStateHandle(mapOf("showingAnswer" to true))
            val viewModel = ReviewerViewModel(savedStateHandle).also { viewModelStore.put("reviewer", it) }

            viewModel.onPageFinished(true)
            advanceUntilIdle()
            assertTrue(viewModel.showingAnswer.value)

            viewModel.answerCard(Rating.GOOD)
            advanceUntilIdle()
            assertEquals(1, col.getCard(card.id).reps)
        }

    @Test
    fun `restored answer waits for the custom scheduler after process death`() =
        runTest {
            val card = addBasicNote().firstCard()
            col.cardStateCustomizer = "await new Promise(resolve => setTimeout(resolve, 5000));"
            val savedStateHandle = SavedStateHandle(mapOf("showingAnswer" to true))
            val viewModel = ReviewerViewModel(savedStateHandle).also { viewModelStore.put("reviewer", it) }

            viewModel.statesMutationEvalFlow.test {
                viewModel.onPageFinished(true)
                advanceUntilIdle()
                assertTrue(viewModel.showingAnswer.value)
                assertTrue(awaitItem().contains(col.cardStateCustomizer))

                viewModel.answerCard(Rating.GOOD)
                advanceUntilIdle()
                assertEquals(0, col.getCard(card.id).reps)
                cancelAndIgnoreRemainingEvents()

                viewModel.onStateMutationCallback()
                advanceUntilIdle()
                assertEquals(1, col.getCard(card.id).reps)
            }
        }

    @Test
    fun `restoring answer with retained view model does not rerun custom scheduler`() =
        runTest {
            val card = addBasicNote().firstCard()
            col.cardStateCustomizer = "await new Promise(resolve => setTimeout(resolve, 5000));"
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }

            viewModel.statesMutationEvalFlow.test {
                viewModel.onPageFinished(false)
                advanceUntilIdle()
                awaitItem()
                viewModel.onStateMutationCallback()
                viewModel.onShowAnswer()
                advanceUntilIdle()
                assertTrue(viewModel.showingAnswer.value)

                viewModel.onPageFinished(true)
                advanceUntilIdle()
                expectNoEvents()
                assertTrue(viewModel.showingAnswer.value)
                cancelAndIgnoreRemainingEvents()

                viewModel.answerCard(Rating.GOOD)
                advanceUntilIdle()
                assertEquals(1, col.getCard(card.id).reps)
            }
        }

    private fun reportRendered(viewModel: ReviewerViewModel) {
        val session = viewModel.reviewerPetSession
        session.report(
            JSONObject()
                .put("generation", "test-page")
                .put("visitId", session.visitId)
                .put("action", "rendered")
                .put("state", session.visualState),
            SystemClock.elapsedRealtime(),
        )
    }
}

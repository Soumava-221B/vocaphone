package com.vocahq.vocaphone.dictation

import com.vocahq.vocaphone.local.LocalModelState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three exits of a keyboard mic tap taken while a model is downloading.
 *
 * The repair state used to snapshot the percentage once and never move; the
 * keyboard then showed a frozen number and could not start dictation until
 * something else reset it. The wait now follows the download, and these are
 * the rules it follows.
 */
class DownloadFollowTest {

    private val before = setOf("tiny-q5_1")

    @Test
    fun aRunningDownloadIsAWait() {
        val state = LocalModelState(downloaded = before, downloading = "parakeet-tdt-0.6b-v2-en", progress = 40)
        assertEquals(DownloadOutcome.WAITING, downloadOutcome(state, before))
    }

    /** Done means a file that was not there at entry — the chosen id is written later. */
    @Test
    fun aNewFileOnDiskMeansItLanded() {
        val state = LocalModelState(downloaded = before + "parakeet-tdt-0.6b-v2-en", downloading = null)
        assertEquals(DownloadOutcome.LANDED, downloadOutcome(state, before))
    }

    @Test
    fun landedWinsEvenIfAnotherDownloadHasAlreadyStarted() {
        val state = LocalModelState(downloaded = before + "parakeet-tdt-0.6b-v2-en", downloading = "canary-180m-flash")
        assertEquals(DownloadOutcome.LANDED, downloadOutcome(state, before))
    }

    @Test
    fun aDownloadThatStopsWithoutLandingDied() {
        val state = LocalModelState(downloaded = before, downloading = null)
        assertEquals(DownloadOutcome.DIED, downloadOutcome(state, before))
    }

    /** A file that was already there at entry is not this download landing. */
    @Test
    fun filesPresentAtEntryDoNotCountAsLanding() {
        val state = LocalModelState(downloaded = before, downloading = "parakeet-tdt-0.6b-v2-en", progress = 5)
        assertEquals(DownloadOutcome.WAITING, downloadOutcome(state, before))
    }
}

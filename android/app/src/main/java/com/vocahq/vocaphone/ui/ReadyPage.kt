package com.vocahq.vocaphone.ui

import com.vocahq.vocaphone.local.LocalModelState

/**
 * How the last setup page presents itself.
 *
 * [SetupStatus.isReadyToDictate] answers "may setup reach this page", and it
 * says yes while a model is still downloading — it has to, or the page would
 * bounce the person back to the model step the moment they arrived. The page
 * needs a second answer, "may they leave and dictate", which is no until the
 * model has landed and been adopted. That is what this decides.
 */
internal enum class ReadyPagePresentation { READY, WAITING_FOR_MODEL, NEEDS_ATTENTION }

/**
 * [LocalModelState.pendingUse] is set by "Download and use" and cleared once
 * the file has landed and been adopted — and on cancel and failure. It is the
 * signal the keyboard follows too, so the page and the keyboard cannot
 * disagree about whether dictation works yet.
 *
 * NEEDS_ATTENTION is checked first: a cancelled or failed download clears
 * `pendingUse` and unsets the source requirement together, and that must read
 * as "review setup", never as a wait that nothing will end.
 */
internal fun readyPagePresentation(
    status: SetupStatus,
    localTranscriptionEnabled: Boolean,
    models: LocalModelState,
): ReadyPagePresentation = when {
    !status.isReadyToDictate -> ReadyPagePresentation.NEEDS_ATTENTION
    localTranscriptionEnabled && models.pendingUse != null -> ReadyPagePresentation.WAITING_FOR_MODEL
    else -> ReadyPagePresentation.READY
}

/**
 * The primary button's label. [progressLine] is passed in rather than the
 * state because [com.vocahq.vocaphone.local.downloadProgressLine] reads the
 * clock, and this must stay testable without one.
 */
internal fun readyPageButtonLabel(presentation: ReadyPagePresentation, progressLine: String?): String =
    when (presentation) {
        ReadyPagePresentation.READY -> SetupCopy.START
        ReadyPagePresentation.NEEDS_ATTENTION -> SetupCopy.REVIEW
        ReadyPagePresentation.WAITING_FOR_MODEL ->
            if (progressLine != null) "${SetupCopy.WAITING_DOWNLOADING} · $progressLine" else SetupCopy.WAITING_PREPARING
    }

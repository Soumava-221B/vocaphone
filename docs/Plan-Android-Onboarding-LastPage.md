# Plan — the last setup page always knows about the model

Follow-up to `Plan-Android-Onboarding.md`, on `feat/android_onboarding`.

## The problem, as seen

A person presses **Skip** on the model page, finishes the keyboard, and lands on
the last page. It reads *"Setup needs attention — a permission, keyboard, or
speech source needs attention — a setup requirement changed"*, with one button,
**Review remaining setup**, which sends them back a page.

Nothing changed; they deferred it. The page does not say *which* requirement,
and cannot finish it in place. Before this branch the page was unreachable
without a model on disk, so the wording was almost never seen; Skip made it a
first-class path.

The download progress card built in Phase 4 is correct but conditional on a
download actually running. With Skip there is nothing to show, so the page
falls through to the generic notice.

## Goal

The last page names the model's state in **every** case and can finish the
model step itself — the way iOS's last page is titled *"Getting your model
ready"* whatever the state. "Review remaining setup" stays for the requirements
that genuinely need their own page: permissions and the keyboard.

## Facts the plan rests on

| Fact | Where |
|---|---|
| The READY branch shows `ModelDownloadCard` only when `localModels.downloading != null` | `SetupScreen.kt:365` |
| Otherwise it shows the generic notice whenever `!status.isReadyToDictate` | `SetupScreen.kt:368` |
| The model the picker recommends comes from `ModelGuidance.recommend(profile, ModelGuidanceIntent(language, priority))` | `LocalModelPicker.kt:120` |
| `LocalModelState` carries `downloaded`, `downloading`, `progress`, and `message` (a failure message when a download ends badly) | `LocalModelManager.kt:55` |
| `SetupStatus.gatewayConfigured` is the source requirement; `remainingSteps` lists what is unmet | `SetupStatus.kt` |
| `onDownloadAndUseLocalModel` and `onCancelLocalModelDownload` are already parameters of `SetupScreen` | `SetupScreen.kt:128` |

## Design

One new composable, `ModelSourceCard`, rendered on READY whenever
`settings.localTranscriptionEnabled`. It replaces the bare
`ModelDownloadCard` call there (the picker and home keep using
`ModelDownloadCard` directly). It has four states, decided by a pure function
so the mapping is tested:

```kotlin
internal enum class ModelSourceState { DOWNLOADING, READY, FAILED, NOT_STARTED }

internal fun modelSourceState(
    settings: VocaPhoneSettings,
    models: LocalModelState,
): ModelSourceState = when {
    models.downloading != null -> DOWNLOADING
    settings.localModelId.isNotEmpty() && settings.localModelId in models.downloaded -> READY
    models.message != null -> FAILED
    else -> NOT_STARTED
}
```

| State | Card |
|---|---|
| `DOWNLOADING` | `ModelDownloadCard` as today — bar, bytes, time left, Cancel |
| `READY` | "Model ready · *name*" with the done check. Quiet; one line |
| `FAILED` | "The download did not finish." + the failure `message` + **Try again** (`onDownloadAndUseLocalModel(recommended)`) |
| `NOT_STARTED` | "No model yet" · *recommended name* · size · **Download** (`onDownloadAndUseLocalModel(recommended)`), and a secondary **Other models** that goes Back to the model page |

The recommended model for `FAILED` and `NOT_STARTED` is the same one the model
page would show, computed the same way: `ModelGuidance.recommend(profile,
intent)` with the automatic language and balanced priority, where `profile` is
`DeviceProfile.current(totalRamGB, languages = phoneLanguages() +
keyboardLanguages(context))` — exactly the picker's call. Extract that into
`internal fun setupRecommendation(context, state, settings): LocalModelDescriptor?`
in `LocalModelPicker.kt` and have both the picker and the card call it, so the
two can never disagree about which model "the recommendation" is.

**The generic notice narrows.** It currently appears whenever
`!status.isReadyToDictate`. It should appear only when something *other than
the source* is unmet:
`status.remainingSteps.any { it != SetupStep.GATEWAY }`. When the source is the
only gap, the card is the message.

**"Review remaining setup" narrows the same way.** Its target is already
`OnboardingStage.firstUnmet(status)`; it stays. But it should be the primary
button only when a non-source requirement is unmet. When the source is the only
gap, the primary action *is* the card's Download, and the footer button reads
**Start dictating** disabled with the caption "Download a model to start" —
rather than a live button that leads somewhere else.

**Download starts in place; the page stays.** Tapping Download on the card
transitions the card to `DOWNLOADING` via the model-state observer already
wired in Phase 4. No navigation. When it lands, the card becomes `READY` and
"Start dictating" enables — the person watches the page finish itself.

## Files

| Path | Change |
|---|---|
| `ui/SetupScreen.kt` | READY branch: `ModelSourceCard` in place of the bare download card; notice and footer conditions narrowed |
| `ui/LocalModelPicker.kt` | extract `setupRecommendation(...)`; the picker calls it |
| `ui/ModelSourceCard.kt` (new) | the enum, the pure state function, the composable |
| `ui/SetupCopy` | the new strings, so `SetupCopyTest` covers them |
| tests: `ModelSourceStateTest` (new) | the mapping, incl. `message` set but a download running → `DOWNLOADING`, and `localModelId` set but not on disk → `NOT_STARTED` |

## What does not change

- `OnboardingStage`, `SetupStatus`, `SetupStep`, the download gate, the
  precondition in `DictationController`, the keyboard hint. None are touched.
- The model page. Skip still leaves the requirement unmet; the difference is
  only that the last page can now meet it.
- Home. `DictateScreen` keeps its own `ModelDownloadCard` and its source-aware
  `SetupRepair`.
- Gateway users. `ModelSourceCard` renders only under
  `localTranscriptionEnabled`; a gateway user with an unconfigured gateway
  keeps the generic notice and "Review remaining setup", as today.

## Regression review

| Risk | Guard |
|---|---|
| The downloading case regresses (it works today) | `DOWNLOADING` is the first branch of the state function and renders the same `ModelDownloadCard`; tested |
| Card and model page recommend different models | one `setupRecommendation()` shared by both; a test asserts equality for a fixed profile |
| Generic notice disappears for a *permission* gap | condition is `remainingSteps.any { it != GATEWAY }`; tested for each non-source step |
| A gateway user sees a model card | rendered only under `localTranscriptionEnabled`; tested |
| "Start dictating" enables without a model | it is gated on `status.isReadyToDictate`, which still requires the source — unchanged |
| Download failure leaves a dead page | `FAILED` state with Try again; `LocalModelState.message` is the existing failure signal |
| Existing `SetupCopyTest` | new strings are constants in `SetupCopy`, added to its list |
| No UI test infrastructure | the state function and recommendation helper are pure and tested; the composable is verified by the device walk below |

## Verification

1. `just android ci`.
2. Emulator, fresh install: Skip the model → grant → keyboard → last page shows
   **No model yet · Parakeet TDT 0.6B English · 661 MB · Download**; no generic
   notice; footer "Start dictating" disabled with its caption.
3. Tap Download → the card becomes the progress bar without leaving the page;
   wait → "Model ready"; "Start dictating" enables.
4. Same walk with "Download and continue" on the model page → last page opens
   already on the progress card (the Phase 4 case, unchanged).
5. Kill the network mid-download → card shows **FAILED** with Try again; tap it
   with the network back → downloads.
6. Revoke the microphone from Settings and return → generic notice and "Review
   remaining setup" appear alongside the model card, and Review lands on
   MICROPHONE.
7. Gateway path: choose Gateway, leave it unconfigured → last page unchanged
   from today.

## Open decision

Whether the `NOT_STARTED` card's secondary action should be **Other models**
(Back to the model page) or open the "All models" sheet directly. Proposed:
Back — the model page is one tap away and already holds the whole choice.

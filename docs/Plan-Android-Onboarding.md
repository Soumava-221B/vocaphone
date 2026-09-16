# Plan — bring the iOS first-run flow to Android

Give Android's guided setup the shape iOS shipped in
`ios/VocaPhoneApp/App/SetupView.swift`: a welcome, one decision per page, a
visible model list keyed to the languages the person actually uses, a keyboard
confirmation, and an ending that does not wait on a 661 MB download.

**Branch:** its own (`feat/android_onboarding`), off `main`. Not on
`feat/ios_stats`, where this file was drafted.

**Closes:** #274 (English-only Parakeet recommended on an English-locale phone).
**Related:** #258 (closed — setup not finishing itself), #142 (layout vs model
language).

## Non-goals

- No change to what counts as a requirement. `SetupStep` and the rule at
  `SetupPage.kt:3` — *page position never grants a requirement* — stay.
- No change to the keyboard, dictation, or model download engines.
- No copy of iOS's `KeyboardSwitchProbeField`, its Settings round-trip, or its
  animated hand. Android can read and open the keyboard picker directly; it
  should be better here, not equivalent.
- The notifications page stays. iOS has none because it needs none; Android's
  recording foreground service does.

## What the code does today (facts the plan rests on)

| Fact | Where |
|---|---|
| The page is *derived* from live requirement status, never stored | `SetupPage.resume(status)`; `SetupScreen.kt:152` (`rememberSaveable`, not persisted) |
| Back exists; Continue appears once a step is satisfied; **no Skip** | `SetupScreen.kt:155`, `:322` |
| The model recommendation reads **one** language: the phone's UI language | `DeviceProfile.kt:92` `Locale.getDefault().language` |
| `recommendations()` puts English first when that language is `en`, and the setup picker draws only the first | `LocalModelCatalog.kt:363`; `LocalModelPicker.kt:70` (`compact`) |
| The source step is satisfied only by a **finished** download | `VocaPhoneViewModel.kt:185` `isDownloaded(localModelId)` |
| The download already survives leaving setup — the manager is app-scoped with its own scope | `VocaPhoneApplication.kt:94`; `LocalModelManager.kt:107` |
| Dictating with no model on disk throws a string error | `DictationController.kt:849` |
| A "recently ready" tracker for step check animations already exists | `SetupScreen.kt:436` `rememberRecentlyReadySteps` |
| Usage-reporting consent is already the last thing setup asks | `SetupScreen.kt:349` |
| Completion writes one boolean | `Keys.ONBOARDING_COMPLETE`; `MainActivity.kt:344` |

iOS's equivalents, for reference: `OnboardingStage` is persisted and distinct
from `SetupStep` (`OnboardingPresentation.swift:5`); the model pick reads the
enabled keyboards (`KeyboardInputLanguages.swift:21`) and
`Locale.preferredLanguages`; `allowsSkip` is true on `model` and `practice`.

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | Add an `OnboardingStage` **alongside** `SetupStep`, persisted; keep `SetupStep` as the only gate | The single structural difference from iOS. Every visible gap (welcome, skip, confirmation, finish-while-downloading) needs a page that is not a requirement. |
| D2 | `DeviceProfile` gains `languages: List<String>`; `language` stays as the primary | Additive. Every existing caller and test is unchanged; the list defaults to `listOf(language)`. |
| D3 | Languages come from `LocaleList.getDefault()` **and** enabled keyboard subtypes | Mirrors iOS's two sources. A phone set to English with a Hindi keyboard is a Hindi speaker. |
| D4 | The source step is satisfied by a model that is downloaded **or downloading** | Finish-while-downloading. Paired with D5 or it ships a worse bug. |
| D5 | The keyboard's Dictate action shows a *downloading* state instead of the string error | Without this, D4 lets a user reach the keyboard and hit `DictationController.kt:849`. |
| D6 | Model page allows Skip; nothing else new does | Same as iOS. Skipping leaves the requirement unmet, so READY still says "Review remaining setup" — existing behaviour. |

## Phases

Each phase ships on its own and leaves `just android ci` green. Order is by
value per risk; 1 and 2 are worth shipping before 3 exists.

### Phase 1 — read the whole language list (closes most of #274, no UI)

**Files:** `local/DeviceProfile.kt`, `local/LocalModelCatalog.kt`, tests.

- `DeviceProfile`: add `val languages: List<String> = listOf(language)`.
  Primary first, deduplicated, lower-cased, empty entries dropped.
- `DeviceProfile.current(...)`: populate it from
  `LocaleList.getDefault()` (API 24; minSdk 33) plus
  `InputMethodManager.getEnabledInputMethodSubtypeList(null, true)` mapped
  through `InputMethodSubtype.languageTag` / `locale`. Wrap the IMM call in
  `runCatching` — some OEMs throw from it on a fresh boot.
- `LocalModelCatalog.recommendations()`: the `== "en"` branch becomes
  `profile.languages.all { it == "en" }`. For the non-English path, `regional`
  is `starterForLanguage(first non-English)`, and `bestMultilingual` requires
  `coversAll(profile.languages)` — falling back to covering the primary alone
  if nothing covers the whole set, so a rare language never empties the list.
- Add `LocalModelDescriptor.coversAll(languages)` beside `coversLanguage`;
  leave `coversLanguage` untouched.

**Tests (new, in `ModelCatalogQueryTest` / a `DeviceProfileLanguagesTest`):**
- English UI + Russian keyboard → multilingual Parakeet leads, GigaAM Russian
  offered, English-only Parakeet still present.
- English UI + Hindi keyboard → a model covering Hindi leads; the 25-language
  Parakeet is *not* marked for-you, because it does not cover Hindi.
- Only `en` → identical output to today (pin this; it is the regression guard).
- Duplicates and casing (`en`, `EN`, `en-US`) collapse to one.

**Regression:** none for single-language phones — the `en`-only pin proves it.
`DeviceProfile(...)` positional construction is untouched because the new field
has a default and is last.

### Phase 2 — show the list, not one card (still inside the existing page)

**Files:** `ui/SetupScreen.kt`, `ui/LocalModelPicker.kt`, `ui/SetupCopy`.

- Drop `compact` on the setup call so the 3–4 `recommendations()` render as a
  list; badge the first "For you". The alternatives are already computed.
- "Works with English" → "English only" wherever a descriptor is English-only.
- Retire "Help me choose" from setup (its language question is now answered
  by D3); rename "Browse" → "All models"; delete the sheet subtitle *"The
  recommendation is still the default."*
- Add a size confirmation before the first download and on metered networks:
  one line, the size, Continue/Cancel. `downloadWarning` already computes the
  metered text; extend it rather than adding a second path.

**Tests:** `SetupCopyTest` for the renamed strings and the confirmation copy;
`LocalModelPicker` has no UI tests today — verify by screenshot.

**Regression:** the Settings model page also uses `LocalModelPicker` without
`compact`; it is unaffected. `MORE_MODELS_LABEL` is referenced from Settings —
rename via the constant, not the literal.

### Phase 3 — `OnboardingStage` (the structural change)

**Files:** new `ui/OnboardingStage.kt`, `settings/SettingsRepository.kt`,
`ui/SetupScreen.kt`, `ui/MainActivity.kt`, tests.

```kotlin
enum class OnboardingStage(val step: SetupStep?, val allowsSkip: Boolean = false) {
    WELCOME(null),
    SOURCE(null),                 // a choice, always Next-able; writes localTranscriptionEnabled
    MODEL(SetupStep.GATEWAY, allowsSkip = true),
    MICROPHONE(SetupStep.MICROPHONE),
    NOTIFICATIONS(SetupStep.NOTIFICATIONS),
    KEYBOARD(SetupStep.KEYBOARD),
    KEYBOARD_READY(null),         // confirmation; auto-advances
    READY(null);
    companion object {
        fun resume(persisted: OnboardingStage?, status: SetupStatus): OnboardingStage
    }
}
```

- Persist as `Keys.ONBOARDING_STAGE` (`stringPreferencesKey`), written on every
  stage change; `persisted(from:)` returns `WELCOME` for null or unknown, as
  iOS does for its retired `"handoff"`.
- `resume(persisted, status)`: start at the persisted stage, then **advance
  past any stage whose step is already satisfied** — someone who granted the
  microphone in Settings while away must not land on a page asking for it.
  This is the existing `SetupPage.resume` rule, applied from a saved start
  rather than from the top.
- Gateway users skip `MODEL` (iOS does the same).
- Replace `SetupPage` with `OnboardingStage`; port the four `SetupPageTest`
  cases first — they encode the resume and back/forward rules that must not
  change — then add: welcome is shown once per install; a persisted stage
  whose requirement is now met is skipped; skip on MODEL leaves GATEWAY unmet
  and READY offers "Review remaining setup"; an unknown persisted value falls
  back to WELCOME.
- Welcome page: three cards (on-device by default, no subscriptions, works in
  any app), thin progress bar replacing "Step N of 4", Back everywhere,
  Skip only where `allowsSkip`.

**Regression:** `MainActivity.kt:175` `showSetup = !onboardingComplete` is
unchanged — the stage lives inside setup. The `open_settings`/`settings_page`
deep link bypasses setup entirely today and must still. Rotation: keep
`rememberSaveable` for the in-memory stage; DataStore is the cross-launch copy.

### Phase 4 — finish while the model downloads (D4 + D5 together)

**Files:** `ui/VocaPhoneViewModel.kt`, `local/LocalModelManager.kt`,
`dictation/DictationController.kt`, the IME's dictate surface, home screen.

- `LocalModelManager`: expose `isDownloading(id)` beside `isDownloaded(id)`.
- `VocaPhoneViewModel.kt:185`: `isDownloaded(id) || isDownloading(id)`.
- Setup's MODEL page: "Download and continue" starts the download and advances
  **immediately**. The download keeps running in the background — it already
  does; `LocalModelManager` is app-scoped — and nothing in setup waits on it.
- **READY page shows the download.** Mirroring iOS's last step ("Getting your
  model ready · 88 MB of 670 MB · ready in about a minute"): the final page
  carries a progress bar, the byte count, and the time estimate, above the
  "Start dictating" button. Reuses `downloadSizeProgress` and
  `downloadTimeRemaining`. When the download finishes while the page is open,
  the bar resolves to a check and the caption becomes "Ready" — no navigation.
  "Start dictating" is enabled throughout, because leaving setup is safe once
  D5 is in place.
- **Home carries the same card** after setup, so a user who left early sees
  where the download is — "Downloading Parakeet TDT 0.6B · 166 MB of 670 MB"
  with "See models". One composable, shown in both places, fed by the same
  `LocalModelState` flow, so the two can never disagree.
- `DictationController.kt:849`: return a typed precondition
  (`ModelDownloading(progress)` / `ModelMissing`) instead of `error(String)`,
  and have the IME's dictate control render "Model downloading · 40%" rather
  than starting a session. **This is the half that makes D4 safe.**
**Tests:** `SetupStatusTest` — downloading satisfies GATEWAY, missing does not;
a `DictationController` precondition test for each typed state.

**Regression:** this phase is the only one that changes what a user can reach
with an incomplete install. Ship the three pieces as one commit; do not land
the gate change alone. A download that fails after setup finished must be
surfaced on home (the existing failure state of `LocalModelState` renders
there already — verify, do not assume).

### Phase 5 — keyboard confirmation and illustration

**Files:** `ui/SetupScreen.kt`, `ui/ImeSetupCard`, a drawable.

- `KEYBOARD_READY`: shown when `ime.selected` flips true while on KEYBOARD.
  Full-page check + "Keyboard ready", auto-advance after ~1.2 s or on tap.
  Drive it from `rememberRecentlyReadySteps`, which already detects the flip.
- On KEYBOARD, keep `showInputMethodPicker()` as the primary action (Android's
  advantage over iOS), add a numbered 1-2-3, and a static screenshot of the
  picker dialog — static, because the dialog varies by OEM skin and a recorded
  animation would go stale.

**Regression:** the #258 class. A `KEYBOARD_READY` page that never
auto-advances would recreate it. Test: with all steps satisfied, `resume`
never returns `KEYBOARD_READY`.

### Phase 6 — optional pages

- `PRACTICE` (a text field, Skip allowed) and moving the existing usage-
  reporting dialog into a page. Lowest value; do last or not at all.

## Regression review (whole plan)

| Risk | Where it bites | Guard |
|---|---|---|
| Single-language phones get a different recommendation | Phase 1 | `en`-only pin test asserts byte-identical output to today |
| A saved stage traps a returning user on a met requirement | Phase 3 | `resume` advances past satisfied steps; tested |
| A saved stage from a future build is unreadable | Phase 3 | unknown → WELCOME, tested |
| User dictates with no model on disk | Phase 4 | typed precondition + IME state; three pieces in one commit |
| Setup never finishes (#258 again) | Phase 5 | `resume` never returns a transient page when all steps are met |
| Settings model page breaks when setup stops passing `compact` | Phase 2 | Settings never passed it |
| Deep link into Settings during a half-done setup | Phase 3 | unchanged bypass in `MainActivity`; add a test on the extra parsing if none exists |
| Telemetry vocabulary | all | no new event names; `reportSetupProgress` reuses existing ones |

## Verification

1. `just android ci` after every phase.
2. After Phase 1, on the emulator: English locale + a Russian IME subtype
   enabled → the picker leads with the multilingual Parakeet and offers GigaAM.
3. After Phase 3: `pm clear`, walk to MODEL, force-stop, relaunch → resumes on
   MODEL. Grant microphone in Settings, relaunch → MICROPHONE is skipped.
4. After Phase 4: tap "Download and continue" → setup advances at once and the
   READY page shows the bar climbing. Stay on READY until it completes → the
   bar resolves to a check without navigating. Separately: leave setup while
   it is still downloading, open the keyboard in another app, tap Dictate →
   "Model downloading", never the string error. Kill the network mid-download
   → both READY and home show the failure, not a spinner.
5. Physical device for Phases 4–5 (keyboard and insertion are touched); the
   template's checkbox applies.

## Open decisions

- **Confirmation threshold** for the download prompt: first download only, or
  every download above N MB? Proposed: first, plus any on a metered network.
- **`KEYBOARD_READY` auto-advance delay.** Proposed 1.2 s; tap to skip.
- **Whether `SOURCE` should be gated at all.** Proposed: no — it is a choice,
  not a requirement, and Next is always enabled. This matches iOS.

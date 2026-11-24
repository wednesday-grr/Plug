# Plug

Local Android music player focused on smooth transitions, deterministic audio processing, and transparent playback diagnostics.

> **Current Status (`v2.0.1-preview`):** AutoMix **Phases A–E** are implemented and unit-tested (**358 passing unit tests**, 0 failures, 2 skipped); they have not been verified on a physical device, and lint and instrumentation tests have not been run. Phases **F–H** are upcoming, and final acoustic tuning on a physical device is still pending.

---

## ✨ Key Highlights

* **Intelligent AutoMix (Phases A–E):**
  * **Transition Curves & Fades:** Exponential and S-curve fade profiles with configurable curve intensity.
  * **Loudness Matching & AGC:** BS.1770 loudness measurement cached in the database (`DB v7+`) alongside a deck-chain Automatic Gain Control (AGC) processor.
  * **Bass Boost:** Dedicated Bass Boost DSP block with user-facing settings toggles.
  * **Energy Matching:** 1-second frame analysis of unweighted RMS and low/mid/high frequency bands over 30-second start/end windows (22,050 Hz), stored in the `energy` table (`DB v8`). Lengthens overlap up to **1.5×** when energy rises by **> 6 dB** or drops by **> 9 dB** (`TRANSITION_DURATION` arbitration) without altering track gain.
  * **Beatmatch & Phase Alignment:** Matches incoming BPM with `BeatPhaseAligner` (`automix_beatmatch_enabled` on by default), supports half-time/double-time checks within speed limits, falls back cleanly to a standard crossfade when confidence is low, and skips Vinyl Brake transitions.
* **Arbitration & Diagnostics:** Centralized parameter ownership registry and an **Audio Quality Info** explanation sheet (`FullBottomSheet`) showing active decisions and `unavailable` states when data is missing or too quiet.
* **Local Cache & Background Analysis:** Scheduled background decoding with database schema **v8**, round-trip cache tests, and diagnostic explanations.

---

## 🗺️ Roadmap & Progress

| Phase / Area | Scope | Status |
| :--- | :--- | :--- |
| **Core Beatmatch** | BPM/phase alignment, half/double-time checks, Vinyl Brake bypass, crossfade fallback | Implemented (legacy baseline) |
| **Phases A–B** | Curve framework, Exponential/S-curve fades, arbitration registry, DSP test helpers | Implemented & unit-tested |
| **Phase C** | BS.1770 loudness caching (`DB v7+`), deck-chain AGC processor, settings & UI | Implemented & unit-tested |
| **Phase D** | Bass Boost DSP module & settings integration | Implemented & unit-tested |
| **Phase E** | RMS/band energy analyzer, `energy` cache (`DB v8`), overlap scaling (`TRANSITION_DURATION`) | Implemented & unit-tested |
| **Device QA** | End-to-end acoustic testing on physical Android hardware | Pending |
| **Phases F–H** | Stem separation, four-source stem mixer / Stem AutoMix, final UI and quality integration (not started; blocked on a model/runtime decision and a mixer architecture) | Planned |

---

## ⚠️ Known Notes & Limitations

* **Device verification pending:** Fades, loop declick behavior, loudness trims, AGC, Bass Boost, Energy Matching overlap scaling, and Beatmatch have passed unit tests but have not yet been verified by ear on a physical device.
* **Energy matching scope:** Evaluates unweighted RMS/bands (not perceived loudness or vocal activity/beat grid), requires prior playback or background analysis, and scales overlap duration only rather than adjusting gain.
* **Cleanup note:** `EnergyProfile.kt` currently contains two unused imports (`cos`, `sin`).

---

## 📚 Documentation

* **Architecture & AutoMix details:** See [`app/AUTOMIX.md`](app/AUTOMIX.md). There is no `docs/` directory or GitHub Pages site in this repository.
* **Release Notes:** No `CHANGELOG.md` exists; see the GitHub Release page.

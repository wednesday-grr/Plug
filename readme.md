# Plug v2.0.1-preview — Next-Gen AutoMix & DSP Engine

**Plug v2.0.1-preview** introduces a major architectural overhaul to the local audio playback and **AutoMix** engine (Phases A–E). This preview release brings intelligent transition curves, BS.1770 loudness normalization, automatic gain control, bass boost, and energy-aware overlap scaling—all backed by a deterministic parameter arbitration system and transparent UI explanations.

> **⚠️ Preview Notice:** All 358 unit tests pass across synthetic and real-PCM signals. However, this build is currently in a **partially implemented** state awaiting final on-device acoustic tuning and verification.

---

## ✨ What's New in AutoMix (Phases A–E)

### 1. Transition Curves & Fades (Phases A–B)
* **Selectable Fade Profiles:** Added **Exponential** and **S-curve** transition styles alongside classic crossfades.
* **Fade Curve Intensity:** Fine-tune the steepness and smoothness of transition curves directly from settings.

### 2. Loudness Matching & Automatic Gain Control (Phase C)
* **BS.1770 Loudness Normalization:** Measures perceived track loudness during background analysis and caches results in the local database (`DB v7+`) to prevent volume jumps between tracks.
* **Deck-Chain AGC:** Integrated a real-time Automatic Gain Control (AGC) processor into the playback deck chain for consistent output levels.

### 3. Bass Boost DSP (Phase D)
* **Dedicated Low-End Enhancement:** Implemented a custom Bass Boost DSP block with user-configurable toggles in the audio settings.

### 4. Energy Matching & Dynamic Overlap Scaling (Phase E)
* **Spectral & RMS Analysis:** Measures unweighted RMS and low/mid/high band energy in 1-second frames across 30-second start/end windows (22,050 Hz) and stores profiles in the new `energy` table (`DB v8`).
* **Smart Overlap Scaling:** Compares the final 10 seconds of the outgoing track with the opening 10 seconds of the incoming track. If energy rises by **> 6 dB** or drops by **> 9 dB**, the transition overlap automatically lengthens by up to **1.5×** (`TRANSITION_DURATION` arbitration) without altering track gain.
* **Opt-In Control:** Controlled via the `automix_energy_matching` toggle (off by default). Silent or unanalyzed tracks gracefully report as `unavailable` rather than zeroing out.

### 5. Beatmatch & Phase Alignment (Core Engine)
* **BPM & Phase Sync:** Matches incoming deck playback speed to the outgoing track's BPM with phase alignment via `BeatPhaseAligner` (`automix_beatmatch_enabled` on by default).
* **Half-Time / Double-Time Support:** Evaluates tempo multiples within safe playback speed limits.
* **Safe Fallbacks:** Automatically falls back to a standard crossfade if BPM or beat-grid confidence is insufficient, and bypasses beatmatching during **Vinyl Brake** transitions.

### 6. Parameter Arbitration & Audio Quality Info
* **Deterministic Ownership:** Centralized arbitration registry managing parameter control (`BEATMATCH`, `BPM_MATCHING`, `TRANSITION_DURATION`, and gain trims) to prevent DSP conflicts.
* **Explanation Engine:** Updated the **Audio Quality Info** sheet to surface real-time explanations of active AutoMix decisions, loudness trims, and energy states.

---

## 🛠️ Architecture & Components Overview

* **Audio Playback & DSP Pipeline:** Custom multi-deck chain supporting real-time PCM processing, curve-based fading, AGC, Bass Boost, and phase-aligned speed adjustment.
* **Background Analysis & Caching:** Local SQLite/Room database (**v8**) with dedicated `energy` and loudness caches, scheduled background decoding (11 kHz / 22.05 kHz windows), and Robolectric-verified cache round-trips.
* **UI & Settings:** Material Design 3 bottom sheets (`FullBottomSheet`), granular AutoMix toggles, and live Audio Quality Info diagnostics.

---

## ⚠️ Known Limitations & Pending Verification

* **On-Device Listening Pending:** Fades, loop boundary declick behavior, loudness trims, AGC, Bass Boost, and Energy Matching overlap scaling have passed automated unit tests (358 passed, 0 failed) but still require real-world acoustic verification on physical hardware.
* **Analysis Scope:** Energy matching does not yet factor in vocal activity or beat-grid alignment, and only takes effect after tracks have been played or analyzed by the background scheduler.
* **Upcoming Roadmap:** Phases **F–H** are planned for subsequent preview builds.

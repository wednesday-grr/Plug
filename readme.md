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

* **Playback engine:** Media3 ExoPlayer 1.10.1 with a single `MediaSession`. AutoMix runs **two ExoPlayer decks** that decode independently; Android's audio output mixes them. There is no shared master PCM bus, master limiter, custom audio sink or native mixer, and no ONNX/TFLite runtime. Each deck's `AudioProcessor` chain is: optional PCM tap → ReplayGain → AGC → Bass Boost → DJ transition processor (EQ/filter/echo/reverb) → seek declick → Media3 Sonic (speed).
* **Native/DSP components:** `hificore` (C++ audio/USB library), `misc/alacdecoder`, and an opt-in Essentia JNI analyzer (AGPL; off by default, see [`app/AUTOMIX.md`](app/AUTOMIX.md)). AutoMix DSP (fades, BS.1770 loudness, AGC, Bass Boost biquad, energy bands) is pure Kotlin.
* **Background analysis & caching:** `AudioAnalysisScheduler` decodes PCM with `MediaCodec` off the main thread. Results are stored by `TrackAnalysisCache`, a plain `SQLiteOpenHelper` database (**`track_analysis.db`, v8**) with `analysis`, `beat_grid`, `loudness` (v7) and `energy` (v8) tables. Room is not used. Edge analysis uses 30 s start/end windows decoded to mono at 22,050 Hz.
* **UI & settings:** Views and Jetpack Compose (Material 3) with `PreferenceFragment` settings, Coil for artwork, and a Now Playing **Audio Quality Info** bottom sheet driven by `TransitionExplanationEngine`.
* **AutoMix settings and defaults:** `automix_enabled` (off), `automix_beatmatch_enabled` (on), `automix_loudness_matching` (off), `automix_agc` (off), `automix_bass_boost` (off; gain 0–12 dB, default 4; corner 40–250 Hz, default 100), `automix_energy_matching` (off), `automix_fade_curve_intensity`, `automix_loop_transition_beats` (default 4). Bass Boost has no Q control in the UI.
* **Arbitration:** `AutoMixEffectArbitration.kt` declares each effect's capability (runtime-available or not), the gain/transition parameters it owns and pairwise compatibility. Energy Matching owns only `TRANSITION_DURATION`; Bass Boost owns separate per-deck boost parameters. Stem effects, Vocal/Instrumental separation and Stem AutoMix are registered but **not runtime-available**.

---

## ⚠️ Known Limitations & Pending Verification

* **On-Device Listening Pending:** Fades, loop boundary declick behavior, loudness trims, AGC, Bass Boost, and Energy Matching overlap scaling have passed automated unit tests (358 passed, 0 failed) but still require real-world acoustic verification on physical hardware.
* **Analysis Scope:** Energy matching does not yet factor in vocal activity or beat-grid alignment, and only takes effect after tracks have been played or analyzed by the background scheduler.
* **Loop boundaries:** Beat Loop and Loop Transition are implemented with Media3 `seekTo` on a ~25 ms tick, so loop boundaries are not sample-accurate; a short declick is applied but a boundary click can remain.
* **Lint/build:** Lint and instrumentation tests have not been run for Phases A–E.
* **Upcoming Roadmap:** **Phase F** (offline Spleeter 2-stem separation via ONNX Runtime: pinned/SHA-256-verified model downloader, stem cache, state machine) is code-complete and unit-tested with a fake network, but it is **not wired to settings/UI/playback and the real model has not been run on a device**. Phases **G–H** (four-source stem mixer / Stem AutoMix, and final UI/quality integration) are **planned and not implemented**. Four simultaneous stem sources cannot be mixed on the current two-deck architecture without a new mixing layer.

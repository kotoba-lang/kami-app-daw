# KAMI DAW

Immersive project authority now selects stereo, 5.1 or 7.1.4 beds, validates normalized audio-object coordinates and emits ADM metadata identified as ITU-R BS.2076-3. This is an interoperable metadata boundary, not Dolby Atmos certification or a proprietary renderer. Distributed publisher revocation lists verify their exact signed payload with WebCrypto ECDSA P-256 and require the signing-key fingerprint already held in the trust store. Accepted lists are retained per publisher with monotonic versions, issued/next-update windows and rollback rejection; a current-list match removes trust and disables that publisher's plugins.

Trusted publishers support compare-and-swap key rotation: the currently trusted fingerprint must match before replacement, and every retired fingerprint is retained with its rotation timestamp. Packages signed by the new key become trusted immediately while stale or conflicting rotation attempts leave project authority unchanged.

Signed third-party packages use ECDSA P-256 over the verified source digest. The browser recomputes the publisher-key SHA-256 fingerprint, verifies the signature with WebCrypto, holds unknown publishers pending explicit trust, and persists the trust decision. Revocation removes the publisher and immediately disables every associated plugin.

Third-party AudioWorklet packages now declare an `audio-processing` capability and a SHA-256 digest for their exact processor source. Import recomputes the digest before registration, rejects undeclared capabilities or modified source, and persists the verified digest and capability set with project authority.

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, effects, and master export. It does not own video editing or 3D character authoring.

The production path is browser-native: imported files and microphone takes are decoded into `AudioBuffer`, displayed as waveforms, and scheduled through the same gain/effect graph used by `OfflineAudioContext` for the PCM WAV master. Recording offers an explicit, default-off headphone monitor with bounded gain and a dedicated WebAudio graph/meter; it exists only while a take owns its stream and is disconnected on take end, cancellation, error and page unload. Loop recording captures 1–8 takes at one range; every take remains in validated `:track/take-lanes`, while the selected comp alone is projected into `:track/clips` for playback/export. Active and inactive takes travel together in verified `.kami.zip` packages.

Bounded JSON AudioWorklet packages provide a third-party SDK surface: manifests accept at most 32 validated parameters and 65,536 source characters, persist the exact source with the project for repeatable live and offline rendering, and execute on the browser audio rendering thread. A package acknowledges startup with `this.port.postMessage({type: "kami-ready"})`; a missing acknowledgement within 750 ms or a `processorerror` restores unity dry gain and silences the failed wet path, so one processor cannot mute the master chain. Signed distribution and explicit permission capabilities remain follow-up scope.

Project-authoritative plugin slots use one manifest for processor identity, parameter labels, defaults, bounds and UI. Each insert owns bounded musical-tick wet/dry automation with project-authoritative `:linear` or `:step` interpolation. A monotonic transport clock advances the visible musical tick during playback; Read, Touch, Latch, Write and Trim modes decide whether gestures, continuing transport samples or relative offsets change automation, with repeated writes on the same tick coalesced. Tolerance-driven thinning preserves linear endpoints and deviations above the chosen error, while step thinning removes only redundant consecutive values. Project-authoritative Web MIDI mappings bind a unique channel/CC pair to plugin wet mix. Mackie Control uses bounded eight-track banks: bank left/right select the project window, 14-bit faders set gain, relative V-Pots adjust pan, and note buttons toggle record-arm, solo and mute. Per-strip touch notes establish physical fader ownership: motor feedback is suppressed while touched and the authoritative project gain is returned on release, avoiding fights with a user's hand. Opt-in SysEx writes the eight banked seven-character track labels to the scribble strip, while standard CC messages update a ten-digit bar/beat/subdivision/tick display at a bounded 10 Hz; denied SysEx permission falls back to ordinary MIDI controls and time display. Capability profiles for Mackie Control, Behringer X-Touch, iCON Platform M+ and generic MCU can be auto-detected from MIDI port names or selected manually; LCD, time and touch paths are gated by the active profile. The DAW returns motor-fader, pan-ring and LED state to every output; solo is honored by live audio scheduling, and loading/recovery or bank changes clear touch ownership safely. MIDI realtime Start/Continue/Stop and locate-aware automation share this device lifecycle. Formal vendor hardware certification remains an explicit commercial-product gap.

## Run

```sh
npm install
npx shadow-cljs watch app
```

Open <http://localhost:9630>. Public app: <https://kotoba-lang.github.io/kami-app-daw/>

## Verify

```sh
npm run check
npm run release
```

Maturity: **逍遥** — project-authoritative built-in and bounded third-party AudioWorklet plugin slots with startup/fault bypass, stereo pan, bus gain, delay-send and master effect automation, deterministic all-track stem bundles, stereo K-weighted loudness analysis, per-channel 4× oversampled true-peak measurement, ceiling-safe normalization, direct clip movement, input monitoring, loop/comp recording, verified packages, recursive relinking, persistent undo/redo, waveform extraction, trim/fades, named routing, effects and metering share one render path. Master and stem renders are two-channel; the 16-bit PCM writer interleaves both channels, and each bundle stem runs from timeline zero to the same project end. Hard pan produces channel-isolated output while centered tracks retain stereo placement. Persistent directory grants, streaming packages above 512 MiB, signed plugin distribution and explicit permission capabilities, certified broadcast loudness conformance and surround/Atmos buses remain follow-up scope.

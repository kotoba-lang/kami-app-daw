# KAMI DAW

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, effects, and master export. It does not own video editing or 3D character authoring.

The production path is browser-native: imported files and microphone takes are decoded into `AudioBuffer`, displayed as waveforms, and scheduled through the same gain/effect graph used by `OfflineAudioContext` for the PCM WAV master. Recording offers an explicit, default-off headphone monitor with bounded gain and a dedicated WebAudio graph/meter; it exists only while a take owns its stream and is disconnected on take end, cancellation, error and page unload. Loop recording captures 1–8 takes at one range; every take remains in validated `:track/take-lanes`, while the selected comp alone is projected into `:track/clips` for playback/export. Active and inactive takes travel together in verified `.kami.zip` packages.

Project-authoritative plugin slots use one manifest for processor identity, parameter labels, defaults, bounds and UI. Each enabled insert owns a bounded wet/dry mix implemented as a parallel dry gain plus AudioWorklet wet gain before the next chain stage. The project vector is chain-order authority and the editor supports bounded movement, bypass and removal. Every manifest parameter also owns musical-tick automation. Realtime playback, master and stems instantiate the same routing, order, mix and ramps. Third-party plugin discovery/SDK, signed distribution and processor isolation remain explicit commercial-product gaps.

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

Maturity: **逍遥** — project-authoritative AudioWorklet plugin slots, stereo pan, bus gain, delay-send and master effect automation, deterministic all-track stem bundles, stereo K-weighted loudness analysis, per-channel 4× oversampled true-peak measurement, ceiling-safe normalization, direct clip movement, input monitoring, loop/comp recording, verified packages, recursive relinking, persistent undo/redo, waveform extraction, trim/fades, named routing, effects and metering share one render path. Master and stem renders are two-channel; the 16-bit PCM writer interleaves both channels, and each bundle stem runs from timeline zero to the same project end. Hard pan produces channel-isolated output while centered tracks retain stereo placement. Persistent directory grants, streaming packages above 512 MiB, a third-party plugin SDK/signed distribution and isolation model, certified broadcast loudness conformance and surround/Atmos buses remain follow-up scope.

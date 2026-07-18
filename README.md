# KAMI DAW

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, effects, and master export. It does not own video editing or 3D character authoring.

The production path is browser-native: imported audio files are decoded into `AudioBuffer`, bound to clips through `:clip/asset-id`, displayed as waveforms, and scheduled through the same gain/effect graph used by `OfflineAudioContext` for the PCM WAV master. Non-destructive source offset and fade-in/out values are honored in both paths, while a realtime dBFS meter exposes master activity. Clips without an imported asset deliberately use the built-in oscillator fallback.

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

Maturity: **逍遥** — imported clip playback, waveform extraction, non-destructive trim/fades, effects, metering, and offline WAV export share one project-authoritative path. Recording, drag handles, plugin hosting, automation, bus routing, loudness metering, and multichannel stems remain follow-up scope.

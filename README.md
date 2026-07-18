# KAMI DAW

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, effects, and master export. It does not own video editing or 3D character authoring.

The production path is browser-native: imported files and microphone takes are decoded into `AudioBuffer`, displayed as waveforms, and scheduled through the same gain/effect graph used by `OfflineAudioContext` for the PCM WAV master. Recording offers an explicit, default-off headphone monitor with bounded gain and a dedicated WebAudio graph/meter; it exists only while a take owns its stream and is disconnected on take end, cancellation, error and page unload. Loop recording captures 1–8 takes at one range; every take remains in validated `:track/take-lanes`, while the selected comp alone is projected into `:track/clips` for playback/export. Active and inactive takes travel together in verified `.kami.zip` packages.

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

Maturity: **逍遥** — gated K-weighted master loudness analysis, 4× oversampled true-peak measurement, ceiling-safe target normalization, direct pointer and keyboard clip movement, opt-in metered input monitoring, loop/comp recording, portable verified project/media packages, recursive directory relinking, persistent bounded undo/redo, imported playback, waveform extraction, trim/fades, gain automation, effects, named bus routing, delay sends, metering, master and per-track WAV export share one project-authoritative path. Normalization gain is the lower of the requested LUFS correction and the dBTP ceiling allowance; the resulting delivery values are reported before download. Clip drag previews do not mutate the project; pointer-up commits one undoable generation and pointer-cancel discards it. Persistent directory grants, streaming packages above 512 MiB, plugin hosting, bus automation, certified broadcast loudness conformance and bundled multichannel stems remain follow-up scope.

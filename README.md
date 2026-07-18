# KAMI DAW

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, effects, and master export. It does not own video editing or 3D character authoring.

The production path is browser-native: imported files and microphone takes are decoded into `AudioBuffer`, displayed as waveforms, and scheduled through the same gain/effect graph used by `OfflineAudioContext` for the PCM WAV master. Loop recording captures 1–8 bounded punch takes at one timeline range; every take remains in validated `:track/take-lanes`, while the selected comp take alone is projected into `:track/clips` for playback and export. Project and every active/non-active take can travel together in a SHA-256-verified `.kami.zip` package. Every project-value edit enters a persistent 50-generation undo/redo history; runtime meters and playback state are excluded.

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

Maturity: **逍遥** — loop/comp take recording and selection, portable SHA-256-verified project/media packages, persistent bounded undo/redo, batch relinking, missing-media reporting, versioned recovery, validated EDN persistence, imported playback, waveform extraction, trim/fades, gain automation, effects, named bus routing, delay sends, metering, master and per-track WAV export share one project-authoritative path. Streaming packages above 512 MiB, search paths, input monitoring, clip drag handles, plugin hosting, bus automation, LUFS/true-peak and bundled multichannel stems remain follow-up scope.

# KAMI DAW

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, effects, and master export. It does not own video editing or 3D character authoring.

The production path is browser-native: imported files and microphone takes are decoded into `AudioBuffer`, bound to clips through `:clip/asset-id`, displayed as waveforms, and scheduled through the same gain/effect graph used by `OfflineAudioContext` for the PCM WAV master. Project and source media can travel together in a `.kami.zip` package containing EDN project/manifest entries and SHA-256-verified media; opening it rebuilds decoded buffers and waveforms without manual relinking. Both compressed input and expanded content are bounded to 512 MiB. Every project-value edit enters a persistent 50-generation undo/redo history available through controls and Cmd/Ctrl+Z; runtime meters and playback state are excluded. The current project and validated history share a versioned autosave envelope, including the redo branch and v1 migration.

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

Maturity: **逍遥** — portable SHA-256-verified project/media packages, persistent bounded project undo/redo, batch media relinking, missing-media reporting, versioned autosave/recovery, validated EDN persistence, bounded punch recording, imported clip playback, waveform extraction, trim/fades, gain automation, effects, named bus routing, delay sends, metering, master and per-track WAV export share one project-authoritative path. Streaming packages above 512 MiB, search paths, loop/comp recording, input monitoring, drag handles, plugin hosting, bus automation, LUFS/true-peak and bundled multichannel stems remain follow-up scope.

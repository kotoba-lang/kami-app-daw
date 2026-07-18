# KAMI DAW

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, effects, and master export. It does not own video editing or 3D character authoring.

The production path is browser-native: imported files and microphone takes are decoded into `AudioBuffer`, bound to clips through `:clip/asset-id`, displayed as waveforms, and scheduled through the same gain/effect graph used by `OfflineAudioContext` for the PCM WAV master. Every project-value edit enters a bounded 50-generation undo/redo history available through controls and Cmd/Ctrl+Z; runtime meters and playback state are excluded. The current project and validated history share a versioned autosave envelope, so reload and crash recovery retain the complete editing position, including the redo branch. Legacy v1 snapshots migrate with an empty history. Stable asset IDs, source filenames and SHA-256 content hashes remain available for explicit missing-media reports and content-based relinking.

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

Maturity: **逍遥** — persistent bounded project undo/redo, SHA-256/filename batch media relinking, missing-media reporting, versioned autosave/recovery, validated EDN project persistence, bounded punch recording, imported clip playback, waveform extraction, trim/fades, gain automation, effects, named bus routing, delay sends, metering, master and per-track WAV export share one project-authoritative path. Search paths, packaged media, loop/comp recording, input monitoring, drag handles, plugin hosting, bus automation, LUFS/true-peak and bundled multichannel stems remain follow-up scope.

# KAMI DAW

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, effects, and master export. It does not own video editing or 3D character authoring.

The production path is browser-native: imported files and microphone takes are decoded into `AudioBuffer`, bound to clips through `:clip/asset-id`, displayed as waveforms, and scheduled through the same gain/effect graph used by `OfflineAudioContext` for the PCM WAV master. Punch recording starts at the current tick, automatically stops at the configured musical tick length, and creates an exact-range project clip rather than transient browser state. Validated EDN and recovery envelopes retain stable asset IDs, source filenames and SHA-256 content hashes. After reload, missing IDs are reported explicitly; a batch of renamed audio files can be selected in any order and matching content is decoded back into the correct clip buffers automatically. Invalid or future-version recovery data is discarded.

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

Maturity: **逍遥** — SHA-256/filename batch media relinking, missing-media reporting, versioned autosave/recovery, validated EDN project persistence, bounded punch recording, imported clip playback, waveform extraction, trim/fades, gain automation, effects, named bus routing, delay sends, metering, master and per-track WAV export share one project-authoritative path. Search paths, packaged media, recovery history, loop/comp recording, input monitoring, drag handles, plugin hosting, bus automation, LUFS/true-peak and bundled multichannel stems remain follow-up scope.

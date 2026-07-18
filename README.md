# KAMI DAW

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, effects, and master export. It does not own video editing or 3D character authoring.

The production path is browser-native: imported files and microphone takes are decoded into `AudioBuffer`, bound to clips through `:clip/asset-id`, displayed as waveforms, and scheduled through the same gain/effect graph used by `OfflineAudioContext` for the PCM WAV master. Punch recording starts at the current tick, automatically stops at the configured musical tick length, and creates an exact-range project clip rather than transient browser state. Validated `kami.ongaku-project/v1` EDN can be saved and reopened; project mutations are also written to a versioned local recovery envelope and restored after reload. Invalid or future-version recovery data is discarded. Runtime buffers remain external and clips retain their asset IDs for relinking. Tracks route to named buses and can feed a shared feedback-delay send.

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

Maturity: **逍遥** — versioned autosave/recovery, validated EDN project persistence, bounded punch recording, imported clip playback, waveform extraction, trim/fades, gain automation, effects, named bus routing, delay sends, metering, master and per-track WAV export share one project-authoritative path. Recovery history, automatic media relinking, loop/comp recording, input monitoring, drag handles, plugin hosting, bus automation, LUFS/true-peak and bundled multichannel stems remain follow-up scope.

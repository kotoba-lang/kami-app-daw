# KAMI DAW

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, effects, and master export. It does not own video editing or 3D character authoring.

The production path is browser-native: imported files and microphone takes are decoded into `AudioBuffer`, bound to clips through `:clip/asset-id`, displayed as waveforms, and scheduled through the same gain/effect graph used by `OfflineAudioContext` for the PCM WAV master. A recorded take becomes a new project clip at the current tick, rather than remaining transient browser state. Tracks route to named buses and can feed a shared feedback-delay send. Non-destructive source offset, fades, and tick-based gain automation are honored in both paths; a realtime dBFS meter exposes master activity, and every track can render an isolated WAV stem. Clips without an imported asset deliberately use the built-in oscillator fallback.

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

Maturity: **逍遥** — microphone recording, imported clip playback, waveform extraction, trim/fades, gain automation, effects, named bus routing, delay sends, metering, master and per-track WAV export share one project-authoritative path. Punch-in/loop recording, input monitoring, drag handles, plugin hosting, bus automation, LUFS/true-peak and bundled multichannel stems remain follow-up scope.

# KAMI DAW

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, effects, and master export. It does not own video editing or 3D character authoring.

The production path is browser-native: real-time synthesis runs through `AudioContext`, low-pass and feedback-delay nodes are applied to the master, and `OfflineAudioContext` renders the full arrangement to a PCM WAV download.

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

Maturity: **逍遥** — real browser playback, effects, and offline WAV export are implemented. External audio-file recording, plugin hosting, and multichannel stems remain follow-up scope.

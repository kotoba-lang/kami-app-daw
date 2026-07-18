# KAMI DAW

EDN-native browser digital audio workstation for `kotoba-lang`. It owns music arrangement UI: tempo, musical ticks, tracks, clips, gain, mute/solo, transport, and EDN export. It does not own video editing or 3D character authoring.

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

Maturity: **遊行** — functional browser prototype; audio graph playback and production export are not yet claimed.

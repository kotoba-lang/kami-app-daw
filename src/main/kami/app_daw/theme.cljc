(ns kami.app-daw.theme
  "KAMI DAW's stylesheet, on **jp-go-dds** — the デジタル庁デザインシステム
  (DADS) mirror, which is this workspace's base design system
  (owner decision 2026-08-05).

  ── what this replaces ──────────────────────────────────────────────────────
  The app used to ship two stylesheets it maintained itself:

  * `public/style.css` — 24 hand-written rules carrying 18 hex literals, its
    own font stack, its own `color-scheme: dark`, its own button styling and
    its own focus ring. Every one of those is a decision the design system had
    already made, and made better: the tokens carry a light appearance too,
    and they are contrast-checked.
  * `public/liquid-glass.css` — a 39 KB artifact generated from
    `liquid-glass-ui` and then frozen into the repo. Byte-identical in
    kami-app-nle. It carried the glass material but **zero `--hig-*` tokens**,
    so the app got the look of the design system and none of its semantics,
    and could not follow the library forward.

  Both are gone. `stylesheet` composes the live bundle from the pinned
  `kotoba-ui`, so the app now tracks the library instead of a snapshot of it.

  ── what remains app-specific ───────────────────────────────────────────────
  A DAW's timeline is genuinely its own: fixed track-head column, lanes with a
  beat grid, clips positioned by tick. Those rules stay here — but their
  colors and type come from tokens, so the timeline reads as part of the same
  system as everything else and flips with the appearance."
  (:require [jp-go-dds.core :as dds]
            [jp-go-dds.tokens :as dds-tokens]))

;; The theme map is gone. liquid-glass took one accent and derived a palette;
;; DADS ships its own (デジタル庁ブルー, `--color-key-900`) and an app does not
;; choose it. `#67e8f9` — the cyan this app used for its focus ring, eyebrow
;; and primary button — is simply no longer part of the design, which is what
;; adopting a base design system means.

(def track-palette
  "Track colors, from the HIG system palette rather than invented per track.

  The sample project shipped `#ff8a65`, `#67e8f9`, `#c4b5fd` — a coral, the
  accent, and a lavender that appear nowhere else in the workspace. A track
  color IS content (the user picks it), so it is data, not styling; what was
  wrong was sourcing it from nowhere. These are the same swatches every other
  surface uses for categorical color, and they have dark-appearance values."
  ["var(--hig-palette-orange)"
   "var(--hig-palette-cyan)"
   "var(--hig-palette-purple)"
   "var(--hig-palette-green)"
   "var(--hig-palette-pink)"
   "var(--hig-palette-yellow)"])

(defn track-color
  "Nth track color, cycling. Index-based so a project without explicit colors
  still reads as a set rather than as six unrelated hues."
  [i]
  (nth track-palette (mod i (count track-palette))))

(def app-css
  "The DAW's own CSS — unlayered, so it wins over the library layers without a
  single compound selector (agent-guide rule 3).

  Only the timeline. Buttons, inputs, typography, focus rings, the header
  surface and the page background all come from the design system now; what is
  left is the geometry a digital audio workstation needs and no token can
  express: the track-head column width, the beat grid, tick-positioned clips."
  (str
   ;; --- frame -------------------------------------------------------------
   ".daw-main{display:flex;flex-direction:column;min-height:100dvh}\n"
   ".daw-footer{margin-top:auto;padding:var(--hig-spacing-4) var(--hig-spacing-content-margin);"
   "color:var(--hig-color-secondary-label)}\n"
   ".daw-eyebrow{color:var(--hig-color-tint);letter-spacing:.14em}\n"
   ;; No `.daw-primary`: DADS states "this is the button you press" with the
   ;; button's own :type (:solid-fill), so the app stops painting it.
   ".daw-toolbar{display:flex;align-items:center;flex-wrap:wrap;"
   "gap:var(--hig-spacing-2);padding:var(--hig-spacing-2) var(--hig-spacing-content-margin);"
   "border-bottom:1px solid var(--hig-color-separator);"
   "background:var(--hig-color-secondary-system-background)}\n"
   ".daw-toolbar h1{margin:0;font-size:var(--hig-text-title3-font-size)}\n"
   ".daw-spacer{flex:1 1 auto}\n"
   ;; Transport readouts: adjacent numbers need a gap, or "Tick 1440" and
   ;; "1.50 s" read as one number.
   ".daw-readout{color:var(--hig-color-secondary-label);white-space:nowrap;"
   "margin-left:var(--hig-spacing-3)}\n"
   ;; The user-test aside, now a row of its own below the toolbar.
   ".bench-panel{display:flex;align-items:center;flex-wrap:wrap;"
   "gap:var(--hig-spacing-3);padding:var(--hig-spacing-3) var(--hig-spacing-content-margin);"
   "border-bottom:var(--hig-hairline) solid var(--hig-color-separator)}\n"
   ;; --- meta rows ----------------------------------------------------------
   ".daw-meta{display:flex;align-items:center;gap:var(--hig-spacing-3);flex-wrap:wrap;"
   "padding:var(--hig-spacing-4) var(--hig-spacing-content-margin);"
   "background:var(--hig-color-secondary-system-background)}\n"
   ".daw-meta label{display:flex;align-items:center;gap:var(--hig-spacing-2);"
   "color:var(--hig-color-secondary-label)}\n"
   ".daw-row{display:flex;align-items:center;gap:var(--hig-spacing-3)}\n"
   ;; --- timeline ----------------------------------------------------------
   ;; 190px is the track-head column. It is a layout constant of this app, not
   ;; a spacing token — a token would make it look shared when it is not.
   ".daw-editor{margin:var(--hig-spacing-4);border-radius:var(--hig-radius-large);"
   "overflow:hidden;border:var(--hig-hairline) solid var(--hig-color-separator);"
   "background:var(--hig-color-secondary-system-background)}\n"
   ".daw-ruler{margin-left:190px;display:flex;justify-content:space-around;"
   "padding:var(--hig-spacing-2);color:var(--hig-color-secondary-label);"
   "border-bottom:var(--hig-hairline) solid var(--hig-color-separator)}\n"
   ".daw-track{display:grid;grid-template-columns:190px 1fr;min-height:112px;"
   "border-bottom:var(--hig-hairline) solid var(--hig-color-separator)}\n"
   ".daw-track-head{padding:var(--hig-spacing-3);display:grid;gap:var(--hig-spacing-2);"
   "background:var(--hig-color-tertiary-system-background)}\n"
   ".daw-track-head input{width:100%}\n"
   ;; The beat grid: four bars per lane. color-mix over the separator token
   ;; keeps the gridline tied to every other hairline in the app.
   ".daw-lane{position:relative;background:repeating-linear-gradient(90deg,"
   "transparent 0,transparent calc(25% - 1px),var(--hig-color-separator) 25%)}\n"
   ".daw-clip{position:absolute;top:18px;height:72px;text-align:left;overflow:hidden;"
   "border:0;border-radius:var(--hig-radius-md);color:var(--hig-color-system-background);"
   "font-weight:700;cursor:pointer}\n"
   ".daw-scrub{width:calc(100% - 210px);margin:var(--hig-spacing-3) 10px var(--hig-spacing-3) 200px}\n"
   ;; --- narrow --------------------------------------------------------------
   "@media(max-width:720px){"
   ".daw-track{grid-template-columns:125px 1fr}"
   ".daw-ruler{margin-left:125px}"
   ".daw-scrub{width:calc(100% - 145px);margin-left:135px}"
   "}\n"))

(def app-css*
  "Everything the app contributes to a DADS page's `:app-css` slot: the
  `--hig-*` bridge, then the app's own rules.

  `app-css` below is the app's rules alone; this is what a page wants. The
  bridge comes first so the app can still override a token, the app last so
  its rules win. `jp-go-dds.page` emits `dds/ext-css` itself between the
  vendored bundle and this."
  (str dds-tokens/bridge-css "\n" app-css))

(defn stylesheet
  "The complete CSS for a host that is not going through `jp-go-dds.page`.
  `dds-css` is the vendored DADS stylesheet, read by the caller (nbb has no
  resource loader; see scripts/gen-page.cljs)."
  [dds-css]
  (str dds-css "\n" dds/ext-css "\n" app-css*))

(defn hex-free?
  "True when `s` contains no raw hex color. Used by the test that keeps this
  ns the only place in the app where one may appear (rule 2)."
  [s]
  (nil? (re-find #"#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3}(?:[0-9a-fA-F]{2})?)?\b" (str s))))

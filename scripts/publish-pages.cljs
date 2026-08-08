(ns publish-pages
  "Publish `public/` to GitHub Pages **without GitHub Actions**.

  ── why this exists ─────────────────────────────────────────────────────────
  `.github/workflows/pages.yml` built this site until 2026-08-04. Actions are
  now disabled repo-wide (ADR-2607300900: CI/CD is the murakumo fleet, not
  GitHub), so the workflow cannot run — and because it cannot run, nothing ever
  failed. `kotoba-lang.github.io/kami-app-daw/` simply kept serving the last
  artifact it had built: the pre-DADS liquid-glass page, long after the app moved
  onto jp-go-dds. A publish path that stops silently is worse than none, so this
  is the publish path now.

  The workflow file itself is still in the tree, and inert. Deleting it needs the
  `workflow` OAuth scope, which this workspace's token does not have.

  ── what it does ────────────────────────────────────────────────────────────
  Commits the built `public/` as the *root* tree of `refs/heads/gh-pages` and
  pushes it; Pages serves that branch directly, so no runner is involved. The
  commit is assembled with plumbing against a throwaway index, so the branch you
  are on is untouched — the only file written is `public/.nojekyll`.

  ── the guard that matters ──────────────────────────────────────────────────
  Deploys have no fast-forward check: last writer wins. Publishing from a
  checkout that does not contain `origin/main` silently reverts whatever landed
  in between — this happened to kotobase.net on 2026-07-25, where a signup-funnel
  fix was undone 11 minutes later by an older checkout, and nobody noticed. So
  this refuses unless `origin/main` is an ancestor of HEAD.

  Run:  npm run publish:pages
  Dry:  npm run publish:pages -- --dry-run"
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:process" :as process]
            [clojure.string :as str]))

(def branch "gh-pages")

(def required
  "What a complete publish contains. `js/` is gitignored in the source tree; on
  this branch it is the payload."
  ["index.html" "user-test-dashboard.html" "js/main.js" "js/dashboard.js"])

(defn- sh
  "Run a command, return trimmed stdout. Throws on non-zero exit."
  [cmd args & [opts]]
  (-> (cp/execFileSync cmd (clj->js args)
                       (clj->js (merge {:encoding "utf8"} opts)))
      str
      str/trim))

(defn- sh? [cmd args & [opts]]
  (try (sh cmd args opts) (catch :default _ nil)))

(defn- die [& msg]
  (println (str/join " " (cons "publish-pages:" msg)))
  (process/exit 1))

(def repo-root (sh "git" ["rev-parse" "--show-toplevel"]))
(def git-dir (sh "git" ["rev-parse" "--absolute-git-dir"]))
(def public-dir (path/join repo-root "public"))

(def slug
  "owner/repo, from the origin URL."
  (or (some-> (re-find #"github\.com[:/]([^/]+/[^/.\s]+)" (sh "git" ["remote" "get-url" "origin"]))
              second)
      (die "cannot read owner/repo from origin")))

(defn- assert-built! []
  (doseq [f required]
    (when-not (fs/existsSync (path/join public-dir f))
      (die "public/" f "is missing. Run: npm run release && npm run page"))))

(defn- assert-contains-main! []
  (sh "git" ["fetch" "origin" "--quiet"])
  (when-not (sh? "git" ["merge-base" "--is-ancestor" "origin/main" "HEAD"])
    (die (str "HEAD does not contain origin/main — publishing would revert what "
              "landed in between. Sync first: git merge --ff-only origin/main"))))

(defn- tree-of-public
  "Hash `public/` — including the gitignored `js/` — into a tree object, using a
  throwaway index so the real one is never touched."
  []
  (let [index (path/join (or (.. process -env -TMPDIR) "/tmp")
                         (str "publish-pages-" (path/basename repo-root) ".index"))
        env (doto (js/Object.assign #js {} (.-env process))
              (aset "GIT_INDEX_FILE" index))
        git (fn [args] (sh "git" (into ["--git-dir" git-dir "--work-tree" public-dir] args)
                           {:cwd public-dir :env env}))]
    (when (fs/existsSync index) (fs/unlinkSync index))
    (git ["add" "-A" "-f" "."])
    (git ["write-tree"])))

(defn- report-pages-config!
  "Report, do not reconfigure: this is live public infrastructure and the flip is
  one-time. `build_type: workflow` with no runner is the shape that produced the
  stale site."
  []
  (when-let [cfg (sh? "gh" ["api" (str "repos/" slug "/pages")
                            "--jq" "[.build_type, .source.branch] | join(\" \")"])]
    (when-not (= cfg (str "legacy " branch))
      (println)
      (println "  NOTE: Pages is configured as" (pr-str cfg) "— it will keep serving")
      (println "        whatever it served before this push. One-time fix:")
      (println (str "        gh api -X PUT repos/" slug "/pages -f build_type=legacy \\"))
      (println (str "          -f 'source[branch]=" branch "' -f 'source[path]=/'")))))

(defn -main [& args]
  (let [dry? (some #{"--dry-run"} args)
        source (sh "git" ["rev-parse" "HEAD"])]
    (assert-built!)
    (assert-contains-main!)
    ;; Pages runs Jekyll on a branch source unless told otherwise, and Jekyll
    ;; drops files it does not recognise.
    (fs/writeFileSync (path/join public-dir ".nojekyll") "")
    (let [tree (tree-of-public)
          parent (some->> (sh? "git" ["ls-remote" "origin" (str "refs/heads/" branch)])
                          not-empty
                          (re-find #"^\S+"))
          msg (str "pages: publish " (subs source 0 12) "\n\n"
                   "Built public/ served straight off " branch ". GitHub Actions are\n"
                   "disabled repo-wide (ADR-2607300900), which is why the previous\n"
                   "artifact went stale instead of failing.\n")
          commit (sh "git" (concat ["--git-dir" git-dir "commit-tree" tree]
                                   (when parent ["-p" parent])
                                   ["-m" msg]))
          [owner repo] (str/split slug #"/")]
      (println "source commit:" source)
      (println "tree:         " tree)
      (println "parent:       " (or parent "(root commit)"))
      (println "commit:       " commit)
      (if dry?
        (println "--dry-run: not pushing.")
        (do (sh "git" ["push" "origin" (str commit ":refs/heads/" branch)])
            (println "pushed" (str (subs commit 0 12) " -> " branch))
            (println (str "https://" owner ".github.io/" repo "/"))))
      (report-pages-config!))))

(apply -main (drop 2 (js->clj (.-argv process))))

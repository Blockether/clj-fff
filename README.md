# clj-fff

Clojure binding to [**fff**](https://github.com/dmtrKovalenko/fff) — fast, typo-tolerant file and content search for long-running tools.

It loads fff's native `libfff_c` in-process through the JDK Foreign Function & Memory API (`java.lang.foreign`) — no subprocess, no JNI.

> ⚠️ This is a vendored binding. The native `libfff_c` is built from the
> **Blockether fff fork**, pinned in one place — `resources/FFF_SOURCE`
> (`Blockether/fff@v0.10.1-blockether.1`) — which both `scripts/build-natives.sh`
> and the CI/release workflows read. The fork adds `fff_rescan_blocking` and
> `FffCreateOptions.respect_ignore_files` (create-options version 3) on top of
> upstream `v0.10.1`.

## Install

```clojure
com.blockether/fff {:mvn/version "0.11.0"}
```

Run the JVM with native access enabled:

```text
--enable-native-access=ALL-UNNAMED
```

## Native libraries

The main jar is small and does **not** bundle every platform binary. At runtime clj-fff resolves natives in this order:

1. `FFF_NATIVE_PATH` or JVM property `com.blockether.fff.native.path`
2. a bundled classpath resource, useful for tests/local development
3. a per-platform Clojars artifact downloaded into `~/.cache/clj-fff`

Published native artifacts use the same version as the main jar:

```clojure
com.blockether/fff-native-linux-x64    {:mvn/version "0.11.0"}
com.blockether/fff-native-linux-arm64  {:mvn/version "0.11.0"}
com.blockether/fff-native-darwin-arm64 {:mvn/version "0.11.0"}
com.blockether/fff-native-darwin-x64   {:mvn/version "0.11.0"}
com.blockether/fff-native-windows-x64  {:mvn/version "0.11.0"}
```

Linux artifacts are built on Debian Bullseye and require no symbols newer than
glibc 2.31, so they load on Ubuntu 22.04 and newer distributions.

Normally users only depend on `com.blockether/fff`; the matching native jar is fetched from Clojars on first use. Set `FFF_DISABLE_DOWNLOAD=true` for offline-only mode, or `FFF_CACHE_DIR` / `com.blockether.fff.cache-dir` to change the native cache directory.

## Usage

```clojure
(require '[com.blockether.fff :as fff])

(fff/with-instance [idx {:base-path "/repo" :watch? false}]
  (fff/wait-for-scan idx)
  (fff/search idx {:query "deps" :page-size 10})
  (fff/glob idx {:pattern "*.clj"})
  (fff/grep idx {:query "defn" :mode :plain :page-limit 20}))
```

Returned values are plain Clojure maps. `create` returns a `Closeable`; use `with-open`, `close`, or `destroy!`.

For a long-lived index that must see its own writes, use `rescan!` — it blocks
until the fresh index (including the grep content index) is queryable, which
`scan-files!` + `wait-for-scan` does not guarantee:

```clojure
(fff/with-instance [idx {:base-path "/repo"
                         :watch? true
                         ;; false = index files excluded by .gitignore/.ignore
                         ;; too (.git internals stay excluded either way)
                         :respect-ignore-files? true}]
  (spit "/repo/new.clj" "(ns new)")
  (fff/rescan! idx)                                  ; read-your-writes
  (fff/grep idx {:query "(ns new)" :mode :plain}))
```

### Ignore overlay

`create` layers three optional pattern lists on top of the ignore files. They
are honored by the scan walk **and** by the live watcher, so a re-included
subtree does not silently vanish on the next filesystem event:

```clojure
(fff/with-instance [idx {:base-path "/repo"
                         ;; per-directory ignore files beyond .gitignore/.ignore
                         :custom-ignore-filenames [".rgignore"]
                         ;; always dropped, even with :respect-ignore-files? false
                         :exclude-globs ["*.pem" "node_modules/"]
                         ;; kept although .gitignore excludes them (exclude wins)
                         :unignore-globs ["vendor/keep/**"]}]
  (fff/wait-for-scan idx)
  (fff/glob idx {:pattern "**"}))
```

Patterns use `.gitignore` syntax and resolve against `:base-path`.

## Develop

```bash
scripts/build-natives.sh   # build libfff_c for your machine into resources/prebuilds
clojure -X:test
clojure -T:build jar
clojure -T:build native-jar :platform '"darwin-arm64"'
```

## License

MIT (matching fff); vendored `libfff_c` binaries © the fff authors, MIT.

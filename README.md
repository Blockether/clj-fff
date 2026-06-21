# clj-fff

Clojure binding to [**fff**](https://github.com/dmtrKovalenko/fff) — fast, typo-tolerant file and content search for long-running tools.

It loads fff's native `libfff_c` in-process through the JDK Foreign Function & Memory API (`java.lang.foreign`) — no subprocess, no JNI.

> ⚠️ This is a vendored binding: `com.blockether/fff X.Y.Z` targets `fff vX.Y.Z`.

## Install

```clojure
com.blockether/fff {:mvn/version "0.9.6"}
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
com.blockether/fff-native-linux-x64    {:mvn/version "0.9.6"}
com.blockether/fff-native-linux-arm64  {:mvn/version "0.9.6"}
com.blockether/fff-native-darwin-arm64 {:mvn/version "0.9.6"}
com.blockether/fff-native-darwin-x64   {:mvn/version "0.9.6"}
com.blockether/fff-native-windows-x64  {:mvn/version "0.9.6"}
```

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

## Develop

```bash
scripts/build-natives.sh   # build libfff_c for your machine into resources/prebuilds
clojure -X:test
clojure -T:build jar
clojure -T:build native-jar :platform '"darwin-arm64"'
```

## License

MIT (matching fff); vendored `libfff_c` binaries © the fff authors, MIT.

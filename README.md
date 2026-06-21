# clj-fff

Clojure binding to [**fff**](https://github.com/dmtrKovalenko/fff) — fast, typo-tolerant file and content search for long-running tools.

It loads fff's native `libfff_c` in-process through the JDK Foreign Function & Memory API (`java.lang.foreign`) — no subprocess, no JNI.

> ⚠️ This is a vendored binding: `com.blockether/fff X.Y.Z` bundles `fff vX.Y.Z`.

## Install

```clojure
com.blockether/fff {:mvn/version "0.9.6"}
```

Run the JVM with native access enabled:

```text
--enable-native-access=ALL-UNNAMED
```

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
```

## License

MIT (matching fff); vendored `libfff_c` binaries © the fff authors, MIT.

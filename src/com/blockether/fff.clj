
(ns com.blockether.fff
  "Clojure binding to fff — fast typo-tolerant file/content search — through
   fff-c's C ABI using the JDK Foreign Function & Memory API."
  (:refer-clojure :exclude [search])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File Closeable InputStream]
           [java.lang.foreign Arena AddressLayout FunctionDescriptor Linker Linker$Option MemoryLayout MemorySegment SymbolLookup ValueLayout ValueLayout$OfBoolean ValueLayout$OfByte ValueLayout$OfInt ValueLayout$OfLong]
           [java.lang.invoke MethodHandle]
           [java.net URL]
           [java.nio.file CopyOption Files Path StandardCopyOption]
           [java.util.jar JarFile]))

(set! *warn-on-reflection* true)

(def ^:private create-options-version 1)

(declare destroy!)

(defrecord Fff [^MemorySegment handle]
  Closeable
  (close [this] (destroy! this)))

(defn- platform []
  (let [os (.. (System/getProperty "os.name") toLowerCase)
        arch (.. (System/getProperty "os.arch") toLowerCase)
        os* (cond
              (or (.contains os "mac") (.contains os "darwin")) "darwin"
              (.contains os "linux") "linux"
              (.contains os "win") "windows"
              :else (throw (ex-info (str "Unsupported OS for fff: " os) {:os os})))
        arch* (cond
                (#{"aarch64" "arm64"} arch) "arm64"
                (#{"x86_64" "amd64"} arch) "x64"
                :else (throw (ex-info (str "Unsupported arch for fff: " arch) {:arch arch})))]
    [os* arch*]))

(defn- lib-file-name [os]
  (case os
    "darwin" "libfff_c.dylib"
    "linux" "libfff_c.so"
    "windows" "fff_c.dll"))

(defn- configured-native-path ^Path []
  (when-let [p (or (System/getenv "FFF_NATIVE_PATH")
                   (System/getProperty "com.blockether.fff.native.path"))]
    (.toPath (io/file p))))

(defn- bundled-library-path ^Path [res fname]
  (when-let [^URL url (io/resource res)]
    (if (= "file" (.getProtocol url))
      (.toPath (io/file url))
      (let [tmp (doto (File/createTempFile "libfff_c" (subs fname (.lastIndexOf ^String fname ".")))
                  .deleteOnExit)]
        (with-open [in (io/input-stream url)]
          (io/copy in tmp))
        (.toPath tmp)))))

(defn- artifact-version []
  ;; Read a NAMESPACED resource. An unqualified "VERSION" at the jar root
  ;; collides with every other lib that ships one (rift, svar, …) — whichever is
  ;; first on the classpath wins, so a lib could resolve a FOREIGN version and try
  ;; to download a nonexistent <lib>-native-<that-version> (HTTP 404). `fff/VERSION`
  ;; is unique to this jar; read only it so a packaging mistake fails loudly here
  ;; rather than silently resolving someone else's version.
  (str/trim (slurp (io/resource "fff/VERSION"))))

(defn- cache-root ^Path []
  (if-let [p (or (System/getenv "FFF_CACHE_DIR")
                 (System/getProperty "com.blockether.fff.cache-dir"))]
    (.toPath (io/file p))
    (.toPath (io/file (System/getProperty "user.home") ".cache" "clj-fff"))))

(defn- native-artifact [platform]
  (str "fff-native-" platform))

(defn- resolve-native-jar ^Path [version platform]
  "Resolve the per-platform native jar through `clojure.tools.deps` — the same
   resolver the `clojure` CLI uses, so configured Maven repositories, mirrors and
   `~/.m2/settings.xml` are honoured (no hand-rolled HTTP to a hardcoded repo).
   Returns the jar's path in the local Maven repository. tools.deps is loaded via
   `requiring-resolve` so it is only touched on this runtime download path."
  (let [lib          (symbol "com.blockether" (native-artifact platform))
        create-basis (or (requiring-resolve 'clojure.tools.deps/create-basis)
                         (throw (ex-info "org.clojure/tools.deps is not on the classpath; cannot resolve the fff native artifact. Add com.blockether/<artifact>, set FFF_NATIVE_PATH, or add tools.deps."
                                  {:lib lib})))
        basis        (create-basis {:project nil :extra {:deps {lib {:mvn/version version}}}})
        path         (-> basis :libs (get lib) :paths first)]
    (when-not path
      (throw (ex-info (str "Could not resolve " lib " " version
                        " via Clojure's dependency resolver. Check your Maven repositories / mirrors.")
               {:lib lib :version version})))
    (.toPath (io/file path))))

(defn- extract-native! ^Path [^Path jar-path res ^Path dest]
  (Files/createDirectories (.getParent dest) (make-array java.nio.file.attribute.FileAttribute 0))
  (with-open [jar (JarFile. (.toFile jar-path))]
    (let [entry (.getEntry jar res)]
      (when-not entry
        (throw (ex-info (str "Native artifact is missing " res) {:jar (str jar-path) :resource res})))
      (with-open [^InputStream in (.getInputStream jar entry)]
        (let [^"[Ljava.nio.file.CopyOption;" opts (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])]
          (Files/copy in dest opts))))
    dest))

(defn- downloaded-library-path ^Path [platform res fname]
  (when-not (#{"1" "true" "yes"} (some-> (System/getenv "FFF_DISABLE_DOWNLOAD") str/lower-case))
    (let [version (artifact-version)
          root (cache-root)
          lib-path (.resolve root (str version "/" platform "/" fname))]
      (if (Files/exists lib-path (make-array java.nio.file.LinkOption 0))
        lib-path
        (extract-native! (resolve-native-jar version platform) res lib-path)))))

(defn- library-path ^Path []
  (let [[os arch] (platform)
        platform (str os "-" arch)
        fname (lib-file-name os)
        res (str "prebuilds/" platform "/" fname)]
    (or (configured-native-path)
        (bundled-library-path res fname)
        (downloaded-library-path platform res fname)
        (throw (ex-info (str "No fff native library for " platform
                             ". Add com.blockether/" (native-artifact platform)
                             ", set FFF_NATIVE_PATH, or enable runtime download.")
                        {:platform platform :resource res})))))

(def ^AddressLayout ^:private addr ValueLayout/ADDRESS)
(def ^ValueLayout$OfLong ^:private i64 ValueLayout/JAVA_LONG)
(def ^ValueLayout$OfInt ^:private i32 ValueLayout/JAVA_INT)
(def ^ValueLayout$OfInt ^:private u32 ValueLayout/JAVA_INT)
(def ^ValueLayout$OfByte ^:private u8 ValueLayout/JAVA_BYTE)
(def ^ValueLayout$OfBoolean ^:private bool ValueLayout/JAVA_BOOLEAN)

(defn- fd [ret & args]
  (if ret
    (FunctionDescriptor/of ret (into-array MemoryLayout args))
    (FunctionDescriptor/ofVoid (into-array MemoryLayout args))))

(defn- bind! []
  (let [linker (Linker/nativeLinker)
        arena (Arena/ofShared)
        lookup (SymbolLookup/libraryLookup (library-path) arena)
        opts (make-array Linker$Option 0)
        sym (fn [name] (.orElseThrow (.find lookup name)))
        down (fn [name desc] (.downcallHandle linker (sym name) desc opts))]
    {:create (down "fff_create_instance_with" (fd addr addr))
     :destroy (down "fff_destroy" (fd nil addr))
     :free-result (down "fff_free_result" (fd nil addr))
     :free-string (down "fff_free_string" (fd nil addr))
     :free-search (down "fff_free_search_result" (fd nil addr))
     :free-grep (down "fff_free_grep_result" (fd nil addr))
     :free-progress (down "fff_free_scan_progress" (fd nil addr))
     :search (down "fff_search" (fd addr addr addr addr u32 u32 u32 i32 u32))
     :glob (down "fff_glob" (fd addr addr addr addr u32 u32 u32))
     :grep (down "fff_live_grep" (fd addr addr addr u8 i64 u32 bool u32 u32 i64 u32 u32 bool))
     :scan (down "fff_scan_files" (fd addr addr))
     :wait-scan (down "fff_wait_for_scan" (fd addr addr i64))
     :progress (down "fff_get_scan_progress" (fd addr addr))
     :base-path (down "fff_get_base_path" (fd addr addr))
     :track-query (down "fff_track_query" (fd addr addr addr addr))
     :historical-query (down "fff_get_historical_query" (fd addr addr i64))
     :refresh-git-status (down "fff_refresh_git_status" (fd addr addr))
     :health (down "fff_health_check" (fd addr addr addr))
     :search-count (down "fff_search_result_get_count" (fd u32 addr))
     :search-total-matched (down "fff_search_result_get_total_matched" (fd u32 addr))
     :search-total-files (down "fff_search_result_get_total_files" (fd u32 addr))
     :search-item (down "fff_search_result_get_item" (fd addr addr u32))
     :search-score (down "fff_search_result_get_score" (fd addr addr u32))
     :file-relative (down "fff_file_item_get_relative_path" (fd addr addr))
     :file-name (down "fff_file_item_get_file_name" (fd addr addr))
     :file-git (down "fff_file_item_get_git_status" (fd addr addr))
     :file-size (down "fff_file_item_get_size" (fd i64 addr))
     :file-modified (down "fff_file_item_get_modified" (fd i64 addr))
     :file-frecency (down "fff_file_item_get_total_frecency_score" (fd i64 addr))
     :file-binary? (down "fff_file_item_get_is_binary" (fd bool addr))
     :grep-count (down "fff_grep_result_get_count" (fd u32 addr))
     :grep-total-matched (down "fff_grep_result_get_total_matched" (fd u32 addr))
     :grep-files-searched (down "fff_grep_result_get_total_files_searched" (fd u32 addr))
     :grep-total-files (down "fff_grep_result_get_total_files" (fd u32 addr))
     :grep-next-offset (down "fff_grep_result_get_next_file_offset" (fd u32 addr))
     :grep-regex-error (down "fff_grep_result_get_regex_fallback_error" (fd addr addr))
     :grep-match (down "fff_grep_result_get_match" (fd addr addr u32))
     :match-path (down "fff_grep_match_get_relative_path" (fd addr addr))
     :match-file (down "fff_grep_match_get_file_name" (fd addr addr))
     :match-git (down "fff_grep_match_get_git_status" (fd addr addr))
     :match-line (down "fff_grep_match_get_line_content" (fd addr addr))
     :match-line-number (down "fff_grep_match_get_line_number" (fd i64 addr))
     :match-col (down "fff_grep_match_get_col" (fd u32 addr))
     :match-byte-offset (down "fff_grep_match_get_byte_offset" (fd i64 addr))
     :match-definition? (down "fff_grep_match_get_is_definition" (fd bool addr))
     :match-binary? (down "fff_grep_match_get_is_binary" (fd bool addr))}))

(defonce ^:private handles (delay (bind!)))
(defn- h [k] (get @handles k))
(defn- invoke [k & args] (.invokeWithArguments ^MethodHandle (h k) (object-array args)))
(defn- null? [^MemorySegment p] (or (nil? p) (= 0 (.address p))))
(defn- cstr [^MemorySegment p] (when-not (null? p) (.getString (.reinterpret p Long/MAX_VALUE) 0)))
(defn- native-bool [x] (if x true false))

(defn- check-result [^MemorySegment result]
  (when (null? result)
    (throw (ex-info "fff returned a null result envelope" {:type :fff/protocol})))
  ;; Downcalls returning ADDRESS produce a zero-length segment; reinterpret it
  ;; before reading the C `FffResult` fields.
  (let [result* (.reinterpret result 32)
        success (.get result* ^ValueLayout$OfBoolean bool 0)
        err-p (.get result* ^AddressLayout addr 8)
        handle (.get result* ^AddressLayout addr 16)
        n (.get result* ^ValueLayout$OfLong i64 24)
        err (cstr err-p)]
    (invoke :free-result result)
    (if success
      {:handle handle :int n}
      (throw (ex-info (or err "fff error") {:type :fff/error})))))

(defn- put-address! [^MemorySegment s off ^MemorySegment v] (.set s ^AddressLayout addr (long off) (or v MemorySegment/NULL)))
(defn- put-bool! [^MemorySegment s off v] (.set s ^ValueLayout$OfBoolean bool (long off) (boolean v)))
(defn- put-u64! [^MemorySegment s off v] (.set s ^ValueLayout$OfLong i64 (long off) (long (or v 0))))
(defn- temp-cstr [^Arena arena x]
  (if (some? x) (.allocateFrom arena (str x)) MemorySegment/NULL))

(defn create
  "Create and return an `Fff` instance. Options:
   `:base-path` (default current dir), `:frecency-db-path`, `:history-db-path`,
   `:enable-mmap-cache?`, `:enable-content-indexing?`, `:watch?`, `:ai-mode?`,
   `:log-file-path`, `:log-level`, cache budget keys, and root/home scanning flags."
  ([] (create {}))
  ([{:keys [base-path frecency-db-path history-db-path enable-mmap-cache?
            enable-content-indexing? watch? ai-mode? log-file-path log-level
            cache-budget-max-files cache-budget-max-bytes cache-budget-max-file-size
            enable-fs-root-scanning? enable-home-dir-scanning?]
     :or {base-path (System/getProperty "user.dir")
          enable-mmap-cache? true
          enable-content-indexing? true
          watch? false
          ai-mode? false}}]
   (with-open [arena (Arena/ofConfined)]
     (let [opts (.allocate arena 88 8)]
       (.set opts ^ValueLayout$OfInt u32 0 (int create-options-version))
       (put-address! opts 8 (temp-cstr arena base-path))
       (put-address! opts 16 (temp-cstr arena frecency-db-path))
       (put-address! opts 24 (temp-cstr arena history-db-path))
       (put-bool! opts 32 enable-mmap-cache?)
       (put-bool! opts 33 enable-content-indexing?)
       (put-bool! opts 34 watch?)
       (put-bool! opts 35 ai-mode?)
       (put-address! opts 40 (temp-cstr arena log-file-path))
       (put-address! opts 48 (temp-cstr arena log-level))
       (put-u64! opts 56 cache-budget-max-files)
       (put-u64! opts 64 cache-budget-max-bytes)
       (put-u64! opts 72 cache-budget-max-file-size)
       (put-bool! opts 80 enable-fs-root-scanning?)
       (put-bool! opts 81 enable-home-dir-scanning?)
       (->Fff (:handle (check-result (invoke :create opts))))))))

(defn destroy! [^Fff fff]
  (when (and fff (not (null? (:handle fff))))
    (invoke :destroy (:handle fff))
    nil))

(defmacro with-instance [[sym opts] & body]
  `(with-open [~sym (create ~opts)] ~@body))

(defn wait-for-scan
  "Wait until the initial scan completes. Returns true on completion, false on timeout."
  ([fff] (wait-for-scan fff 30000))
  ([^Fff fff timeout-ms]
   (pos? (long (:int (check-result (invoke :wait-scan (:handle fff) (long timeout-ms))))))))

(defn scan-files! [^Fff fff]
  (check-result (invoke :scan (:handle fff)))
  nil)

(defn refresh-git-status! [^Fff fff]
  (:int (check-result (invoke :refresh-git-status (:handle fff)))))

(defn track-query! [^Fff fff query file-path]
  (with-open [arena (Arena/ofConfined)]
    (pos? (long (:int (check-result (invoke :track-query (:handle fff)
                                            (temp-cstr arena query)
                                            (temp-cstr arena file-path))))))))

(defn historical-query [^Fff fff offset]
  (let [p (:handle (check-result (invoke :historical-query (:handle fff) (long offset))))
        s (cstr p)]
    (when-not (null? p) (invoke :free-string p))
    s))

(defn base-path [^Fff fff]
  (let [p (:handle (check-result (invoke :base-path (:handle fff))))
        s (cstr p)]
    (when-not (null? p) (invoke :free-string p))
    s))

(defn health-check
  "Return fff-c's health-check JSON string. Pass nil or omit `test-path` to check the instance base path."
  ([fff] (health-check fff nil))
  ([^Fff fff test-path]
   (with-open [arena (Arena/ofConfined)]
     (let [p (:handle (check-result (invoke :health (if fff (:handle fff) MemorySegment/NULL)
                                            (temp-cstr arena test-path))))
           s (cstr p)]
       (when-not (null? p) (invoke :free-string p))
       s))))

(defn- file-item [^MemorySegment item]
  {:relative-path (cstr (invoke :file-relative item))
   :file-name (cstr (invoke :file-name item))
   :git-status (cstr (invoke :file-git item))
   :size (invoke :file-size item)
   :modified (invoke :file-modified item)
   :frecency-score (invoke :file-frecency item)
   :binary? (native-bool (invoke :file-binary? item))})

(defn- collect-search [^MemorySegment result]
  (try
    (let [n (invoke :search-count result)]
      {:items (vec (for [i (range n)] (file-item (invoke :search-item result (int i)))))
       :count n
       :total-matched (invoke :search-total-matched result)
       :total-files (invoke :search-total-files result)})
    (finally
      (invoke :free-search result))))

(defn search
  "Fuzzy path search. Options: `:query`, `:current-file`, `:max-threads`,
   `:page-index`, `:page-size`, `:combo-boost-multiplier`, `:min-combo-count`."
  [^Fff fff {:keys [query current-file max-threads page-index page-size combo-boost-multiplier min-combo-count]
             :or {query "" max-threads 0 page-index 0 page-size 100 combo-boost-multiplier 100 min-combo-count 3}}]
  (with-open [arena (Arena/ofConfined)]
    (collect-search (:handle (check-result
                              (invoke :search (:handle fff)
                                      (temp-cstr arena query) (temp-cstr arena current-file)
                                      (int max-threads) (int page-index) (int page-size)
                                      (int combo-boost-multiplier) (int min-combo-count)))))))

(defn glob
  "Glob-only path search. Options: `:pattern`, `:current-file`, `:max-threads`, `:page-index`, `:page-size`."
  [^Fff fff {:keys [pattern current-file max-threads page-index page-size]
             :or {max-threads 0 page-index 0 page-size 100}}]
  (with-open [arena (Arena/ofConfined)]
    (collect-search (:handle (check-result
                              (invoke :glob (:handle fff)
                                      (temp-cstr arena pattern) (temp-cstr arena current-file)
                                      (int max-threads) (int page-index) (int page-size)))))))

(defn- grep-match [^MemorySegment m]
  {:relative-path (cstr (invoke :match-path m))
   :file-name (cstr (invoke :match-file m))
   :git-status (cstr (invoke :match-git m))
   :line-content (cstr (invoke :match-line m))
   :line-number (invoke :match-line-number m)
   :col (invoke :match-col m)
   :byte-offset (invoke :match-byte-offset m)
   :definition? (native-bool (invoke :match-definition? m))
   :binary? (native-bool (invoke :match-binary? m))})

(defn grep
  "Content search. `:mode` is `:plain` (default), `:regex`, or `:fuzzy`.
   Other options mirror fff-c: max file size, matches/page limits, smart case,
   context, definition classification, file pagination, and time budget."
  [^Fff fff {:keys [query mode max-file-size max-matches-per-file smart-case? file-offset
                    page-limit time-budget-ms before-context after-context classify-definitions?]
             :or {mode :plain max-file-size 0 max-matches-per-file 0 smart-case? true
                  file-offset 0 page-limit 50 time-budget-ms 0 before-context 0 after-context 0
                  classify-definitions? false}}]
  (let [mode* (case mode :regex 1 :fuzzy 2 0)]
    (with-open [arena (Arena/ofConfined)]
      (let [result (:handle (check-result
                             (invoke :grep (:handle fff) (temp-cstr arena query) (byte mode*)
                                     (long max-file-size) (int max-matches-per-file) (boolean smart-case?)
                                     (int file-offset) (int page-limit) (long time-budget-ms)
                                     (int before-context) (int after-context) (boolean classify-definitions?))))]
        (try
          (let [n (invoke :grep-count result)]
            {:matches (vec (for [i (range n)] (grep-match (invoke :grep-match result (int i)))))
             :count n
             :total-matched (invoke :grep-total-matched result)
             :total-files-searched (invoke :grep-files-searched result)
             :total-files (invoke :grep-total-files result)
             :next-file-offset (invoke :grep-next-offset result)
             :regex-fallback-error (cstr (invoke :grep-regex-error result))})
          (finally
            (invoke :free-grep result)))))))

(ns com.blockether.fff
  "Clojure binding to fff — fast typo-tolerant file/content search — through
   fff-c's C ABI using the JDK Foreign Function & Memory API."
  (:refer-clojure :exclude [search])
  (:require [clojure.string :as str]
            [com.blockether.nativeresolver :as nr]
            [com.blockether.nativeresolver.ffm :as ffm])
  (:import [java.io Closeable]
           [java.lang.foreign AddressLayout Arena MemorySegment ValueLayout ValueLayout$OfBoolean ValueLayout$OfInt ValueLayout$OfLong]))

(set! *warn-on-reflection* true)

(def ^:private create-options-version 4)

(declare destroy!)

(defrecord Fff [^MemorySegment handle]
  Closeable
  (close [this] (destroy! this)))

(def ^:private native-spec
  "How libfff_c is found. Everything derives mechanically from `:lib`:
   FFF_NATIVE_PATH / `com.blockether.fff.native.path` (verbatim), the bundled
   `prebuilds/<platform>/<lib>` resource shipped by
   `com.blockether/fff-native-<platform>`, then the tools.deps download cached
   under ~/.cache/clj-fff (FFF_DISABLE_DOWNLOAD=1 to disable). The version comes
   from the NAMESPACED `fff/VERSION` resource."
  (nr/spec {:lib "fff"}))

(def ^AddressLayout ^:private addr ValueLayout/ADDRESS)
(def ^ValueLayout$OfLong ^:private i64 ValueLayout/JAVA_LONG)
(def ^ValueLayout$OfInt ^:private u32 ValueLayout/JAVA_INT)
(def ^ValueLayout$OfBoolean ^:private bool ValueLayout/JAVA_BOOLEAN)

(defonce ^:private handles
  (delay
    (nr/bind! native-spec
              {:create               [:ptr "fff_create_instance_with" :ptr]
               :destroy              [nil  "fff_destroy" :ptr]
               :free-result          [nil  "fff_free_result" :ptr]
               :free-string          [nil  "fff_free_string" :ptr]
               :free-search          [nil  "fff_free_search_result" :ptr]
               :free-directories     [nil  "fff_free_dir_search_result" :ptr]
               :free-mixed           [nil  "fff_free_mixed_search_result" :ptr]
               :free-grep            [nil  "fff_free_grep_result" :ptr]
               :free-progress        [nil  "fff_free_scan_progress" :ptr]
               :search               [:ptr "fff_search" :ptr :ptr :ptr :u32 :u32 :u32 :i32 :u32]
               :directories          [:ptr "fff_search_directories" :ptr :ptr :ptr :u32 :u32 :u32]
               :mixed                [:ptr "fff_search_mixed" :ptr :ptr :ptr :u32 :u32 :u32 :i32 :u32]
               :glob                 [:ptr "fff_glob" :ptr :ptr :ptr :u32 :u32 :u32]
               :grep                 [:ptr "fff_live_grep" :ptr :ptr :i8 :i64 :u32 :bool :u32 :u32 :i64 :u32 :u32 :bool]
               :scan                 [:ptr "fff_scan_files" :ptr]
               :wait-scan            [:ptr "fff_wait_for_scan" :ptr :i64]
               :rescan-blocking      [:ptr "fff_rescan_blocking" :ptr :i64]
               :progress             [:ptr "fff_get_scan_progress" :ptr]
               :base-path            [:ptr "fff_get_base_path" :ptr]
               :track-query          [:ptr "fff_track_query" :ptr :ptr :ptr]
               :historical-query     [:ptr "fff_get_historical_query" :ptr :i64]
               :refresh-git-status   [:ptr "fff_refresh_git_status" :ptr]
               :health               [:ptr "fff_health_check" :ptr :ptr]
               :search-count         [:u32 "fff_search_result_get_count" :ptr]
               :search-total-matched [:u32 "fff_search_result_get_total_matched" :ptr]
               :search-total-files   [:u32 "fff_search_result_get_total_files" :ptr]
               :search-item          [:ptr "fff_search_result_get_item" :ptr :u32]
               :search-score         [:ptr "fff_search_result_get_score" :ptr :u32]
               :file-relative        [:ptr "fff_file_item_get_relative_path" :ptr]
               :file-name            [:ptr "fff_file_item_get_file_name" :ptr]
               :file-git             [:ptr "fff_file_item_get_git_status" :ptr]
               :file-size            [:i64 "fff_file_item_get_size" :ptr]
               :file-modified        [:i64 "fff_file_item_get_modified" :ptr]
               :file-frecency        [:i64 "fff_file_item_get_total_frecency_score" :ptr]
               :file-binary?         [:bool "fff_file_item_get_is_binary" :ptr]
               :directories-count    [:u32 "fff_dir_search_result_get_count" :ptr]
               :directories-total-matched [:u32 "fff_dir_search_result_get_total_matched" :ptr]
               :directories-total    [:u32 "fff_dir_search_result_get_total_dirs" :ptr]
               :directories-item     [:ptr "fff_dir_search_result_get_item" :ptr :u32]
               :directory-relative   [:ptr "fff_dir_item_get_relative_path" :ptr]
               :directory-name       [:ptr "fff_dir_item_get_dir_name" :ptr]
               :directory-frecency   [:i32 "fff_dir_item_get_max_access_frecency" :ptr]
               :mixed-count          [:u32 "fff_mixed_search_result_get_count" :ptr]
               :mixed-total-matched  [:u32 "fff_mixed_search_result_get_total_matched" :ptr]
               :mixed-total-files    [:u32 "fff_mixed_search_result_get_total_files" :ptr]
               :mixed-total-dirs     [:u32 "fff_mixed_search_result_get_total_dirs" :ptr]
               :mixed-item           [:ptr "fff_mixed_search_result_get_item" :ptr :u32]
               :mixed-directory?     [:bool "fff_mixed_item_get_is_directory" :ptr]
               :mixed-relative       [:ptr "fff_mixed_item_get_relative_path" :ptr]
               :mixed-name           [:ptr "fff_mixed_item_get_display_name" :ptr]
               :mixed-git            [:ptr "fff_mixed_item_get_git_status" :ptr]
               :mixed-size           [:i64 "fff_mixed_item_get_size" :ptr]
               :mixed-modified       [:i64 "fff_mixed_item_get_modified" :ptr]
               :mixed-access-frecency [:i64 "fff_mixed_item_get_access_frecency_score" :ptr]
               :mixed-modification-frecency [:i64 "fff_mixed_item_get_modification_frecency_score" :ptr]
               :mixed-frecency       [:i64 "fff_mixed_item_get_total_frecency_score" :ptr]
               :mixed-binary?        [:bool "fff_mixed_item_get_is_binary" :ptr]
               :grep-count           [:u32 "fff_grep_result_get_count" :ptr]
               :grep-total-matched   [:u32 "fff_grep_result_get_total_matched" :ptr]
               :grep-files-searched  [:u32 "fff_grep_result_get_total_files_searched" :ptr]
               :grep-total-files     [:u32 "fff_grep_result_get_total_files" :ptr]
               :grep-next-offset     [:u32 "fff_grep_result_get_next_file_offset" :ptr]
               :grep-regex-error     [:ptr "fff_grep_result_get_regex_fallback_error" :ptr]
               :grep-match           [:ptr "fff_grep_result_get_match" :ptr :u32]
               :match-path           [:ptr "fff_grep_match_get_relative_path" :ptr]
               :match-file           [:ptr "fff_grep_match_get_file_name" :ptr]
               :match-git            [:ptr "fff_grep_match_get_git_status" :ptr]
               :match-line           [:ptr "fff_grep_match_get_line_content" :ptr]
               :match-line-number    [:i64 "fff_grep_match_get_line_number" :ptr]
               :match-col            [:u32 "fff_grep_match_get_col" :ptr]
               :match-byte-offset    [:i64 "fff_grep_match_get_byte_offset" :ptr]
               :match-definition?    [:bool "fff_grep_match_get_is_definition" :ptr]
               :match-binary?        [:bool "fff_grep_match_get_is_binary" :ptr]
               ;; Context lines fff ALREADY captured for `:before-context` /
               ;; `:after-context`. Without these accessors the native side
               ;; collected them and Clojure threw them away, so every caller
               ;; had to re-read the file in the JVM just to rebuild context it
               ;; had already paid for.
               :match-before-count   [:u32 "fff_grep_match_get_context_before_count" :ptr]
               :match-before-line    [:ptr "fff_grep_match_get_context_before" :ptr :u32]
               :match-after-count    [:u32 "fff_grep_match_get_context_after_count" :ptr]
               :match-after-line     [:ptr "fff_grep_match_get_context_after" :ptr :u32]
               ;; Stateless directory listing (`fff_list_directory`): no index, no
               ;; instance, and therefore allowed in a filesystem ROOT or a HOME
               ;; directory, where `fff_create_instance_with` refuses to scan.
               :list-directory       [:ptr "fff_list_directory" :ptr :u32 :bool :bool :bool]
               :free-list            [nil  "fff_free_list_result" :ptr]
               :list-count           [:u32 "fff_list_result_get_count" :ptr]
               :list-item            [:ptr "fff_list_result_get_item" :ptr :u32]
               :list-relative        [:ptr "fff_list_item_get_relative_path" :ptr]
               :list-name            [:ptr "fff_list_item_get_name" :ptr]
               :list-depth           [:u32 "fff_list_item_get_depth" :ptr]
               :list-dir?            [:bool "fff_list_item_get_is_dir" :ptr]
               :list-size            [:i64 "fff_list_item_get_size" :ptr]
               :list-modified        [:i64 "fff_list_item_get_modified" :ptr]})))

(defn- invoke [k & args] (apply nr/invoke @handles k args))
(defn- null? [^MemorySegment p] (ffm/null? p))
(defn- cstr [^MemorySegment p] (ffm/c-string p))
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
(defn- lines-cstr
  "Join a seq of overlay patterns into the newline-separated string the C ABI
  takes. nil/empty -> nil, so the field stays NULL."
  [xs]
  (when (seq xs)
    (let [joined (str/join "\n" (remove str/blank? (map str xs)))]
      (when-not (str/blank? joined) joined))))

(defn- temp-cstr [^Arena arena x]
  (if (some? x) (.allocateFrom arena (str x)) MemorySegment/NULL))

(defn create
  "Create and return an `Fff` instance. Options:
   `:base-path` (default current dir), `:frecency-db-path`, `:history-db-path`,
   `:enable-mmap-cache?`, `:enable-content-indexing?`, `:watch?`, `:ai-mode?`,
   `:log-file-path`, `:log-level`, cache budget keys, `:follow-symlinks?`, root/home scanning flags,
   and `:respect-ignore-files?` (default true — set false to index files excluded by
   `.gitignore`/`.ignore`/`.git/info/exclude`; `.git` internals stay excluded either way).

   Ignore overlay (each a seq of strings, all optional):
   - `:custom-ignore-filenames` — extra per-directory ignore-file names to honor,
     e.g. `[\".rgignore\"]`. The `ignore` crate registers `.gitignore`/`.ignore`
     itself; anything else must be opted in here.
   - `:exclude-globs` — gitignore-syntax patterns ALWAYS excluded, whatever the
     ignore files say and even when `:respect-ignore-files?` is false.
   - `:unignore-globs` — gitignore-syntax patterns RE-INCLUDED although an ignore
     file excludes them (a whitelist; `:exclude-globs` still wins). No-op when
     `:respect-ignore-files?` is false.
   The overlay is honored by the scan walk AND by the live watcher, so a
   re-included subtree stays in the index."
  ([] (create {}))
  ([{:keys [base-path frecency-db-path history-db-path enable-mmap-cache?
            enable-content-indexing? watch? ai-mode? log-file-path log-level
            cache-budget-max-files cache-budget-max-bytes cache-budget-max-file-size
            enable-fs-root-scanning? enable-home-dir-scanning? follow-symlinks?
            respect-ignore-files? custom-ignore-filenames exclude-globs unignore-globs]
     :or {base-path (System/getProperty "user.dir")
          ;; OFF by default: fff's mmap cache SIGBUSes the whole host
          ;; process when a cached file is modified concurrently (vis runs
          ;; fff in-process). Upstream's own recommendation over PR #626.
          enable-mmap-cache? false
          enable-content-indexing? true
          watch? false
          ai-mode? false
          respect-ignore-files? true}}]
   (with-open [arena (Arena/ofConfined)]
     (let [opts (.allocate arena 112 8)]
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
       (put-bool! opts 82 follow-symlinks?)
       (put-bool! opts 83 respect-ignore-files?)
       (put-address! opts 88 (temp-cstr arena (lines-cstr custom-ignore-filenames)))
       (put-address! opts 96 (temp-cstr arena (lines-cstr exclude-globs)))
       (put-address! opts 104 (temp-cstr arena (lines-cstr unignore-globs)))
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

(defn scan-files!
  "Trigger an ASYNC rescan. Fire-and-forget: the request is dropped into a
  pending flag while a previous scan's post-scan indexing is still running, and
  `wait-for-scan` returns before the grep content index is rebuilt. Use
  `rescan!` when the caller must see its own writes."
  [^Fff fff]
  (check-result (invoke :scan (:handle fff)))
  nil)

(defn rescan!
  "Rescan the filesystem and BLOCK until the fresh index is queryable.

  Read-your-writes: after this returns true, `search`/`grep` see files and file
  contents written before the call. Returns false on timeout."
  ([fff] (rescan! fff 30000))
  ([^Fff fff timeout-ms]
   (pos? (long (:int (check-result (invoke :rescan-blocking (:handle fff) (long timeout-ms))))))))

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

(defn- directory-path [path]
  (if (and (seq path) (.endsWith ^String path "/"))
    (subs path 0 (dec (count path)))
    path))

(defn- directory-item [^MemorySegment item]
  {:relative-path (directory-path (cstr (invoke :directory-relative item)))
   :dir-name (cstr (invoke :directory-name item))
   :max-access-frecency (invoke :directory-frecency item)})

(defn- collect-directories [^MemorySegment result]
  (try
    (let [n (invoke :directories-count result)]
      {:items (vec (for [i (range n)] (directory-item (invoke :directories-item result (int i)))))
       :count n
       :total-matched (invoke :directories-total-matched result)
       :total-dirs (invoke :directories-total result)})
    (finally
      (invoke :free-directories result))))

(defn search-directories
  "Fuzzy directory search over fff's live directory index. Options: `:query`,
   `:current-file`, `:max-threads`, `:page-index`, and `:page-size`."
  [^Fff fff {:keys [query current-file max-threads page-index page-size]
             :or {query "" max-threads 0 page-index 0 page-size 100}}]
  (with-open [arena (Arena/ofConfined)]
    (collect-directories (:handle (check-result
                                   (invoke :directories (:handle fff)
                                           (temp-cstr arena query) (temp-cstr arena current-file)
                                           (int max-threads) (int page-index) (int page-size)))))))

(defn- mixed-item [^MemorySegment item]
  (let [directory? (native-bool (invoke :mixed-directory? item))
        relative-path (cstr (invoke :mixed-relative item))]
    {:directory? directory?
     :relative-path (if directory? (directory-path relative-path) relative-path)
     :display-name (cstr (invoke :mixed-name item))
     :git-status (cstr (invoke :mixed-git item))
     :size (invoke :mixed-size item)
     :modified (invoke :mixed-modified item)
     :access-frecency-score (invoke :mixed-access-frecency item)
     :modification-frecency-score (invoke :mixed-modification-frecency item)
     :frecency-score (invoke :mixed-frecency item)
     :binary? (native-bool (invoke :mixed-binary? item))}))

(defn- collect-mixed [^MemorySegment result]
  (try
    (let [n (invoke :mixed-count result)]
      {:items (vec (for [i (range n)] (mixed-item (invoke :mixed-item result (int i)))))
       :count n
       :total-matched (invoke :mixed-total-matched result)
       :total-files (invoke :mixed-total-files result)
       :total-dirs (invoke :mixed-total-dirs result)})
    (finally
      (invoke :free-mixed result))))

(defn search-mixed
  "Fuzzy file-and-directory search over fff's live indexes. Options mirror `search`."
  [^Fff fff {:keys [query current-file max-threads page-index page-size combo-boost-multiplier min-combo-count]
             :or {query "" max-threads 0 page-index 0 page-size 100 combo-boost-multiplier 100 min-combo-count 3}}]
  (with-open [arena (Arena/ofConfined)]
    (collect-mixed (:handle (check-result
                             (invoke :mixed (:handle fff)
                                     (temp-cstr arena query) (temp-cstr arena current-file)
                                     (int max-threads) (int page-index) (int page-size)
                                     (int combo-boost-multiplier) (int min-combo-count)))))))

(defn- context-lines
  "The context lines fff captured on ONE match, in file order. `count-k` and
   `line-k` are the bound count/line accessors; a null line pointer ends the
   vector, since the native accessor returns null past the captured window.
   Empty when the grep asked for no context."
  [^MemorySegment m count-k line-k]
  (let [n (long (invoke count-k m))]
    (loop [i 0 acc (transient [])]
      (if (>= i n)
        (persistent! acc)
        (let [^MemorySegment p (invoke line-k m (int i))]
          (if (null? p)
            (persistent! acc)
            (recur (inc i) (conj! acc (cstr p)))))))))

(defn- grep-match [^MemorySegment m]
  {:relative-path (cstr (invoke :match-path m))
   :file-name (cstr (invoke :match-file m))
   :git-status (cstr (invoke :match-git m))
   :line-content (cstr (invoke :match-line m))
   :line-number (invoke :match-line-number m)
   :col (invoke :match-col m)
   :byte-offset (invoke :match-byte-offset m)
   :definition? (native-bool (invoke :match-definition? m))
   :binary? (native-bool (invoke :match-binary? m))
   ;; `:before-context`/`:after-context` lines, nearest-last / nearest-first as
   ;; fff emits them. Empty vectors when no context was requested.
   :context-before (context-lines m :match-before-count :match-before-line)
   :context-after (context-lines m :match-after-count :match-after-line)})

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

(defn- list-item [^MemorySegment item]
  {:relative-path (cstr (invoke :list-relative item))
   :name (cstr (invoke :list-name item))
   :depth (invoke :list-depth item)
   :dir? (native-bool (invoke :list-dir? item))
   :size (invoke :list-size item)
   :modified (invoke :list-modified item)})

(defn list-directory
  "List `path` itself — one bounded, ignore-aware directory walk, sorted by
   relative path. Stateless: it needs no `Fff` instance and no index, so it is
   the ONE fff entry point that works in a filesystem root or a home directory,
   which `create` refuses to scan.

   Options: `:max-depth` (default 1, the immediate children; 0 means unlimited),
   `:include-hidden?` (default false — dotfiles are omitted), and
   `:respect-ignore-files?` (default true — `.gitignore`/`.ignore` and fff's own
   cache-directory rules apply), `:follow-symlinks?` (default false — a symlinked
   directory is listed but never descended).

   Returns a vector of `{:relative-path :name :depth :dir? :size :modified}`.
   Throws when `path` is missing or is not a directory."
  ([path] (list-directory path {}))
  ([path {:keys [max-depth include-hidden? respect-ignore-files? follow-symlinks?]
          :or {max-depth 1 include-hidden? false respect-ignore-files? true follow-symlinks? false}}]
   (with-open [arena (Arena/ofConfined)]
     (let [result (:handle (check-result
                            (invoke :list-directory (temp-cstr arena path)
                                    (int max-depth) (boolean include-hidden?)
                                    (boolean respect-ignore-files?) (boolean follow-symlinks?))))]
       (try
         (vec (for [i (range (invoke :list-count result))]
                (list-item (invoke :list-item result (int i)))))
         (finally
           (invoke :free-list result)))))))

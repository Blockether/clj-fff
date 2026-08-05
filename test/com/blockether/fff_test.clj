(ns com.blockether.fff-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.fff :as fff])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- git-init!
  "fff honors `.gitignore` only inside a git work tree."
  [^File dir]
  (let [pb (doto (ProcessBuilder. ["git" "init" "--quiet"])
             (.directory dir))]
    (assert (zero? (.waitFor (.start pb))) "git init failed")))

(defn- temp-dir [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(deftest binding-loads-and-searches
  (testing "real fff-c native binding: index, fuzzy search, glob, and grep"
    (let [^File dir (temp-dir "fff-src")]
      (spit (io/file dir "alpha.clj") "(ns alpha)
(defn hello [] :world)
")
      (spit (io/file dir "README.md") "hello fff
")
      (with-open [^com.blockether.fff.Fff idx (fff/create {:base-path (str dir) :watch? false :enable-content-indexing? true})]
        (is (fff/wait-for-scan idx 30000))
        (is (= (.getCanonicalPath dir) (.getCanonicalPath ^File (io/file (fff/base-path idx)))))
        (is (some #(= "alpha.clj" (:relative-path %)) (:items (fff/search idx {:query "alpha"}))))
        (is (some #(= "alpha.clj" (:relative-path %)) (:items (fff/glob idx {:pattern "*.clj"}))))
        (is (some #(= "alpha.clj" (:relative-path %)) (:matches (fff/grep idx {:query "defn" :page-limit 10}))))))))

(deftest directory-and-mixed-searches
  (testing "directory searches retain empty directories and mixed searches preserve item kinds"
    (let [^File dir (temp-dir "fff-directories")]
      (.mkdirs (io/file dir "empty"))
      (.mkdirs (io/file dir "nested" "child"))
      (spit (io/file dir "nested" "child" "alpha.clj") "(ns alpha)\n")
      (with-open [^com.blockether.fff.Fff idx (fff/create {:base-path (str dir) :watch? false})]
        (is (fff/wait-for-scan idx 30000))
        (let [directories (fff/search-directories idx {:query "" :page-size 100})
              directory-paths (set (map :relative-path (:items directories)))
              mixed (fff/search-mixed idx {:query "" :page-size 100})
              mixed-by-path (into {} (map (juxt :relative-path identity) (:items mixed)))]
          (is (contains? directory-paths "empty"))
          (is (contains? directory-paths "nested"))
          (is (contains? directory-paths "nested/child"))
          (is (= (:count directories) (count (:items directories))))
          (is (<= (:count directories) (:total-matched directories)))
          (is (true? (get-in mixed-by-path ["empty" :directory?])))
          (is (false? (get-in mixed-by-path ["nested/child/alpha.clj" :directory?])))
          (is (= (:count mixed) (count (:items mixed))))
          (is (<= (:count mixed) (:total-matched mixed))))))))

(deftest follow-symlinks-option
  (testing "v2 create-options: :follow-symlinks? is accepted and follows a symlinked dir"
    (let [^File dir (temp-dir "fff-sym")
          real (io/file dir "real")]
      (.mkdirs real)
      (spit (io/file real "target.clj") "(ns target)\n")
      (Files/createSymbolicLink (.toPath (io/file dir "link"))
                                (.toPath real)
                                (make-array java.nio.file.attribute.FileAttribute 0))
      ;; Requires FffCreateOptions version 2 — an older native rejects this create.
      (with-open [^com.blockether.fff.Fff idx (fff/create {:base-path (str dir)
                                                           :follow-symlinks? true
                                                           :watch? false})]
        (is (fff/wait-for-scan idx 30000))
        ;; The file is reachable both directly and through the followed symlink.
        (is (some #(= "target.clj" (:file-name %)) (:items (fff/search idx {:query "target"}))))))))

(deftest respect-ignore-files-option
  (testing "v3 create-options: :respect-ignore-files? false indexes .gitignore'd files"
    (let [^File dir (temp-dir "fff-ignore")]
      ;; `.gitignore` is only honoured inside a git work tree.
      (.mkdirs (io/file dir ".git"))
      (spit (io/file dir ".gitignore") "hidden-by-ignore.clj\n")
      (spit (io/file dir "hidden-by-ignore.clj") "(ns hidden)\n")
      (spit (io/file dir "visible.clj") "(ns visible)\n")
      (with-open [^com.blockether.fff.Fff idx (fff/create {:base-path (str dir) :watch? false})]
        (is (fff/wait-for-scan idx 30000))
        (let [names (set (map :file-name (:items (fff/search idx {:query "" :page-size 100}))))]
          (is (contains? names "visible.clj"))
          (is (not (contains? names "hidden-by-ignore.clj")))))
      (with-open [^com.blockether.fff.Fff idx (fff/create {:base-path (str dir)
                                                           :watch? false
                                                           :respect-ignore-files? false})]
        (is (fff/wait-for-scan idx 30000))
        (let [names (set (map :file-name (:items (fff/search idx {:query "" :page-size 100}))))]
          (is (contains? names "visible.clj"))
          (is (contains? names "hidden-by-ignore.clj")))))))

(deftest ignore-overlay-options
  (testing "v4 create-options: custom ignore filenames, exclude + unignore globs"
    (let [^File dir (temp-dir "fff-overlay")
          _ (git-init! dir)
          paths (fn [idx]
                  (set (map :relative-path (:items (fff/glob idx {:pattern "**" :limit 500})))))]
      (spit (io/file dir ".gitignore") "vendor/\n")
      (spit (io/file dir ".rgignore") "generated/\n")
      (.mkdirs (io/file dir "vendor" "keep"))
      (.mkdirs (io/file dir "vendor" "drop"))
      (.mkdirs (io/file dir "generated"))
      (spit (io/file dir "main.clj") "(ns main)\n")
      (spit (io/file dir "secret.pem") "key\n")
      (spit (io/file dir "generated" "out.clj") "(ns out)\n")
      (spit (io/file dir "vendor" "keep" "lib.clj") "(ns lib)\n")
      (spit (io/file dir "vendor" "drop" "junk.clj") "(ns junk)\n")

      (testing "no overlay: .rgignore is inert, vendor/ is dropped by .gitignore"
        (with-open [^com.blockether.fff.Fff idx (fff/create {:base-path (str dir) :watch? false})]
          (is (fff/wait-for-scan idx 30000))
          (let [ps (paths idx)]
            (is (contains? ps "generated/out.clj"))
            (is (not-any? #(str/starts-with? % "vendor/") ps)))))

      (testing "overlay: .rgignore honored, *.pem excluded, vendor/keep re-included"
        (with-open [^com.blockether.fff.Fff idx
                    (fff/create {:base-path (str dir)
                                 :watch? false
                                 :custom-ignore-filenames [".rgignore"]
                                 :exclude-globs ["*.pem"]
                                 :unignore-globs ["vendor/keep/**"]})]
          (is (fff/wait-for-scan idx 30000))
          (let [ps (paths idx)]
            (is (contains? ps "main.clj"))
            (is (contains? ps "vendor/keep/lib.clj"))
            (is (not (contains? ps "generated/out.clj")))
            (is (not (contains? ps "secret.pem")))
            (is (not (contains? ps "vendor/drop/junk.clj")))))))))

(deftest grep-returns-native-context-lines
  (testing "context lines requested from fff-c reach Clojure instead of being dropped"
    (let [^File dir (temp-dir "fff-grep-context")]
      (spit (io/file dir "ctx.txt")
            (str/join "\n" ["L1" "L2" "L3 ZZNEEDLEZZ" "L4" "L5"]))
      (with-open [^com.blockether.fff.Fff idx (fff/create {:base-path (str dir)
                                                           :watch? false
                                                           :enable-content-indexing? true})]
        (is (fff/wait-for-scan idx 30000))
        (let [hit (first (:matches (fff/grep idx {:query "ZZNEEDLEZZ"
                                                  :page-limit 10
                                                  :before-context 2
                                                  :after-context 2})))]
          (is (some? hit))
          (is (= "L3 ZZNEEDLEZZ" (:line-content hit)))
          (is (= 3 (:line-number hit)))
          (is (= ["L1" "L2"] (:context-before hit)))
          (is (= ["L4" "L5"] (:context-after hit))))
        (testing "no context requested -> empty vectors, never nil"
          (let [hit (first (:matches (fff/grep idx {:query "ZZNEEDLEZZ" :page-limit 10})))]
            (is (= [] (:context-before hit)))
            (is (= [] (:context-after hit)))))))))

(deftest list-directory-without-an-index
  (testing "stateless listing: bounded depth, ignore-aware, dotfiles on request"
    (let [^File dir (temp-dir "fff-list")]
      (git-init! dir)
      (spit (io/file dir ".gitignore") "ignored.txt\n")
      (spit (io/file dir "alpha.clj") "(ns alpha)\n")
      (spit (io/file dir "ignored.txt") "nope\n")
      (spit (io/file dir ".hidden") "psst\n")
      (.mkdirs (io/file dir "nested" "child"))
      (spit (io/file dir "nested" "child" "beta.clj") "(ns beta)\n")
      (let [names (fn [opts] (set (map :relative-path (fff/list-directory (str dir) opts))))]
        (is (= #{"alpha.clj" "nested"} (names {})))
        (is (contains? (names {:include-hidden? true}) ".hidden"))
        (is (contains? (names {:respect-ignore-files? false}) "ignored.txt"))
        (is (contains? (names {:max-depth 3}) "nested/child/beta.clj")))
      (let [entries (fff/list-directory (str dir))
            by-path (into {} (map (juxt :relative-path identity) entries))]
        (is (true? (:dir? (get by-path "nested"))))
        (is (false? (:dir? (get by-path "alpha.clj"))))
        (is (= "alpha.clj" (:name (get by-path "alpha.clj"))))
        (is (= 1 (:depth (get by-path "alpha.clj"))))
        (is (pos? (:size (get by-path "alpha.clj"))))
        (is (pos? (:modified (get by-path "alpha.clj"))))))))

(deftest list-directory-in-a-filesystem-root-or-home
  (testing "the places an fff INDEX refuses: listing them still works"
    (is (seq (fff/list-directory "/")))
    (is (seq (fff/list-directory (System/getProperty "user.home") {:include-hidden? true})))
    (is (thrown? clojure.lang.ExceptionInfo (fff/list-directory "/definitely/not/here")))))

(ns com.blockether.fff-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.fff :as fff])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

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

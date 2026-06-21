(ns com.blockether.fff-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.blockether.fff :as fff])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(deftest binding-loads-and-searches
  (testing "real fff-c native binding: index, fuzzy search, glob, and grep"
    (let [dir (temp-dir "fff-src")]
      (spit (io/file dir "alpha.clj") "(ns alpha)
(defn hello [] :world)
")
      (spit (io/file dir "README.md") "hello fff
")
      (with-open [idx (fff/create {:base-path (str dir) :watch? false :enable-content-indexing? true})]
        (is (fff/wait-for-scan idx 30000))
        (is (= (str dir) (fff/base-path idx)))
        (is (some #(= "alpha.clj" (:relative-path %)) (:items (fff/search idx {:query "alpha"}))))
        (is (some #(= "alpha.clj" (:relative-path %)) (:items (fff/glob idx {:pattern "*.clj"}))))
        (is (some #(= "alpha.clj" (:relative-path %)) (:matches (fff/grep idx {:query "defn" :page-limit 10}))))))))

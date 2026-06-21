(ns build
  "Build/deploy for clj-fff: one jar at `com.blockether/fff` bundling libfff_c."
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.blockether/fff)
(def version
  (let [v (System/getenv "VERSION")]
    (cond
      (and v (str/starts-with? v "v")) (subs v 1)
      v v
      :else (str (str/trim (slurp "resources/VERSION")) "-SNAPSHOT"))))
(def class-dir "target/classes")
(def jar-file (format "target/%s.jar" (name lib)))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_] (b/delete {:path "target"}))
(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data [[:description "Clojure binding to fff — fast file and content search — via JDK FFM."]
                           [:url "https://github.com/Blockether/clj-fff"]
                           [:licenses [:license [:name "MIT License"] [:url "https://opensource.org/licenses/MIT"]]]
                           [:scm [:url "https://github.com/Blockether/clj-fff"]
                            [:connection "scm:git:https://github.com/Blockether/clj-fff.git"]
                            [:developerConnection "scm:git:ssh://git@github.com/Blockether/clj-fff.git"]]]})
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Built:" jar-file "version:" version))
(defn deploy [_] (jar nil) (dd/deploy {:installer :remote :artifact jar-file :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
(defn install [_] (jar nil) (dd/deploy {:installer :local :artifact jar-file :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

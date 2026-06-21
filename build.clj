(ns build
  "Build/deploy for clj-fff. The main `com.blockether/fff` jar is small; native
  libraries are published as per-platform artifacts such as
  `com.blockether/fff-native-linux-x64`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.blockether/fff)
(def native-platforms #{"linux-x64" "linux-arm64" "darwin-arm64" "darwin-x64" "windows-x64"})
(def native-libs {"linux-x64" "libfff_c.so"
                  "linux-arm64" "libfff_c.so"
                  "darwin-arm64" "libfff_c.dylib"
                  "darwin-x64" "libfff_c.dylib"
                  "windows-x64" "fff_c.dll"})

(def version
  (let [v (System/getenv "VERSION")]
    (cond
      (and v (str/starts-with? v "v")) (subs v 1)
      v v
      :else (str (str/trim (slurp "resources/VERSION")) "-SNAPSHOT"))))
(def class-dir "target/classes")
(def native-class-dir "target/native-classes")
(def jar-file (format "target/%s.jar" (name lib)))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_] (b/delete {:path "target"}))

(defn- pom-data [description]
  [[:description description]
   [:url "https://github.com/Blockether/clj-fff"]
   [:licenses [:license [:name "MIT License"] [:url "https://opensource.org/licenses/MIT"]]]
   [:scm [:url "https://github.com/Blockether/clj-fff"]
    [:connection "scm:git:https://github.com/Blockether/clj-fff.git"]
    [:developerConnection "scm:git:ssh://git@github.com/Blockether/clj-fff.git"]]])

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data (pom-data "Clojure binding to fff — fast file and content search — via JDK FFM.")})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/copy-file {:src "resources/VERSION" :target (str class-dir "/VERSION")})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Built:" jar-file "version:" version))

(defn- native-lib [platform]
  (symbol "com.blockether" (str "fff-native-" platform)))

(defn native-jar [{:keys [platform]}]
  (let [platform (some-> platform name)]
    (when-not (native-platforms platform)
      (throw (ex-info (str "Unknown native platform: " platform) {:platform platform :known native-platforms})))
    (let [fname (native-libs platform)
          src (format "resources/prebuilds/%s/%s" platform fname)
          lib* (native-lib platform)
          jar* (format "target/%s.jar" (name lib*))]
      (b/delete {:path native-class-dir})
      (b/delete {:path jar*})
      (when-not (.exists (io/file src))
        (throw (ex-info (str "Native library not found: " src) {:platform platform :path src})))
      (b/write-pom {:class-dir native-class-dir
                    :lib lib*
                    :version version
                    :basis @basis
                    :src-dirs []
                    :pom-data (pom-data (format "Native fff-c library for %s." platform))})
      (b/copy-file {:src src :target (format "%s/prebuilds/%s/%s" native-class-dir platform fname)})
      (b/jar {:class-dir native-class-dir :jar-file jar*})
      (println "Built:" jar* "version:" version)
      jar*)))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote :artifact jar-file :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

(defn deploy-native [{:keys [platform]}]
  (let [platform (some-> platform name)
        jar* (native-jar {:platform platform})
        lib* (native-lib platform)]
    (dd/deploy {:installer :remote :artifact jar* :pom-file (b/pom-path {:lib lib* :class-dir native-class-dir})})))

(defn install [_]
  (jar nil)
  (dd/deploy {:installer :local :artifact jar-file :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

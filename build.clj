(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [clojure.edn :as edn]))

(def project (get-in (edn/read-string (slurp "deps.edn")) [:aliases :build :build/project]))

(def lib (:lib project))
(def main (:main project))
(def version (:version project))
(def license (:license project))

(def src-dirs ["src"])
(def resource-dirs ["resources"])
(def class-dir "target/classes")
(def jar-file (format "target/%s.jar" (name lib)))
(def uber-file (format "target/%s-standalone.jar" (name lib)))

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs src-dirs
                :resource-dirs resource-dirs
                :pom-data
                [[:licenses
                  [:license
                   [:name license]]]]})
  (b/copy-dir {:src-dirs (concat src-dirs resource-dirs)
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn uber [_]
  (b/copy-dir {:src-dirs (concat src-dirs resource-dirs)
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile [main]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main main}))

(defn deploy [_]
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

(def project 'manifold-cljs)
(def version "0.1.7-1-SNAPSHOT")

(set-env! :resource-paths #{"src" "vendor"}
          :dependencies   '[[org.clojure/clojure "1.8.0" :scope "provided"]
                            [org.clojure/clojurescript "1.9.562" :scope "provided"]
                            [adzerk/boot-cljs "2.0.0" :scope "test"
                             :exclusions [org.clojure/clojurescript]]
                            [adzerk/boot-test "1.1.2" :scope "test"]
                            [crisptrutski/boot-cljs-test "0.3.0" :scope "test"]
                            [adzerk/bootlaces "0.1.13" :scope "test"]

                            [adzerk/boot-reload "0.5.1" :scope "test"]
                            [pandeiro/boot-http "0.7.3" :scope "test"]])

(task-options!
 pom {:project     project
      :version     version
      :description "Manifold implementation in Clojurescript"
      :url         "https://github.com/dm3/manifold-cljs"
      :scm         {:url "https://github.com/dm3/manifold-cljs"}
      :license     {"MIT License" "https://opensource.org/licenses/MIT"}})

(require '[adzerk.boot-cljs :refer [cljs]]
         '[adzerk.bootlaces :as l :refer [push-release]]
         '[crisptrutski.boot-cljs-test :refer [test-cljs]])

(l/bootlaces! version :dont-modify-paths? true)

(deftask build-jar []
  (comp (pom) (jar) (install)))

(deftask release []
  (comp (build-jar) (push-release)))

(defn dev! []
  (task-options! cljs {:optimizations :none, :source-map true}))

(deftask dev []
  (dev!)
  (comp (watch)
        (cljs)))

(deftask test [j js-env VAL kw "JS environment (node/phantomjs/karma/...)"]
  (merge-env! :resource-paths #{"test"})
  (dev!)
  (test-cljs :js-env (or js-env :phantom)))

(deftask autotest [j js-env VAL kw "JS environment (node/phantomjs/karma/...)"]
  (merge-env! :resource-paths #{"test"})
  (dev!)
  (comp (watch)
        (test-cljs :js-env (or js-env :phantom))))

(deftask repl-dev []
  (merge-env! :resource-paths #{"test"})
  (dev!)
  (repl))

;; examples
(require '[pandeiro.boot-http :as http]
         '[adzerk.boot-reload :refer [reload]])

(deftask examples []
  (dev!)
  (set-env! :resource-paths #{"examples/src"})
  (merge-env! :dependencies `[[~project ~version]
                              [org.clojure/core.async "0.2.395"]])
  (comp
    (http/serve :httpkit true, :port 3000)
    (watch)
    (reload)
    (cljs)))

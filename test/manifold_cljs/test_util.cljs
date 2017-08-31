(ns manifold-cljs.test-util
  (:require [manifold-cljs.deferred :as d])
  (:require-macros [manifold-cljs.test-util]))

(defn unrealized? [d]
  (not (d/realized? d)))

(defn later* [f]
  (js/setTimeout f 0))

(defn capture-success [d]
  (let [a (atom nil)]
    (d/on-realized d
      #(do (reset! a %) true)
      #(println "Expected success, got error:" % "!"))
    a))

(defn capture-error
  ([result]
    (capture-error result true))
  ([result expected-return-value]
    (let [a (atom nil)]
      (d/on-realized result
        #(println "Expected error, got success:" % "!")
        #(do (reset! a %) expected-return-value))
      a)))

(defn no-success? [d]
  (= ::none (d/success-value d ::none)))

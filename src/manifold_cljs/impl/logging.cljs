(ns manifold-cljs.impl.logging)

;; TODO: pretty console logging?

(defn error
  ([msg] (println "ERROR:" msg))
  ([err msg] (println "ERROR:" err msg)))

(defn warn
  ([msg] (println "WARN:" msg)))

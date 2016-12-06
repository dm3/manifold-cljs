(ns manifold-cljs.impl.queue
  (:refer-clojure :exclude [empty? pop]))

(defn queue []
  (array))

(defn offer [q e]
  (.push q e))

(defn poll [q]
  (.shift q))

(defn pop [q]
  (.pop q))

(defn size [q]
  (count q))

(defn empty? [q]
  (zero? (count q)))

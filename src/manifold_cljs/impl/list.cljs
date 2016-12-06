(ns manifold-cljs.impl.list
  (:refer-clojure :exclude [remove list]))

(defn list []
  (array))

(defn remove [l item]
  (let [s (.-length l)]
    (loop [idx 0]
      (when (< idx s)
        (if (identical? item (aget l idx))
          (.splice l idx 1))
        (recur (inc idx))))))

(defn size [l]
  (.-length l))

(defn add [l item]
  (.push l item))

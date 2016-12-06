(ns manifold-test.daisy
  (:require [cljs.core.async :refer [chan <! >!]]
            [manifold-cljs.stream :as s]
            [manifold-cljs.deferred :as d])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn run-async []
  (let [f #(go (>! %1 (inc (<! %2))))
        leftmost (chan)
        rightmost (loop [n 100000 left leftmost]
                    (if-not (pos? n)
                      left
                      (let [right (chan)]
                        (f left right)
                        (recur (dec n) right))))]
    (go (time (do
                (>! rightmost 1)
                (.log js/console (<! leftmost)))))))

(defn run-manifold []
  (let [leftmost (s/stream)
        rightmost (loop [n 1000 left leftmost]
                    (if-not (pos? n)
                      left
                      (let [right (s/stream 0 (map inc))]
                        (s/connect right left)
                        (recur (dec n) right))))]
    (d/time
      (-> (s/put! rightmost 1)
          (d/chain
            (fn [_] (s/take! leftmost))
            (fn [result]
              (.log js/console result)))))))

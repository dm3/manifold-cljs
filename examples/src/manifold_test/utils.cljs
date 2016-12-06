(ns manifold-test.utils
  (:require [manifold-cljs.stream :as s]
            [manifold-cljs.deferred :as d]
            [manifold-cljs.time :as t]))

(defn event-stream [el event-type buffer-size]
  (let [s (s/stream buffer-size)
        skip? (atom false)
        cb (fn [e]
             (when-not @skip?
               (-> (s/put! s e)
                   (d/chain (fn [_] (reset! skip? false))))))]
    (.addEventListener el event-type cb)
    (s/on-closed s #(.removeEventListener el event-type cb))
    s))

(defn debounce [src period-ms]
  (let [dst (s/stream)]

    (d/loop [state ::ready, wait-ms 0]
      (let [start (t/current-millis)]
        (-> (s/take! src ::done)
            (d/chain
              (fn [v]
                (if (= ::done v)
                  (do (s/close! dst) false)
                  (if (= ::ready state)
                    (s/put! dst v)
                    true)))
              (fn [result]
                (if result
                  (let [passed (- (t/current-millis) start)
                        [next-state next-wait]
                        (if (= ::ready state)
                          [::waiting period-ms]
                          (if (>= passed wait-ms)
                            [::ready 0]))]
                    (d/recur next-state next-wait))))))))

    (s/source-only dst)))

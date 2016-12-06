(ns manifold-test.robpike
  (:require [cljs.core.async :as async :refer [<! >! chan close! timeout]]
            [manifold-cljs.stream :as s]
            [manifold-cljs.deferred :as d])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))

(defn run-async []
  (let [fake-search (fn [kind]
                      (fn [c query]
                        (go
                          (<! (timeout (rand-int 100)))
                          (>! c [kind query]))))
        web1 (fake-search :web1)
        web2 (fake-search :web2)
        image1 (fake-search :image1)
        image2 (fake-search :image2)
        video1 (fake-search :video1)
        video2 (fake-search :video2)

        fastest (fn [query & replicas]
                  (let [c (chan)]
                    (doseq [replica replicas]
                      (replica c query))
                    c))

        google (fn [query]
                 (let [c (chan)
                       t (timeout 80)]
                   (go (>! c (<! (fastest query web1 web2))))
                   (go (>! c (<! (fastest query image1 image2))))
                   (go (>! c (<! (fastest query video1 video2))))
                   (go (loop [i 0 ret []]
                         (if (= i 3)
                           ret
                           (recur (inc i) (conj ret (alt! [c t] ([v] v)))))))))]

    (go (println (<! (google "clojure"))))))

(defn run-manifold []
  (let [fake-search (fn [kind]
                      (fn [query]
                        (-> (d/deferred)
                            (d/timeout! (rand-int 100) [kind query]))))

        web1 (fake-search :web1)
        web2 (fake-search :web2)
        image1 (fake-search :image1)
        image2 (fake-search :image2)
        video1 (fake-search :video1)
        video2 (fake-search :video2)

        fastest (fn [query & replicas]
                  (->> (map #(% query) replicas)
                       (apply d/alt)))

        google (fn [query]
                 (let [timeout #(d/timeout! % 80 nil)
                       web (fastest query web1 web2)
                       image (fastest query image1 image2)
                       video (fastest query video1 video2)]
                   (->> (map timeout [web image video])
                        (apply d/zip))))]

    (d/chain (google "clojure") println)))

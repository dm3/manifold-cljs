(ns manifold-test.main
  (:require [manifold-cljs.deferred :as d]
            [manifold-test.daisy :as daisy]
            [manifold-test.robpike :as robpike]
            [manifold-test.utils :as utils]

            [manifold-cljs.stream :as s]
            [manifold-cljs.deferred :as d]
            [manifold-cljs.time :as t]))

(enable-console-print!)

(defn robpike []
  (println "[MANIFOLD] Running robpike...")
  (robpike/run-manifold)
  (println "[CORE.ASY] Running robpike...")
  (robpike/run-async))

(defn daisy []
  (println "[MANIFOLD] Running daisy...")
  (daisy/run-manifold)
  (println "[CORE.ASY] Running daisy...")
  (daisy/run-async))

(defn clicks [stop-in-ms debounce-ms]
  (println "Starting click stream...")
  (let [click-stream (-> (utils/event-stream js/window "click" 0)
                         (utils/debounce debounce-ms))]
    (t/in stop-in-ms #(do (println "Stopping click stream...")
                          (s/close! click-stream)))
    (s/consume #(println "Clicked: " %) click-stream)
    (s/on-drained click-stream
      #(println "Click stream stopped!"))
    click-stream))

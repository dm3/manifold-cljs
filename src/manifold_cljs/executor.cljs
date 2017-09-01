(ns manifold-cljs.executor
  (:require [goog.async.nextTick])
  (:require-macros [manifold-cljs.executor]))

(defprotocol Executor
  (execute [_ f]))

(def ^:private sync-executor-instance
  (reify Executor
    (execute [_ f]
      (f))))

(defn sync-executor [] sync-executor-instance)

(defn timeout-executor [timeout-ms]
  (reify Executor
    (execute [_ f]
      (js/setTimeout f timeout-ms))))

(defn- node? [] (= cljs.core/*target* "nodejs"))

(def ^:private next-tick-executor-instance
  (if (node?)
    (reify Executor
      (execute [_ f]
        (.nextTick js/process f)))
    (reify Executor
      (execute [_ f]
        (goog.async.nextTick f)))))

(declare ^:private process-batched)

(defn batched-executor [underlying-executor batch-size]
  ;; straightforward adaptation of core.async default dispatcher
  (let [buffer (array) , running? (volatile! false), queued? (volatile! false)]
    (letfn [(enqueue []
              (when-not (and @queued? @running?)
                (vreset! queued? true)
                (execute underlying-executor process)))
            (process []
              (vreset! running? true)
              (vreset! queued? false)
              (loop [i 0]
                (when-let [f (.pop buffer)]
                  (f)
                  (when (< i batch-size)
                    (recur (inc i)))))
              (vreset! running? false)
              (when (> (.-length buffer) 0)
                (enqueue)))]
      (reify Executor
        (execute [_ f]
          (.unshift buffer f)
          (enqueue))))))

(defn execute-on-next-tick [f]
  (execute next-tick-executor-instance f))

(defn next-tick-executor [] next-tick-executor-instance)

;; different to Clj - use batched next-tick by default
;; there's no default executor in Clj - field is nil
(def default-executor
  ;; buffer size same as core.async
  (batched-executor next-tick-executor-instance 1024))

;; for with-executor binding
(def ^:private ^:mutable current-executor
  default-executor)

(defn executor []
  current-executor)

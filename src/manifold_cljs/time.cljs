(ns manifold-cljs.time
  (:require [manifold-cljs.deferred.default :as d]
            [manifold-cljs.executor :as ex]
            [manifold-cljs.deferred.core :as core]))

;; Differences from Clojure impl:
;; - no Clock abstraction
;; - no `format-duration`
;; - no `add`

;; cljs specific
(defn current-millis []
  (.getTime (js/Date.)))

;; same as clj
(defn nanoseconds
  "Converts nanoseconds -> milliseconds"
  [n]
  (/ n 1e6))

;; same as clj
(defn microseconds
  "Converts microseconds -> milliseconds"
  [n]
  (/ n 1e3))

;; same as clj
(defn milliseconds
  "Converts milliseconds -> milliseconds"
  [n]
  n)

;; same as clj
(defn seconds
  "Converts seconds -> milliseconds"
  [n]
  (* n 1e3))

;; same as clj
(defn minutes
  "Converts minutes -> milliseconds"
  [n]
  (* n 6e4))

;; same as clj
(defn hours
  "Converts hours -> milliseconds"
  [n]
  (* n 36e5))

;; same as clj
(defn days
  "Converts days -> milliseconds"
  [n]
  (* n 864e5))

;; same as clj
(defn hz
  "Converts frequency -> period in milliseconds"
  [n]
  (/ 1e3 n))

;; - Throwable -> js/Error
(defn in
  "Schedules no-arg function `f` to be invoked in `interval` milliseconds.  Returns a deferred
     representing the returned value of the function."
    [^double interval f]
    (let [d (d/deferred (ex/executor))
          f (fn []
              (try
                (core/success d (f))
                (catch js/Error e
                  (core/error d e))))]
      (js/setTimeout f interval)
      d))

(defn every
  "Schedules no-arg function `f` to be invoked every `period` milliseconds, after `initial-delay`
  milliseconds, which defaults to `0`.  Returns a zero-argument function which, when invoked,
  cancels the repeated invocation.

  If the invocation of `f` ever throws an exception, repeated invocation is automatically
  cancelled."
  ([period-ms f]
   (every period-ms 0 f))
  ([period-ms initial-delay-ms f]
   (let [continue? (atom true)
         stop-f #(reset! continue? false)]
     (js/setTimeout
       (fn this []
         (when @continue?
           (f)
           (js/setTimeout this period-ms)))
       initial-delay-ms)
     stop-f)))

(defn at
  "Schedules no-arg function  `f` to be invoked at `timestamp`, which is the milliseconds
   since the epoch.  Returns a deferred representing the returned value of the function."
  [timestamp f]
  (in (max 0 (- timestamp (current-millis))) f))

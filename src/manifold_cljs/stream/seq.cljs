(ns manifold-cljs.stream.seq
  (:require [manifold-cljs.impl.logging :as log]
            [manifold-cljs.deferred :as d]
            [manifold-cljs.utils :as utils]
            [manifold-cljs.stream.core :as s]
            [manifold-cljs.stream.graph :as g]
            [manifold-cljs.time :as time]))

;; - AtomicReference -> atom
;; - isSynchronous - always false
;; - close - there is no java.io.Closeable analog in Clojurescript
;; - removed Pending checks
;; - removed blocking? branch
(s/def-source SeqSource
  [s-ref]

  :stream
  [(isSynchronous [_] false)

   (close [_])

   (description [this]
     (merge
       {:type "seq"
        :drained? (s/drained? this)}
       (let [s @s-ref]
         (when (counted? s)
           {:count (count s)}))))]

  :source
  [(take [this default-val blocking?]
     (let [s @s-ref
           v (try
               (if (empty? s)
                 (do
                   (s/markDrained this)
                   default-val)
                 (let [x (first s)]
                   (swap! s-ref rest)
                   x))
               (catch js/Error e
                 (log/error e "error in seq stream")
                 (s/markDrained this)
                 default-val))]
       (d/success-deferred v)))

   (take [this default-val blocking? timeout timeout-val]
     (if (nil? timeout)
       (s/take this blocking? default-val)
       (-> (s/take this false default-val)
           (d/timeout! timeout timeout-val))))])

;; ISeq and ISeqable are protocols in Cljs, so no way to extend them to ISourceable
(defn seq-source? [s]
  (or (seq? s) (seqable? s)))

(defn to-source [s]
  (let [s' (cond (seq? s) s
                 (seqable? s) (seq s)
                 :else (throw (ex-info (str "Can't create a SeqSource from " (type s))
                                       {:s s})))]
    (->SeqSource (atom s'))))

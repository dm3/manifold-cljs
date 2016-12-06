(ns manifold-cljs.stream.core
  (:refer-clojure :exclude [take])
  (:require-macros [manifold-cljs.stream.core]))

(defprotocol Sinkable
  (to-sink [_] "Provides a conversion mechanism to Manifold sinks."))

(defprotocol Sourceable
  (to-source [_] "Provides a conversion mechanism to Manifold source."))

;; same as clj except
;; - definterface -> defprotocol
(defprotocol IEventStream
  (description [_])
  (isSynchronous [_])
  (downstream [_])
  (weakHandle [_ reference-queue])
  (close [_]))

;; same as clj except
;; - definterface -> defprotocol
(defprotocol IEventSink
  (put [_ x blocking?]
       [_ x blocking? timeout timeout-val])
  (markClosed [_])
  (isClosed [_])
  (onClosed [_ callback]))

;; - definterface -> defprotocol
(defprotocol IEventSource
  (take [_ default-val blocking?]
        [_ default-val blocking? timeout timeout-val])
  (markDrained [_])
  (isDrained [_])
  (onDrained [_ callback])
  (connector [_ sink]))

;; - definline -> defn
(defn close!
  "Closes an event sink, so that it can't accept any more messages."
  [sink] (close sink))

;; - definline -> defn
(defn closed?
  "Returns true if the event sink is closed."
  [sink] (isClosed sink))

;; - definline -> defn
(defn drained?
  "Returns true if the event source is drained."
  [source] (isDrained source))

;; - definline -> defn
(defn weak-handle
  "Returns a weak reference that can be used to construct topologies of streams."
  [x] (weakHandle x nil))

;; - definline -> defn
;; TODO: change docstring? Will this always be false in Cljs?
(defn synchronous?
  "Returns true if the underlying abstraction behaves synchronously, using thread blocking
   to provide backpressure."
  [x] (isSynchronous x))

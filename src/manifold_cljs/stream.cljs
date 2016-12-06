(ns manifold-cljs.stream
  (:refer-clojure :exclude [map filter repeatedly reductions reduce mapcat concat])
  (:require [clojure.core :as clj]
            [manifold-cljs.stream.core :as core]
            [manifold-cljs.stream.default :as default]
            [manifold-cljs.stream.graph :as g]
            [manifold-cljs.time :as time]
            [manifold-cljs.utils :as u]
            [manifold-cljs.deferred :as d]
            [manifold-cljs.executor :as e]
            [manifold-cljs.impl.logging :as log]

            [manifold-cljs.stream.seq :as sq]))

;; - IEventSink is a protocol
;; - remove utils/fast-satisfies
(defn sinkable? [x]
  (or
    (satisfies? core/IEventSink x)
    (satisfies? core/Sinkable x)))

;; - IEventSource is a protocol
;; - remove utils/fast-satisfies
(defn sourceable? [x]
  (or
    (satisfies? core/IEventSource x)
    (satisfies? core/Sourceable x)))

;; - identical? -> keyword-identical?
;; - IEventSink is a protocol
;; - IllegalArgumentException -> js/Error
(defn ->sink
  "Converts, if possible, the object to a Manifold sink, or `default-val` if it cannot.  If no
   default value is given, an exception is thrown."
  ([x]
    (let [x' (->sink x ::none)]
      (if (keyword-identical? ::none x')
        (throw
          (js/Error.
            (str "cannot convert " (type x) " to sink")))
        x')))
  ([x default-val]
    (cond
      (satisfies? core/IEventSink x) x
      (sinkable? x) (core/to-sink x)
      :else default-val)))

;; - identical? -> keyword-identical?
;; - IEventSource is a protocol
;; - IllegalArgumentException -> js/Error
(defn ->source
  "Converts, if possible, the object to a Manifold source, or `default-val` if it cannot.  If no
   default value is given, an exception is thrown."
  ([x]
    (let [x' (->source x ::none)]
      (if (keyword-identical? ::none x')
        (throw
          (js/Error.
            (str "cannot convert " (type x) " to source")))
        x')))
  ([x default-val]
    (cond
      (satisfies? core/IEventSource x) x
      (sourceable? x) (core/to-source x)
      (sq/seq-source? x) (sq/to-source x)
      :else default-val)))

;; - interface method calls -> protocols
;; - remove type annotations
(deftype SinkProxy [sink]
  core/IEventStream
  (description [_]
    (core/description sink))
  (isSynchronous [_]
    (core/isSynchronous sink))
  (downstream [_]
    (core/downstream sink))
  (close [_]
    (core/close sink))
  (weakHandle [_ ref-queue]
    (core/weakHandle sink ref-queue))
  core/IEventSink
  (put [_ x blocking?]
    (core/put sink x blocking?))
  (put [_ x blocking? timeout timeout-val]
    (core/put sink x blocking? timeout timeout-val))
  (isClosed [_]
    (core/isClosed sink))
  (onClosed [_ callback]
    (core/onClosed sink callback)))

(declare connect)

;; - interface method calls -> protocols
;; - remove type annotations
(deftype SourceProxy [source]
  core/IEventStream
  (description [_]
    (core/description source))
  (isSynchronous [_]
    (core/isSynchronous source))
  (downstream [_]
    (core/downstream source))
  (close [_]
    (core/close source))
  (weakHandle [_ ref-queue]
    (core/weakHandle source ref-queue))
  core/IEventSource
  (take [_ default-val blocking?]
    (core/take source default-val blocking?))
  (take [_ default-val blocking? timeout timeout-val]
    (core/take source default-val blocking? timeout timeout-val))
  (isDrained [_]
    (core/isDrained source))
  (onDrained [_ callback]
    (core/onDrained source callback))
  (connector [_ sink]
    (fn [_ sink options]
      (connect source sink options))))

;; same
(defn source-only
  "Returns a view of the stream which is only a source."
  [s]
  (SourceProxy. s))

;; same
(defn sink-only
  "Returns a view of the stream which is only a sink."
  [s]
  (SinkProxy. s))

;; - definline -> defn
;; - interface -> protocol
(defn stream?
  "Returns true if the object is a Manifold stream."
  [x] (satisfies? core/IEventStream x))

;; - definline -> defn
;; - interface -> protocol
(defn source?
  "Returns true if the object is a Manifold source."
  [x] (satisfies? core/IEventSource x))

;; - definline -> defn
;; - interface -> protocol
(defn sink?
  "Returns true if the object is a Manifold sink."
  [x] (satisfies? core/IEventSink x))

;; - definline -> defn
;; - interface -> protocol
(defn description
  "Returns a description of the stream."
  [x] (core/description x))

;; - definline -> defn
;; - interface -> protocol
(defn downstream
  "Returns all sinks downstream of the given source as a sequence of 2-tuples, with the
   first element containing the connection's description, and the second element containing
   the sink."
  [x] (core/downstream x))

;; - definline -> defn
;; - interface -> protocol
(defn weak-handle
  "Returns a weak reference that can be used to construct topologies of streams."
  [x] (core/weakHandle x nil))

;; - definline -> defn
;; - interface -> protocol
(defn synchronous?
  "Returns true if the underlying abstraction behaves synchronously, using thread blocking
   to provide backpressure."
  [x] (core/isSynchronous x))

;; - definline -> defn
;; - interface -> protocol
(defn close!
  "Closes a source or sink, so that it can't emit or accept any more messages."
  [sink] (core/close sink))

;; - definline -> defn
;; - interface -> protocol
(defn closed?
  "Returns true if the event sink is closed."
  [sink] (core/isClosed sink))

;; - definline -> defn
;; - interface -> protocol
(defn on-closed
  "Registers a no-arg callback which is invoked when the sink is closed."
  [sink callback] (core/onClosed sink callback))

;; - definline -> defn
;; - interface -> protocol
(defn drained?
  "Returns true if the event source is drained."
  [source] (core/isDrained source))

;; - definline -> defn
;; - interface -> protocol
(defn on-drained
  "Registers a no-arg callback which is invoked when the source is drained."
  [source callback] (core/onDrained source callback))

;; - remove inline
(defn put!
  "Puts a value into a sink, returning a deferred that yields `true` if it succeeds,
   and `false` if it fails.  Guaranteed to be non-blocking."
  ([^IEventSink sink x]
    (core/put sink x false)))

;; same
(defn put-all!
  "Puts all values into the sink, returning a deferred that yields `true` if all puts
   are successful, or `false` otherwise.  If the sink provides backpressure, will
   pause. Guaranteed to be non-blocking."
  [^IEventSink sink msgs]
  (d/loop [msgs msgs]
    (if (empty? msgs)
      true
      (d/chain' (put! sink (first msgs))
        (fn [result]
          (if result
            (d/recur (rest msgs))
            false))))))

;; - remove inline
(defn try-put!
  "Puts a value into a stream if the put can successfully be completed in `timeout`
   milliseconds.  Returns a promiise that yields `true` if it succeeds, and `false`
   if it fails or times out.  Guaranteed to be non-blocking.

   A special `timeout-val` may be specified, if it is important to differentiate
   between failure due to timeout and other failures."
  ([^IEventSink sink x ^double timeout]
    (core/put sink x false timeout false))
  ([^IEventSink sink x ^double timeout timeout-val]
    (core/put sink x false timeout timeout-val)))

;; - remove inline
(defn take!
  "Takes a value from a stream, returning a deferred that yields the value when it
   is available, or `nil` if the take fails.  Guaranteed to be non-blocking.

   A special `default-val` may be specified, if it is important to differentiate
   between actual `nil` values and failures."
  ([^IEventSource source]
    (core/take source nil false))
  ([^IEventSource source default-val]
    (core/take source default-val false)))

;; - remove inline
(defn try-take!
  "Takes a value from a stream, returning a deferred that yields the value if it is
   available within `timeout` milliseconds, or `nil` if it fails or times out.
   Guaranteed to be non-blocking.

   Special `timeout-val` and `default-val` values may be specified, if it is
   important to differentiate between actual `nil` values and failures."
  ([^IEventSource source ^double timeout]
    (core/take source nil false timeout nil))
  ([^IEventSource source default-val ^double timeout timeout-val]
    (core/take source default-val false timeout timeout-val)))

(defn connect
  "Connects a source to a sink, propagating all messages from the former into the latter.

   Optionally takes a map of parameters:

   |:---|:---
   | `upstream?` | if closing the sink should always close the source, even if there are other sinks downstream of the source.  Defaults to `false`.  Note that if the sink is the only thing downstream of the source, the source will always be closed, unless it is permanent.
   | `downstream?` | if closing the source will close the sink.  Defaults to `true`.
   | `timeout` | if defined, the maximum time, in milliseconds, that will be spent trying to put a message into the sink before closing it.  Useful when there are multiple sinks downstream of a source, and you want to avoid a single backed up sink from blocking all the others.
   | `description` | describes the connection, useful for traversing the stream topology via `downstream`."
  {:arglists
   '[[source sink]
     [source
      sink
      {:keys [upstream?
              downstream?
              timeout
              description]
       :or {upstream? false
            downstream? true}}]]}
  ([source sink]
    (connect source sink nil))
  ([source sink options]
    (let [source (->source source)
          sink (->sink sink)
          connector (core/connector source sink)]
      (if connector
        (connector source sink options)
        (g/connect source sink options))
      nil)))

;;;

;; same
(defn stream
  "Returns a Manifold stream with a configurable `buffer-size`.  If a capacity is specified,
   `put!` will yield `true` when the message is in the buffer.  Otherwise it will only yield
   `true` once it has been consumed.

   `xform` is an optional transducer, which will transform all messages that are enqueued
   via `put!` before they are dequeued via `take!`.

   `executor`, if defined, specifies which java.util.concurrent.Executor will be used to
   handle the deferreds returned by `put!` and `take!`."
  ([]
    (default/stream))
  ([buffer-size]
    (default/stream buffer-size))
  ([buffer-size xform]
    (default/stream buffer-size xform))
  ([buffer-size xform executor]
    (default/stream buffer-size xform executor)))

;; same
(defn stream*
  "An alternate way to build a stream, via a map of parameters.

   |:---|:---
   | `permanent?` | if `true`, the channel cannot be closed
   | `buffer-size` | the number of messages that can accumulate in the channel before backpressure is applied
   | `description` | the description of the channel, which is a single arg function that takes the base properties and returns an enriched map.
   | `executor` | the `manifold-cljs.executor/Executor` that will execute all callbacks registered on the deferreds returns by `put!` and `take!`
   | `xform` | a transducer which will transform all messages that are enqueued via `put!` before they are dequeued via `take!`."
  {:arglists '[[{:keys [permanent? buffer-size description executor xform]}]]}
  [options]
  (default/stream* options))

;;;

;; - removed type annotations, lock, meta
;; - interface method calls -> protocol calls
(deftype SplicedStream [sink source]
  core/IEventStream
  (isSynchronous [_]
    (or (synchronous? sink)
      (synchronous? source)))
  (description [_]
    {:type "splice"
     :sink (core/description sink)
     :source (core/description source)})
  (downstream [_]
    (core/downstream source))
  (close [_]
    (core/close source)
    (core/close sink))
  (weakHandle [_ ref-queue]
    (core/weakHandle source ref-queue))

  core/IEventSink
  (put [_ x blocking?]
    (core/put sink x blocking?))
  (put [_ x blocking? timeout timeout-val]
    (core/put sink x blocking? timeout timeout-val))
  (isClosed [_]
    (core/isClosed sink))
  (onClosed [_ callback]
    (core/onClosed sink callback))

  core/IEventSource
  (take [_ default-val blocking?]
    (core/take source default-val blocking?))
  (take [_ default-val blocking? timeout timeout-val]
    (core/take source default-val blocking? timeout timeout-val))
  (isDrained [_]
    (core/isDrained source))
  (onDrained [_ callback]
    (core/onDrained source callback))
  (connector [_ sink]
    (core/connector source sink)))

(defn splice
  "Splices together two halves of a stream, such that all messages enqueued via `put!` go
   into `sink`, and all messages dequeued via `take!` come from `source`."
  [sink source]
  (SplicedStream. (->sink sink) (->source source)))

;;;

;; - protocols
;; - Throwable, IAE -> js/Error
(deftype Callback
    [f
     close-callback
     downstream
     constant-response]
  core/IEventStream
  (isSynchronous [_]
    false)
  (close [_]
    (when close-callback
      (e/execute-on-next-tick close-callback)))
  (weakHandle [_ ref-queue]
    (if downstream
      (core/weakHandle downstream ref-queue)
      (throw (js/Error. "No downstream!"))))
  (description [_]
    {:type "callback"})
  (downstream [_]
    (when downstream
      [[(description downstream) downstream]]))
  core/IEventSink
  (put [this x _]
    (try
      (let [rsp (f x)]
        (if (nil? constant-response)
          rsp
          constant-response))
      (catch js/Error e
        (log/error e "error in stream handler")
        (core/close this)
        (d/success-deferred false))))
  (put [this x default-val _ _]
    (core/put this x default-val))
  (isClosed [_]
    (if downstream
      (core/isClosed downstream)
      false))
  (onClosed [_ callback]
    (when downstream
      (core/onClosed downstream callback))))

;; same
(let [result (d/success-deferred true)]
  (defn consume
    "Feeds all messages from `source` into `callback`.

     Messages will be processed as quickly as the callback can be executed. Returns
     a deferred which yields `true` when `source` is exhausted."
    [callback source]
    (let [complete (d/deferred)]
      (connect source (Callback. callback #(d/success! complete true) nil result) nil)
      complete)))

;; same
(defn consume-async
  "Feeds all messages from `source` into `callback`, which must return a deferred yielding
   `true` or `false`.  If the returned value yields `false`, the consumption will be cancelled.

   Messages will be processed only as quickly as the deferred values are realized. Returns a
   deferred which yields `true` when `source` is exhausted or `callback` yields `false`."
  [callback source]
  (let [complete (d/deferred)]
    (connect source (Callback. callback #(d/success! complete true) nil nil) nil)
    complete))

;; same
(defn connect-via
  "Feeds all messages from `src` into `callback`, with the understanding that they will
   eventually be propagated into `dst` in some form.  The return value of `callback`
   should be a deferred yielding either `true` or `false`. When `false`,  the downstream
   sink is assumed to be closed, and the connection is severed.

   Returns a deferred which yields `true` when `src` is exhausted or `callback` yields `false`."
  ([src callback dst]
   (connect-via src callback dst nil))
  ([src callback dst options]
    (let [dst            (->sink dst)
          complete       (d/deferred)
          close-callback #(do
                            (close! dst)
                            (d/success! complete true))]
      (connect
        src
        (Callback. callback close-callback dst nil)
        options)
      complete)))

;; same
(defn- connect-via-proxy
  ([src proxy dst]
    (connect-via-proxy src proxy dst nil))
  ([src proxy dst options]
   (let [dst            (->sink dst)
         proxy          (->sink proxy)
         complete       (d/deferred)
         close-callback #(do
                           (close! proxy)
                           (d/success! complete true))]
     (connect
       src
       (Callback. #(put! proxy %) close-callback dst nil)
       options)
     complete)))

;; same
(defn drain-into
  "Takes all messages from `src` and puts them into `dst`, and returns a deferred that
   yields `true` once `src` is drained or `dst` is closed.  If `src` is closed or drained,
   `dst` will not be closed."
  [src dst]
  (let [dst      (->sink dst)
        complete (d/deferred)]
    (connect
      src
      (Callback. #(put! dst %) #(d/success! complete true) dst nil)
      {:description "drain-into"})
    complete))

;;;

;; - no stream->seq as we can't block

;; - Throwable -> js/Error
;; - System/currentTimeMillis -> time/current-millis
(defn- periodically-
  [stream period initial-delay f]
  (let [cancel (atom nil)]
    (reset! cancel
      (time/every period initial-delay
        (fn []
          (try
            (let [d (if (closed? stream)
                      (d/success-deferred false)
                      (put! stream (f)))]
              (if (realized? d)
                (when-not @d
                  (do
                    (@cancel)
                    (close! stream)))
                (do
                  (@cancel)
                  (d/chain' d
                    (fn [x]
                      (if-not x
                        (close! stream)
                        (periodically- stream period (- period (rem (time/current-millis) period)) f)))))))
            (catch js/Error e
              (@cancel)
              (close! stream)
              (log/error e "error in 'periodically' callback"))))))))

;; - System/currentTimeMillis -> time/current-millis
(defn periodically
  "Creates a stream which emits the result of invoking `(f)` every `period` milliseconds."
  ([period initial-delay f]
    (let [s (stream 1)]
      (periodically- s period initial-delay f)
      (source-only s)))
  ([period f]
    (periodically period (- period (rem (time/current-millis) period)) f)))

(declare zip)

;; - remove type annotation
(defn transform
  "Takes a transducer `xform` and returns a source which applies it to source `s`. A buffer-size
   may optionally be defined for the output source."
  ([xform s]
    (transform xform 0 s))
  ([xform buffer-size s]
    (let [s' (stream buffer-size xform)]
      (connect s s' {:description {:op "transducer"}})
      (source-only s'))))

;; same
(defn map
  "Equivalent to Clojure's `map`, but for streams instead of sequences."
  ([f s]
    (let [s' (stream)]
      (connect-via s
        (fn [msg]
          (put! s' (f msg)))
        s'
        {:description {:op "map"}})
      (source-only s')))
  ([f s & rest]
    (map #(apply f %)
      (apply zip s rest))))

;; same
(defn realize-each
  "Takes a stream of potentially deferred values, and returns a stream of realized values."
  [s]
  (let [s' (stream)]
    (connect-via s
      (fn [msg]
        (-> msg
          (d/chain' #(put! s' %))
          (d/catch' (fn [e]
                      (log/error e "deferred realized as error, closing stream")
                      (close! s')
                      false))))
      s'
      {:description {:op "realize-each"}})
    (source-only s')))

;; same
(let [some-drained? (partial some #{::drained})]
  (defn zip
    "Takes n-many streams, and returns a single stream which will emit n-tuples representing
     a message from each stream."
    ([a]
      (map vector a))
    ([a & rest]
      (let [srcs (list* a rest)
            intermediates (clj/repeatedly (count srcs) stream)
            dst (stream)]

        (doseq [[a b] (clj/map list srcs intermediates)]
          (connect-via a #(put! b %) b {:description {:op "zip"}}))

        (d/loop []
          (d/chain'
            (->> intermediates
              (clj/map #(take! % ::drained))
              (apply d/zip))
            (fn [msgs]
              (if (some-drained? msgs)
                (do (close! dst) false)
                (put! dst msgs)))
            (fn [result]
              (when result
                (d/recur)))))

        (source-only dst)))))

;; same
(defn filter
  "Equivalent to Clojure's `filter`, but for streams instead of sequences."
  [pred s]
  (let [s' (stream)]
    (connect-via s
      (fn [msg]
        (if (pred msg)
          (put! s' msg)
          (d/success-deferred true)))
      s'
      {:description {:op "filter"}})
    (source-only s')))

;; - identical? -> keyword-identical?
(defn reductions
  "Equivalent to Clojure's `reductions`, but for streams instead of sequences."
  ([f s]
    (reductions f ::none s))
  ([f initial-value s]
    (let [s' (stream)
          val (atom initial-value)]
      (d/chain' (if (keyword-identical? ::none initial-value)
                  true
                  (put! s' initial-value))
        (fn [_]
          (connect-via s
            (fn [msg]
              (if (keyword-identical? ::none @val)
                (do
                  (reset! val msg)
                  (put! s' msg))
                (-> msg
                  (d/chain'
                    (partial f @val)
                    (fn [x]
                      (reset! val x)
                      (put! s' x)))
                  (d/catch' (fn [e]
                              (log/error e "error in reductions")
                              (close! s)
                             false)))))
            s')))

      (source-only s'))))

;; identical? -> keyword-identical?
(defn reduce
  "Equivalent to Clojure's `reduce`, but returns a deferred representing the return value."
  ([f s]
    (reduce f ::none s))
  ([f initial-value s]
   (-> (if (keyword-identical? ::none initial-value)
         (take! s ::none)
         initial-value)
     (d/chain'
       (fn [initial-value]
         (if (keyword-identical? ::none initial-value)
           (f)
           (d/loop [val initial-value]
             (-> (take! s ::none)
               (d/chain' (fn [x]
                           (if (keyword-identical? ::none x)
                             val
                             (d/recur (f val x)))))))))))))

;; same
(defn mapcat
  "Equivalent to Clojure's `mapcat`, but for streams instead of sequences."
  ([f s]
    (let [s' (stream)]
      (connect-via s
        (fn [msg]
          (d/loop [s (f msg)]
            (when-not (empty? s)
              (d/chain' (put! s' (first s))
                (fn [_]
                  (d/recur (rest s)))))))
        s'
        {:description {:op "mapcat"}})
      (source-only s')))
  ([f s & rest]
    (->> (apply zip s rest)
      (mapcat #(apply f %)))))

;; identical? -> keyword-identical?
;; Throwable -> js/Error
(defn lazily-partition-by
  "Equivalent to Clojure's `partition-by`, but returns a stream of streams.  This means that
   if a sub-stream is not completely consumed, the next sub-stream will never be emitted.

   Use with caution.  If you're not totally sure you want a stream of streams, use
   `(transform (partition-by f))` instead."
  [f s]
  (let [in (stream)
        out (stream)]

    (connect-via-proxy s in out {:description {:op "lazily-partition-by"}})

    ;; TODO: how is this represented in the topology?
    (d/loop [prev ::x, s' nil]
      (d/chain' (take! in ::none)
        (fn [msg]
          (if (keyword-identical? ::none msg)
            (do
              (when s' (close! s'))
              (close! out))
            (let [curr (try
                         (f msg)
                         (catch js/Error e
                           (close! in)
                           (close! out)
                           (log/error e "error in lazily-partition-by")
                           ::error))]
              (when-not (keyword-identical? ::error curr)
                (if (= prev curr)
                  (d/chain' (put! s' msg)
                    (fn [_] (d/recur curr s')))
                  (let [s'' (stream)]
                    (when s' (close! s'))
                    (d/chain' (put! out s'')
                      (fn [_] (put! s'' msg))
                      (fn [_] (d/recur curr s'')))))))))))

    (source-only out)))

;; identical? -> keyword-identical?
(defn concat
  "Takes a stream of streams, and flattens it into a single stream."
  [s]
  (let [in (stream)
        out (stream)]

    (connect-via-proxy s in out {:description {:op "concat"}})

    (d/loop []
      (d/chain' (take! in ::none)
        (fn [s']
          (cond
            (closed? out)
            (close! s')

            (keyword-identical? ::none s')
            (do
              (close! out)
              s')

            :else
            (d/loop []
              (d/chain' (take! s' ::none)
                (fn [msg]
                  (if (keyword-identical? ::none msg)
                    msg
                    (put! out msg)))
                (fn [result]
                  (case result
                    false (do (close! s') (close! in))
                    ::none nil
                    (d/recur)))))))
        (fn [result]
          (when-not (keyword-identical? ::none result)
            (d/recur)))))

    (source-only out)))

;;;

;; - removed type hints
;; - removed Reference (meta) impls
;; - description -> description-fn
;; - buffer-size AtomicInteger -> (atom int)
;; - last-put AtomicReference -> (atom deferred)
;; - weakHandle handle -> returns this
(deftype BufferedStream
  [buf
   limit
   metric
   description-fn
   buffer-size
   last-put
   buf+]

  core/IEventStream
  (isSynchronous [_]
    false)
  (downstream [this]
    (g/downstream this))
  (close [_]
    (core/close buf))
  (description [_]
    (description-fn
      (merge
        (description buf)
        {:buffer-size @buffer-size
         :buffer-capacity limit})))
  (weakHandle [this ref-queue] this)

  core/IEventSink
  (put [_ x blocking?]
    (assert (not blocking?) "Blocking puts not supported!")
    (let [size (metric x)]
      (let [val (d/chain' (core/put buf [size x] blocking?)
                  (fn [result]
                    (if result
                      (do
                        (buf+ size)
                        @last-put)
                      false)))]
        val)))
  (put [_ x blocking? timeout timeout-val]
    (assert (not blocking?) "Blocking puts not supported!")
    ;; TODO: this doesn't really time out, because that would
    ;; require consume-side filtering of messages
    (let [size (metric x)]
      (let [val (d/chain' (core/put buf [size x] blocking? timeout ::timeout)
                  (fn [result]
                    (cond

                      (keyword-identical? result ::timeout)
                      timeout-val

                      (false? result)
                      false

                      :else
                      (do
                        (buf+ size)
                        @last-put))))]
        val)))
  (isClosed [_]
    (core/isClosed buf))
  (onClosed [_ callback]
    (core/onClosed buf callback))

  core/IEventSource
  (take [_ default-val blocking?]
    (assert (not blocking?) "Blocking takes not supported!")
    (let [val (d/chain' (core/take buf default-val blocking?)
                (fn [x]
                  (if (keyword-identical? default-val x)
                    x
                    (let [[size msg] x]
                      (buf+ (- size))
                      msg))))]
      val))
  (take [_ default-val blocking? timeout timeout-val]
    (assert (not blocking?) "Blocking takes not supported!")
    (let [val (d/chain' (core/take buf default-val blocking? timeout ::timeout)
                (fn [x]
                  (cond

                    (keyword-identical? ::timeout x)
                    timeout-val

                    (keyword-identical? default-val x)
                    x

                    :else
                    (let [[size msg] x]
                      (buf+ (- size))
                      msg))))]
      val))
  (isDrained [_]
    (core/isDrained buf))
  (onDrained [_ callback]
    (core/onDrained buf callback))
  (connector [_ sink]
    (core/connector buf sink)))

;; Integer/MAX_VALUE -> u/integer-max-value
;; AtomicLong/AtomicReference -> atom
(defn buffered-stream
  "A stream which will buffer at most `limit` data, where the size of each message
   is defined by `(metric message)`."
  ([buffer-size]
    (buffered-stream (constantly 1) buffer-size))
  ([metric limit]
    (buffered-stream metric limit identity))
  ([metric limit description]
    (let [buf (stream u/integer-max-value)
          buffer-size (atom 0)
          last-put (atom (d/success-deferred true))
          buf+ (fn [^long n]
                 (locking last-put
                   (let [buf' (swap! buffer-size + n)
                         buf  (unchecked-subtract buf' n)]
                     (cond
                       (and (<= buf' limit) (< limit buf))
                       (-> last-put deref (d/success! true))

                       (and (<= buf limit) (< limit buf'))
                       (let [last-put' @last-put]
                         (reset! last-put (d/deferred))
                         (d/success! last-put' true))))))]

      (BufferedStream.
        buf
        limit
        metric
        description
        buffer-size
        last-put
        buf+))))

(defn buffer
  "Takes a stream, and returns a stream which is a buffered view of that stream.  The buffer
   size may either be measured in messages, or if a `metric` is defined, by the sum of `metric`
   mapped over all messages currently buffered."
  ([limit s]
    (let [s' (buffered-stream limit)]
      (connect s s')
      (source-only s')))
  ([metric limit s]
    (let [s' (buffered-stream metric limit)]
      (connect s s')
      (source-only s'))))

;; System/currentTimeMillis -> time/current-millis
;; identical? -> keyword-identical?
(defn batch
  "Batches messages, either into groups of fixed size, or according to upper bounds on size and
   latency, in milliseconds.  By default, each message is of size `1`, but a custom `metric` function that
   returns the size of each message may be defined."
  ([batch-size s]
    (batch (constantly 1) batch-size nil s))
  ([max-size max-latency s]
    (batch (constantly 1) max-size max-latency s))
  ([metric max-size max-latency s]
    (assert (pos? max-size))

    (let [buf (stream)
          s' (stream)]

      (connect-via-proxy s buf s' {:description {:op "batch"}})
      (on-closed s' #(close! buf))

      (d/loop [msgs [], size 0, earliest-message -1, last-message -1]
        (cond
          (or
            (== size max-size)
            (and (< max-size size) (== (count msgs) 1)))
          (d/chain' (put! s' msgs)
            (fn [_]
              (d/recur [] 0 -1 -1)))

          (> size max-size)
          (let [msg (peek msgs)]
            (d/chain' (put! s' (pop msgs))
              (fn [_]
                (d/recur [msg] (metric msg) last-message last-message))))

          :else
          (d/chain' (if (or
                          (nil? max-latency)
                          (neg? earliest-message)
                          (empty? msgs))
                      (take! buf ::empty)
                      (try-take! buf
                        ::empty
                        (- max-latency (- (time/current-millis) earliest-message))
                        ::timeout))
            (fn [msg]
              (condp keyword-identical? msg
                ::empty
                (do
                  (when-not (empty? msgs)
                    (put! s' msgs))
                  (close! s'))

                ::timeout
                (d/chain' (when-not (empty? msgs)
                            (put! s' msgs))
                  (fn [_]
                    (d/recur [] 0 -1 -1)))

                (let [time (time/current-millis)]
                  (d/recur
                    (conj msgs msg)
                    (+ size (metric msg))
                    (if (neg? earliest-message)
                      time
                      earliest-message)
                    time)))))))

      (source-only s'))))

;; System/currentTimeMillis -> time/current-millis
;; identical? -> keyword-identical?
(defn throttle
  "Limits the `max-rate` that messages are emitted, per second.

   The `max-backlog` dictates how much \"memory\" the throttling mechanism has, or how many
   messages it will emit immediately after a long interval without any messages.  By default,
   this is set to one second's worth."
  ([max-rate s]
     (throttle max-rate max-rate s))
  ([max-rate max-backlog s]
     (let [buf (stream)
           s' (stream)
           period (double (/ 1000 max-rate))]

       (connect-via-proxy s buf s' {:description {:op "throttle"}})
       ;; TODO: why do we need this?
       (on-closed s' #(close! buf))

       (d/loop [backlog 0.0, read-start (time/current-millis)]
         (d/chain (take! buf ::none)

           (fn [msg]
             (if (keyword-identical? ::none msg)
               (do
                 (close! s')
                 false)
               (put! s' msg)))

           (fn [result]
             (when result
               (let [elapsed (double (- (time/current-millis) read-start))
                     backlog' (min (+ backlog (- (/ elapsed period) 1)) max-backlog)]
                 (if (<= 1 backlog')
                   (- backlog' 1.0)
                   (d/timeout! (d/deferred) (- period elapsed) 0.0)))))

           (fn [backlog]
             (when backlog
               (d/recur backlog (time/current-millis))))))

       (source-only s'))))

(ns manifold-cljs.bus
  "An implementation of an event bus, where publishers and subscribers can interact via topics."
  (:require [manifold-cljs.stream :as s]
            [manifold-cljs.deferred :as d]))

;; - interface -> protocol
(defprotocol IEventBus
  (snapshot [this])
  ;; - removed definline under the same name
  (subscribe [this topic]
    "Returns a stream which consumes all messages from `topic`.")
  ;; - removed definline under the same name
  (downstream [this topic]
    "Returns a list of all streams subscribed to `topic`.")
  (publish [this topic message])
  (isActive [this topic]))

;; - definline -> defn
(defn publish!
  "Publishes a message on the bus, returning a deferred result representing the message
   being accepted by all subscribers.  To prevent one slow consumer from blocking all
   the others, use `manifold.stream/buffer`, or `manifold.stream/connect` with a timeout
   specified."
  [bus topic message]
  (publish bus topic message))

;; - definline -> defn
(defn active?
  "Returns `true` if there are any subscribers to `topic`."
  [bus topic]
  (isActive bus topic))

;; - definline -> defn
(defn topic->subscribers
  [bus]
  (snapshot bus))

;; instead of System/arraycopy
(defn- arraycopy [from a to b len]
  (loop [a a b b len len]
    (if (zero? len)
      to
      (do (aset to b (aget from a))
          (recur (inc a) (inc b) (dec len))))))

;; - Array/getLength -> alength
;; - System/arraycopy -> arraycopy
(defn- conj' [ary x]
  (if (nil? ary)
    (object-array [x])
    (let [len (alength ary)
          ary' (object-array (inc len))]
      (arraycopy ary 0 ary' 0 len)
      (aset ^objects ary' len x)
      ary')))

;; - Array/getLength -> alength
;; - System/arraycopy -> arraycopy
(defn- disj' [^objects ary x]
  (let [len (alength ary)]
    (if-let [idx (loop [i 0]
                   (if (<= len i)
                     nil
                     (if (identical? x (aget ary i))
                       i
                       (recur (inc i)))))]
      (let [idx (long idx)]
        (if (== 1 len)
          nil
          (let [ary' (object-array (dec len))]
            (arraycopy ary 0 ary' 0 idx)
            (arraycopy ary (inc idx) ary' idx (- len idx 1))
            ary')))
      ary)))

;; - ConcurrentHashMap -> atom
;; - removed CAS loops
(defn event-bus
  "Returns an event bus that can be used with `publish!` and `subscribe`."
  ([]
    (event-bus s/stream))
  ([stream-generator]
    (let [topic->subscribers (atom {})]
      (reify IEventBus

        (snapshot [_]
          (->> @topic->subscribers
            (map
              (fn [[topic subscribers]]
                [topic (into [] subscribers)]))
            (into {})))

        (subscribe [_ topic]
          (let [s (stream-generator)]

            (let [subscribers (get @topic->subscribers topic)
                  subscribers' (conj' subscribers s)]
              (swap! topic->subscribers assoc topic subscribers'))

            (s/on-closed s
              (fn []
                (let [subscribers (get @topic->subscribers topic)
                      subscribers' (disj' subscribers s)]
                  (swap! topic->subscribers assoc topic subscribers'))))

            (s/source-only s)))

        (publish [_ topic message]
          (let [subscribers (get @topic->subscribers topic)]
            (if (nil? subscribers)
              (d/success-deferred false)
              (-> (apply d/zip' (map #(s/put! % message) subscribers))
                (d/chain' (fn [_] true))))))

        (downstream [_ topic]
          (seq (get @topic->subscribers topic)))

        (isActive [_ topic]
          (boolean (get @topic->subscribers topic)))))))

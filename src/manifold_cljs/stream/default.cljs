(ns manifold-cljs.stream.default
  (:require [manifold-cljs.deferred :as d]
            [manifold-cljs.impl.queue :as q]
            [manifold-cljs.impl.logging :as log]
            [manifold-cljs.utils :as u]
            [manifold-cljs.executor :as ex]
            [manifold-cljs.stream.core :as s]
            [manifold-cljs.stream.graph :as g]))

(deftype Production [deferred message token])
(deftype Consumption [message deferred token])
(deftype Producer [message deferred])
(deftype Consumer [deferred default-val])

;; - Throwable -> js/Error
;; - LinkedList methods -> queue ns
(s/def-sink+source Stream
  [^boolean permanent?
   description
   producers
   consumers
   capacity
   messages
   executor
   add!]

  :stream
  [(isSynchronous [_] false)

   (description [this]
                (let [m {:type "manifold"
                         :sink? true
                         :source? true
                         :pending-puts (q/size producers)
                         :buffer-capacity capacity
                         :buffer-size (if messages (q/size messages) 0)
                         :pending-takes (q/size consumers)
                         :permanent? permanent?
                         :closed? (s/closed? this)
                         :drained? (s/drained? this)}]
                  (if description
                    (description m)
                    m)))

   (close [this]
          (when-not permanent?
            (when-not (s/closed? this)

              (try
                (add!)
                (catch js/Error e
                  (log/error e "error in stream transformer")))

              (loop []
                (when-let [c (q/poll consumers)]
                  (try
                    (d/success! (.-deferred c) (.-default-val c))
                    (catch js/Error e
                      (log/error e "error in callback")))
                  (recur)))

              (s/markClosed this)

              (when (s/drained? this)
                (s/markDrained this)))))]

  :sink
  [(put [this msg blocking? timeout timeout-val]
        (assert (not blocking?) "Blocking operations not supported!")
        (let [acc (q/queue)

              result
              (try
                (if (s/isClosed this)
                  false
                  (add! acc msg))
                (catch js/Error e
                  (log/error e "error in stream transformer")
                  false))

              close?
              (reduced? result)

              result
              (if close?
                @result
                result)

              val (loop [val true]
                    (if (q/empty? acc)
                      val
                      (let [x (q/pop acc)]
                        (cond

                          (instance? Producer x)
                          (do
                            (log/warn "excessive pending puts (> 16384), closing stream")
                            (s/close! this)
                            false)

                          (instance? Production x)
                          (let [^Production p x]
                            (d/success! (.-deferred p) (.-message p) (.-token p))
                            (recur true))

                          :else
                          (do
                            (d/timeout! x timeout timeout-val)
                            (recur x))))))]

          (cond

            (or close? (false? result))
            (do
              (s/close this)
              (d/success-deferred false executor))

            (d/deferred? val)
            val

            :else
            (d/success-deferred val executor))))

   (put [this msg blocking?]
        (s/put this msg blocking? nil nil))]

  :source
  [(isDrained [this]
              (and (s/closed? this)
                   (q/empty? producers)
                   (or (nil? messages)
                       (q/empty? messages))))

   ;; TODO: remove claim! - we don't need it in single-threaded environment
   (take [this default-val blocking? timeout timeout-val]
         (assert (not blocking?) "Blocking operations not supported!")
         (let [result
               (or

                 ;; see if we can dequeue from the buffer
                 (when-let [msg (and messages (q/poll messages))]

                   ;; check if we're drained
                   (when (and (s/closed? this) (s/drained? this))
                     (s/markDrained this))

                   (if-let [^Producer p (q/poll producers)]
                     (if-let [token (d/claim! (.-deferred p))]
                       (do
                         (q/offer messages (.-message p))
                         (Consumption. msg (.-deferred p) token))
                       (d/success-deferred msg executor))
                     (d/success-deferred msg executor)))

                 ;; see if there are any unclaimed producers left
                 (loop [^Producer p (q/poll producers)]
                   (when p
                     (if-let [token (d/claim! (.-deferred p))]
                       (let [c (Consumption. (.-message p) (.-deferred p) token)]

                         ;; check if we're drained
                         (when (and (s/closed? this) (s/drained? this))
                           (s/markDrained this))

                         c)
                       (recur (q/poll producers)))))

                 ;; closed, return << default-val >>
                 (and (s/closed? this)
                      (d/success-deferred default-val executor))

                 ;; add to the consumers queue
                 (if (and timeout (<= timeout 0))
                   (d/success-deferred timeout-val executor)
                   (let [d (d/deferred executor)]
                     (d/timeout! d timeout timeout-val)
                     (let [c (Consumer. d default-val)]
                       (if (and (< (q/size consumers) 16384) (q/offer consumers c))
                         d
                         c)))))]

           (cond

             (instance? Consumer result)
             (do
               (log/warn "excessive pending takes (> 16384), closing stream")
               (s/close! this)
               (d/success-deferred false executor))

             (instance? Consumption result)
             (let [^Consumption result result]
               (try
                 (d/success! (.-deferred result) true (.-token result))
                 (catch js/Error e
                   (log/error e "error in callback")))
               (let [msg (.-message result)]
                 (d/success-deferred msg executor)))

             :else result)))

   (take [this default-val blocking?]
         (s/take this default-val blocking? nil nil))])

;; same as clj
;; - removed type annotations
;; - LinkedList -> array
;; - ArrayDeque -> queue
(defn add!
  [producers
   consumers
   messages
   capacity
   executor]
  (let [capacity (long capacity)
        t-d (d/success-deferred true executor)]
    (fn
      ([]
       )
      ([_]
       (d/success-deferred false executor))
      ([acc msg]
       (doto acc
         (q/offer
           (or

             ;; see if there are any unclaimed consumers left
             (loop [^Consumer c (q/poll consumers)]
               (when c
                 (if-let [token (d/claim! (.-deferred c))]
                   (Production. (.-deferred c) msg token)
                   (recur (q/poll consumers)))))

             ;; see if we can enqueue into the buffer
             (and
               messages
               (when (< (q/size messages) capacity)
                 (q/offer messages msg))
               t-d)

             ;; add to the producers queue
             (let [d (d/deferred executor)]
               (let [pr (Producer. msg d)]
                 (if (and (< (q/size producers) 16384) (q/offer producers pr))
                   d
                   pr))))))))))

;; - LinkedList -> array
;; - ArrayDeque -> queue
;; - Math/max -> max
(defn stream
  ([]
    (stream 0 nil (ex/executor)))
  ([buffer-size]
    (stream buffer-size nil (ex/executor)))
  ([buffer-size xform]
    (stream buffer-size xform (ex/executor)))
  ([buffer-size xform executor]
    (let [consumers    (q/queue)
          producers    (q/queue)
          buffer-size  (long (max 0 (long buffer-size)))
          messages     (when (pos? buffer-size) (q/queue))
          add!         (add! producers consumers messages buffer-size executor)
          add!         (if xform (xform add!) add!)]
      (->Stream
        false
        nil
        producers
        consumers
        buffer-size
        messages
        executor
        add!))))

;; - LinkedList -> array
;; - ArrayDeque -> queue
;; - Math/max -> max
(defn stream*
  [{:keys [permanent?
           buffer-size
           description
           executor
           xform]
    :or {permanent? false
         executor (ex/executor)}}]
  (let [consumers   (q/queue)
        producers   (q/queue)
        buffer-size (long (or buffer-size 0))
        messages    (when buffer-size (q/queue))
        buffer-size (if buffer-size (long (max 0 buffer-size)) 0)
        add!        (add! producers consumers messages buffer-size executor)
        add!        (if xform (xform add!) add!)]
    (->Stream
      permanent?
      description
      producers
      consumers
      buffer-size
      messages
      executor
      add!)))

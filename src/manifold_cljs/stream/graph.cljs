(ns manifold-cljs.stream.graph
  (:require [org.weakmap]

            [manifold-cljs.deferred :as d]
            [manifold-cljs.utils :as utils]
            [manifold-cljs.stream.core :as s]
            [manifold-cljs.executor :as ex]
            [manifold-cljs.impl.list :as l]
            [manifold-cljs.impl.queue :as q]
            [manifold-cljs.impl.logging :as log]))

;; - ConcurrentHashMap -> WeakMap
(def handle->downstreams (js/WeakMap.))

;; - removed type hints
(deftype Downstream
  [^long timeout
   ^boolean upstream?
   ^boolean downstream?
   sink
   description])

;; - removed type hints
(deftype AsyncPut
  [deferred
   dsts
   dst
   ^boolean upstream?])

;;;

;; - removed COWAList-specific iteration
;; (.x) -> (.-x)
(defn downstream [source]
  ;; TODO: why would it not return a handle?
  (when-let [handle (s/weak-handle source)]
    (when-let [l (.get handle->downstreams handle)]
      (map
        (fn [^Downstream d]
          [(.-description d) (.-sink d)]) l))))

;;;

;; (.x) -> (.-x)
(defn- async-send
  [^Downstream d msg dsts]
  (let [sink (.-sink d)]
    (let [x (if (== (.-timeout d) -1)
              (s/put sink msg false)
              (s/put sink msg false (.-timeout d) (if (.-downstream? d) sink false)))]
      (AsyncPut. x dsts d (.-upstream? d)))))

;; (.x) -> (.-x)
;; CHM#remove -> WeakMap.delete
;; COWAList.remove, size -> list/remove,size
;; instance? -> satisfies?
(defn- handle-async-put [^AsyncPut x val source]
  (let [d (.-deferred x)
        val (if (satisfies? s/IEventSink val)
              (do
                (s/close! val)
                false)
              val)]
    (when (false? val)
      (let [l (.-dsts x)]
        (l/remove l (.-dst x))
        (when (or (.-upstream? x) (== 0 (l/size l)))
          (s/close! source)
          (.delete handle->downstreams (s/weak-handle source)))))))

;; (.x) -> (.-x)
;; CHM#remove -> WeakMap.delete
;; COWAList.remove, size -> list/remove,size
(defn- handle-async-error [^AsyncPut x err source]
  (some-> (.-dst x) .-sink s/close!)
  (log/error err "error in message propagation")
  (let [l (.-dsts x)]
    (l/remove l (.-dst x))
    (when (or (.upstream? x) (== 0 (l/size l)))
      (s/close! source)
      (.delete handle->downstreams (s/weak-handle source)))))

;; LinkedList -> queue
(defn- async-connect
  [source dsts]
  (let [sync-sinks (q/queue)
        deferreds  (q/queue)

        sync-propagate
        (fn this [recur-point msg]
          (loop []
            (let [^Downstream d (q/poll sync-sinks)]
              (if (nil? d)
                recur-point
                (let [^AsyncPut x (async-send d msg dsts)
                      d (.-deferred x)
                      val (d/success-value d ::none)]
                  (if (keyword-identical? val ::none)
                    (d/on-realized d
                      (fn [v]
                        (handle-async-put x v source)
                        (trampoline #(this recur-point msg)))
                      (fn [e]
                        (handle-async-error x e source)
                        (trampoline #(this recur-point msg))))
                    (do
                      (handle-async-put x val source)
                      (recur))))))))

        async-propagate
        (fn this [recur-point msg]
          (loop []
            (let [^AsyncPut x (q/poll deferreds)]
              (if (nil? x)

                ;; iterator over sync-sinks
                (if (q/empty? sync-sinks)
                  recur-point
                  #(sync-propagate recur-point msg))

                ;; iterate over async-sinks
                (let [d (.-deferred x)
                      val (d/success-value d ::none)]
                  (if (keyword-identical? val ::none)
                    (d/on-realized d
                      (fn [val]
                        (handle-async-put x val source)
                        (trampoline #(this recur-point msg)))
                      (fn [e]
                        (handle-async-error x e source)
                        (trampoline #(this recur-point msg))))
                    (do
                      (handle-async-put x val source)
                      (recur))))))))

        err-callback
        (fn [err]
          (log/error err "error in source of 'connect'")
          (.delete handle->downstreams (s/weak-handle source)))]

    (trampoline
      (fn this
        ([]
          (let [d (s/take source ::drained false)]
            (if (d/realized? d)
              (this @d)
              (d/on-realized d
                (fn [msg] (trampoline #(this msg)))
                err-callback))))
        ([msg]
          (cond

            (keyword-identical? ::drained msg)
            (do
              (.delete handle->downstreams (s/weak-handle source))
              (doseq [^Downstream d dsts]
                (when (.-downstream? d)
                  (s/close! (.-sink d)))))

            (== 1 (l/size dsts))
            (let [dst (first dsts)
                  ^AsyncPut x (async-send dst msg dsts)
                  d (.-deferred x)
                  val (d/success-value d ::none)]
              (if (keyword-identical? ::none val)
                (d/on-realized d
                  (fn [val]
                    (handle-async-put x val source)
                    (trampoline this))
                  (fn [e]
                    (handle-async-error x e source)
                    (trampoline this)))
                (do
                  (handle-async-put x val source)
                  this)))

            :else
            (if (empty? dsts)
              (do
                (s/close! source)
                (.delete handle->downstreams (s/weak-handle source)))

              (do
                (doseq [^Downstream d dsts]
                  (if (s/synchronous? (.-sink d))
                    (l/add sync-sinks d)
                    (l/add deferreds (async-send d msg dsts))))
                (async-propagate this msg)))))))))

;; CHM#putIfAbsent -> WeakMap.set
(defn connect
  ([src dst
    {:keys [upstream?
            downstream?
            timeout
            description]
     :or {timeout -1
          upstream? false
          downstream? true}
     :as opts}]
   (let [d (Downstream.
             timeout
             (boolean (and upstream? (satisfies? s/IEventSink src)))
             downstream?
             dst
             description)
         k (s/weakHandle src nil)]
     (if-let [dsts (.get handle->downstreams k)]
       (l/add dsts d)
       (let [dsts (l/list)]
         (.set handle->downstreams k dsts)
         (l/add dsts d)
         (if (s/synchronous? src)
           (throw (js/Error. "Cannot connect to a synchronous source!"))
           (async-connect src dsts)))))))

(ns manifold-cljs.deferred.default
  (:require [manifold-cljs.executor :as ex]
            [manifold-cljs.impl.list :as l]
            [manifold-cljs.impl.queue :as q]
            [manifold-cljs.impl.logging :as log]
            [manifold-cljs.deferred.core :as core])
  (:require-macros [manifold-cljs.deferred.default :refer [set-deferred deref-deferred]]))

(deftype Deferred
  [^:mutable val
   ^:mutable state
   ^:mutable claim-token
   listeners
   ^:mutable consumed?
   executor]

  core/IMutableDeferred
  (claim [_]
    (when (keyword-identical? state ::unset)
      (set! state ::claimed)
      (set! claim-token #js {}))
    claim-token)

  (addListener [_ listener]
    (set! consumed? true)
    (when-let [f (condp keyword-identical? state
                   ::success #(core/onSuccess listener val)
                   ::error   #(core/onError listener val)
                   (do
                     (l/add listeners listener)
                     nil))]
      (if executor
        (ex/execute executor f)
        (f)))
    true)

  (cancelListener [_ listener]
    (let [state state]
      (if (or (keyword-identical? ::unset state)
              (keyword-identical? ::set state))
        (l/remove listeners listener)
        false)))

  (success [_ x]
    (set-deferred x nil true false executor))
  (success [_ x token]
    (set-deferred x token true true executor))
  (error [_ x]
    (set-deferred x nil false false executor))
  (error [_ x token]
    (set-deferred x token false true executor))

  IFn
  (-invoke [this x]
    (if (core/success this x)
      this
      nil))

  core/IDeferred
  (executor [_] executor)
  (realized [_]
    (let [state state]
      (or (keyword-identical? ::success state)
          (keyword-identical? ::error state))))
  (onRealized [this on-success on-error]
    (core/addListener this (core/listener on-success on-error)))
  (successValue [this default-value]
    (if (keyword-identical? ::success state)
      (do
        (set! consumed? true)
        val)
      default-value))
  (errorValue [this default-value]
    (if (keyword-identical? ::error state)
      (do
        (set! consumed? true)
        val)
      default-value))

  IPending
  (-realized? [this] (core/realized this))

  IDeref
  (-deref [this]
    (set! consumed? true)
    (deref-deferred)))

(deftype SuccessDeferred
  [val
   executor]

  core/IMutableDeferred
  (claim [_] false)
  (addListener [_ listener]
    (if (nil? executor)
      (core/onSuccess listener val)
      (ex/execute executor #(core/onSuccess listener val)))
    true)
  (cancelListener [_ listener] false)
  (success [_ x] false)
  (success [_ x token] false)
  (error [_ x] false)
  (error [_ x token] false)

  IFn
  (-invoke [this x] nil)

  core/IDeferred
  (executor [_] executor)
  (realized [this] true)
  (onRealized [this on-success on-error]
    (if executor
      (ex/execute executor #(on-success val))
      (on-success val)))
  (successValue [_ default-value]
    val)
  (errorValue [_ default-value]
    default-value)

  IPending
  (-realized? [this] (core/realized this))

  IDeref
  (-deref [this] val))

(deftype ErrorDeferred
  [error
   ^:mutable consumed?
   executor]

  core/IMutableDeferred
  (claim [_] false)
  (addListener [_ listener]
    (set! consumed? true)
    (core/onError listener error)
    true)
  (cancelListener [_ listener] false)
  (success [_ x] false)
  (success [_ x token] false)
  (error [_ x] false)
  (error [_ x token] false)

  IFn
  (-invoke [this x] nil)

  core/IDeferred
  (executor [_] executor)
  (realized [_] true)
  (onRealized [this on-success on-error]
    (set! consumed? true)
    (if (nil? executor)
      (on-error error)
      (ex/execute executor #(on-error error))))
  (successValue [_ default-value]
    default-value)
  (errorValue [_ default-value]
    (set! consumed? true)
    error)

  IPending
  (-realized? [this] (core/realized this))

  IDeref
  (-deref [this]
    (set! consumed? true)
    (if (instance? js/Error error)
      (throw error)
      (throw (ex-info "" {:error error})))))

;; Manifold uses `nil` as the default executor.
;; Manifold-cljs uses `ex/default-executor`, so we optimize for the relevant case.
(def ^:no-doc true-deferred-  (SuccessDeferred. true ex/default-executor))
(def ^:no-doc false-deferred- (SuccessDeferred. false ex/default-executor))
(def ^:no-doc nil-deferred-   (SuccessDeferred. nil ex/default-executor))

(defn success-deferred
  "A deferred which already contains a realized value"
  ([val]
     (success-deferred val (ex/executor)))
  ([val executor]
     (if (identical? executor ex/default-executor)
       (condp = val
         true true-deferred-
         false false-deferred-
         nil nil-deferred-
         (SuccessDeferred. val executor))
       (SuccessDeferred. val executor))))

(defn error-deferred
  "A deferred which already contains a realized error"
  ([error]
    (ErrorDeferred. error false (ex/executor)))
  ([error executor]
    (ErrorDeferred. error false executor)))

(defn deferred [executor]
  (Deferred. nil ::unset nil (l/list) false executor))

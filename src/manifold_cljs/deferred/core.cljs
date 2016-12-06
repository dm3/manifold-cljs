(ns manifold-cljs.deferred.core)

;; moved from manifold.deferred

;; - definterface -> defprotocol
(defprotocol IDeferred
  (executor [this])
  (^boolean realized [this])
  (onRealized [this on-success on-error])
  (successValue [this default])
  (errorValue [this default]))

;; - interface -> protocol
(defprotocol IMutableDeferred
  (success
    [this x]
    [this x claim-token])
  (error
    [this x]
    [this x claim-token])
  (claim [this])
  (addListener [this listener])
  (cancelListener [this listener]))

;; - interface -> protocol
(defprotocol IDeferredListener
  (onSuccess [this x])
  (onError [this err]))

;; - no equals/hashCode
(deftype Listener [on-success on-error]
  IDeferredListener
  (onSuccess [_ x] (on-success x))
  (onError [_ err] (on-error err)))

;; - definiline -> defn
(defn listener
  "Creates a listener which can be registered or cancelled via `add-listener!` and `cancel-listener!`."
  ([on-success]
    (listener on-success (fn [_])))
  ([on-success on-error]
    (Listener. on-success on-error)))

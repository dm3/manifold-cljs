(ns manifold-cljs.deferred.default-impl
  (:require [manifold-cljs.utils :refer [kw-identical?]]))

;; Throwable -> js/Error
;; identical? -> keyword-identical?
;; .poll -> q/poll
;; .onSuccess/.onError -> core/onSuccess core/onError
;; removed lock
;; IllegalStateException -> ex-info
(defmacro set-deferred [val token success? claimed? executor]
  `(if (when (and
               (kw-identical? ~(if claimed? ::claimed ::unset) ~'state)
               ~@(when claimed?
                   `((identical? ~'claim-token ~token))))
         (set! ~'val ~val)
         (set! ~'state ~(if success? ::success ::error))
         true)
     (do
       (clojure.core/loop []
         (when-let [l# (q/poll ~'listeners)]
           (try
             (if (nil? ~executor)
               (~(if success?  `core/onSuccess `core/onError) l# ~val)
               (ex/execute ~executor
                 (fn []
                   (try
                     (~(if success? `core/onSuccess `core/onError) l# ~val)
                     (catch js/Error e#
                       (log/error e# "error in deferred handler"))))))
             (catch js/Error e#
               (log/error e# "error in deferred handler")))
           (recur)))
       true)
     ~(if claimed?
        `(throw (ex-info
                  (if (identical? ~'claim-token ~token)
                    "deferred isn't claimed"
                    "invalid claim-token")
                  {:claim-token ~'claim-token, :token ~token}))
        false)))

;; removed timeout version
;; Throwable -> jsError
(defmacro deref-deferred []
  `(if (kw-identical? ~'state ::success)
     ~'val
     (if (kw-identical? ~'state ::error)
       (if (instance? js/Error ~'val)
         (throw ~'val)
         (throw (ex-info "" {:error ~'val})))
       (throw (ex-info "invalid state" {:state ~'state})))))

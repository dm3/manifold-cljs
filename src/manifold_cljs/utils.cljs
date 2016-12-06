(ns manifold-cljs.utils
  (:require [manifold-cljs.impl.queue :as q]
            [manifold-cljs.executor :as ex]
            [manifold-cljs.impl.logging :as log])
  (:require-macros [manifold-cljs.utils :as u]))

(def ^:private integer-max-value
  ;; equal to Number.MAX_SAFE_INTEGER
  ;; copied here for better compatibility
  9007199254740991)

;; - remove type annotation
;; - .queue methods -> queue ns
;; - Throwable -> js/Error
;; - runs on next tick
(defn invoke-callbacks [callbacks]
  (ex/execute-on-next-tick
    (fn []
      (loop []
        (when-let [c (q/poll callbacks)]
          (try
            (c)
            (catch js/Error e
              (log/error e "error in invoke-callbacks")))
          (recur))))))

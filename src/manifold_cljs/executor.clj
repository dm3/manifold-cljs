(ns manifold-cljs.executor)

(defmacro with-executor [executor & body]
  `(let [executor# (executor)]
     (set! current-executor ~executor)
     (try
       ~@body
       (finally
         (set! current-executor executor#)))))

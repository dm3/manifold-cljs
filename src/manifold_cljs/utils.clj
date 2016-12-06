(ns manifold-cljs.utils
  (:require [manifold-cljs.executor :as ex]))

(defn- if-cljs [env then else]
  (if (:ns env) then else))

(defmacro kw-identical? [a b]
  (if-cljs &env
    `(cljs.core/keyword-identical? ~a ~b)
    `(identical? ~a ~b)))

(defmacro future-with [executor & body]
  ;; TODO: simulate var bindings using
  ;; https://github.com/hoplon/hoplon/blob/master/src/hoplon/binding.cljs
  ;; ?
  `(ex/execute ~executor (fn [] ~@body)))

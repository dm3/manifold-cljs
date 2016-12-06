(ns manifold-cljs.deferred
  (:refer-clojure :exclude [loop future time])
  (:require [manifold-cljs.utils :as u]
            [manifold-cljs.executor :as ex]
            [clojure.set :as sets]))

;; completely different from clj
(defmacro future-with [executor & body]
  `(let [d# (deferred)]
     (manifold-cljs.utils/future-with ~executor
       (when-not (realized? d#)
         (try
           (success! d# (do ~@body))
           (catch js/Error e#
             (error! d# e#)))))
     d#))

;; completely different from clj
(defmacro future [& body]
  `(future-with (ex/next-tick-executor) ~@body))

;; - identical? -> keyword-identical?
(defmacro ^:no-doc success-error-unrealized
  [deferred
   success-value
   success-clause
   error-value
   error-clause
   unrealized-clause]
  `(let [d# ~deferred
         ~success-value (success-value d# ::none)]
     (if (cljs.core/keyword-identical? ::none ~success-value)
       (let [~error-value (error-value d# ::none)]
         (if (cljs.core/keyword-identical? ::none ~error-value)
           ~unrealized-clause
           ~error-clause))
       ~success-clause)))

;; - Throwable -> js/Error
(defmacro loop
  "A version of Clojure's loop which allows for asynchronous loops, via `manifold.deferred/recur`.
  `loop` will always return a deferred value, even if the body is synchronous.  Note that `loop` does **not** coerce values to deferreds, actual Manifold deferreds must be used.

   (loop [i 1e6]
     (chain (future i)
       #(if (zero? %)
          %
          (recur (dec %)))))"
  [bindings & body]
  (let [vars (->> bindings (partition 2) (map first))
        vals (->> bindings (partition 2) (map second))
        x-sym (gensym "x")
        val-sym (gensym "val")
        var-syms (map (fn [_] (gensym "var")) vars)]
    `(let [result# (deferred)]
       ((fn this# [result# ~@var-syms]
          (clojure.core/loop
            [~@(interleave vars var-syms)]
            (let [~x-sym (try
                           ~@body
                           (catch js/Error e#
                             (error! result# e#)
                             nil))]
              (cond

                (deferred? ~x-sym)
                (success-error-unrealized ~x-sym
                  ~val-sym (if (instance? Recur ~val-sym)
                             (let [~val-sym @~val-sym]
                               (~'recur
                                 ~@(map
                                     (fn [n] `(nth ~val-sym ~n))
                                     (range (count vars)))))
                             (success! result# ~val-sym))

                  err# (error! result# err#)

                  (on-realized (chain' ~x-sym)
                    (fn [x#]
                      (if (instance? Recur x#)
                        (apply this# result# @x#)
                        (success! result# x#)))
                    (fn [err#]
                      (error! result# err#))))

                (instance? Recur ~x-sym)
                (~'recur
                  ~@(map
                      (fn [n] `(nth @~x-sym ~n))
                      (range (count vars))))

                :else
                (success! result# ~x-sym)))))
         result#
         ~@vals)
       result#)))

;; cljs specific
(defmacro time [& body]
  `(time* (fn [] ~@body)))

;; TODO: let-flow

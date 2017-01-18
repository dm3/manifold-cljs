(ns manifold-cljs.deferred
  (:refer-clojure :exclude [realized?])
  (:require [manifold-cljs.deferred.default :as d]
            [manifold-cljs.deferred.core :as core]
            [manifold-cljs.executor :as ex]
            [manifold-cljs.time :as time])
  (:require-macros [manifold-cljs.deferred :refer [success-error-unrealized]]))

(defprotocol Deferrable
  (^:private to-deferred [_] "Provides a conversion mechanism to manifold deferreds."))

;; - no definline
(defn realized?
  "Returns true if the manifold deferred is realized."
  [x] (core/realized x))

;; - definline -> defn
(defn success-value [x default-value]
  (core/successValue x default-value))

;; - definline -> defn
(defn error-value [x default-value]
  (core/errorValue x default-value))

;; - definline -> defn
(defn on-realized
  "Registers callbacks with the manifold deferred for both success and error outcomes."
  [x on-success on-error]
  (do
    (core/onRealized x on-success on-error)
    x))

;; - instance? -> satisfies?
(defn deferred?
  "Returns true if the object is an instance of a Manifold deferred."
  [x] (satisfies? core/IDeferred x))

;; - instance? -> satisfies?
(defn- satisfies-deferrable?
  [x] (satisfies? Deferrable x))

;; no IPending support
(defn deferrable?
  "Returns true if the object can be coerced to a Manifold deferred."
  [x]
  (or
    (deferred? x)
    (satisfies-deferrable? x)))

;; - cannot coerce IDeref + IPending
;; - throws ex-info
(defn ->deferred
  "Transforms `x` into a deferred if possible, or returns `default-val`.  If no default value
   is given, an `IllegalArgumentException` is thrown."
  ([x]
    (let [x' (->deferred x ::none)]
      (if (keyword-identical? ::none x')
        (throw
          (ex-info (str "cannot convert " (type x) " to deferred.") {}))
        x')))
  ([x default-val]
    (cond
      (deferred? x)
      x

      (satisfies-deferrable? x)
      (to-deferred x)

      :else
      default-val)))

;; - moved to manifold-cljs.deferred.core
(def ^{:doc "Creates a listener which can be registered or cancelled via
            `add-listener!` and `cancel-listener!`."}
  listener core/listener)

;; - definiline -> defn
(defn success!
  "Equivalent to `deliver`, but allows a `claim-token` to be passed in."
  ([deferred x]
    (core/success deferred x))
  ([deferred x claim-token]
    (core/success deferred x claim-token)))

;; - definiline -> defn
(defn error!
  "Puts the deferred into an error state."
  ([deferred x]
    (core/error deferred x))
  ([deferred x claim-token]
    (core/error deferred x claim-token)))

;; - definiline -> defn
(defn claim!
  "Attempts to claim the deferred for future updates.  If successful, a claim token is returned, otherwise returns `nil`."
  [deferred]
  (core/claim deferred))

;; - definiline -> defn
(defn add-listener!
  "Registers a listener which can be cancelled via `cancel-listener!`.  Unless this is useful, prefer `on-realized`."
  [deferred listener]
  (core/addListener deferred listener))

;; - definiline -> defn
(defn cancel-listener!
  "Cancels a listener which has been registered via `add-listener!`."
  [deferred listener]
  (core/cancelListener deferred listener))

(defn deferred
  "Equivalent to Clojure's `promise`, but also allows asynchronous callbacks to be registered
  and composed via `chain`."
  ([] (deferred (ex/executor)))
  ([executor] (d/deferred executor)))

(defn success-deferred
  "A deferred which already contains a realized value"
  ([val] (success-deferred val (ex/executor)))
  ([val executor] (d/success-deferred val executor)))

(defn error-deferred
  "A deferred which already contains a realized error"
  ([error] (error-deferred error (ex/executor)))
  ([error executor] (d/error-deferred error executor)))

;; identical? -> keyword-identical?
(defn unwrap' [x]
  (if (deferred? x)
    (let [val (success-value x ::none)]
      (if (keyword-identical? val ::none)
        x
        (recur val)))
    x))

;; same
(defn unwrap [x]
  (let [d (->deferred x nil)]
    (if (nil? d)
      x
      (let [val (success-value d ::none)]
        (if (keyword-identical? ::none val)
          d
          (recur val))))))

;; `instance? IDeferred` -> `deferred?`
(defn connect
  "Conveys the realized value of `a` into `b`."
  [a b]
  (assert (deferred? b) "sink `b` must be a Manifold deferred")
  (let [a (unwrap a)]
    (if (deferred? a)
      (if (realized? b)
        false
        (do
          (on-realized a
            #(let [a' (unwrap %)]
               (if (deferred? a')
                 (connect a' b)
                 (success! b a')))
            #(error! b %))
          true))
      (success! b a))))

;; - inline `subscribe`
;; - less unrolled arities
;; - Throwable -> js/Error
(defn chain'-
  ([d x]
   (try
     (let [x' (unwrap' x)]

       (if (deferred? x')

         (let [d (or d (deferred))]
           (on-realized x'
                        #(chain'- d %)
                        #(error! d %))
           d)

         (if (nil? d)
           (success-deferred x')
           (success! d x'))))
     (catch js/Error e
       (if (nil? d)
         (error-deferred e)
         (error! d e)))))

   ([d x f]
      (try
        (let [x' (unwrap' x)]

          (if (deferred? x')

            (let [d (or d (deferred))]
              (on-realized x'
                           #(chain'- d % f)
                           #(error! d %))
              d)

            (let [x'' (f x')]
              (if (deferred? x'')
                (chain'- d x'')
                (if (nil? d)
                  (success-deferred x'')
                  (success! d x''))))))
        (catch js/Error e
          (if (nil? d)
            (error-deferred e)
            (error! d e)))))

  ([d x f & fs]
   (when (or (nil? d) (not (realized? d)))
     (let [d (or d (deferred))]
       (clojure.core/loop [x x, fs (list* f fs)]
         (if (empty? fs)
           (success! d x)
           (let [[f & fs] fs
                 d' (chain'- nil x f)]
             (success-error-unrealized d'
               val (recur val fs)
               err (error! d err)
               (on-realized d'
                            #(apply chain'- d % fs)
                            #(error! d %))))))
       d))))

;; - inline `subscribe`
;; - less unrolled arities
;; - Throwable -> js/Error
(defn chain-
  ([d x]
   (let [x' (unwrap x)]

     (if (deferred? x')

       (let [d (or d (deferred))]
         (on-realized x'
                      #(chain- d %)
                      #(error! d %))
         d)

       (if (nil? d)
         (success-deferred x')
         (success! d x')))))
  ([d x f]
   (if (or (nil? d) (not (realized? d)))
     (try
       (let [x' (unwrap x)]

         (if (deferred? x')

           (let [d (or d (deferred))]
             (on-realized x'
                          #(chain- d % f)
                          #(error! d %))
             d)

           (let [x'' (f x')]
             (if (deferrable? x'')
               (chain- d x'')
               (if (nil? d)
                 (success-deferred x'')
                 (success! d x''))))))
       (catch js/Error e
         (if (nil? d)
           (error-deferred e)
           (error! d e))))
     d))
  ([d x f & fs]
   (when (or (nil? d) (not (realized? d)))
     (let [d (or d (deferred))]
       (clojure.core/loop [x x, fs (list* f fs)]
         (if (empty? fs)
           (success! d x)
           (let [[f & fs] fs
                 d' (deferred)
                 _ (chain- d' x f)]
             (success-error-unrealized d
               val (recur val fs)
               err (error! d err)
               (on-realized d'
                            #(apply chain- d % fs)
                            #(error! d %))))))
       d))))

;; - removed inline block
;; - less unrolled arities
(defn chain'
  "Like `chain`, but does not coerce deferrable values.  This is useful when
  coercion is undesired."
  ([x]
    (chain'- nil x identity))
  ([x f]
    (chain'- nil x f))
  ([x f & fs]
    (apply chain'- nil x f fs)))

;; - removed inline block
;; - less unrolled arities
(defn chain
  "Composes functions, left to right, over the value `x`, returning a deferred containing
   the result.  When composing, either `x` or the returned values may be values which can
   be converted to a deferred, causing the composition to be paused.

   The returned deferred will only be realized once all functions have been applied and their
   return values realized.

       @(chain 1 inc #(async (inc %))) => 3

       @(chain (success-deferred 1) inc inc) => 3

   "
  ([x]
   (chain- nil x identity))
  ([x f]
   (chain- nil x f))
  ([x f & fs]
   (apply chain- nil x f fs)))

;; - Throwable -> js/Error
(defn catch'
  "Like `catch`, but does not coerce deferrable values."
  ([x error-handler]
     (catch' x nil error-handler))
  ([x error-class error-handler]
   (let [x (chain' x)
         catch? #(or (nil? error-class) (instance? error-class %))]
     (if-not (deferred? x)

       ;; not a deferred value, skip over it
       x

       (success-error-unrealized x
         val x

         err (try
               (if (catch? %)
                 (chain' (error-handler err))
                 (error-deferred err))
               (catch js/Error e
                 (error-deferred e)))

         (let [d' (deferred)]

           (on-realized x
                        #(success! d' %)
                        #(try
                           (if (catch? %)
                             (chain'- d' (error-handler %))
                             (chain'- d' (error-deferred %)))
                           (catch js/Error e
                             (error! d' e))))

           d'))))))

;; - Throwable -> js/Error
(defn catch
  "An equivalent of the catch clause, which takes an `error-handler` function that will be invoked
   with the exception, and whose return value will be yielded as a successful outcome.  If an
   `error-class` is specified, only exceptions of that type will be caught.  If not, all exceptions
   will be caught.

       (-> d
         (chain f g h)
         (catch MyError #(str \"oh no: \" (.getMessage %)))
         (catch         #(str \"something unexpected: \" (.getMessage %))))

    "
  ([x error-handler]
    (catch x nil error-handler))
  ([x error-class error-handler]
     (if-let [d (->deferred x nil)]
       (-> d
         chain
         (catch' error-class error-handler)
         chain)
       x)))

;; - Throwable -> js/Error
(defn finally'
  "Like `finally`, but doesn't coerce deferrable values."
  [x f]
  (success-error-unrealized x

     val (try
           (f)
           x
           (catch js/Error e
             (error-deferred e)))

     err (try
           (f)
           (error-deferred err)
           (catch js/Error e
             (error-deferred e)))

     (let [d (deferred)]
       (on-realized x
         #(try
            (f)
            (success! d %)
            (catch js/Error e
              (error! d e)))
         #(try
            (f)
            (error! d %)
            (catch js/Error e
              (error! d e))))
       d)))

;; same
(defn finally
  "An equivalent of the finally clause, which takes a no-arg side-effecting function that executes
   no matter what the result."
  [x f]
  (if-let [d (->deferred x nil)]
    (finally' d f)
    (finally' x f)))

;; - remove type tags
;; - AtomicInteger -> volatile
(defn zip'
  "Like `zip`, but only unwraps Manifold deferreds."
  [& vals]
  (let [cnt (count vals)
        ary (object-array cnt)
        counter (volatile! cnt)]
    (clojure.core/loop [d nil, idx 0, s vals]

      (if (empty? s)

        ;; no further results, decrement the counter one last time
        ;; and return the result if everything else has been realized
        (if (zero? @counter)
          (success-deferred (or (seq ary) (list)))
          d)

        (let [x (first s)
              rst (rest s)
              idx' (unchecked-inc idx)]
          (if (deferred? x)

            (success-error-unrealized x

              val (do
                    (aset ary idx val)
                    (vswap! counter dec)
                    (recur d idx' rst))

              err (error-deferred err)

              (let [d (or d (deferred))]
                (on-realized (chain' x)
                  (fn [val]
                    (aset ary idx val)
                    (when (zero? (vswap! counter dec))
                      (success! d (seq ary))))
                  (fn [err]
                    (error! d err)))
                (recur d idx' rst)))

            ;; not deferred - set, decrement, and recur
            (do
              (aset ary idx x)
              (vswap! counter dec)
              (recur d idx' rst))))))))

;; - remove inline block
(defn zip
  "Takes a list of values, some of which may be deferrable, and returns a deferred that will yield a list
   of realized values.

        @(zip 1 2 3) => [1 2 3]
        @(zip (async 1) 2 3) => [1 2 3]

  "
  [& vals]
  (->> vals
    (map #(or (->deferred % nil) %))
    (apply zip')))

;; https://github.com/ztellman/manifold/pull/102
;; not in Manifold proper
;; same technique as clojure.core.async/random-array
;; same as clj
;; - set zero element to zero (JS arrays have nils by default)
(defn- random-array [n]
  (let [a (int-array n)]
    (aset a 0 0)
    (clojure.core/loop [i 1]
      (if (= i n)
        a
        (let [j (rand-int (inc i))]
          (aset a i (aget a j))
          (aset a j i)
          (recur (inc i)))))))

;; same as clj
(defn alt'
  "Like `alt`, but only unwraps Manifold deferreds."
  [& vals]
  (let [d (deferred)
        cnt (count vals)
        ^ints idxs (random-array cnt)]
    (clojure.core/loop [i 0]
      (when (< i cnt)
        (let [i' (aget idxs i)
              x (nth vals i')]
          (if (deferred? x)
            (success-error-unrealized x
              val (success! d val)
              err (error! d err)
              (do (on-realized (chain' x)
                    #(success! d %)
                    #(error! d %))
                  (recur (inc i))))
            (success! d x)))))
    d))

(defn alt
  "Takes a list of values, some of which may be deferrable, and returns a
  deferred that will yield the value which was realized first.

    @(alt 1 2) => 1
    @(alt (future (Thread/sleep 1) 1)
          (future (Thread/sleep 1) 2)) => 1 or 2 depending on the thread scheduling

  Values appearing earlier in the input are preferred."
  [& vals]
  (->> vals
       (map #(or (->deferred % nil) %))
       (apply alt')))

;; - TimeoutException -> ex-info
(defn timeout!
  "Takes a deferred, and sets a timeout on it, such that it will be realized as `timeout-value`
   (or a TimeoutException if none is specified) if it is not realized in `interval` ms.  Returns
   the deferred that was passed in.

   This will act directly on the deferred value passed in.  If the deferred represents a value
   returned by `chain`, all actions not yet completed will be short-circuited upon timeout."
  ([d interval]
    (cond
      (or (nil? interval) (not (deferred? d)) (realized? d))
      nil

      (not (pos? interval))
      (error! d
        (ex-info
          (str "timed out after " interval " milliseconds") {}))

      :else
      (time/in interval
        #(error! d
           (ex-info
             (str "timed out after " interval " milliseconds") {}))))
    d)
  ([d interval timeout-value]
    (cond
      (or (nil? interval) (not (deferred? d)) (realized? d))
      nil

      (not (pos? interval))
      (success! d timeout-value)

      :else
      (time/in interval #(success! d timeout-value)))
    d))

;; same
(deftype Recur [s]
  IDeref
  (-deref [_] s))

;; same
(defn recur
  "A special recur that can be used with `manifold.deferred/loop`."
  [& args]
  (Recur. args))

;; cljs specific
(defn time* [deferred-fn]
  (let [start (system-time)
        announce #(prn (str "Elapsed time: "
                            (.toFixed (- (system-time) start) 6)
                            " msecs"))
        d (deferred-fn)]
    (on-realized d announce announce)
    d))

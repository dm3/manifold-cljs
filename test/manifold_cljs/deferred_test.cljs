(ns manifold-cljs.deferred-test
  (:require [manifold-cljs.deferred :as d]
            [manifold-cljs.test-util :refer [later capture-success capture-error]]
            [cljs.test :refer [deftest testing is async]]))

(deftest test-deferred-success
  (async done
    (let [d (d/deferred)]
      (is (= true (d/success! d 1)))
      (is (= 1 @d))

      (let [callback-result (capture-success d)]
        (later
          (is (= 1 @callback-result))
          (done))))))

(deftest test-deferred-error
  (async done
    (let [d (d/deferred)
          ex (js/Error. "boom")]
      (is (= true (d/error! d ex)))
      (is (thrown? js/Error @d))

      (let [callback-result (capture-error d ::ok)]
        (later
          (is (= ex @callback-result))
          (done))))))

(deftest test-chain
  (async done
         (is (= 0 @(d/chain 0)))
         (done))

  (async done
         (is (= 1 @(d/chain 0 inc)))
         (done))

  (async done
         (is (= 1 @(d/chain 0 (constantly 1))))
         (done)))

(deftest test-catch
  (async done
         (let [d (-> 0
                     (d/chain #(throw (js/Error. "boom")))
                     (d/catch js/Number (constantly :foo)))]
           (later (is (thrown-with-msg? js/Error #"boom" @d))
                  (done))))

  (async done
         (let [d (-> 0
                     (d/chain #(throw (js/Error. "boom")))
                     (d/catch (constantly :foo)))]
           (later (is (= :foo @d))
                  (done)))))

(deftest test-chain-errors
  (async done
    (let [boom (fn [n] (throw (ex-info "" {:n n})))]
      (doseq [b [boom (fn [n] (d/future (boom n)))]]
        (dorun
          (for [i (range 10)
                j (range 10)]
            (let [fs (concat (repeat i inc) [boom] (repeat j inc))
                  a (-> (apply d/chain 0 fs)
                        (d/catch (fn [e] (:n (ex-data e)))))
                  b (-> (apply d/chain' 0 fs)
                        (d/catch' (fn [e] (:n (ex-data e)))))]
              (later (is (= i @a @b))))))))
    (done)))

(deftest test-callbacks
  (testing "listener on success"
    (async done
           (let [d (d/deferred)
                 result (atom nil)
                 f #(reset! result %)
                 l (d/listener f f)]
             (d/add-listener! d l)
             (d/success! d ::done)
             (later
               (is (= ::done @result))
               (done)))))

  (testing "listener on error"
    (async done
           (let [d (d/deferred)
                 result (atom nil)
                 f #(reset! result %)
                 l (d/listener f f)]
             (d/add-listener! d l)
             (d/error! d ::error)
             (later
               (is (= ::error @result))
               (done)))))

  (testing "removes listener"
    (async done
           (let [d (d/deferred)
                 result (atom nil)
                 f #(reset! result %)
                 l (d/listener f f)]
             (d/add-listener! d l)
             (d/cancel-listener! d l)
             (d/success! d ::done)
             (later
               (is (nil? @result))
               (done)))))

  (testing "executes listeners in order"
    (async done
           (let [d (d/deferred)
                 result (atom [])
                 f (fn [v] (fn [_] (swap! result conj v)))
                 l1 (d/listener (f 1) (f 1))
                 l2 (d/listener (f 2) (f 2))]
             (d/add-listener! d l1)
             (d/add-listener! d l2)
             (d/success! d ::done)
             (later
               (is (= [1 2] @result))
               (done)))))

  (testing "removes all identical listeners"
    (async done
           (let [d (d/deferred)
                 result (atom [])
                 f (fn [v] (fn [_] (swap! result conj v)))
                 l1 (d/listener (f 1) (f 1))
                 l2 (d/listener (f 2) (f 2))]
             (d/add-listener! d l1)
             (d/add-listener! d l2)
             (d/add-listener! d l1)
             (d/cancel-listener! d l1)
             (d/success! d ::done)
             (later
               (is (= [2] @result))
               (done))))))

(deftest test-alt
  (is (#{1 2 3} @(d/alt 1 2 3)))

  (testing "deferred result"
    (try (async done
           (try (let [d (d/alt (d/future 1) 2)]
             (println ">>>>>>" d)
             (later (println "!!!!!" d) (is (= 3 @d))
                    (done)))
                (catch js/Error e
                  (println "!!!!!!" e)
                  (done))))
         (catch js/Error e
           (println "???" e))))

  (async done
         (let [d (d/alt (d/timeout! (d/deferred) 10) 2)]
           (later (is (= 3 @d))
                  (done))))

  (async done
         (let [d (d/alt (d/error-deferred (js/Error. "boom"))
                        (d/timeout! (d/deferred) 10 1))]
           (is (thrown-with-msg? js/Error #"boom" @d)
               (done))))

  (testing "uniformly distributed"
    (let [results (atom {})
          ;; within 10%
          n 1e4, r 10, eps (* n 0.1)
          f #(/ (% n eps) r)]
      (dotimes [_ n]
        @(d/chain (apply d/alt (range r))
                  #(swap! results update % (fnil inc 0))))
      (doseq [[i times] @results]
        (is (<= (f -) times (f +)))))))

(deftest test-loop
  ;; body produces a non-deferred value
  (async done
         (let [result (capture-success
                        (d/loop [] true))]
           (later
             (is (true? @result))
             (done))))

  (async done
         ;; body raises exception
         (let [ex (js/Error.)
               result (capture-error
                        (d/loop [] (throw ex)))]
           (later (is (= ex @result))
                  (done)))))

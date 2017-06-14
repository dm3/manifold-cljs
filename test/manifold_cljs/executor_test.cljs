(ns manifold-cljs.executor-test
  (:require [manifold-cljs.executor :as ex]
            [manifold-cljs.test-util :refer [later]]
            [cljs.test :refer [deftest testing is async]]))

(deftest test-executor-timeout
  (async done
         (let [a (atom nil)
               e (ex/timeout-executor 50)]
           (ex/execute e #(reset! a ::done))
           (is (not @a))
           (later
             (is (not @a))
             (js/setTimeout
               (fn []
                 (is (= ::done @a))
                 (done))
               80)))))

(deftest test-executor-next-tick
  (async done
         (let [a (atom nil)
               e (ex/next-tick-executor)]
           (ex/execute e #(reset! a ::done))
           (is (not @a))
           (later
             (is (= ::done @a))
             (done)))))

(deftest test-executor-sync
  (let [a (atom nil)
        e (ex/sync-executor)]
    (ex/execute e #(reset! a ::done))
    (is (= ::done @a))))

(deftest with-executor-test
  (let [e (ex/sync-executor)]
    (is (not= (ex/executor) e))
    (ex/with-executor e
      (is (= (ex/executor) e)))
    (is (not= (ex/executor) e))))

(ns manifold-cljs.bus-test
  (:require [manifold-cljs.stream :as s]
            [manifold-cljs.stream.core :as core]
            [manifold-cljs.deferred :as d]
            [manifold-cljs.bus :as b]
            [manifold-cljs.test-util :refer [later unrealized?]]
            [cljs.test :refer [deftest testing is async]]))

(deftest test-bus-default
  (async done
         (testing "doesn't put without subscribers"
           (let [b (b/event-bus)
                 p1 (b/publish! b :test "hi 1")]
             (is (not (b/active? b :test)))
             (is (nil? (b/downstream b :test)))
             (is (false? @p1))

             (testing "puts with a single subscriber"
               (let [s1 (b/subscribe b :test)
                     p2 (b/publish! b :test "hi 2")]
                 (is (b/active? b :test))
                 (is (= 1 (count (b/downstream b :test))))
                 (is (unrealized? p2))
                 (is (= "hi 2" @(s/take! s1)))
                 (later
                   (is (true? @p2))

                   (testing "two subscribers on same topic"
                     (let [s2 (b/subscribe b :test)
                           p3 (b/publish! b :test "hi 3")]
                       (is (= 2 (count (b/downstream b :test))))
                       (is (= "hi 3" @(s/take! s1)))
                       (is (= "hi 3" @(s/take! s2)))
                       (is (unrealized? p3))
                       (later
                         (is (true? @p3))

                         (testing "another subscription isn't active"
                           (is (not (b/active? b :another))))

                         (testing "removes subscriptions on stream close"
                           (s/close! s1)
                           (s/close! s2)
                           ;; still active as on-closed will run in a later task
                           (is (b/active? b :test))
                           (later
                             (is (not (b/active? b :test)))
                             (done)))))))))))))

(ns manifold-cljs.stream.seq-test
  (:require [manifold-cljs.stream :as s]
            [manifold-cljs.stream.core :as core]
            [manifold-cljs.deferred :as d]
            [manifold-cljs.test-util :refer [later]]
            [cljs.test :refer [deftest testing is async]]

            [manifold-cljs.stream.seq :as sq]))

(deftest seq-stream
  (testing "lazy ok"
    (let [c (range 2)
          src (sq/to-source c)]
      (is (= 0 @(s/take! src)))
      (is (= 1 @(s/take! src)))
      (is (nil? @(s/take! src)))
      (is (s/drained? src))))

  (testing "lazy failing"
    (let [c (filter #(throw (js/Error.)) (range 100))
          src (sq/to-source c)]
      (is (= ::fail @(s/take! src ::fail)))
      (is (s/drained? src))))

  (testing "eager"
    (let [c [0 1]
          src (sq/to-source c)]
      (is (= 0 @(s/take! src)))
      (is (= 1 @(s/take! src)))
      (is (nil? @(s/take! src)))
      (is (s/drained? src)))))

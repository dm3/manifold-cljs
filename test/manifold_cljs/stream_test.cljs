(ns manifold-cljs.stream-test
  (:require [manifold-cljs.stream :as s]
            [manifold-cljs.stream.core :as core]
            [manifold-cljs.deferred :as d]
            [manifold-cljs.time :as t]
            [manifold-cljs.test-util :refer [later no-success?]]
            [cljs.test :refer [deftest testing is async]]))

(deftest test-default-stream
  (testing "stream"
    (let [s (s/stream)]
      (is (s/sinkable? s))
      (is (s/sourceable? s))
      (is (= (s/->sink s) s))
      (is (= (s/->source s) s))

      (is (empty? (s/downstream s)))
      (is (not (s/synchronous? s)))))

  (testing "stream proxy"
    (let [s (s/stream)
          sink (s/sink-only s)
          source (s/source-only s)]
      (is (s/sink? s))
      (is (s/source? s))
      (is (not= sink source))

      (is (s/sink? sink))
      (is (not (s/source? sink)))

      (is (s/source? source))
      (is (not (s/sink? source)))))

  (testing "put then take"
    (let [s (s/stream)
          p (s/put! s 1)]
      (is (no-success? p))

      (let [t (s/take! s)]
        (is (= 1 @t))
        (is (true? @p)))))

  (testing "take then put"
    (let [s (s/stream)
          t (s/take! s)]
      (is (no-success? t))

      (let [p (s/put! s 1)]
        (is (true? @p))
        (is (= 1 @t)))))

  (testing "closes and drains"
    (testing "empty stream"
      (let [s (s/stream)
            status (atom {:closed? false, :drained? false})]
        (s/on-closed s #(swap! status assoc :closed? true))
        (s/on-drained s #(swap! status assoc :drained? true))

        (is (not (s/closed? s)))

        (s/close! s)
        (is (s/closed? s))
        (is (s/drained? s))
        (is (= @status {:closed? false, :drained? false}))

        (later
          (= @status {:closed? true, :drained? true}))))

    (testing "non-empty stream"
      (let [s (s/stream)
            p (s/put! s 1)]
        (s/close! s)

        (is (s/closed? s))
        (is (not (s/drained? s)))

        (is (= 1 @(s/take! s)))
        (is (s/drained? s)))))

  (testing "buffered"
    (let [s (s/stream 1)]
      (is (true? @(s/put! s 1)))
      (is (no-success? (s/put! s 2)))

      (is (= 1 @(s/take! s)))
      (is (= 2 @(s/take! s))))))

(deftest test-stream-xform
  (async done
         (let [s (s/stream 0 (mapcat #(vector % % %)))]
           (s/put! s 1)
           (let [t1 (s/take! s), t2 (s/take! s)]
             (later
               (is (= 1 @t1))
               (is (= 1 @t2))
               (done))))))

(deftest test-put-all
  (async done
         (let [s (s/stream)
               p (s/put-all! s [1 2])
               t1 (s/take! s), t2 (s/take! s)]
           (is (= 1 @t1))
           (is (no-success? t2))
           (later
             (is (= 2 @t2))
             (done)))))

(deftest test-try-take
  (async done
         (let [s (s/stream)
               t1 (s/try-take! s ::none 500 ::timeout)]
           (t/in 600
                 (fn []
                   (do
                     (is (= ::timeout @t1))
                     (done)))))))

(deftest test-async-propagate
  (testing "new takes propagate asynchronously"
    (async done
           (let [a (s/stream), b (s/stream)]
             (s/connect a b)
             (let [p (s/put! a 1), t (s/take! b 1)]
               (is (true? @p))
               (is (no-success? t))
               (later
                 (is (= 1 @t))
                 (done)))))))

(deftest test-propagate-buffered
  (testing "propagates with buffered streams"
    (async done
           (let [a (s/stream 1), b (s/stream 1)]
             (s/connect a b)
             (is (true? @(s/put! a 1)))
             (is (true? @(s/put! a 2)))
             (let [t1 (s/take! b), t2 (s/take! b)]
               (later
                 (is (= 1 @t1))
                 (is (= 2 @t2))
                 (done)))))))

(deftest test-close-downstream
  (testing "closes all downstreams when single upstream closed"
    (async done
           (let [a (s/stream), b (s/stream), c (s/stream)]
             (s/connect a b)
             (s/connect a c)

             (is (not (s/closed? a)))
             (is (not (s/closed? b)))
             (is (not (s/closed? c)))

             (s/close! a)
             (is (s/closed? a))
             (is (not (s/closed? b)))
             (is (not (s/closed? c)))
             (later
               (is (s/closed? b))
               (is (s/closed? c))
               (done))))))

(deftest test-not-close-downstream
  (testing "doesn't close downstream when connected with downstream? = false"
    (async done
           (let [a (s/stream), b (s/stream)]
             (s/connect a b {:downstream? false})

             (s/close! a)
             (later
               (is (not (s/closed? b)))
               (done))))))

(deftest test-existing-propagate
  (testing "existing puts/take propagate synchronously after connect"
    (let [a (s/stream), b (s/stream)
          p (s/put! a 1), t (s/take! b 1)]
      (s/connect a b)
      (is (true? @p))
      (is (= 1 @t)))))

(deftest test-consume
  (async done
         (let [a (s/stream)
               result (atom [])
               consume-result (s/consume #(swap! result conj %) a)]
           (s/put! a 1)
           (s/put! a 2)
           (s/close! a)
           (is (= @result []))
           (is (no-success? consume-result))
           (later
             (is (= @result [1 2]))
             (later
               (is (true? @consume-result))
               (done))))))

(deftest test-async-consume
  (async done
         (let [a (s/stream)
               result (atom [])
               consume-result (s/consume-async
                                #(do (swap! result conj %)
                                     (d/success-deferred (= (count @result) 1))) a)]
           (s/put! a 1)
           (s/put! a 2)
           (is (= @result []))
           (later
             (is (s/closed? a))
             (is (= @result [1 2]))
             (later
               (is (true? @consume-result))
               (done))))))

(deftest test-connect-via
  (async done
         (let [src (s/stream), dst (s/stream)
               result (atom [])
               done? (s/connect-via src #(do (swap! result conj %) (s/put! dst %)) dst)]
           (s/put! src 1)
           (s/put! src 2)
           (s/close! src)
           (let [t1 (s/take! dst), t2 (s/take! dst)]
             (later
               (is (= [1 2] @result [@t1 @t2]))
               (later
                 (is (s/closed? dst))
                 (is (true? @done?))
                 (done)))))))

(deftest test-connect-via-proxy
  (async done
         (let [src (s/stream), prx (s/stream), dst (s/stream)
               done? (s/connect-via-proxy src prx dst)]
           (s/put! src 1)
           (s/put! src 2)
           (s/close! src)
           (let [t1 (s/take! prx), t2 (s/take! prx)]
             (later
               (is (= [1 2] [@t1 @t2]))
               (later
                 (is (s/closed? prx))
                 (is (true? @done?))
                 (done)))))))

(deftest test-drain-into
  (async done
         (let [src (s/stream), dst (s/stream)
               done? (s/drain-into src dst)]
           (s/put! src 1)
           (s/put! src 2)
           (s/close! src)

           (let [t1 (s/take! dst), t2 (s/take! dst)]
             (later
               (is (= [1 2] [@t1 @t2]))
               (later
                 (is @done?)
                 (is (not (s/closed? dst)))
                 (done)))))))

(deftest test-periodically-initial-delay
  (testing "initial delay set below period when not provided explicitly"
    (async done
           (let [result (atom 0)
                 period 50
                 s (s/periodically period #(swap! result inc))]
             (s/consume identity s)
             (t/in period
                   #(do (is (= @result 1))
                        (done)))))))

(deftest test-periodically
  (async done
         (let [result (atom 0)
               period 50, init-delay 25
               s (s/periodically init-delay period #(swap! result inc))]
             (s/consume identity s)
           (t/in (+ init-delay (* 2 period))
                 #(do (is (= @result 3))
                      (done))))))

(deftest test-transform
  (async done
         (let [s (s/stream)
               r (s/transform (map inc) s)]
           (s/put! s 1)
           (let [t (s/take! r)]
             (later (is (= 2 @t))
                    (done))))))

(deftest test-map
  (async done
         (let [s (s/stream)
               r (s/map inc s)]
           (s/put! s 1)
           (let [t (s/take! r)]
             (later
               (is (= 2 @t))
               (done))))))

(deftest test-realize-each
  (async done
         (let [s (s/stream)
               r (s/realize-each s)
               d (d/deferred)]
           (s/put! s d)
           (s/close! s)
           (let [t (s/take! r)]
             (d/success! d 1)
             (later
               (is (= 1 @t))
               (later
                 (is (s/drained? r))
                 (done)))))))

(deftest test-zip
  (async done
         (let [s1 (s/stream), s2 (s/stream)
               r (s/zip s1 s2)]
           (s/put! s1 1)
           (let [t (s/take! r)]
             (later
               (is (no-success? t))
               (s/put! s2 'x)
               (s/close! s1)
               (s/close! s2)
               (later
                 (is (= [1 'x] @t))
                 (later
                   (is (s/drained? r))
                   (done))))))))

(deftest test-filter
  (async done
         (let [s (s/stream)
               r (s/filter even? s)]
           (s/put! s 1)
           (s/put! s 2)
           (s/close! s)
           (let [t (s/take! r)]
             (later
               (is (= 2 @t))
               (later
                 (is (s/drained? r))
                 (done)))))))

(deftest test-reductions
  (async done
         (let [s (s/stream)
               r (s/reductions (fn [[a acc] b] [(+ a acc) (+ a b)]) [0 1] s)]
           (s/put! s 1)
           (s/put! s 2)
           (s/close! s)
           (let [t1 (s/take! r), t2 (s/take! r), t3 (s/take! r)]
             (is (= [0 1] @t1))
             (later
               (is (= [1 1] @t2))
               (is (= [2 3] @t3))
               (later
                 (is (s/drained? r))
                 (done)))))))

(deftest test-reduce
  (async done
         (let [s (s/stream)
               r (s/reduce + 0 s)]
           (s/put! s 1)
           (s/put! s 2)
           (s/close! s)
           (later
             (is (= 3 @r))
             (done)))))

(deftest test-reduce-reduced
  (async done
         (let [inputs (range 10)
               accf (fn [acc el]
                      (if (= el 5) (reduced :large) el))
               s (s/->source inputs)
               result (s/reduce accf 0 s)]
           (later
             (is (= :large (reduce accf 0 inputs) @result))
             (is (not (s/drained? s)))
             (let [t (s/try-take! s 1)]
               (later
                 (is (= 6 @t))
                 (done)))))))

(deftest test-mapcat
  (async done
         (let [s (s/stream)
               r (s/mapcat #(vector % %) s)]
           (s/put! s 1)
           (s/close! s)
           (let [t1 (s/take! r), t2 (s/take! r)]
             (later
               (is (= 1 @t1))
               (is (= 1 @t2))
               (later
                 (is (s/drained? r))
                 (done)))))))

(deftest test-lazily-partition-by
  (async done
         (let [s (s/stream)
               r (s/lazily-partition-by even? s)]
           (s/put! s 1)
           (s/put! s 3)
           (s/put! s 2)
           (s/close! s)
           (let [t1 (s/take! r), t2 (s/take! r)]
             (later
               (let [r1 @t1]
                 (is (s/source? r1))
                 (let [x1 (s/take! r1), x2 (s/take! r1)]
                   (is (= 1 @x1))
                   (later
                     (is (s/drained? s))
                     (is (= 3 @x2))
                     (let [r2 @t2]
                       (is (= 2 @(s/take! r2)))
                       (done))))))))))

(deftest test-concat
  (async done
         (let [s (s/stream), r (s/concat s)
               s1 (s/stream), s2 (s/stream)]
           (s/put! s1 1)
           (s/put! s2 2)
           (s/put! s s1)
           (s/put! s s2)
           (s/close! s1)
           (s/close! s2)
           (s/close! s)
           (let [t1 (s/take! r), t2 (s/take! r)]
             (later
               (is (= 1 @t1))
               (is (= 2 @t2))
               (later
                 (is (s/drained? s))
                 (done)))))))

(deftest test-buffered-stream
  (async done
         (let [s (s/buffered-stream (constantly 2) 5)
               p1 (s/put! s 1), p2 (s/put! s 2), p3 (s/put! s 3)]
           (is @p1)
           (is @p2)
           (is (no-success? p3))
           ;; buffered-stream has an infinite-buffer stream backing it
           ;; so all the takes complete immediately if one disregards
           ;; the backpressure on the `put!`s.
           (is (= 1 @(s/take! s)))
           (is (= 2 @(s/take! s)))
           (is (= 3 @(s/take! s)))
           (later
             (is @p3)
             (done)))))

(deftest test-batch
  (testing "batch size"
    (async done
           (let [s (s/stream)
                 r (s/batch 2 s)]
             (s/put-all! s [1 2 3])
             (let [t1 (s/take! r), t2 (s/take! r)]
               ;; closing `s` here will close the Consumer associated with `t2`
               (later
                 (is (= [1 2] @t1))
                 (s/close! s)
                 (later
                   ;; on-closed callbacks are triggered
                   (later
                     (is (= [3] @t2))
                     (done)))))))))

(deftest test-throttle
  (async done
         (let [s (s/stream), r (s/throttle 2 s)]
           (s/put-all! s [1 2])
           (s/close! s)
           (let [t1 (s/take! r), t2 (s/take! r)]
             (later
               (is (= 1 @t1))
               (t/in 100
                 (fn []
                   (is (no-success? t2))
                   ;; 2 msg per second => 1 every ~500 ms
                   (t/in 500
                     (fn []
                       (is (= 2 @t2))
                       ;; wait for the throttle timeout to realize
                       (t/in 500
                         (fn []
                           (is (s/drained? r))
                           (done))))))))))))

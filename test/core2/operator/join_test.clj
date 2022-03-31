(ns core2.operator.join-test
  (:require [clojure.test :as t]
            [core2.operator.join :as join]
            [core2.test-util :as tu]
            [core2.types :as ty])
  (:import (core2 ICursor)
           (org.apache.arrow.vector.types.pojo Field Schema)))

(t/use-fixtures :each tu/with-allocator)

(def ^:private a-field (ty/->field "a" ty/bigint-type false))
(def ^:private b-field (ty/->field "b" ty/bigint-type false))
(def ^:private c-field (ty/->field "c" ty/bigint-type false))
(def ^:private d-field (ty/->field "d" ty/bigint-type false))
(def ^:private e-field (ty/->field "e" ty/bigint-type false))

(defn- run-join-test
  ([join-op left-blocks right-blocks]
   (run-join-test join-op left-blocks right-blocks {}))

  ([->join-cursor left-blocks right-blocks
    {:keys [left-fields right-fields
            left-join-cols
            right-join-cols]
     :or {left-fields [a-field], right-fields [b-field]
          left-join-cols ["a"], right-join-cols ["b"]}}]

   (let [left-col-names (->> left-fields (into #{} (map (comp symbol #(.getName ^Field %)))))
         right-col-names (->> right-fields (into #{} (map (comp symbol #(.getName ^Field %)))))]
     (with-open [left-cursor (tu/->cursor (Schema. left-fields) left-blocks)
                 right-cursor (tu/->cursor (Schema. right-fields) right-blocks)
                 ^ICursor join-cursor (->join-cursor tu/*allocator*
                                                     left-cursor left-join-cols left-col-names
                                                     right-cursor right-join-cols right-col-names)]

       (vec (tu/<-cursor join-cursor))))))

(t/deftest test-cross-join
  (letfn [(->cross-join-cursor [al lc _ljc _lcns rc _rjc _rcns]
            (join/->cross-join-cursor al lc rc))]
    (t/is (= [{{:a 12, :b 10, :c 1} 2,
               {:a 12, :b 15, :c 2} 2,
               {:a 0, :b 10, :c 1} 1,
               {:a 0, :b 15, :c 2} 1}
              {{:a 100, :b 10, :c 1} 1, {:a 100, :b 15, :c 2} 1}
              {{:a 12, :b 83, :c 3} 2, {:a 0, :b 83, :c 3} 1}
              {{:a 100, :b 83, :c 3} 1}]
             (->> (run-join-test ->cross-join-cursor
                                 [[{:a 12}, {:a 12}, {:a 0}]
                                  [{:a 100}]]
                                 [[{:b 10 :c 1}, {:b 15 :c 2}]
                                  [{:b 83 :c 3}]]
                                 {:right-fields [b-field c-field]})
                  (mapv frequencies))))

    (t/is (empty? (run-join-test ->cross-join-cursor
                                 [[{:a 12}, {:a 0}]
                                  [{:a 100}]]
                                 []))
          "empty input and output")))

(t/deftest test-equi-join
  (t/is (= [#{{:a 12, :b 12}}
            #{{:a 100, :b 100}
              {:a 0, :b 0}}]
           (->> (run-join-test join/->equi-join-cursor
                               [[{:a 12}, {:a 0}]
                                [{:a 100}]]
                               [[{:b 12}, {:b 2}]
                                [{:b 100} {:b 0}]])
                (mapv set))))

  (t/is (= [{{:a 12} 2}
            {{:a 100} 1, {:a 0} 1}]
           (->> (run-join-test join/->equi-join-cursor
                               [[{:a 12}, {:a 0}]
                                [{:a 12}, {:a 100}]]
                               [[{:a 12}, {:a 2}]
                                [{:a 100} {:a 0}]]
                               {:left-fields [a-field], :right-fields [a-field]
                                :left-join-cols ["a"], :right-join-cols ["a"]})
                (mapv frequencies)))
        "same column name")

  (t/is (empty? (run-join-test join/->equi-join-cursor
                               [[{:a 12}, {:a 0}]
                                [{:a 100}]]
                               []))
        "empty input")

  (t/is (empty? (run-join-test join/->equi-join-cursor
                               [[{:a 12}, {:a 0}]
                                [{:a 100}]]
                               [[{:b 10 :c 1}, {:b 15 :c 2}]
                                [{:b 83 :c 3}]]))
        "empty output"))

(t/deftest test-equi-join-multi-col
  (->> "multi column"
       (t/is (= [{{:a 12, :b 42, :c 12, :d 42, :e 0} 2}
                 {{:a 12, :b 42, :c 12, :d 42, :e 0} 4
                  {:a 12, :b 42, :c 12, :d 42, :e 1} 2}]
                (->> (run-join-test join/->equi-join-cursor
                                    [[{:a 12, :b 42}
                                      {:a 12, :b 42}
                                      {:a 11, :b 44}
                                      {:a 10, :b 42}]]
                                    [[{:c 12, :d 42, :e 0}
                                      {:c 12, :d 43, :e 0}
                                      {:c 11, :d 42, :e 0}]
                                     [{:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 1}]]
                                    {:left-fields [a-field b-field], :right-fields [c-field d-field e-field]
                                     :left-join-cols ["a" "b"], :right-join-cols ["c" "d"]})
                     (mapv frequencies))))))

(t/deftest test-semi-equi-join
  (t/is (= [{{:a 12} 2} {{:a 100} 1}]
           (->> (run-join-test join/->left-semi-equi-join-cursor
                               [[{:a 12}, {:a 12}, {:a 0}]
                                [{:a 100}]]
                               [[{:b 12}, {:b 2}]
                                [{:b 100}]])
                (mapv frequencies))))

  (t/testing "empty input"
    (t/is (empty? (run-join-test join/->left-semi-equi-join-cursor
                                 [[{:a 12}, {:a 0}]
                                  [{:a 100}]]
                                 [])))

    (t/is (empty? (run-join-test join/->left-semi-equi-join-cursor
                                 []
                                 [[{:b 12}, {:b 2}]
                                  [{:b 100} {:b 0}]])))

    (t/is (empty? (run-join-test join/->left-semi-equi-join-cursor
                                 [[{:a 12}, {:a 0}]
                                  [{:a 100}]]
                                 [[]]))))

  (t/is (empty? (run-join-test join/->left-semi-equi-join-cursor
                               [[{:a 12}, {:a 0}]
                                [{:a 100}]]
                               [[{:b 10 :c 1}, {:b 15 :c 2}]
                                [{:b 83 :c 3}]]))
        "empty output"))

(t/deftest test-semi-equi-join-multi-col
  (->> "multi column semi"
       (t/is (= [{{:a 12, :b 42} 2}]
                (->> (run-join-test join/->left-semi-equi-join-cursor
                                    [[{:a 12, :b 42}
                                      {:a 12, :b 42}
                                      {:a 11, :b 44}
                                      {:a 10, :b 42}]]
                                    [[{:c 12, :d 42, :e 0}
                                      {:c 12, :d 43, :e 0}
                                      {:c 11, :d 42, :e 0}]
                                     [{:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 1}]]
                                    {:left-fields [a-field b-field], :right-fields [c-field d-field]
                                     :left-join-cols ["a" "b"], :right-join-cols ["c" "d"]})
                     (mapv frequencies))))))

(t/deftest test-left-equi-join
  (t/is (= [{{:a 12, :b 12, :c 2} 1, {:a 12, :b 12, :c 0} 1, {:a 0, :b nil, :c nil} 1}
            {{:a 12, :b 12, :c 2} 1, {:a 100, :b 100, :c 3} 1, {:a 12, :b 12, :c 0} 1}]
           (->> (run-join-test join/->left-outer-equi-join-cursor
                               [[{:a 12}, {:a 0}]
                                [{:a 12}, {:a 100}]]
                               [[{:b 12, :c 0}, {:b 2, :c 1}]
                                [{:b 12, :c 2}, {:b 100, :c 3}]]
                               {:right-fields [b-field c-field]})
                (mapv frequencies))))

  (t/testing "empty input"
    (t/is (= [#{{:a 12, :b nil}, {:a 0, :b nil}}
              #{{:a 100, :b nil}}]
             (->> (run-join-test join/->left-outer-equi-join-cursor
                                 [[{:a 12}, {:a 0}]
                                  [{:a 100}]]
                                 [])
                  (mapv set))))

    (t/is (empty? (run-join-test join/->left-outer-equi-join-cursor
                                 []
                                 [[{:b 12}, {:b 2}]
                                  [{:b 100} {:b 0}]])))

    (t/is (= [#{{:a 12, :b nil}, {:a 0, :b nil}}
              #{{:a 100, :b nil}}]
             (->> (run-join-test join/->left-outer-equi-join-cursor
                                 [[{:a 12}, {:a 0}]
                                  [{:a 100}]]
                                 [[]])
                  (mapv set))))))

(t/deftest test-left-equi-join-multi-col
  (->> "multi column left"
       (t/is (= [{{:a 11, :b 44, :c nil, :d nil, :e nil} 1
                  {:a 10, :b 42, :c nil, :d nil, :e nil} 1
                  {:a 12, :b 42, :c 12, :d 42, :e 0} 6
                  {:a 12, :b 42, :c 12, :d 42, :e 1} 2}]
                (->> (run-join-test join/->left-outer-equi-join-cursor
                                    [[{:a 12, :b 42}
                                      {:a 12, :b 42}
                                      {:a 11, :b 44}
                                      {:a 10, :b 42}]]
                                    [[{:c 12, :d 42, :e 0}
                                      {:c 12, :d 43, :e 0}
                                      {:c 11, :d 42, :e 0}]
                                     [{:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 1}]]
                                    {:left-fields [a-field b-field], :right-fields [c-field d-field e-field]
                                     :left-join-cols ["a" "b"], :right-join-cols ["c" "d"]})
                     (mapv frequencies))))))

(t/deftest test-full-outer-join
  (t/testing "missing on both sides"
    (t/is (= [{{:a 12, :b 12, :c 0} 2, {:a nil, :b 2, :c 1} 1}
              {{:a 12, :b 12, :c 2} 2, {:a 100, :b 100, :c 3} 1}
              {{:a 0, :b nil, :c nil} 1}]
             (->> (run-join-test join/->full-outer-equi-join-cursor
                                 [[{:a 12}, {:a 0}]
                                  [{:a 12}, {:a 100}]]
                                 [[{:b 12, :c 0}, {:b 2, :c 1}]
                                  [{:b 12, :c 2}, {:b 100, :c 3}]]
                                 {:right-fields [b-field c-field]})
                  (mapv frequencies))))

    (t/is (= [{{:a 12, :b 12, :c 0} 2, {:a 12, :b 12, :c 2} 2, {:a nil, :b 2, :c 1} 1}
              {{:a 100, :b 100, :c 3} 1}
              {{:a 0, :b nil, :c nil} 1}]
             (->> (run-join-test join/->full-outer-equi-join-cursor
                                 [[{:a 12}, {:a 0}]
                                  [{:a 12}, {:a 100}]]
                                 [[{:b 12, :c 0}, {:b 12, :c 2}, {:b 2, :c 1}]
                                  [{:b 100, :c 3}]]
                                 {:right-fields [b-field c-field]})
                  (mapv frequencies)))))

  (t/testing "all matched"
    (t/is (= [{{:a 12, :b 12, :c 0} 2, {:a 100, :b 100, :c 3} 1}
              {{:a 12, :b 12, :c 2} 2}]
             (->> (run-join-test join/->full-outer-equi-join-cursor
                                 [[{:a 12}]
                                  [{:a 12}, {:a 100}]]
                                 [[{:b 12, :c 0}, {:b 100, :c 3}]
                                  [{:b 12, :c 2}]]
                                 {:right-fields [b-field c-field]})
                  (mapv frequencies))))

    (t/is (= [{{:a 12, :b 12, :c 0} 2, {:a 12, :b 12, :c 2} 2}
              {{:a 100, :b 100, :c 3} 1}]
             (->> (run-join-test join/->full-outer-equi-join-cursor
                                 [[{:a 12}]
                                  [{:a 12}, {:a 100}]]
                                 [[{:b 12, :c 0}, {:b 12, :c 2}]
                                  [{:b 100, :c 3}]]
                                 {:right-fields [b-field c-field]})
                  (mapv frequencies)))))

  (t/testing "empty input"
    (t/is (= [{{:a 0, :b nil} 1, {:a 100, :b nil} 1, {:a 12, :b nil} 1}]
             (->> (run-join-test join/->full-outer-equi-join-cursor
                                 [[{:a 12}, {:a 0}]
                                  [{:a 100}]]
                                 [])
                  (mapv frequencies))))

    (t/is (= [{{:a nil, :b 12} 1, {:a nil, :b 2} 1}
              {{:a nil, :b 100} 1, {:a nil, :b 0} 1}]
             (->> (run-join-test join/->full-outer-equi-join-cursor
                                 []
                                 [[{:b 12}, {:b 2}]
                                  [{:b 100} {:b 0}]])
                  (mapv frequencies))))))

(t/deftest test-full-outer-equi-join-multi-col
  (->> "multi column full outer"
       (t/is (= [{{:a 12 :b 42 :c 12 :d 42 :e 0} 2
                  {:a nil :b nil :c 11 :d 42 :e 0} 1
                  {:a nil :b nil :c 12 :d 43 :e 0} 1}
                 {{:a 12 :b 42 :c 12 :d 42 :e 0} 4
                  {:a 12 :b 42 :c 12 :d 42 :e 1} 2}
                 {{:a 10 :b 42 :c nil :d nil :e nil} 1
                  {:a 11 :b 44 :c nil :d nil :e nil} 1}]
                (->> (run-join-test join/->full-outer-equi-join-cursor
                                    [[{:a 12, :b 42}
                                      {:a 12, :b 42}
                                      {:a 11, :b 44}
                                      {:a 10, :b 42}]]
                                    [[{:c 12, :d 42, :e 0}
                                      {:c 12, :d 43, :e 0}
                                      {:c 11, :d 42, :e 0}]
                                     [{:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 1}]]
                                    {:left-fields [a-field b-field], :right-fields [c-field d-field e-field]
                                     :left-join-cols ["a" "b"], :right-join-cols ["c" "d"]})
                     (mapv frequencies))))))

(t/deftest test-anti-equi-join
  (t/is (= [{{:a 0} 2}]
           (->> (run-join-test join/->left-anti-semi-equi-join-cursor
                               [[{:a 12}, {:a 0}, {:a 0}]
                                [{:a 100}]]
                               [[{:b 12}, {:b 2}]
                                [{:b 100}]])
                (mapv frequencies))))

  (t/testing "empty input"
    (t/is (empty? (run-join-test join/->left-anti-semi-equi-join-cursor
                                 []
                                 [[{:b 12}, {:b 2}]
                                  [{:b 100}]])))

    (t/is (= [#{{:a 12}, {:a 0}}
              #{{:a 100}}]
             (->> (run-join-test join/->left-anti-semi-equi-join-cursor
                                 [[{:a 12}, {:a 0}]
                                  [{:a 100}]]
                                 [])
                  (mapv set)))))

  (t/is (empty? (run-join-test join/->left-anti-semi-equi-join-cursor
                               [[{:a 12}, {:a 2}]
                                [{:a 100}]]
                               [[{:b 12}, {:b 2}]
                                [{:b 100}]]))
        "empty output"))

(t/deftest test-anti-equi-join-multi-col
  (->> "multi column anti"
       (t/is (= [{{:a 10 :b 42} 1
                  {:a 11 :b 44} 1}]
                (->> (run-join-test join/->left-anti-semi-equi-join-cursor
                                    [[{:a 12, :b 42}
                                      {:a 12, :b 42}
                                      {:a 11, :b 44}
                                      {:a 10, :b 42}]]
                                    [[{:c 12, :d 42, :e 0}
                                      {:c 12, :d 43, :e 0}
                                      {:c 11, :d 42, :e 0}]
                                     [{:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 1}]]
                                    {:left-fields [a-field b-field], :right-fields [c-field d-field e-field]
                                     :left-join-cols ["a" "b"], :right-join-cols ["c" "d"]})
                     (mapv frequencies))))))
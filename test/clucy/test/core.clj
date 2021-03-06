(ns clucy.test.core
  (:use clucy.core
        clojure.test))

(def people [{:name "Miles" :age 36}
             {:name "Emily" :age 0.3}
             {:name "Joanna" :age 34}
             {:name "Melinda" :age 34}
             {:name "Mary" :age 48}
             {:name "Mary Lou" :age 39}])

(deftest core

  (testing "memory-index fn"
    (let [index (memory-index)]
      (is (not (nil? index)))))

  (testing "disk-index fn"
    (let [index (disk-index "/tmp/test-index")]
      (is (not (nil? index)))))

  (testing "add fn"
    (let [index (memory-index)]
      (doseq [person people] (add index person))
      (is (== 1 (count (search index "name:miles" 10))))))

  (testing "delete fn"
    (let [index (memory-index)]
      (doseq [person people] (add index person))
      (delete index (first people))
      (is (== 0 (count (search index "name:miles" 10))))))

  (testing "search fn"
    (let [index (memory-index)]
      (doseq [person people] (add index person))
      (is (== 1 (count (search index "name:miles" 10))))
      (is (== 1 (count (search index "name:miles age:100" 10))))
      (is (== 0 (count (search index "name:miles AND age:100" 10))))
      (is (== 0 (count (search index "name:miles age:100" 10 :default-operator :and))))))

  (testing "search-and-delete fn"
    (let [index (memory-index)]
      (doseq [person people] (add index person))
      (search-and-delete index "name:mary")
      (is (== 0 (count (search index "name:mary" 10))))))

  (testing "search fn with highlighting"
    (let [index (memory-index)
          config {:field :name}]
      (doseq [person people] (add index person))
      (is (= (map #(-> % meta :_fragments)
                  (search index "name:mary" 10 :highlight config))
             ["<b>Mary</b>" "<b>Mary</b> Lou"]))))

  (testing "search fn returns scores in metadata"
    (let [index (memory-index)
          _ (doseq [person people] (add index person))
          results (search index "name:mary" 10)]
      (is (true? (every? pos? (map (comp :_score meta) results))))
      (is (= 2 (:_total-hits (meta results))))
      (is (pos? (:_max-score (meta results))))
      (is (= (count people) (:_total-hits (meta (search index "*:*" 2)))))))

  (testing "search fn with empty index"
    (let [index (memory-index)
          results (search index "name:miles" 10)]
      (is (empty? results))))

  (testing "search fn with pagination"
    (let [index (memory-index)]
      (doseq [person people] (add index person))
      (is (= 1 (count (search index "age:34" 10 :limit 1))))
      (is (= 0 (count (search index "age:34" 1 :limit 10 :page 1))))
      (is (= 1 (count (search index "age:34" 10 :limit 1 :page 1))))
      (is (= 0 (count (search index "age:34" 10 :limit 2 :page 1)))))))

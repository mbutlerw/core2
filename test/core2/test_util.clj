(ns core2.test-util
  (:require [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [clojure.test :as t]
            [core2.api :as c2]
            [core2.client :as client]
            [core2.json :as c2-json]
            [core2.local-node :as node]
            [core2.logical-plan :as lp]
            core2.object-store
            [core2.operator :as op]
            [core2.temporal :as temporal]
            [core2.types :as types]
            [core2.util :as util]
            [core2.vector.indirect :as iv]
            [core2.vector.writer :as vw])
  (:import (core2 ICursor InstantSource)
           core2.local_node.Node
           core2.object_store.FileSystemObjectStore
           core2.vector.IIndirectVector
           java.net.ServerSocket
           (java.nio.file Files Path)
           java.nio.file.attribute.FileAttribute
           (java.time Duration Instant Period)
           (java.util LinkedList)
           java.util.concurrent.TimeUnit
           java.util.function.Consumer
           (org.apache.arrow.memory BufferAllocator RootAllocator)
           (org.apache.arrow.vector FieldVector ValueVector VectorSchemaRoot)
           org.apache.arrow.vector.complex.DenseUnionVector
           (org.apache.arrow.vector.types.pojo Schema)))

(def ^:dynamic ^org.apache.arrow.memory.BufferAllocator *allocator*)

(defn with-allocator [f]
  (with-open [allocator (RootAllocator.)]
    (binding [*allocator* allocator]
      (f))))

(def ^:dynamic ^:private *node-opts* {})
(def ^:dynamic *node*)

(defn with-opts
  ([opts] (partial with-opts opts))
  ([opts f]
   (binding [*node-opts* (merge *node-opts* opts)]
     (f))))

(defn with-node [f]
  (with-open [node (node/start-node *node-opts*)]
    (binding [*node* node]
      (f))))

(defn free-port ^long []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn with-http-client-node [f]
  (let [port (free-port)]
    (with-open [_ (node/start-node (-> *node-opts*
                                       (assoc-in [:core2/server :port] port)))]
      (binding [*node* (client/start-client (str "http://localhost:" port))]
        (f)))))

(def ^:dynamic *node-type*)

(defn with-each-api-implementation [api-implementations]
  (fn [f]
    (doseq [[node-type run-tests] api-implementations]
      (binding [*node-type* node-type]
        (t/testing (str node-type)
          (run-tests f))))))

(defn component
  ([k] (component *node* k))
  ([node k] (util/component node k)))

(defn then-await-tx
  (^core2.api.TransactionInstant [tx node]
   @(node/await-tx-async node tx))

  (^core2.api.TransactionInstant [tx node ^Duration timeout]
   @(-> (node/await-tx-async node tx)
        (.orTimeout (.toMillis timeout) TimeUnit/MILLISECONDS))))

(defn latest-completed-tx ^core2.api.TransactionInstant [node]
  (:latest-completed-tx (c2/status node)))

(defn ->mock-clock
  (^core2.InstantSource []
   (->mock-clock (iterate #(.plus ^Instant % (Period/ofDays 1))
                          (.toInstant #inst "2020-01-01"))))

  (^core2.InstantSource [^Iterable insts] (InstantSource/mock insts)))

(defn with-mock-clock [f]
  (with-opts {:core2.log/memory-log {:instant-src (->mock-clock)}} f))

(defn await-temporal-snapshot-build [^Node node]
  (.awaitSnapshotBuild ^core2.temporal.TemporalManagerPrivate (::temporal/temporal-manager @(:!system node))))

(defn finish-chunk [^Node node]
  (.finishChunk ^core2.indexer.Indexer (component node :core2.indexer/indexer))
  (await-temporal-snapshot-build node))

(defn write-vec! [^ValueVector v, vs]
  (.clear v)

  (let [duv? (instance? DenseUnionVector v)
        writer (vw/vec->writer v)]
    (doseq [v vs]
      (.startValue writer)
      (if duv?
        (doto (.writerForType (.asDenseUnion writer) (types/value->col-type v))
          (.startValue)
          (->> (types/write-value! v))
          (.endValue))

        (types/write-value! v writer))

      (.endValue writer))

    (.setValueCount v (count vs))

    v))

(defn open-vec
  (^org.apache.arrow.vector.ValueVector [col-name vs]
   (open-vec col-name
             (->> (into #{} (map types/value->col-type) vs)
                  (apply types/merge-col-types))
             vs))

  (^org.apache.arrow.vector.ValueVector [col-name col-type vs]
   (let [res (-> (types/col-type->field col-name col-type)
                 (.createVector *allocator*))]
     (try
       (doto res (write-vec! vs))
       (catch Throwable e
         (.close res)
         (throw e))))))

(defn populate-root ^core2.vector.IIndirectRelation [^VectorSchemaRoot root rows]
  (.clear root)

  (let [field-vecs (.getFieldVectors root)
        row-count (count rows)]
    (doseq [^FieldVector field-vec field-vecs]
      (write-vec! field-vec (map (keyword (.getName (.getField field-vec))) rows)))

    (.setRowCount root row-count)
    root))

(defn ->cursor
  (^core2.ICursor [^Schema schema, blocks] (->cursor *allocator* schema blocks))

  (^core2.ICursor [^BufferAllocator allocator ^Schema schema, blocks]
   (let [blocks (LinkedList. blocks)
         root (VectorSchemaRoot/create schema allocator)]
     (reify ICursor
       (tryAdvance [_ c]
         (if-let [block (some-> (.poll blocks) vec)]
           (do
             (populate-root root block)
             (.accept c (iv/<-root root))
             true)
           false))

       (close [_]
         (.close root))))))

(defmethod lp/ra-expr ::blocks [_]
  (s/cat :op #{::blocks}
         :col-types (s/? (s/map-of simple-symbol? some?))
         :blocks vector?))

(defmethod lp/emit-expr ::blocks [{:keys [col-types blocks]} _args]
  (let [col-types (or col-types
                      (let [rows (into [] cat blocks)]
                        (->> (for [col-name (into #{} (mapcat keys) rows)]
                               [(symbol col-name) (->> rows
                                                       (into #{} (map (fn [row]
                                                                        (types/value->col-type (get row col-name)))))
                                                       (apply types/merge-col-types))])
                             (into {}))))
        ^Schema schema (Schema. (for [[col-name col-type] col-types]
                                  (types/col-type->field col-name col-type)))]
    {:col-types col-types
     :->cursor (fn [{:keys [allocator]}]
                 (->cursor allocator schema blocks))}))

(defn <-column [^IIndirectVector col]
  (mapv (fn [idx]
          (types/get-object (.getVector col) (.getIndex col idx)))
        (range (.getValueCount col))))

(defn <-cursor [^ICursor cursor]
  (let [!res (volatile! (transient []))]
    (.forEachRemaining cursor
                       (reify Consumer
                         (accept [_ rel]
                           (vswap! !res conj! (iv/rel->rows rel)))))
    (persistent! @!res)))

(defn query-ra
  ([query] (query-ra query {}))
  ([query {:keys [srcs params preserve-blocks? with-col-types?] :as query-opts}]
   (with-open [pq (op/open-prepared-ra query)
               res (.openCursor pq (-> query-opts
                                       (dissoc :preserve-blocks? :with-col-types?)
                                       (assoc :srcs srcs, :params params)))]

     (let [rows (-> (<-cursor res)
                   (cond->> (not preserve-blocks?) (into [] cat)))]
       (if with-col-types?
         {:res rows, :col-types (.columnTypes res)}
         rows)))))

(t/deftest round-trip-cursor
  (with-allocator
    (fn []
      (let [blocks [[{:name "foo", :age 20}
                     {:name "bar", :age 25}]
                    [{:name "baz", :age 30}]]]
        (with-open [cursor (->cursor (Schema. [(types/col-type->field "name" :utf8)
                                               (types/col-type->field "age" :i64)])
                                     blocks)]

          (t/is (= blocks (<-cursor cursor))))))))

(defn check-json-file [^Path expected, ^Path actual]
  (t/is (= (json/parse-string (Files/readString expected))
           (json/parse-string (Files/readString actual)))
        actual))

(defn check-json [^Path expected-path, ^FileSystemObjectStore os]
  (let [^Path os-path (.root-path os)]

    (let [expected-file-count (.count (Files/list expected-path))]
      (t/is (= expected-file-count (.count (Files/list os-path))))
      (t/is (= expected-file-count (count (.listObjects os)))))

    (c2-json/write-arrow-json-files (.toFile os-path))

    (doseq [^Path path (iterator-seq (.iterator (Files/list os-path)))
            :when (.endsWith (str path) ".json")]
      (check-json-file (.resolve expected-path (.getFileName path)) path))))

(defn ->local-node ^core2.local_node.Node [{:keys [^Path node-dir ^String buffers-dir
                                                   max-rows-per-block max-rows-per-chunk]
                                            :or {buffers-dir "buffers"}}]
  (node/start-node {:core2.log/local-directory-log {:root-path (.resolve node-dir "log")
                                                    :instant-src (->mock-clock)}
                    :core2.tx-producer/tx-producer {:instant-src (->mock-clock)}
                    :core2.buffer-pool/buffer-pool {:cache-path (.resolve node-dir buffers-dir)}
                    :core2.object-store/file-system-object-store {:root-path (.resolve node-dir "objects")}
                    :core2/row-counts (->> {:max-rows-per-block max-rows-per-block
                                            :max-rows-per-chunk max-rows-per-chunk}
                                           (into {} (filter val)))}))

(defn ->local-submit-node ^core2.local_node.SubmitNode [{:keys [^Path node-dir]}]
  (node/start-submit-node {:core2.tx-producer/tx-producer {:clock (->mock-clock)}
                           :core2.log/local-directory-log {:root-path (.resolve node-dir "log")
                                                           :clock (->mock-clock)}}))

(defn with-tmp-dir* [prefix f]
  (let [dir (Files/createTempDirectory prefix (make-array FileAttribute 0))]
    (try
      (f dir)
      (finally
        (util/delete-dir dir)))))

(defmacro with-tmp-dirs
  "Usage:
    (with-tmp-dirs #{log-dir objects-dir}
      ...)"
  [[dir-binding & more-bindings] & body]
  (if dir-binding
    `(with-tmp-dir* ~(name dir-binding)
       (fn [~(vary-meta dir-binding assoc :tag 'java.nio.file.Path)]
         (with-tmp-dirs #{~@more-bindings}
           ~@body)))
    `(do ~@body)))

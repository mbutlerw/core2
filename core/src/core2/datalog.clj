(ns core2.datalog
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [core2.error :as err]
            [core2.logical-plan :as lp]
            [core2.operator :as op]
            [core2.vector.writer :as vw])
  (:import clojure.lang.MapEntry
           java.time.LocalDate
           java.lang.AutoCloseable
           org.apache.arrow.memory.BufferAllocator))

(s/def ::logic-var simple-symbol?)

;; TODO flesh out
(def ^:private eid? (some-fn string? number? inst? keyword? (partial instance? LocalDate)))

(s/def ::eid eid?)
(s/def ::value (some-fn eid?))

(s/def ::aggregate
  (s/cat :agg-fn simple-symbol?
         :param ::logic-var))

(s/def ::find-arg
  (s/or :logic-var ::logic-var
        :aggregate ::aggregate))

(s/def ::semi-join
  (s/cat :exists '#{exists?}
         :args ::args-list
         :terms (s/+ ::term)))

(s/def ::anti-join
  (s/cat :not-exists '#{not-exists?}
         :args ::args-list
         :terms (s/+ ::term)))

(s/def ::find (s/coll-of ::find-arg :kind vector? :min-count 1))
(s/def ::keys (s/coll-of symbol? :kind vector?))

(s/def ::args-list (s/coll-of ::logic-var, :kind vector?, :min-count 1))

(s/def ::binding
  (s/or :collection (s/tuple ::logic-var '#{...})
        :relation (s/tuple ::args-list)
        :scalar ::logic-var
        :tuple ::args-list))

(s/def ::source
  (s/and simple-symbol?
         (comp #(str/starts-with? % "$") name)))

(s/def ::in-binding
  (s/or :source ::source
        :collection (s/tuple ::logic-var '#{...})
        :relation (s/tuple ::args-list)
        :scalar ::logic-var
        :tuple ::args-list))

(s/def ::in (s/* ::in-binding))

(s/def ::triple
  (s/and vector?
         (s/conformer identity vec)
         (s/cat :e (s/or :literal ::eid, :logic-var ::logic-var)
                :a keyword?
                :v (s/? (s/or :literal ::value,
                              :logic-var ::logic-var
                              :unwind (s/tuple ::logic-var #{'...}))))))

(s/def ::fn-call
  (s/and list?
         (s/cat :f simple-symbol?
                :args (s/* ::form))))

(s/def ::form
  (s/or :logic-var ::logic-var
        :fn-call ::fn-call
        :value ::value))

(s/def ::call-clause
  (s/and vector?
         ;; top-level can only be a fn-call because otherwise the EDN Datalog syntax is ambiguous
         ;; (it wasn't ever meant to handle this...)
         (s/cat :form (s/spec (s/or :fn-call ::fn-call))
                :return (s/? ::binding))))

(s/def ::and (s/cat :and #{'and}
                    :terms (s/+ ::term)))

(s/def ::or-branches
  (s/+ (s/and (s/or :term ::term, :and ::and)
              (s/conformer (fn [[type arg]]
                             (case type
                               :term [arg]
                               :and (:terms arg)))
                           (fn [terms]
                             (if (= (count terms) 1)
                               [:term (first terms)]
                               [:and {:and 'and, :terms terms}]))))))

(s/def ::union-join
  (s/cat :union-join #{'union-join}
         :args ::args-list
         :branches ::or-branches))

(s/def ::sub-query
  (s/cat :q #{'q}, :query ::query))

(s/def ::term
  (s/or :triple ::triple
        :semi-join ::semi-join
        :anti-join ::anti-join
        :union-join ::union-join
        :call ::call-clause
        :sub-query ::sub-query))

(s/def ::where
  (s/coll-of ::term :kind vector? :min-count 1))

(s/def ::offset nat-int?)
(s/def ::limit nat-int?)

(s/def ::order-element
  (s/and vector?
         (s/cat :find-arg (s/or :logic-var ::logic-var
                                :aggregate ::aggregate)
                :direction (s/? #{:asc :desc}))))

(s/def ::order-by (s/coll-of ::order-element :kind vector?))

(s/def ::query
  (s/keys :req-un [::find]
          :opt-un [::keys ::in ::where ::order-by ::offset ::limit]))

(s/def ::relation-arg
  (s/or :maps (s/coll-of (s/map-of simple-keyword? any?))
        :vecs (s/coll-of vector?)))

(defn- conform-query [query]
  (let [conformed-query (s/conform ::query query)]
    (when (s/invalid? conformed-query)
      (throw (err/illegal-arg :malformed-query
                              {::err/message "Malformed query"
                               :query query
                               :explain (s/explain-data ::query query)})))
    conformed-query))

(declare plan-query)

(defn- col-sym
  ([col]
   (-> (symbol col) (vary-meta assoc :column? true)))
  ([prefix col]
   (col-sym (str (format "%s_%s" prefix col)))))

(defn- wrap-select [plan predicates]
  (-> (case (count predicates)
        0 plan
        1 [:select (first predicates) plan]
        [:select (list* 'and predicates) plan])
      (with-meta (meta plan))))

(defn- unify-preds [var->cols]
  ;; this enumerates all the binary join conditions
  ;; once mega-join has multi-way joins we could throw the multi-way `=` over the fence
  (->> (vals var->cols)
       (filter #(> (count %) 1))
       (mapcat
         (fn [cols]
           (->> (set (for [col cols
                           col2 cols
                           :when (not= col col2)]
                       (set [col col2])))
                (map #(list* '= %)))))
       (vec)))

(defn- wrap-unify [plan var->cols]
  (-> [:project (vec (for [[lv cols] var->cols]
                       (or (cols lv)
                           {(col-sym lv) (first cols)})))
       (-> plan
           (wrap-select (unify-preds var->cols)))]
      (with-meta (-> (meta plan) (assoc ::vars (set (keys var->cols)))))))

(defn- with-unique-cols [plans param-vars]
  (as-> plans plans
    (->> plans
         (into [] (map-indexed
                   (fn [idx plan]
                     (let [{::keys [vars]} (meta plan)
                           var->col (->> vars
                                         (into {} (map (juxt col-sym (partial col-sym (str "_r" idx))))))]
                       (-> [:rename var->col
                            plan]
                           (with-meta (into (meta plan)
                                            {::vars (set (vals var->col))
                                             ::var->col var->col}))))))))
    (-> plans
        (with-meta {::var->cols (-> (concat (->> plans (mapcat (comp ::var->col meta)))
                                            param-vars)
                                    (->> (group-by key))
                                    (update-vals #(into #{} (map val) %)))}))))

(defn- mega-join [rels param-vars]
  (let [rels (with-unique-cols rels param-vars)]
    (-> (case (count rels)
          0 (-> [:table [{}]] (with-meta {::vars #{}}))
          1 (first rels)
          [:mega-join [] rels])
        (wrap-unify (::var->cols (meta rels))))))

(defn- form-vars [form]
  (letfn [(form-vars* [[form-type form-arg]]
            (case form-type
              :fn-call (into #{} (mapcat form-vars*) (:args form-arg))
              :logic-var #{form-arg}
              :value #{}))]
    (form-vars* form)))

(defn- combine-term-vars [term-varses]
  (let [{:keys [provided-vars] :as vars} (->> term-varses
                                              (apply merge-with set/union))]
    (-> vars
        (update :required-vars set/difference provided-vars))))

(defn- term-vars [[term-type term-arg]]
  (letfn [(sj-term-vars [spec]
            (let [{:keys [args terms]} term-arg
                  arg-vars (set args)
                  {:keys [required-vars]} (combine-term-vars (map term-vars terms))]

              (when-let [unsatisfied-vars (not-empty (set/difference required-vars arg-vars))]
                (throw (err/illegal-arg :unsatisfied-vars
                                        {:vars unsatisfied-vars
                                         :term (s/unform spec term-arg)})))

              ;; semi-joins do not provide vars
              {:required-vars required-vars}))]

    (case term-type
      :call (let [{:keys [form return]} term-arg]
              {:required-vars (form-vars form)
               :provided-vars (when-let [[return-type return-arg] return]
                                (case return-type
                                  :scalar return-arg))})

      :triple {:provided-vars (into #{}
                                    (comp (map term-arg)
                                          (keep (fn [[val-type val-arg]]
                                                  (when (= :logic-var val-type)
                                                    val-arg))))
                                    [:e :v])}

      :union-join (let [{:keys [args branches]} term-arg
                        arg-vars (set args)
                        branches-vars (for [branch branches]
                                        (into {:branch branch}
                                              (combine-term-vars (map term-vars branch))))
                        provided-vars (->> branches-vars
                                           (map (comp set :provided-vars))
                                           (apply set/intersection))
                        required-vars (->> branches-vars
                                           (map (comp set :required-vars))
                                           (apply set/union))]

                    (when-let [unsatisfied-vars (not-empty (set/difference required-vars arg-vars))]
                      (throw (err/illegal-arg :unsatisfied-vars
                                              {:vars unsatisfied-vars
                                               :term (s/unform ::union-join term-arg)})))

                    {:provided-vars (set/intersection arg-vars provided-vars)
                     :required-vars (set/difference arg-vars provided-vars)})

      :semi-join (sj-term-vars ::semi-join)
      :anti-join (sj-term-vars ::anti-join))))

(defn- ->param-sym [lv]
  (-> (symbol (str "?" (name lv)))
      (with-meta {::param? true})))

(defn- plan-in-tables [{in-bindings :in}]
  (let [in-bindings (->> in-bindings
                         (into [] (map-indexed
                                   (fn [idx [binding-type binding-arg]]
                                     (let [table-key (symbol (str "?in" idx))]
                                       (-> (case binding-type
                                             :source {::in-cols [binding-arg]}
                                             :scalar {::vars #{binding-arg}, ::in-cols [(->param-sym binding-arg)]}
                                             :tuple {::vars (set binding-arg), ::in-cols (mapv ->param-sym binding-arg)}
                                             :relation (let [cols (first binding-arg)]
                                                         {::table-key table-key, ::in-cols cols, ::vars (set cols)})
                                             :collection (let [col (first binding-arg)]
                                                           {::table-key table-key, ::vars #{col}, ::in-cols [col]}))
                                           (assoc ::binding-type binding-type)))))))]
    (-> in-bindings
        (->> (into [] (keep (fn [{::keys [table-key in-cols vars]}]
                              (when table-key
                                (-> [:table in-cols table-key]
                                    (with-meta {::vars vars})))))))
        (with-meta {::in-bindings in-bindings
                    ::param-vars (into {}
                                       (comp (remove ::table-key)
                                             (mapcat ::vars)
                                             (map (juxt identity ->param-sym)))
                                       in-bindings)}))))

(defn- wrap-scan-col-preds [scan-col col-preds]
  (case (count col-preds)
    0 scan-col
    1 {scan-col (first col-preds)}
    {scan-col (list* 'and col-preds)}))

(defn- plan-scan [e triples]
  (let [triples (->> (conj triples {:e e, :a :id, :v e})
                     (map #(update % :a col-sym)))

        attrs (into #{} (map :a) triples)

        attr->lits (-> triples
                       (->> (keep (fn [{:keys [a], [v-type v-arg] :v}]
                                    (when (= :literal v-type)
                                      {:a a, :lit v-arg})))
                            (group-by :a))
                       (update-vals #(into #{} (map :lit) %)))]

    (-> [:scan (or (some-> (first (attr->lits '_table)) symbol)
                   'xt_docs)
         (-> attrs
             (conj 'application_time_start 'application_time_end)
             (disj '_table)
             (->> (mapv (fn [attr]
                          (-> attr
                              (wrap-scan-col-preds
                               (concat (for [lit (get attr->lits attr)]
                                         (list '= attr lit))
                                       (case attr
                                         application_time_start ['(<= application_time_start (current-timestamp))]
                                         application_time_end ['(> application_time_end (current-timestamp))]
                                         nil))))))))]
        (with-meta {::vars attrs}))))

(defn- attr->unwind-col [a]
  (col-sym "__uw" a))

(defn- wrap-unwind [plan triples]
  (->> triples
       (transduce
        (comp (keep (fn [{:keys [a], [v-type _v-arg] :v}]
                      (when (= v-type :unwind)
                        a)))
              (distinct))

        (completing (fn [plan a]
                      (let [uw-col (attr->unwind-col a)]
                        (-> [:unwind {uw-col a}
                             plan]
                            (vary-meta update ::vars conj uw-col)))))
        plan)))

(defn- plan-triples [triples]
  (->> (group-by :e triples)
       (mapv (fn [[e triples]]
               (let [triples (->> (conj triples {:e e, :a :id, :v e})
                                  (map #(update % :a col-sym)))

                     var->cols (-> triples
                                   (->> (keep (fn [{:keys [a], [v-type v-arg] :v}]
                                                (case v-type
                                                  :logic-var {:lv v-arg, :col a}
                                                  :unwind {:lv (first v-arg), :col (attr->unwind-col a)}
                                                  nil)))
                                        (group-by :lv))
                                   (update-vals #(into #{} (map :col) %)))]

                 (-> (plan-scan e triples)
                     (wrap-unwind triples)
                     (wrap-unify var->cols)))))))

(defn- plan-call [{:keys [form return]}]
  (letfn [(with-col-metadata [[form-type form-arg]]
            [form-type
             (case form-type
               :logic-var (if (str/starts-with? (name form-arg) "?")
                            form-arg
                            (col-sym form-arg))
               :fn-call (-> form-arg (update :args #(mapv with-col-metadata %)))
               :value form-arg)])]
    (-> (s/unform ::form (with-col-metadata form))
        (with-meta (into {::required-vars (form-vars form)}

                         (when-let [[return-type return-arg] return]
                           (-> (case return-type
                                 :scalar {::return-var return-arg
                                          ::vars #{return-arg}})
                               (assoc ::return-type return-type))))))))

(defn- wrap-calls [plan calls]
  (letfn [(wrap-scalars [plan scalars]
            (let [scalars (->> scalars
                               (into [] (map-indexed
                                         (fn [idx form]
                                           (let [{::keys [return-var]} (meta form)]
                                             (-> form
                                                 (vary-meta assoc ::return-col (col-sym (str "_c" idx) return-var))))))))
                  var->cols (-> (concat (->> (::vars (meta plan)) (map (juxt identity identity)))
                                        (->> scalars (map (comp (juxt ::return-var ::return-col) meta))))
                                (->> (group-by first))
                                (update-vals #(into #{} (map second) %)))]
              (-> [:map (vec (for [form scalars]
                               {(::return-col (meta form)) form}))
                   plan]
                  (with-meta (-> (meta plan) (update ::vars into (map ::return-col scalars))))
                  (wrap-unify var->cols))))]

    (let [{selects nil, scalars :scalar} (group-by (comp ::return-type meta) calls)]
      (-> plan
          (cond-> scalars (wrap-scalars scalars))
          (wrap-select selects)))))

(defn- ->apply-mapping [apply-params]
  ;;TODO symbol names will clash with nested applies
  ;; (where an apply is nested inside the dep side of another apply)
  (when (seq apply-params)
    (->> (for [param apply-params]
           (let [param-symbol (-> (symbol (str "?ap_" param))
                                  (with-meta {:correlated-column? true}))]
             (MapEntry/create param param-symbol)))
         (into {}))))

(defn- plan-semi-join [sj-type {:keys [args terms] :as sj}]
  (let [{sj-required-vars :required-vars} (term-vars [sj-type sj])
        required-vars (if (seq sj-required-vars) (set args) #{})
        apply-mapping (->apply-mapping required-vars)]

    (-> (plan-query
         (cond-> {:find (vec (for [arg args]
                               [:logic-var arg]))
                  :where terms}
           (seq required-vars) (assoc ::apply-mapping apply-mapping)))
        (vary-meta into {::required-vars required-vars
                         ::apply-mapping apply-mapping}))))

(defn- wrap-semi-joins [plan sj-type semi-joins]
  (->> semi-joins
       (reduce (fn [acc sq-plan]
                 (let [{::keys [apply-mapping]} (meta sq-plan)]
                   (-> (if apply-mapping
                         [:apply sj-type apply-mapping
                          acc sq-plan]

                         [sj-type (->> (::vars (meta sq-plan))
                                       (mapv (fn [v] {v v})))
                          acc sq-plan])
                       (with-meta (meta acc)))))
               plan)))

(defn- plan-union-join [{:keys [args branches] :as uj}]
  (let [{:keys [required-vars]} (term-vars [:union-join uj])
        apply-mapping (->apply-mapping required-vars)]
    (-> branches
        (->> (mapv (fn [branch]
                     (plan-query
                      {:find (vec (for [arg args]
                                    [:logic-var arg]))
                       ::apply-mapping apply-mapping
                       :where branch})))
             (reduce (fn [acc plan]
                       (-> [:union-all acc plan]
                           (with-meta (meta acc))))))
        (vary-meta into {::required-vars required-vars, ::apply-mapping apply-mapping}))))

(defn- wrap-union-joins [plan union-joins param-vars]
  (if-let [apply-mapping (->> union-joins
                              (into {} (mapcat (comp ::apply-mapping meta)))
                              not-empty)]
    (let [sq-plan (mega-join union-joins param-vars)]
      (-> [:apply :cross-join apply-mapping
           plan sq-plan]
          (with-meta (-> (meta plan) (update ::vars into (::vars (meta sq-plan)))))))

    (mega-join (into [plan] union-joins) param-vars)))

(defn- plan-sub-query [{:keys [query]}]
  (let [required-vars (->> (:in query)
                           (into #{} (map
                                      (fn [[in-type in-arg :as in]]
                                        (when-not (= in-type :scalar)
                                          (throw (err/illegal-arg :non-scalar-subquery-param
                                                                  (s/unform ::in-binding in))))
                                        in-arg))))
        apply-mapping (->apply-mapping required-vars)]
    (-> (plan-query (-> query
                        (dissoc :in)
                        (assoc ::apply-mapping apply-mapping)))
        (vary-meta into {::required-vars required-vars, ::apply-mapping apply-mapping}))))

(defn- wrap-sub-queries [plan sub-queries param-vars]
  (if-let [apply-mapping (->> sub-queries
                              (into {} (mapcat (comp ::apply-mapping meta)))
                              (not-empty))]
    (let [sq-plan (mega-join sub-queries param-vars)]
      (-> [:apply :cross-join apply-mapping
           plan sq-plan]
          (with-meta (-> (meta plan) (update ::vars into (::vars (meta sq-plan)))))))

    (mega-join (into [plan] sub-queries) param-vars)))

(defn- plan-body [{where-clauses :where, apply-mapping ::apply-mapping, :as query}]
  (let [in-rels (plan-in-tables query)
        {::keys [param-vars]} (meta in-rels)

        {triple-clauses :triple, call-clauses :call, sub-query-clauses :sub-query
         semi-join-clauses :semi-join, anti-join-clauses :anti-join, union-join-clauses :union-join}
        (-> where-clauses
            (->> (group-by first))
            (update-vals #(mapv second %)))]

    (loop [plan (mega-join (vec (concat in-rels (plan-triples triple-clauses)))
                           (concat param-vars apply-mapping))

           calls (some->> call-clauses (mapv plan-call))
           union-joins (some->> union-join-clauses (mapv plan-union-join))
           semi-joins (some->> semi-join-clauses (mapv (partial plan-semi-join :semi-join)))
           anti-joins (some->> anti-join-clauses (mapv (partial plan-semi-join :anti-join)))
           sub-queries (some->> sub-query-clauses (mapv plan-sub-query))]

      (if (and (empty? calls) (empty? sub-queries)
               (empty? semi-joins) (empty? anti-joins) (empty? union-joins))
        (-> plan
            (vary-meta assoc ::in-bindings (::in-bindings (meta in-rels))))

        (let [{available-vars ::vars} (meta plan)]
          (letfn [(available? [clause]
                    (set/superset? available-vars (::required-vars (meta clause))))]

            (let [{available-calls true, unavailable-calls false} (->> calls (group-by available?))
                  {available-sqs true, unavailable-sqs false} (->> sub-queries (group-by available?))
                  {available-ujs true, unavailable-ujs false} (->> union-joins (group-by available?))
                  {available-sjs true, unavailable-sjs false} (->> semi-joins (group-by available?))
                  {available-ajs true, unavailable-ajs false} (->> anti-joins (group-by available?))]

              (if (and (empty? available-calls) (empty? available-sqs)
                       (empty? available-ujs) (empty? available-sjs) (empty? available-ajs))
                (throw (err/illegal-arg :no-available-clauses
                                        {:available-vars available-vars
                                         :unavailable-subqs unavailable-sqs
                                         :unavailable-calls unavailable-calls
                                         :unavailable-union-joins unavailable-ujs
                                         :unavailable-semi-joins unavailable-sjs
                                         :unavailable-anti-joins unavailable-ajs}))

                (recur (cond-> plan
                         union-joins (wrap-union-joins union-joins param-vars)
                         available-calls (wrap-calls available-calls)
                         available-sjs (wrap-semi-joins :semi-join available-sjs)
                         available-ajs (wrap-semi-joins :anti-join available-ajs)
                         available-sqs (wrap-sub-queries available-sqs param-vars))

                       unavailable-calls unavailable-sqs
                       unavailable-ujs unavailable-sjs unavailable-ajs)))))))))

(defn- analyse-find-clauses [{find-clauses :find, rename-keys :keys}]
  (when-let [clauses (mapv (fn [[clause-type clause] rename-key]
                             (let [rename-sym (some-> rename-key col-sym)]
                               (-> (case clause-type
                                     :logic-var {:lv clause
                                                 :col (or rename-sym (col-sym clause))
                                                 :vars #{clause}}
                                     :aggregate (let [{:keys [agg-fn param]} clause]
                                                  {:aggregate clause
                                                   :col (or rename-sym (col-sym (str agg-fn "-" param)))
                                                   :vars #{param}}))
                                   (assoc :clause-type clause-type))))
                           find-clauses
                           (or rename-keys (repeat nil)))]

    (let [{agg-clauses :aggregate, lv-clauses :logic-var} (->> clauses (group-by :clause-type))]
      (into {:clauses (->> clauses (mapv #(select-keys % [:lv :clause-type :col])))}

            (when-let [aggs (->> agg-clauses
                                 (into {} (comp (filter #(= :aggregate (:clause-type %)))
                                                (map (juxt :col :aggregate)))))]
              {:aggs aggs
               :grouping-vars (->> lv-clauses
                                   (into #{} (comp (remove :aggregate) (mapcat :vars))))})))))

(defn- plan-find [query]
  (let [{:keys [clauses aggs grouping-vars]} (analyse-find-clauses query)]
    (-> clauses
        (->> (mapv (fn [{:keys [clause-type] :as clause}]
                     (case clause-type
                       :logic-var (let [in-col (col-sym (:lv clause))]
                                    (if-let [out-col (:col clause)]
                                      {out-col in-col}
                                      in-col))
                       :aggregate (:col clause)))))
        (with-meta {::vars (into #{} (map :col) clauses)
                    ::aggs aggs
                    ::grouping-vars grouping-vars}))))

(defn- wrap-find [plan find-clauses]
  (-> [:project find-clauses plan]
      (with-meta (-> (meta plan) (assoc ::vars (::vars (meta find-clauses)))))))

(defn- plan-order-by [{:keys [order-by]}]
  (when order-by
    (let [{:keys [clauses aggs grouping-vars]} (analyse-find-clauses {:find (map :find-arg order-by)})]
      (-> (mapv (fn [{:keys [clause-type] :as clause} {:keys [direction] :or {direction :asc}}]
                  [(case clause-type
                     :logic-var (:lv clause)
                     :aggregate (:col clause))
                   {:direction direction}])
                clauses order-by)
          (with-meta {::aggs aggs, :grouping-vars grouping-vars})))))

(defn- wrap-order-by [plan order-by-clauses]
  (-> [:order-by order-by-clauses plan]
      (with-meta (meta plan))))

(defn- plan-group-by [{:keys [find-clauses order-by-clauses]}]
  (let [{find-aggs ::aggs, find-grouping-vars ::grouping-vars} (meta find-clauses)
        {order-by-aggs ::aggs, order-by-grouping-vars ::grouping-vars} (meta order-by-clauses)
        aggs (not-empty (merge find-aggs order-by-aggs))
        grouping-vars (set/union (set find-grouping-vars)
                                 (set order-by-grouping-vars))]
    (when aggs
      (-> (into (vec grouping-vars)
                (map (fn [[col agg]]
                       {col (s/unform ::aggregate agg)}))
                aggs)
          (with-meta {::vars (into (set grouping-vars) (map key) aggs)})))))

(defn- wrap-group-by [plan group-by-clauses]
  (-> [:group-by group-by-clauses plan]
      (with-meta (-> (meta plan) (assoc ::vars (::vars (meta group-by-clauses)))))))

(defn- wrap-head [plan query]
  (let [find-clauses (plan-find query)
        order-by-clauses (plan-order-by query)
        group-by-clauses (plan-group-by {:find-clauses find-clauses
                                         :order-by-clauses order-by-clauses})]

    (-> plan
        (cond-> group-by-clauses (wrap-group-by group-by-clauses)
                order-by-clauses (wrap-order-by order-by-clauses))
        (wrap-find find-clauses))))

(defn- wrap-top [plan {:keys [limit offset]}]
  (if (or limit offset)
    (-> [:top (cond-> {}
                offset (assoc :skip offset)
                limit (assoc :limit limit))
         plan]
        (with-meta (meta plan)))

    plan))

(defn- plan-query [conformed-query]
  (-> (plan-body conformed-query)
      (wrap-head conformed-query)
      (wrap-top conformed-query)))

(defn compile-query [query]
  (-> (conform-query query)
      (plan-query)))

(defn- args->params [args in-bindings]
  (->> (mapcat (fn [{::keys [binding-type in-cols]} arg]
                 (case binding-type
                   (:source :scalar) [(MapEntry/create (first in-cols) arg)]
                   :tuple (zipmap in-cols arg)
                   (:collection :relation) nil))
               in-bindings
               args)
       (into {})))

(defn- args->tables [args in-bindings]
  (->> (mapcat (fn [{::keys [binding-type in-cols table-key]} arg]
                 (case binding-type
                   (:source :scalar :tuple) nil

                   :collection (let [in-col (first in-cols)
                                     binding-k (keyword in-col)]
                                 (if-not (coll? arg)
                                   (throw (err/illegal-arg :bad-collection
                                                           {:binding in-col
                                                            :coll arg}))
                                   [(MapEntry/create table-key
                                                     (vec (for [v arg]
                                                            {binding-k v})))]))

                   :relation (let [conformed-arg (s/conform ::relation-arg arg)]
                               (if (s/invalid? conformed-arg)
                                 (throw (err/illegal-arg :bad-relation
                                                         {:binding in-cols
                                                          :relation arg
                                                          :explain-data (s/explain-data ::relation-arg arg)}))
                                 (let [[rel-type rel] conformed-arg
                                       ks (mapv keyword in-cols)]
                                   [(MapEntry/create table-key
                                                     (case rel-type
                                                       :maps rel
                                                       :vecs (mapv #(zipmap ks %) rel)))])))))
               in-bindings
               args)
       (into {})))

(defn open-datalog-query ^core2.IResultSet [^BufferAllocator allocator query db args]
  (let [plan (compile-query (dissoc query :basis :basis-timeout :default-tz))
        {::keys [in-bindings]} (meta plan)

        plan (-> plan
                 #_(doto clojure.pprint/pprint)
                 #_(->> (binding [*print-meta* true]))
                 (lp/rewrite-plan {})
                 #_(doto clojure.pprint/pprint)
                 (doto (lp/validate-plan)))

        pq (op/prepare-ra plan)]

    (when (not= (count in-bindings) (count args))
      (throw (err/illegal-arg :in-arity-exception {::err/message ":in arity mismatch"
                                                   :expected (count in-bindings)
                                                   :actual args})))

    (let [^AutoCloseable
          params (vw/open-params allocator (args->params args in-bindings))]
      (try
        (-> (.bind pq {:srcs {'$ db}, :params params, :table-args (args->tables args in-bindings)
                       :current-time (get-in query [:basis :current-time])
                       :default-tz (:default-tz query)})
            (.openCursor)
            (op/cursor->result-set params))
        (catch Throwable t
          (.close params)
          (throw t))))))


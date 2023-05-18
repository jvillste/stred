(ns stred.core
  (:require
   [argumentica.branch-transaction-log :as branch-transaction-log]
   [argumentica.btree-collection :as btree-collection]
   [argumentica.comparator :as comparator]
   [argumentica.contents :as contents]
   [argumentica.db.common :as db-common]
   [argumentica.db.query :as query]
   [argumentica.entity-id :as entity-id]
   [argumentica.map-to-transaction :as map-to-transaction]
   [argumentica.merged-sorted :as merged-sorted]
   [argumentica.sorted-map-transaction-log :as sorted-map-transaction-log]
   [argumentica.sorted-reducible :as sorted-reducible]
   [argumentica.stream :as stream]
   [argumentica.temporary-ids :as temporary-ids]
   [argumentica.transaction-log :as transaction-log]
   [clojure.core.async :as async]
   [clojure.set :as set]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [flow-gl.graphics.font :as font]
   [flow-gl.gui.keyboard :as keyboard]
   [flow-gl.gui.scene-graph :as scene-graph]
   [flow-gl.gui.visuals :as visuals]
   [fungl.application :as application]
   [fungl.component.text-area :as text-area]
   [fungl.dependable-atom :as dependable-atom]
   [fungl.layout :as layout]
   [fungl.layouts :as layouts]
   [fungl.view-compiler :as view-compiler]
   [me.raynes.fs :as fs]
   [medley.core :as medley]
   [argumentica.db.db :as db]
   [fungl.swing.root-renderer :as root-renderer]
   [fungl.cache :as cache]
   [fungl.component :as component]
   [fungl.derivation :as derivation]
   [flow-gl.tools.trace :as trace]
   [clojure.walk :as walk]
   [argumentica.db.common :as common]))

(defn create-dependable-stream-db-in-memory [id index-definitions]
  (assoc (stream/in-memory {:id id :create-atom (partial dependable-atom/atom (str "stream " id))})
         :indexes (db-common/index-definitions-to-indexes (fn [index-key]
                                                            (btree-collection/create-in-memory {:create-atom (partial dependable-atom/atom index-key)}))
                                                          index-definitions)))

(defn create-stream-db-on-disk [id path index-definitions]
  (let [stream-db (assoc (stream/on-disk path {:id id})
                         :indexes (db-common/index-definitions-to-indexes (fn [index-key]
                                                                            (btree-collection/create-in-memory {:create-atom (partial dependable-atom/atom index-key)}))
                                                                          index-definitions))]
    (db-common/update-indexes-2! stream-db)
    stream-db))

(defn transact! [stream-db temporary-changes]
  (let [temporary-id-resolution (temporary-ids/temporary-id-resolution @(:next-id-atom stream-db)
                                                                       temporary-changes)
        temporary-id-resolution-with-global-ids (medley/map-vals (partial entity-id/global
                                                                          (:id stream-db))
                                                                 temporary-id-resolution)
        changes (temporary-ids/assign-temporary-ids temporary-id-resolution-with-global-ids
                                                    temporary-changes)
        changes (db-common/expand-set-statements (get-in stream-db [:indexes :eav])
                                                 changes)]
    (when (not (empty? temporary-id-resolution))
      (reset! (:next-id-atom stream-db)
              (temporary-ids/new-next-id temporary-id-resolution)))

    (transaction-log/add! (get-in stream-db [:transaction-log])
                          changes)

    (db-common/add-transaction-to-indexes! (:indexes stream-db)
                                           (or (transaction-log/last-transaction-number (:transaction-log stream-db))
                                               0)
                                           changes)

    {:temporary-id-resolution temporary-id-resolution-with-global-ids
     :changes changes}))

(defn merge-indexes [upstream-indexes downstream-indexes last-upstream-transaction-number]
  (db-common/index-definitions-to-indexes (fn [key]
                                            (when-let [upstream-collection (get-in upstream-indexes [key :collection])]
                                              (merged-sorted/->MergedSorted  upstream-collection
                                                                             (get-in downstream-indexes [key :collection])
                                                                             last-upstream-transaction-number)))
                                          (let [common-keys (set/intersection (set (map :key (vals upstream-indexes)))
                                                                              (set (map :key (vals downstream-indexes))))]
                                            (->> (vals downstream-indexes)
                                                 (filter (comp common-keys :key))
                                                 (map db-common/index-to-index-definition)))))

(deftest test-merge-indexes
  (is (= '([1 :name "bar" 1 :add]
           [1 :name "foo" 0 :add])
         (let [stream-db-a (create-dependable-stream-db-in-memory :a [db-common/eav-index-definition])
               stream-db-b (create-dependable-stream-db-in-memory :b [db-common/eav-index-definition])]
           (transact! stream-db-a [[:add 1 :name "foo"]])
           (transact! stream-db-b [[:add 1 :name "bar"]])
           (:collection (:eav (merge-indexes (:indexes stream-db-a)
                                             (:indexes stream-db-b)
                                             (transaction-log/last-transaction-number (:transaction-log stream-db-a)))))))))

(defn merge-stream-dbs [upstream-stream-db downstream-stream-db]
  (assoc downstream-stream-db
         :transaction-log (branch-transaction-log/create (:transaction-log upstream-stream-db)
                                                         (transaction-log/last-transaction-number (:transaction-log upstream-stream-db))
                                                         (:transaction-log downstream-stream-db))
         ;; :last-upstream-transaction-number (or (transaction-log/last-transaction-number (:transaction-log upstream-stream-db))
         ;;                                       (:last-upstream-transaction-number upstream-stream-db)
         ;;                                       0)
         :indexes (merge-indexes (:indexes upstream-stream-db)
                                 (:indexes downstream-stream-db)
                                 (transaction-log/last-transaction-number (:transaction-log upstream-stream-db)))))

#_(defn create-stream-db-branch [stream-db]
    (let [next-id @(:next-id-atom stream-db)
          last-upstream-transaction-number (transaction-log/last-transaction-number (:transaction-log stream-db))]
      {:id (:id stream-db)
       :first-new-id next-id
       :next-id-atom (dependable-atom/atom next-id)
       :transaction-log (branch-transaction-log/create (:transaction-log stream-db)
                                                       last-upstream-transaction-number
                                                       (sorted-map-transaction-log/create {:create-atom dependable-atom/atom}))
       ;;     :base-stream-db stream-db

       ;;           :last-upstream-transaction-number last-upstream-transaction-number
       :indexes (db-common/index-definitions-to-indexes (fn [key]
                                                          (merged-sorted/->MergedSorted (-> stream-db :indexes key :collection)
                                                                                        (btree-collection/create-in-memory  {:create-atom dependable-atom/atom})
                                                                                        last-upstream-transaction-number))
                                                        (map common/index-to-index-definition (vals (:indexes stream-db))))}))

(defn create-stream-db-branch [branch-stream-id upstream-stream-db-value]
  (assert (db-common/db-value? upstream-stream-db-value))
  (let [branch-transaction-log (sorted-map-transaction-log/create {:create-atom (partial dependable-atom/atom (str branch-stream-id "-transaction-log"))})]
    {:id branch-stream-id
     :next-id-atom (dependable-atom/atom (str branch-stream-id "-next-id")
                                         0)
     :transaction-log (branch-transaction-log/create (:transaction-log upstream-stream-db-value)
                                                     (:last-transaction-number upstream-stream-db-value)
                                                     branch-transaction-log)
     :branch-transaction-log branch-transaction-log
     :upstream-stream-db-value upstream-stream-db-value

     ;;           :last-upstream-transaction-number last-upstream-transaction-number
     :indexes (db-common/index-definitions-to-indexes (fn [key]
                                                        (merged-sorted/->MergedSorted (-> upstream-stream-db-value :indexes key :collection)
                                                                                      (btree-collection/create-in-memory  {:create-atom dependable-atom/atom})
                                                                                      (:last-transaction-number upstream-stream-db-value)))
                                                      (map db-common/index-to-index-definition (vals (:indexes upstream-stream-db-value))))}))

(deftest test-stream-db-branch
  (let [stream-db (doto (create-dependable-stream-db-in-memory "base" [db-common/eav-index-definition])
                    (transact! [[:add
                                 :entity-1
                                 :label
                                 "base label"]]))
        branch (doto (create-stream-db-branch "branch" stream-db)
                 (transact! [[:add
                              :entity-1
                              :label
                              "branch label 1"]])
                 (transact! [[:add
                              :entity-1
                              :label
                              "branch label 2"]]))]

    (testing "transaction numbers in branch indexes start from zero"
      (is (= '([:entity-1 :label "branch label 1" 0 :add]
               [:entity-1 :label "branch label 2" 1 :add])
             (-> branch :indexes :eav :collection contents/contents :downstream-sorted))))

    (testing "merged indexes shift branch transaction numbers so that they start after the last upstream transaction number"
      (is (= 0 (-> branch :indexes :eav :collection contents/contents :last-upstream-transaction-number)))
      (is (= '([:entity-1 :label "base label" 0 :add]
               [:entity-1 :label "branch label 1" 1 :add]
               [:entity-1 :label "branch label 2" 2 :add])
             (-> branch :indexes :eav :collection))))

    (is (= ["base label"
            "branch label 1"
            "branch label 2"]
           (into [] (db-common/values branch
                                      :entity-1
                                      :label))))))

(defn undo-last-branch-transaction [stream-db-branch]
  (let [new-branch (create-stream-db-branch (:id stream-db-branch)
                                            (:upstream-stream-db-value stream-db-branch))]
    (doseq [transaction (drop-last (:branch-transaction-log stream-db-branch))]
      (transact! new-branch transaction))
    new-branch))

(defn rebase [new-upstream branch]
  (let [new-branch (create-stream-db-branch (:id branch)
                                            new-upstream)]
    (run! (partial transact! new-branch)
          (:branch-transaction-log branch))))

(defn new-id-to-temporary-id [first-new-id value]
  (if (and (entity-id/entity-id? value)
           (<= first-new-id
               (:id value)))
    (keyword "tmp" (str "id" (:id value)))
    value))

(defn new-ids-to-temporary-ids-in-datom [first-new-id datom]
  (vec (map (partial new-id-to-temporary-id
                     first-new-id)
            datom)))

(defn new-ids-to-temporary-ids-in-transaction [first-new-id transaction]
  (set (map (partial new-ids-to-temporary-ids-in-datom
                     first-new-id)
            transaction)))

(defn commit-transaction [stream-db-branch]
  (->> stream-db-branch
       (:transaction-log)
       (db-common/squash-transaction-log)
       (new-ids-to-temporary-ids-in-transaction (:first-new-id stream-db-branch))))

(deftest test-create-stream-db-branch
  (let [stream-db (create-dependable-stream-db-in-memory :stream [db-common/eav-index-definition])]
    (transact! stream-db [[:add :tmp/id1 :name "foo"]])
    (let [branch (create-stream-db-branch stream-db)]

      (is (= '([{:id 0 :stream-id :stream} :name "foo" 0 :add])
             (-> branch :indexes :eav :collection)))

      (transact! branch [[:add {:id 0 :stream-id :stream} :name "bar"]])
      (transact! branch [[:add :tmp/id1 :name "baz"]])

      (is (= '([{:id 0 :stream-id :stream} :name "bar" 1 :add]
               [{:id 0 :stream-id :stream} :name "foo" 0 :add]
               [{:id 1 :stream-id :stream} :name "baz" 2 :add])
             (-> branch :indexes :eav :collection)))

      (is (= '(#{[:add {:id 0 :stream-id :stream} :name "bar"]}
               #{[:add {:id 1 :stream-id :stream} :name "baz"]})
             (seq (-> branch :transaction-log))))

      (is (= #{[:add :tmp/id1 :name "baz"]
               [:add {:id 0 :stream-id :stream} :name "bar"]}
             (commit-transaction branch)))

      (transact! stream-db (commit-transaction branch))

      (is (= '([{:id 0 :stream-id :stream} :name "bar" 1 :add]
               [{:id 0 :stream-id :stream} :name "foo" 0 :add]
               [{:id 1 :stream-id :stream} :name "baz" 1 :add])
             (seq (-> stream-db :indexes :eav :collection)))))))


(defn print-and-return [label value]
  (prn label value)
  value)

(def index-definitions [db-common/eav-index-definition
                        db-common/ave-index-definition
                        db-common/vae-index-definition
                        db-common/sequence-vae-index-definition
                        db-common/full-text-index-definition
                        ;; (db-common/composite-index-definition :text
                        ;;                                       [{:attributes [:name :description]
                        ;;                                         :value-function db-common/tokenize}])
                        ;; (db-common/enumeration-index-definition :data-type :data-type)
                        ;; (db-common/composite-index-definition :food-nutrient-amount [:food :nutrient :amount])
                        ;; (db-common/composite-index-definition :nutrient-amount-food [:nutrient :amount :food])
                        ;; (db-common/rule-index-definition :datatype-nutrient-amount-food {:head [:?data-type :?nutrient :?amount :?food :?description]
                        ;;                                                                  :body [[:eav
                        ;;                                                                          [:?measurement :amount :?amount]
                        ;;                                                                          [:?measurement :nutrient :?nutrient]
                        ;;                                                                          [:?measurement :food :?food]
                        ;;                                                                          [:?food :data-type :?data-type]
                        ;;                                                                          [:?food :description :?description]]]})
                        ])


(defn stream-entity-id-to-temporary-id [stream-id value]
  (if (and (entity-id/entity-id? value)
           (= stream-id (:stream-id value)))
    (temporary-ids/temporary-id (str "id-" (:id value)))
    value))

;; TODO: when array of temporary ids is written to the transaction log, replace the temporary ids with real ids

(defn stream-entity-ids-to-temporary-ids [stream-id datom]
  (vec (map (fn [value]
              (if (vector? value)
                (vec (map (partial stream-entity-id-to-temporary-id stream-id)
                          value))
                (stream-entity-id-to-temporary-id stream-id value)))
            datom)))

(defn local-entities-to-temporary-ids [datom]
  (vec (map (fn [value]
              (if (entity-id/local? value)
                (temporary-ids/temporary-id (str "id-" (:id value)))
                value))
            datom)))

(defn branch-changes [stream-db-branch]
  (->> (:branch-transaction-log stream-db-branch)
       (apply concat)
       #_(map (partial stream-entity-ids-to-temporary-ids (:id stream-db-branch)))
       (db-common/squash-statements)))

#_(defn merge-stream-db-branch! [stream-db-branch]
    (transact! (:base-stream-db stream-db-branch)
               (branch-changes stream-db-branch)))


(def path "temp/test-stream")

;; (def prelude #{[:add :tmp/label :tmp/label "label"]
;;                [:add :tmp/label :tmp/type-attribute :tmp/attribute]

;;                [:add :tmp/type-attribute :tmp/label "type"]
;;                [:add :tmp/type-attribute :tmp/type-attribute :tmp/attribute]

;;                [:add :tmp/attribute :tmp/label "attribute"]
;;                [:add :tmp/attribute :tmp/type-attribute :tmp/type-type]

;;                [:add :tmp/type-type :tmp/label "type"]
;;                [:add :tmp/type-type :tmp/type-attribute :tmp/type-type]})

;; (comment
;;   (map-to-transaction/maps-to-transaction {:dali/id :tmp/label
;;                                            :tmp/label "label"
;;                                            :tmp/type-attribute :tmp/attribute}

;;                                           {:dali/id :tmp/type-attribute
;;                                            :tmp/label "type"
;;                                            :tmp/type-attribute :tmp/attribute}

;;                                           {:dali/id :tmp/attribute
;;                                            :tmp/label "attribute"
;;                                            :tmp/type-attribute :tmp/type-type}

;;                                           {:dali/id :tmp/type-type
;;                                            :tmp/label "type"
;;                                            :tmp/type-attribute :tmp/type-type})


;;   )


(defn schema-transaction [id-map entities]
  (temporary-ids/assign-temporary-ids (medley/map-vals (fn [number] {:id number})
                                                       id-map)
                                      (mapcat (fn [[type id label]]
                                                [[:add id :type-attribute type]
                                                 [:add id :label label]])
                                              entities)))

(def prelude-transaction (schema-transaction {:label 0
                                              :type-attribute 1
                                              :attribute 2
                                              :type-type 3}
                                             [[:attribute :label "label"]
                                              [:attribute :type-attribute "type"]
                                              [:type-type :attribute "attribute"]
                                              [:type-type :type-type "type"]]))

(defn maps-to-transaction [id-map maps]
  (temporary-ids/assign-temporary-ids id-map
                                      (mapcat map-to-transaction/map-to-statements maps)
                                      #_(apply map-to-transaction/maps-to-transaction
                                               maps
                                               #_(map (fn [a-map]
                                                        (medley/map-keys (fn [key]
                                                                           (if (= :id key)
                                                                             :dali/id
                                                                             key))
                                                                         a-map))
                                                      maps))))

(defn numbers-to-ids [stream-id number-map]
  (medley/map-vals (fn [number]
                     {:stream-id stream-id
                      :id number})
                   number-map))

(defn keys-to-number-map [keys]
  (->> keys
       (map-indexed vector)
       (map reverse)
       (map vec)
       (into {})))

(deftest test-keys-to-number-map
  (is (= {:a 0, :b 1}
         (keys-to-number-map [:a :b]))))

(defn id-function [id-map]
  (fn [key]
    (assert (get id-map key) (str "unknown key " key))
    (get id-map key)))

(defn stream-id-function [stream-id keys]
  (id-function (numbers-to-ids stream-id
                               (keys-to-number-map keys))))

(def prelude-stream-id "prelude")
(def prelude (stream-id-function prelude-stream-id
                                 [:attribute
                                  :type-type
                                  :value-type

                                  :label
                                  :type-attribute
                                  :subtype-of
                                  :range

                                  :text
                                  :number
                                  :boolean
                                  :entity

                                  :array

                                  :component?]))

(defn keyword-to-id [value]
  (if (keyword? value)
    (prelude value)
    value))

(defn attribute [id label range & [attributes]]
  (merge {:dali/id (keyword-to-id id)
          (prelude :label) label
          (prelude :type-attribute) (prelude :attribute)}
         (when range
           {(prelude :range) (keyword-to-id range)})
         attributes))

(deftest test-attribute
  (is (= {:dali/id {:stream-id "prelude", :id 3},
          {:stream-id "prelude", :id 3} "label",
          {:stream-id "prelude", :id 6} {:stream-id "prelude", :id 7},
          {:stream-id "prelude", :id 4} {:stream-id "prelude", :id 0},
          :foo :bar}

         (attribute :label "label" :text {:foo :bar}))))

(defn type [id label & [attributes]]
  (merge {:dali/id (keyword-to-id id)
          (prelude :label) label
          (prelude :type-attribute) (prelude :type-type)}
         attributes))

(defn instance [type id label & [attributes]]
  (merge {:dali/id (keyword-to-id id)
          (prelude :label) label
          (prelude :type-attribute) (keyword-to-id type)}
         attributes))

(def prelude-transaction (mapcat map-to-transaction/map-to-statements
                                 [(type :type-type "type")
                                  (type :attribute "attribute")
                                  (type :value-type "value type")

                                  (attribute :label "label" :text)
                                  (attribute :type-attribute "type" :type-type)
                                  (attribute :range "range" :value-type)
                                  (attribute :subtype-of "subtype of" :type-type)

                                  (type :text "text" {(prelude :subtype-of) (prelude :value-type)})
                                  (type :number "number" {(prelude :subtype-of) (prelude :value-type)})
                                  (type :boolean "boolean" {(prelude :subtype-of) (prelude :value-type)})
                                  (type :entity "entity" {(prelude :subtype-of) (prelude :value-type)})

                                  (instance :value-type :array "array")]))

(def argumentation-stream-id "argumentation")
(def argumentation (stream-id-function argumentation-stream-id
                                       [:statement
                                        :argument
                                        :premises
                                        :supports
                                        :undermines
                                        :concept
                                        :refers
                                        :question
                                        :answers]))

;; TODO: make prompt return arguments and statements.
;; Add separate editing views for arguments and statements so that satatement has label editor and list of arguments where it is involved.

;; How is argument visualized if it does not have a label?
;; Argument can be created when ever a statement is selected. It can either support or undermine the statement.


(def argumentation-schema-transaction (mapcat map-to-transaction/map-to-statements
                                              [(type (argumentation :statement) "statement")
                                               (type (argumentation :argument) "argument")
                                               (type (argumentation :concept) "concept")
                                               (type (argumentation :question) "question")

                                               (attribute (argumentation :premises) "premises" :array)
                                               (attribute (argumentation :supports) "supports" :entity)
                                               (attribute (argumentation :undermines) "undermines" :entity)
                                               (attribute (argumentation :refers) "refers" :entity)
                                               (attribute (argumentation :answers) "answers" :entity)]))


;; stred = structure editor

(def stred-stream-id "stred")
(def stred (stream-id-function stred-stream-id
                               [:notebook
                                :entities
                                :editors
                                :views
                                :domain

                                :view
                                :outline-view
                                :entity-attribute-editor
                                :entity-array-attribute-editor
                                :text-attribute-editor
                                :attribute
                                :entity
                                :lens
                                :editor
                                :reverse?
                                :value-lens
                                ]))

(defn namespaced-keyword-to-entity-id [namespaces-to-stream-id-functions a-keyword]
  (if-let [stream-id-function (get namespaces-to-stream-id-functions
                                   (keyword (namespace a-keyword)))]
    (stream-id-function (keyword (name a-keyword)))
    a-keyword))

(deftest test-namespaced-keyword-to-entity-id
  (is (= {:stream-id "prelude", :id 4}
         (namespaced-keyword-to-entity-id {:prelude prelude}
                                          :prelude/type-attribute)))

  (is (= :foo/type-attribute
         (namespaced-keyword-to-entity-id {:prelude prelude}
                                          :foo/type-attribute))))

(defn namespaced-keywords-to-entity-ids [namespaces-to-stream-id-functions value]
  (walk/postwalk (fn [a-value]
                   (if (keyword? a-value)
                     (namespaced-keyword-to-entity-id namespaces-to-stream-id-functions
                                                      a-value)
                     a-value))
                 value))

(deftest test-namespaced-keywords-to-entity-ids
  (is (= [{:stream-id "prelude", :id 4}]
         (namespaced-keywords-to-entity-ids {:prelude prelude}
                                            [:prelude/type-attribute])))

  (is (= {{:stream-id "prelude", :id 4}
          {:stream-id "prelude", :id 1}}
         (namespaced-keywords-to-entity-ids {:prelude prelude}
                                            {:prelude/type-attribute :prelude/type-type})))

  (is (= {{:stream-id "prelude", :id 3} "foo"}
         (namespaced-keywords-to-entity-ids {:prelude prelude}
                                            {:prelude/label "foo"}))))

(comment
  {:prelude/type-attribute :stred/notebook
   :stred/editors [{:prelude/type-attribute :stred/outline-view}]}
  ) ;; TODO: remove me


(defn type-definitions [stream-id-function keys]
  (for [key keys]
    (type (stream-id-function key) (name key))))

(defn attribute-definitions [stream-id-function keys]
  (for [key keys]
    (attribute (stream-id-function key)
               (name key)
               nil)))


(def stred-transaction (mapcat map-to-transaction/map-to-statements
                               (concat (type-definitions stred
                                                         [:notebook
                                                          :outline-view
                                                          :entity-attribute-editor
                                                          :entity-array-attribute-editor
                                                          :text-attribute-editor
                                                          :editor])
                                       (attribute-definitions stred
                                                              [:entities
                                                               :attribute
                                                               :entity
                                                               :reverse?])
                                       [(attribute (stred :editors) "editors" :array {(prelude :component?) true})
                                        (attribute (stred :views) "views" :array {(prelude :component?) true})
                                        (attribute (stred :lens) "lens" :entity {(prelude :component?) true})])))

(defn search-entities
  ([db query-string]
   (map :?entity
        (into []
              (apply query/reducible-query
                     (for [token (db-common/tokenize query-string)]
                       [(-> db :indexes :full-text :collection)
                        [(prelude :label)
                         (query/starts-with token)
                         :?entity]])))))

  ([db type query-string]
   (map :?entity
        (into []
              (apply query/reducible-query

                     [(-> db :indexes :eav :collection)
                      [:?entity
                       (prelude :type-attribute)
                       type]]

                     (for [token (db-common/tokenize query-string)]
                       [(-> db :indexes :full-text :collection)
                        [(prelude :label)
                         (query/starts-with token)
                         :?entity]]))))))

(comment

  (let [stream-db (create-dependable-stream-db-in-memory 0 index-definitions)]
    #_(transact! stream-db
                 #{[:add :tmp/t-1 0 "foo"]})
    (let [stream-db-branch (create-stream-db-branch :branch stream-db)]
      #_(transact! stream-db-branch
                   #{[:add :tmp/t-1 0 "bar"]})

      ;; (branch-changes stream-db-branch)
      #_(merge-stream-db-branch! stream-db-branch)

      [(-> stream-db-branch :indexes :eav :collection)]
      stream-db-branch))

  (do
    #_(do (fs/delete-dir path)
          (def stream-db (create-stream-db-on-disk path index-definitions)))
    (def stream-db (create-dependable-stream-db-in-memory 0 index-definitions))
    (transact! stream-db prelude-transaction)
    (transact! stream-db [[:add :tmp/a (prelude :label) "foo bar"]])
    #_(-> stream-db :indexes :full-text :collection)
    (search-entities stream-db (:attribute prelude) "l")
    )


  (into [] (sorted-reducible/subreducible (-> stream-db :indexes :eav :collection)))


  (into []
        (transaction-log/subreducible (:transaction-log stream-db)
                                      0))



  (db-common/update-indexes-2! (:db stream-db))

  (into []
        (eduction (map-indexed vector)
                  (transaction-log/subreducible (:transaction-log (:db stream-db))
                                                0)))

  (into []
        (transaction-log/subreducible (:transaction-log (:stream stream-db))
                                      0))


  )

(comment
  (font/available-names)
  )

(defn hor [margin & children]
  (apply layouts/horizontally-2 {:margin margin}
         children))

(defn chor [margin & children]
  (apply layouts/horizontally-2 {:margin margin :centered true}
         children))

(defn ver [margin & children]
  (assert (number? margin))
  (apply layouts/vertically-2 {:margin margin}
         children))

(def font (font/create-by-name "CourierNewPSMT" 40))
(def bold-font (font/create-by-name "CourierNewPS-BoldMT" 40))

(defn text [string & [{:keys [font color] :or {font font
                                               color [0 0 0 255]}}]]
  (text-area/text (str string)
                  color
                  font))

(defn box [content & [{:keys [fill-color] :or {fill-color [255 255 255 255]}}]]
  (layouts/box 3
               (visuals/rectangle-2 :fill-color fill-color
                                    :draw-color [200 200 200 255]
                                    :line-width 4
                                    :corner-arc-radius 10
                                    )
               content))

(defn entities [db attribute value]
  (map last (db-common/propositions db
                                    :ave
                                    [attribute value])))

(defn label [db entity]
  (db-common/value db entity (prelude :label)))

(defn attributes [db entity]
  (->> (db-common/propositions db
                               :eav
                               [entity])
       (map second)
       (distinct)))

(defn reverse-attributes [db entity]
  (->> (db-common/propositions db
                               :vae
                               [entity])
       (map second)
       (distinct)))

(defn highlight [content & [{:keys [fill-color] :or {fill-color [240 240 255 255]}}]]
  (layouts/box 5
               (visuals/rectangle-2 :fill-color fill-color
                                    :draw-color nil
                                    :line-width 0
                                    :corner-arc-radius 30
                                    )
               content))

(defn type-symbol [type]
  (condp = type
    (argumentation :statement) (box (layouts/with-minimum-size 40 10 (text "S"))
                                    {:fill-color [200 200 255 255]})

    (argumentation :argument) (box (layouts/with-minimum-size 40 10 (text "A"))
                                   {:fill-color [200 255 200 255]})

    (argumentation :concept) (box (layouts/with-minimum-size 40 10 (text "C"))
                                  {:fill-color [200 200 255 255]})

    (argumentation :question) (box (layouts/with-minimum-size 40 10 (text "Q"))
                                   {:fill-color [255 200 0 255]})

    (stred :notebook) (box (layouts/with-minimum-size 40 10 (text "N"))
                           {:fill-color [200 200 255 255]})

    (text "*")))

(defn open-entity [entity-id node-id state]
  (-> state
      (update :previous-entities (fn [previous-entities]
                                   (concat (take-last 4 previous-entities)
                                           (if (:entity state)
                                             [{:entity (:entity state)
                                               :node-id node-id}]
                                             []))))
      (assoc :entity entity-id)))

(defn open-entity! [state-atom entity-id & [{:keys [node-id]}]]
  (swap! state-atom (partial open-entity entity-id node-id)))

(defn focus-highlight-keyboard-event-handler [state-atom _subtree event]
  (cond (and (= :descent (:phase event))
             (= :focus-gained (:type event)))
        (swap! state-atom assoc :focused? true)


        (and (= :descent (:phase event))
             (= :focus-lost (:type event)))
        (swap! state-atom assoc :focused? false))

  event)

(defn focus-highlight [_child]
  (let [state-atom (dependable-atom/atom {})]
    (fn [child]
      (-> (highlight child
                     {:fill-color (if (keyboard/sub-component-is-focused?)
                                    #_(:focused? @state-atom)
                                    [240 240 255 255]
                                    [255 255 255 0])})
          #_(assoc :keyboard-event-handler [focus-highlight-keyboard-event-handler state-atom])))))

(defn on-click-mouse-event-handler [on-clicked node event]
  (when (= :mouse-clicked (:type event))
    (on-clicked))
  event)

(defn focus-on-click-mouse-event-handler [node event]
  (when (= :mouse-clicked (:type event))
    (keyboard/set-focused-node! node))
  event)

(defn entity-symbol [state-atom entity-type entity-id]
  [focus-highlight (-> (type-symbol entity-type)
                       (assoc :mouse-event-handler [on-click-mouse-event-handler (partial open-entity! state-atom entity-id)]
                              :entity entity-id
                              :can-gain-focus? true))])

(defn- entity-view [db state-atom entity-id]
  (chor 10
        (entity-symbol state-atom
                       (db-common/value db
                                        entity-id
                                        (prelude :type-attribute))
                       entity-id)
        (text (label db entity-id))))

(defn entity-string [db entity-id]
  (str "["
       (:stream-id entity-id)
       "/"
       (:id entity-id)
       (when-let [label (db-common/value db entity-id (prelude :label))]
         (str " " label))
       (when-let [type (db-common/value db entity-id (prelude :type-attribute))]
         (when-let [label (db-common/value db type (prelude :label))]
           (str " / " label)))
       "]"))


(defn value-string [db value]
  (if (entity-id/entity-id? value)
    (entity-string db value)
    (pr-str value)))

(defn value-string [db value]
  (if (or (entity-id/entity-id? value)
          (temporary-ids/temporary-id? value))
    (str "["
         (if (entity-id/entity-id? value)
           (str (:stream-id value)
                "/"
                (or (label db value)
                    (:id value)))
           (pr-str value))

         (when-let [type (db-common/value db
                                          value
                                          (prelude :type-attribute))]
           (if (entity-id/entity-id? type)
             (str "::"
                  (:stream-id type)
                  "/"
                  (label db type))
             (str type)))
         "]")
    (pr-str value)))

(def column-width 1200)

(defn value-view [db value]
  ;;(text (value-string db value))

  (if (or (entity-id/entity-id? value)
          (temporary-ids/temporary-id? value))
    (hor 10
         (let [type (db-common/value db
                                     value
                                     (prelude :type-attribute))]
           (box (if type
                  (text (or (label db type)
                            (value-string db type)))
                  {:width 35
                   :height 35})
                {:fill-color [200 200 255 255]}))
         (layouts/with-maximum-size column-width nil (text (or (label db value)
                                                               (value-string db value)))))
    (text (pr-str value))))

(defn scene-graph-to-string [scene-graph]
  (with-out-str (clojure.pprint/pprint (scene-graph/map-nodes #(select-keys % [:type
                                                                               :local-id
                                                                               :id
                                                                               :can-gain-focus?])
                                                              scene-graph))))

(defn bare-text-editor [text on-text-change]
  [text-area/text-area-3 {:style {:color [0 0 0 255]
                                  :font  font}
                          :text text
                          :on-text-change on-text-change}])

(defn bare-text-editor-2 [text on-change & [{:keys [validate-new-text]}]]
  [text-area/text-area-3 {:style {:color [0 0 0 255]
                                  :font  font}
                          :text text
                          :on-change on-change
                          :validate-new-text validate-new-text}])

(defn text-editor [text on-text-change]
  (box (layouts/with-minimum-size 300 nil
         (bare-text-editor text on-text-change))))

(defn button-mouse-event-handler [on-pressed node event]
  (when (= :mouse-clicked (:type event))
    (on-pressed))
  event)

(defn button [label on-pressed]
  (assoc (layouts/box 15

                      (text label))
         :mouse-event-handler [button-mouse-event-handler on-pressed]))

(defn parse-integer [string]
  (try (Integer/parseInt string)
       (catch Exception e
         nil)))

(defn editor-keyboard-event-handler [on-enter on-escape node event]
  (cond (and (= :descent (:phase event))
             (= :key-pressed (:type event))
             (= :enter (:key event)))
        (do (on-enter)
            nil)

        (and (= :descent (:phase event))
             (= :key-pressed (:type event))
             (= :escape (:key event)))
        (do (on-escape)
            nil)

        :else
        event))

(defn number-editor [given-number _on-change!]
  (let [state-atom (dependable-atom/atom {:number given-number
                                          :given-number given-number})]
    (fn [given-number on-change!]
      (let [state @state-atom]
        (when (not (= given-number (:given-number state)))
          (swap! state-atom
                 assoc
                 :number given-number
                 :given-number given-number))

        (-> (box (layouts/with-minimum-size 80 nil
                   (bare-text-editor-2 (str (:number state))
                                       (fn [old-state new-state]
                                         (let [text-changed? (not= (:text new-state)
                                                                   (:text old-state))
                                               new-integer (parse-integer (:text new-state))]

                                           (cond (and text-changed?
                                                      (empty? (:text new-state)))
                                                 (do (swap! state-atom assoc :number nil)
                                                     new-state)

                                                 (and text-changed?
                                                      new-integer)
                                                 (do (swap! state-atom assoc :number new-integer)
                                                     new-state)

                                                 (and text-changed?
                                                      (not new-integer))
                                                 old-state

                                                 :else
                                                 new-state)))))
                 {:fill-color (when (not (= given-number (:number state)))
                                [240 240 255 255])})
            (assoc :keyboard-event-handler [editor-keyboard-event-handler
                                            (fn []
                                              (if (nil? (:number state))
                                                (swap! state-atom assoc :number given-number)
                                                (on-change! (:number state))))

                                            (fn []
                                              (swap! state-atom assoc :number given-number))]))))))

(defn text-editor-2 [given-text _on-change!]
  (let [state-atom (dependable-atom/atom {:text given-text
                                          :given-text given-text})]
    (fn text-editor-2 [given-text on-change!]
      (let [state @state-atom]
        (when (not (= given-text (:given-text state)))
          (swap! state-atom
                 assoc
                 :text given-text
                 :given-text given-text))

        [focus-highlight (-> (box (layouts/with-minimum-size 80 nil
                                    (bare-text-editor-2 (str (:text state))
                                                        (fn on-change [old-state new-state]
                                                          (let [text-changed? (not= (:text new-state)
                                                                                    (:text old-state))]

                                                            (cond ;; (and text-changed?
                                                              ;;      (empty? (:text new-state)))
                                                              ;; (do (swap! state-atom assoc :text nil)
                                                              ;;     new-state)

                                                              text-changed?
                                                              (do (swap! state-atom assoc :text (:text new-state))
                                                                  new-state)

                                                              :else
                                                              new-state)))))
                                  {:fill-color (if (not (= given-text (:text state)))
                                                 [240 240 255 255]
                                                 [255 255 255 255])})
                             (assoc :keyboard-event-handler [editor-keyboard-event-handler
                                                             (fn on-enter []
                                                               (if (nil? (:text state))
                                                                 (swap! state-atom assoc :text given-text)
                                                                 (on-change! (:text state))))

                                                             (fn on-escape []
                                                               (swap! state-atom assoc :text given-text))]))]))))

;; (defn attribute-selector [db]
;;   (let [state-atom (dependable-atom/atom {:text ""})]
;;     (fn [db]
;;       (let [state @state-atom]
;;         (layouts/vertically-2 {}
;;                               (text-editor (:text state)
;;                                            (fn [new-text]
;;                                              (swap! state-atom
;;                                                     assoc
;;                                                     :results (search-entities db
;;                                                                               (prelude :attribute)
;;                                                                               new-text)
;;                                                     :text new-text)))
;;                               (when (not (empty? (:text state)))
;;                                 (layouts/hover {:z 2}
;;                                                (box (layouts/vertically-2 {}
;;                                                                           (map (partial value-view db)
;;                                                                                (:results state)))))))))))

(defn text-attribute-editor [db entity attribute]
  (layouts/with-maximum-size column-width nil
    ^{:local-id [entity attribute]}
    [text-editor-2
     (db-common/value db entity attribute)
     (fn [new-value]
       (if (= "" new-value)
         (transact! db [[:remove entity attribute (db-common/value db entity attribute)]])
         (transact! db [[:set entity attribute new-value]])))]))

(defn outline [db entity]
  (layouts/vertically-2 {:margin 10}
                        (layouts/box 10
                                     (visuals/rectangle-2 :fill-color [255 255 255 255]
                                                          :draw-color [200 200 200 255]
                                                          :line-width 4
                                                          :corner-arc-radius 30)
                                     (layouts/center-horizontally (text (str (label db entity)))))

                        (layouts/vertically-2 {:margin 10}
                                              (for [attribute (attributes db entity)]
                                                (layouts/horizontally-2 {:margin 10}
                                                                        (text (str (label db attribute) ":"))
                                                                        (layouts/vertically-2 {:margin 10}
                                                                                              (for [value (into [] (db-common/values db entity attribute))]
                                                                                                (value-view db value)))))
                                              (for [attribute (reverse-attributes db entity)]
                                                (layouts/horizontally-2 {:margin 10}
                                                                        (text (str "<-" (label db attribute) ":"))
                                                                        (layouts/vertically-2 {:margin 10}
                                                                                              (for [referring-entity (into [] (entities db attribute entity))]
                                                                                                (text (label db referring-entity)))))))))

(defn- create-entity [db type label]
  (-> (transact! db
                 [[:add
                   :tmp/new-entity
                   (prelude :type-attribute)
                   type]

                  [:add
                   :tmp/new-entity
                   (prelude :label)
                   label]])
      :temporary-id-resolution
      :tmp/new-entity))

(defn- create-run-create-entity [state-atom db on-entity-change type]
  (fn [_subtree]
    (let [label (:text @state-atom)]
      (swap! state-atom
             assoc :text "")
      (on-entity-change (create-entity db
                                       type
                                       label)))))

(defn prompt-command-set [state-atom db on-entity-change]
  (let [state @state-atom]
    {:name "prompt"
     :commands [{:name "create question"
                 :available? (not (empty? (:text state)))
                 :key-patterns [[#{:control} :c] [#{:control} :n]]
                 :run! (create-run-create-entity state-atom db on-entity-change (argumentation :question))}

                {:name "create question"
                 :available? (not (empty? (:text state)))
                 :key-patterns [[#{:control} :c] [#{:control} :q]]
                 :run! (create-run-create-entity state-atom db on-entity-change (argumentation :question))}

                {:name "create statement"
                 :available? (not (empty? (:text state)))
                 :key-patterns [[#{:control} :c] [#{:control} :s]]
                 :run! (create-run-create-entity state-atom db on-entity-change (argumentation :statement))}

                {:name "create concept"
                 :available? (not (empty? (:text state)))
                 :key-patterns [[#{:control} :c] [#{:control} :c]]
                 :run! (create-run-create-entity state-atom db on-entity-change (argumentation :concept))}

                ;; {:name "create notebook"
                ;;  :available? (not (empty? (:text state)))
                ;;  :key-patterns [[#{:control} :c] [#{:control} :n]]
                ;;  :run! (create-run-create-entity state-atom db on-entity-change (stred :notebook))}

                {:name "commit selection"
                 :available? (not (empty? (:results state)))
                 :key-patterns [[#{} :enter]]
                 :run! (fn [_subtree]
                         (swap! state-atom
                                assoc :text ""
                                :results [])
                         (on-entity-change (nth (vec (:results state))
                                                (:selected-index state))))}

                {:name "select next"
                 :available? (and (not (empty? (:results state)))
                                  (< (:selected-index state)
                                     (dec (count (:results state)))))
                 :key-patterns [[#{:meta} :n]]
                 :run! (fn [_subtree]
                         (swap! state-atom
                                update :selected-index inc))}

                {:name "select previous"
                 :available? (and (not (empty? (:results state)))
                                  (< 0
                                     (:selected-index state)))
                 :key-patterns [[#{:meta} :p]]
                 :run! (fn [_subtree]
                         (swap! state-atom
                                update :selected-index dec))}]}))

;; (defn prompt-keyboard-event-handler [create-statement node event]
;; ;;  (prn 'event event) ;; TODO: remove-me

;;   (cond (and (= :descent (:phase event))
;;              (= :key-pressed (:type event))
;;              (= :c (:key event))
;;              (:control? event))
;;         (do (create-statement)
;;             nil)

;;         :else
;;         event))


;; (defn prompt-keyboard-event-handler [state-atom _subtree event]
;;   (cond (and (= :descent (:phase event))
;;              (= :focus-gained (:type event)))
;;         (swap! state-atom assoc :focused? true)


;;         (and (= :descent (:phase event))
;;              (= :focus-lost (:type event)))
;;         (swap! state-atom assoc :focused? false))

;;   event)

(defn prompt [_db _types _on-entity-change]
  (let [state-atom (dependable-atom/atom "prompt-state"
                                         {:text ""
                                          :selected-index 0})]
    (fn [db types on-entity-change]
      (let [state @state-atom]
        (-> (ver 0
                 [focus-highlight (-> (text-editor (:text state)
                                                   (fn [new-text]
                                                     (let [entities (if (= "" new-text)
                                                                      []
                                                                      (if types
                                                                        (distinct (mapcat (fn [type]
                                                                                            (search-entities db type new-text))
                                                                                          types))
                                                                        (distinct (search-entities db new-text))))]
                                                       (swap! state-atom
                                                              (fn [state]
                                                                (assoc state
                                                                       :results entities
                                                                       :selected-index 0
                                                                       :text new-text))))))
                                      (assoc :local-id :prompt-editor))]
                 (when (and (keyboard/sub-component-is-focused?)
                            (not (empty? (:results state))))
                   (layouts/hover {:z 4}
                                  (box (layouts/vertically-2 {}
                                                             (map-indexed (fn [index statement]
                                                                            (-> (highlight (value-view db statement)
                                                                                           {:fill-color (if (= index (:selected-index state))
                                                                                                          [240 240 255 255]
                                                                                                          [255 255 255 255])})
                                                                                (assoc :mouse-event-handler [on-click-mouse-event-handler (fn []
                                                                                                                                            (on-entity-change statement))])))
                                                                          (:results state)))))))
            (assoc :command-set (prompt-command-set state-atom db on-entity-change)
                                        ;                   :keyboard-event-handler [prompt-keyboard-event-handler state-atom]
                   ))))))

(defn prompt-2-command-set [state-atom commands]
  (let [state @state-atom]
    {:name "prompt"
     :commands (concat (filter :key-patterns
                               commands)
                       [{:name "commit selection"
                         :available? (not (empty? (:results state)))
                         :key-patterns [[#{} :enter]]
                         :run! (fn [subtree]
                                 (swap! state-atom
                                        assoc :text ""
                                        :results [])
                                 ((:run! (nth (vec commands)
                                              (:selected-index state)))
                                  subtree))}

                        {:name "select next"
                         :available? (and (not (empty? commands))
                                          (< (:selected-index state)
                                             (dec (count commands))))
                         :key-patterns [[#{:meta} :n]]
                         :run! (fn [_subtree]
                                 (swap! state-atom
                                        update :selected-index inc))}

                        {:name "select previous"
                         :available? (and (not (empty? commands))
                                          (< 0
                                             (:selected-index state)))
                         :key-patterns [[#{:meta} :p]]
                         :run! (fn [_subtree]
                                 (swap! state-atom
                                        update :selected-index dec))}])}))

(def modifier-key-to-string {:control "C"
                             :meta "M"})

(defn key-pattern-to-string [key-pattern]
  (string/join "-"
               (concat (map modifier-key-to-string (sort (first key-pattern)))
                       [(name (second key-pattern))])))

(deftest test-key-pattern-to-string
  (is (= "M-c"
         (key-pattern-to-string [#{:meta} :c])))

  (is (= "C-M-c"
         (key-pattern-to-string [#{:control :meta} :c])))

  (is (= "c"
         (key-pattern-to-string [#{} :c]))))

(defn key-patterns-to-string [key-patterns]
  (string/join " " (map key-pattern-to-string key-patterns)))

(deftest test-key-patterns-to-string
  (is (= "M-c C-d"
         (key-patterns-to-string [[#{:meta} :c]
                                  [#{:control} :d]] )))
  (is (= ""
         (key-patterns-to-string nil))))

(defn prompt-2 [_run-query _commands]
  (let [state-atom (dependable-atom/atom "prompt-state"
                                         {:text ""
                                          :selected-index 0})]
    (fn [run-query commands]
      (let [state @state-atom]
        (-> (ver 0
                 [focus-highlight (-> (text-editor (:text state)
                                                   (fn [new-text]
                                                     (let [entities (if (= "" new-text)
                                                                      []
                                                                      (run-query new-text))]

                                                       (swap! state-atom
                                                              (fn [state]
                                                                (assoc state
                                                                       :results entities
                                                                       :selected-index 0
                                                                       :text new-text))))))
                                      (assoc :local-id :prompt-editor))]
                 (when (and (keyboard/sub-component-is-focused?)
                            (not (empty? (:results state))))
                   (layouts/hover {:z 4}
                                  (box (layouts/vertically-2 {}
                                                             (map-indexed (fn [index command]
                                                                            (-> (highlight (chor 40
                                                                                                 (text (key-patterns-to-string (:key-patterns command)))
                                                                                                 (or (:view command)
                                                                                                     (text (:name command))))
                                                                                           {:fill-color (if (= index (:selected-index state))
                                                                                                          [240 240 255 255]
                                                                                                          [255 255 255 255])})
                                                                                (assoc :mouse-event-handler [on-click-mouse-event-handler (fn []
                                                                                                                                            ((:run! command)))])))
                                                                          (commands (:results state))))))))
            (assoc :command-set (prompt-2-command-set state-atom
                                                      (commands (:results state)))))))))


;; from https://stackoverflow.com/a/26442057
(defn insert [sequence index value]
  (let [[before after] (split-at index sequence)]
    (vec (concat before [value] after))))

(deftest test-insert
  (is (= '(1 :x 2 3)
         (insert [1 2 3] 1 :x)))

  (is (= '(:x 1 2 3)
         (insert [1 2 3] 0 :x)))

  (is (= '(1 2 3 :x)
         (insert [1 2 3] 10 :x))))

(defn drop-index [dropped-index sequence]
  (loop [index 0
         sequence sequence
         result []]
    (if (empty? sequence)
      result
      (if (= index dropped-index)
        (recur (inc index)
               (rest sequence)
               result)
        (recur (inc index)
               (rest sequence)
               (conj result (first sequence)))))))

(deftest test-drop-index
  (is (= [2]
         (drop-index 0 [1 2])))

  (is (= [1]
         (drop-index 1 [1 2])))

  (is (= [1 3]
         (drop-index 1 [1 2 3])))

  (is (= []
         (drop-index 1 [])))

  (is (= [1 2 3]
         (drop-index -1 [1 2 3])))

  (is (= [1 2 3]
         (drop-index 10 [1 2 3]))))

(defn move-left [index sequence]
  (assert (and (< 0 index)
               (< index (count sequence))))

  (concat (take (dec index)
                sequence)
          [(nth sequence index)]
          [(nth sequence (dec index))]
          (drop (inc index)
                sequence)))

(deftest test-move-left
  (is (thrown? AssertionError
               (move-left 0 [1 2 3])))

  (is (= '(2 1 3)
         (move-left 1 [1 2 3])))

  (is (= '(1 3 2)
         (move-left 2 [1 2 3]))))

(defn move-right [index sequence]
  (assert (and (<= 0 index)
               (< index (dec (count sequence)))))

  (concat (take index
                sequence)
          [(nth sequence (inc index))]
          [(nth sequence index)]
          (drop (+ index 2)
                sequence)))

(deftest test-move-right
  (is (= '(2 1 3)
         (move-right 0 [1 2 3])))

  (is (= '(1 3 2)
         (move-right 1 [1 2 3])))

  (is (thrown? AssertionError
               (move-right 2 [1 2 3]))))

(defn focus-first-focusable-child [scene-graph entity-array-editor-node-id]
  (->> (scene-graph/find-first #(= entity-array-editor-node-id
                                   (:id %))
                               scene-graph)
       (scene-graph/find-first-child :can-gain-focus?)
       (keyboard/set-focused-node!)))


(defn focus-insertion-prompt [scene-graph parent-node-id]
  (->> (scene-graph/find-first-breath-first #(= parent-node-id
                                                (:id %))
                                            scene-graph)
       (scene-graph/find-first-breath-first #(= :insertion-prompt
                                                (:local-id %)))
       (scene-graph/find-first-breath-first :can-gain-focus?)
       (keyboard/set-focused-node!)))

(defn array-editor-command-set [state-atom db entity attribute allow-array-spreading? item-removal-transaction item-commands]
  (let [state @state-atom
        array (db-common/value db entity attribute)]
    {:name "array editor"
     :commands (concat (when (and item-commands
                                  (:selected-index state))
                         (item-commands (nth array
                                             (:selected-index state))))
                       [{:name "cancel insertion"
                         :available? (:insertion-index state)
                         :key-patterns [[#{} :escape]]
                         :run! (fn [_subtree]
                                 (swap! state-atom dissoc :insertion-index))}

                        {:name "delete selected"
                         :available? (and (:selected-index state)
                                          (not (:insertion-index state))
                                          (not (empty? array)))
                         :key-patterns [[#{:control} :d]]
                         :run! (fn [subtree]
                                 (transact! db (concat (item-removal-transaction (nth array (:selected-index state)))
                                                       [[:set entity attribute (drop-index (:selected-index state)
                                                                                           array)]]))
                                 (if (= 1 (count array))
                                   (swap! state-atom dissoc :selected-index)
                                   (swap! state-atom update :selected-index #(min % (- (count array)
                                                                                       2))))

                                 (when (< 1 (count array))
                                   (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                        (->> (scene-graph/find-first #(= (:id subtree)
                                                                                                         (:id %))
                                                                                                     scene-graph)
                                                                             (scene-graph/find-first-child #(= [:value (min (:selected-index state)
                                                                                                                            (- (count array)
                                                                                                                               2))]
                                                                                                               (:local-id %)))
                                                                             (keyboard/set-focused-node!))))))}

                        {:name "insert above"
                         :available? (:selected-index state)
                         :key-patterns [[#{:control :shift} :i]]
                         :run! (fn [subtree]
                                 (swap! state-atom
                                        (fn [state]
                                          (-> state
                                              (assoc :insertion-index (:selected-index state))
                                              (dissoc :selected-index))))
                                 (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                      (focus-insertion-prompt scene-graph
                                                                                              (:id subtree)))))}

                        {:name "insert below"
                         :available? (:selected-index state)
                         :key-patterns [[#{:control} :i]]
                         :run! (fn [subtree]
                                 (swap! state-atom
                                        (fn [state]
                                          (-> state
                                              (assoc :insertion-index (inc (:selected-index state)))
                                              (dissoc :selected-index))))
                                 (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                      (focus-insertion-prompt scene-graph
                                                                                              (:id subtree)))))}

                        {:name "move up"
                         :available? (and (:selected-index state)
                                          (< 0 (:selected-index state)))
                         :key-patterns [[#{:control} :p]]
                         :run! (fn [subtree]
                                 (transact! db [[:set entity attribute (vec (move-left (:selected-index state)
                                                                                       array))]])

                                 (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                      (->> (scene-graph/find-first #(= (:id subtree)
                                                                                                       (:id %))
                                                                                                   scene-graph)
                                                                           (scene-graph/find-first-child #(= [:value (dec (:selected-index state))]
                                                                                                             (:local-id %)))
                                                                           (keyboard/set-focused-node!)))))}

                        {:name "move down"
                         :available? (and (:selected-index state)
                                          (< (:selected-index state)
                                             (dec (count array))))
                         :key-patterns [[#{:control} :n]]
                         :run! (fn [subtree]
                                 (transact! db [[:set entity attribute (vec (move-right (:selected-index state)
                                                                                        array))]])

                                 (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                      (->> (scene-graph/find-first #(= (:id subtree)
                                                                                                       (:id %))
                                                                                                   scene-graph)
                                                                           (scene-graph/find-first-child #(= [:value (inc (:selected-index state))]
                                                                                                             (:local-id %)))
                                                                           (keyboard/set-focused-node!)))))}]
                       (when allow-array-spreading?
                         [{:name "spread array"
                           :available? true
                           :key-patterns [[#{:control} :a]]
                           :run! (fn [_subtree]
                                   (transact! db (into [[:remove entity attribute array]]
                                                       (for [value array]
                                                         [:add entity attribute value]))))}]))}))

(defn array-editor-item-view-keyboard-event-handler [state-atom index _subtree event]
  (cond (and (= :on-target (:phase event))
             (= :focus-gained (:type event)))
        (swap! state-atom assoc :selected-index index)


        (and (= :on-target (:phase event))
             (= :focus-lost (:type event)))
        (swap! state-atom dissoc :selected-index))

  event)

(defn array-editor [_db _entity _attribute _item-removal-transaction _new-item-transaction _run-query _available-items _item-view & [_options]]
  (let [state-atom (dependable-atom/atom "array-editor-state"
                                         {})]
    (fn array-editor [db entity attribute item-removal-transaction new-item-transaction run-query available-items item-view & [{:keys [item-commands
                                                                                                                                       show-empty-prompt?
                                                                                                                                       allow-array-spreading?]
                                                                                                                                :or {show-empty-prompt? true
                                                                                                                                     allow-array-spreading? false}}]]
      (let [state @state-atom
            array (db-common/value db entity attribute)
            array-editor-node-id view-compiler/id]
        (if (and (empty? array)
                 show-empty-prompt?)
          ^{:local-id :insertion-prompt}
          [prompt-2
           run-query
           (fn [results]
             (for [item (available-items results)]
               (assoc item
                      :run! (fn [_subtree]
                              (let [{:keys [item-id transaction]} (new-item-transaction item)]
                                (transact! db (concat transaction
                                                      [[:set entity attribute [item-id]]])))))))]
          (-> (ver 0
                   (map-indexed (fn [index value-entity]
                                  (let [item-view (-> (highlight (with-meta [item-view db value-entity]
                                                                   {:mouse-event-handler [focus-on-click-mouse-event-handler]
                                                                    :can-gain-focus? true
                                                                    :array-value true
                                                                    :local-id [:value index]
                                                                    :keyboard-event-handler [array-editor-item-view-keyboard-event-handler
                                                                                             state-atom
                                                                                             index]})
                                                                 {:fill-color (if (= index (:selected-index state))
                                                                                [240 240 255 255]
                                                                                [255 255 255 0])}))
                                        insertion-prompt ^{:local-id :insertion-prompt} [prompt-2
                                                                                         run-query
                                                                                         (fn [results]
                                                                                           (for [item (available-items results)]
                                                                                             (assoc item
                                                                                                    :run! (fn [subtree]
                                                                                                            (let [{:keys [item-id transaction]} (new-item-transaction item)]
                                                                                                              (transact! db (concat transaction
                                                                                                                                    [[:set entity attribute (insert array (:insertion-index state) item-id)]])))
                                                                                                            (swap! state-atom dissoc :insertion-index)

                                                                                                            (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                                                                                                 (->> (scene-graph/find-first-breath-first #(= array-editor-node-id
                                                                                                                                                                                               (:id %))
                                                                                                                                                                                           scene-graph)
                                                                                                                                                      (scene-graph/find-first-breath-first #(= [:value (:insertion-index state)]
                                                                                                                                                                                               (:local-id %)))
                                                                                                                                                      (scene-graph/find-first-breath-first :can-gain-focus?)
                                                                                                                                                      (keyboard/set-focused-node!))))))))]]

                                    (cond (and (= index (dec (count array)))
                                               (= (inc index) (:insertion-index state)))
                                          (ver 0
                                               item-view
                                               insertion-prompt)

                                          (= index (:insertion-index state))
                                          (ver 0
                                               insertion-prompt
                                               item-view)

                                          :else
                                          item-view)))
                                array)
                   )
              (assoc ;; :keyboard-event-handler [entity-array-attribute-editor-keyboard-event-handler state-atom]
               :command-set (array-editor-command-set state-atom
                                                      db
                                                      entity
                                                      attribute
                                                      allow-array-spreading?
                                                      item-removal-transaction
                                                      item-commands)
               ;; :can-gain-focus? true
               )))))))

(defn entity-array-attribute-editor-command-set [state-atom db entity attribute]
  (let [state @state-atom
        array (db-common/value db entity attribute)]
    {:name "entity array editor"
     :commands [{:name "cancel insertion"
                 :available? (:insertion-index state)
                 :key-patterns [[#{} :escape]]
                 :run! (fn [_subtree]
                         (swap! state-atom dissoc :insertion-index))}

                {:name "delete selected"
                 :available? (and (not (:insertion-index state))
                                  (not (empty? array)))
                 :key-patterns [[#{:control} :d]]
                 :run! (fn [subtree]
                         (transact! db [[:set entity attribute (drop-index (:selected-index state)
                                                                           array)]])

                         (when (< 1 (count array))
                           (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                (->> (scene-graph/find-first #(= (:id subtree)
                                                                                                 (:id %))
                                                                                             scene-graph)
                                                                     (scene-graph/find-first-child #(= [:value (min (:selected-index state)
                                                                                                                    (- (count array)
                                                                                                                       2))]
                                                                                                       (:local-id %)))
                                                                     (keyboard/set-focused-node!))))))}

                {:name "insert above"
                 :available? true
                 :key-patterns [[#{:control} :i]]
                 :run! (fn [subtree]
                         (swap! state-atom
                                (fn [state]
                                  (-> state
                                      (assoc :insertion-index (:selected-index state))
                                      (dissoc :selected-index))))
                         (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                              (focus-insertion-prompt scene-graph
                                                                                      (:id subtree)))))}
                {:name "insert below"
                 :available? true
                 :key-patterns [[#{:control :shift} :i]]
                 :run! (fn [subtree]
                         (swap! state-atom
                                (fn [state]
                                  (-> state
                                      (assoc :insertion-index (inc (:selected-index state)))
                                      (dissoc :selected-index))))
                         (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                              (focus-insertion-prompt scene-graph
                                                                                      (:id subtree)))))}]}))

(defn entity-array-attribute-editor-value-view-keyboard-event-handler [state-atom index _subtree event]
  ;; (prn 'event event)

  (cond (and (= :on-target (:phase event))
             (= :focus-gained (:type event)))
        (swap! state-atom assoc :selected-index index)


        (and (= :on-target (:phase event))
             (= :focus-lost (:type event)))
        (swap! state-atom dissoc :selected-index))

  event)

(defn entity-array-attribute-editor [_db _entity _attribute]
  (let [state-atom (dependable-atom/atom "entity-array-attribute-editor-state"
                                         {})]
    (fn entity-array-attribute-editor [db entity attribute]


      (let [state @state-atom
            array (db-common/value db entity attribute)
            entity-array-attribute-editor-node-id view-compiler/id]
        (if (empty? array)
          ^{:local-id :insertion-prompt}
          [prompt
           db
           [(argumentation :statement)]
           (fn on-new-entity [new-entity]
             (transact! db [[:set entity attribute [new-entity]]]))]
          (-> (ver 0 (map-indexed (fn [index value-entity]
                                    (let [value-view (-> (highlight (value-view db value-entity)
                                                                    {:fill-color (if (= index (:selected-index state))
                                                                                   [240 240 255 255]
                                                                                   [255 255 155 0])})
                                                         (assoc :mouse-event-handler [focus-on-click-mouse-event-handler]
                                                                :can-gain-focus? true
                                                                :array-value true
                                                                :local-id [:value index]
                                                                :entity value-entity
                                                                :keyboard-event-handler [entity-array-attribute-editor-value-view-keyboard-event-handler
                                                                                         state-atom
                                                                                         index]))]

                                      (let [insertion-prompt ^{:local-id :insertion-prompt} [prompt
                                                                                             db
                                                                                             [(argumentation :statement)]
                                                                                             (fn on-new-entity [new-entity]
                                                                                               (transact! db [[:set entity attribute (insert array (:insertion-index state) new-entity)]])
                                                                                               (swap! state-atom dissoc :insertion-index)

                                                                                               (keyboard/handle-next-scene-graph! (fn [scene-graph]


                                                                                                                                    (->> (scene-graph/find-first #(= entity-array-attribute-editor-node-id
                                                                                                                                                                     (:id %))
                                                                                                                                                                 scene-graph)
                                                                                                                                         (scene-graph/find-first-child #(= [:value (:insertion-index state)]
                                                                                                                                                                           (:local-id %)))
                                                                                                                                         (keyboard/set-focused-node!)))))]]
                                        (cond (and (= index (dec (count array)))
                                                   (= (inc index) (:insertion-index state)))
                                              (ver 0
                                                   value-view
                                                   insertion-prompt)

                                              (= index (:insertion-index state))
                                              (ver 0
                                                   insertion-prompt
                                                   value-view)

                                              :else
                                              value-view))))
                                  array))
              (assoc ;; :keyboard-event-handler [entity-array-attribute-editor-keyboard-event-handler state-atom]
               :command-set (entity-array-attribute-editor-command-set state-atom
                                                                       db
                                                                       entity
                                                                       attribute)
               ;; :can-gain-focus? true
               )))))))

(defn entity-array-attribute-editor-2 [db entity attribute]
  [array-editor
   db
   entity
   attribute

   (fn item-removal-transaction [_item]
     [])

   (fn new-item-transaction [new-item]
     {:item-id (if (:entity new-item)
                 (:entity new-item)
                 :tmp/new-entity)
      :transaction (if (:entity new-item)
                     []
                     [[:add
                       :tmp/new-entity
                       (prelude :label)
                       (:label new-item)]])})

   (fn run-query [text]
     {:text text
      :entities (distinct (search-entities db
                                           text))})

   (fn available-items [results]
     (concat (for [entity (:entities results)]
               {:entity entity
                :view (value-view db entity)})
             (when (:text results)
               [{:name "Create entity "
                 :available? (constantly true)
                 :key-patterns [[#{:control} :c] [#{:control} :n]]
                 :label (:text results)}])))

   (fn item-view [db entity]
     (value-view db entity))
   {:allow-array-spreading? true}])



(comment
  (scene-graph/print-scene-graph (scene-graph/select-node-keys [:id]
                                                               (->> (scene-graph/find-first-breath-first #(= array-editor-node-id
                                                                                                             (:id %))
                                                                                                         scene-graph)
                                                                    #_(scene-graph/find-first-breath-first #(= :insertion-prompt
                                                                                                               (:local-id %)))
                                                                    #_(scene-graph/find-first-breath-first :can-gain-focus?))))


  ) ;; TODO: remove me


(defn sort-entity-ids [entity-ids]
  (sort-by (fn [entity-id]
             [(:stream-id entity-id)
              (:id entity-id)])
           entity-ids))

(derivation/def-derivation focused-node-id
  (:focused-node-id @keyboard/state-atom))

(derivation/def-derivation focused-subtrees-with-command-sets
  (if-let [focused-node-id @focused-node-id]
    (->> (scene-graph/path-to (:scene-graph @keyboard/state-atom)
                              focused-node-id)
         (filter :command-set)
         (reverse))
    []))

(defn entity-attribute-editor-command-set [state-atom db entity attribute values reverse?]
  (let [state @state-atom]
    {:name "entity attribute editor"
     :commands [{:name "cancel adding"
                 :available? (and (not (empty? values))
                                  (:adding? state))
                 :key-patterns [[#{} :escape]]
                 :run! (fn [_subtree]
                         (swap! state-atom assoc :adding? false))}

                {:name "remove selected"
                 :available? (not (empty? values))
                 :key-patterns [[#{:control} :d]]
                 :run! (fn [subtree]
                         (transact! db (if reverse?
                                         (->> (concat (common/changes-to-remove-entity-references db entity)
                                                      (common/changes-to-remove-sequence-references db entity))
                                              (filter (fn [change]
                                                        (= (common/change-entity change)
                                                           (nth values (:selected-index state))))))
                                         [[:remove entity attribute (nth values
                                                                         (:selected-index state))]]))

                         (when (< 1 (count values))
                           (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                (->> (scene-graph/find-first #(= (:id subtree)
                                                                                                 (:id %))
                                                                                             scene-graph)
                                                                     (scene-graph/find-first-child #(= [:value (min (:selected-index state)
                                                                                                                    (- (count values)
                                                                                                                       2))]
                                                                                                       (:local-id %)))
                                                                     (scene-graph/find-first :can-gain-focus?)
                                                                     (keyboard/set-focused-node!))))))}


                {:name "make array"
                 :available? (not reverse?)
                 :key-patterns [[#{:control} :a]]
                 :run! (fn [_subtree]
                         (transact! db (into [[:add entity attribute (vec values)]]
                                             (common/changes-to-remove-entity-property (common/deref db) entity attribute))))}

                {:name "insert"
                 :available? true
                 :key-patterns [[#{:control} :i]]
                 :run! (fn [subtree]
                         (swap! state-atom
                                assoc :adding? true)
                         (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                              (->> (scene-graph/find-first #(= (:id subtree)
                                                                                               (:id %))
                                                                                           scene-graph)
                                                                   (scene-graph/find-first-child #(= :adding-prompt
                                                                                                     (:local-id %)))
                                                                   (scene-graph/find-first-child :can-gain-focus?)
                                                                   (keyboard/set-focused-node!)))))}]}))
(defn assoc-last [& arguments]
  (apply assoc (last arguments)
         (drop-last arguments)))

(deftest test-assoc-last
  (is (= {:y 2, :x 1}
         (assoc-last :x 1 {:y 2}))))


(defn focus-on-prompt [scene-graph]
  (->> scene-graph
       (scene-graph/find-first #(= :prompt (:local-id %)))
       (scene-graph/find-first :can-gain-focus?)
       keyboard/set-focused-node!))

(defn starts-with? [sequence-1 sequence-2]
  (= (take (count sequence-1)
           sequence-2)
     sequence-1))

(declare outline-view)

(defn entity-attribute-editor [_db _entity _attribute _value-lens & _options]
  (let [state-atom (dependable-atom/atom "entity-attribute-editor-state"
                                         {})]
    (fn [db entity attribute value-lens & [{:keys [reverse?]}]]
      (let [state @state-atom
            value-entities (sort-entity-ids (if reverse?
                                              (concat (common/entities db attribute entity)
                                                      (common/entities-referring-with-sequence db entity))
                                              (common/values db entity attribute)))
            entity-attribute-editor-node-id view-compiler/id]
        (-> (ver 0
                 (map-indexed (fn [index value-entity]
                                (highlight (with-meta [outline-view db value-entity value-lens]
                                             {:mouse-event-handler [focus-on-click-mouse-event-handler]
                                              :can-gain-focus? true
                                              :local-id [:value index]
                                              :entity value-entity
                                              :keyboard-event-handler [entity-array-attribute-editor-value-view-keyboard-event-handler
                                                                       state-atom
                                                                       index]})
                                           {:fill-color (if (= index (:selected-index state))
                                                          [240 240 255 255]
                                                          [255 255 255 0])}))
                              value-entities)
                 (when (or (empty? value-entities)
                           (:adding? state))
                   ^{:local-id :adding-prompt}
                   [prompt-2
                    (fn run-query [text]
                      {:text text
                       :entities (distinct (search-entities db
                                                            text))})

                    (fn commands [results]
                      (let [focus-on-new-entity (fn [new-value-entity]
                                                  (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                                       (let [new-selected-index (->> (conj value-entities new-value-entity)
                                                                                                                     (sort-entity-ids)
                                                                                                                     medley/indexed
                                                                                                                     (medley/find-first #(= new-value-entity (second %)))
                                                                                                                     first)]
                                                                                         (->> (scene-graph/find-first-breath-first #(= entity-attribute-editor-node-id
                                                                                                                                       (:id %))
                                                                                                                                   scene-graph)
                                                                                              (scene-graph/find-first-breath-first #(= [:value new-selected-index]
                                                                                                                                       (:local-id %)))
                                                                                              (scene-graph/find-first-breath-first :can-gain-focus?)
                                                                                              (keyboard/set-focused-node!))))))]
                        (concat (for [new-value-entity (:entities results)]
                                  {:view (value-view db new-value-entity)
                                   :available? true
                                   :run! (fn [_subtree]
                                           (when (not (contains? (hash-set value-entities)
                                                                 new-value-entity))
                                             (transact! db [(if reverse?
                                                              [:add new-value-entity attribute entity]
                                                              [:add entity attribute new-value-entity])])
                                             (focus-on-new-entity new-value-entity))
                                           (swap! state-atom assoc :adding? false))})

                                [{:name "Create new entity"
                                  :available? true
                                  :key-patterns  [[#{:control} :c] [#{:control} :n]]
                                  :run! (fn [_subtree]
                                          (let [temporary-id-resolution (:temporary-id-resolution (transact! db (concat [[:add :tmp/new-entity (prelude :label) (:text results)]]
                                                                                                                        (if reverse?
                                                                                                                          [[:add :tmp/new-entity attribute entity]]
                                                                                                                          [[:add entity attribute :tmp/new-entity]]))))

                                                new-value-entity (get temporary-id-resolution :tmp/new-entity)]
                                            (focus-on-new-entity new-value-entity)
                                            (swap! state-atom assoc :adding? false)))}])))]))
            (assoc :command-set (entity-attribute-editor-command-set state-atom
                                                                     db
                                                                     entity
                                                                     attribute
                                                                     value-entities
                                                                     reverse?)))))))


(defn empty-attribute-prompt [db entity attribute reverse?]
  [prompt-2
   (fn run-query [text]
     {:text text
      :entities (distinct (search-entities db
                                           text))})

   (fn commands [results]
     (concat (for [value-entity (:entities results)]
               {:view (value-view db value-entity)
                :available? true
                :run! (fn [_subtree]
                        (transact! db [(if reverse?
                                         [:add value-entity attribute entity]
                                         [:add entity attribute value-entity])]))})
             (when (and (:text results)
                        (not reverse?))
               [{:view (text (pr-str (:text results)))
                 :available? true
                 :key-patterns  [[#{:control} :enter]]
                 :run! (fn [_subtree]
                         (transact! db [[:add entity attribute (:text results)]]))}])

             [{:name "Create new entity"
               :available? true
               :key-patterns  [[#{:control} :c] [#{:control} :n]]
               :run! (fn [_subtree]
                       (transact! db (concat [[:add :tmp/new-entity (prelude :label) (:text results)]]
                                             (if reverse?
                                               [[:add :tmp/new-entity attribute entity]]
                                               [[:add entity attribute :tmp/new-entity]]))))}]))])

(defn property [label editor]
  (hor 0
       (layouts/with-margins 5 0 0 0
         (text (str label ":")
               {:font bold-font}))
       editor))

(defn outline-view [db entity lens]
  (let [outline-view-id view-compiler/id]
    (assoc-last :entity entity
                (ver 10
                     (value-view db entity)
                     (layouts/with-margins 0 0 0 40
                       [array-editor
                        db
                        lens
                        (stred :editors)

                        (fn item-removal-transaction [editor]
                          (common/changes-to-remove-component-tree (common/deref db)
                                                                   editor))
                        (fn new-item-transaction [new-item]
                          {:item-id :tmp/new-editor
                           :transaction (if (:label new-item)
                                          (concat [[:add
                                                    :tmp/new-attribute
                                                    (prelude :type-attribute)
                                                    (prelude :attribute)]

                                                   [:add
                                                    :tmp/new-attribute
                                                    (prelude :label)
                                                    (:label new-item)]]
                                                  (when (:range new-item)
                                                    [[:add
                                                      :tmp/new-attribute
                                                      (prelude :range)
                                                      (:range new-item)]])
                                                  (map-to-transaction/map-to-statements {:dali/id :tmp/new-editor
                                                                                         (prelude :type-attribute) (stred :editor)
                                                                                         (stred :attribute) :tmp/new-attribute
                                                                                         (stred :value-lens) :tmp/new-lens}))
                                          (map-to-transaction/map-to-statements {:dali/id :tmp/new-editor
                                                                                 (prelude :type-attribute) (stred :editor)
                                                                                 (stred :attribute) (:entity new-item)}))})
                        (fn run-query [text]
                          {:text text
                           :attributes (distinct (search-entities db
                                                                  (prelude :attribute)
                                                                  text))})

                        (fn available-items [results]
                          (concat (for [attribute (:attributes results)]
                                    {:entity attribute
                                     :view (value-view db attribute)})
                                  (when (:text results)
                                    [{:name (str "Create attribute " (:text results))
                                      :available? (constantly true)
                                      :key-patterns [[#{:control} :c] [#{:control} :n]]
                                      :label (:text results)}

                                     ;; {:name (str "Create text attribute " (:text results))
                                     ;;  :available? (constantly true)
                                     ;;  :key-patterns [[#{:control} :c] [#{:control} :t]]
                                     ;;  :label (:text results)
                                     ;;  :range (prelude :text)}

                                     ;; {:name (str "Create entity attribute " (:text results))
                                     ;;  :available? (constantly true)
                                     ;;  :key-patterns [[#{:control} :c] [#{:control} :e]]
                                     ;;  :label (:text results)
                                     ;;  :range (prelude :entity)}
                                     ])))

                        (fn item-view [db editor]
                          (let [attribute (db-common/value db
                                                           editor
                                                           (stred :attribute))
                                reverse? (db-common/value db
                                                          editor
                                                          (stred :reverse?))
                                value-lens (db-common/value db
                                                            editor
                                                            (stred :value-lens))
                                value (db-common/value db
                                                       entity
                                                       attribute)
                                range (cond reverse?
                                            (prelude :entity)

                                            (vector? value)
                                            (prelude :array)

                                            (string? value)
                                            (prelude :text)

                                            (entity-id/entity-id? value)
                                            (prelude :entity)

                                            :else
                                            (db-common/value db
                                                             attribute
                                                             (prelude :range)))]
                            (property (str (when reverse?
                                             "<-")
                                           (label db attribute))
                                      #_(str (or (:stream-id attribute)
                                                 :tmp)
                                             "/"
                                             (label db attribute))
                                      (if range
                                        (condp = range
                                          (prelude :text)
                                          [text-attribute-editor
                                           db
                                           entity
                                           (db-common/value db
                                                            editor
                                                            (stred :attribute))]

                                          (prelude :entity)
                                          [entity-attribute-editor
                                           db
                                           entity
                                           attribute
                                           value-lens
                                           {:reverse? reverse?}]

                                          (prelude :array)
                                          (ver 0
                                               (text "[")
                                               [entity-array-attribute-editor-2
                                                db
                                                entity
                                                attribute]
                                               (text "]"))

                                          (text (str (pr-str editor)
                                                     " "
                                                     (pr-str (db-common/value db
                                                                              editor
                                                                              (prelude :type-attribute))))))

                                        [empty-attribute-prompt
                                         db
                                         entity
                                         attribute
                                         reverse?]))))

                        {:item-commands (fn [editor]
                                          (let [range (db-common/value-in db
                                                                          editor
                                                                          [(stred :attribute)
                                                                           (prelude :range)])
                                                reverse? (db-common/value db
                                                                          editor
                                                                          (stred :reverse?))]

                                            [{:name "toggle reverse"
                                              :available? (or (nil? range)
                                                              (= range (prelude :entity)))
                                              :key-patterns [[#{:control} :r]]
                                              :run! (fn [_subtree]
                                                      (transact! db [[:set editor (stred :reverse?) (not reverse?)]]))}]))
                         :show-empty-prompt? (starts-with? outline-view-id #_(if (bound? #'view-compiler/id)
                                                             view-compiler/id
                                                             [])
                                                           @focused-node-id)}]))))
  )

(defn change-view [db change]
  (text (str "["
             (string/join " " (map (partial value-string db)
                                   change))
             "]")))

(defn transaction-log-view [db]
  (ver 40
       (for [[transaction-number transaction] (map-indexed vector (:transaction-log db))]
         (ver 0
              (hor 10
                   (text transaction-number)
                   (map (partial change-view db) transaction))))))
(defn header [title]
  (layouts/with-margins 40 0 0 0
    (text title
          {:font bold-font})))

(defn keyboard-event-to-key-pattern [event]
  [(into #{}
         (remove nil?)
         [(when (:control? event)
            :control)
          (when (:shift? event)
            :shift)
          (when (:alt? event)
            :alt)
          (when (:meta? event)
            :meta)])
   (:key event)])

(deftest test-keyboard-event-to-key-pattern
  (is (= [#{:shift} :n]
         (keyboard-event-to-key-pattern {:key-code 78
                                         :alt? false
                                         :key :n
                                         :control? false
                                         :time 1646884415009
                                         :phase :descent
                                         :type :key-pressed
                                         :source :keyboard
                                         :shift? true
                                         :is-auto-repeat nil
                                         :character \n}))))



(defn remove-runs [command-set]
  (update command-set :commands (fn [commands]
                                  (map #(dissoc % :run!)
                                       commands))))

(defn command-help [triggered-key-patterns command-sets]
  (ver 20
       (when (not (empty? triggered-key-patterns))
         (text triggered-key-patterns))
       (for [command-set command-sets]
         (ver 0
              (text (:name command-set)
                    {:font bold-font})
              (for [command (filter (fn [command]
                                      (starts-with? triggered-key-patterns
                                                    (:key-patterns command)))
                                    (:commands command-set))]
                (text (str (pr-str (:key-patterns command))
                           " "
                           (:name command))
                      {:color (if (:available? command)
                                [0 0 0 255]
                                [200 200 200 255])}))))))


#_(defn focused-subtrees-with-command-sets []
    (if-let [focused-node-id @focused-node-id]
      (->> (scene-graph/path-to (:scene-graph @keyboard/state-atom)
                                focused-node-id)
           (filter :command-set)
           (reverse))
      []))



;; (defmacro inline-defn [name & arguments-and-body])

;; (comment
;;   (hash (do
;;           (declare x)
;;           (if (bound? #'x)
;;             x
;;             (def x (fn [] 1)))))
;;   (ns-unmap *ns* 'x)
;;   )
;; TODO: remove-me


;; todo: warn when there are closures in the scenegraph
;; todo: make command handler depend only on command sets on the scene graph and not on the whole scene graph using something like reagent tracks
(defn closure? [value]
  (and (fn? value)
       (string/includes? (.getName (clojure.core/type value))
                         "fn__")))

(deftest test-closure?
  (is (closure? (fn [])))
  (is (not (closure? closure?)))
  (is (not (closure? 1))))

(comment
  (clojure.core/type (clojure.core/type focused-subtrees-with-command-sets))
  (clojure.core/type (.getName (clojure.core/type (fn []))))

  ) ;; TODO: remove-me


(defn command-handler-keyboard-event-handler [state-atom focused-subtrees-with-command-sets _scene-graph event]
  (if (and (= :descent (:phase event))
           (= :key-pressed (:type event)))
    (if (empty? focused-subtrees-with-command-sets)
      event
      (let [triggered-key-patterns (conj (:triggered-key-patterns @state-atom)
                                         (keyboard-event-to-key-pattern event))
            possible-commands-and-subtrees (->> focused-subtrees-with-command-sets
                                                (mapcat (fn [subtree]
                                                          (for [command (:commands (:command-set subtree))]
                                                            {:subtree subtree
                                                             :command command})))
                                                (filter (fn [command-and-subtree]
                                                          (and (:available? (:command command-and-subtree))
                                                               (starts-with? triggered-key-patterns
                                                                             (:key-patterns (:command command-and-subtree)))))))]
        (if (empty? possible-commands-and-subtrees)
          (do (swap! state-atom assoc :triggered-key-patterns [])
              event)
          (if-let [matched-command-and-subtree (medley/find-first (fn [command-and-subtree]
                                                                    (= triggered-key-patterns
                                                                       (:key-patterns (:command command-and-subtree))))
                                                                  possible-commands-and-subtrees)]
            (do ((:run! (:command matched-command-and-subtree))
                 (:subtree matched-command-and-subtree))
                nil)
            (do (swap! state-atom assoc :triggered-key-patterns triggered-key-patterns)
                event)))))
    event))

(defn command-handler [_show-help? _child]
  (let [state-atom (dependable-atom/atom "command-handler-state"
                                         {:triggered-key-patterns []})]
    ^{:name "command-handler"}
    (fn [show-help? child]
      (let [focused-subtrees-with-command-sets @focused-subtrees-with-command-sets]
        (-> (ver 20
                 child
                 (when show-help?
                   (layouts/with-margins 0 0 0 20 [command-help
                                                   (:triggered-key-patterns @state-atom)
                                                   (map (comp remove-runs :command-set)
                                                        focused-subtrees-with-command-sets)])))
            (assoc :keyboard-event-handler [command-handler-keyboard-event-handler
                                            state-atom
                                            focused-subtrees-with-command-sets]))))))

(defn- can-undo?  [state]
  (not (empty? (seq (:branch-transaction-log (:branch state))))))

(defn- undo! [state-atom]
  (swap! state-atom
         (fn [state]
           (let [new-branch (undo-last-branch-transaction (:branch state))]
             (-> state
                 (assoc :branch new-branch)
                 (update :undoed-transactions (fn [undoed-transactions]
                                                (conj (if (= (:undoed-transactions-are-applicable-on state)
                                                             (last (:branch-transaction-log (:branch state))))
                                                        undoed-transactions
                                                        '())
                                                      (last (:branch-transaction-log (:branch state))))))
                 (assoc :undoed-transactions-are-applicable-on (last (:branch-transaction-log new-branch))))))))

(defn- can-redo? [state]
  (and (not (empty? (:undoed-transactions state)))
       (= (:undoed-transactions-are-applicable-on state)
          (last (:branch-transaction-log (:branch state))))))

(defn- redo! [state-atom]
  (transact! (:branch @state-atom)
             (first (:undoed-transactions @state-atom)))
  (swap! state-atom
         (fn [state]
           (-> state
               (update :undoed-transactions rest)
               (assoc :undoed-transactions-are-applicable-on (first (:undoed-transactions @state-atom)))))))


(deftest test-undo-and-redo
  (let [state-atom (atom {:branch (create-stream-db-branch "uncommitted"
                                                           (db-common/deref (doto (create-dependable-stream-db-in-memory "base" index-definitions)
                                                                              (transact! [[:add :entity-1 :label 0]]))))
                          :undoed-transactions '()})]

    (is (not (can-undo? @state-atom)))
    (is (not (can-redo? @state-atom)))

    (transact! (:branch @state-atom)
               [[:add :entity-1 :label 1]])

    (transact! (:branch @state-atom)
               [[:add :entity-1 :label 2]])

    (is (can-undo? @state-atom))
    (is (not (can-redo? @state-atom)))

    (is (= '(#{[:add :entity-1 :label 1]} #{[:add :entity-1 :label 2]})
           (seq (:branch-transaction-log (:branch @state-atom)))))

    (is (= '()
           (:undoed-transactions @state-atom)))

    (undo! state-atom)

    (is (can-undo? @state-atom))
    (is (can-redo? @state-atom))

    (is (= '(#{[:add :entity-1 :label 1]})
           (seq (:branch-transaction-log (:branch @state-atom)))))

    (is (= '(#{[:add :entity-1 :label 2]})
           (:undoed-transactions @state-atom)))

    (is (= #{[:add :entity-1 :label 1]}
           (:undoed-transactions-are-applicable-on @state-atom)))

    (undo! state-atom)

    (is (= '(#{[:add :entity-1 :label 1]}
             #{[:add :entity-1 :label 2]})
           (:undoed-transactions @state-atom)))

    (is (= '()
           (seq (:branch-transaction-log (:branch @state-atom)))))

    (is (= nil
           (:undoed-transactions-are-applicable-on @state-atom)))

    (is (not (can-undo? @state-atom)))
    (is (can-redo? @state-atom))


    (redo! state-atom)

    (is (= '(#{[:add :entity-1 :label 2]})
           (:undoed-transactions @state-atom)))

    (is (= '(#{[:add :entity-1 :label 1]})
           (seq (:branch-transaction-log (:branch @state-atom)))))

    (is (= #{[:add :entity-1 :label 1]}
           (:undoed-transactions-are-applicable-on @state-atom)))

    (is (can-undo? @state-atom))
    (is (can-redo? @state-atom))

    (redo! state-atom)

    (is (= '()
           (:undoed-transactions @state-atom)))

    (is (= '(#{[:add :entity-1 :label 1]}
             #{[:add :entity-1 :label 2]})
           (seq (:branch-transaction-log (:branch @state-atom)))))

    (is (= #{[:add :entity-1 :label 2]}
           (:undoed-transactions-are-applicable-on @state-atom)))

    (is (can-undo? @state-atom))
    (is (not (can-redo? @state-atom)))))



;; (defn statement-view [state-atom statement]
;;   (let [state @state-atom]
;;     (ver 10
;;          (property "label"
;;                    (text-attribute-editor
;;                     (:branch state)
;;                     statement
;;                     (prelude :label)))
;;          (property "refers"
;;                    [entity-attribute-editor
;;                     (:branch state)
;;                     (:entity state)
;;                     (argumentation :refers)
;;                     [(argumentation :concept)]])
;;          (property "answers"
;;                    [entity-attribute-editor
;;                     (:branch state)
;;                     (:entity state)
;;                     (argumentation :answers)
;;                     [(argumentation :question)]])
;;          (property "supporting arguments"
;;                    (ver 10
;;                         (map (fn [argument]
;;                                (hor 10
;;                                     [entity-symbol
;;                                      state-atom
;;                                      (argumentation :argument)
;;                                      argument]
;;                                     (-> (box [entity-array-attribute-editor
;;                                               (:branch state)
;;                                               argument
;;                                               (argumentation :premises)])
;;                                         (assoc :local-id argument))))
;;                              (entities (:branch state)
;;                                        (argumentation :supports)
;;                                        statement)))))))

;; (defn concept-view [state-atom concept]
;;   (let [state @state-atom]
;;     (ver 10
;;          (property "label"
;;                    (text-attribute-editor
;;                     (:branch state)
;;                     concept
;;                     (prelude :label)))
;;          (property "referring statements"
;;                    [entity-attribute-editor
;;                     (:branch state)
;;                     (:entity state)
;;                     (argumentation :refers)
;;                     [(argumentation :statement)]
;;                     {:reverse? true}]
;;                    #_(ver 10
;;                           (map (fn [statement]
;;                                  (entity-view (:branch state)
;;                                               state-atom
;;                                               statement))
;;                                (entities (:branch state)
;;                                          (argumentation :refers)
;;                                          concept)))))))

;; (defn question-view [state-atom question]
;;   (let [state @state-atom]
;;     (ver 10
;;          (property "label"
;;                    (text-attribute-editor
;;                     (:branch state)
;;                     question
;;                     (prelude :label)))
;;          (property "answers"
;;                    [entity-attribute-editor
;;                     (:branch state)
;;                     (:entity state)
;;                     (argumentation :answers)
;;                     [(argumentation :statement)]
;;                     {:reverse? true}]))))

;; (defn- argument-view
;;   [state]
;;   (ver 10
;;        (property "premises"
;;                  [entity-array-attribute-editor
;;                   (:branch state)
;;                   (:entity state)
;;                   (argumentation :premises)])
;;        (property "supports"
;;                  [entity-attribute-editor
;;                   (:branch state)
;;                   (:entity state)
;;                   (argumentation :supports)
;;                   [(argumentation :statement)]])))

;; TODO: how to allow embedded outline views? each attribute editor in
;; an outline view should have a value editor, but it should only be
;; added on demand. value editor could be a table or an outline view.

(defn notebook-view [db notebook]
  [array-editor
   db
   notebook
   (stred :views)

   (fn item-removal-transaction [view]
     (common/changes-to-remove-component-tree (common/deref db)
                                              view))

   (fn new-item-transaction [new-item]
     {:item-id :tmp/new-view
      :transaction (if (:entity new-item)
                     (map-to-transaction/map-to-statements {:dali/id :tmp/new-view
                                                            (prelude :type-attribute) (stred :outline-view)
                                                            (stred :lens) {:dali/id :tmp/new-lens}
                                                            (stred :entity) (:entity new-item)})
                     (concat (when (:type new-item)
                               [[:add
                                 :tmp/new-entity
                                 (prelude :type-attribute)
                                 (:type new-item)]])
                             [[:add
                               :tmp/new-entity
                               (prelude :label)
                               (:label new-item)]]
                             (map-to-transaction/map-to-statements {:dali/id :tmp/new-view
                                                                    (prelude :type-attribute) (stred :outline-view)
                                                                    (stred :lens) {:dali/id :tmp/new-lens}
                                                                    (stred :entity) :tmp/new-entity})))})

   (fn run-query [text]
     {:text text
      :entities (distinct (search-entities db text))})

   (fn available-items [results]
     (concat (for [entity (:entities results)]
               {:entity entity
                :view (value-view db entity)})
             [{:name "Create new entity"
               :label (:text results)
               :available? (constantly true)
               :key-patterns [[#{:control} :c] [#{:control} :n]]}]
             (for [[index type] (map-indexed vector
                                             (into [] (let [types (common/entities-from-ave (common/index db :ave)
                                                                                            (prelude :type-attribute)
                                                                                            (prelude :type-type))]
                                                        (concat (filter temporary-ids/temporary-id?
                                                                        types)
                                                                (filter (fn [type]
                                                                          (= "uncommitted"
                                                                             (:stream-id type)))
                                                                        types)
                                                                (filter (fn [type]
                                                                          (= "base"
                                                                             (:stream-id type)))
                                                                        types)
                                                                [(prelude :type-type)
                                                                 (prelude :attribute)]))))]

               (let [key (nth [:j :k :l :ö :f :d :s :a :u :i :o :p :r :e :w :q]
                              index
                              nil)]

                 {:name (str "Create " (:stream-id type) "/" (label db type))
                  :available? (constantly true)
                  :key-patterns (if key
                                  [[#{:control} :c] [#{:control} key]]
                                  nil)
                  :type type
                  :label (:text results)}))))

   (fn item-view [db view]
     (condp = (db-common/value db
                               view
                               (prelude :type-attribute))
       (stred :outline-view)
       [outline-view db
        (db-common/value db
                         view
                         (stred :entity))
        (db-common/value db
                         view
                         (stred :lens))]

       (text (pr-str [view
                      (db-common/value db
                                       view
                                       (prelude :type-attribute))]))))])

(defn entity-list [state-atom entity-type]
  (ver 0 (for [statement (db-common/entities-from-ave (db-common/index (:branch @state-atom)
                                                                       :ave)
                                                      (prelude :type-attribute)
                                                      entity-type)]
           (-> (text (db-common/value (:branch @state-atom)
                                      statement
                                      (prelude :label))
                     #_(entity-string (:branch state)
                                      statement))
               (assoc :mouse-event-handler [on-click-mouse-event-handler (partial open-entity! state-atom statement)])))))


(derivation/def-derivation focused-entity
  (:entity (scene-graph/find-first-breath-first :entity
                                                (-> @keyboard/state-atom :focused-node))))


(def the-branch-changes #{[:add {:id 0, :stream-id "base"} {:stream-id "stred", :id 3} [:tmp/id-1]]
                          [:add :tmp/id-0 {:stream-id "prelude", :id 3} "sdf"]
                          [:add :tmp/id-3 {:stream-id "prelude", :id 4} {:stream-id "prelude", :id 0}]
                          [:add :tmp/id-3 {:stream-id "prelude", :id 6} {:stream-id "prelude", :id 10}]
                          [:add :tmp/id-4 {:stream-id "prelude", :id 4} {:stream-id "stred", :id 13}]
                          [:add :tmp/id-4 {:stream-id "stred", :id 10} :tmp/id-3]
                          [:add :tmp/id-3 {:stream-id "prelude", :id 3} "friend"]
                          [:add :tmp/id-2 {:stream-id "stred", :id 2} [:tmp/id-4]]
                          [:add :tmp/id-1 {:stream-id "stred", :id 11} :tmp/id-0]
                          [:add :tmp/id-0 :tmp/id-3 :tmp/id-0]
                          [:add :tmp/id-1 {:stream-id "prelude", :id 4} {:stream-id "stred", :id 6}]
                          [:add :tmp/id-0 {:stream-id "prelude", :id 4} {:stream-id "prelude", :id 1}]
                          [:add :tmp/id-1 {:stream-id "stred", :id 12} :tmp/id-2]})

(defn transaction-roots [transaction]
  (set/difference (set (map common/change-entity transaction))
                  (set (mapcat (fn [value]
                                 (if (vector? value)
                                   value
                                   [value]))
                               (map common/change-value transaction)))))

(deftest test-transaction-roots
  (is (= #{:a}
         (transaction-roots #{[:add :a :name "John"]
                              [:add :a :friend :b]
                              [:add :a :buddies [:c]]
                              [:add :b :name "Jack"]
                              [:add :c :name "Mat"]}))))

(defn transaction-branch [db root visited-set transaction]

  ;; TODO: visualize remvoed propositions

  (let [transaction (remove (fn [change]
                              (= :remove (common/change-operator change)))
                            transaction)]
    (ver 10
         (value-view db root)
         (layouts/with-margins 0 0 0 100
           (ver 10
                (for [attribute (->> transaction
                                     (filter (fn [change]
                                               (= (common/change-entity change)
                                                  root)))
                                     (map common/change-attribute)
                                     (distinct))]
                  (hor 10
                       (layouts/with-margins 0 0 0 0
                         (text (str (or (:stream-id attribute)
                                        :tmp)
                                    "/"
                                    (or (label db attribute)
                                        (:id attribute))
                                    ": ")))
                       (ver 10
                            (for [value (->> transaction
                                             (filter (fn [change]
                                                       (= (common/change-entity change)
                                                          root)))
                                             (filter (fn [change]
                                                       (= (common/change-attribute change)
                                                          attribute)))
                                             (map common/change-value))]
                              (cond (string? value)
                                    (text (pr-str value))

                                    (vector? value)
                                    (ver 0
                                         (text "[")
                                         (for [item value]
                                           (transaction-branch db item (conj visited-set value) transaction))
                                         (text "]"))

                                    (and (or (entity-id/entity-id? value)
                                             (temporary-ids/temporary-id? value))
                                         (not (contains? visited-set value)))
                                    (transaction-branch db value (conj visited-set value) transaction)

                                    :else
                                    (text (pr-str value))))))))))))

(defn transaction-view [db transaction]
  (ver 10
       (for [root (set/union (transaction-roots transaction)
                             #_(set (filter temporary-ids/temporary-id? (map common/change-entity transaction))))]
         (transaction-branch db root #{} transaction))))

(comment
  (transaction-roots the-branch-changes)
  ) ;; TODO: remove me

(defn root-view [state-atom]
  #_(logga.core/write (pr-str 'event-cache
                              (cache/stats cache/state)))
  (let [state @state-atom
        db (:stream-db state)]

    (layouts/superimpose (visuals/rectangle-2 :fill-color [255 255 255 255])
                         (-> (layouts/with-margins 20 20 20 20
                               [command-handler
                                (:show-help? state)
                                (-> (ver 10

                                         ;; (layouts/center-horizontally

                                         ;;  ^{:local-id :prompt}
                                         ;;  [prompt
                                         ;;   (:branch state)
                                         ;;   [(argumentation :statement)
                                         ;;    (argumentation :concept)
                                         ;;    (argumentation :question)]
                                         ;;   (partial open-entity! state-atom)]
                                         ;;  )

                                         (when (:entity state)
                                           (let [entity-type (db-common/value (:branch state)
                                                                              (:entity state)
                                                                              (prelude :type-attribute))]

                                             (ver 10

                                                  ;; (chor 10
                                                  ;;       (type-symbol entity-type)
                                                  ;;       (text (entity-string (:branch state) (:entity state))))


                                                  ;; (text (str "Focused entity: " (value-string db @focused-entity)))
                                                  ;; (text (str "Focused node:" (if-let [focused-node-id (-> @keyboard/state-atom :focused-node :id) ]
                                                  ;;                              (pr-str focused-node-id)
                                                  ;;                              "")))

                                                  ;; (text (if-let [focused-entity @focused-entity #_(-> @keyboard/state-atom :focused-node :entity)]
                                                  ;;         (entity-string (:branch state) focused-entity)
                                                  ;;         ""))

                                                  (condp = entity-type
                                                    ;; (argumentation :statement)
                                                    ;; (statement-view state-atom (:entity state))

                                                    ;; (argumentation :concept)
                                                    ;; (concept-view state-atom (:entity state))

                                                    ;; (argumentation :question)
                                                    ;; (question-view state-atom (:entity state))

                                                    ;; (argumentation :argument)
                                                    ;; (argument-view state)

                                                    (stred :notebook)
                                                    (notebook-view (:branch state) (:entity state))

                                                    {}))))
                                         ;; [attribute-selector db]

                                         #_(button "commit" (fn []
                                                              (transact! (:stream-db state)
                                                                         (branch-changes (:branch state)))
                                                              (swap! state-atom assoc :branch (create-stream-db-branch "uncommitted" (:stream-db state)))))
                                         #_(text (pr-str (-> state :branch :transaction-log)))
                                         #_(transaction-log-view (:branch state))
                                         #_(text (transaction-log/last-transaction-number (:transaction-log (:branch state))))

                                         ;; (text "undoed:")
                                         ;; (text (pr-str (:undoed-transactions state)))

                                         ;; (text "uncommitted:")
                                         ;; (text (pr-str (seq (:branch-transaction-log (:branch state)))))

                                         ;; (text ":undoed-transactions-are-applicable-on")
                                         ;; (text (pr-str (:undoed-transactions-are-applicable-on state)))

                                         #_(text (into [] (db-common/values (:branch state)
                                                                            (:entity state)
                                                                            (prelude :label))))
                                         #_(text (entity-string (:branch state)
                                                                (:entity state)))

                                         ;; (text ":branch-transaction-log")
                                         ;; (text (pr-str (seq (:branch-transaction-log (:branch state)))))

                                         ;; (text (pr-str (:entity (-> @keyboard/state-atom :focused-node))))
                                         ;; (text (pr-str (keys (-> @keyboard/state-atom :focused-node))))

                                         ;; (header "Previous entities")
                                         ;; (ver 0 (for [entity (map :entity (reverse (:previous-entities state)))]
                                         ;;          (chor 0
                                         ;;                [entity-symbol
                                         ;;                 state-atom
                                         ;;                 (db-common/value (:branch state)
                                         ;;                                  entity
                                         ;;                                  (prelude :type-attribute))
                                         ;;                 entity]
                                         ;;                (text (entity-string (:branch state) entity)))))

                                         ;; (header "Statements")
                                         ;; (entity-list state-atom (argumentation :statement))


                                         ;; (header "Concepts")
                                         ;; (entity-list state-atom (argumentation :concept))

                                         (when (and (not (:show-help? state))
                                                    (:show-uncommitted-changes? state))
                                           (ver 0
                                                (header "Uncommitted changes")
                                                (transaction-view (:branch state) (branch-changes (:branch state)) #_the-branch-changes)

                                                (ver 0 (map (partial change-view (:branch state))
                                                            (sort comparator/compare-datoms
                                                                  (branch-changes (:branch state)))))))

                                         ;; (header "Undoed transactions")
                                         ;; (ver 30 (for [undoed-transaction (:undoed-transactions state)]
                                         ;;           (ver 0
                                         ;;                (map (partial change-view (:branch state))
                                         ;;                     (sort comparator/compare-datoms
                                         ;;                           undoed-transaction)))))

                                         #_(text (with-out-str (clojure.pprint/pprint (-> state :branch :transaction-log)
                                                                                      #_(keys @keyboard/state-atom)
                                                                                      #_(-> @keyboard/state-atom
                                                                                            (dissoc :scene-graph :focused-handler))
                                                                                      1
                                                                                      #_(map :id (keyboard/keyboard-event-handler-nodes-from-scene-graph (:scene-graph @keyboard/state-atom))))))
                                         #_(outline db (:type-type prelude))
                                         #_(for [type (entities db
                                                                (:type-attribute prelude)
                                                                (:type-type prelude))]
                                             (text (label db type))))
                                    (assoc :command-set {:name "root"
                                                         :commands [{:name "toggle view cache misses highlighting"
                                                                     :available? true
                                                                     :key-patterns [[#{:meta} :e]]
                                                                     :run! (fn [_subtree]
                                                                             (swap! application/state-atom
                                                                                    update :highlight-view-call-cache-misses? not))}
                                                                    {:name "descent focus"
                                                                     :available? true
                                                                     :key-patterns [[#{:meta} :d]]
                                                                     :run! (fn [subtree]
                                                                             (when-let [focusable-child (scene-graph/find-first-child :can-gain-focus?
                                                                                                                                      (scene-graph/find-first #(= (-> @keyboard/state-atom :focused-node-id)
                                                                                                                                                                  (:id %))
                                                                                                                                                              scene-graph/current-scene-graph))]
                                                                               (keyboard/set-focused-node! focusable-child)))}

                                                                    {:name "ascent focus"
                                                                     :available? true
                                                                     :key-patterns [[#{:meta} :a]]
                                                                     :run! (fn [_subtree]
                                                                             (when-let [focusable-ancestor (medley/find-first :can-gain-focus?
                                                                                                                              (rest (reverse (scene-graph/path-to scene-graph/current-scene-graph
                                                                                                                                                                  (-> @keyboard/state-atom :focused-node-id)))))]
                                                                               (keyboard/set-focused-node! focusable-ancestor)))}

                                                                    {:name "move focus left"
                                                                     :available? true
                                                                     :key-patterns [[#{:meta} :b]]
                                                                     :run! (fn [_subtree]
                                                                             (keyboard/move-focus! (:scene-graph @keyboard/state-atom)
                                                                                                   (partial scene-graph/closest-horizontal-nodes
                                                                                                            (keyboard/focused-node-id @keyboard/state-atom))
                                                                                                   dec
                                                                                                   keyboard/cycle-position))}

                                                                    {:name "move focus right"
                                                                     :available? true
                                                                     :key-patterns [[#{:meta} :f]]
                                                                     :run! (fn [_subtree]
                                                                             (keyboard/move-focus! (:scene-graph @keyboard/state-atom)
                                                                                                   (partial scene-graph/closest-horizontal-nodes
                                                                                                            (keyboard/focused-node-id @keyboard/state-atom))
                                                                                                   inc
                                                                                                   keyboard/cycle-position))}

                                                                    {:name "move focus down"
                                                                     :available? true
                                                                     :key-patterns [[#{:meta} :n]]
                                                                     :run! (fn [_subtree]
                                                                             (keyboard/move-focus! (:scene-graph @keyboard/state-atom)
                                                                                                   (partial scene-graph/closest-vertical-nodes
                                                                                                            @focused-node-id)
                                                                                                   inc
                                                                                                   keyboard/cycle-position))}

                                                                    {:name "move focus up"
                                                                     :available? true
                                                                     :key-patterns [[#{:meta} :p]]
                                                                     :run! (fn [_subtree]
                                                                             (keyboard/move-focus! scene-graph/current-scene-graph
                                                                                                   (partial scene-graph/closest-vertical-nodes
                                                                                                            @focused-node-id)
                                                                                                   dec
                                                                                                   keyboard/cycle-position))}

                                                                    {:name "move focus forward"
                                                                     :available? true
                                                                     :key-patterns [[#{} :tab]]
                                                                     :run! (fn [_subtree]
                                                                             (keyboard/move-focus! (:scene-graph @keyboard/state-atom)
                                                                                                   keyboard/order-nodes-down-right
                                                                                                   inc
                                                                                                   keyboard/cycle-position))}

                                                                    {:name "move focus backward"
                                                                     :available? true
                                                                     :key-patterns [[#{:shift} :tab]]
                                                                     :run! (fn [_subtree]
                                                                             (keyboard/move-focus! (:scene-graph @keyboard/state-atom)
                                                                                                   keyboard/order-nodes-down-right
                                                                                                   dec
                                                                                                   keyboard/cycle-position))}
                                                                    {:name "quit"
                                                                     :available? true
                                                                     :key-patterns [[#{:meta} :escape]]
                                                                     :run! (fn [_subtree]
                                                                             (application/send-event! {:type :close-requested}))}

                                                                    {:name "move focus to prompt"
                                                                     :available? true
                                                                     :key-patterns [[#{:meta} :o]]
                                                                     :run! (fn [subtree]
                                                                             (focus-on-prompt subtree))}

                                                                    {:name "open previous entity"
                                                                     :available? (not (empty? (:previous-entities @state-atom)))
                                                                     :key-patterns [[#{:meta} :comma]]
                                                                     :run! (fn [_subtree]
                                                                             (swap! state-atom (fn [state]
                                                                                                 (-> state
                                                                                                     (assoc :entity (:entity (last (:previous-entities state))))
                                                                                                     (update :previous-entities drop-last))) )
                                                                             (when-let [node-id (:node-id (last (:previous-entities state)))]
                                                                               (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                                                                    (keyboard/set-focused-node! (scene-graph/find-first #(= node-id
                                                                                                                                                                            (:id %))
                                                                                                                                                                        scene-graph))))))}

                                                                    {:name "open focused entity"
                                                                     :available? @focused-entity
                                                                     :key-patterns [[#{:meta} :period]]
                                                                     :run! (fn [_subtree]
                                                                             (open-entity! state-atom
                                                                                           @focused-entity
                                                                                           {:node-id @keyboard/focused-node}))}

                                                                    {:name "create supporting argument"
                                                                     :available? true
                                                                     :key-patterns [[#{:control} :c] [#{:control} :a]]
                                                                     :run! (fn [_subtree]

                                                                             (let [new-argument-id (-> (transact! (:branch state)
                                                                                                                  [[:add
                                                                                                                    :tmp/new-argument
                                                                                                                    (prelude :type-attribute)
                                                                                                                    (argumentation :argument)]

                                                                                                                   [:add
                                                                                                                    :tmp/new-argument
                                                                                                                    (argumentation :supports)
                                                                                                                    (:entity state)]])
                                                                                                       :temporary-id-resolution
                                                                                                       :tmp/new-argument)]

                                                                               (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                                                                    (->> scene-graph
                                                                                                                         (scene-graph/find-first #(= new-argument-id
                                                                                                                                                     (:local-id %)))
                                                                                                                         (scene-graph/find-first-child :can-gain-focus?)
                                                                                                                         (keyboard/set-focused-node!))))))}

                                                                    {:name "commit changes"
                                                                     :available? (not (empty? (branch-changes (:branch state))))
                                                                     :key-patterns [[#{:meta} :s]]
                                                                     :run! (fn [_subtree]
                                                                             (transact! (:stream-db state)
                                                                                        (->> (branch-changes (:branch state))
                                                                                             (map (partial stream-entity-ids-to-temporary-ids (:id (:branch state))))))
                                                                             (swap! state-atom assoc :branch (create-stream-db-branch "uncommitted" (db-common/deref (:stream-db state)))))}

                                                                    {:name "delete focused entity"
                                                                     :available? @focused-entity
                                                                     :key-patterns [[#{:control} :c] [#{:control} :x]]
                                                                     :run! (fn [_subtree]
                                                                             (transact! (:branch state)
                                                                                        (db-common/changes-to-remove-component-tree (db-common/deref (:branch state))
                                                                                                                                    @focused-entity)))}

                                                                    {:name "undo last transaction"
                                                                     :available? (can-undo? state)
                                                                     :key-patterns [[#{:meta} :z]]
                                                                     :run! (fn [_subtree]
                                                                             (undo! state-atom))}

                                                                    {:name "redo transaction"
                                                                     :available? (can-redo? state)
                                                                     :key-patterns [[#{:meta :shift} :z]]
                                                                     :run! (fn [_subtree]
                                                                             (redo! state-atom))}

                                                                    {:name "toggle help"
                                                                     :available? true
                                                                     :key-patterns [[#{:control} :h]]
                                                                     :run! (fn [_subtree]
                                                                             (swap! state-atom update :show-help? not))}

                                                                    {:name "toggle uncommitted changes"
                                                                     :available? true
                                                                     :key-patterns [[#{:control} :g]]
                                                                     :run! (fn [_subtree]
                                                                             (swap! state-atom update :show-uncommitted-changes? not))}]}))]))

                         )

    ;; (->
    ;;  (assoc :keyboard-event-handler (fn [scene-graph event]
    ;;                                   (if (and (= :descent (:phase event))
    ;;                                            (= :key-pressed (:type event)))
    ;;                                     (if-let [focused-node-id (:focused-node-id @keyboard/state-atom)]
    ;;                                       (let [key-pattern (keyboard-event-to-key-pattern event)]
    ;;                                         (if-let [command-and-subtree (->> (scene-graph/path-to scene-graph
    ;;                                                                                                focused-node-id)
    ;;                                                                           (filter :command-set)
    ;;                                                                           #_(map :command-set)
    ;;                                                                           (mapcat (fn [_subtree]
    ;;                                                                                     (for [command (:commands (:command-set _subtree))]
    ;;                                                                                       {:subtree _subtree
    ;;                                                                                        :command command})))
    ;;                                                                           (medley/find-first (fn [command-and-subtree]
    ;;                                                                                                (and (:available? (:command command-and-subtree))
    ;;                                                                                                     (= key-pattern
    ;;                                                                                                        (first (:key-patterns (:command command-and-subtree))))))))]
    ;;                                           (do ((:run! (:command command-and-subtree))
    ;;                                                (:subtree command-and-subtree))
    ;;                                               nil)
    ;;                                           event))
    ;;                                       event)
    ;;                                     event)


    ;;                                   #_(if (and (= :descent (:phase event))
    ;;                                              (= :key-pressed (:type event))
    ;;                                              (= :n (:key event))
    ;;                                              (:control? event))
    ;;                                       (do (keyboard/cycle-focus scene-graph event)
    ;;                                           nil)
    ;;                                       event))))
    ))

;; How to move focus to a new scene graph node?
;; - give scenegraph subtree as a aparameter to command handlers
;; - add keyboard/handle-next-scene-graph that registeres a function that gets the next scene graph as a parameter, it can then move the focus
;; to the new scenegraph node. It can find the right sub tree of the scene graph by id because the command handler gets the sub tree as a parameter.

(def test-stream-path "temp/test-stream-2")

(comment
  (fs/delete-dir test-stream-path)
  )

(defn initial-state []
  (let [stream-db (merge-stream-dbs (doto (create-dependable-stream-db-in-memory "base" index-definitions)
                                      (transact! prelude-transaction)
                                      (transact! argumentation-schema-transaction))
                                    (create-stream-db-on-disk "stream"
                                                              test-stream-path
                                                              index-definitions))
        branch (create-stream-db-branch "uncommitted" (db-common/deref stream-db))]
    {:stream-db stream-db
     :branch branch}

    #_(let [entity (-> (transact! branch
                                  [[:add
                                    :tmp/new-statement
                                    (prelude :type-attribute)
                                    (argumentation :statement)]

                                   #_[:add
                                      :tmp/new-statement
                                      (prelude :label)
                                      1]])
                       :temporary-id-resolution
                       :tmp/new-statement)]
        {:stream-db stream-db
         :branch branch
         :entity entity})))



(comment

  (let [base (doto (create-dependable-stream-db-in-memory "base" index-definitions)
               (transact! prelude-transaction))
        branch (create-stream-db-branch "uncommitted" base)]

    (transact! branch
               [[:add
                 :entity-1
                 :premises
                 [:statement-1]]])

    (transact! branch
               [[:add
                 :entity-1
                 :premises
                 [:statement-2]]])
    (assoc-in {:a [1 2 3]} [:a 1] :x)
    )


  (layout/select-layout-keys (root-view (atom (initial-state))))

  (with-bindings (application/create-event-handling-state)
    (layout/select-layout-keys (layout/do-layout-for-size (view-compiler/compile (root-view (atom (initial-state))))
                                                          200 200)))

  (-> (create-stream-db-on-disk "stred"
                                #_test-stream-path
                                "temp/koe2"
                                index-definitions)
      :indexes
      :full-text
      :collection)

  (search-entities (db-common/deref (create-stream-db-on-disk "stred"
                                                              #_test-stream-path
                                                              "temp/koe2"
                                                              index-definitions))
                   (argumentation :statement)
                   "you")
  ) ;; TODO: remove-me

(defn- component-1
  [hover-color]
  (assoc (visuals/rectangle-2 :fill-color hover-color #_[0 255 255 255])
         :width 100
         :height 100)
  #_(ver 10
         (assoc (visuals/rectangle-2 :fill-color [0 255 255 255])
                :width 100
                :height 100)
         #_(assoc (visuals/rectangle-2 :draw-color [255 0 255 255]
                                       :line-width 10
                                       :fill-color nil)
                  :width 200
                  :height 100)
         #_(layouts/hover (assoc (visuals/rectangle-2 :draw-color hover-color
                                                      :line-width 10
                                                      :fill-color nil)
                                 :width 200
                                 :height 100))))

(defn- stateful-component []
  (let [state-atom (dependable-atom/atom "stateful-component-state" 1)]
    (fn []
      (prn 'stateful-component) ;; TODO: remove me

      (assoc (text @state-atom)
             :can-gain-focus? true
             :keyboard-event-handler (fn [_scene-graph event]
                                       (prn 'event event) ;; TODO: remove me

                                       (when (and (= :on-target (:phase event))
                                                  (= :key-pressed (:type event)))
                                         (prn 'swapping @state-atom) ;; TODO: remove me

                                         (swap! state-atom inc))
                                       event)))))

(defn- component-2
  []
  (hor 10
       [stateful-component]
       [component-1 [155 255 255 255]]
       [component-1 [155 255 255 255]]
       ;; [component-1
       ;;  [255 255 255 255]
       ;;  #_[255 0 255 255]]
       ;; [component-1 [255 255 0 255]]
       #_(layouts/hover [component-1 [155 255 0 255]])))

(defn random-text-editor []
  (let [state-atom (dependable-atom/atom "random-text-editor-state" (string/trim (apply str (repeatedly 20 #(rand-nth "      abcdefghijklmnopqrstuvwxyz")))))]
    (fn []
      [text-area/text-area-3 {:style {:color [0 0 0 255]
                                      :font  font}
                              :text @state-atom
                              :on-text-change (fn [new-value]
                                                (reset! state-atom new-value))}])))

(defn stateless-component []
  (text "foo"))


(defn constructor-cache-test-root []
  (let [state-atom (dependable-atom/atom 2)]
    (fn []
      (assoc (layouts/vertically-2 {}
                                   (repeat @state-atom [random-text-editor]))
             :keyboard-event-handler (fn [_scene-graph event]
                                       (when (and (= :descent (:phase event))
                                                  (= :key-pressed (:type event)))
                                         (when (= (:key event) :n)
                                           (swap! state-atom inc))
                                         (when (= (:key event) :p)
                                           (swap! state-atom dec)))
                                       event)))))

(defn performance-test-root []
  (layouts/vertically-2 {}
                        ;; [text "foo"]
                        #_(map vector (repeat 20 component-2))
                        #_[stateful-component]
                        [random-text-editor]
                        [random-text-editor]
                        [random-text-editor]
                        [random-text-editor]
                        [random-text-editor]
                        ))


(defn adapt-to-space-test-root []
  {:adapt-to-space (fn [_node]
                     (prn 'adapt-to-space-test-root-adapt-to-space) ;; TODO: remove me

                     #_[random-text-editor]
                     [stateful-component])})

(defn genfun []
  (fn []))

(comment
  (= (genfun)
     (genfun))
  (pr-str (random-text-editor))
  (fn [])
  (with-bindings (merge (view-compiler/state-bindings)
                        (cache/state-bindings))
    ;; (view-compiler/compile-view-calls [stateful-component])
    ;; (view-compiler/compile-view-calls [stateful-component])
    ;; (view-compiler/component-tree (view-compiler/compile-view-calls [stateful-component]))
    (let [scene-graph (view-compiler/compile-view-calls [stateful-component])]
      ((:keyboard-event-handler scene-graph)
       scene-graph
       {:type :key-pressed,
        :phase :on-target}))
    (view-compiler/print-component-tree (view-compiler/component-tree (view-compiler/compile-view-calls [stateful-component])))
    )

  ) ;; TODO: remove me


(defn ui []
  #_[stateful-component]
  #_(hor 100
         [stateful-component]
         #_[stateful-component]
         #_[component-1 [155 255 255 255]])
  ;; (keyboard/handle-next-scene-graph! focus-on-prompt)
  (let [state-atom (dependable-atom/atom "ui-state"
                                         (let [stream-db (merge-stream-dbs (doto (create-dependable-stream-db-in-memory "base" index-definitions)
                                                                             (transact! prelude-transaction)
                                                                             (transact! argumentation-schema-transaction))
                                                                           (create-stream-db-on-disk
                                                                            ;; "stred"
                                                                            ;; test-stream-path
                                                                            "health" "temp/health"
                                                                            ;; "koe" "temp/koe2"
                                                                            index-definitions))
                                               branch (create-stream-db-branch "uncommitted" (db-common/deref stream-db))
                                               entity {:stream-id "stream" :id 5}]


                                           #_(transact! branch
                                                        [[:set
                                                          entity
                                                          (argumentation :premises)
                                                          [{:stream-id "stream" :id 5}]]])

                                           {:stream-db stream-db
                                            :branch branch
                                            :entity nil ;; entity
                                            :previous-entities []
                                            :undoed-transactions '()
                                            :show-help? false
                                            :show-uncommitted-changes? false}
                                           #_(let [entity (-> branch
                                                              (transact!
                                                               [#_

                                                                [:add
                                                                 :tmp/new-statement
                                                                 (prelude :type-attribute)
                                                                 (argumentation :statement)][:add
                                                                                             :tmp/new-statement
                                                                                             (prelude :label)
                                                                                             1]])
                                                              :temporary-id-resolution
                                                              :tmp/new-statement)]
                                               {:stream-db stream-db
                                                :branch branch
                                                :entity entity})))]
    (fn []
      #_[component-1 [0 255 0 255]]
      #_[component-2]

      #_(layouts/superimpose (visuals/rectangle-2 :fill-color [255 255 255 255])
                             (ver 10
                                  [component-2]
                                  #_[component-2]))
      (root-view state-atom)))
  )

(defonce event-channel-atom (atom nil))

#_(defn start []
    (reset! event-channel-atom
            (application/start-window #_adapt-to-space-test-root
                                      performance-test-root
                                      #_ui
                                      :on-exit #(reset! event-channel-atom nil)
                                        ;:do-profiling true
                                      ))

    ;; (Thread/sleep 100)

    ;; (doseq [event [{:key :f,
    ;;                 :control? true,
    ;;                 :type :key-pressed,
    ;;                 :source :keyboard,
    ;;                 :character \}]]
    ;;   (async/>!! @event-channel-atom
    ;;              event))

    ;; (Thread/sleep 100)

    ;; (doseq [event [{:key :f,
    ;;                 :control? true,
    ;;                 :type :key-pressed,
    ;;                 :source :keyboard,
    ;;                 :character \}]]
    ;;   (async/>!! @event-channel-atom
    ;;              event))

    ;; (Thread/sleep 100)

    ;; (doseq [event [{:key :f,
    ;;                 :control? true,
    ;;                 :type :key-pressed,
    ;;                 :source :keyboard,
    ;;                 :character \}]]
    ;;   (async/>!! @event-channel-atom
    ;;              event))

    (Thread/sleep 100)

    (when @event-channel-atom
      (async/>!! @event-channel-atom
                 {:type :close-requested}))
    )

#_(defn start []
    #_(application/start-application performance-test-root)
    (let [traced-vars [#'application/process-event!]]
      (try
        (run! trace/trace-var traced-vars)

        (def values-atom (atom {}))
        (trace/with-call-tree-printing values-atom
          (let [state (application/create-state performance-test-root)]
            (application/handle-events! state
                                        [{:type :resize-requested, :width 3384.0, :height 2928.0}
                                         {:type :resize-requested, :width 3384.0, :height 2914.0}
                                         ;; {:type :resize-requested, :width 3384.0, :height 2912.0}
                                         ;; {:type :resize-requested, :width 3384.0, :height 2910.0}
                                         ;; {:type :resize-requested, :width 3384.0, :height 2908.0}
                                         ])
            #_(application/close! state)))
        (finally (run! trace/untrace-var traced-vars)))))

(comment
  (get @values-atom
       526059776)

  (create-stream-db-on-disk
   ;; "stred" test-stream-path
   "health" "temp/health"
   ;; "koe" "temp/koe2"
   index-definitions)
  )

(defn notebook-ui []
  (let [state-atom (dependable-atom/atom "ui-state"
                                         (let [stream-db (merge-stream-dbs (doto (create-dependable-stream-db-in-memory "base" index-definitions)
                                                                             (transact! (concat (map-to-transaction/map-to-statements {:dali/id :tmp/notebook
                                                                                                                                       (prelude :type-attribute) (stred :notebook)})
                                                                                                stred-transaction
                                                                                                prelude-transaction
                                                                                                argumentation-schema-transaction)))
                                                                           (create-stream-db-on-disk
                                                                            ;; "stred"
                                                                            ;; test-stream-path
                                                                            ;; "health" "temp/health"
                                                                            ;; "koe" "temp/koe3"
                                                                            "koe" "temp/koe5"
                                                                            index-definitions))

                                               branch (create-stream-db-branch "uncommitted" (db-common/deref stream-db))
                                               entity {:id 0, :stream-id "base"}]

                                           ;;                                           (common/transact! branch the-branch-changes)


                                           #_(transact! branch
                                                        [[:set
                                                          entity
                                                          (argumentation :premises)
                                                          [{:stream-id "stream" :id 5}]]])

                                           {:stream-db stream-db
                                            :branch branch
                                            :entity  entity
                                            :previous-entities []
                                            :undoed-transactions '()
                                            :show-help? false}
                                           #_(let [entity (-> branch
                                                              (transact!
                                                               [#_

                                                                [:add
                                                                 :tmp/new-statement
                                                                 (prelude :type-attribute)
                                                                 (argumentation :statement)][:add
                                                                                             :tmp/new-statement
                                                                                             (prelude :label)
                                                                                             1]])
                                                              :temporary-id-resolution
                                                              :tmp/new-statement)]
                                               {:stream-db stream-db
                                                :branch branch
                                                :entity entity})))]
    (fn []
      #_[component-1 [0 255 0 255]]
      #_[component-2]

      #_(layouts/superimpose (visuals/rectangle-2 :fill-color [255 255 255 255])
                             (ver 10
                                  [component-2]
                                  #_[component-2]))
      (root-view state-atom))))


(defn start []
  (println "\n\n------------ start -------------\n\n")
  (reset! event-channel-atom
          (application/start-application ;; ui
           notebook-ui
           ;; performance-test-root
           ;; adapt-to-space-test-root
           :on-exit #(reset! event-channel-atom nil)))

  ;; (Thread/sleep 100)

  ;; (doseq [event [#_{:key :f,
  ;;                 :type :key-pressed,
  ;;                 :source :keyboard,
  ;;                 :character \}
  ;;                #_{:key :f,
  ;;                 :type :key-pressed,
  ;;                 :source :keyboard,
  ;;                 :character \}

  ;;                {:key :f,
  ;;                 :type :key-released,
  ;;                 :source :keyboard,
  ;;                 :character \}
  ;;                {:key :f,
  ;;                 :type :key-released,
  ;;                 :source :keyboard,
  ;;                 :character \}

  ;;                {:key :f,
  ;;                 :type :key-pressed,
  ;;                 :source :keyboard,
  ;;                 :character \}

  ;;                {:type :close-requested}]]
  ;;   (async/>!! @event-channel-atom
  ;;              event)
  ;;   (Thread/sleep 300))


  #_(let [state (application/create-state adapt-to-space-test-root)]
      (try
        (application/handle-events! state
                                    [{:type :resize-requested, :width 3384.0, :height 2928.0}
                                     {:type :resize-requested, :width 3384.0, :height 2914.0}
                                     {:type :resize-requested, :width 3384.0, :height 2912.0}
                                     ;; {:type :resize-requested, :width 3384.0, :height 2910.0}
                                     ;; {:type :resize-requested, :width 3384.0, :height 2908.0}
                                     ])
        (finally (application/close! state))))

  #_(let [state (application/create-state performance-test-root)]
      (application/handle-events! state
                                  [{:type :resize-requested, :width 3384.0, :height 2928.0}
                                   {:type :resize-requested, :width 3384.0, :height 2914.0}
                                   {:type :resize-requested, :width 3384.0, :height 2912.0}
                                   ;; {:type :resize-requested, :width 3384.0, :height 2910.0}
                                   ;; {:type :resize-requested, :width 3384.0, :height 2908.0}
                                   ])

      #_(application/handle-events! state
                                    [{:type :resize-requested, :width 3384.0, :height 2928.0}
                                        ; {:type :resize-requested, :width 3384.0, :height 2914.0}
                                     ;; {:type :resize-requested, :width 3384.0, :height 2912.0}
                                     ;; {:type :resize-requested, :width 3384.0, :height 2910.0}
                                     ;; {:type :resize-requested, :width 3384.0, :height 2908.0}
                                     ])
      (application/close! state)))

;; TODO: remove me


(when @event-channel-atom
  (async/>!! @event-channel-atom
             {:type :redraw}))



;; TODO:
;; * make merged-sorted writable so that new datoms are added to the downstream sorted
;; * allow stream-db to have base indexes that are merged to the stream-db indexes
;; * when merging indexes, use only downstream index if upstream does not have the index and vice versa.
;;   * This way prelude does not have to have all the indexes that the applicaiton uses.
;; * with these the stream-db can be queried prelude and domain schema statements along with the stream that is being edited
(comment

  (let [stream-db (create-dependable-stream-db-in-memory :base index-definitions)
        branch (create-stream-db-branch  "branch" stream-db)]
    #_(transact! branch
                 [[:add
                   :tmp/new-statement
                   (prelude :type-attribute)
                   (argumentation :statement)]])

    #_(transact! branch
                 [[:set
                   {:id 0, :stream-id :base}
                   (prelude :label)
                   "foo"]])

    (transact! branch
               [[:set
                 {:id 0, :stream-id :base}
                 (prelude :label)
                 "bar"]])

    (transact! branch
               [[:set
                 {:id 0, :stream-id :base}
                 (prelude :label)
                 "bar"]])
    (println (pr-str (-> branch :transaction-log))))


  (let [stream-db (create-dependable-stream-db-in-memory :base index-definitions)]
    (transact! stream-db prelude-transaction)
    (transact! stream-db argumentation-schema-transaction)
    (-> (transact! branch-db
                   [[:add
                     :tmp/new-statement
                     (prelude :type-attribute)
                     (argumentation :statement)]])
        :temporary-id-resolution
        :tmp/new-statement)
    (let [branch-db (create-stream-db-branch :branch stream-db)
          new-statement (-> (transact! branch-db
                                       [[:add
                                         :tmp/new-statement
                                         (prelude :type-attribute)
                                         (argumentation :statement)]])
                            :temporary-id-resolution
                            :tmp/new-statement)]

      (transact! branch-db
                 [[:set
                   new-statement
                   (prelude :label)
                   "new statement"]])
      #_(transact! branch-db
                   [[:set
                     {:id 0}
                     (prelude :label)
                     "new statement 2"]])
      (:collection (:eav (:indexes branch-db)))
      #_(subseq (:collection (:eav (:indexes branch-db)))
                >=
                [{:id 0 :stream-id :branch}])
      #_(db-common/values branch-db new-statement (prelude :label))
      #_(:transaction-log branch-db)))


  (let [stream-db (merge-stream-dbs (doto (create-dependable-stream-db-in-memory "base" index-definitions)
                                      (transact! prelude-transaction)
                                      (transact! argumentation-schema-transaction))
                                    (create-dependable-stream-db-in-memory "stream" index-definitions))
        branch (create-stream-db-branch "branch" stream-db)]
    #_(transact! stream-db
                 [[:add
                   :tmp/new-statement
                   (prelude :type-attribute)
                   (argumentation :statement)]])

    (prn 'transacting-to-branch) ;; TODO: remove-me

    (transact! branch
               [[:add
                 :tmp/new-statement
                 (prelude :type-attribute)
                 (argumentation :statement)]])

    (prn (transaction-log/last-transaction-number (:transaction-log stream-db)))
    (prn 'last-branch-transaction-number (transaction-log/last-transaction-number (:transaction-log branch)))
    (prn 'transaction-log (:branch-transaction-log branch)) ;; TODO: remove-me
    (prn 'eav (-> branch :indexes :eav :collection))
    (prn 'values (into [] (db-common/values branch
                                            {:id 0 :stream-id "branch"}
                                            (prelude :type-attribute))))

    )

  ;; removing an entity

  ) ;; TODO: remove-me

(deftest test-entity-removal
  (let [stream-db (create-dependable-stream-db-in-memory "stream" index-definitions)
        statement-1 (-> (transact! stream-db
                                   [[:add :tmp/new-statement (prelude :type-attribute) (argumentation :statement)]
                                    [:add :tmp/new-statement (prelude :label) "first statement"]])
                        :temporary-id-resolution
                        :tmp/new-statement)
        statement-2 (-> (transact! stream-db
                                   [[:add :tmp/new-statement (prelude :type-attribute) (argumentation :statement)]
                                    [:add :tmp/new-statement (prelude :label) "second statement"]])
                        :temporary-id-resolution
                        :tmp/new-statement)

        statement-3 (-> (transact! stream-db
                                   [[:add :tmp/new-statement (prelude :type-attribute) (argumentation :statement)]
                                    [:add :tmp/new-statement (prelude :label) "third statement"]])
                        :temporary-id-resolution
                        :tmp/new-statement)]

    (transact! stream-db
               [[:add
                 :tmp/new-argument
                 (prelude :type-attribute)
                 (argumentation :argument)]
                [:add
                 :tmp/new-argument
                 (argumentation :premises)
                 [statement-1
                  statement-2]]
                [:add
                 :tmp/new-argument
                 (argumentation :supports)
                 statement-3]])

    ;; (prn 'sequence-vae (-> stream-db :indexes :sequence-vae :collection))

    ;;    TODO: use these to form remove datoms

    (is (= '((:remove
              {:id 0, :stream-id "stream"}
              {:stream-id "prelude", :id 3}
              "first statement")
             (:remove
              {:id 0, :stream-id "stream"}
              {:stream-id "prelude", :id 4}
              {:stream-id "argumentation", :id 0})
             [:remove
              {:id 3, :stream-id "stream"}
              {:stream-id "argumentation", :id 2}
              [{:id 0, :stream-id "stream"} {:id 1, :stream-id "stream"}]]
             [:add
              {:id 3, :stream-id "stream"}
              {:stream-id "argumentation", :id 2}
              [{:id 1, :stream-id "stream"}]])
           (db-common/changes-to-remove-entity (db-common/deref stream-db)
                                               statement-1)))


    ;; (prn (transaction-log/last-transaction-number (:transaction-log stream-db)))
    ;; (prn 'transaction-log (:transaction-log stream-db)) ;; TODO: remove-me
    ;; (prn 'eav (-> stream-db :indexes :eav :collection))

    )

  )


(deftest test-entity-removal-2
  (let [stream-db (create-dependable-stream-db-in-memory "stream" [db-common/eav-index-definition
                                                                   db-common/vae-index-definition
                                                                   db-common/sequence-vae-index-definition])
        create-statement (fn [label]
                           (-> (transact! stream-db
                                          [[:add :tmp/new-statement :type :statement]
                                           [:add :tmp/new-statement :label label]])
                               :temporary-id-resolution
                               :tmp/new-statement))
        statement-1 (create-statement "first statement")
        statement-2 (create-statement "second statement")
        statement-3 (create-statement "third statement")
        argument (-> (transact! stream-db
                                [[:add :tmp/new-argument :type :argument]
                                 [:add :tmp/new-argument :premises [statement-1
                                                                    statement-2]]
                                 [:add :tmp/new-argument :supports statement-3]])
                     :temporary-id-resolution
                     :tmp/new-argument)]

    (is (= [[:remove statement-1 :label "first statement"]
            [:remove statement-1 :type :statement]
            [:remove argument :premises [statement-1
                                         statement-2]]
            [:add argument :premises [statement-2]]]
           (db-common/changes-to-remove-entity (db-common/deref stream-db)
                                               statement-1)))))

;; TODO: keep track of focused node in entity history so that navigating back reatins focus

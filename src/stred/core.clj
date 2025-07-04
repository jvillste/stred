                                        ; TODO: value-view should be evaluated in the hightlihgt binding block to swap text an bachground color when highlighted.
                                        ; could the scene graph convey dynamic variables to child nodes?
                                        ; dynamic variable bindings are part of view calls and they should be taken into account when invalidating cache for view calls
                                        ; bindings should be inherited to all view calls on a scene graph branch
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
   [argumentica.db.common :as common]
   [stred.hierarchical-table :as hierarchical-table]
   [stred.dev :as dev]
   [fungl.depend :as depend]))

(def ^:dynamic global-state-atom)

(def uncommitted-stream-id "uncommitted")

(defn assoc-last [& arguments]
  (apply assoc (last arguments)
         (drop-last arguments)))

(deftest test-assoc-last
  (is (= {:y 2, :x 1}
         (assoc-last :x 1 {:y 2}))))

(def escape-key-pattern-sequences [[[#{:control} :g]]
                                   [[#{} :escape]]])


(defn add-color [color delta]
  (vec (concat (map (comp #(max 0 (min 255 %))
                          +) color delta)
               (drop (count delta)
                     color))))

(deftest test-add-color
  (is (= [1 0 0 0]
         (add-color [0 0 0 0]
                    [1])))

  (is (= [1 2 3 4]
         (add-color [0 0 0 0]
                    [1 2 3 4])))

  (is (= [255 0 0 0]
         (add-color [100 0 0 0]
                    [200]))))

(defn gray [value]
  [value value value])

(def dark-mode (let [text-color [200 200 200 255]
                     background-color [0 0 0 255]
                     symbol-background [0 100 0 255]]
                 {:background-color background-color
                  :text-color text-color
                  :highlighted-text-color background-color
                  :symbol-background-color symbol-background
                  :symbol-foreground-color text-color #_(add-color symbol-background
                                                                   (gray 100))
                  :highlighted-background-color (add-color background-color
                                                           [0 140 0])
                  :selection-background-color (add-color background-color
                                                         [0 100 0])
                  #_(add-color background-color
                               [0 0 100])
                  :focus-highlight-color (add-color background-color
                                                    [0 100 0])
                  :menu-background (add-color background-color
                                              [50 50 50])}))

(def ^:dynamic theme dark-mode)

(defn create-dependable-stream-db-in-memory [id index-definitions]
  (assoc (stream/in-memory {:id id :create-atom (partial dependable-atom/atom (str "stream-" id))})
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

(deftest test-depend
  (let [stream-db-a (create-dependable-stream-db-in-memory :a [db-common/eav-index-definition])]
    (transact! stream-db-a [[:add 1 :name "foo"]])
    (is (= '([1 :name "foo" 0 :add])
           (:collection (:eav (:indexes stream-db-a)))))

    (is (identical? (depend/current-value (.state-atom (:transaction-log stream-db-a)))
                    (depend/current-value (.state-atom (:transaction-log stream-db-a)))))
    (is (identical? (depend/current-value (.btree-atom (:collection (:eav (:indexes stream-db-a)))))
                    (depend/current-value (.btree-atom (:collection (:eav (:indexes stream-db-a)))))))))

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


;; An index example:
;;
;; (defn statements-to-english-vehicle-changes [_indexes _transaction-number statements]
;;   (mapcat (fn [statement]
;;             (let [[operator entity attribute value] statement]
;;               (if (= :vehicle-in-finnish attribute)
;;                 [[operator
;;                   (get {"mopo" "moped"
;;                         "fillari" "a bike"}
;;                        value)
;;                   entity]]
;;                 [])))
;;           statements))

;; (deftest test-statements-to-english-vehicle-changes
;;   (let [db (doto (create-dependable-stream-db-in-memory "base"
;;                                                         [db-common/eav-index-definition
;;                                                          {:key :entities-by-english-vehicle-name
;;                                                           :statements-to-changes statements-to-english-vehicle-changes}])
;;              (transact! [[:add :entity-1 :vehicle-in-finnish "mopo"]
;;                          [:add :entity-1 :price 1000]])
;;              (transact! [[:add :entity-2 :vehicle-in-finnish "fillari"]
;;                          [:add :entity-2 :price 100]])
;;              (transact! [[:remove :entity-2 :vehicle-in-finnish "fillari"]
;;                          [:add :entity-2 :vehicle-in-finnish "mopo"]]))]

;;     (is (= [["a bike" :entity-2 1 :add]
;;             ["a bike" :entity-2 2 :remove]
;;             ["moped" :entity-1 0 :add]
;;             ["moped" :entity-2 2 :add]]
;;            (-> db
;;                :indexes
;;                :entities-by-english-vehicle-name
;;                :collection)))

;;     (is (= [[:entity-1 :price 1000 0 :add]
;;             [:entity-1 :vehicle-in-finnish "mopo" 0 :add]
;;             [:entity-2 :price 100 1 :add]
;;             [:entity-2 :vehicle-in-finnish "fillari" 1 :add]
;;             [:entity-2 :vehicle-in-finnish "fillari" 2 :remove]
;;             [:entity-2 :vehicle-in-finnish "mopo" 2 :add]]
;;            (-> db
;;                :indexes
;;                :eav
;;                :collection)))))


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

(defn unmerged-entity-id-to-merged-stream-id [unmberged-stream-id unmerged-entity-id temporary-id-resolution]
  (get temporary-id-resolution
       (stream-entity-id-to-temporary-id unmberged-stream-id
                                         unmerged-entity-id)))

(deftest test-unmerged-entity-id-to-merged-stream-id
  (is (= {:id 358, :stream-id "main"}
         (unmerged-entity-id-to-merged-stream-id uncommitted-stream-id
                                                 {:id 0, :stream-id uncommitted-stream-id}
                                                 #:tmp{:id-1 {:id 356, :stream-id "main"},
                                                       :id-2 {:id 357, :stream-id "main"},
                                                       :id-0 {:id 358, :stream-id "main"}}))))

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
                                :lens-map
                                :table-lens
                                :sublens
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
                                                               :reverse?
                                                               :lens-map])
                                       [(attribute (stred :editors) "editors" :array {(prelude :component?) true})
                                        (attribute (stred :views) "views" :array {(prelude :component?) true})
                                        (attribute (stred :table-lens) "table-lens" :entity {(prelude :component?) true})
                                        (attribute (stred :lens) "lens" :entity {(prelude :component?) true})
                                        (attribute (stred :sublens) "sublens" :entity {(prelude :component?) true})
                                        (attribute (stred :value-lens) "value-lens" :entity {(prelude :component?) true})])))

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
    (search-entities stream-db (:attribute prelude) "")
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
  (assert (number? margin))
  (apply layouts/horizontally-2 {:margin margin}
         children))

(defn chor [margin & children]
  (assert (number? margin))
  (apply layouts/horizontally-2 {:margin margin :centered true}
         children))

(defn ver [margin & children]
  (assert (number? margin))
  (apply layouts/vertically-2 {:margin margin}
         children))

(def font (font/create-by-name "CourierNewPSMT" 40))
(def bold-font (font/create-by-name "CourierNewPS-BoldMT" 40))
(def header-font (font/create-by-name "CourierNewPS-BoldMT" 60))

(defn text [string & [{:keys [font color] :or {font font
                                               color (:text-color theme)}}]]
  (text-area/text (str string)
                  color
                  font))

(defn box [content & [{:keys [padding fill-color draw-color line-width corner-arc-radius] :or {fill-color (:background-color theme)
                                                                                               draw-color (:background-color theme)
                                                                                               line-width 2
                                                                                               padding 2
                                                                                               corner-arc-radius 5}}]]
  (layouts/box padding
               (visuals/rectangle-2 :fill-color fill-color
                                    :draw-color draw-color
                                    :line-width line-width
                                    :corner-arc-radius corner-arc-radius)
               content))

(defn entities [db attribute value]
  (map last (db-common/propositions db
                                    :ave
                                    [attribute value])))

(defn label [db entity]
  (if (:show-entity-ids? @global-state-atom)
    (str (if (entity-id/entity-id? entity)
           (str (:stream-id entity) "/" (:id entity))
           entity)
         ": "
         (db-common/value db entity (prelude :label)))
    (db-common/value db entity (prelude :label))))

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
  (layouts/box 3
               (visuals/rectangle-2 :fill-color fill-color
                                    :draw-color nil
                                    :line-width 0
                                    :corner-arc-radius 20)
               content))

(defn highlight-2 [highlight? content & [{:keys [fill-color]
                                          :or {fill-color (:highlighted-background-color theme)}}]]
  (layouts/box 5
               (visuals/rectangle-2 :fill-color (if highlight?
                                                  fill-color
                                                  [0 0 0 0]#_(:background-color theme))
                                    :draw-color nil
                                    :line-width 0
                                    :corner-arc-radius 20)
               content))

(defn highlight-3 [color content]
  (layouts/box 5
               (visuals/rectangle-2 :fill-color color
                                    :draw-color nil
                                    :line-width 0
                                    :corner-arc-radius 20)
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

(defn focus-highlight [child]
  (highlight child
             {:fill-color (if (keyboard/sub-component-is-in-focus?)
                            (:focus-highlight-color theme)
                            (:background-color theme))}))

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
                (:id value)
                "/"
                (label db value)
                #_(or (label db value)
                      (:id value)))
           (pr-str value))

         #_(when-let [type (db-common/value db
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

(defn entity-value-type-view [db value value-view]
  (hor 10
       (layouts/with-margins 0 0 0 0
         (let [type (db-common/value db
                                     value
                                     (prelude :type-attribute))]
           (highlight (if type
                        (text (or (label db type)
                                  (value-string db type))
                              {:color (:symbol-foreground-color theme)})
                        {:width 40
                         :height 40})
                      {:fill-color (:symbol-background-color theme)})))
       value-view))

(defn value-view [db value]
  ;;(text (value-string db value))

  (if (or (entity-id/entity-id? value)
          (temporary-ids/temporary-id? value))
    (entity-value-type-view db value
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
  [text-area/text-area-3 {:style {:color (:text-color theme)
                                  :font  font}
                          :text text
                          :on-text-change on-text-change}])

(defn bare-text-editor-2 [text on-change & [{:keys [validate-new-text font] :or {font font}}]]
  [text-area/text-area-3 {:style {:color (:text-color theme)
                                  :font  font}
                          :text text
                          :on-change on-change
                          :validate-new-text validate-new-text}])

(defn text-editor [text on-text-change]
  (box (layouts/with-maximum-size column-width nil
         (layouts/with-minimum-size 300 nil
           (bare-text-editor text on-text-change)))
       {:draw-color (:text-color theme)}))

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
            (assoc :command-set {:name "number editor"
                                 :commands [{:name "revert"
                                             :available? (not (= (:number state)
                                                                 given-number))
                                             :key-patterns escape-key-pattern-sequences
                                             :run! (fn [_subtree] (swap! state-atom assoc :number given-number))}

                                            {:name "commit"
                                             :available? true
                                             :key-patterns [[#{} :enter]]
                                             :run! (fn [_subtree]
                                                     (if (nil? (:number state))
                                                       (swap! state-atom assoc :number given-number)
                                                       (on-change! (:number state))))}]}))))))

(defn text-editor-2 [given-text _on-change! & [{:keys [font] :or {font font}}]]
  (let [state-atom (dependable-atom/atom {:text given-text
                                          :given-text given-text})]
    (fn text-editor-2 [given-text on-change! & [{:keys [font] :or {font font}}]]
      (let [state @state-atom]
        (when (not (= given-text (:given-text state)))
          (swap! state-atom
                 assoc
                 :text given-text
                 :given-text given-text))

        (if (keyboard/sub-component-is-in-focus?)
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
                                                                new-state)))
                                                          {:font font}))
                                    {:fill-color (if (= given-text (:text state))
                                                   (:background-color theme)
                                                   (:highlighted-background-color theme))})
                               (assoc :command-set {:name "text editor"
                                                    :commands [{:name "revert"
                                                                :available? (not (= (:text state)
                                                                                    given-text))
                                                                :key-patterns escape-key-pattern-sequences
                                                                :run! (fn [_subtree] (swap! state-atom assoc :text given-text))}

                                                               {:name "commit"
                                                                :available? true
                                                                :key-patterns [[#{} :enter]]
                                                                :run! (fn [_subtree]
                                                                        (if (nil? (:text state))
                                                                          (swap! state-atom assoc :text given-text)
                                                                          (on-change! (:text state))))}]}))]
          (assoc (text given-text
                       {:font font})
                 :can-gain-focus? true))))))

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

(defn text-attribute-editor [db entity attribute & [{:keys [font] :or {font font}}]]
  (layouts/with-maximum-size column-width nil
    {:local-id [entity attribute]
     :node [text-editor-2
            (db-common/value db entity attribute)
            (fn [new-value]
              (if (= "" new-value)
                (transact! db [[:remove entity attribute (db-common/value db entity attribute)]])
                (transact! db [[:set entity attribute new-value]])))
            {:font font}]}))

(defn outline [db entity]
  (layouts/vertically-2 {:margin 10}
                        (layouts/box 10
                                     (visuals/rectangle-2 :fill-color (:background-color theme)
                                                          :draw-color [0 0 0 0]
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

;; (defn prompt-command-set [state-atom db on-entity-change]
;;   (let [state @state-atom]
;;     {:name "prompt"
;;      :commands [{:name "create question"
;;                  :available? (not (empty? (:text state)))
;;                  :key-patterns [[#{:control} :c] [#{:control} :n]]
;;                  :run! (create-run-create-entity state-atom db on-entity-change (argumentation :question))}

;;                 {:name "create question"
;;                  :available? (not (empty? (:text state)))
;;                  :key-patterns [[#{:control} :c] [#{:control} :q]]
;;                  :run! (create-run-create-entity state-atom db on-entity-change (argumentation :question))}

;;                 {:name "create statement"
;;                  :available? (not (empty? (:text state)))
;;                  :key-patterns [[#{:control} :c] [#{:control} :s]]
;;                  :run! (create-run-create-entity state-atom db on-entity-change (argumentation :statement))}

;;                 {:name "create concept"
;;                  :available? (not (empty? (:text state)))
;;                  :key-patterns [[#{:control} :c] [#{:control} :c]]
;;                  :run! (create-run-create-entity state-atom db on-entity-change (argumentation :concept))}

;;                 ;; {:name "create notebook"
;;                 ;;  :available? (not (empty? (:text state)))
;;                 ;;  :key-patterns [[#{:control} :c] [#{:control} :n]]
;;                 ;;  :run! (create-run-create-entity state-atom db on-entity-change (stred :notebook))}

;;                 {:name "commit selection"
;;                  :available? (not (empty? (:results state)))
;;                  :key-patterns [[#{} :enter]]
;;                  :run! (fn [_subtree]
;;                          (swap! state-atom
;;                                 assoc :text ""
;;                                 :results [])
;;                          (on-entity-change (nth (vec (:results state))
;;                                                 (:selected-index state))))}

;;                 {:name "select next"
;;                  :available? (and (not (empty? (:results state)))
;;                                   (< (:selected-index state)
;;                                      (dec (count (:results state)))))
;;                  :key-patterns [[#{:meta} :n]]
;;                  :run! (fn [_subtree]
;;                          (swap! state-atom
;;                                 update :selected-index inc))}

;;                 {:name "select previous"
;;                  :available? (and (not (empty? (:results state)))
;;                                   (< 0
;;                                      (:selected-index state)))
;;                  :key-patterns [[#{:meta} :p]]
;;                  :run! (fn [_subtree]
;;                          (swap! state-atom
;;                                 update :selected-index dec))}]}))

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
;;   (cond (and (= :descent (on-target:phase event))
;;              (= :focus-gained (:type event)))
;;         (swap! state-atom assoc :focused? true)


;;         (and (= :descent (:phase event))
;;              (= :focus-lost (:type event)))
;;         (swap! state-atom assoc :focused? false))

;;   event)

;; (defn prompt [_db _types _on-entity-change]
;;   (let [state-atom (dependable-atom/atom "prompt-state"
;;                                          {:text ""
;;                                           :selected-index 0})]
;;     (fn [db types on-entity-change]
;;       (let [state @state-atom]
;;         (-> (ver 0
;;                  [focus-highlight (-> (text-editor (:text state)
;;                                                    (fn [new-text]
;;                                                      (let [entities (if (= "" new-text)
;;                                                                       []
;;                                                                       (if types
;;                                                                         (distinct (mapcat (fn [type]
;;                                                                                             (search-entities db type new-text))
;;                                                                                           types))
;;                                                                         (distinct (search-entities db new-text))))]
;;                                                        (swap! state-atom
;;                                                               (fn [state]
;;                                                                 (assoc state
;;                                                                        :results entities
;;                                                                        :selected-index 0
;;                                                                        :text new-text))))))
;;                                       (assoc :local-id :prompt-editor))]
;;                  (when (and (keyboard/sub-component-is-focused?)
;;                             (not (empty? (:results state))))
;;                    (layouts/hover {:z 4}
;;                                   (box (layouts/vertically-2 {}
;;                                                              (map-indexed (fn [index statement]
;;                                                                             (-> (highlight-2 (= index (:selected-index state))
;;                                                                                              (value-view db statement))
;;                                                                                 (assoc :mouse-event-handler [on-click-mouse-event-handler (fn []
;;                                                                                                                                             (on-entity-change statement))])))
;;                                                                           (:results state)))))))
;;             (assoc :command-set (prompt-command-set state-atom db on-entity-change)))))))

(defn prompt-2-command-set [state-atom commands]
  (let [state @state-atom]
    {:name "prompt"
     :commands (concat (filter :key-patterns
                               commands)
                       [{:name "commit selection"
                         :available? (and (:show-dropdown? state)
                                          (not (empty? commands)))
                         :key-patterns [[#{} :enter]]
                         :run! (fn [subtree]
                                 (swap! state-atom
                                        assoc
                                        :text ""
                                        :results []
                                        :show-dropdown? false)
                                 ((:run! (nth (vec commands)
                                              (:selected-index state)))
                                  subtree))}

                        {:name "show dropdown"
                         :available? (not (:show-dropdown? state))
                         :key-patterns [[#{} :enter]]
                         :run! (fn [_subtree]
                                 (swap! state-atom
                                        assoc :show-dropdown? true))}

                        {:name "hide dropdown"
                         :available? (and (:show-dropdown? state)
                                          (not (empty? (:commands @state-atom))))
                         :key-patterns escape-key-pattern-sequences
                         :run! (fn [_subtree]
                                 (swap! state-atom
                                        assoc
                                        :show-dropdown? false
                                        :text ""
                                        :results []))}

                        {:name "select next"
                         :available? (and (:show-dropdown? state)
                                          (not (empty? commands))
                                          (< (:selected-index state)
                                             (dec (count commands))))
                         :key-patterns [[#{:control} :n]] ;; can not use control here because text editor uses it
                         :run! (fn [_subtree]
                                 (swap! state-atom
                                        update :selected-index inc))}

                        {:name "select previous"
                         :available? (and (:show-dropdown? state)
                                          (not (empty? commands))
                                          (< 0
                                             (:selected-index state)))
                         :key-patterns [[#{:control} :p]] ;; can not use control here because text editor uses it
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

(defn run-command-mouse-event-handler [command node event]
  (when (= :mouse-clicked (:type event))
    ((:run! command) node))
  event)

(defn prompt-2 [commands]
  (let [state-atom (dependable-atom/atom "prompt-state"
                                         {:text ""
                                          :selected-index 0
                                          :commands (commands "")
                                          :show-dropdown? true})]

    ;; TODO: combine run-query and commands. run-query hould return commands directly
    (fn [commands]
      (let [state @state-atom
            the-commands (:commands state)]
        (-> (ver 0
                 [focus-highlight (-> (text-editor (:text state)
                                                   (fn [new-text]
                                                     (swap! state-atom
                                                            (fn [state]
                                                              (assoc state
                                                                     :show-dropdown? true
                                                                     :commands (if (= "" new-text)
                                                                                 []
                                                                                 (commands new-text))
                                                                     :selected-index 0
                                                                     :text new-text)))))
                                      (assoc :local-id :prompt-editor))]
                 (when (and (keyboard/sub-component-is-in-focus?)
                            (not (empty? the-commands))
                            (:show-dropdown? state)
                            #_(not (empty? (:commands state))))
                   (layouts/hover (box (layouts/vertically-2 {}
                                                             (map-indexed (fn [index command]
                                                                            (-> (highlight-2 (= index (:selected-index state))
                                                                                             (chor 40
                                                                                                   (when (not (empty? (:key-patterns command)))
                                                                                                     (text (key-patterns-to-string (:key-patterns command))))
                                                                                                   (or (:view command)
                                                                                                       (text (:name command)))))
                                                                                (assoc :mouse-event-handler [run-command-mouse-event-handler command])))
                                                                          the-commands))
                                       {:fill-color (:menu-background theme)}))))
            (assoc :command-set (prompt-2-command-set state-atom
                                                      the-commands)))))))


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
  (->> parent-node-id
       (scene-graph/id-to-local-id-path)
       (scene-graph/get-in-path scene-graph)
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
                       ;; NOW TODO: remove me
                       ;; TODO: where should the  selected entities be set to global-state atom?
                       ;; add root commands to copy selected entities to clipboard
                       ;; add commadns to array editor entity attribute editor to insert entities from clipboard
                       [{:name "drop selection anchor"
                         :available? (not (= (:selected-index state)
                                             (:anchor-index state)))
                         :key-patterns [[[#{:control} :space]]]
                         :run! (fn [_subtree]
                                 (swap! state-atom assoc :anchor-index (:selected-index state)))}
                        {:name "raise selection anchor"
                         :available? (:anchor-index state)
                         :key-patterns escape-key-pattern-sequences
                         :run! (fn [_subtree]
                                 (swap! state-atom dissoc :anchor-index))}
                        {:name "cancel insertion"
                         :available? (:insertion-index state)
                         :key-patterns escape-key-pattern-sequences
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
                                                                        (->> (:id subtree)
                                                                             (drop-last)
                                                                             (scene-graph/id-to-local-id-path)
                                                                             (scene-graph/get-in-path scene-graph)
                                                                             (scene-graph/find-first-child #(= [:value (min (:selected-index state)
                                                                                                                            (- (count array)
                                                                                                                               2))]
                                                                                                               (:local-id %)))
                                                                             (keyboard/set-focused-node!))))))}

                        {:name "insert before"
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
                                                                                              (drop-last (:id subtree))))))}

                        {:name "insert after"
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
                                                                                              (drop-last (:id subtree))))))}

                        {:name "move backward"
                         :available? (and (:selected-index state)
                                          (< 0 (:selected-index state)))
                         :key-patterns [[#{:meta} :p]]
                         :run! (fn [subtree]
                                 (transact! db [[:set entity attribute (vec (move-left (:selected-index state)
                                                                                       array))]])
                                 (swap! state-atom update :selected-index dec))}

                        {:name "move forward"
                         :available? (and (:selected-index state)
                                          (< (:selected-index state)
                                             (dec (count array))))
                         :key-patterns [[#{:meta} :n]]
                         :run! (fn [subtree]
                                 (transact! db [[:set entity attribute (vec (move-right (:selected-index state)
                                                                                        array))]])

                                 (swap! state-atom update :selected-index inc))}]
                       (when allow-array-spreading?
                         [{:name "spread array"
                           :available? true
                           :key-patterns [[#{:control} :a]]
                           :run! (fn [_subtree]
                                   (transact! db (into [[:remove entity attribute array]]
                                                       (for [value array]
                                                         [:add entity attribute value]))))}]))}))

(defn focus-gained? [event]
  (and (= :on-target (:phase event))
       (= :focus-gained (:type event))))

(defn focus-lost? [event]
  (and (= :on-target (:phase event))
       (= :focus-lost (:type event))))

(defn array-editor-item-view-keyboard-event-handler [state-atom index _subtree event]
  (cond (focus-gained? event)
        (swap! state-atom assoc :selected-index index)


        (focus-lost? event)
        (swap! state-atom dissoc :selected-index))

  event)

(defn array-editor-nodes [state-atom db entity attribute new-item-transaction available-items item-view show-empty-prompt? allow-array-spreading? item-removal-transaction item-commands]
  (let [state @state-atom
        array (db-common/value db entity attribute)]
    (if (and (empty? array)
             show-empty-prompt?)
      [{:local-id :insertion-prompt
        :node [prompt-2
               (fn [new-text]
                 (for [item (available-items new-text)]
                   (assoc item
                          :run! (fn [_subtree]
                                  (let [{:keys [item-id transaction]} (new-item-transaction item)]
                                    (transact! db (concat transaction
                                                          [[:set entity attribute [item-id]]])))))))]}]
      (apply concat
             (map-indexed (fn [index value-entity]
                            (let [command-set (array-editor-command-set state-atom
                                                                        db
                                                                        entity
                                                                        attribute
                                                                        allow-array-spreading?
                                                                        item-removal-transaction
                                                                        item-commands)
                                  item-view (assoc (highlight-3 (cond (= index (:selected-index state))
                                                                      (:highlighted-background-color theme)

                                                                      (and (:anchor-index state)
                                                                           (or (and (>= index
                                                                                        (:anchor-index state))
                                                                                    (< index
                                                                                       (:selected-index state)))
                                                                               (and (<= index
                                                                                        (:anchor-index state))
                                                                                    (> index
                                                                                       (:selected-index state)))))
                                                                      (:selection-background-color theme)

                                                                      :else
                                                                      [0 0 0 0]
                                                                      ;;(:background-color theme)
                                                                      )
                                                                {:node [item-view db value-entity]
                                                                 :local-id [:value index]
                                                                 :mouse-event-handler [focus-on-click-mouse-event-handler]
                                                                 :can-gain-focus? true
                                                                 :array-value true
                                                                 :keyboard-event-handler [array-editor-item-view-keyboard-event-handler
                                                                                          state-atom
                                                                                          index]})
                                                   :command-set command-set)
                                  insertion-prompt
                                  (assoc-last :local-id :insertion-prompt
                                              :command-set command-set
                                              (layouts/wrap [prompt-2
                                                             (fn [new-text]
                                                               (for [item (available-items new-text)]
                                                                 (assoc item
                                                                        :run! (fn [subtree]
                                                                                (let [{:keys [item-id transaction]} (new-item-transaction item)]
                                                                                  (transact! db (concat transaction
                                                                                                        [[:set entity attribute (insert array (:insertion-index state) item-id)]])))
                                                                                (swap! state-atom dissoc :insertion-index)

                                                                                (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                                                                     (->> (:id subtree)
                                                                                                                          (scene-graph/id-to-local-id-path)
                                                                                                                          (reverse)
                                                                                                                          (drop-while (fn [local-id]
                                                                                                                                        (not (= local-id
                                                                                                                                                :insertion-prompt))))
                                                                                                                          (drop 1)
                                                                                                                          (reverse)
                                                                                                                          (scene-graph/get-in-path scene-graph)
                                                                                                                          (scene-graph/find-first-breath-first #(= [:value (:insertion-index state)]
                                                                                                                                                                   (:local-id %)))
                                                                                                                          (keyboard/set-focused-node!))))))))]))]

                              (cond (and (= index (dec (count array)))
                                         (= (inc index) (:insertion-index state)))
                                    [item-view
                                     insertion-prompt]

                                    (= index (:insertion-index state))
                                    [insertion-prompt
                                     item-view]

                                    :else
                                    [item-view])))
                          array)))))

(defn array-editor [_db _entity _attribute _item-removal-transaction _new-item-transaction _available-items _item-view & [_options]]
  (let [state-atom (dependable-atom/atom "array-editor-state"
                                         {})]
    (fn array-editor [db entity attribute item-removal-transaction new-item-transaction available-items item-view & [{:keys [item-commands
                                                                                                                             show-empty-prompt?
                                                                                                                             allow-array-spreading?]
                                                                                                                      :or {show-empty-prompt? true
                                                                                                                           allow-array-spreading? false}}]]
      (apply ver
             0
             (array-editor-nodes state-atom
                                 db
                                 entity
                                 attribute
                                 new-item-transaction
                                 available-items
                                 item-view
                                 show-empty-prompt?
                                 allow-array-spreading?
                                 item-removal-transaction
                                 item-commands)))))

;; (defn entity-array-attribute-editor-command-set [state-atom db entity attribute]
;;   (let [state @state-atom
;;         array (db-common/value db entity attribute)]
;;     {:name "entity array editor"
;;      :commands [{:name "cancel insertion"
;;                  :available? (:insertion-index state)
;;                  :key-patterns escape-key-pattern-sequences
;;                  :run! (fn [_subtree]
;;                          (swap! state-atom dissoc :insertion-index))}

;;                 {:name "delete selected"
;;                  :available? (and (not (:insertion-index state))
;;                                   (not (empty? array)))
;;                  :key-patterns [[#{:control} :d]]
;;                  :run! (fn [subtree]
;;                          (transact! db [[:set entity attribute (drop-index (:selected-index state)
;;                                                                            array)]])

;;                          (when (< 1 (count array))
;;                            (keyboard/handle-next-scene-graph! (fn [scene-graph]
;;                                                                 (->> (scene-graph/find-first #(= (:id subtree)
;;                                                                                                  (:id %))
;;                                                                                              scene-graph)
;;                                                                      (scene-graph/find-first-child #(= [:value (min (:selected-index state)
;;                                                                                                                     (- (count array)
;;                                                                                                                        2))]
;;                                                                                                        (:local-id %)))
;;                                                                      (keyboard/set-focused-node!))))))}

;;                 {:name "insert above"
;;                  :available? true
;;                  :key-patterns [[#{:control} :i]]
;;                  :run! (fn [subtree]
;;                          (swap! state-atom
;;                                 (fn [state]
;;                                   (-> state
;;                                       (assoc :insertion-index (:selected-index state))
;;                                       (dissoc :selected-index))))
;;                          (keyboard/handle-next-scene-graph! (fn [scene-graph]
;;                                                               (focus-insertion-prompt scene-graph
;;                                                                                       (:id subtree)))))}
;;                 {:name "insert below"
;;                  :available? true
;;                  :key-patterns [[#{:control :shift} :i]]
;;                  :run! (fn [subtree]
;;                          (swap! state-atom
;;                                 (fn [state]
;;                                   (-> state
;;                                       (assoc :insertion-index (inc (:selected-index state)))
;;                                       (dissoc :selected-index))))
;;                          (keyboard/handle-next-scene-graph! (fn [scene-graph]
;;                                                               (focus-insertion-prompt scene-graph
;;                                                                                       (:id subtree)))))}]}))

(defn entity-array-attribute-editor-value-view-keyboard-event-handler [state-atom index _subtree event]
  ;; (prn 'event event)

  (cond (and (= :on-target (:phase event))
             (= :focus-gained (:type event)))
        (swap! state-atom assoc :selected-index index)


        (and (= :on-target (:phase event))
             (= :focus-lost (:type event)))
        (swap! state-atom dissoc :selected-index))

  event)

;; (defn entity-array-attribute-editor [_db _entity _attribute]
;;   (let [state-atom (dependable-atom/atom "entity-array-attribute-editor-state"
;;                                          {})]
;;     (fn entity-array-attribute-editor [db entity attribute]


;;       (let [state @state-atom
;;             array (db-common/value db entity attribute)
;;             entity-array-attribute-editor-node-id view-compiler/id]
;;         (if (empty? array)
;;           ^{:local-id :insertion-prompt}
;;           [prompt
;;            db
;;            [(argumentation :statement)]
;;            (fn on-new-entity [new-entity]
;;              (transact! db [[:set entity attribute [new-entity]]]))]
;;           (-> (ver 0 (map-indexed (fn [index value-entity]
;;                                     (let [value-view (-> (highlight-2 (= index (:selected-index state))
;;                                                                       (value-view db value-entity))
;;                                                          (assoc :mouse-event-handler [focus-on-click-mouse-event-handler]
;;                                                                 :can-gain-focus? true
;;                                                                 :array-value true
;;                                                                 :local-id [:value index]
;;                                                                 :entity value-entity
;;                                                                 :keyboard-event-handler [entity-array-attribute-editor-value-view-keyboard-event-handler
;;                                                                                          state-atom
;;                                                                                          index]))]

;;                                       (let [insertion-prompt ^{:local-id :insertion-prompt} [prompt
;;                                                                                              db
;;                                                                                              [(argumentation :statement)]
;;                                                                                              (fn on-new-entity [new-entity]
;;                                                                                                (transact! db [[:set entity attribute (insert array (:insertion-index state) new-entity)]])
;;                                                                                                (swap! state-atom dissoc :insertion-index)

;;                                                                                                (keyboard/handle-next-scene-graph! (fn [scene-graph]


;;                                                                                                                                     (->> (scene-graph/find-first #(= entity-array-attribute-editor-node-id
;;                                                                                                                                                                      (:id %))
;;                                                                                                                                                                  scene-graph)
;;                                                                                                                                          (scene-graph/find-first-child #(= [:value (:insertion-index state)]
;;                                                                                                                                                                            (:local-id %)))
;;                                                                                                                                          (keyboard/set-focused-node!)))))]]
;;                                         (cond (and (= index (dec (count array)))
;;                                                    (= (inc index) (:insertion-index state)))
;;                                               (ver 0
;;                                                    value-view
;;                                                    insertion-prompt)

;;                                               (= index (:insertion-index state))
;;                                               (ver 0
;;                                                    insertion-prompt
;;                                                    value-view)

;;                                               :else
;;                                               value-view))))
;;                                   array))
;;               (assoc ;; :keyboard-event-handler [entity-array-attribute-editor-keyboard-event-handler state-atom]
;;                :command-set (entity-array-attribute-editor-command-set state-atom
;;                                                                        db
;;                                                                        entity
;;                                                                        attribute)
;;                ;; :can-gain-focus? true
;;                )))))))

(declare outline-view)

(defn entity-array-attribute-editor-2 [db entity attribute lens-map & {:keys [shared-lens add-lens]}]
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

   (fn available-items [query-text]
     (concat (for [entity (if (empty? query-text)
                            []
                            (distinct (search-entities db
                                                       query-text)))]
               {:entity entity
                :view (value-view db entity)})
             (when query-text
               [{:name "Create entity "
                 :available? (constantly true)
                 :key-patterns [[#{:control} :c] [#{:control} :n]]
                 :label query-text}])))

   (fn item-view [db entity]
     [outline-view
      db
      entity
      (or (get lens-map
               entity)
          shared-lens)
      {:add-lens (fn []
                   (add-lens entity))}])
   {:allow-array-spreading? true}])



(defn sort-entity-ids [entity-ids]
  (sort-by (fn [entity-id]
             [(:stream-id entity-id)
              (:id entity-id)])
           entity-ids))

(derivation/def-derivation focused-node-id
  (:focused-node-id @keyboard/state-atom))

(derivation/def-derivation focused-subtrees-with-command-sets
  (if-let [focused-node-id @focused-node-id]
    ;;    TODO: this path-to call must be cached because otherwise this derivation invalidates the view compilation. There are anonyme closueres in text editor command set which make the consecutive values unequal even though they are in practice the same
    (->> (scene-graph/path-to #_scene-graph/current-scene-graph ;; @scene-graph/current-scene-graph-atom is not dependable so this derivation does not get invalidated when the scene graph changes
                              ;;(:scene-graph @keyboard/state-atom)
                              @scene-graph/current-scene-graph-atom
                              focused-node-id)
         (filter :command-set)
         (reverse))
    []))

(def page-size 50)

(defn entity-attribute-editor-command-set [state-atom db entity attribute values reverse?]
  (let [state @state-atom]
    {:name "entity attribute editor"
     :commands [{:name "cancel adding"
                 :available? (and (not (empty? values))
                                  (:adding? state))
                 :key-patterns escape-key-pattern-sequences
                 :run! (fn [_subtree]
                         (swap! state-atom assoc :adding? false))}

                {:name "remove selected"
                 :available? (and (not (empty? values))
                                  (:selected-index state))
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
                                                                   (keyboard/set-focused-node!)))))}

                {:name "next page"
                 :available? (< (* page-size
                                   (inc (:page state)))
                                (count values))
                 :key-patterns [[#{:control :meta} :n]]
                 :run! (fn [subtree]
                         (swap! state-atom
                                (fn [state]
                                  (-> state
                                      (update :page inc)
                                      (assoc :selected-index 0))))
                         (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                              (->> (scene-graph/find-first-breath-first #(= (:id subtree)
                                                                                                            (:id %))
                                                                                                        scene-graph)
                                                                   (scene-graph/find-first-breath-first #(= [:value 0]
                                                                                                            (:local-id %)))
                                                                   (scene-graph/find-first-breath-first :can-gain-focus?)
                                                                   (keyboard/set-focused-node!)))))}
                {:name "previous page"
                 :available? (< 0 (:page state))
                 :key-patterns [[#{:control :meta} :p]]
                 :run! (fn [subtree]
                         (swap! state-atom
                                (fn [state]
                                  (-> state
                                      (update :page dec)
                                      (assoc :selected-index 0))))
                         (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                              (->> (scene-graph/find-first-breath-first #(= (:id subtree)
                                                                                                            (:id %))
                                                                                                        scene-graph)
                                                                   (scene-graph/find-first-breath-first #(= [:value 0]
                                                                                                            (:local-id %)))
                                                                   (scene-graph/find-first-breath-first :can-gain-focus?)
                                                                   (keyboard/set-focused-node!)))))}]}))


(defn focus-on-prompt [scene-graph]
  (->> scene-graph
       (scene-graph/find-first #(= :prompt (:local-id %)))
       (scene-graph/find-first :can-gain-focus?)
       keyboard/set-focused-node!))

(defn starts-with? [sequence-1 sequence-2]
  (= (take (count sequence-1)
           sequence-2)
     sequence-1))

(defn entity-attribute-editor [_db _entity _attribute _lens-map & _options]
  (let [state-atom (dependable-atom/atom "entity-attribute-editor-state"
                                         {:page 0})]
    (fn entity-attribute-editor [db entity attribute lens-map & [{:keys [reverse? add-lens shared-lens]}]]
      (let [state @state-atom
            value-entities (sort-entity-ids (if reverse?
                                              (concat (common/entities db attribute entity)
                                                      (common/entities-referring-with-sequence db entity))
                                              (common/values db entity attribute)))
            entity-attribute-editor-node-id view-compiler/id]
        (-> (ver 0
                 (when (< page-size (count value-entities))
                   (text (str (inc (:page state))
                              "/"
                              (int (Math/ceil (/ (count value-entities)
                                                 page-size))))))
                 (map-indexed (fn [index value-entity]
                                (highlight-2 (= index (:selected-index state))
                                             {:node [outline-view
                                                     db
                                                     value-entity
                                                     (or (get lens-map
                                                              value-entity)
                                                         shared-lens)
                                                     {:add-lens (fn []
                                                                  (add-lens value-entity))}]
                                              :mouse-event-handler [focus-on-click-mouse-event-handler]
                                              :local-id [:value index]
                                              :entity value-entity
                                              :keyboard-event-handler [entity-array-attribute-editor-value-view-keyboard-event-handler
                                                                       state-atom
                                                                       index]}))
                              (take page-size
                                    (drop (* page-size
                                             (:page state))
                                          value-entities)))
                 (when (or (empty? value-entities)
                           (:adding? state))
                   {:local-id :adding-prompt
                    :node [prompt-2
                           (fn commands [query-text]
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
                               (concat (for [new-value-entity (distinct (search-entities db
                                                                                         query-text))]
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
                                         :key-patterns  [[#{:control} :c] [#{:control} :c]]
                                         :run! (fn [_subtree]
                                                 (let [temporary-id-resolution (:temporary-id-resolution (transact! db (concat [[:add :tmp/new-entity (prelude :label) query-text]]
                                                                                                                               (if reverse?
                                                                                                                                 [[:add :tmp/new-entity attribute entity]]
                                                                                                                                 [[:add entity attribute :tmp/new-entity]]))))

                                                       new-value-entity (get temporary-id-resolution :tmp/new-entity)]
                                                   (focus-on-new-entity new-value-entity)
                                                   (swap! state-atom assoc :adding? false)))}])))]}))
            (assoc :command-set (entity-attribute-editor-command-set state-atom
                                                                     db
                                                                     entity
                                                                     attribute
                                                                     value-entities
                                                                     reverse?)))))))


(defn empty-attribute-prompt [db entity attribute reverse?]
  [prompt-2
   (fn commands [query-text]
     (concat (for [value-entity (distinct (search-entities db
                                                           query-text))]
               {:view (value-view db value-entity)
                :available? true
                :run! (fn [_subtree]
                        (transact! db [(if reverse?
                                         [:add value-entity attribute entity]
                                         [:add entity attribute value-entity])]))})
             (when (and query-text
                        (not reverse?))
               [{:view (text (pr-str query-text))
                 :available? true
                 :key-patterns  [[#{:control} :enter]]
                 :run! (fn [_subtree]
                         (transact! db [[:add entity attribute query-text]]))}])

             [{:name "Create new entity"
               :available? true
               :key-patterns  [[#{:control} :c] [#{:control} :c]]
               :run! (fn [_subtree]
                       (transact! db (concat [[:add :tmp/new-entity (prelude :label) query-text]]
                                             (if reverse?
                                               [[:add :tmp/new-entity attribute entity]]
                                               [[:add entity attribute :tmp/new-entity]]))))}]))])

(defn property [label editor]
  (hor 0
       (layouts/with-margins 0 0 0 0
         (text (str label ":")
               {:font bold-font}))
       editor))

(defn property-editor [_db _entity _attribute _reverse? _editor-entity _editor]
  (let [state-atom (dependable-atom/atom {})]
    (fn [db entity attribute reverse? editor-entity editor-view]
      {:node (hor 0
                  (if (:show-attribute-prompt? @state-atom)
                    {:local-id :new-attribute-prompt
                     :node [prompt-2
                            (fn commands [query-text]
                              (concat (when (not (empty? query-text))
                                        (for [attribute-candidate (remove #{attribute}
                                                                          (search-entities db
                                                                                           (prelude :attribute)
                                                                                           query-text))]
                                          {:view (text (str (str (when reverse?
                                                                   "<-")
                                                                 (label db attribute-candidate)))
                                                       {:font bold-font})
                                           :run! (fn [_subtree]
                                                   (swap! state-atom assoc :show-attribute-prompt? false)
                                                   (transact! db (concat [[:remove editor-entity (stred :attribute) attribute]
                                                                          [:add editor-entity (stred :attribute) attribute-candidate]]
                                                                         (if reverse?
                                                                           (db-common/changes-to-change-reverse-attribute (db-common/deref db)
                                                                                                                          entity
                                                                                                                          attribute
                                                                                                                          attribute-candidate)
                                                                           (db-common/changes-to-change-attribute (db-common/deref db)
                                                                                                                  entity
                                                                                                                  attribute
                                                                                                                  attribute-candidate)))))}))

                                      (when (not (empty? query-text))
                                        [{:name "Create new attribute"
                                          :key-patterns  [[#{:control} :c] [#{:control} :c]]
                                          :run! (fn [_subtree]
                                                  (swap! state-atom assoc :show-attribute-prompt? false)
                                                  (transact! db (concat [[:remove editor-entity (stred :attribute) attribute]
                                                                         [:add editor-entity (stred :attribute) :tmp/new-attribute]]
                                                                        (db-common/changes-to-change-attribute (db-common/deref db)
                                                                                                               entity
                                                                                                               attribute
                                                                                                               :tmp/new-attribute)
                                                                        [[:add
                                                                          :tmp/new-attribute
                                                                          (prelude :type-attribute)
                                                                          (prelude :attribute)]

                                                                         [:add
                                                                          :tmp/new-attribute
                                                                          (prelude :label)
                                                                          query-text]])))}])))]}
                    (layouts/with-margins 0 0 0 0
                      (text (str (str (when reverse?
                                        "<-")
                                      (label db attribute))
                                 ":")
                            {:font bold-font})))
                  editor-view)
       :command-set {:name "property"
                     :commands [{:name "change attribute"
                                 :available? (not (:show-attribute-prompt? @state-atom))
                                 :key-patterns [[[#{:control} :a]]]
                                 :run! (fn [subtree]
                                         (swap! state-atom assoc :show-attribute-prompt? true)

                                         (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                              (->> scene-graph
                                                                                   (scene-graph/find-first-breath-first #(= (:id subtree)
                                                                                                                            (:id %)))
                                                                                   (scene-graph/find-first #(= :new-attribute-prompt (:local-id %)))
                                                                                   (scene-graph/find-first :can-gain-focus?)
                                                                                   keyboard/set-focused-node!))))}

                                {:name "cancel attribute change"
                                 :available? (:show-attribute-prompt? @state-atom)
                                 :key-patterns escape-key-pattern-sequences
                                 :run! (fn [_subtree]
                                         (swap! state-atom assoc :show-attribute-prompt? false))}]}})))

(defn available-lens-editor-items [query-text db lens entities]
  (let [existing-attributes-set (->> (mapcat #(common/entity-attributes db %)
                                             entities)
                                     #_(remove (fn [attribute]
                                                 (= "stred" (:stream-id attribute))))
                                     (set))
        existing-reverse-attributes-set (->> (mapcat #(concat (common/reverse-entity-attributes db %)
                                                              (common/entity-sequence-reference-attributes db %))
                                                     entities)
                                             #_(remove (fn [attribute]
                                                         (= "stred" (:stream-id attribute))))
                                             (set))
        all-existing-attributes (set/union existing-attributes-set
                                           existing-reverse-attributes-set)
        matched-attribute? (fn [attribute]
                             (or (empty? query-text)
                                 (string/includes? (string/lower-case (label db attribute))
                                                   (string/lower-case query-text))))
        visible-editors (common/value db lens (stred :editors))
        visible-attributes-set (->> visible-editors
                                    (remove (fn [editor]
                                              (common/value db editor (stred :reverse?))))
                                    (map (fn [editor]
                                           (common/value db editor (stred :attribute))))
                                    (into #{}))
        visible-reverse-attributes-set (->> visible-editors
                                            (filter (fn [editor]
                                                      (common/value db editor (stred :reverse?))))
                                            (map (fn [editor]
                                                   (common/value db editor (stred :attribute))))
                                            (into #{}))]
    (concat (for [attribute (remove visible-attributes-set
                                    (filter matched-attribute?
                                            existing-attributes-set))]
              {:entity attribute
               :view (text (str "-> " (label db attribute)))})
            (for [attribute (remove visible-reverse-attributes-set
                                    (filter matched-attribute?
                                            existing-reverse-attributes-set))]
              {:entity attribute
               :view (text (str "<- " (label db attribute)))
               :reverse? true})
            (for [attribute (remove (set/union visible-attributes-set
                                               visible-reverse-attributes-set)
                                    (if (empty? query-text)
                                      []
                                      (distinct (search-entities db
                                                                 (prelude :attribute)
                                                                 query-text))))]
              {:entity attribute
               :view (text (label db attribute))})
            ;; (when (not (empty? all-existing-attributes))
            ;;   [{:name (str "Add all existing attributes")
            ;;     :available? (constantly true)
            ;;     :key-patterns [[#{:control} :c] [#{:control} :a]]
            ;;     :existing-attributes-set existing-attributes-set
            ;;     :existing-reverse-attributes-set existing-reverse-attributes-set}])
            (when (not (empty? query-text))
              [{:name "Create new attribute"
                :available? (constantly true)
                :key-patterns [[#{:control} :c] [#{:control} :c]]
                :label query-text}

               ;; {:name (str "Create text attribute " query-text)
               ;;  :available? (constantly true)
               ;;  :key-patterns [[#{:control} :c] [#{:control} :t]]
               ;;  :label query-text
               ;;  :range (prelude :text)}

               ;; {:name (str "Create entity attribute " query-text)
               ;;  :available? (constantly true)
               ;;  :key-patterns [[#{:control} :c] [#{:control} :e]]
               ;;  :label query-text
               ;;  :range (prelude :entity)}
               ]))))

(declare table-view)

(defn outline-view [_db _entity _lens & [_options]]
  (let [state-atom (dependable-atom/atom {})]
    (fn [db entity lens & [{:keys [add-lens]}]]
      (let [outline-view-id view-compiler/id]
        (assoc-last :entity entity
                    :can-gain-focus? true
                    :command-set {:name "outline view"
                                  :commands [{:name "show prompt"
                                              :available? (not (:show-empty-prompt? @state-atom))
                                              :key-patterns [[#{:control} :e]]
                                              :run! (fn [_subtree]
                                                      (swap! state-atom assoc :show-empty-prompt? true)
                                                      (when (nil? lens)
                                                        (add-lens))
                                                      (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                                           (->> scene-graph
                                                                                                (scene-graph/find-first-breath-first #(= outline-view-id
                                                                                                                                         (:id %)))
                                                                                                (scene-graph/find-first-breath-first #(= :insertion-prompt
                                                                                                                                         (:local-id %)))
                                                                                                (scene-graph/find-first-child :can-gain-focus?)
                                                                                                (keyboard/set-focused-node!)))))}
                                             {:name "hide prompt"
                                              :available? (:show-empty-prompt? @state-atom)
                                              :key-patterns escape-key-pattern-sequences
                                              :run! (fn [_subtree]
                                                      (swap! state-atom assoc :show-empty-prompt? false))}

                                             {:name "toggle edit label"
                                              :available? true
                                              :key-patterns [[#{:control :meta} :e]]
                                              :run! (fn [_subtree]
                                                      (swap! state-atom update :edit-label? not)
                                                      (when (:edit-label? @state-atom)
                                                        (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                                             (->> (scene-graph/find-first-breath-first #(= outline-view-id
                                                                                                                                           (:id %))
                                                                                                                                       scene-graph)
                                                                                                  (scene-graph/find-first-breath-first #(= :label-editor
                                                                                                                                           (:local-id %)))
                                                                                                  (scene-graph/find-first-child :can-gain-focus?)
                                                                                                  (keyboard/set-focused-node!))))))}]}


                    (ver 0
                         (if (:edit-label? @state-atom)
                           (entity-value-type-view db
                                                   entity
                                                   {:local-id :label-editor
                                                    :node [text-attribute-editor db entity (prelude :label)]})
                           (value-view db entity))
                         ;;                     (text (str "Lens:" (pr-str lens)))
                         (when lens
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
                                                                                               (stred :lens-map) {}
                                                                                               (stred :table-lens) :tmp/new-table-lens}))
                                                (map-to-transaction/map-to-statements (merge {:dali/id :tmp/new-editor
                                                                                              (prelude :type-attribute) (stred :editor)
                                                                                              (stred :attribute) (:entity new-item)
                                                                                              (stred :lens-map) {}
                                                                                              (stred :table-lens) :tmp/new-table-lens}
                                                                                             (when (:reverse? new-item)
                                                                                               {(stred :reverse?) true}))))})
                              (fn available-items [query-text]
                                (available-lens-editor-items query-text db lens [entity])
                                #_(let [existing-attributes-set (->> (common/entity-attributes db entity)
                                                                     #_(remove (fn [attribute]
                                                                                 (= "stred" (:stream-id attribute))))
                                                                     (set))
                                        existing-reverse-attributes-set (->> (concat (common/reverse-entity-attributes db entity)
                                                                                     (common/entity-sequence-reference-attributes db entity))
                                                                             #_(remove (fn [attribute]
                                                                                         (= "stred" (:stream-id attribute))))
                                                                             (set))
                                        all-existing-attributes (set/union existing-attributes-set
                                                                           existing-reverse-attributes-set)
                                        matched-attribute? (fn [attribute]
                                                             (or (empty? query-text)
                                                                 (string/includes? (string/lower-case (label db attribute))
                                                                                   (string/lower-case query-text))))
                                        visible-editors (common/value db lens (stred :editors))
                                        visible-attributes-set (->> visible-editors
                                                                    (remove (fn [editor]
                                                                              (common/value db editor (stred :reverse?))))
                                                                    (map (fn [editor]
                                                                           (common/value db editor (stred :attribute))))
                                                                    (into #{}))
                                        visible-reverse-attributes-set (->> visible-editors
                                                                            (filter (fn [editor]
                                                                                      (common/value db editor (stred :reverse?))))
                                                                            (map (fn [editor]
                                                                                   (common/value db editor (stred :attribute))))
                                                                            (into #{}))]
                                    (concat (for [attribute (remove visible-attributes-set
                                                                    (filter matched-attribute?
                                                                            existing-attributes-set))]
                                              {:entity attribute
                                               :view (text (str "-> " (label db attribute)))})
                                            (for [attribute (remove visible-reverse-attributes-set
                                                                    (filter matched-attribute?
                                                                            existing-reverse-attributes-set))]
                                              {:entity attribute
                                               :view (text (str "<- " (label db attribute)))
                                               :reverse? true})
                                            (for [attribute (remove all-existing-attributes
                                                                    (if (empty? text)
                                                                      []
                                                                      (distinct (search-entities db
                                                                                                 (prelude :attribute)
                                                                                                 text))))]
                                              {:entity attribute
                                               :view (text (label db attribute))})
                                            ;; (when (not (empty? all-existing-attributes))
                                            ;;   [{:name (str "Add all existing attributes")
                                            ;;     :available? (constantly true)
                                            ;;     :key-patterns [[#{:control} :c] [#{:control} :a]]
                                            ;;     :existing-attributes-set existing-attributes-set
                                            ;;     :existing-reverse-attributes-set existing-reverse-attributes-set}])
                                            (when (not (empty? query-text))
                                              [{:name "Create new attribute"
                                                :available? (constantly true)
                                                :key-patterns [[#{:control} :c] [#{:control} :c]]
                                                :label query-text}

                                               ;; {:name (str "Create text attribute " query-text)
                                               ;;  :available? (constantly true)
                                               ;;  :key-patterns [[#{:control} :c] [#{:control} :t]]
                                               ;;  :label query-text
                                               ;;  :range (prelude :text)}

                                               ;; {:name (str "Create entity attribute " query-text)
                                               ;;  :available? (constantly true)
                                               ;;  :key-patterns [[#{:control} :c] [#{:control} :e]]
                                               ;;  :label query-text
                                               ;;  :range (prelude :entity)}
                                               ]))))

                              (fn item-view [db editor]
                                (let [attribute (db-common/value db
                                                                 editor
                                                                 (stred :attribute))
                                      reverse? (db-common/value db
                                                                editor
                                                                (stred :reverse?))
                                      lens-map (db-common/value db
                                                                editor
                                                                (stred :lens-map))
                                      shared-lens (db-common/value db
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
                                                                   (prelude :range)))

                                      entity-attribute-editor-call [table-view
                                                                    db
                                                                    entity
                                                                    attribute
                                                                    lens-map
                                                                    {:reverse? reverse?
                                                                     :shared-lens shared-lens
                                                                     :table-lens  (db-common/value db editor (stred :table-lens))
                                                                     :add-lens (fn [value]
                                                                                 ;; TODO: remove nonexisting values from lens map
                                                                                 (transact! db [[:set editor (stred :lens-map)
                                                                                                 (assoc lens-map value :tmp/new-lens)]]))}]
                                      #_[entity-attribute-editor
                                         db
                                         entity
                                         attribute
                                         lens-map
                                         {:reverse? reverse?
                                          :shared-lens shared-lens
                                          :add-lens (fn [value]
                                                      ;; TODO: remove nonexisting values from lens map
                                                      (transact! db [[:set editor (stred :lens-map)
                                                                      (assoc lens-map value :tmp/new-lens)]]))}]]
                                  [property-editor
                                   db
                                   entity
                                   attribute
                                   reverse?
                                   editor
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
                                       entity-attribute-editor-call

                                       (prelude :type-type) ;; TODO make type-type a subtype of entity
                                       entity-attribute-editor-call

                                       (prelude :array)
                                       (ver 0
                                            (text "[")
                                            [entity-array-attribute-editor-2
                                             db
                                             entity
                                             attribute
                                             lens-map
                                             {:shared-lens shared-lens
                                              :add-lens (fn [value]
                                                          (transact! db [[:set editor (stred :lens-map)
                                                                          (assoc lens-map value :tmp/new-lens)]]))}]
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
                                      reverse?])]))

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
                                                                    (= range (prelude :entity))
                                                                    (= range (prelude :type-type)))
                                                    :key-patterns [[#{:control} :r]]
                                                    :run! (fn [_subtree]
                                                            (transact! db [[:set editor (stred :reverse?) (not reverse?)]]))}]))
                               :show-empty-prompt? (and (:show-empty-prompt? @state-atom)
                                                        (starts-with? outline-view-id
                                                                      @focused-node-id))}]))))))))


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

(defn remove-runs [command-set]
  (update command-set :commands (fn [commands]
                                  (map #(dissoc % :run!)
                                       commands))))

(defn command-help [triggered-key-patterns query-text command-sets]
  (ver 20
       (when (not (empty? query-text))
         (text (string/replace query-text
                               #"[^a-z]"
                               "")))
       (when (not (empty? triggered-key-patterns))
         (text triggered-key-patterns))
       (for [command-set command-sets]
         (ver 0
              (text (:name command-set)
                    {:font bold-font})
              (for [command (filter (fn [command]
                                      (and (string/includes? (:name command)
                                                             query-text)
                                           (keyboard/key-patterns-prefix-match? triggered-key-patterns
                                                                                (:key-patterns command))))
                                    (:commands command-set))]
                (text (str (pr-str (:key-patterns command))
                           " "
                           (:name command))
                      {:color (if (:available? command)
                                (:text-color theme)
                                (add-color (:text-color theme)
                                           [0 0 0 -100]))}))))))


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

(defn key-pattern-sequences [key-patterns]
  (if (every? (comp set? first)
              key-patterns)
    [key-patterns]
    key-patterns))

(deftest test-key-pattern-sequences
  (let [single-key-pattern-sequence [[#{:control} :e]
                                     [#{:control} :a]]]
    (is (= [single-key-pattern-sequence]
           (key-pattern-sequences single-key-pattern-sequence)))

    (is (= [single-key-pattern-sequence
            single-key-pattern-sequence]
           (key-pattern-sequences [single-key-pattern-sequence
                                   single-key-pattern-sequence])))))

(defn command-handler-keyboard-event-handler [show-help? state-atom _scene-graph event]
  (when (not (:handled? event))
    (let [focused-subtrees-with-command-sets @focused-subtrees-with-command-sets]
      (if (empty? focused-subtrees-with-command-sets)
        event
        (if (and (= :ascent (:phase event))
                 (= :key-pressed (:type event)))
          (let [triggered-key-patterns (conj (:triggered-key-patterns @state-atom)
                                             (keyboard/event-to-key-pattern event))
                possible-commands-and-subtrees (->> focused-subtrees-with-command-sets
                                                    (mapcat (fn [subtree]
                                                              (for [command (:commands (:command-set subtree))]
                                                                {:subtree subtree
                                                                 :command command})))
                                                    (filter (fn [command-and-subtree]
                                                              (and (:available? (:command command-and-subtree))
                                                                   (some (partial keyboard/key-patterns-prefix-match? triggered-key-patterns)
                                                                         (key-pattern-sequences (:key-patterns (:command command-and-subtree))))))))]
            (if (empty? possible-commands-and-subtrees)
              (do (swap! state-atom assoc :triggered-key-patterns [])
                  event)
              (if-let [matched-command-and-subtree (medley/find-first (fn [command-and-subtree]
                                                                        (keyboard/key-patterns-match? triggered-key-patterns
                                                                                                      (:key-patterns (:command command-and-subtree))))
                                                                      possible-commands-and-subtrees)]
                (do ((:run! (:command matched-command-and-subtree))
                     (:subtree matched-command-and-subtree))
                    (swap! state-atom assoc :text "")
                    nil)
                (do (swap! state-atom assoc :triggered-key-patterns triggered-key-patterns)
                    event))))
          (if (and show-help?
                   (= :descent (:phase event))
                   (= :key-pressed (:type event))
                   (not (:alt? event))
                   (not (:shift? event))
                   (not (:control? event))
                   (not (:meta? event))
                   (:character event)
                   (not (= :enter (:key event)))
                   (not (= :escape (:key event))))
            (if (= :back-space (:key event))
              (swap! state-atom update :text (fn [old-text]
                                               (subs old-text 0 (max 0
                                                                     (dec (count old-text))))))
              (swap! state-atom update :text str (:character event)))
            event))))))

(defn command-handler [_show-help? _child]
  (let [state-atom (dependable-atom/atom "command-handler-state"
                                         {:triggered-key-patterns []
                                          :text ""})]
    ^{:name "command-handler"}
    (fn [show-help? child]
      (let [focused-subtrees-with-command-sets @focused-subtrees-with-command-sets]
        (-> (layouts/vertical-split child
                                    (when show-help?
                                      (ver 10
                                           (assoc (visuals/rectangle-2 :fill-color (:text-color theme))
                                                  :height 10)
                                           {:node [command-help
                                                   (:triggered-key-patterns @state-atom)
                                                   (:text @state-atom)
                                                   (map (comp remove-runs :command-set)
                                                        focused-subtrees-with-command-sets)]
                                            :local-id :command-help})))
            (assoc :keyboard-event-handler [command-handler-keyboard-event-handler
                                            show-help?
                                            state-atom]))))))

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
  (let [state-atom (atom {:branch (create-stream-db-branch uncommitted-stream-id
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

(derivation/def-derivation focused-entity
  (:entity (scene-graph/find-first-breath-first :entity
                                                (-> @keyboard/state-atom :focused-node))))

(defn notebook-view [db notebook]
  (assoc-last :command-set {:name "notebook"
                            :commands [{:name "add focused entity into the notebook"
                                        :available? @focused-entity
                                        :key-patterns [[#{:control :meta} :a]]
                                        :run! (fn [_subtree]
                                                (transact! db (concat (map-to-transaction/map-to-statements {:dali/id :tmp/new-view
                                                                                                             (prelude :type-attribute) (stred :outline-view)
                                                                                                             (stred :lens) {:dali/id :tmp/new-lens}
                                                                                                             (stred :entity) @focused-entity})
                                                                      [[:set notebook (stred :views) (vec (conj (common/value db
                                                                                                                              notebook
                                                                                                                              (stred :views))
                                                                                                                :tmp/new-view))]])))}]}
              (ver 0
                   (assoc-last :local-id :notebook-label
                               (layouts/with-margins 0 0 30 0 [text-attribute-editor db notebook (prelude :label) {:font header-font}]))
                   {:local-id :notebook-body
                    :node [array-editor
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
                                                     (when (:label new-item)
                                                       [[:add
                                                         :tmp/new-entity
                                                         (prelude :label)
                                                         (:label new-item)]])
                                                     (map-to-transaction/map-to-statements {:dali/id :tmp/new-view
                                                                                            (prelude :type-attribute) (stred :outline-view)
                                                                                            (stred :lens) {:dali/id :tmp/new-lens}
                                                                                            (stred :entity) :tmp/new-entity})))})

                           (fn available-items [query-text]
                             (concat (for [entity (if (empty? query-text)
                                                    []
                                                    (distinct (search-entities db query-text)))]
                                       {:entity entity
                                        :view (value-view db entity)})
                                     [{:name "Create new entity"
                                       :label query-text
                                       :available? (constantly true)
                                       :key-patterns [[#{:control} :c] [#{:control} :c]]}]
                                     (for [[index type] (map-indexed vector
                                                                     (into [] (let [types (common/entities-from-ave (common/index db :ave)
                                                                                                                    (prelude :type-attribute)
                                                                                                                    (prelude :type-type))]
                                                                                (concat (remove (fn [type]
                                                                                                  (or (= "prelude"
                                                                                                         (:stream-id type))
                                                                                                      (= "stred"
                                                                                                         (:stream-id type))))
                                                                                                types)
                                                                                        ;; (filter temporary-ids/temporary-id?
                                                                                        ;;         types)
                                                                                        ;; (filter (fn [type]
                                                                                        ;;           (= uncommitted-stream-id
                                                                                        ;;              (:stream-id type)))
                                                                                        ;;         types)
                                                                                        ;; (filter (fn [type]
                                                                                        ;;           (= "base"
                                                                                        ;;              (:stream-id type)))
                                                                                        ;;         types)
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
                                          :label query-text}))))

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
                                                               (prelude :type-attribute))]))))]})))

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


(defn top-prompt [db state-atom]
  [prompt-2
   (fn commands [query-text]
     (concat (when (not (empty? query-text))
               [{:view (text "create notebook")
                 :run! (fn [_subtree]
                         (let [new-notebook (get (:temporary-id-resolution (transact! db (map-to-transaction/map-to-statements {:dali/id :tmp/new-notebook
                                                                                                                                (prelude :type-attribute) (stred :notebook)
                                                                                                                                (prelude :label) query-text})))
                                                 :tmp/new-notebook)]
                           (open-entity! state-atom new-notebook)))}])

             #_(for [value-entity (if (empty? query-text)
                                    []
                                    (distinct (search-entities db
                                                               (stred :notebook)
                                                               text)))]
                 {:view (value-view db value-entity)
                  :available? true
                  :run! (fn [_subtree]
                          (open-entity! state-atom value-entity))})
             (for [notebook (filter (fn [notebook]
                                      (if query-text
                                        (if-let [label (label db notebook)]
                                          (string/includes? (string/lower-case label)
                                                            (string/lower-case query-text))
                                          true)
                                        true))
                                    (common/entities db (prelude :type-attribute) (stred :notebook)))]

               {:view (value-view db notebook)
                #_(text (str "notebook: " (label db notebook)
                             #_(or (label db (common/value db (first (common/value db notebook (stred :views)))
                                                           (stred :entity)))
                                   (common/value db notebook (stred :views)))))
                :available? true
                ;;                   :key-patterns  [[#{:control} :enter]]
                :run! (fn [_subtree]
                        (open-entity! state-atom notebook))})))])

(defn scroll-pane [local-id x y child]
  ;;  child
  #_{:children [child]
     :x x
     :y y
     :local-id local-id}
  (visuals/clip {:children [child]
                 :x x
                 :y y
                 :local-id local-id}))

(defn stred-change? [change]
  (some (fn [value]
          (and (map? value)
               (= "stred" (:stream-id value))))
        change))

(defn move-focus! [& select-node-functions]
  (let [nodes (scene-graph/flatten @scene-graph/current-scene-graph-atom)]
    (when-let [selected-node (some (fn [select-node]
                                     (select-node (scene-graph/find-by-id @focused-node-id
                                                                          nodes)
                                                  (filter :can-gain-focus?
                                                          nodes)))
                                   select-node-functions)]
      (keyboard/move-focus-2! selected-node))))

(defn root-view-command-set [state-atom]
  (let [state @state-atom]
    {:name "root"
     :commands [{:name "toggle view cache misses highlighting"
                 :available? true
                 :key-patterns [[#{:meta} :e]]
                 :run! (fn [_subtree]
                         (swap! application/application-loop-state-atom
                                update :highlight-view-call-cache-misses? not))}
                {:name "toggle viewing of entity ids"
                 :available? true
                 :key-patterns [[#{:meta} :w]]
                 :run! (fn [_subtree]
                         (swap! global-state-atom
                                update :show-entity-ids? not))}
                {:name "descent focus"
                 :available? true
                 :key-patterns [[[#{:meta} :d]]
                                #_[[#{:control} :e]]]
                 :run! (fn [_subtree]
                         (when-let [focusable-child (scene-graph/find-first-child :can-gain-focus?
                                                                                  (scene-graph/find-first #(= (-> @keyboard/state-atom :focused-node-id)
                                                                                                              (:id %))
                                                                                                          @scene-graph/current-scene-graph-atom))]
                           (keyboard/set-focused-node! focusable-child)))}

                {:name "ascent focus"
                 :available? true
                 :key-patterns [[[#{:meta} :a]]
                                #_[[#{:control} :a]]]
                 :run! (fn [_subtree]
                         (when-let [focusable-ancestor (medley/find-first :can-gain-focus?
                                                                          (rest (reverse (scene-graph/path-to @scene-graph/current-scene-graph-atom
                                                                                                              (-> @keyboard/state-atom :focused-node-id)))))]
                           (keyboard/set-focused-node! focusable-ancestor)))}

                {:name "move focus left"
                 :available? true
                 :key-patterns [[#{:control} :b]]
                 :run! (fn [_subtree]
                         (move-focus! scene-graph/closest-node-left))}

                {:name "move focus right"
                 :available? true
                 :key-patterns [[#{:control} :f]]
                 :run! (fn [_subtree]
                         (move-focus! scene-graph/closest-node-right))}

                {:name "move focus down"
                 :available? true
                 :key-patterns [[[#{:control} :n]]
                                #_[[#{:meta} :n]]]
                 :run! (fn [_subtree]
                         (move-focus! scene-graph/closest-node-down))}

                {:name "move focus up"
                 :available? true
                 :key-patterns [[#{:control} :p]]
                 :run! (fn [_subtree]
                         (move-focus! scene-graph/closest-node-up))}

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
                 :run! (fn [subtree]
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
                         (let [temporary-id-resolution (:temporary-id-resolution (transact! (:stream-db state)
                                                                                            (->> (branch-changes (:branch state))
                                                                                                 (map (partial stream-entity-ids-to-temporary-ids (:id (:branch state)))))))]
                           (swap! state-atom assoc :branch (create-stream-db-branch "uncommitted" (db-common/deref (:stream-db state))))
                           (when (= uncommitted-stream-id (:stream-id (:entity @state-atom)))
                             (swap! state-atom
                                    assoc
                                    :branch (create-stream-db-branch uncommitted-stream-id
                                                                     (db-common/deref (:stream-db state)))
                                    :entity (unmerged-entity-id-to-merged-stream-id uncommitted-stream-id
                                                                                    (:entity @state-atom)
                                                                                    temporary-id-resolution)))))}

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
                 :key-patterns [[#{:control} :u]]
                 :run! (fn [_subtree]
                         (swap! state-atom update :show-uncommitted-changes? not))}

                {:name "scroll to focus"
                 :available? @focused-node-id
                 :key-patterns [[#{:control} :l]]
                 :run! (fn [_subtre]
                         (let [focused-path (scene-graph/path-to @scene-graph/current-scene-graph-atom
                                                                 @focused-node-id)
                               scrolling-pane (medley/find-first #(= :scrolling-pane
                                                                     (:local-id %))
                                                                 focused-path)
                               current-absolute-y (reduce + (map :y (remove #(= :scrolling-pane
                                                                                (:local-id %))
                                                                            focused-path)))
                               middle-y (/ (:height scrolling-pane)
                                           2)
                               middle-scroll-y (- middle-y
                                                  (/ (:height (last focused-path))
                                                     2)
                                                  current-absolute-y)]
                           (swap! state-atom assoc :y (if (= (:y state)
                                                             middle-scroll-y)
                                                        (+ 20 (- current-absolute-y))
                                                        middle-scroll-y))))}]}))


(comment
  (scene-graph/path-to the-current-scene-graph
                       the-focused-node-id)
  ) ;; TODO: remove me

(defn root-view [state-atom]
  #_(logga.core/write (pr-str 'event-cache
                              (cache/stats cache/state)))
  (let [state @state-atom
        db (:stream-db state)
        root-view-node-id view-compiler/id]
    (layouts/superimpose (assoc-last :local-id :background
                                     (visuals/rectangle-2 :fill-color (:background-color theme)))

                         [command-handler
                          (:show-help? state)
                          (scroll-pane :scrolling-pane
                                       (:x state)
                                       (:y state)
                                       (-> (ver 10
                                                ;; (text (pr-str (:entity state)))
                                                (assoc-last :local-id :center-prompt-horizontally
                                                            (layouts/center-horizontally

                                                             {:local-id :prompt
                                                              :node [top-prompt (:branch state) state-atom]}))

                                                (when (:entity state)
                                                  (let [entity-type (db-common/value (:branch state)
                                                                                     (:entity state)
                                                                                     (prelude :type-attribute))]

                                                    (ver 10



                                                         ;; (for [i (range 10)]
                                                         ;;   (box (text (str "foo " i))))
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

                                                           (text (:entity state))
                                                           #_[outline-view  (:branch state) (:entity state)]))))
                                                ;; [attribute-selector db]

                                                #_(button "commit" (fn []
                                                                     (transact! (:stream-db state)
                                                                                (branch-changes (:branch state)))
                                                                     (swap! state-atom assoc :branch (create-stream-db-branch uncommitted-stream-id))))
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
                                                       #_(transaction-view (:branch state) (branch-changes (:branch state)) #_the-branch-changes)

                                                       (ver 0 (->> (branch-changes (:branch state))
                                                                   (remove stred-change?)
                                                                   (sort comparator/compare-datoms)
                                                                   (map (partial change-view (:branch state)))))))

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
                                           (assoc :mouse-event-handler (fn [_node event]
                                                                         (when (= :mouse-wheel-rotated
                                                                                  (:type event))
                                                                           (swap! state-atom
                                                                                  update
                                                                                  (if (:horizontal? event)
                                                                                    :x :y)
                                                                                  -
                                                                                  (* 3 (:precise-wheel-rotation event))))
                                                                         event)
                                                  :command-set (root-view-command-set state-atom))))])))

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
        branch (create-stream-db-branch uncommitted-stream-id)]
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
        branch (create-stream-db-branch uncommitted-stream-id)]

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
      [text-area/text-area-3 {:style {:color [255 0 0 255]
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
                        (map vector (repeat 60 random-text-editor))
                        #_[stateful-component]
                        ;; [random-text-editor]
                        ;; [random-text-editor]
                        ;; [random-text-editor]
                        ;; [random-text-editor]
                        ;; [random-text-editor]
                        ))

(defn image-cache-test-root []
  (layouts/vertically-2 {}
                        {:node [random-text-editor]
                         :local-id :editor-1}

                        (assoc-last :local-id :clip
                                    (visuals/clip (assoc-last :local-id :vertically
                                                              (layouts/vertically-2 {}
                                                                                    {:node [random-text-editor]
                                                                                     :local-id :editor-2}
                                                                                    (assoc-last :local-id :vertically-2
                                                                                                (layouts/vertically-2 {}
                                                                                                                      {:node [random-text-editor]
                                                                                                                       :local-id :editor-3}
                                                                                                                      {:node [random-text-editor]
                                                                                                                       :local-id :editor-4}))))))))


(defn adapt-to-space-test-root []
  {:adapt-to-space (fn [_node]
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
                                               branch (create-stream-db-branch uncommitted-stream-id)
                                               entity {:stream-id "stream" :id 5}]


                                           #_(transact! branch
                                                        [[:set
                                                          entity
                                                          (argumentation :premises)
                                                          [{:stream-id "stream" :id 5}]]])

                                           {:stream-db stream-db
                                            :branch branch
                                            :entity nil #_{:stream-id "uoa" :id 181}
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
   ;;  "health" "temp/health"
   ;; "koe" "temp/koe2"
   ;; "koe" "temp/koe5"
   "koe" "temp/koe6"
   ;; "du" "temp/du"
   index-definitions)

  (distinct (let [db (create-stream-db-on-disk "uoa" "temp/uses-of-argument"
                                               index-definitions)]
              (into []
                    (query/reducible-query [(-> db :indexes :eav :collection)
                                            [:?entity
                                             (prelude :type-attribute)
                                             (stred :editor)]
                                            [:?entity
                                             (stred :attribute)
                                             :?attribute]

                                            [:?attribute
                                             (prelude :label)
                                             :?label]]))))


  ;; notebooks

  (distinct (let [db (create-stream-db-on-disk "uoa" "temp/uses-of-argument"
                                               index-definitions)]
              (into []
                    (query/reducible-query [(-> db :indexes :eav :collection)
                                            [:?entity
                                             (prelude :type-attribute)
                                             (stred :notebook)]

                                            [:?entity
                                             (prelude :label)
                                             :?label]]))))

  (doto (create-dependable-stream-db-in-memory "base" index-definitions)
    (transact! [[:add
                 {:stream-id "base", :id 0}
                 (stred :lens-map)
                 {{:stream-id "base" :id 1} "notebook"}]]))



  (let [db (create-stream-db-on-disk
            ;; "stred"
            ;; test-stream-path
            ;; "health" "temp/health"
            ;; "koe" "temp/koe3"
            ;; "koe" "temp/koe4" ;; the switch
            ;; "koe" "temp/koe5"
            ;; "koe" "temp/koe6"
            ;; "du" "temp/du"
            "uoa" "temp/uses-of-argument"
            index-definitions)])


  )

(defn notebook-ui-for-db [db]
  (let [state-atom (dependable-atom/atom "ui-state"
                                         (let [stream-db (merge-stream-dbs #_(create-dependable-stream-db-in-memory "base" index-definitions)
                                                                           (doto (create-dependable-stream-db-in-memory "base" index-definitions)
                                                                             (transact! (concat #_(map-to-transaction/map-to-statements {:dali/id :tmp/notebook
                                                                                                                                         (prelude :type-attribute) (stred :notebook)})
                                                                                                stred-transaction
                                                                                                prelude-transaction
                                                                                                argumentation-schema-transaction)))
                                                                           db)

                                               branch (create-stream-db-branch uncommitted-stream-id (db-common/deref stream-db))
                                               ]

                                           ;;                                           (common/transact! branch the-branch-changes)


                                           #_(transact! branch
                                                        [[:set
                                                          entity
                                                          (argumentation :premises)
                                                          [{:stream-id "stream" :id 5}]]])

                                           {:stream-db stream-db
                                            :branch branch
                                            :entity  #_{:stream-id "uoa" :id 6}  ;; usees of argument
                                            {:id 39, :stream-id "uoa"} ;; health
                                            :previous-entities []
                                            :undoed-transactions '()
                                            :show-help? false
                                            :y 20
                                            :x 20}
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

;; TODO: investiage invalidated dependencies with this and then make the db-demo in which a simple dependable stream-db is used with fungl

(defn notebook-ui []

  (notebook-ui-for-db #_(create-dependable-stream-db-in-memory
                         "demo" index-definitions)

                      (create-stream-db-on-disk
                       ;; "stred"
                       ;; test-stream-path
                       ;; "health" "temp/health"
                       ;; "koe" "temp/koe3"
                       ;; "koe" "temp/koe4" ;; the switch
                       ;; "koe" "temp/koe5"
                       ;; "koe" "temp/koe6"
                       ;; "du" "temp/du"
                       "uoa" "temp/uses-of-argument"
                       index-definitions)))

(defn counter []
  (let [state (dependable-atom/atom 0)]
    (fn []
      ;;      (prn '@focused-node-id-derivation view-compiler/id  @keyboard/focused-node-id-derivation) ;; TODO: remove me

      {:node (text (str "count " @state)
                   {:color (if (keyboard/component-is-in-focus?)
                             [255 255 255 255]
                             [180 180 180 255])})
       :can-gain-focus? true
       :keyboard-event-handler (fn [_scene-graph event]
                                 (when (and (= :key-pressed (:type event))
                                            (= :f (:key event)))
                                   (println "incrementing count" @state)
                                   (swap! state inc)))})))

(defn state-demo []
  (assoc (layouts/superimpose (visuals/rectangle [0 0 0 255] 0 0)
                              (layouts/horizontally-2 {:margin 20}
                                                      {:node [counter]
                                                       :local-id "counter a"}
                                                      {:node [counter]
                                                       :local-id "counter b"}))
         :keyboard-event-handler (fn [_scene-graph event]
                                   (when (and (= :descent (:phase event))
                                              (= :key-pressed (:type event))
                                              (= :tab (:key event)))
                                     (keyboard/cycle-focus (:scene-graph @keyboard/state-atom true)))

                                   (when (and (= :descent (:phase event))
                                              (= :key-pressed (:type event))
                                              (= :escape (:key event)))
                                     (application/send-event! {:type :close-requested})))))

(defn grid-demo []
  #_(box (text "1" {:color [255 255 255 255]})
         {:fill-color [255 0 0 255]})
  (layouts/grid [[(box (text "this" {:color [255 255 255 255]})
                       {:fill-color [255 0 0 255]})
                  (box (text "foo" {:color [255 255 255 255]})
                       {:fill-color [255 0 0 255]})
                  (box (text "baz" {:color [255 255 255 255]})
                       {:fill-color [255 0 0 255]})]

                 [(box (text "bar" {:color [255 255 255 255]})
                       {:fill-color [255 0 0 255]})
                  (box (layouts/with-maximum-size 400 nil (text "this is something that is wrapped" {:color [255 255 255 255]}))
                       {:fill-color [255 0 0 255]})
                  (box (text "bar" {:color [255 255 255 255]})
                       {:fill-color [255 0 0 255]})]]))

(defn split-demo []
  (layouts/vertical-split (visuals/clip (ver 10
                                             (for [i (range 10)]
                                               (box (text (str "xxxxx upper" i))))))
                          (visuals/clip (ver 10
                                             (for [i (range 10)]
                                               (box (text (str "xx middle" i))))))
                          (visuals/clip (ver 10
                                             (for [i (range 10)]
                                               (box (text (str "lower" i))))))))

(defn dynamic-scope-demo []
  (ver 0
       (text "regular text")
       (highlight-2 true (fn [] (text "highlighted text")))
       (binding [theme (update theme :text-color add-color [30 30])]
         (highlight-2 true (fn [] (ver 0
                                       (text "text inside highlighted branch text")
                                       (highlight-2 true
                                                    (fn [] (ver 0
                                                                (text "text inside double highlighted branch text")
                                                                (highlight-2 true
                                                                             (fn [] (text "trible highlighted text"))))))))))))


(defn big-text [string]
  (text string
        {:font (font/create-by-name "CourierNewPSMT" 100)}))

(defn z-order-demo []
  (ver 0
       (ver 0
            (big-text "1")
            (layouts/hover (box (ver 0
                                     (big-text "1.2")
                                     (big-text "1.3"))
                                {:fill-color [0 100 0 220]})))
       {:node [box [big-text "2"]
               {:fill-color [100 1 0 220]}]

        :z 0}
       (big-text "3")))


(comment
  (let [db (create-dependable-stream-db-in-memory "base" index-definitions)
        temporary-id-resolution (:temporary-id-resolution (transact! db (mapcat map-to-transaction/map-to-statements
                                                                                [{:dali/id         :tmp/lens
                                                                                  (stred :editors) []}])))]
    (db-common/values db (:tmp/lens temporary-id-resolution) (stred :editors)))
  ) ;; TODO: remove me

(def table-cell-command-set {:name "table cell"
                             :commands [{:name "move focus left"
                                         :available? true
                                         :key-patterns [[#{:control} :b]]
                                         :run! (fn [_subtree]
                                                 ;;  TODO: moving in table left from empty prompt to list of three entities does not work
                                                 (move-focus! scene-graph/closest-node-directly-left
                                                              scene-graph/closest-node-left))}

                                        {:name "move focus right"
                                         :available? true
                                         :key-patterns [[#{:control} :f]]
                                         :run! (fn [_subtree]
                                                 (move-focus! scene-graph/closest-node-directly-right
                                                              scene-graph/closest-node-right))}

                                        {:name "move focus down"
                                         :available? true
                                         :key-patterns [[[#{:control} :n]]
                                                        #_[[#{:meta} :n]]]
                                         :run! (fn [_subtree]
                                                 (move-focus! scene-graph/closest-node-directly-down
                                                              scene-graph/closest-node-down))}

                                        {:name "move focus up"
                                         :available? true
                                         :key-patterns [[#{:control} :p]]
                                         :run! (fn [_subtree]
                                                 (move-focus! scene-graph/closest-node-directly-up
                                                              scene-graph/closest-node-up))}]})

;; TODO: take common parts from entity-attribute-editor and entity-array-attribute-editor and put them to table view or put table view into them so that table columns can be added to entity-attribute-editor when ever needed
;; table view grid should be possible to apply to any list of entities coming for example forom a datalog query execution


(defn table-view-command-set [state-atom db entity attribute values reverse?]
  (let [state @state-atom]
    {:name "table view"
     :commands [{:name "cancel adding"
                 :available? (and (not (empty? values))
                                  (:adding? state))
                 :key-patterns escape-key-pattern-sequences
                 :run! (fn [_subtree]
                         (swap! state-atom assoc :adding? false))}


                                        ;                TODO: make removing values work by finding out if values are selected without keeping track of :selected-index

                {:name "remove selected"
                 :available? (and (not (empty? values))
                                  (:selected-index state))
                 :key-patterns [[#{:control} :d]]
                 :run! (fn [subtree]
                         (transact! db (let [value (nth values (:selected-index state))]
                                         (if reverse?
                                           [[:remove value attribute entity]]
                                           [[:remove entity attribute value]])))

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


                {:name "toggle empty column prompt"
                 :available? true
                 :key-patterns [[#{:control} :v]]
                 :run! (fn [_subtree]
                         (swap! state-atom update :show-empty-column-prompt? not))}

                {:name "make array"
                 :available? (not reverse?)
                 :key-patterns [[#{:control :meta} :a]]
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
                                                              (->> (scene-graph/find-first-breath-first #(= (:id subtree)
                                                                                                            (:id %))
                                                                                                        scene-graph)
                                                                   (scene-graph/find-first-breath-first #(= :adding-prompt
                                                                                                            (:local-id %)))
                                                                   (scene-graph/find-first-breath-first :can-gain-focus?)
                                                                   (keyboard/set-focused-node!)))))}

                {:name "next page"
                 :available? (< (* page-size
                                   (inc (:page state)))
                                (count values))
                 :key-patterns [[#{:control :meta} :n]]
                 :run! (fn [subtree]
                         (swap! state-atom
                                (fn [state]
                                  (-> state
                                      (update :page inc)
                                      (assoc :selected-index 0))))
                         (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                              (->> (scene-graph/find-first-breath-first #(= (:id subtree)
                                                                                                            (:id %))
                                                                                                        scene-graph)
                                                                   (scene-graph/find-first-breath-first #(= [:value 0]
                                                                                                            (:local-id %)))
                                                                   (scene-graph/find-first-breath-first :can-gain-focus?)
                                                                   (keyboard/set-focused-node!)))))}
                {:name "previous page"
                 :available? (< 0 (:page state))
                 :key-patterns [[#{:control :meta} :p]]
                 :run! (fn [subtree]
                         (swap! state-atom
                                (fn [state]
                                  (-> state
                                      (update :page dec)
                                      (assoc :selected-index 0))))
                         (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                              (->> (scene-graph/find-first-breath-first #(= (:id subtree)
                                                                                                            (:id %))
                                                                                                        scene-graph)
                                                                   (scene-graph/find-first-breath-first #(= [:value 0]
                                                                                                            (:local-id %)))
                                                                   (scene-graph/find-first-breath-first :can-gain-focus?)
                                                                   (keyboard/set-focused-node!)))))}]}))

(defn- table-view-header-row [table-view-state-atom the-value-entities column-array-editor-state-atom db table-lens show-empty-column-prompt?]
  (map vector
       (array-editor-nodes column-array-editor-state-atom
                           db
                           table-lens
                           (stred :editors)

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
                                                                                            (stred :attribute) :tmp/new-attribute}))
                                             (map-to-transaction/map-to-statements (merge {:dali/id :tmp/new-editor
                                                                                           (prelude :type-attribute) (stred :editor)
                                                                                           (stred :attribute) (:entity new-item)}
                                                                                          (when (:reverse? new-item)
                                                                                            {(stred :reverse?) true}))))})
                           (fn available-items [query-text]
                             (available-lens-editor-items query-text db table-lens the-value-entities))

                           (fn item-view [db editor]
                             (let [attribute (db-common/value db
                                                              editor
                                                              (stred :attribute))
                                   ;; sublens (db-common/value db
                                   ;;                          editor
                                   ;;                          (stred :sublens))
                                   reverse? (db-common/value db
                                                             editor
                                                             (stred :reverse?))]

                               (text (str (when reverse?
                                            "<-")
                                          (label db attribute)))
                               #_[
                                  #_(if (= editor
                                           (:show-subcolummn-prompt-for-editor @table-view-state-atom))
                                      [(text "prompt")]
                                      [])]))

                           show-empty-column-prompt? #_(starts-with? table-view-node-id @focused-node-id)

                           false

                           (fn item-removal-transaction [editor]
                             (common/changes-to-remove-component-tree (common/deref db)
                                                                      editor))
                           (fn item-commands [editor]
                             (let [range (db-common/value-in db
                                                             editor
                                                             [(stred :attribute)
                                                              (prelude :range)])
                                   reverse? (db-common/value db
                                                             editor
                                                             (stred :reverse?))]

                               [{:name "toggle reverse"
                                 :available? (or (nil? range)
                                                 (= range (prelude :entity))
                                                 (= range (prelude :type-type)))
                                 :key-patterns [[#{:control} :r]]
                                 :run! (fn [_subtree]
                                         (transact! db [[:set editor (stred :reverse?) (not reverse?)]]))}
                                {:name "add sub column"
                                 :available? true
                                 :key-patterns [[#{:control} :s]]
                                 :run! (fn [_subtree]
                                         (swap! table-view-state-atom assoc :show-subcolummn-prompt-for-editor editor))}])))))


;; (defn- hierarchical-table-view-header-row [current-level-value-entities column-array-editor-state-atom db table-lens reverse? attribute show-empty-column-prompt?]
;;   [(concat [(text "")]
;;            (array-editor-nodes column-array-editor-state-atom
;;                                db
;;                                table-lens
;;                                (stred :editors)

;;                                (fn new-item-transaction [new-item]
;;                                  {:item-id :tmp/new-editor
;;                                   :transaction (if (:label new-item)
;;                                                  (concat [[:add
;;                                                            :tmp/new-attribute
;;                                                            (prelude :type-attribute)
;;                                                            (prelude :attribute)]

;;                                                           [:add
;;                                                            :tmp/new-attribute
;;                                                            (prelude :label)
;;                                                            (:label new-item)]]
;;                                                          (when (:range new-item)
;;                                                            [[:add
;;                                                              :tmp/new-attribute
;;                                                              (prelude :range)
;;                                                              (:range new-item)]])
;;                                                          (map-to-transaction/map-to-statements {:dali/id :tmp/new-editor
;;                                                                                                 (prelude :type-attribute) (stred :editor)
;;                                                                                                 (stred :attribute) :tmp/new-attribute}))
;;                                                  (map-to-transaction/map-to-statements (merge {:dali/id :tmp/new-editor
;;                                                                                                (prelude :type-attribute) (stred :editor)
;;                                                                                                (stred :attribute) (:entity new-item)}
;;                                                                                               (when (:reverse? new-item)
;;                                                                                                 {(stred :reverse?) true}))))})
;;                                (fn available-items [query-text]
;;                                  (available-lens-editor-items query-text db table-lens current-level-value-entities))

;;                                (fn item-view [db editor]
;;                                  (let [attribute (db-common/value db
;;                                                                   editor
;;                                                                   (stred :attribute))
;;                                        reverse? (db-common/value db
;;                                                                  editor
;;                                                                  (stred :reverse?))
;;                                        sub-editors (db-common/value db
;;                                                                     editor
;;                                                                     (stred :editors))
;;                                        show-empty-sub-column-prompt? (= editor
;;                                                                         (:show-empty-prompt-for-editor @column-array-editor-state-atom))]
;;                                    {:command-set {:name "table header cell"
;;                                                   :commands [{:name "toggle sub column prompt"
;;                                                               :available? true
;;                                                               :key-patterns [[#{:control} :s]]
;;                                                               :run! (fn [_subtree]
;;                                                                       (swap! column-array-editor-state-atom
;;                                                                              update
;;                                                                              :show-empty-prompt-for-editor
;;                                                                              (fn [show-empty-prompt-for-editor]
;;                                                                                (if (= show-empty-prompt-for-editor
;;                                                                                       editor)
;;                                                                                  nil
;;                                                                                  editor)))
;;                                                                       (swap! column-array-editor-state-atom
;;                                                                              update
;;                                                                              :sub-column-array-editor-states
;;                                                                              (fn [column-array-editor-state]
;;                                                                                ())))}]}
;;                                     :node (let [header (text (str (when reverse?
;;                                                                     "<-")
;;                                                                   (label db attribute)))]
;;                                             (if (or (not (empty? sub-editors))
;;                                                     show-empty-sub-column-prompt?)
;;                                               (ver 3
;;                                                    (table-view-header-row (mapcat (fn [value-entity]
;;                                                                                     (value-entities reverse? db attribute value-entity))
;;                                                                                   (take 10 current-level-value-entities)) ;; TODO create index to look up possible attributes
;;                                                                           column-array-editor-state-atom db table-lens reverse? attribute show-empty-sub-column-prompt?)
;;                                                    header)
;;                                               header))}))

;;                                show-empty-column-prompt? #_(starts-with? table-view-node-id @focused-node-id)

;;                                false

;;                                (fn item-removal-transaction [editor]
;;                                  (common/changes-to-remove-component-tree (common/deref db)
;;                                                                           editor))
;;                                (fn item-commands [editor]
;;                                  (let [range (db-common/value-in db
;;                                                                  editor
;;                                                                  [(stred :attribute)
;;                                                                   ])
;;                                        reverse? (db-common/value db
;;                                                                  editor
;;                                                                  (stred :reverse?))]

;;                                    [{:name "toggle reverse"
;;                                      :available? (do (prn 'range range) ;; TODO: remove me
;;                                                      (or (nil? range)
;;                                                          (= range (prelude :entity))
;;                                                          (= range (prelude :type-type))))
;;                                      :key-patterns [[#{:control} :r]]
;;                                      :run! (fn [_subtree]
;;                                              (transact! db [[:set editor (stred :reverse?) (not reverse?)]]))}]))))])

(defn- value-entities [reverse? db attribute entity]
  (if reverse?
    (concat (common/entities db attribute entity)
            (common/entities-referring-with-sequence db entity))
    (common/values db entity attribute)))

(defn- table-view-row-prompt  [value-entities state-atom table-view-node-id db reverse? attribute entity]
  {:local-id :adding-prompt
   :node [prompt-2
          (fn commands [query-text]
            (let [focus-on-new-entity (fn [new-value-entity]
                                        (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                             (let [new-selected-index (->> (conj value-entities new-value-entity)
                                                                                                           (sort-entity-ids)
                                                                                                           medley/indexed
                                                                                                           (medley/find-first #(= new-value-entity (second %)))
                                                                                                           first)]
                                                                               (->> (scene-graph/find-first-breath-first #(= table-view-node-id
                                                                                                                             (:id %))
                                                                                                                         scene-graph)
                                                                                    (scene-graph/find-first-breath-first #(= [:value new-selected-index]
                                                                                                                             (:local-id %)))
                                                                                    (scene-graph/find-first-breath-first :can-gain-focus?)
                                                                                    (keyboard/set-focused-node!))))))
                  #_(fn [new-value-entity]
                      (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                           (->> (scene-graph/find-first-breath-first #(= table-view-node-id
                                                                                                         (:id %))
                                                                                                     scene-graph)
                                                                (scene-graph/find-first-breath-first #(= [:value new-value-entity]
                                                                                                         (:local-id %)))
                                                                (scene-graph/find-first-breath-first :can-gain-focus?)
                                                                (keyboard/set-focused-node!)))))]
              (concat (for [new-value-entity (distinct (search-entities db
                                                                        query-text))]
                        {:view (value-view db new-value-entity)
                         :available? true
                         :run! (fn [_subtree]
                                 (when (not (contains? (hash-set value-entities)
                                                       new-value-entity))
                                   (transact! db [(if reverse?
                                                    [:add new-value-entity attribute entity]
                                                    [:add entity attribute new-value-entity])])
                                   (focus-on-new-entity new-value-entity)
                                   )
                                 (swap! state-atom assoc :adding? false))})

                      [{:name "Create new entity"
                        :available? true
                        :key-patterns  [[#{:control} :c] [#{:control} :c]]
                        :run! (fn [_subtree]
                                (let [temporary-id-resolution (:temporary-id-resolution (transact! db (concat [[:add :tmp/new-entity (prelude :label) query-text]]
                                                                                                              (if reverse?
                                                                                                                [[:add :tmp/new-entity attribute entity]]
                                                                                                                [[:add entity attribute :tmp/new-entity]]))))

                                      new-value-entity (get temporary-id-resolution :tmp/new-entity)]
                                  (focus-on-new-entity new-value-entity)
                                  (swap! state-atom assoc :adding? false)))}])))]})

(defn table-view-row-value-cell-keyboard-event-handler [state-atom index _subtree event]
  (cond (and (= :descent (:phase event))
             (= 1 (:target-depth event))
             (= :focus-gained (:type event)))
        (swap! state-atom assoc :selected-index index)


        (and (= :descent (:phase event))
             (= 1 (:target-depth event))
             (= :focus-lost (:type event)))
        (swap! state-atom dissoc :selected-index))

  event)

(defn- table-view-value-row [index state db value-entity lens-map shared-lens add-lens state-atom entity attribute reverse? table-lens column-insertion-index]
  (concat [(highlight-2 (= index (:selected-index state))
                        {:mouse-event-handler [focus-on-click-mouse-event-handler]
                         :command-set table-cell-command-set
                         :local-id [:value index]
                         :entity value-entity
                         :keyboard-event-handler [table-view-row-value-cell-keyboard-event-handler
                                                  state-atom
                                                  index]
                         :node (layouts/wrap [outline-view
                                              db
                                              value-entity
                                              (or (get lens-map
                                                       value-entity)
                                                  shared-lens)
                                              {:add-lens (fn []
                                                           (add-lens value-entity))}])})]
          (apply concat
                 (for [[index editor] (map-indexed vector (db-common/value db table-lens (stred :editors)))]
                   #_(text  (pr-str editor (db-common/value db editor (stred :attribute))) #_(db-common/values db
                                                                                                               value-entity
                                                                                                               (db-common/value db editor (stred :attribute))))
                   #_(ver 0 (for [cell-value (db-common/values db
                                                               value-entity
                                                               (db-common/value db editor (stred :attribute)))]
                              (value-view db cell-value)))

                   (let [attribute (db-common/value db
                                                    editor
                                                    (stred :attribute))
                         reverse? (db-common/value db
                                                   editor
                                                   (stred :reverse?))
                         lens-map (db-common/value db
                                                   editor
                                                   (stred :lens-map))
                         shared-lens (db-common/value db
                                                      editor
                                                      (stred :value-lens))
                         value (db-common/value db
                                                value-entity
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
                                                      (prelude :range)))

                         entity-attribute-editor-call [entity-attribute-editor
                                                       db
                                                       value-entity
                                                       attribute
                                                       lens-map
                                                       {:reverse? reverse?
                                                        :shared-lens shared-lens
                                                        :add-lens (fn [value]
                                                                    ;; TODO: remove nonexisting values from lens map
                                                                    (transact! db [[:set editor (stred :lens-map)
                                                                                    (assoc lens-map value :tmp/new-lens)]]))}]
                         editor { ;; :can-gain-focus? true
                                 :command-set table-cell-command-set
                                 :node (layouts/wrap (if range
                                                       (condp = range
                                                         (prelude :text)
                                                         [text-attribute-editor
                                                          db
                                                          value-entity
                                                          (db-common/value db
                                                                           editor
                                                                           (stred :attribute))]

                                                         (prelude :entity)
                                                         entity-attribute-editor-call

                                                         (prelude :type-type) ;; TODO make type-type a subtype of entity
                                                         entity-attribute-editor-call

                                                         (prelude :array)
                                                         (ver 0
                                                              (text "[")
                                                              [entity-array-attribute-editor-2
                                                               db
                                                               value-entity
                                                               attribute
                                                               lens-map
                                                               {:shared-lens shared-lens
                                                                :add-lens (fn [value]
                                                                            (transact! db [[:set editor (stred :lens-map)
                                                                                            (assoc lens-map value :tmp/new-lens)]]))}]
                                                              (text "]"))

                                                         (text (str (pr-str editor)
                                                                    " "
                                                                    (pr-str (db-common/value db
                                                                                             editor
                                                                                             (prelude :type-attribute))))))

                                                       [empty-attribute-prompt
                                                        db
                                                        value-entity
                                                        attribute
                                                        reverse?]))}]
                     (if (= index column-insertion-index)
                       [(text  "")
                        editor]
                       [editor]))))))

(defn table-view [_db _entity _attribute _lens-map & _options]
  (let [column-array-editor-state-atom (dependable-atom/atom "column-array-editor-state"
                                                             {})
        state-atom (dependable-atom/atom {:page 0})]
    (fn [db entity attribute lens-map & [{:keys [reverse? add-lens shared-lens table-lens]}]]
      (let [value-entities (sort-entity-ids (value-entities reverse? db attribute entity))
            table-view-node-id view-compiler/id
            state @state-atom
            page-size 30]
        (ver 0
             ;; (text (pr-str @keyboard/focused-node-id-derivation))
             (assoc-last :command-set (table-view-command-set state-atom db entity attribute value-entities reverse?)
                         (hierarchical-table/hierarchical-table #_layouts/grid
                                                                (if (or (:show-empty-column-prompt? state)
                                                                        (not (empty? (db-common/value db
                                                                                                      table-lens
                                                                                                      (stred :editors)))))
                                                                  (concat [[(text "")]
                                                                           #_(text (pr-str (:selected-index state)))]
                                                                          (table-view-header-row state-atom value-entities column-array-editor-state-atom db table-lens true))
                                                                  [[{:width 0
                                                                     :height 0}]])
                                                                (doall (concat (when (or (empty? value-entities)
                                                                                         (:adding? @state-atom))
                                                                                 [[(table-view-row-prompt value-entities state-atom table-view-node-id db reverse? attribute entity)]])

                                                                               (map-indexed (fn [index value-entity]
                                                                                              (table-view-value-row index state db value-entity lens-map shared-lens add-lens state-atom entity attribute reverse? table-lens (:insertion-index @column-array-editor-state-atom)))
                                                                                            (take page-size
                                                                                                  (drop (* page-size
                                                                                                           (:page state))
                                                                                                        value-entities))))))))))))

(defn table-demo []
  (let [db (create-dependable-stream-db-in-memory "base" index-definitions)
        temporary-id-resolution (:temporary-id-resolution (transact! db (concat (mapcat map-to-transaction/map-to-statements
                                                                                        [{:dali/id         :tmp/depends-on
                                                                                          (prelude :label) "depends on"}
                                                                                         {:dali/id         :tmp/editor
                                                                                          (stred :lens-map) {}
                                                                                          (stred :table-lens) :tmp/table-lens}
                                                                                         {:dali/id         :tmp/system-1
                                                                                          (prelude :label) "System 1"
                                                                                          :tmp/depends-on  #{{:dali/id         :tmp/system-2
                                                                                                              (prelude :label) "System 2"
                                                                                                              :tmp/depends-on  :tmp/system-4}
                                                                                                             {:dali/id         :tmp/system-3
                                                                                                              (prelude :label) "System 3"
                                                                                                              :tmp/depends-on  :tmp/system-4}
                                                                                                             {:dali/id         :tmp/system-4
                                                                                                              (prelude :label) "System 4"}}}])
                                                                                stred-transaction
                                                                                prelude-transaction)))
        root-view-state-atom (dependable-atom/atom {})]
    #_(common/value db
                    (:tmp/system-1 temporary-id-resolution)
                    (prelude :label))
    (fn []
      (let [lens-map (db-common/value db
                                      (:tmp/editor temporary-id-resolution)
                                      (stred :lens-map))]
        [command-handler
         (:show-help? @root-view-state-atom)
         {:command-set (root-view-command-set root-view-state-atom)
          :node [table-view
                 db
                 (:tmp/system-1 temporary-id-resolution)
                 (:tmp/depends-on temporary-id-resolution)
                 lens-map
                 {:add-lens (fn [value]
                              (transact! db [[:set
                                              (:tmp/editor temporary-id-resolution)
                                              (stred :lens-map)
                                              (assoc lens-map value :tmp/new-lens)]]))
                  :table-lens  (db-common/value db
                                                (:tmp/editor temporary-id-resolution)
                                                (stred :table-lens)) }]}]))))




;; (defn text [string]
;;   {:text string
;;    :width (count string)
;;    :height 10})

;; (defn multiplication-table []
;;   (box (layouts/with-margins 20 20 20 20
;;          (layouts/grid (for [y (range 1 11)]
;;                          (for [x (range 1 11)]
;;                            (box (layouts/with-margins 10 10 10 10
;;                                   (text (str x "*" y "=" (* x y))
;;                                         {:color [0 0 0 255]}))
;;                                 {:fill-color [255 255 255 255]
;;                                  :line-width 5
;;                                  :draw-color (if (= x y)
;;                                                [200 200 200 255]
;;                                                [255 255 255 255])})))))
;;        {:fill-color [255 255 255 255]}))

(defn db-demo []
  (let [db (create-dependable-stream-db-in-memory "demo"
                                                  index-definitions)]
    (transact! db [[:add 1 :name "foo"]])
    (println "creating demo")
    (fn []
      (println "rendering demo")
      (text (db-common/value db 1 :name)))))

(defn start []
  (println "\n\n------------ start -------------\n\n")
  (with-bindings {#'global-state-atom (dependable-atom/atom {})}
    (reset! dev/event-channel-atom
            (application/start-application ;; ui
             #'notebook-ui
             ;;#'state-demo
             ;; #'hierarchical-table/demo
             ;; #'db-demo
             ;; #'multiplication-table
             ;; #'grid-demo
             ;; #'z-order-demo
             ;; #'split-demo
             ;; #'performance-test-root
             ;; #'image-cache-test-root
             ;; adapt-to-space-test-root
             ;; #'dynamic-scope-demo
             ;; #'table-demo
             :on-exit #(reset! dev/event-channel-atom nil)))

    ;; (doseq [event [{:type :resize-requested, :width 2000, :height 2000}
    ;;                {:type :resize-requested, :width 3780.0, :height 3278.0}
    ;;                {:key-code 88, :alt? false, :key :x, :meta? false, :control? false, :time 1742352467633, :type :key-pressed, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \x}
    ;;                {:key-code 0, :alt? false, :key :undefined, :meta? false, :control? false, :time 1742352467633, :type :key-typed, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \x}
    ;;                {:key-code 88, :alt? false, :key :x, :meta? false, :control? false, :time 1742352467719, :type :key-released, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \x}
    ;;                {:key-code 88, :alt? false, :key :x, :meta? false, :control? false, :time 1742352469608, :type :key-pressed, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \x}
    ;;                {:key-code 0, :alt? false, :key :undefined, :meta? false, :control? false, :time 1742352469608, :type :key-typed, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \x}
    ;;                {:key-code 88, :alt? false, :key :x, :meta? false, :control? false, :time 1742352469722, :type :key-released, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \x}
    ;;                {:key-code 88, :alt? false, :key :x, :meta? false, :control? false, :time 1742352471121, :type :key-pressed, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \x}
    ;;                {:key-code 0, :alt? false, :key :undefined, :meta? false, :control? false, :time 1742352471121, :type :key-typed, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \x}
    ;;                {:key-code 88, :alt? false, :key :x, :meta? false, :control? false, :time 1742352471238, :type :key-released, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \x}

    ;;                {:key-code 157, :alt? false, :key :meta, :meta? true, :control? false, :time 1741703367097, :type :key-pressed, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \￿}
    ;;                {:key-code 18, :alt? true, :key :alt, :meta? true, :control? false, :time 1741703367104, :type :key-pressed, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \￿}
    ;;                {:key-code 87, :alt? true, :key :w, :meta? true, :control? false, :time 1741703367290, :type :key-pressed, :source :keyboard, :shift? false, :is-auto-repeat nil, :character \ω}]]
    ;;   (async/>!! @dev/event-channel-atom
    ;;              event))
    )

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

(when @dev/event-channel-atom
  (async/>!! @dev/event-channel-atom
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

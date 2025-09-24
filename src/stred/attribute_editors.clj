(ns stred.attribute-editors
  (:require
   [argumentica.db.common :as db-common]
   [flow-gl.gui.keyboard :as keyboard]
   [flow-gl.gui.scene-graph :as scene-graph]
   [fungl.dependable-atom :as dependable-atom]
   [stred.core :as stred]))



(defn unlabeled-array-editor-command-set [state-atom db entity attribute item-removal-transaction new-item-transaction]
  (let [state @state-atom
        array (db-common/value db entity attribute)]
    {:name "array editor"
     :commands [{:name "insert before"
                 :available? (:selected-index state)
                 :key-patterns [[#{:control :shift} :i]]
                 :run! (fn [_subtree])}

                {:name "insert after"
                 :available? (or (:selected-index state)
                                 (empty? array))
                 :key-patterns [[#{:control} :i]]
                 :run! (fn [subtree]
                         (let [insertion-index (if (:selected-index state)
                                                 (inc (:selected-index state))
                                                 0)]
                           (stred/transact! db (concat (:transaction new-item-transaction)
                                                       [[:set
                                                         entity
                                                         attribute
                                                         (stred/insert array
                                                                       insertion-index
                                                                       (:item-id new-item-transaction))]]))

                           (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                (->> (:id subtree)
                                                                     (scene-graph/id-to-local-id-path)
                                                                     (scene-graph/get-in-path scene-graph)
                                                                     (scene-graph/find-first-breath-first #(= [:value insertion-index]
                                                                                                              (:local-id %)))
                                                                     (keyboard/set-focused-node!))))))}

                (stred/drop-selection-anchor-array-editor-command state state-atom)
                (stred/reaise-selection-anchor-array-editor-command state state-atom)

                (stred/delete-selected-array-editor-command state array db item-removal-transaction entity attribute state-atom)

                (stred/move-backward-array-editor-command state db entity attribute array state-atom)
                (stred/move-forward-array-editor-command state array db entity attribute state-atom)]}))

(defn unlabeled-array-editor [_db _entity _attribute _item-view _new-item-transaction _item-removal-transaction]
  (let [state-atom (dependable-atom/atom "unlabeled-array-editor-state"
                                         {})]
    (fn unlabeled-array-editor [db entity attribute item-view new-item-transaction item-removal-transaction]
      (let [state @state-atom
            array (db-common/value db entity attribute)]
        {:node (apply stred/ver
                      0
                      (if (empty? array)
                        [[stred/focus-highlight {:node (stred/text "+")
                                                 :can-gain-focus? true}]]

                        (map-indexed (fn [index value-entity]
                                       #_(stred/text (pr-str value-entity))
                                       #_{:node [item-view db value-entity]
                                          :local-id [:value index]
                                          :mouse-event-handler [stred/focus-on-click-mouse-event-handler]
                                          :can-gain-focus? true
                                          :array-value true
                                          :keyboard-event-handler [stred/array-editor-item-view-keyboard-event-handler
                                                                   state-atom
                                                                   index]}
                                       (stred/highlight-3 (cond (= index (:selected-index state))
                                                                  (:highlighted-background-color stred/theme)

                                                                  (and (:anchor-index state)
                                                                       (or (and (>= index
                                                                                    (:anchor-index state))
                                                                                (< index
                                                                                   (:selected-index state)))
                                                                           (and (<= index
                                                                                    (:anchor-index state))
                                                                                (> index
                                                                                   (:selected-index state)))))
                                                                  (:selection-background-color stred/theme)

                                                                  :else
                                                                  [0 0 0 0]
                                                                  ;;(:background-color theme)
                                                                  )
                                                            {:node [item-view db value-entity]
                                                             :local-id [:value index]
                                                             :mouse-event-handler [stred/focus-on-click-mouse-event-handler]
                                                             :can-gain-focus? true
                                                             :array-value true
                                                             :keyboard-event-handler [stred/array-editor-item-view-keyboard-event-handler
                                                                                      state-atom
                                                                                      index]}))
                                     array)))
         :command-set (unlabeled-array-editor-command-set state-atom
                                                          db
                                                          entity
                                                          attribute
                                                          item-removal-transaction
                                                          new-item-transaction)}))))

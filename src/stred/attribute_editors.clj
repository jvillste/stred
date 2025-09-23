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
     :commands (concat [{:name "insert before"
                         :available? (:selected-index state)
                         :key-patterns [[#{:control :shift} :i]]
                         :run! (fn [_subtree])}

                        {:name "insert after"
                         :available? (:selected-index state)
                         :key-patterns [[#{:control} :i]]
                         :run! (fn [subtree]
                                 (let [{:keys [item-id transaction]} new-item-transaction]
                                   (stred/transact! db (concat transaction
                                                               [[:set entity attribute (stred/insert array (:selected-index state) item-id)]])))
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
                                                                           (keyboard/set-focused-node!)))))
                         #_(fn [subtree]
                             (swap! state-atom
                                    (fn [state]
                                      (-> state
                                          (assoc :insertion-index (inc (:selected-index state)))
                                          (dissoc :selected-index))))
                             (keyboard/handle-next-scene-graph! (fn [scene-graph]
                                                                  (focus-insertion-prompt scene-graph
                                                                                          (drop-last (:id subtree))))))}

                        (stred/drop-selection-anchor-array-editor-command state state-atom)
                        (stred/reaise-selection-anchor-array-editor-command state state-atom)

                        (stred/delete-selected-array-editor-command state array db item-removal-transaction entity attribute state-atom)

                        (stred/move-backward-array-editor-command state db entity attribute array state-atom)
                        (stred/move-forward-array-editor-command state array db entity attribute state-atom)])}))

(defn unlabeled-array-editor [db entity attribute new-item-transaction item-view item-removal-transaction item-commands]
  (let [state-atom (dependable-atom/atom "array-editor-state"
                                         {})]
    (fn []
      (let [state @state-atom
            array (db-common/value db entity attribute)]
        (if (empty? array)
          [[stred/focus-highlight {:node (stred/text "+")
                                   :can-gain-focus? true}]]
          (apply concat
                 (map-indexed (fn [index value-entity]
                                (assoc (stred/highlight-3 (cond (= index (:selected-index state))
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
                                                                                    index]})
                                       :command-set (unlabeled-array-editor-command-set state-atom
                                                                                        db
                                                                                        entity
                                                                                        attribute
                                                                                        item-removal-transaction
                                                                                        item-commands
                                                                                        new-item-transaction)))
                              array)))))))

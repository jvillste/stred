(ns stred.hierarchical-table
  (:require
   [clojure.test :refer :all]
   [flow-gl.graphics.font :as font]
   [flow-gl.gui.visuals :as visuals]
   [fungl.component.text-area :as text-area]
   [fungl.layout :as layout]
   [fungl.layouts :as layouts]
   [fungl.util :as util]
   [medley.core :as medley]))

(defn immediate-child? [parent-path child-path]
  (and (util/starts-with? parent-path child-path)
       (= (count parent-path)
          (dec (count child-path)))))

(defn header-size [header headers column-widths-by-column-index]
  (let [child-sizes (map (fn [child-header]
                           (header-size child-header headers column-widths-by-column-index))
                         (filter (fn [child-candidate]
                                   (immediate-child? (::path header)
                                                     (::path child-candidate)))
                                 headers))]
    {:width (max (:width header)
                 (+ (get column-widths-by-column-index
                         (::column header))
                    (reduce + (map :width child-sizes))))
     :height (+ (:height header)
                (apply max (conj (map :height child-sizes)
                                 0)))}))

(deftest test-header-size
  (is (= {:width 11, :height 20}
         (header-size {:text "header 1",
                       :width 8,
                       :height 10,
                       :stred.hierarchical-table/path [0],
                       :stred.hierarchical-table/column 0}
                      '({:text "header 1",
                         :width 8,
                         :height 10,
                         :stred.hierarchical-table/path [0],
                         :stred.hierarchical-table/column 0}
                        {:text "header 1 1",
                         :width 10,
                         :height 10,
                         :stred.hierarchical-table/path [0 1],
                         :stred.hierarchical-table/column 1}
                        {:text "header 2",
                         :width 8,
                         :height 10,
                         :stred.hierarchical-table/path [1],
                         :stred.hierarchical-table/column 2})
                      {0 1
                       1 2
                       2 3}))))

(defn- column-widths-by-column-index [nodes]
  (->> nodes
       (group-by ::column)
       (medley/map-vals (fn [column]
                          (apply max (map :width column))))))

(defn- hierarchical-table-get-size [hierachical-table-node]
  (let [[header-nodes row-nodes] (partition-by (fn [node]
                                                 (some? (::path node)))
                                               (:children hierachical-table-node))
        column-widths-by-column-index (column-widths-by-column-index row-nodes)
        root-header-sizes (map (fn [header]
                                 (header-size header header-nodes column-widths-by-column-index))
                               header-nodes)]
    {:width (reduce + (map :width root-header-sizes))
     :height (+ (apply max (map :height root-header-sizes))
                (->> (group-by ::row row-nodes)
                     (vals)
                     (map (fn [row-nodes]
                            (apply max (map :height row-nodes))))
                     (reduce +)))}))

(deftest test-hierarchical-table-get-size
  (is (= {:width 35, :height 30}
         (hierarchical-table-get-size '{:children
                                        ({:text "header 1",
                                          :width 8,
                                          :height 10,
                                          :stred.hierarchical-table/path [0],
                                          :stred.hierarchical-table/column 0}
                                         {:text "header 1 1",
                                          :width 10,
                                          :height 10,
                                          :stred.hierarchical-table/path [0 1],
                                          :stred.hierarchical-table/column 1}
                                         {:text "header 2",
                                          :width 8,
                                          :height 10,
                                          :stred.hierarchical-table/path [1],
                                          :stred.hierarchical-table/column 2}

                                         {:text "value 1",
                                          :width 7,
                                          :height 10,
                                          ::column 0,
                                          ::row 0}
                                         {:text "value 1 1",
                                          :width 9,
                                          :height 10,
                                          ::column 1,
                                          ::row 0}
                                         {:text "value 2",
                                          :width 7,
                                          :height 10,
                                          ::column 2,
                                          ::row 0})}))))

(defn- hierarchical-table-give-space [node]
  (update node
          :children
          (fn [children]
            (->> children
                 (map (fn [child]
                        (assoc child
                               :available-width java.lang.Integer/MAX_VALUE
                               :available-height java.lang.Integer/MAX_VALUE)))))))

(defn layout-rows [min-y column-widths-by-column-index nodes]
  (let [rows (partition-by ::row nodes)]
    (loop [layouted-nodes []
           x 0
           y min-y
           cells (first rows)
           rows (rest rows)
           max-height 0]
      (if-let [cell (first cells)]
        (recur (conj layouted-nodes
                     (layout/do-layout (assoc cell
                                              :x x
                                              :y y
                                              :available-width (get column-widths-by-column-index (::column cell)))))
               (+ x (get column-widths-by-column-index (::column cell)))
               y
               (rest cells)
               rows
               (max max-height (:height cell)))
        (if (not (empty? rows))
          (recur layouted-nodes
                 0
                 (+ y max-height)
                 (first rows)
                 (rest rows)
                 0)
          layouted-nodes)))))

(deftest test-layout-rows
  (is (= [{:text "value 1",
           :width 7,
           :height 10,
           :stred.hierarchical-table/column 0,
           :stred.hierarchical-table/row 0,
           :x 0,
           :y 10}
          {:text "value 1 1",
           :width 9,
           :height 10,
           :stred.hierarchical-table/column 1,
           :stred.hierarchical-table/row 0,
           :x 1,
           :y 10}
          {:text "value 2",
           :width 7,
           :height 10,
           :stred.hierarchical-table/column 2,
           :stred.hierarchical-table/row 0,
           :x 3,
           :y 10}]
         (layout-rows 10
                      {0 1
                       1 2
                       2 3}
                      [{:text "value 1",
                        :width 7,
                        :height 10,
                        ::column 0,
                        ::row 0}
                       {:text "value 1 1",
                        :width 9,
                        :height 10,
                        ::column 1,
                        ::row 0}
                       {:text "value 2",
                        :width 7,
                        :height 10,
                        ::column 2,
                        ::row 0}]))))

(defn compare-paths [path-a path-b]
  (cond (= path-a path-b)
        0

        (empty? path-a)
        -1

        (empty? path-b)
        1

        (= (first path-a)
           (first path-b))
        (compare-paths (rest path-a)
                       (rest path-b))

        :else
        (compare (first path-a)
                 (first path-b))))

(deftest test-compare-paths
  (is (= 0 (compare-paths [] [])))
  (is (= 1 (compare-paths [1] [])))
  (is (= 1 (compare-paths [1] [0 1])))
  (is (= '([] [0] [0 1] [1])
         (sort compare-paths [[] [0] [0 1] [1]]))))

(defn sort-headers [headers]
  (sort-by ::path compare-paths headers))

(defn header-row-heights [header-nodes]
  (map (fn [header-nodes-in-row]
         (apply max (map :height header-nodes-in-row)))
       (partition-by (fn [header]
                       (count (::path header)))
                     (sort-by (fn [header]
                                (count (::path header)))
                              header-nodes))))

(deftest test-header-row-heights
  (is (= '(10 11)
         (header-row-heights [{:height 10,
                               :stred.hierarchical-table/path [0]}
                              {:height 11,
                               :stred.hierarchical-table/path [0 1]}
                              {:height 10,
                               :stred.hierarchical-table/path [1]}]))))

(defn header-row-y [header-row-heights row-number]
  (reduce + (take row-number header-row-heights)))

(deftest test-header-row-y
  (is (= 0 (header-row-y [1 2 3] -1)))
  (is (= 0 (header-row-y [1 2 3] 0)))
  (is (= 3 (header-row-y [1 2 3] 2))))

(defn layout-headers [header-row-heights column-widths-by-column-index headers]
  (letfn [(layout-header [x header children]
            (let [column-width (get column-widths-by-column-index
                                    (::column header)
                                    0)
                  child-branches (if (empty? children)
                                   []
                                   (loop [branches []
                                          current-branch []
                                          remaining-children children]
                                     (if (empty? remaining-children)
                                       (conj branches current-branch)
                                       (let [child (first remaining-children)]
                                         (if (immediate-child? (::path header)
                                                               (::path child))
                                           (recur (if (empty? current-branch)
                                                    branches
                                                    (conj branches current-branch))
                                                  [child]
                                                  (rest remaining-children))
                                           (recur branches
                                                  (conj current-branch child)
                                                  (rest remaining-children)))))))

                  layouted-child-branches (loop [x column-width
                                                 remaining-child-branches child-branches
                                                 layouted-child-branches []]
                                            (if (empty? remaining-child-branches)
                                              layouted-child-branches
                                              (let [child-branch (first remaining-child-branches)
                                                    layouted-child-branch (layout-header x
                                                                                         (first child-branch)
                                                                                         (rest child-branch))]

                                                (recur (+ x
                                                          (::header-width (first layouted-child-branch))
                                                          (:x (first layouted-child-branch)))
                                                       (rest remaining-child-branches)
                                                       (conj layouted-child-branches
                                                             layouted-child-branch)))))
                  width (if (empty? layouted-child-branches)
                          (max (:width header)
                               column-width)
                          (apply +
                                 column-width
                                 (map ::header-width (map first layouted-child-branches))))]

              (concat [(layout/do-layout (assoc header
                                                :x x
                                                :y (header-row-y header-row-heights
                                                                 (dec (count (::path header))))
                                                ::has-children? (not (empty? layouted-child-branches))
                                                :available-width width
                                                ::header-width width
                                                ))]
                      (apply concat layouted-child-branches))))]
    (rest (layout-header 0
                         {:width 0
                          :height 0
                          ::path [],
                          ::column -1}
                         (sort-headers headers)))))

(deftest test-layout-headers
  (is (= '({:y 0,
            :stred.hierarchical-table/has-children? false,
            :width-was-given true,
            :children nil,
            :stred.hierarchical-table/column 0,
            :stred.hierarchical-table/path [0],
            :available-width 8,
            :width 8,
            :height-was-given true,
            :stred.hierarchical-table/header-width 8,
            :x 0,
            :height 10,
            :text "header 1"})
         (layout-headers {0 10}
                         {0 1}
                         [{:text "header 1",
                           :width 8,
                           :height 10,
                           ::path [0],
                           ::column 0}])))

  (is (= '({:y 0,
            :stred.hierarchical-table/has-children? true,
            :width-was-given true,
            :children nil,
            :stred.hierarchical-table/column 0,
            :stred.hierarchical-table/path [0],
            :available-width 11,
            :width 8,
            :height-was-given true,
            :stred.hierarchical-table/header-width 11,
            :x 0,
            :height 10,
            :text "header 1"}
           {:y [0 10],
            :stred.hierarchical-table/has-children? false,
            :width-was-given true,
            :children nil,
            :stred.hierarchical-table/column 1,
            :stred.hierarchical-table/path [0 1],
            :available-width 10,
            :width 10,
            :height-was-given true,
            :stred.hierarchical-table/header-width 10,
            :x 1,
            :height 10,
            :text "header 1 1"}
           {:y 0,
            :stred.hierarchical-table/has-children? false,
            :width-was-given true,
            :children nil,
            :stred.hierarchical-table/column 2,
            :stred.hierarchical-table/path [1],
            :available-width 8,
            :width 8,
            :height-was-given true,
            :stred.hierarchical-table/header-width 8,
            :x 11,
            :height 10,
            :text "header 2"})
         (layout-headers {0 10
                          1 10
                          2 10}
                         {0 1
                          1 2
                          2 3}
                         [{:text "header 1",
                           :width 8,
                           :height 10,
                           ::path [0],
                           ::column 0}
                          {:text "header 1 1",
                           :width 10,
                           :height 10,
                           ::path [0 1],
                           ::column 1}
                          {:text "header 2",
                           :width 8,
                           :height 10,
                           ::path [1],
                           ::column 2}]))))

(defn column-widths-from-headers [headers]
  (->> headers
       (remove ::has-children?)
       (map (fn [header]
              [(::column header)
               (::header-width header)]))
       (into {})))

(deftest test-column-widths-from-headers
  (is (= {1 10, 2 8}
         (column-widths-from-headers '({::header-width 11,
                                        ::column 0
                                        ::has-children? true}
                                       {::header-width 10,
                                        ::column 1
                                        ::has-children? false}
                                       {::header-width 8,
                                        ::column 2
                                        ::has-children? false})))))

(defn- hierarchical-table-make-layout [node]
  (assoc node :children
         (let [[header-nodes row-nodes] (partition-by (fn [node]
                                                        (some? (::path node)))
                                                      (:children node))
               header-row-heights (header-row-heights header-nodes)
               column-widths-by-column-index (column-widths-by-column-index row-nodes)
               layouted-headers (layout-headers header-row-heights
                                                column-widths-by-column-index
                                                header-nodes)]
           (concat layouted-headers
                   (layout-rows (reduce + header-row-heights)
                                (merge-with max
                                            column-widths-by-column-index
                                            (column-widths-from-headers layouted-headers))
                                row-nodes)))))

(defn- flatten-header-hierarchy [path column [header & sub-headers]]
  (apply concat
         [(-> header
              (assoc ::path path)
              (assoc ::column column))]
         (map-indexed (fn [index sub-header]
                        (flatten-header-hierarchy (conj path (inc index))
                                                  (+ (inc index) column)
                                                  sub-header))
                      sub-headers)))

(defn- flatten-headers [headers]
  (loop [column 0
         index 0
         headers headers
         flat-headers []]
    (if (empty? headers)
      flat-headers
      (let [flattened-header-hierarchy (flatten-header-hierarchy [index] column (first headers))]
        (recur (inc (apply max (remove nil? (map ::column flattened-header-hierarchy))))
               (inc index)
               (rest headers)
               (concat flat-headers flattened-header-hierarchy))))))

(deftest test-flatten-headers
  (is (= '({:text "header 1",
            :width 8,
            :height 10,
            :stred.hierarchical-table/path [0],
            :stred.hierarchical-table/column 0}
           {:text "header 1 1",
            :width 10,
            :height 10,
            :stred.hierarchical-table/path [0 1],
            :stred.hierarchical-table/column 1}
           {:text "header 2",
            :width 8,
            :height 10,
            :stred.hierarchical-table/path [1],
            :stred.hierarchical-table/column 2})
         (flatten-headers [[{:text "header 1", :width 8, :height 10}
                            [{:text "header 1 1", :width 10, :height 10}]]
                           [{:text "header 2", :width 8, :height 10}]]))))

(defn flatten-rows [rows]
  (apply concat
         (map-indexed (fn [row-number row]
                        (map-indexed (fn [column-number cell]
                                       (assoc cell
                                              ::column column-number
                                              ::row row-number))
                                     row))
                      rows)))

(deftest test-flatten-rows
  (is (= '({:title "cell 0 0", ::column 0, ::row 0}
           {:title "cell 0 1", ::column 1, ::row 0}
           {:title "cell 1 0", ::column 0, ::row 1}
           {:title "cell 1 1", ::column 1, ::row 1})
         (flatten-rows [[{:title "cell 0 0"}
                         {:title "cell 0 1"}]
                        [{:title "cell 1 0"}
                         {:title "cell 1 1"}]]))))

(defn hierarchical-table [headers rows]
  {:type ::hierarchical-table
   :children (concat (flatten-headers headers)
                     (flatten-rows rows))
   :get-size hierarchical-table-get-size
   :give-space hierarchical-table-give-space
   :make-layout hierarchical-table-make-layout})


;; DEMO


(def font (font/create-by-name "CourierNewPSMT" 30))

(defn- text [string]
  (text-area/text (str string)
                  [255 255 255 255]
                  font))

(defn header [label]
  (layouts/with-margin 5
    (layouts/vertically-2 {:margin  0}
                          (text label)
                          (assoc (visuals/rectangle-2 {:fill-color [150 150 255 255]})
                                 :height 10))))

(defn cell [string]
  (layouts/with-margin 5
    (layouts/box 5
                 (visuals/rectangle-2 :fill-color [150 150 255 155]
                                      :corner-arc-radius 40)
                 (text string)
                 {:fill-width? true})))

(defn demo []
    (hierarchical-table [[(header "header 1")
                        [(header "long header 1.1")]]
                       [(header "header 2")]]

                      [[(cell "value 1")
                        (cell "value 1.1")
                        (cell "value 2")]]))

(ns stred.dev
  (:require [clojure.tools.namespace.repl :as repl]))

(repl/disable-unload!)

(defonce event-channel-atom (atom nil))

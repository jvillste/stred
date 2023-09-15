(defproject stred "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [flow-gl "1.0.0-SNAPSHOT"]
                 [dali "0.1.0-SNAPSHOT"]
                 [com.clojure-goes-fast/clj-async-profiler "1.0.4"]]
  :repl-options {:init-ns stred.core}
  :jvm-opts ["-Djdk.attach.allowAttachSelf"] ;; for clj-async-profiler
  )

(ns clj-chrome-devtools.automation
  "Higher level automation convenience API"
  (:require [clj-chrome-devtools.commands.dom :as dom]
            [clj-chrome-devtools.commands.domsnapshot :as dom-snapshot]
            [clj-chrome-devtools.commands.page :as page]
            [clj-chrome-devtools.commands.input :as input]
            [clj-chrome-devtools.commands.runtime :as runtime]
            [clj-chrome-devtools.commands.network :as network]
            [clj-chrome-devtools.events :as events]
            [clj-chrome-devtools.impl.connection :as connection]
            [clojure.core.async :as async :refer [go-loop go <!! <!]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Automation context wraps a low-level CDP connection with state handling
(defrecord Automation [connection root])

(defonce current-automation (atom nil))

(def ^:dynamic *wait-ms* {:element 4000
                          :navigate 30000
                          :render 500})

(defmacro wait
  ([wait-category result-not body]
   `(wait ~wait-category ~result-not ::return-unwanted-result ~body))
  ([wait-category result-not on-failure body]
   `(let [wait-until# (+ (System/currentTimeMillis) (~wait-category *wait-ms*))
          unwanted# ~result-not
          on-failure# ~on-failure
          evaluate# (fn []
                      ~body)]
      (loop [res# (evaluate#)]
        (cond
          ;; If result is not the unwanted one return the result
          (not= res# unwanted#)
          res#

          ;; Waited long enough without successfull response
          (> (System/currentTimeMillis) wait-until#)
          (if (= ::return-unwanted-result on-failure#)
            res#
            on-failure#)

          ;; Otherwise recur and wait more
          :default
          (do (Thread/sleep 100)
              (recur (evaluate#))))))))

(defn create-automation [connection]
  (let [root-atom (atom nil)
        ch (events/listen connection :page :frame-stopped-loading)]
    (go-loop [v (<! ch)]
      (when v
        (let [root (:root (dom/get-document connection {}))]
          (println "Document updated, new root: " root)
          (reset! root-atom root))
        (recur (<! ch))))
    (->Automation connection root-atom)))

(defn start!
  "Start a new CDP connection and an automation context for it.
  Sets the current automation context."
  []
  (reset! current-automation
          (create-automation (connection/connect "localhost" 9222))))

(defn root
  "Returns the root node for id reference."
  ([] (root @current-automation))
  ([{c :connection}]
   {:node-id (-> (dom/get-document c {}) :root :node-id)}))

(defn to
  "Navigate to the given URL. Waits for browser load to be finished before returning."
  ([url]
   (to @current-automation url))
  ([{c :connection r :root} url]
   (reset! r nil)
   (page/enable c {})
   (events/with-event-wait c :page :frame-stopped-loading
     (page/navigate c {:url url}))

   ;; Wait for root element to have been updated
   (wait :navigate nil @r)))


(defn- root-node-id
  ([] (root-node-id @current-automation))
  ([ctx]
   (:node-id @(:root ctx))))

(defn sel
  "Select elements by path. Path is a string."
  ([path] (sel @current-automation path))
  ([{c :connection :as ctx} path]
   (wait :element '()
         (map (fn [id]
                {:node-id id})
              (:node-ids (dom/query-selector-all c {:node-id (root-node-id ctx)
                                                    :selector path}))))))

(defn sel1
  "Select a single element by path."
  ([path] (sel1 @current-automation path))
  ([{c :connection :as ctx} path]
   (wait :element {:node-id 0} nil
         (dom/query-selector c {:node-id (root-node-id ctx)
                                :selector path}))))

(defn bounding-box
  ([node] (bounding-box @current-automation node))
  ([{c :connection :as ctx} node]
   (let [{:keys [width height content]} (:model (dom/get-box-model c node))
         [x y] content]
     {:x x :y y
      :width width :height height
      :center [(Math/round (double (+ x (/ width 2))))
               (Math/round (double (+ y (/ height 2))))]})))

(defn evaluate
  ([expression] (evaluate @current-automation expression))
  ([{c :connection :as ctx} expression]
   (->> {:expression expression :return-by-value true}
        (runtime/evaluate c)
        ;; :value only works for simple values
        :result :value)))

(comment
  (defn scroll-visible
    "Make the given node visible by scrolling to it. Returns the bounding box in
  screen coordinates (NOT page).
  Tries to center the viewport around the element."
    ([node] (scroll-visible @current-automation node))
    ([{c :connection :as ctx} node]
     (println "SCROLL-VISIBLE: " node ", resolved: " (dom/resolve-node c node))
     (let [[ww wh] (evaluate ctx "[window.innerHeight, window.innerWidth]")
           [center-x center-y] (:center (bounding-box ctx node))
           scroll-x (int (- center-x (/ ww 2)))
           scroll-y (int (- center-y (/ wh 2)))]
       (println "SCROLL TO " scroll-x ", " scroll-y)
       (evaluate ctx (format "window.scrollTo(%d,%d)" scroll-x scroll-y))
       (let [{:keys [x y width height]} (bounding-box ctx node)
             [sx sy] (evaluate ctx "[window.scrollX, window.scrollY]")
             screen-x (- x sx)
             screen-y (- y sy)]
         (println "scroll at: " sx "," sy)
         {:x screen-x
          :y screen-y
          :center [(Math/round (double (+ screen-x (/ width 2))))
                   (Math/round (double (+ screen-y (/ height 2))))]})))))

(defn- node-object-id [{c :connection} node]
  (->> node (dom/resolve-node c) :object :object-id))

(defn- eval-node [{c :connection :as ctx} node-or-object-id & fn-def-strings]
  (let [object-id (if (map? node-or-object-id)
                    (node-object-id ctx node-or-object-id)
                    node-or-object-id)]
    (-> (runtime/call-function-on
         c {:object-id object-id :return-by-value true
            :function-declaration (str/join " " fn-def-strings)})
        :result :value)))

(defn scroll-into-view
  "Make the given node visible by scrolling to it if necessary.
  Returns the center X/Y of the element."
  ([node] (scroll-into-view @current-automation node))
  ([{c :connection :as ctx} node]
   (let [node-object-id (node-object-id ctx node)
         {:keys [x y] :as ret} (eval-node
                                ctx node
                                "function() { this.scrollIntoViewIfNeeded(); "
                                " var r = this.getBoundingClientRect(); "
                                " return {x: r.left + r.width/2, y: r.top + r.height/2}; "
                                "}")]
     {:x (Math/round (double x))
      :y (Math/round (double y))})))

(defn visible
  "Wait for element to become visible, return true if element is visible, false otherwise."
  ([node] (visible @current-automation node))
  ([{c :connection :as ctx} node]
   (let [object-id (node-object-id ctx node)]
     (wait :render false
           (eval-node ctx object-id
                      "function() {"
                      "  return (this.offsetParent !== null);"
                      "}")))))

(defn click
  ([node] (click @current-automation node))
  ([{c :connection :as ctx} node]
   (visible ctx node)
   (let [{:keys [x y]} (scroll-into-view ctx node)
         event {:x x :y y :button "left" :click-count 1}]
     (input/dispatch-mouse-event c (assoc event :type "mousePressed"))
     (input/dispatch-mouse-event c (assoc event :type "mouseReleased"))

     ;; FIXME: Artificial slowdown, nodes that appear after clicking may be found
     ;; by a selector, but cannot be resolved (aren't sent to us by chrome yet?).
     ;; Implement a "dom" model that has all the nodes and keep resolved nodes
     ;; in the implementation. Always resolve a node from the "dom"
     (Thread/sleep 100))))

(defn click-navigate
  "Click element and wait for navigation to be done."
  ([node] (click-navigate @current-automation node))
  ([{c :connection :as ctx} node]
   (events/with-event-wait c :page :frame-stopped-loading
     (click ctx node))))

(defn html-of
  ([node] (html-of @current-automation node))
  ([{c :connection :as ctx} node]
   (:outer-html (dom/get-outer-html c node))))

(defn text-of
  ([node] (text-of @current-automation node))
  ([{c :connection :as ctx} node]
   (eval-node ctx node "function() { return this.innerText; }")))



(defn file-download
  "Run the given interaction-fn which will interact with the page and cause a file download.
  Monitors network activity to receive a file where the request matches the given URL pattern.
  Returns a map describing the downloaded file and also has the content."
  ([url-pattern interaction-fn]
   (file-download @current-automation url-pattern interaction-fn))
  ([{c :connection :as ctx} url-pattern interaction-fn]
   (network/enable c {})
   (page/enable c {})
   (let [response-ch (events/listen c :network :response-received)
         timeout-ch (async/timeout (:navigate *wait-ms*))
         file-request
         (go-loop [[v ch] (async/alts! [response-ch timeout-ch])]
           (cond
             (= ch timeout-ch)
             nil

             (->> v :params :response :url (re-find url-pattern))
             (:params v)

             :default
             (recur (async/alts! [response-ch timeout-ch]))))]
     (interaction-fn)
     (let [file (<!! file-request)]
       (events/unlisten c :network :response-received response-ch)
       (when file
         ;; PENDING: get-response-body does not work for downloaded files
         (:response file))))))

(defn screenshot
  ([] (screenshot @current-automation))
  ([{c :connection}]
   (let [decode #(.decode (java.util.Base64/getDecoder) %)]
     (-> (page/capture-screenshot c {})
         :data decode
         (java.io.ByteArrayInputStream.)
         (io/copy (io/file "screenshot.png"))))))

(defn set-attribute
  ([node attribute-name attribute-value]
   (set-attribute @current-automation node attribute-name attribute-value))
  ([{c :connection :as ctx} node attribute-name attribute-value]
   (dom/set-attribute-value c (merge node {:name attribute-name
                                           :value attribute-value}))))

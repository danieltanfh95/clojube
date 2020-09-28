#!/usr/bin/env bb -I

(require '[clj-yaml.core :as yaml])
(require '[clojure.string :as cljstr])
(require '[clojure.edn :as edn])
(require '[clojure.java.shell :as shell])

(def k8s-read-write-mode-map {:rw "ReadWriteMany"
                              :ro "ReadOnlyMany"})

(defn uuid [] (subs (str (java.util.UUID/randomUUID)) 0 8))

(defn merge-yaml [yamls] (cljstr/join "\n---\n\n" yamls))

(defn generate-yaml [data] (yaml/generate-string data :dumper-options {:flow-style :block}))

(defn generate-local-volume [name ctnr-path local-path mode]
      (let [v (str name "-" (uuid) "-data")
            pv (str v "-pv")
            pvc (str pv "c")
            access-mode [(k8s-read-write-mode-map mode)]]
           {:key       {:name                  v
                        :persistentVolumeClaim {:claimName pvc}}
            :ctnr-path ctnr-path
            :value     [{:apiVersion "v1"
                         :kind       "PersistentVolume"
                         :metadata   {:name   pv
                                      :labels {:type "local"}}
                         :spec       {:storageClassName "manual"
                                      :capacity         {:storage "1Gi"}
                                      :accessModes      access-mode
                                      :hostPath         {:path local-path}}}
                        {:apiVersion "v1"
                         :kind       "PersistentVolumeClaim"
                         :metadata   {:name pvc}
                         :spec       {:storageClassName "manual"
                                      :accessModes      access-mode
                                      :resources        {:requests {:storage "1Gi"}}}}]}))

(defn generate-service [name ports]
      (let [svc (str name "-svc")]
           {:apiVersion "v1"
            :kind       "Service"
            :metadata   {:name svc}
            :spec       {:selector {:app name}
                         :type     "NodePort"
                         :ports    (->> ports
                                        (filter map?)
                                        (map (fn [{:keys [from to]}] {:protocol "TCP"
                                                                      :port     from
                                                                      :nodePort to})))}}))

(defn generate-container [name image ports volumes]
      (let [ctnr (str "corename")]
           {:name         ctnr
            :image        image
            :ports        (->> ports
                               (map #(if (map? %) (:from %) %))
                               (map (fn [port] {:containerPort port})))
            :volumeMounts (map (fn [{:keys [ctnr-path key]}] {:mountPath ctnr-path
                                                              :name      (:name key)}) volumes)}))

(defn generate-deployment [name replica-number volumes container]
      (let [dep (str name "-deployment")]
           {:apiVersion "apps/v1"
            :kind       "Deployment"
            :metadata   {:name   dep
                         :labels {:app dep}}
            :spec       {:replicas replica-number
                         :selector {:matchLabels {:app name}}
                         :template {:metadata {:labels {:app name}}
                                    :spec     {:imagePullSecrets [{:name "gitlab-registry-key"}]
                                               :volumes          volumes
                                               :containers       [container]}}}}))

(defn generate-daemonset [name replica-number volumes container]
      (let [dep (str name "-daemonset")]
           {:apiVersion "apps/v1"
            :kind       "Daemonset"
            :metadata   {:name   dep
                         :labels {:app dep}}
            :spec       {:selector {:matchLabels {:app name}}
                         :template {:metadata {:labels {:app name}}
                                    :spec     {:imagePullSecrets [{:name "gitlab-registry-key"}]
                                               :volumes          volumes
                                               :containers       [container]}}}}))

(def k8s-app-type-map {:per-machine generate-daemonset
                       :anywhere    generate-deployment})

(defn generate-app [name deployment generated-volumes container]
      (let [generator (k8s-app-type-map (:type deployment))
            number (:number deployment)]
           (generator name number (map :key generated-volumes) container)))

(defn keys-in
      "Returns a sequence of all key paths in a given map using DFS walk."
      [m]
      (letfn [(children [node]
                        (let [v (get-in m node)]
                             (if (map? v)
                               (map (fn [x] (conj node x)) (keys v))
                               [])))
              (branch? [node] (-> (children node) seq boolean))]
             (->> (keys m)
                  (map vector)
                  (mapcat #(tree-seq branch? children %)))))

(defn generate-k8s-config [env-config name]
      (let [generated-volumes (map (fn [{:keys [path host-path mode]}]
                                       (generate-local-volume name path host-path mode)) (:volumes env-config))
            generated-container (generate-container "core"
                                                    (:image env-config)
                                                    (:ports env-config)
                                                    generated-volumes)]
           (->> [(map :value generated-volumes)
                 (generate-app name (:deployment env-config) generated-volumes generated-container)
                 (generate-service name (:ports env-config))]
                (flatten)
                (map generate-yaml)
                (merge-yaml))))

(defn process-app-config [app]
      (shell/sh "mkdir" "-p" "output")
      (let [name (:name app)
            env-configs (dissoc app :name)
            envs (keys env-configs)]
           (as-> env-configs app
                 (loop [app app
                        keys (reverse (sort-by count (keys-in app)))]
                       (if-not (empty? keys)
                               (recur (let [path (first keys)
                                            value (get-in app path)]
                                           (if (some #(= % value) envs)
                                             (assoc-in app path (get-in app (into [value] (rest path))))
                                             app))
                                      (rest keys))
                               app))
                 (map (fn [[env-key env-config]] [env-key (generate-k8s-config env-config name)]) app)
                 (doseq [[env-key env-yaml] app]
                        (spit (str "output/" name "-" (symbol env-key) ".yaml") env-yaml))
                 )))

(process-app-config (first *input*))

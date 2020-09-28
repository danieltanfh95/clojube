#!/usr/bin/env bb

(require '[clj-yaml.core :as yaml]
         '[clojure.string :as cljstr]
         '[clojure.edn :as edn]
         '[clojure.java.shell :as shell]
         '[clojure.java.io :as io])

(def k8s-read-write-mode-map {:rw "ReadWriteMany"
                              :ro "ReadOnlyMany"})

(defn uuid [] (subs (str (java.util.UUID/randomUUID)) 0 8))

(defn merge-yaml [yamls] (cljstr/join "\n---\n\n" yamls))

(defn generate-yaml [data] (yaml/generate-string data :dumper-options {:flow-style :block}))

(defn generate-local-volume [name id ctnr-path local-path mode]
      (let [v (str name (if (some? id) (str "-" id)) "-data")
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
                                      :capacity         {:storage "10Gi"}
                                      :accessModes      access-mode
                                      :hostPath         {:path local-path}}}
                        {:apiVersion "v1"
                         :kind       "PersistentVolumeClaim"
                         :metadata   {:name pvc}
                         :spec       {:storageClassName "manual"
                                      :accessModes      access-mode
                                      :resources        {:requests {:storage "10Gi"}}}}]}))

(defn generate-service [name ports]
      (let [svc (str name "-service")]
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
      (let [ctnr name]
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
                         :labels {:app name}}
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
           (generator name number generated-volumes container)))

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
      (let [generated-volumes (map (fn [{:keys [path host-path mode id]}]
                                       (generate-local-volume name id path host-path mode)) (:volumes env-config))
            generated-container (generate-container name
                                                    (:image env-config)
                                                    (:ports env-config)
                                                    generated-volumes)]
           (->> [(map :value generated-volumes)
                 (generate-app name (:deployment env-config) (map :key generated-volumes) generated-container)
                 (generate-service name (:ports env-config))]
                (flatten)
                (map generate-yaml)
                (merge-yaml)
                (str "#auto-generated YAML files by clojube\n\n"))))

(defn process-app-config [app]
      (let [name (:name app)
            output-folder (:output-folder app)
            env-configs (-> app (dissoc :name) (dissoc :output-folder))
            envs (keys env-configs)]
           (shell/sh "mkdir" "-p" output-folder)
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
                 (map (fn [[env-key env-config]] [env-key (generate-k8s-config env-config (str name "-" (symbol env-key)))]) app)
                 (doseq [[env-key env-yaml] app]
                        (spit (->> (str name "-" (symbol env-key) ".yaml")
                                   (io/file output-folder)
                                   (.getCanonicalFile)
                                   (.toString))
                              env-yaml))
                 (println (str "The files are created in the \"" output-folder "\" folder")))))


(let [[config-edn] *command-line-args*]
     (when (empty? config-edn)
           (println "please provide a valid edn file like `./clojube.clj config.edn`, and do note that `gitlab-registry-key` must be available in your cluster")
           (System/exit 1))
     (-> config-edn
         (slurp)
         (edn/read-string)
         (process-app-config)))

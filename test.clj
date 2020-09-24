#!/usr/bin/env bb

(require '[clj-yaml.core :as yaml])
(require '[clojure.string :as cljstr])
(require '[clojure.edn :as edn])
(require '[clojure.java.shell :as shell])

(defn uuid [] (subs (str (java.util.UUID/randomUUID)) 0 8))

(defn merge-yaml [yamls] (cljstr/join "\n---\n\n" yamls))

(defn generate-yaml [data] (yaml/generate-string data :dumper-options {:flow-style :block}))

(defn generate-local-volume [name ctnr-path local-path mode]
      (let [v (str name "-" (uuid) "-data")
            pv (str v "-pv")
            pvc (str pv "c")
            access-mode [({:rw  "ReadWriteMany"
                           :rwo "ReadWriteOnce"
                           :ro  "ReadOnlyMany"} mode)]]
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
                         :kind       "PersistentVolume"
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

(def app (first *input*))


(shell/sh "mkdir" "-p" "output")
; network
(spit "output/network.yaml" (generate-yaml (generate-service (:name app) (:ports (:dev app)))))
; volumes
(def generated-volumes (map (fn [{:keys [path host-path mode]}]
                                (generate-local-volume (:name app) path host-path mode)) (:volumes (:dev app))))
(spit "output/volumes.yaml" (->> generated-volumes
                                 (map :value)
                                 (flatten)
                                 (map generate-yaml)
                                 (merge-yaml)))
(spit "output/container.yaml" (generate-yaml (generate-container "core"
                                                                 (:image (:dev app))
                                                                 (:ports (:dev app))
                                                                 generated-volumes)))

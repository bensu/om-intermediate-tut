(ns om-async.core
  (:require [ring.util.response :refer [file-response]]
            [ring.adapter.jetty :refer [run-jetty]]
            [compojure.core :refer [defroutes GET PUT POST]]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [datomic.api :as d]
            [clojure.edn :as edn]))

(def uri "datomic:free://localhost:4334/om_async")
(def conn (d/connect uri))

(defn index []
  (file-response "public/html/index.html" {:root "resources"}))

(defn generate-response [data & [status]]
  {:status (or status 200)
   :headers {"Content-Type" "application/edn"}
   :body (pr-str data)})

(defn create-class [params]
  {:status 500})

(defn get-classes [db]
  (->> (d/q '[:find ?class
              :where
              [?class :class/id]]
          db)
       (map #(d/touch (d/entity db (first %))))
       vec))

(defn init []
  (generate-response
     {:classes {:url "/classes" :coll (get-classes (d/db conn))}}))

(defn update-class [params]
  (let [db    (d/db conn)
        id    (:class/id params)
        title (:class/title params)
        eid   (ffirst
                (d/q '[:find ?class
                       :in $ ?id
                       :where
                       [?class :class/id ?id]]
                  db id))]
    (d/transact conn [[:db/add eid :class/title title]])
    (generate-response {:status :ok})))

(defn classes []
  (generate-response (get-classes (d/db conn))))

(defroutes routes
  (GET "/" [] (index))
  (GET "/init" [] (init))
  (GET "/classes" [] (classes))
  (POST "/classes" {params :body-edn} (create-class params))
  (PUT "/classes" {params :body-edn} (update-class params))
  (route/files "/" {:root "resources/public"}))

(defn read-inputstream-edn [input]
  (edn/read
   {:eof nil}
   (java.io.PushbackReader.
    (java.io.InputStreamReader. input "UTF-8"))))

(defn parse-edn-body [handler]
  (fn [request]
    (handler (if-let [body (:body request)]
               (assoc request
                 :body-edn (read-inputstream-edn body))
               request))))

(def handler
  (-> routes
      parse-edn-body))

(ns ^:figwheel-always om-async.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async :refer [put! chan alts!]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [om-sync.core :refer [om-sync]]
            [om-sync.util :refer [tx-tag edn-xhr]]))

(enable-console-print!)

(def app-state
  (atom {:classes []}))

(defn display [show]
  (if show
    #js {}
    #js {:display "none"}))

(defn handle-change [e data edit-key owner]
  (om/transact! data edit-key (fn [_] (.. e -target -value))))

(defn end-edit [data edit-key text owner cb]
  (om/set-state! owner :editing false)
  (om/transact! data edit-key (fn [_] text) :update)
  (when cb
    (cb text)))

(defn editable [data owner {:keys [edit-key on-edit] :as opts}]
  (reify
    om/IInitState
    (init-state [_]
      {:editing false})
    om/IRenderState
    (render-state [_ {:keys [editing]}]
      (let [text (get data edit-key)]
        (dom/li nil
          (dom/span #js {:style (display (not editing))} text)
          (dom/input
            #js {:style (display editing)
                 :value text
                 :onChange #(handle-change % data edit-key owner)
                 :onKeyDown #(when (= (.-key %) "Enter")
                               (end-edit data edit-key text owner on-edit))
                 :onBlur #(when (om/get-state owner :editing)
                            (end-edit data edit-key text owner on-edit))})
          (dom/button
            #js {:style (display (not editing))
                 :onClick #(om/set-state! owner :editing true)}
            "Edit"))))))

(defn create-class [classes owner]
  (let [class-id-el   (om/get-node owner "class-id")
        class-id      (.-value class-id-el)
        class-name-el (om/get-node owner "class-name")
        class-name    (.-value class-name-el)
        new-class     {:class/id class-id :class/title class-name}]
    (om/transact! classes [] #(conj % new-class)
      [:create new-class])
    (set! (.-value class-id-el) "")
    (set! (.-value class-name-el) "")))

(defn classes-view [classes owner]
  (reify
    om/IRender
    (render [_]
      (dom/div #js {:id "classes"}
        (dom/h2 nil "Classes")
        (apply dom/ul nil
          (map #(om/build editable % {:opts {:edit-key :class/title}})
            classes))
        (dom/div nil
          (dom/label nil "ID:")
          (dom/input #js {:ref "class-id"})
          (dom/label nil "Name:")
          (dom/input #js {:ref "class-name"})
          (dom/button
            #js {:onClick (fn [e] (create-class classes owner))}
           "Add"))))))

(defn app-view [app owner]
  (reify
    om/IWillUpdate
    (will-update [_ next-props next-state]
      (when (:err-msg next-state)
        (js/setTimeout #(om/set-state! owner :err-msg nil) 5000)))
    om/IRenderState
    (render-state [_ {:keys [err-msg]}]
      (dom/div nil
        (om/build om-sync (:classes app)
          {:opts {:view classes-view
                  :filter (comp #{:create :update :delete} tx-tag)
                  :id-key :class/id
                  :on-success (fn [res tx-data] (println res))
                  :on-error
                  (fn [err tx-data]
                    (reset! app-state (:old-state tx-data))
                    (om/set-state! owner :err-msg
                      "Oops! Sorry, something went wrong. Try again later."))}})
         (when err-msg
           (dom/div nil err-msg))))))

(let [tx-chan (chan)
      tx-pub-chan (async/pub tx-chan (fn [_] :txs))]
  (edn-xhr
    {:method :get
     :url "/init"
     :on-complete
     (fn [res]
       (reset! app-state res)
       (om/root app-view app-state
         {:target (.getElementById js/document "classes") 
          :shared {:tx-chan tx-pub-chan}
          :tx-listen
          (fn [tx-data root-cursor]
            (put! tx-chan [tx-data root-cursor]))}))}))

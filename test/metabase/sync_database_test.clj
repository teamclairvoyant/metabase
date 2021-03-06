(ns metabase.sync-database-test
  (:require [expectations :refer :all]
            (metabase [db :as db]
                      [driver :as driver])
            (metabase.models [database :refer [Database]]
                             [field :refer [Field]]
                             [field-values :refer [FieldValues]]
                             [hydrate :refer :all]
                             [raw-table :refer [RawTable]]
                             [table :refer [Table]])
            [metabase.sync-database :refer :all]
            (metabase.test [data :refer :all]
                           [util :refer [resolve-private-fns] :as tu])))

(def ^:private ^:const sync-test-tables
  {"movie"  {:name "movie"
             :schema "default"
             :fields #{{:name      "id"
                        :base-type :IntegerField}
                       {:name      "title"
                        :base-type :TextField}
                       {:name      "studio"
                        :base-type :TextField}}}
   "studio" {:name "studio"
             :schema nil
             :fields #{{:name         "studio"
                        :base-type    :TextField
                        :special-type :id}
                       {:name      "name"
                        :base-type :TextField}}}})

(defrecord SyncTestDriver []
  clojure.lang.Named
  (getName [_] "SyncTestDriver"))

(extend SyncTestDriver
  driver/IDriver
  (merge driver/IDriverDefaultsMixin
         {:analyze-table      (constantly nil)
          :describe-database  (constantly {:tables (set (for [table (vals sync-test-tables)]
                                                          (dissoc table :fields)))})
          :describe-table     (fn [_ _ table]
                                (get sync-test-tables (:name table)))
          :describe-table-fks (fn [_ _ table]
                                (if (= "movie" (:name table))
                                  #{{:fk-column-name   "studio"
                                     :dest-table       {:name "studio"
                                                        :schema nil}
                                     :dest-column-name "studio"}}
                                  #{}))
          :features           (constantly #{:foreign-keys})
          :details-fields     (constantly [])}))

(driver/register-driver! :sync-test (SyncTestDriver.))


(def ^:private venues-table (delay (Table (id :venues))))

(defn- table-details [table]
  (into {} (-> (dissoc table :db :pk_field :field_values)
               (assoc :fields (for [field (db/select Field, :table_id (:id table), {:order-by [:name]})]
                                (into {} (dissoc field :table :db :children :qualified-name :qualified-name-components :values :target))))
               tu/boolean-ids-and-timestamps)))

(def ^:private ^:const table-defaults
  {:id                      true
   :db_id                   true
   :raw_table_id            true
   :schema                  nil
   :description             nil
   :caveats                 nil
   :points_of_interest      nil
   :show_in_getting_started false
   :entity_type             nil
   :entity_name             nil
   :visibility_type         nil
   :rows                    nil
   :active                  true
   :created_at              true
   :updated_at              true})

(def ^:private ^:const field-defaults
  {:id                 true
   :table_id           true
   :raw_column_id      true
   :description        nil
   :caveats            nil
   :points_of_interest nil
   :active             true
   :parent_id          false
   :position           0
   :preview_display    true
   :visibility_type    :normal
   :fk_target_field_id false
   :created_at         true
   :updated_at         true
   :last_analyzed      true})


;; ## SYNC DATABASE
(expect
  [(merge table-defaults
          {:schema       "default"
           :name         "movie"
           :display_name "Movie"
           :fields       [(merge field-defaults
                                 {:special_type :id
                                  :name         "id"
                                  :display_name "ID"
                                  :base_type    :IntegerField})
                          (merge field-defaults
                                 {:special_type       :fk
                                  :name               "studio"
                                  :display_name       "Studio"
                                  :base_type          :TextField
                                  :fk_target_field_id true})
                          (merge field-defaults
                                 {:special_type nil
                                  :name         "title"
                                  :display_name "Title"
                                  :base_type    :TextField})]})
   (merge table-defaults
          {:name         "studio"
           :display_name "Studio"
           :fields       [(merge field-defaults
                                 {:special_type :name
                                  :name         "name"
                                  :display_name "Name"
                                  :base_type    :TextField})
                          (merge field-defaults
                                 {:special_type :id
                                  :name         "studio"
                                  :display_name "Studio"
                                  :base_type    :TextField})]})]
  (tu/with-temp Database [fake-db {:engine :sync-test}]
    (sync-database! fake-db)
    ;; we are purposely running the sync twice to test for possible logic issues which only manifest
    ;; on resync of a database, such as adding tables that already exist or duplicating fields
    (sync-database! fake-db)
    (mapv table-details (db/select Table, :db_id (:id fake-db), {:order-by [:name]}))))


;; ## SYNC TABLE

(expect
  (merge table-defaults
         {:schema       "default"
          :name         "movie"
          :display_name "Movie"
          :fields       [(merge field-defaults
                                {:special_type :id
                                 :name         "id"
                                 :display_name "ID"
                                 :base_type    :IntegerField})
                         (merge field-defaults
                                {:special_type nil
                                 :name         "studio"
                                 :display_name "Studio"
                                 :base_type    :TextField})
                         (merge field-defaults
                                {:special_type nil
                                 :name         "title"
                                 :display_name "Title"
                                 :base_type    :TextField})]})
  (tu/with-temp* [Database [fake-db {:engine :sync-test}]
                  RawTable [{raw-table-id :id} {:database_id (:id fake-db), :name "movie", :schema "default"}]
                  Table    [fake-table {:raw_table_id raw-table-id
                                        :name   "movie"
                                        :schema "default"
                                        :db_id  (:id fake-db)}]]
    (sync-table! fake-table)
    (table-details (Table (:id fake-table)))))


;; test that we prevent running simultaneous syncs on the same database

(defonce ^:private sync-count (atom 0))

(defrecord ConcurrentSyncTestDriver []
  clojure.lang.Named
  (getName [_] "ConcurrentSyncTestDriver"))

(extend ConcurrentSyncTestDriver
  driver/IDriver
  (merge driver/IDriverDefaultsMixin
         {:analyze-table     (constantly nil)
          :describe-database (fn [_ _]
                               (swap! sync-count inc)
                               (Thread/sleep 500)
                               {:tables []})
          :describe-table    (constantly nil)
          :details-fields    (constantly [])}))

(driver/register-driver! :concurrent-sync-test (ConcurrentSyncTestDriver.))

(expect
  [0 1]
  (tu/with-temp* [Database [fake-db {:engine :concurrent-sync-test}]]
    (reset! sync-count 0)
    (let [future-sleep-then-run (fn [f]
                                  (Thread/sleep 100)
                                  (future (f)))
          concurrent-sync       #(sync-database! fake-db)]
    [@sync-count
     (do
       (future-sleep-then-run concurrent-sync)
       (future-sleep-then-run concurrent-sync)
       (concurrent-sync)
       @sync-count)])))

;; ## Test that we will remove field-values when they aren't appropriate

(expect
  [[1 2 3]
   [1 2 3]]
  (tu/with-temp* [Database [fake-db    {:engine :sync-test}]
                  RawTable [fake-table {:database_id (:id fake-db), :name "movie", :schema "default"}]]
    (sync-database! fake-db)
    (let [table-id (db/select-one-id Table, :raw_table_id (:id fake-table))
          field-id (db/select-one-id Field, :table_id table-id, :name "title")]
      (tu/with-temp FieldValues [_ {:field_id field-id
                                    :values   "[1,2,3]"}]
        (let [initial-field-values (db/select-one-field  :values FieldValues, :field_id field-id)]
          (sync-database! fake-db)
          [initial-field-values
           (db/select-one-field :values FieldValues, :field_id field-id)])))))


;; ## Individual Helper Fns

;; ## TEST PK SYNCING
(expect [:id
         nil
         :id
         :latitude
         :id]
  (let [get-special-type (fn [] (db/select-one-field :special_type Field, :id (id :venues :id)))]
    [;; Special type should be :id to begin with
     (get-special-type)
     ;; Clear out the special type
     (do (db/update! Field (id :venues :id), :special_type nil)
         (get-special-type))
     ;; Calling sync-table! should set the special type again
     (do (sync-table! @venues-table)
         (get-special-type))
     ;; sync-table! should *not* change the special type of fields that are marked with a different type
     (do (db/update! Field (id :venues :id), :special_type :latitude)
         (get-special-type))
     ;; Make sure that sync-table runs set-table-pks-if-needed!
     (do (db/update! Field (id :venues :id), :special_type nil)
         (sync-table! @venues-table)
         (get-special-type))]))

;; ## FK SYNCING

;; Check that Foreign Key relationships were created on sync as we expect

(expect (id :venues :id)
  (db/select-one-field :fk_target_field_id Field, :id (id :checkins :venue_id)))

(expect (id :users :id)
  (db/select-one-field :fk_target_field_id Field, :id (id :checkins :user_id)))

(expect (id :categories :id)
  (db/select-one-field :fk_target_field_id Field, :id (id :venues :category_id)))

;; Check that sync-table! causes FKs to be set like we'd expect
(expect [{:special_type :fk, :fk_target_field_id true}
         {:special_type nil, :fk_target_field_id false}
         {:special_type :fk, :fk_target_field_id true}]
  (let [field-id (id :checkins :user_id)
        get-special-type-and-fk-exists? (fn []
                                          (into {} (-> (db/select-one [Field :special_type :fk_target_field_id], :id field-id)
                                                       (update :fk_target_field_id #(db/exists? Field :id %)))))]
    [ ;; FK should exist to start with
     (get-special-type-and-fk-exists?)
     ;; Clear out FK / special_type
     (do (db/update! Field field-id, :special_type nil, :fk_target_field_id nil)
         (get-special-type-and-fk-exists?))
     ;; Run sync-table and they should be set again
     (let [table (Table (id :checkins))]
       (sync-table! table)
       (get-special-type-and-fk-exists?))]))


;;; ## FieldValues Syncing

(let [get-field-values    (fn [] (db/select-one-field :values FieldValues, :field_id (id :venues :price)))
      get-field-values-id (fn [] (db/select-one-id FieldValues, :field_id (id :venues :price)))]
  ;; Test that when we delete FieldValues syncing the Table again will cause them to be re-created
  (expect
    [[1 2 3 4]  ; 1
     nil        ; 2
     [1 2 3 4]] ; 3
    [ ;; 1. Check that we have expected field values to start with
     (get-field-values)
     ;; 2. Delete the Field values, make sure they're gone
     (do (db/cascade-delete! FieldValues :id (get-field-values-id))
         (get-field-values))
     ;; 3. Now re-sync the table and make sure they're back
     (do (sync-table! @venues-table)
         (get-field-values))])

  ;; Test that syncing will cause FieldValues to be updated
  (expect
    [[1 2 3 4]  ; 1
     [1 2 3]    ; 2
     [1 2 3 4]] ; 3
    [ ;; 1. Check that we have expected field values to start with
     (get-field-values)
     ;; 2. Update the FieldValues, remove one of the values that should be there
     (do (db/update! FieldValues (get-field-values-id), :values [1 2 3])
         (get-field-values))
     ;; 3. Now re-sync the table and make sure the value is back
     (do (sync-table! @venues-table)
         (get-field-values))]))

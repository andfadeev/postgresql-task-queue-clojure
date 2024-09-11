(ns postgresql-task-queue-clojure.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [postgresql-task-queue-clojure.core :refer :all])
  (:import (org.testcontainers.containers PostgreSQLContainer)))

(defn create-tasks-table
  [db-spec]
  (jdbc/execute! db-spec ["CREATE TABLE tasks (
                           id serial PRIMARY KEY,
                           task_name text,
                           status text
                           );"]))

(defn fetch-next-task
  [conn]
  (jdbc/execute-one!
    conn
    ["SELECT id, task_name, status
    FROM tasks WHERE status = 'pending' order by id
     FOR UPDATE SKIP LOCKED LIMIT 1"]
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn mark-task-completed [conn task-id]
  (jdbc/execute!
    conn
    ["UPDATE tasks SET status = 'completed' WHERE id = ?" task-id]))

(defn process-task
  [task]
  (println "Processing task:" (:task-name task))
  (Thread/sleep 1000)
  (println "Task processing complete for:" (:task-name task)))

(defn process-next-task
  [db-spec]
  (jdbc/with-transaction
    [tx db-spec]
    (if-let [task (fetch-next-task tx)]
      (do
        (println task)
        (process-task task)
        (mark-task-completed tx (:id task))
        (println "Marked task as completed:" (:id task))
        (:task-name task))
      (println "No pending tasks found."))))

(defn insert-new-task
  [db-spec task-name]
  (jdbc/execute-one!
    db-spec
    ["INSERT INTO tasks (task_name, status) VALUES (?, 'pending') RETURNING id"
     task-name]))

(deftest task-queue-demo-test
  (with-open [database-container (PostgreSQLContainer. "postgres:15.4")]
    (.start database-container)
    (let [db-spec {:jdbcUrl (.getJdbcUrl database-container)
                   :user (.getUsername database-container)
                   :password (.getPassword database-container)}]
      (create-tasks-table db-spec)
      (is (nil? (process-next-task db-spec)))

      (insert-new-task db-spec "task1")
      (insert-new-task db-spec "task2")
      (insert-new-task db-spec "task3")

      (is (= "task1" (process-next-task db-spec)))
      (is (= "task2" (process-next-task db-spec)))
      (is (= "task3" (process-next-task db-spec)))
      (is (nil? (process-next-task db-spec))))))
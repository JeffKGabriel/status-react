(ns status-im.test.group-chats.core
  (:require [cljs.test :refer-macros [deftest is testing]]
            [status-im.utils.clocks :as utils.clocks]
            [status-im.utils.config :as config]
            [status-im.group-chats.core :as group-chats]))

(def random-id "685a9351-417e-587c-8bc1-191ac2a57ef8")
(def chat-name "chat-name")

(def member-1 "member-1")
(def member-2 "member-2")
(def member-3 "member-3")
(def member-4 "member-4")

(def admin member-1)

(def chat-id (str random-id admin))

(def initial-message {:chat-id chat-id
                      :membership-updates [{:from admin
                                            :events [{:type "chat-created"
                                                      :name "chat-name"
                                                      :clock-value 1}
                                                     {:type "member-added"
                                                      :clock-value 3
                                                      :member member-2}
                                                     {:type "member-added"
                                                      :clock-value 3
                                                      :member member-3}]}]})

(deftest get-last-clock-value-test
  (is (= 3 (group-chats/get-last-clock-value {:db {:chats {chat-id initial-message}}} chat-id))))

(deftest handle-group-membership-update
  (with-redefs [config/group-chats-enabled? true]
    (testing "a brand new chat"
      (let [actual   (->
                      (group-chats/handle-membership-update {:db {}} initial-message admin)
                      :db
                      :chats
                      (get chat-id))]
        (testing "it creates a new chat"
          (is actual))
        (testing "it sets the right chat-name"
          (is (= "chat-name"
                 (:name actual))))
        (testing "it sets the right chat-id"
          (is (= chat-id
                 (:chat-id actual))))
        (testing "it sets the right participants"
          (is (= #{member-1 member-2 member-3}
                 (:members actual))))
        (testing "it sets the updates"
          (is (= (:membership-updates initial-message)
                 (:membership-updates actual))))
        (testing "it sets the right admins"
          (is (= #{admin}
                 (:admins actual))))))
    (testing "a chat with the wrong id"
      (let [bad-chat-id (str random-id member-2)
            actual      (->
                         (group-chats/handle-membership-update
                          {:db {}}
                          (assoc initial-message :chat-id bad-chat-id)
                          admin)
                         :db
                         :chats
                         (get bad-chat-id))]
        (testing "it does not create a chat"
          (is (not actual)))))
    (testing "an already existing chat"
      (let [cofx {:db {:chats {chat-id {:admins #{admin}
                                        :name "chat-name"
                                        :chat-id chat-id
                                        :is-active true
                                        :group-chat true
                                        :members #{member-1 member-2 member-3}
                                        :membership-updates (:membership-updates initial-message)}}}}]
        (testing "the message has already been received"
          (let [actual (group-chats/handle-membership-update cofx initial-message admin)]
            (testing "it noops"
              (is (= (get-in actual [:db :chats chat-id])
                     (get-in cofx [:db :chats chat-id]))))))
        (testing "a new message comes in"
          (let [actual (group-chats/handle-membership-update cofx
                                                             {:chat-id chat-id
                                                              :membership-updates [{:from member-1
                                                                                    :events [{:type "chat-created"
                                                                                              :clock-value 1
                                                                                              :name "group-name"}
                                                                                             {:type "admin-added"
                                                                                              :clock-value 10
                                                                                              :member member-2}
                                                                                             {:type "admin-removed"
                                                                                              :clock-value 11
                                                                                              :member member-1}]}
                                                                                   {:from member-2
                                                                                    :events [{:type "member-removed"
                                                                                              :clock-value 12
                                                                                              :member member-3}
                                                                                             {:type "member-added"
                                                                                              :clock-value 12
                                                                                              :member member-4}]}]}
                                                             member-3)
                actual-chat (get-in actual [:db :chats chat-id])]
            (testing "the chat is updated"
              (is actual-chat))
            (testing "admins are updated"
              (is (= #{member-2} (:admins actual-chat))))
            (testing "members are updated"
              (is (= #{member-1 member-2 member-4} (:members actual-chat))))))))))

(deftest build-group-test
  (testing "only adds"
    (let [events [{:type    "chat-created"
                   :clock-value 0
                   :name    "chat-name"
                   :from    "1"}
                  {:type    "member-added"
                   :clock-value 2
                   :from    "1"
                   :member  "2"}
                  {:type    "admin-added"
                   :clock-value 3
                   :from    "1"
                   :member  "2"}
                  {:type    "member-added"
                   :clock-value 3
                   :from    "2"
                   :member  "3"}]
          expected {:name   "chat-name"
                    :admins #{"1" "2"}
                    :members #{"1" "2" "3"}}]
      (is (= expected (group-chats/build-group events)))))
  (testing "adds and removes"
    (let [events [{:type    "chat-created"
                   :clock-value 0
                   :name  "chat-name"
                   :from    "1"}
                  {:type    "member-added"
                   :clock-value 1
                   :from    "1"
                   :member  "2"}
                  {:type    "admin-added"
                   :clock-value 2
                   :from    "1"
                   :member  "2"}
                  {:type    "admin-removed"
                   :clock-value 3
                   :from    "2"
                   :member  "2"}
                  {:type    "member-removed"
                   :clock-value 4
                   :from   "2"
                   :member "2"}]
          expected {:name "chat-name"
                    :admins #{"1"}
                    :members #{"1"}}]
      (is (= expected (group-chats/build-group events)))))
  (testing "name changed"
    (let [events [{:type    "chat-created"
                   :clock-value 0
                   :name  "chat-name"
                   :from    "1"}
                  {:type    "member-added"
                   :clock-value 1
                   :from    "1"
                   :member  "2"}
                  {:type    "admin-added"
                   :clock-value 2
                   :from    "1"
                   :member  "2"}
                  {:type    "name-changed"
                   :clock-value 3
                   :from    "2"
                   :name  "new-name"}]
          expected {:name "new-name"
                    :admins #{"1" "2"}
                    :members #{"1" "2"}}]
      (is (= expected (group-chats/build-group events)))))
  (testing "invalid events"
    (let [events [{:type    "chat-created"
                   :name "chat-name"
                   :clock-value 0
                   :from    "1"}
                  {:type    "admin-added" ; can't make an admin a user not in the group
                   :clock-value 1
                   :from    "1"
                   :member  "non-existing"}
                  {:type    "member-added"
                   :clock-value 2
                   :from    "1"
                   :member  "2"}
                  {:type    "admin-added"
                   :clock-value 3
                   :from    "1"
                   :member  "2"}
                  {:type    "member-added"
                   :clock-value 4
                   :from    "2"
                   :member  "3"}
                  {:type    "admin-removed" ; can't remove an admin from admins unless it's the same user
                   :clock-value 5
                   :from    "1"
                   :member  "2"}
                  {:type    "member-removed" ; can't remove an admin from the group
                   :clock-value 6
                   :from    "1"
                   :member  "2"}]
          expected {:name "chat-name"
                    :admins #{"1" "2"}
                    :members #{"1" "2" "3"}}]
      (is (= expected (group-chats/build-group events)))))
  (testing "out of order-events"
    (let [events [{:type    "chat-created"
                   :name    "chat-name"
                   :clock-value 0
                   :from    "1"}
                  {:type    "admin-added"
                   :clock-value 2
                   :from    "1"
                   :member  "2"}
                  {:type    "member-added"
                   :clock-value 1
                   :from    "1"
                   :member  "2"}
                  {:type    "member-added"
                   :clock-value 3
                   :from    "2"
                   :member  "3"}]
          expected {:name "chat-name"
                    :admins #{"1" "2"}
                    :members #{"1" "2" "3"}}]
      (is (= expected (group-chats/build-group events))))))

(deftest valid-event-test
  (let [multi-admin-group {:admins #{"1" "2"}
                           :members #{"1" "2" "3"}}
        single-admin-group {:admins #{"1"}
                            :members #{"1" "2" "3"}}]
    (testing "member-addeds"
      (testing "admins can add members"
        (is (group-chats/valid-event? multi-admin-group
                                      {:type "member-added" :clock-value 6 :from "1" :member "4"})))
      (testing "non-admin members cannot add members"
        (is (not (group-chats/valid-event? multi-admin-group
                                           {:type "member-added" :clock-value 6 :from "3" :member "4"})))))
    (testing "admin-addeds"
      (testing "admins can make other member admins"
        (is (group-chats/valid-event? multi-admin-group
                                      {:type "admin-added" :clock-value 6 :from "1" :member "3"})))
      (testing "non-admins can't make other member admins"
        (is (not (group-chats/valid-event? multi-admin-group
                                           {:type "admin-added" :clock-value 6 :from "3" :member "3"}))))
      (testing "non-existing users can't be made admin"
        (is (not (group-chats/valid-event? multi-admin-group
                                           {:type "admin-added" :clock-value 6 :from "1" :member "not-existing"})))))
    (testing "member-removed"
      (testing "admins can remove non-admin members"
        (is (group-chats/valid-event? multi-admin-group
                                      {:type "member-removed" :clock-value 6 :from "1" :member "3"})))
      (testing "admins can't remove themselves"
        (is (not (group-chats/valid-event? multi-admin-group
                                           {:type "member-removed" :clock-value 6 :from "1" :member "1"}))))
      (testing "participants non-admin can remove themselves"
        (is (group-chats/valid-event? multi-admin-group
                                      {:type "member-removed" :clock-value 6 :from "3" :member "3"})))
      (testing "non-admin can't remove other members"
        (is (not (group-chats/valid-event? multi-admin-group
                                           {:type "member-removed" :clock-value 6 :from "3" :member "1"})))))
    (testing "admin-removed"
      (testing "admins can remove themselves"
        (is (group-chats/valid-event? multi-admin-group
                                      {:type "admin-removed" :clock-value 6 :from "1" :member "1"})))
      (testing "admins can't remove other admins"
        (is (not (group-chats/valid-event? multi-admin-group
                                           {:type "admin-removed" :clock-value 6 :from "1" :member "2"}))))
      (testing "participants non-admin can't remove other admins"
        (is (not (group-chats/valid-event? multi-admin-group
                                           {:type "admin-removed" :clock-value 6 :from "3" :member "1"}))))
      (testing "the last admin can't be removed"
        (is (not (group-chats/valid-event? single-admin-group
                                           {:type "admin-removed" :clock-value 6 :from "1" :member "1"}))))
      (testing "name-changed"
        (testing "a change from an admin"
          (is (group-chats/valid-event? multi-admin-group
                                        {:type "name-changed" :clock-value 6 :from "1" :name "new-name"}))))
      (testing "a change from an non-admin"
        (is (not (group-chats/valid-event? multi-admin-group
                                           {:type "name-changed" :clock-value 6 :from "3" :name "new-name"}))))
      (testing "an empty name"
        (is (not (group-chats/valid-event? multi-admin-group
                                           {:type "name-changed" :clock-value 6 :from "1" :name "   "})))))))

(deftest create-test
  (testing "create a new chat"
    (with-redefs [utils.clocks/send inc]
      (let [cofx {:random-guid-generator (constantly "random")
                  :db {:current-public-key "me"
                       :group/selected-contacts #{"1" "2"}}}]
        (is (= {:chat-id "randomme"
                :from    "me"
                :events [{:type "chat-created"
                          :clock-value 1
                          :name "group-name"}
                         {:type "member-added"
                          :clock-value 2
                          :member "1"}
                         {:type "member-added"
                          :clock-value 3
                          :member "2"}]}
               (:group-chats/sign-membership (group-chats/create cofx "group-name"))))))))

(deftest membership-changes-test
  (testing "addition and removals"
    (let [old-group {:admins #{"1" "2"}
                     :members #{"1" "2"}}
          new-group {:admins #{"1" "3"}
                     :members #{"1" "3"}}]
      (is (= [{:type "admin-removed" :member "2"}
              {:type "member-removed" :member "2"}
              {:type "member-added" :member "3"}
              {:type "admin-added" :member "3"}]
             (group-chats/membership-changes old-group new-group))))))

(deftest signature-pairs-test
  (let [event-1 {:from "1"
                 :signature "signature-1"
                 :events [{:type "a" :name "a" :clock-value 1}
                          {:type "b" :name "b" :clock-value 2}]}
        event-2 {:from "2"
                 :signature "signature-2"
                 :events [{:type "c" :name "c" :clock-value 1}
                          {:type "d" :name "d" :clock-value 2}]}
        message {:chat-id "randomme"

                 :membership-updates [event-1
                                      event-2]}
        expected (js/JSON.stringify
                  (clj->js [[(group-chats/signature-material "randomme" (:events event-1))
                             "signature-1"]
                            [(group-chats/signature-material "randomme" (:events event-2))
                             "signature-2"]]))]

    (is (= expected (group-chats/signature-pairs message)))))

(deftest signature-material-test
  (is (= (js/JSON.stringify (clj->js [[[["a" "a-value"]
                                        ["b" "b-value"]
                                        ["c" "c-value"]]
                                       [["a" "a-value"]
                                        ["e" "e-value"]]] "chat-id"]))
         (group-chats/signature-material "chat-id" [{:b "b-value"
                                                     :a "a-value"
                                                     :c "c-value"}
                                                    {:e "e-value"
                                                     :a "a-value"}]))))

(deftest remove-group-chat-test
  (with-redefs [utils.clocks/send inc]
    (let [cofx {:db {:chats {chat-id {:admins #{admin}
                                      :name "chat-name"
                                      :chat-id chat-id
                                      :is-active true
                                      :group-chat true
                                      :members #{member-1 member-2 member-3}
                                      :membership-updates (:membership-updates initial-message)}}}}]
      (testing "removing a member"
        (is (= {:from member-3
                :chat-id chat-id
                :events [{:type "member-removed" :member member-3 :clock-value 4}]}
               (:group-chats/sign-membership
                (group-chats/remove
                 (assoc-in cofx [:db :current-public-key] member-3)
                 chat-id)))))
      (testing "removing an admin"
        (is (not (:group-chats/sign-membership
                  (group-chats/remove
                   (assoc-in cofx [:db :current-public-key] member-1)
                   chat-id))))))))

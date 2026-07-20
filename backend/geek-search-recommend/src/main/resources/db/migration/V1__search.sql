CREATE TABLE search_hot_word (id BIGINT PRIMARY KEY,keyword VARCHAR(96) NOT NULL UNIQUE,score INT NOT NULL,start_at TIMESTAMP(3),end_at TIMESTAMP(3),status VARCHAR(32) NOT NULL);
CREATE TABLE search_synonym (id BIGINT PRIMARY KEY,source_word VARCHAR(96) NOT NULL UNIQUE,target_words VARCHAR(500) NOT NULL,status VARCHAR(32) NOT NULL);
CREATE TABLE search_history (id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,keyword VARCHAR(128) NOT NULL,searched_at TIMESTAMP(3) NOT NULL,KEY idx_search_user(user_id,searched_at));
CREATE TABLE event_consume_log (consumer_group VARCHAR(96) NOT NULL,event_id VARCHAR(64) NOT NULL,processed_at TIMESTAMP(3) NOT NULL,result VARCHAR(32) NOT NULL,PRIMARY KEY(consumer_group,event_id));
CREATE TABLE search_reconcile_log (id BIGINT PRIMARY KEY,started_at TIMESTAMP(3) NOT NULL,finished_at TIMESTAMP(3),source_count INT,indexed_count INT,status VARCHAR(32) NOT NULL,error_message VARCHAR(500));

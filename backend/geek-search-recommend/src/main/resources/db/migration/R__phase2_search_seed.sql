INSERT INTO search_hot_word VALUES (1,'轻薄本',100,NULL,NULL,'ENABLED'),(2,'MiniLED',90,NULL,NULL,'ENABLED'),(3,'32GB 内存',80,NULL,NULL,'ENABLED'),(4,'4K 显示器',70,NULL,NULL,'ENABLED') ON DUPLICATE KEY UPDATE score=VALUES(score);
INSERT INTO search_synonym VALUES (1,'笔记本','轻薄本,游戏本,电脑', 'ENABLED'),(2,'屏幕','显示器,MiniLED','ENABLED') ON DUPLICATE KEY UPDATE target_words=VALUES(target_words);

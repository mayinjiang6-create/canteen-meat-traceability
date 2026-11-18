-- =============================================================
-- V6 业务编号序列（发号器）
--
-- 背景（并发实测暴露）：原来的编号是「SELECT 当日最大号 + 1」生成的。
-- 单线程没问题，并发下四个请求会算出同一个号 PR20261118006，
-- 三个撞 uk_process_no 唯一键直接 500 —— 业务没写错，是发号方式错了。
--
-- 改法：一张序列表 + `UPDATE ... SET next_val = LAST_INSERT_ID(next_val + 1)`。
-- 这条 UPDATE 是当前读（不是快照读），行锁天然把并发请求排在队里，
-- 每个请求拿到的一定是不同且递增的号。
--
-- next_val 的语义：**下一个要发的号**。发号时先 +1，再取回（即拿到 +1 之后的值）。
-- 首次使用某个序列名时，用业务表现有最大号做种子，保证不与历史数据撞号。
--
-- 这不是业务表，不参与溯源与存证，删掉它只会让编号从头再来。
-- =============================================================

CREATE TABLE IF NOT EXISTS biz_sequence (
  seq_name   VARCHAR(64) NOT NULL COMMENT '序列名：PROCESS_NO:20261118 / SUPPLIER',
  next_val   BIGINT      NOT NULL DEFAULT 0 COMMENT '下一个待发号码',
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (seq_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务编号序列（发号器，非业务表）';

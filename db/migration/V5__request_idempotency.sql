-- =============================================================
-- 高校食堂肉类供应链溯源系统 · 业务层幂等（BR-10）
-- Flyway V5
--
-- 为什么需要单独一张表：
--   存证层幂等靠合约的 eventId + 摘要判重（BR-11/BR-12），但那是"网络重试"层面的；
--   业务层幂等要解决的是"用户连点两次提交"——如果只靠合约判重，
--   第二次仍会往 purchase_batch 里插一条新行，只是链上不重复而已。
--
-- 做法：写接口进来先 INSERT 一条 request_id（唯一键）。插入成功=首次请求，继续业务；
--       唯一键冲突=重复请求，查出首次的 biz_id 直接返回，标 replayed=true。
--
-- 只存 biz_id 而不存整个响应体，是为了避免响应结构变化后旧缓存反序列化失败。
-- =============================================================

SET NAMES utf8mb4;

CREATE TABLE request_idempotency (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  request_id   VARCHAR(64)   NOT NULL COMMENT '请求头 X-Request-Id',
  user_id      BIGINT UNSIGNED DEFAULT NULL,
  endpoint     VARCHAR(100)  NOT NULL COMMENT '接口标识，如 POST /api/purchases',
  biz_id       BIGINT UNSIGNED DEFAULT NULL COMMENT '首次请求落库的业务主键',
  business_no  VARCHAR(64)   DEFAULT NULL COMMENT '业务编号（批次号/验收号），便于排查',
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem_request (request_id),
  KEY idx_idem_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务层幂等（BR-10）';

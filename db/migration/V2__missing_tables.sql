-- =============================================================
-- 高校食堂肉类供应链溯源系统 · 补齐缺失表
-- Flyway V2 ｜ 对应缺口 G4（见 docs/tech/11-落地实施计划.md §2.2）
--
-- 为什么要有这个脚本：
--   TECH-02 与 PROD-01 承诺了「冷链登记」「异常处置」「召回」三个功能，
--   CHANGELOG 0.1.0 也写了 V3 会做 AI 扩展，但 V1 的 11 张表里并没有对应结构
--   （冷链只体现为 purchase_batch.cold_chain_temp 一个到货温度字段）。
--   本脚本补齐 4 张表，使「文档承诺的功能」与「数据库能力」对齐。
-- =============================================================

SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 12. 冷链温控记录（一个采购批次可有多段、多次记录）
--     对应事件类型 COLD_CHAIN，关键字段 temperature / transportNo / segment
-- -------------------------------------------------------------
CREATE TABLE cold_chain_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  log_no        VARCHAR(40)   NOT NULL COMMENT 'CC+yyyyMMdd+3位',
  batch_id      BIGINT UNSIGNED NOT NULL,
  segment       VARCHAR(30)   NOT NULL COMMENT '运输段',
  transport_no  VARCHAR(40)   DEFAULT NULL COMMENT '运输单号',
  temperature   DECIMAL(5,2)  NOT NULL COMMENT '实测温度℃',
  threshold     DECIMAL(5,2)  DEFAULT NULL COMMENT '本段阈值上限℃',
  is_exceeded   TINYINT       NOT NULL DEFAULT 0 COMMENT '1=超阈值',
  recorded_at   DATETIME      NOT NULL COMMENT '测温时刻',
  recorder      VARCHAR(50)   DEFAULT NULL COMMENT '记录人/司机',
  remark        VARCHAR(200)  DEFAULT NULL,
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cold_log_no (log_no),
  KEY idx_cold_batch (batch_id, recorded_at),
  CONSTRAINT fk_cold_batch FOREIGN KEY (batch_id) REFERENCES purchase_batch (id),
  CONSTRAINT ck_cold_segment CHECK (segment IN ('ORIGIN_TO_WAREHOUSE','WAREHOUSE_TO_CANTEEN')),
  CONSTRAINT ck_cold_exceeded CHECK (is_exceeded IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='冷链温控记录';

-- -------------------------------------------------------------
-- 13. 异常事件与处置
--     对应事件类型 DISPOSAL，关键字段 abnormalType / action / handler / conclusion
--     一条异常要么挂在采购批次上，要么挂在加工批次上，二者至少有一个
-- -------------------------------------------------------------
CREATE TABLE abnormal_event (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  abnormal_no       VARCHAR(40)   NOT NULL COMMENT 'AB+yyyyMMdd+3位',
  batch_id          BIGINT UNSIGNED DEFAULT NULL COMMENT '关联采购批次',
  process_batch_id  BIGINT UNSIGNED DEFAULT NULL COMMENT '关联加工批次',
  abnormal_type     VARCHAR(30)   NOT NULL,
  level             VARCHAR(10)   NOT NULL DEFAULT 'MEDIUM',
  description       VARCHAR(300)  NOT NULL COMMENT '现象描述',
  action            VARCHAR(200)  DEFAULT NULL COMMENT '处置措施',
  handler           VARCHAR(50)   DEFAULT NULL COMMENT '处置人',
  conclusion        VARCHAR(200)  DEFAULT NULL COMMENT '处置结论',
  status            VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
  occurred_at       DATETIME      NOT NULL COMMENT '发生时刻',
  closed_at         DATETIME      DEFAULT NULL,
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_abnormal_no (abnormal_no),
  KEY idx_abnormal_batch (batch_id),
  KEY idx_abnormal_process (process_batch_id),
  KEY idx_abnormal_status (status, occurred_at),
  CONSTRAINT fk_abnormal_batch FOREIGN KEY (batch_id) REFERENCES purchase_batch (id),
  CONSTRAINT fk_abnormal_process FOREIGN KEY (process_batch_id) REFERENCES process_batch (id),
  CONSTRAINT ck_abnormal_type CHECK (abnormal_type IN
    ('TEMPERATURE_EXCEEDED','SENSORY_ABNORMAL','CERT_MISSING','SHELF_LIFE','OTHER')),
  CONSTRAINT ck_abnormal_level CHECK (level IN ('LOW','MEDIUM','HIGH')),
  CONSTRAINT ck_abnormal_status CHECK (status IN ('OPEN','HANDLING','CLOSED')),
  CONSTRAINT ck_abnormal_ref CHECK (batch_id IS NOT NULL OR process_batch_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常事件与处置';

-- -------------------------------------------------------------
-- 14. 召回记录
--     对应事件类型 RECALL，关键字段 processNo / reason / scope / affectedSales
--     召回后：sale_record.status 批量置 RECALLED，process_batch.status 置 RECALLED
-- -------------------------------------------------------------
CREATE TABLE recall_record (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  recall_no         VARCHAR(40)   NOT NULL COMMENT 'RC+yyyyMMdd+3位',
  process_batch_id  BIGINT UNSIGNED NOT NULL,
  reason            VARCHAR(300)  NOT NULL COMMENT '召回原因',
  scope             VARCHAR(200)  DEFAULT NULL COMMENT '召回范围说明',
  affected_sales    INT           NOT NULL DEFAULT 0 COMMENT '受影响销售笔数',
  recalled_qty      DECIMAL(12,3) DEFAULT NULL COMMENT '已售出需追回数量',
  operator          VARCHAR(50)   DEFAULT NULL,
  recall_time       DATETIME      NOT NULL,
  status            VARCHAR(20)   NOT NULL DEFAULT 'INITIATED',
  remark            VARCHAR(200)  DEFAULT NULL,
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_recall_no (recall_no),
  KEY idx_recall_process (process_batch_id),
  KEY idx_recall_time (recall_time),
  CONSTRAINT fk_recall_process FOREIGN KEY (process_batch_id) REFERENCES process_batch (id),
  CONSTRAINT ck_recall_status CHECK (status IN ('INITIATED','COMPLETED')),
  CONSTRAINT ck_recall_affected CHECK (affected_sales >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='召回记录';

-- -------------------------------------------------------------
-- 15. 知识库分块（AI 问答 / RAG）
--     设计口径见 TECH-09：向量存 MySQL，Java 内存余弦，不引向量库
--     只允许 MATCHED 的事件进入知识库（见 PROD-06）
-- -------------------------------------------------------------
CREATE TABLE kb_chunk (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  source_type      VARCHAR(20)   NOT NULL COMMENT 'EVENT 事件 / DOC 文档',
  source_id        VARCHAR(64)   NOT NULL COMMENT 'eventId 或文档标识',
  batch_id         VARCHAR(40)   DEFAULT NULL COMMENT '便于按批次过滤',
  chunk_index      INT           NOT NULL DEFAULT 0,
  chunk_text       TEXT          NOT NULL COMMENT '送检索的正文',
  embedding        JSON          DEFAULT NULL COMMENT 'float 数组，内存余弦用',
  embedding_model  VARCHAR(50)   DEFAULT NULL COMMENT '如 embedding-3',
  dim              INT           DEFAULT NULL COMMENT '向量维度',
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_chunk_source (source_type, source_id, chunk_index),
  KEY idx_chunk_batch (batch_id),
  KEY idx_chunk_model (embedding_model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库分块';

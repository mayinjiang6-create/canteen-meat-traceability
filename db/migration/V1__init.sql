-- =============================================================
-- 高校食堂肉类供应链溯源系统 · 初始化脚本
-- Flyway V1 ｜ 设计说明见 docs/tech/02-数据库设计.md
-- 注意：本脚本已发布后不可修改，变更请新增 V4__xxx.sql
-- =============================================================

SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 1. 供应商备案
-- -------------------------------------------------------------
CREATE TABLE supplier (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  supplier_code   VARCHAR(32)  NOT NULL COMMENT '供应商编码 SUP-0001',
  name            VARCHAR(100) NOT NULL COMMENT '企业名称',
  credit_code     VARCHAR(18)  DEFAULT NULL COMMENT '统一社会信用代码',
  license_no      VARCHAR(50)  DEFAULT NULL COMMENT '食品经营许可证号',
  contact_name    VARCHAR(50)  DEFAULT NULL,
  contact_phone   VARCHAR(20)  DEFAULT NULL,
  address         VARCHAR(200) DEFAULT NULL,
  status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_code (supplier_code),
  CONSTRAINT ck_supplier_status CHECK (status IN ('ACTIVE','SUSPENDED','BLACKLISTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商备案';

-- -------------------------------------------------------------
-- 2. 账号与角色
-- -------------------------------------------------------------
CREATE TABLE sys_user (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username      VARCHAR(50)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt，禁止明文',
  display_name  VARCHAR(50)  NOT NULL,
  role          VARCHAR(20)  NOT NULL,
  supplier_id   BIGINT UNSIGNED DEFAULT NULL,
  status        TINYINT      NOT NULL DEFAULT 1,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username),
  KEY idx_user_supplier (supplier_id),
  CONSTRAINT fk_user_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (id),
  CONSTRAINT ck_user_role CHECK (role IN ('ADMIN','SUPPLIER','VIEWER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号与角色';

-- -------------------------------------------------------------
-- 3. 采购批次
-- -------------------------------------------------------------
CREATE TABLE purchase_batch (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_no        VARCHAR(40)  NOT NULL COMMENT 'PB+yyyyMMdd+3位',
  supplier_id     BIGINT UNSIGNED NOT NULL,
  meat_type       VARCHAR(30)  NOT NULL,
  origin          VARCHAR(100) DEFAULT NULL,
  slaughter_no    VARCHAR(50)  DEFAULT NULL,
  quarantine_no   VARCHAR(60)  DEFAULT NULL COMMENT '检疫合格证号',
  quantity        DECIMAL(12,3) NOT NULL,
  available_qty   DECIMAL(12,3) NOT NULL,
  unit            VARCHAR(10)  NOT NULL DEFAULT 'kg',
  unit_price      DECIMAL(10,2) DEFAULT NULL,
  arrive_time     DATETIME     DEFAULT NULL,
  cold_chain_temp DECIMAL(5,2) DEFAULT NULL,
  status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING_ACCEPT',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_purchase_batch_no (batch_no),
  KEY idx_purchase_supplier (supplier_id),
  KEY idx_purchase_status (status),
  KEY idx_purchase_quarantine (quarantine_no),
  CONSTRAINT fk_purchase_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (id),
  CONSTRAINT ck_purchase_qty CHECK (quantity > 0),
  CONSTRAINT ck_purchase_avail CHECK (available_qty >= 0 AND available_qty <= quantity),
  CONSTRAINT ck_purchase_status CHECK (status IN ('PENDING_ACCEPT','PASSED','REJECTED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购批次';

-- -------------------------------------------------------------
-- 4. 验收记录
-- -------------------------------------------------------------
CREATE TABLE acceptance_record (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  accept_no       VARCHAR(40)  NOT NULL,
  batch_id        BIGINT UNSIGNED NOT NULL,
  accept_time     DATETIME     NOT NULL,
  inspector       VARCHAR(50)  NOT NULL,
  temperature     DECIMAL(5,2) DEFAULT NULL,
  appearance_ok   TINYINT      NOT NULL DEFAULT 1,
  cert_complete   TINYINT      NOT NULL DEFAULT 1,
  sample_retained TINYINT      NOT NULL DEFAULT 0,
  result          VARCHAR(10)  NOT NULL,
  reject_reason   VARCHAR(200) DEFAULT NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_accept_no (accept_no),
  KEY idx_accept_batch (batch_id),
  CONSTRAINT fk_accept_batch FOREIGN KEY (batch_id) REFERENCES purchase_batch (id),
  CONSTRAINT ck_accept_result CHECK (result IN ('PASS','FAIL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='验收记录';

-- -------------------------------------------------------------
-- 5. 加工批次
-- -------------------------------------------------------------
CREATE TABLE process_batch (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  process_no       VARCHAR(40)  NOT NULL COMMENT 'PR+yyyyMMdd+3位，即追溯码',
  meat_type        VARCHAR(30)  NOT NULL,
  output_qty       DECIMAL(12,3) NOT NULL,
  available_qty    DECIMAL(12,3) NOT NULL,
  unit             VARCHAR(10)  NOT NULL DEFAULT 'kg',
  process_time     DATETIME     NOT NULL,
  workshop         VARCHAR(50)  DEFAULT NULL,
  operator         VARCHAR(50)  DEFAULT NULL,
  retain_sample_no VARCHAR(40)  DEFAULT NULL COMMENT '留样编号',
  status           VARCHAR(20)  NOT NULL DEFAULT 'CREATED',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_process_no (process_no),
  KEY idx_process_status (status),
  CONSTRAINT ck_process_qty CHECK (output_qty > 0),
  CONSTRAINT ck_process_avail CHECK (available_qty >= 0 AND available_qty <= output_qty),
  CONSTRAINT ck_process_status CHECK (status IN ('CREATED','ON_SALE','SOLD_OUT','ABNORMAL','RECALLED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加工批次';

-- -------------------------------------------------------------
-- 6. 来源关系（多来源加工核心表）
-- -------------------------------------------------------------
CREATE TABLE source_relation (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  process_batch_id  BIGINT UNSIGNED NOT NULL,
  purchase_batch_id BIGINT UNSIGNED NOT NULL,
  used_qty          DECIMAL(12,3) NOT NULL,
  created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_process_purchase (process_batch_id, purchase_batch_id),
  KEY idx_source_purchase (purchase_batch_id),
  CONSTRAINT fk_source_process FOREIGN KEY (process_batch_id) REFERENCES process_batch (id),
  CONSTRAINT fk_source_purchase FOREIGN KEY (purchase_batch_id) REFERENCES purchase_batch (id),
  CONSTRAINT ck_source_used CHECK (used_qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加工批次来源关系';

-- -------------------------------------------------------------
-- 7. 销售记录
-- -------------------------------------------------------------
CREATE TABLE sale_record (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  sale_no          VARCHAR(40)  NOT NULL,
  process_batch_id BIGINT UNSIGNED NOT NULL,
  channel          VARCHAR(30)  DEFAULT NULL COMMENT '档口/窗口',
  quantity         DECIMAL(12,3) NOT NULL,
  unit_price       DECIMAL(10,2) DEFAULT NULL,
  sale_time        DATETIME     NOT NULL,
  operator         VARCHAR(50)  DEFAULT NULL,
  status           VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sale_no (sale_no),
  KEY idx_sale_process (process_batch_id),
  CONSTRAINT fk_sale_process FOREIGN KEY (process_batch_id) REFERENCES process_batch (id),
  CONSTRAINT ck_sale_qty CHECK (quantity > 0),
  CONSTRAINT ck_sale_status CHECK (status IN ('NORMAL','RECALLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售记录';

-- -------------------------------------------------------------
-- 8. 事件快照（上链原件）
-- -------------------------------------------------------------
CREATE TABLE event_snapshot (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id       CHAR(64)     NOT NULL COMMENT '32位hex业务事件标识',
  batch_id       VARCHAR(40)  NOT NULL,
  event_type     VARCHAR(30)  NOT NULL,
  biz_time       DATETIME     NOT NULL,
  quantity       DECIMAL(12,3) DEFAULT NULL,
  unit           VARCHAR(10)  DEFAULT NULL,
  payload_json   JSON         NOT NULL,
  canonical_text TEXT         NOT NULL COMMENT '规范化原串，核验按此重算',
  data_digest    CHAR(64)     NOT NULL COMMENT 'SHA-256 小写hex',
  schema_version VARCHAR(10)  NOT NULL DEFAULT 'v1',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_id (event_id),
  KEY idx_snapshot_batch (batch_id),
  KEY idx_snapshot_type_time (event_type, biz_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件快照';

-- -------------------------------------------------------------
-- 9. 存证任务
-- -------------------------------------------------------------
CREATE TABLE evidence_task (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id      CHAR(64)    NOT NULL,
  status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  retry_count   INT         NOT NULL DEFAULT 0,
  next_retry_at DATETIME    DEFAULT NULL,
  last_error    VARCHAR(500) DEFAULT NULL,
  created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_event (event_id),
  KEY idx_task_status (status, next_retry_at),
  CONSTRAINT ck_task_status CHECK (status IN ('PENDING','SUBMITTED','CONFIRMED','FAILED','UNREACHABLE','CONFLICT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存证任务';

-- -------------------------------------------------------------
-- 10. 交易回执
-- -------------------------------------------------------------
CREATE TABLE tx_receipt (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id          CHAR(64)    NOT NULL,
  tx_hash           CHAR(66)    NOT NULL,
  block_number      BIGINT      DEFAULT NULL,
  block_hash        CHAR(66)    DEFAULT NULL,
  contract_address  VARCHAR(66) DEFAULT NULL,
  tx_status         VARCHAR(10) DEFAULT NULL COMMENT '0x0 成功',
  gas_used          BIGINT      DEFAULT NULL,
  submitter_address VARCHAR(66) DEFAULT NULL,
  raw_receipt       JSON        DEFAULT NULL,
  receipt_time      DATETIME    DEFAULT NULL,
  created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_receipt_tx (tx_hash),
  KEY idx_receipt_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易回执';

-- -------------------------------------------------------------
-- 11. 操作审计（可选扩展）
-- -------------------------------------------------------------
CREATE TABLE audit_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     BIGINT UNSIGNED DEFAULT NULL,
  action      VARCHAR(50)  NOT NULL,
  target_type VARCHAR(30)  DEFAULT NULL,
  target_id   VARCHAR(50)  DEFAULT NULL,
  detail      JSON         DEFAULT NULL,
  ip          VARCHAR(45)  DEFAULT NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_audit_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计';

-- PoC 데이터베이스 정의서_2.md 8장 DDL 그대로 (ERD 변경 없음)
CREATE DATABASE IF NOT EXISTS tetra_poc
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE tetra_poc;

CREATE TABLE tenant (
  tenant_id      CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name           VARCHAR(100) NOT NULL,
  account_status VARCHAR(20)  NOT NULL DEFAULT 'INACTIVE',
  PRIMARY KEY (tenant_id),
  CONSTRAINT chk_tenant_id_format CHECK (tenant_id REGEXP '^[a-z0-9]{12}$'),
  CHECK (account_status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event (
  event_id          INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  tenant_id         CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name              VARCHAR(100)  NOT NULL,
  type              VARCHAR(20)   NOT NULL,
  start_at          DATETIME      NOT NULL,
  end_at            DATETIME      NOT NULL,
  hosting_start_at  DATETIME      NOT NULL,
  hosting_end_at    DATETIME      NOT NULL,
  endpoint_url      VARCHAR(2048) NOT NULL,
  event_status      VARCHAR(20)   NOT NULL DEFAULT 'SCHEDULED',
  hosting_status    VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
  live_path         VARCHAR(1024) NULL DEFAULT NULL,
  banner_image_path VARCHAR(1024) NOT NULL,
  public_key        VARCHAR(2048) NOT NULL,
  subdomain         VARCHAR(253)  NULL DEFAULT NULL,
  PRIMARY KEY (event_id),
  KEY idx_event_tenant (tenant_id),
  UNIQUE KEY uk_event_subdomain (subdomain),
  CONSTRAINT chk_event_period CHECK (end_at > start_at),
  CONSTRAINT chk_event_hosting_period CHECK (hosting_end_at > hosting_start_at),
  CONSTRAINT chk_event_tenant_id_format CHECK (tenant_id REGEXP '^[a-z0-9]{12}$'),
  CHECK (type IN ('COUPON','TIMESALE','LIVE_COMMERCE')),
  CHECK (event_status IN ('SCHEDULED','DEPLOYING','EXPIRED')),
  CHECK (hosting_status IN ('PENDING','HOSTING','ENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE coupon (
  coupon_id   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id    INT UNSIGNED NOT NULL,
  tenant_id   CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name        VARCHAR(100) NOT NULL,
  description VARCHAR(500) NOT NULL,
  stock_count INT UNSIGNED NOT NULL,
  PRIMARY KEY (coupon_id),
  KEY idx_coupon_event (event_id),
  KEY idx_coupon_tenant_event (tenant_id, event_id),
  CONSTRAINT chk_coupon_tenant_id_format CHECK (tenant_id REGEXP '^[a-z0-9]{12}$'),
  CONSTRAINT fk_coupon_event FOREIGN KEY (event_id) REFERENCES event (event_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE issuance_history (
  issuance_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id        CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id         INT UNSIGNED    NOT NULL,
  user_id          VARCHAR(128)    NOT NULL,
  ticket_number    INT UNSIGNED    NULL DEFAULT NULL,
  queue_entered_at DATETIME(3)     NOT NULL,
  served_at        DATETIME(3)     NULL DEFAULT NULL,
  result           VARCHAR(20)     NOT NULL DEFAULT 'IN_PROGRESS',
  PRIMARY KEY (issuance_id),
  UNIQUE KEY uk_issuance_event_user (event_id, user_id),
  KEY idx_issuance_tenant_event_entered (tenant_id, event_id, queue_entered_at),
  KEY idx_issuance_event_result (event_id, result),
  CONSTRAINT fk_issuance_event FOREIGN KEY (event_id) REFERENCES event (event_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CHECK (result IN ('IN_PROGRESS','SUCCESS','FAILED_SOLDOUT','ABANDONED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

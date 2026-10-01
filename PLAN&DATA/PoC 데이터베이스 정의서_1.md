# PoC 데이터베이스 정의서

Oct 1, 2026 · @박주연

## 목차

1. 개요
2. 테이블 요약
3. tenant 명세
4. event 명세
5. coupon 명세
6. issuance\_history 명세
7. 초기 데이터(Seed)
8. DDL
9. 명세서 대비 제외·변경 사항과 확인 필요 항목

## 1. 개요

ERD 4단계 명세서에서 PoC에 필요한 4개 테이블, 32개 컬럼만 골라 단일 MySQL DB로 정의했습니다.

| 항목 | 내용 |
| --- | --- |
| 기준 문서 | ERD 4단계 MySQL 물리 ERD 명세 (2026-09-29, 박주연) |
| 범위 | tenant, event, coupon, issuance\_history 4개 테이블 |
| DB 구성 | 단일 MySQL DB. 운영의 AWS RDS·온프레미스 분리를 하나로 합침 |
| DBMS / 엔진 | MySQL 8.0, InnoDB (명세서 제안값. CHECK 제약은 8.0.16부터 적용) |
| 문자셋 | utf8mb4 / utf8mb4\_0900\_ai\_ci |
| 저장 타임존 | KST (Asia/Seoul) |
| tenant\_id | CHAR(12), 문자셋 ascii(ascii\_bin). 앱이 소문자·숫자 12자 랜덤 문자열로 생성. 운영 명세의 BINARY(16) UUID와 다름(2026-10-01 변경) |
| enum 컬럼 | VARCHAR(20) + CHECK |
| FK 정책 | 같은 DB의 event 참조만 FK(ON DELETE / ON UPDATE RESTRICT). tenant\_id 참조는 운영과 동일하게 FK 없이 인덱스만 |

## 2. 테이블 요약

4개 테이블에 컬럼 32개를 두고, 시드 데이터는 12행(tenant 1, event 1, coupon 10, issuance\_history 0)입니다.

| 테이블 | PoC 컬럼 수 | 원본 컬럼 수 | 시드 행 수 | 원본 명세 위치 |
| --- | --- | --- | --- | --- |
| tenant | 3 | 11 | 1 | 온프레 `tenant` |
| event | 15 | 16 | 1 | AWS `event` 11개 + `event_page` 2개(live\_path, banner\_image\_path) + `event_auth_key` 1개(public\_key) + `event_resource` 1개(subdomain) |
| coupon | 6 | 6 | 10 | AWS `coupon` |
| issuance\_history | 8 | 8 | 0 | AWS `issuance_history` |

## 3. tenant 명세

tenant는 원본 11개 컬럼 중 PK, 이름, 계정상태 3개만 남겼고, 인덱스는 PK뿐입니다.

| 컬럼 | 한글명 | 물리 타입 | NULL | 기본값 | 키 / 제약 | 비고 |
| --- | --- | --- | --- | --- | --- | --- |
| tenant\_id | 테넌트ID | CHAR(12) ascii\_bin | NOT NULL | - | PK | 앱에서 소문자·숫자 12자 문자열 생성 |
| name | 테넌트명 | VARCHAR(100) | NOT NULL | - |  |  |
| account\_status | 계정상태 | VARCHAR(20) | NOT NULL | 'INACTIVE' | CHECK IN ('ACTIVE','INACTIVE') | 이벤트 생성 시 ACTIVE로 전환 |

## 4. event 명세

event는 원본 `event`의 11개 컬럼에 다른 테이블에서 가져온 4개 컬럼(12\~15번)을 합친 15개입니다.

| # | 컬럼 | 한글명 | 물리 타입 | NULL | 기본값 | 키 / 제약 | 원본 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | event\_id | 이벤트ID | INT UNSIGNED | NOT NULL | AUTO\_INCREMENT | PK | event |  |
| 2 | tenant\_id | 테넌트ID | CHAR(12) ascii\_bin | NOT NULL | - | 참조: tenant.tenant\_id (FK 없음) | event | 인덱스 idx\_event\_tenant |
| 3 | name | 이벤트명 | VARCHAR(100) | NOT NULL | - |  | event |  |
| 4 | type | 이벤트유형 | VARCHAR(20) | NOT NULL | - | CHECK IN ('COUPON','TIMESALE','LIVE\_COMMERCE') | event | 1차는 COUPON만 |
| 5 | start\_at | 시작일시 | DATETIME | NOT NULL | - |  | event |  |
| 6 | end\_at | 종료일시 | DATETIME | NOT NULL | - |  | event |  |
| 7 | hosting\_start\_at | 호스팅시작일시 | DATETIME | NOT NULL | - |  | event |  |
| 8 | hosting\_end\_at | 호스팅종료일시 | DATETIME | NOT NULL | - |  | event |  |
| 9 | endpoint\_url | 엔드포인트URL | VARCHAR(2048) | NOT NULL | - |  | event |  |
| 10 | event\_status | 이벤트상태 | VARCHAR(20) | NOT NULL | 'SCHEDULED' | CHECK IN ('SCHEDULED','DEPLOYING','EXPIRED') | event | 초기값은 명세서 제안 |
| 11 | hosting\_status | 호스팅상태 | VARCHAR(20) | NOT NULL | 'PENDING' | CHECK IN ('PENDING','HOSTING','ENDED') | event | 초기값은 명세서 제안 |
| 12 | live\_path | 라이브경로 (정적 페이지 S3 URL) | VARCHAR(1024) | NULL | NULL |  | event\_page | 게시 전엔 NULL. 매핑 확인 필요(9장) |
| 13 | banner\_image\_path | 배너이미지경로 (배너 이미지 URL) | VARCHAR(1024) | NOT NULL | - |  | event\_page | 실제 파일은 S3 |
| 14 | public\_key | 공개키 (이벤트 인증키) | VARCHAR(2048) | NOT NULL | - |  | event\_auth\_key | RS256 공개키만 저장. 개인키는 저장 안 함 |
| 15 | subdomain | 서브도메인 | VARCHAR(253) | NULL | NULL | UK uk\_event\_subdomain | event\_resource | NULL은 중복 허용 |

제약과 인덱스는 아래 4개입니다.

| 이름 | 종류 | 대상 | 비고 |
| --- | --- | --- | --- |
| PRIMARY | PK | event\_id |  |
| idx\_event\_tenant | 일반 인덱스 | tenant\_id | 크로스 DB 참조 인덱스. 원본 `idx_event_tenant_applied`(tenant\_id, applied\_at)에서 applied\_at을 뺀 형태 |
| uk\_event\_subdomain | UNIQUE | subdomain | 원본 이름은 `uk_event_resource_subdomain`(event\_resource) |
| chk\_event\_period | CHECK | end\_at > start\_at | 원본 그대로 |
| chk\_event\_hosting\_period | CHECK | hosting\_end\_at > hosting\_start\_at | 원본 그대로 |

## 5. coupon 명세

coupon은 원본 6개 컬럼을 전부 유지하고, event를 참조하는 FK 1개와 인덱스 2개를 둡니다.

| 컬럼 | 한글명 | 물리 타입 | NULL | 기본값 | 키 / 제약 | 비고 |
| --- | --- | --- | --- | --- | --- | --- |
| coupon\_id | 쿠폰ID | INT UNSIGNED | NOT NULL | AUTO\_INCREMENT | PK |  |
| event\_id | 이벤트ID | INT UNSIGNED | NOT NULL | - | FK fk\_coupon\_event → event.event\_id | ON DELETE / UPDATE RESTRICT |
| tenant\_id | 테넌트ID | CHAR(12) ascii\_bin | NOT NULL | - | 참조: tenant.tenant\_id (FK 없음) | 비정규화 |
| name | 쿠폰명 | VARCHAR(100) | NOT NULL | - |  |  |
| description | 설명 | VARCHAR(500) | NOT NULL | - |  | 화면 노출 문구 |
| stock\_count | 재고수량 | INT UNSIGNED | NOT NULL | - |  | 신청 시점 기준 재고. 실시간 차감은 Redis |

| 인덱스 | 컬럼 | 용도 |
| --- | --- | --- |
| idx\_coupon\_event | event\_id | FK 겸용. 이벤트별 쿠폰 목록 |
| idx\_coupon\_tenant\_event | tenant\_id, event\_id | 테넌트 격리 조회 |

이벤트당 쿠폰 최대 10종 제한은 DB 제약이 아니라 앱에서 검증합니다. 시드 10행이 그 상한과 같습니다.

## 6. issuance\_history 명세

issuance\_history는 원본 8개 컬럼을 전부 유지하고, 데이터 없이 빈 테이블로 만듭니다.

PoC는 발급 결과가 확정될 때 행을 **1회 INSERT**합니다(성공·품절 모두, 2026-10-01 확정). 운영 설계의 "진입 시 INSERT(진행중) 후 UPDATE"는 PoC에서 쓰지 않습니다. 이에 따라 `result`는 INSERT 때 `SUCCESS` 또는 `FAILED_SOLDOUT`을 항상 명시하고, 기본값 `IN_PROGRESS`와 `ABANDONED`, `served_at`은 PoC에서 사용하지 않습니다. DDL은 원본 그대로 두고 PoC 종료 후 수정합니다. `coupon_id`는 두지 않습니다. 쿠폰이 이벤트 단위로 일괄 발급되어 `event_id`로 충분하기 때문입니다.

| 컬럼 | 한글명 | 물리 타입 | NULL | 기본값 | 키 / 제약 | 비고 |
| --- | --- | --- | --- | --- | --- | --- |
| issuance\_id | 발급이력ID | BIGINT UNSIGNED | NOT NULL | AUTO\_INCREMENT | PK |  |
| tenant\_id | 테넌트ID | CHAR(12) ascii\_bin | NOT NULL | - | 참조: tenant.tenant\_id (FK 없음) | 비정규화 |
| event\_id | 이벤트ID | INT UNSIGNED | NOT NULL | - | FK fk\_issuance\_event → event.event\_id | 핫패스 FK. ON DELETE / UPDATE RESTRICT |
| user\_id | 사용자ID | VARCHAR(128) | NOT NULL | - |  | 테넌트가 부여한 외부 식별자. 최대 길이 128은 명세서 가정 |
| ticket\_number | 티켓번호 | INT UNSIGNED | NULL | NULL |  |  |
| queue\_entered\_at | 대기열진입일시 | DATETIME(3) | NOT NULL | - |  | 세션 생성 시각과 동일. 1회 INSERT라 세션(Redis)에 저장해 둔 값을 사용 |
| served\_at | 서빙일시 | DATETIME(3) | NULL | NULL |  | 발급 페이지 도달 시각. PoC 미사용(NULL) |
| result | 발급결과 | VARCHAR(20) | NOT NULL | 'IN\_PROGRESS' | CHECK IN ('IN\_PROGRESS','SUCCESS','FAILED\_SOLDOUT','ABANDONED') | PoC는 `SUCCESS`·`FAILED_SOLDOUT`만 기록. `IN_PROGRESS`·`ABANDONED` 미사용 |

| 인덱스 | 컬럼 | 용도 |
| --- | --- | --- |
| uk\_issuance\_event\_user | event\_id, user\_id | **UNIQUE**. 이벤트당 사용자 1행. 사용자별 조회, FK 겸용 |
| idx\_issuance\_tenant\_event\_entered | tenant\_id, event\_id, queue\_entered\_at | 발급 결과 조회 API 페이징 |
| idx\_issuance\_event\_result | event\_id, result | 이벤트별 성공·실패 집계 |

`(event_id, user_id)`는 UNIQUE입니다(2026-10-01 확정). 사용자당 이력 행이 이벤트마다 1개로 제한되어, 재시도로 INSERT가 중복 실행돼도 행이 늘지 않습니다. 앱은 중복 키 오류(1062)를 "이미 기록됨"으로 처리합니다. 재진입 시 행이 여러 개 생긴다고 가정한 운영 명세(ERD 4단계 7장 #2)와 다릅니다.

INSERT가 몰리는 테이블이라 PK 외 인덱스 3개가 쓰기 비용이 됩니다. UNIQUE 인덱스는 중복 검사 비용이 더해집니다. 부하 테스트 후 `idx_issuance_event_result`부터 줄일지 판단하라는 것이 명세서의 권고입니다.

## 7. 초기 데이터(Seed)

테넌트 1행, 이벤트 1행, 쿠폰 10행을 넣고 발급이력은 비워 둡니다.

### tenant (1행)

| tenant\_id | name | account\_status |
| --- | --- | --- |
| poctenant001 | (주) PoC 테스트사 | ACTIVE |

### event (1행)

| 컬럼 | 값 | 비고 |
| --- | --- | --- |
| event\_id | 1 |  |
| tenant\_id | poctenant001 | 위 테넌트와 동일. 값 확인 필요(9장) |
| name | PoC 쿠폰 발급 이벤트 |  |
| type | COUPON |  |
| start\_at | 2026-09-29 10:00:00 | 입력값 46294.41667(엑셀 일련값)을 변환 |
| end\_at | 2026-10-29 10:00:00 | 입력값 46324.41667을 변환. 시작 + 30일 |
| hosting\_start\_at | 2026-09-26 10:00:00 | start\_at − 3일 |
| hosting\_end\_at | 2026-10-30 10:00:00 | end\_at + 1일 |
| endpoint\_url | '' | 빈 값. NOT NULL이라 빈 문자열 |
| event\_status | DEPLOYING |  |
| hosting\_status | HOSTING |  |
| live\_path | NULL | 빈 값. NULL 허용 컬럼 |
| banner\_image\_path | '' | 빈 값. NOT NULL이라 빈 문자열 |
| public\_key | '' | 빈 값. NOT NULL이라 빈 문자열 |
| subdomain | NULL | 빈 값. UNIQUE 컬럼이라 NULL 사용 |

### coupon (10행)

coupon\_id는 1\~10이고, 쿠폰명만 번호가 다릅니다.

| coupon\_id | name |
| --- | --- |
| 1 | PoC 쿠폰 1 |
| 2 | PoC 쿠폰 2 |
| 3 | PoC 쿠폰 3 |
| 4 | PoC 쿠폰 4 |
| 5 | PoC 쿠폰 5 |
| 6 | PoC 쿠폰 6 |
| 7 | PoC 쿠폰 7 |
| 8 | PoC 쿠폰 8 |
| 9 | PoC 쿠폰 9 |
| 10 | PoC 쿠폰 10 |

10행 모두 event\_id는 1, tenant\_id는 위 테넌트 ID(`poctenant001`), description은 'PoC 쿠폰입니다.', stock\_count는 10입니다.

### issuance\_history (0행)

빈 테이블입니다.

## 8. DDL

아래 SQL을 위에서 아래로 실행하면 4개 테이블과 시드 12행이 만들어집니다. DB명 `tetra_poc`는 가칭입니다.

### 테이블 생성

```sql
CREATE DATABASE IF NOT EXISTS tetra_poc
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE tetra_poc;

CREATE TABLE tenant (
  tenant_id      CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name           VARCHAR(100) NOT NULL,
  account_status VARCHAR(20)  NOT NULL DEFAULT 'INACTIVE',
  PRIMARY KEY (tenant_id),
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
```

### Seed INSERT

```sql
SET @tenant_id = 'poctenant001';

INSERT INTO tenant (tenant_id, name, account_status)
VALUES (@tenant_id, '(주) PoC 테스트사', 'ACTIVE');

INSERT INTO event (event_id, tenant_id, name, type, start_at, end_at, hosting_start_at, hosting_end_at,
                   endpoint_url, event_status, hosting_status, live_path,
                   banner_image_path, public_key, subdomain)
VALUES (1, @tenant_id, 'PoC 쿠폰 발급 이벤트', 'COUPON',
        '2026-09-29 10:00:00', '2026-10-29 10:00:00',
        '2026-09-26 10:00:00', '2026-10-30 10:00:00',
        '', 'DEPLOYING', 'HOSTING', NULL, '', '', NULL);

INSERT INTO coupon (coupon_id, event_id, tenant_id, name, description, stock_count) VALUES
(1,  1, @tenant_id, 'PoC 쿠폰 1',  'PoC 쿠폰입니다.', 10),
(2,  1, @tenant_id, 'PoC 쿠폰 2',  'PoC 쿠폰입니다.', 10),
(3,  1, @tenant_id, 'PoC 쿠폰 3',  'PoC 쿠폰입니다.', 10),
(4,  1, @tenant_id, 'PoC 쿠폰 4',  'PoC 쿠폰입니다.', 10),
(5,  1, @tenant_id, 'PoC 쿠폰 5',  'PoC 쿠폰입니다.', 10),
(6,  1, @tenant_id, 'PoC 쿠폰 6',  'PoC 쿠폰입니다.', 10),
(7,  1, @tenant_id, 'PoC 쿠폰 7',  'PoC 쿠폰입니다.', 10),
(8,  1, @tenant_id, 'PoC 쿠폰 8',  'PoC 쿠폰입니다.', 10),
(9,  1, @tenant_id, 'PoC 쿠폰 9',  'PoC 쿠폰입니다.', 10),
(10, 1, @tenant_id, 'PoC 쿠폰 10', 'PoC 쿠폰입니다.', 10);
```

## 9. 명세서 대비 제외·변경 사항과 확인 필요 항목

이 문서가 둔 가정 6가지 중 5번(issuance\_history 구조)은 2026-10-01에 확정했고 나머지 5가지는 확인이 필요합니다. 7·8번은 확인이 아니라 2026-10-01에 확정한 변경입니다. 제외한 컬럼과 제약은 아래 표에 정리했습니다.

### 확인 필요 항목

| # | 항목 | 이 문서의 처리 | 확인할 내용 |
| --- | --- | --- | --- |
| 1 | event에 합친 4개 컬럼 | 정적 페이지 S3 URL → `event_page.live_path`, 배너 이미지 URL → `event_page.banner_image_path`, 이벤트 인증키 → `event_auth_key.public_key`, 서브도메인 → `event_resource.subdomain`으로 매핑 | 매핑이 맞는지. 정적 페이지 S3 URL은 `event_resource.s3_bucket_url`, `event_page.staging_path`, `event_page.preview_url`도 후보 |
| 2 | 단일 DB | 4개 테이블을 한 DB에 두고 tenant\_id 참조(event, coupon, issuance\_history)는 FK 없이 인덱스만 | 같은 DB이니 tenant\_id에 FK를 걸지 |
| 3 | 빈 값 처리 | NOT NULL인 endpoint\_url, banner\_image\_path, public\_key는 빈 문자열 '', NULL 허용인 live\_path, subdomain은 NULL | NOT NULL을 NULL 허용으로 바꿀지 |
| 4 | 일시 변환 | 엑셀 일련값을 KST 일시로 변환 | 2026-09-29 10:00 \~ 2026-10-29 10:00이 맞는지 |
| 5 | issuance\_history 구조 | **확정(2026-10-01)**: 결과 확정 시 1회 INSERT. DDL은 명세서 그대로 두고 `result`는 `SUCCESS`·`FAILED_SOLDOUT`만 기록, `IN_PROGRESS`·`ABANDONED`·`served_at`은 PoC에서 미사용. `coupon_id`는 두지 않음 | PoC 종료 후 `IN_PROGRESS`·`ABANDONED` 제거와 `served_at` 처리를 수정. `coupon_id`는 쿠폰이 이벤트 단위로 일괄 발급되므로 불필요(확정) |
| 6 | event.tenant\_id 시드 값 | 시드 event의 tenant\_id를 시드 tenant와 같은 ID(`poctenant001`)로 입력 | 이 이벤트가 해당 테넌트 소속이 맞는지 |
| 7 | issuance\_history 사용자 중복 | **확정(2026-10-01)**: (event\_id, user\_id) UNIQUE(`uk_issuance_event_user`). 재진입으로 행이 여러 개 생긴다고 가정한 운영 명세와 다름 | 중복 키 오류를 앱이 어떻게 처리할지(무시 또는 기존 행 조회). 운영 명세에 반영할지는 PoC 종료 후 판단 |
| 8 | tenant\_id 형식 | **확정(2026-10-01)**: `CHAR(12)` ascii\_bin, 소문자·숫자 12자 랜덤 문자열. 운영 명세의 BINARY(16) UUID에서 변경. 4개 테이블의 tenant\_id 컬럼과 시드(`poctenant001`)에 반영 | 저장 크기는 16바이트에서 12바이트로 소폭 감소. 호스트 `{event id}.{tenant id}.루트도메인`에 들어가므로 소문자·숫자만 사용. JWT의 tenant\_id 클레임도 같은 형식으로 맞춰야 함. 길이(12)와 문자 구성은 임시 제안 |

### 제외한 컬럼과 함께 뺀 제약·인덱스

| 원본 테이블 | 제외한 컬럼 | 함께 뺀 제약·인덱스 |
| --- | --- | --- |
| tenant | root\_domain, created\_at, email, password, contact\_name, reset\_token, reset\_token\_expires\_at, email\_notification\_enabled | uk\_tenant\_email |
| event | application\_status, applied\_at, traffic\_plan\_code, approved\_by, approved\_at | idx\_event\_appstatus, idx\_event\_approved\_by, idx\_event\_traffic\_plan |
| event\_page | event\_id, tenant\_id, design\_mode, template\_id, status, staging\_path, preview\_url, created\_at | idx\_event\_page\_tenant, idx\_event\_page\_template, idx\_event\_page\_status, chk\_event\_page\_design |
| event\_auth\_key | key\_id, event\_id, tenant\_id, key\_version, issued\_at, revoked\_at, status | uk\_auth\_key\_event\_version, idx\_auth\_key\_event\_status, idx\_event\_auth\_key\_tenant, chk\_auth\_key\_revoked |
| event\_resource | event\_id, tenant\_id, s3\_bucket\_url | idx\_resource\_tenant |

`event_page`, `event_auth_key`, `event_resource`는 테이블째로 두지 않고 위 4개 컬럼만 event에 합쳤습니다. 이 때문에 이벤트당 인증키는 1개이고 키 버전·폐기 이력은 없습니다. 나머지 14개 테이블(operator, traffic\_plan, template, 상태 로그, 알림, 리포트 등)은 통째로 제외했습니다.

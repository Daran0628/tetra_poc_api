# Tetra Issuance Service — PoC 백엔드 개발 플랜

- 작성일: 2026-10-01
- 대상 독자: 이 디렉토리에서 코드를 작성할 담당자(Claude Code 세션 포함)
- 목적: 아래 범위·설계·결정 사항만으로 PoC 백엔드 구현을 바로 시작할 수 있도록, 지금까지 나온 모든 산출물(ADR 2건, PoC DB 정의서, API 정의서, 폴링 클라이언트 스펙, 이번 대화에서 확정한 사항)을 하나로 합쳐 정리한 문서입니다.
- 원본 문서가 서로 다른 시점에 쓰여 충돌하는 부분이 있어, **이 문서의 "확정 사항" 섹션이 항상 최신 기준**입니다. 원본 문서를 직접 열어볼 필요 없이 이 문서만 보고 구현을 시작하면 됩니다.

---

## 1. PoC 범위 (반드시 지킬 것)

### 포함

사용자 체감 흐름: **대기실 진입 → 번호표 발급 → 서빙 커서 폴링 → 쿠폰 발급**. 구현할 API는 6개입니다(2026-10-02 이벤트 정보 조회 추가).

| # | 메서드 | 경로 | 역할 |
|---|---|---|---|
| 1 | GET | `/api/issuance/session` | 테넌트 302 리다이렉트 수신 → JWT 검증 → 세션 쿠키 발급 |
| 2 | POST | `/api/issuance/events/{eventId}/ticket` | 번호표 채번 (대기실 진입 시 1회 호출) |
| 3 | GET | `/api/issuance/events/{eventId}/queue/cursor` | 서빙 커서 폴링 (전역 값 1개) |
| 4 | GET | `/api/issuance/events/{eventId}/coupons` | 쿠폰 목록 + 잔여 수량 |
| 5 | POST | `/api/issuance/events/{eventId}/coupons/claim` | 쿠폰 발급(원자적 재고 차감) |
| 6 | GET | `/api/issuance/events/{eventId}/info` | 이벤트 정보(이름·시작/종료 시각·배너·복귀 주소) — 02 대기방 카운트다운용 (2026-10-02 확정) |

### 제외 (이번 PoC에서 절대 손대지 않음)

- Tetra Portal, Operations Console 전체 (테넌트 등록/신청/승인, 리포트, 알림, 모니터링 등)
- 테넌트/이벤트 프로비저닝 경로(GitOps, Terraform, S3/키페어 생성, `/provisioning-status` 등)
- 발급 화면(02·03·04) 자체의 정적 페이지 서빙(CloudFront/S3) — ALB·Issuance Service를 거치지 않으므로 이 서비스 코드와 무관
- "발급 이력 조회"·"발급 결과 집계" API 2종(API 정의서에 초안만 있음) — 저빈도 관리자용이라 PoC 트래픽 경로가 아님. 나중에 Operations Console 작업 시 별도로 구현

이 범위 경계는 `ADR-0001-Issuance-Service-POC-트래픽-아키텍처.md`에서 확정됐고 바뀐 내용이 없습니다(2026-10-01 ADR 개정은 진입 도메인·대기열 방식만 변경).

---

## 2. 인프라 경로 — 2026-10-01 ADR-0001 개정 반영

ADR-0001 최초 버전은 **API 별도 도메인(`issuance.tetra.io`) + Redis ZSET 대기열**을 전제로 쓰여 있었습니다. 2026-10-01에 ADR-0001·ADR-0002를 개정해 아래 최신 내용을 반영했습니다. 그래도 충돌하는 부분이 있으면 이 표를 따릅니다.

| 항목 | 이전(구) | 현재(신) |
|---|---|---|
| 진입 도메인 | 화면 `*.{tenant_id}.tetra.io`(CloudFront→S3), API `issuance.tetra.io`(ALB 직접) — 진입점 2개 | **단일 호스트 `{event_slug}.{tenant_id}.루트도메인`** (예 `k7f2q9.poctenant001.gamza-dev.shop`) — CloudFront가 경로로 분기(`/api/*` → ALB, 그 외 → S3). `event_slug`는 이벤트 승인 워크플로우가 만드는 UX용 해시값이고 `event.subdomain`에 `{event_slug}.{tenant_id}`로 저장, 별도 컬럼 없음. API는 slug를 쓰지 않고 숫자 `event_id`만 받음(2026-10-02, 프런트 담당 확인) |
| 쿠키·CORS | 출처가 달라 CORS·credentials 처리 필요 | 같은 출처 — host-only 쿠키 + SameSite=Lax 그대로, CORS 불필요 |
| 대기 순번 | ZSET(`ZADD`/`ZRANK`/`ZPOPMIN`) | 전역 카운터(`INCR`) 기반 번호표 + 전역 서빙 커서 |
| 순번 응답 | 개인화된 응답(1~2초 폴링) | 이벤트당 전역 값 1개(`{"cursor": number}`) — CloudFront에서 1초 캐시, 인증 없음 |
| 폴링 간격 | 고정 1~2초 | 클라이언트가 `gap = 내번호 - 받은 cursor 최댓값` 기준으로 적응형 결정(1~10초) |
| SPA 에러 복구 | distribution 전체 custom error response(403/404 → index.html) | S3 behavior의 CloudFront Function이 확장자 없는 경로를 `/index.html`로 재작성. custom error response는 사용하지 않음 |

CloudFront behavior 요구사항(인프라 담당, 백엔드 동작의 전제 — 2026-10-01 커서 캐싱 구체화 반영):

| 우선순위 | behavior | 오리진 | 캐시 | 비고 |
|---|---|---|---|---|
| 1 | `/api/issuance/events/*/queue/cursor` | ALB | cache policy TTL은 오리진 헤더를 따름(min 0 ~ max 2초). 캐시 키는 **경로만**(쿠키·쿼리·헤더 제외) | `/api/*`보다 반드시 앞에 배치 |
| 1-1 | `/api/issuance/events/*/info` | ALB | 오리진 헤더(`public, max-age=0, s-maxage=60`)를 따름. 캐시 키는 경로만 | `/api/*`보다 앞에 배치 (2026-10-02 추가, 이벤트 정보 API) |
| 2 | `/api/*` | ALB | 캐시 안 함 | 쿠키를 ALB까지 전달 |
| 기본 | `*` | S3(OAC) | 정적 자산 정책 | viewer-request CloudFront Function으로 SPA 경로 재작성 |

- **custom error response(403/404 → index.html)는 쓰지 않습니다.** distribution 전체에 적용돼 `/api/*`의 404/5xx까지 `index.html` + 200으로 바뀌고, 프런트의 `res.ok` 체크가 무력화되기 때문입니다.
- 에러 응답 캐시 TTL(error caching minimum TTL)은 0~1초.
- ALB는 CloudFront 경유 요청만 받도록 제한.
- `/api/issuance/session` 요청의 쿼리스트링(`JWT=...`)은 CloudFront·ALB access log에서 마스킹 권장(4-1절).
- **오리진이 받는 Host는 사용자가 보는 호스트가 아닙니다(2026-10-01, 프론트 담당 공유).** CloudFront가 ALB로 넘길 때 Host가 오리진 주소(dev: `origin.gamza-dev.shop`)로 바뀌어 들어옵니다. 그래서 백엔드는 **요청의 Host·scheme으로 주소나 쿠키 속성을 만들지 않습니다.**
  - 302 `Location`은 **상대 경로**(`/...`)로만 줍니다. Host로 절대 주소를 만들면 브라우저가 오리진 주소로 이동해 버립니다.
  - `Set-Cookie`에 **`Domain` 속성을 넣지 않습니다**(host-only). 브라우저가 사용자가 보는 호스트에 쿠키를 저장합니다.
  - `Secure` 속성은 요청 scheme(`request.isSecure()`)이 아니라 설정값(`tetra.session.cookie-secure`)으로 붙입니다.
  - Spring Boot 내장 Tomcat은 기본값(`server.tomcat.use-relative-redirects: false`)이면 `sendRedirect("/")`도 Host를 붙인 절대 주소로 바꿉니다. `application.yml`에서 `true`로 고정했습니다.
  - 두 항목은 범위 정의서 CloudFront 검증 항목에도 추가됨(프론트 담당).

그 밖의 인프라 배치(ElastiCache, RDS MySQL, EKS Namespace=테넌트 단위, 정적 페이지 S3 분리 등)는 ADR-0001 그대로 유효합니다. 다만 **로컬 개발 단계에서는 ElastiCache/RDS 대신 docker-compose로 띄운 Redis(Valkey)·MySQL을 씁니다** (8절 참고). 로컬에는 CloudFront가 없으므로 앱에 직접 접속합니다.

레이어드 아키텍처(`Filter → Controller → Service → Repository/Client`, `JwtAuthFilter`·`SessionAuthFilter`, `GlobalExceptionHandler`+`ErrorCode` Enum, `ApiResponse<T>` 공통 응답)는 ADR-0002(2026-10-01 개정본) 그대로 적용합니다.

---

## 3. 데이터 모델 (`PoC 데이터베이스 정의서_2.md` 그대로)

**기준 문서는 최신 수정본 `PoC 데이터베이스 정의서_2.md`(2026-10-01)입니다.** (`_1.md`도 대체됨) 이전 PDF(`PoC 데이터베이스 정의서.pdf`)는 더 이상 기준이 아닙니다. DDL은 이 문서를 그대로 쓰고, 앱 쪽에서 임의로 바꾸지 않습니다.

4개 테이블·32개 컬럼, 단일 MySQL DB(`tetra_poc`, utf8mb4, InnoDB). `event_id`는 `INT UNSIGNED AUTO_INCREMENT`.

이전 PDF 대비 변경점(DB 정의서 9장 #5·#7·#8, `_2.md`에서 형식 CHECK 추가):

| 항목 | 이전(PDF) | 현재(정의서_2) | 앱 영향 |
|---|---|---|---|
| `tenant_id` 형식 | `BINARY(16)` UUID (`UUID_TO_BIN`) | **`CHAR(12)` ascii_bin**, 소문자·숫자 12자 랜덤 문자열 (시드 `poctenant001`) | 변환 함수 없이 문자열 그대로 사용. 호스트 `{이벤트}.{tenant_id}.tetra.io`에 들어가는 값. JWT `tenant_id` 클레임도 같은 형식(`^[a-z0-9]{12}$`) |
| `event.tenant_id` | 없음 | **추가**(NOT NULL, `idx_event_tenant`, FK 없음) | 세션 API에서 JWT의 `tenant_id`가 `event.tenant_id`와 같은지 확인 가능 |
| `issuance_history (event_id, user_id)` | 일반 인덱스 `idx_issuance_event_user` | **UNIQUE** `uk_issuance_event_user` — 이벤트당 사용자 1행 | INSERT 중복 키 오류(1062)는 "이미 기록됨"으로 처리 |
| `issuance_history.served_at` | 발급 페이지 도달 시각 | **PoC 미사용(NULL)** | claim INSERT 때 넣지 않음 |
| `tenant_id` 형식 강제 (`_2.md`) | 없음 | **`CHECK (tenant_id REGEXP '^[a-z0-9]{12}$')`** — tenant·event·coupon에만. `issuance_history`는 INSERT 핫패스라 CHECK 없이 앱이 보장 | `issuance_history`에 넣는 `tenant_id`는 서명 검증된 JWT 값(형식 검증 + `event.tenant_id` 일치 확인 통과)이라 앱에서 보장됨 |
| `coupon_id` | 확인 필요 | **두지 않음(확정)** — 이벤트 단위 일괄 발급이라 `event_id`로 충분 | 변경 없음 |

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
```

**쓰기 패턴 (DB 정의서 6장과 일치)**: `issuance_history`는 세션 발급·번호표 발급 단계에서는 MySQL을 전혀 건드리지 않습니다. `tenant_id`/`event_id`/`user_id`/`ticket_number`/`queue_entered_at`은 그동안 Redis 세션 해시에 들고 있다가, **claim이 성공 또는 품절로 확정되는 순간 딱 1번 INSERT**합니다.
- `result`는 INSERT 때 `SUCCESS` 또는 `FAILED_SOLDOUT`을 항상 명시합니다. 기본값 `IN_PROGRESS`와 `ABANDONED`는 쓰지 않습니다.
- `served_at`은 PoC에서 쓰지 않습니다(NULL).
- `queue_entered_at`은 **번호표 발급 시각**입니다(재발급하면 새 시각으로 갱신, D9). DB 정의서 6장 비고의 "세션 생성 시각과 동일"과 다르며, 이 문서를 따릅니다(2026-10-01 확정).
- `(event_id, user_id)` UNIQUE라 재시도로 INSERT가 중복 실행돼도 행이 늘지 않습니다. 중복 키 오류(1062)는 에러가 아니라 "이미 기록됨"으로 처리합니다. 평소에는 claim Lua의 `claim:done` 키가 중복을 먼저 막으므로, 1062는 재시도·Redis 키 유실 같은 예외 상황에서만 발생합니다.
- DDL의 `IN_PROGRESS`·`ABANDONED`·`served_at` 정리는 PoC 종료 후 DB 담당이 수정합니다. 이탈률 집계가 필요해지면 별도 설계가 필요합니다.

`coupon.stock_count`는 **신청 시점 기준값(고정)**이며 실시간 차감은 Redis에서만 일어납니다 — 이 컬럼을 UPDATE하는 코드는 만들지 않습니다(5절 참고).

### 시드 데이터

- `tenant` 1행, `event` 1행(`type='COUPON'`), `coupon` 10행(`coupon_id` 1~10, 각 `stock_count=10` → 전체 재고 100장). `issuance_history`는 빈 테이블로 시작.
- 실 운영 재고가 100장 수준이고 목표 동시접속은 5만 명이므로, **대다수는 품절 응답을 받는 것이 정상 시나리오**입니다. 이건 버그가 아니라 의도된 테스트 조건입니다.
- 정확한 INSERT 문은 `PoC 데이터베이스 정의서_2.md` 8장 "Seed INSERT" 참고(그대로 사용). 시드 테넌트·이벤트의 `tenant_id`는 `poctenant001`.
- **ERD는 앱 쪽에서 변경하지 않습니다(2026-10-01 확정).** DB 정의서가 바뀌면 그 문서를 따릅니다. 흐름상 쓰지 않는 컬럼은 NULL 허용이면 `NULL`, NOT NULL이면 Seed와 같이 빈 문자열 `''`로 둡니다.
- Seed의 `event.public_key`는 빈 값이므로, 로컬에서는 `db/init/03_local_test_key.sql`로 테스트 공개키를 UPDATE해서 JWT 검증에 씁니다(Seed 원본은 수정하지 않음).

---

## 4. API 계약 상세

공통:

- 인증은 쿠키 세션(HttpOnly·Secure·SameSite=Lax, host-only). **단, 4-3 커서 API는 예외로 인증하지 않습니다**(2026-10-01).
- 화면과 API가 같은 호스트라 CORS 설정은 두지 않습니다(2절).
- 모든 응답은 ADR-0002의 `ApiResponse<T>` 포맷 + `ErrorCode` Enum으로 통일.
- **에러 응답은 정확한 HTTP 상태 코드 + `Cache-Control: no-store`**로 내려줍니다(CDN·브라우저가 에러를 캐시하지 않도록). 4-3 성공 응답을 뺀 나머지 성공 응답도 캐시 대상이 아닙니다.
- 응답 형식 (구현 기준, 2026-10-01): 성공 `{"success":true,"data":{...}}`, 실패 `{"success":false,"error":{"code":"...","message":"..."}}`. 아래 각 API의 응답 예시는 `data` 안의 내용입니다.
- 에러 코드 (`common/ErrorCode.java`):

  | HTTP | 코드 | 상황 |
  |---|---|---|
  | 400 | `INVALID_REQUEST` | 경로 값 형식 오류(예: eventId가 숫자 아님) |
  | 400 | `JWT_MISSING` / `JWT_MALFORMED` | 토큰 없음 / 형식·필수 클레임 오류, `exp`가 너무 먼 미래 |
  | 400 | `INVALID_TENANT_ID` / `USER_ID_TOO_LONG` | `tenant_id` 형식 오류 / `user_id` 128자 초과 |
  | 401 | `JWT_INVALID_SIGNATURE` / `JWT_EXPIRED` / `JWT_REUSED` | 서명 불일치·허용 안 된 알고리즘 / 만료 / 이미 쓴 토큰(jti) |
  | 401 | `SESSION_NOT_FOUND` | 세션 쿠키 없음·세션 만료 |
  | 403 | `TENANT_MISMATCH` / `SESSION_EVENT_MISMATCH` | 다른 테넌트의 토큰 / 다른 이벤트의 세션 |
  | 404 | `EVENT_NOT_FOUND` / `NOT_FOUND` | 없는 이벤트·공개키 미등록 이벤트 / 없는 경로 |
  | 405 | `METHOD_NOT_ALLOWED` | 허용 안 된 메서드 |
  | 409 | `EVENT_NOT_STARTED` | 이벤트 시작 전 번호표 요청 |
  | 409 | `EVENT_ENDED` | 이벤트 종료(`end_at`) 후 번호표·claim 요청 (2026-10-02 구현) |
  | 409 | `TICKET_REQUIRED` / `ALREADY_CLAIMED` | 번호표 없이 claim / 이미 claim 처리됨(claim과 **번호표** 둘 다에서 옴) |
  | 500 | `INTERNAL_ERROR` | 예상하지 못한 오류(내부 정보는 응답에 넣지 않음) |

### 4-0. `GET /api/issuance/events/{eventId}/info` — 이벤트 정보 (2026-10-02 확정)

- 목적: 프런트는 모든 이벤트가 같은 빌드(`dist/`)를 쓰므로 이벤트명·시각을 코드에 넣을 수 없다. 02 대기방이 이 API로 받아 카운트다운을 그린다. 서버의 시작·종료 판정(`EVENT_NOT_STARTED`·`EVENT_ENDED`)과 같은 DB 값을 보게 된다. 정식 프로젝트에서도 쓰는 API.
- **인증 없음** — 이벤트 공통 공개 정보. `SessionAuthFilter`가 커서 API처럼 이 경로를 세션 조회 없이 통과시킨다.
- 응답 (`data`):
  ```json
  {"eventId":1,"name":"PoC 쿠폰 발급 이벤트",
   "startAt":"2026-09-29T10:00:00+09:00","endAt":"2026-10-29T10:00:00+09:00",
   "bannerUrl":"","returnUrl":""}
  ```
  - `startAt`·`endAt`: DB의 KST 값을 `+09:00`이 붙은 ISO 문자열로.
  - `bannerUrl` = `event.banner_image_path`, `returnUrl` = `event.endpoint_url`. **빈 문자열일 수 있다**(시드는 빈 값). 나머지는 항상 값이 있다.
  - 지터 상한은 넣지 않는다(프런트 설정).
- 에러: 404 `EVENT_NOT_FOUND`, 400 `INVALID_REQUEST` (모두 `no-store`).
- 캐시: 성공 응답 `Cache-Control: public, max-age=0, s-maxage=60` — 5만 명이 02를 열 때마다 부르므로 CloudFront에 캐시(behavior 1-1, 2절). 시작 시각을 바꾸면 최대 60초 뒤 반영. 캐시 시간은 values 변수.
- 요청마다 MySQL 접근 없음 — 기동 시 이벤트 메타 캐시에서 응답(이름·배너·복귀 주소도 캐시에 추가).
- 경로를 `/{eventId}`가 아니라 `/{eventId}/info`로 둔 이유: CloudFront 경로 패턴의 `*`는 `/`도 포함해 맞추므로 `/api/issuance/events/*`로는 이 API만 골라 캐시할 수 없다(번호표·claim까지 캐시됨).

### 4-1. `GET /api/issuance/session`

- 쿼리 파라미터: `JWT`(RS256) — claims: `user_id`, `tenant_id`, `event_id`, `exp`(1~2분), `jti`
- **JWT 구성 방식 (2026-10-01 확정)**
  - **알고리즘 RS256** (대칭키 HS256 아님): 테넌트(서명자)와 Tetra(검증자)의 신뢰 경계를 분리해야 하는 멀티테넌트 구조라, 서명용 개인키와 검증용 공개키를 나눔. Tetra DB에는 공개키(`event.public_key`)만 저장 — DB가 뚫려도 토큰 위조 불가. HS256이었다면 대칭키를 Tetra와 공유해야 해서 DB 유출 시 전체 테넌트 토큰 위조 가능.

    | 클레임 | 역할 |
    |---|---|
    | `user_id` | 사용자 식별 — 세션·발급 이력에 그대로 연결 |
    | `tenant_id` | 멀티테넌트 격리. 형식은 소문자·숫자 12자(`^[a-z0-9]{12}$`, DB `CHAR(12)`), `event.tenant_id`와 일치해야 함 |
    | `event_id` | 토큰 유효 범위를 해당 이벤트로 한정 |
    | `exp` | 1~2분으로 짧게 — "입장권" 성격 토큰이라 탈취 시 재사용 위험을 줄임 |
    | `jti` | 토큰 1회성 보장. `SET jti:{jti} 1 NX EX <exp까지 남은 초 + 시계 오차>` — exp만으로는 짧은 시간 내 재전송 공격을 못 막기 때문 |

  - 만료 판정은 테넌트 서버와의 시계 오차 `jwt.clock-skew`(5초)를 허용하고, `exp`가 지금부터 `jwt.max-ttl`(5분)보다 멀면 거절(1년짜리 같은 비정상 토큰 차단).
  - `iss`/`aud`는 PoC에서 생략 — 이벤트별로 공개키가 이미 분리돼 있어 "누가 발급했는지"가 키 자체로 증명됨.
  - **구현 시 주의 (검증 순서)**: 검증용 공개키가 이벤트별이라, 서명 검증 전에 토큰의 `event_id`를 먼저 읽어(서명 미검증 상태) 해당 이벤트의 `public_key`를 고른 뒤 서명을 검증함. 미검증 상태로 읽은 값은 키 선택에만 쓰고, 세션에 넣는 값은 반드시 서명 검증이 끝난 클레임에서 가져옴. 해당 이벤트가 없거나 `public_key`가 비어 있으면 거절.
  - **구현 시 주의 (알고리즘 고정)**: 헤더의 `alg`를 믿지 않고 RS256만 허용. `none`이나 HS256 토큰은 거절(공개키를 HMAC 비밀키로 오용하는 알고리즘 혼동 공격 방지).
  - **전달 방식 (2026-10-02 확정)**: 테넌트 페이지가 히든폼을 `method="GET"`, 필드 `JWT`로 페이지 이동 제출 → 쿼리 파라미터(`?JWT=...`)로 전달됨. POST는 지원하지 않음(보내면 검증 후 405, 토큰 소모 — Next Plan N5). exp 1~2분 + jti 1회성으로 위험도는 낮지만, 이 경로는 access log에서 쿼리스트링 마스킹 권장 — 앱 로그(요청 로깅 시 `JWT` 값 마스킹)와 인프라 로그(CloudFront·ALB access log) 모두 해당.
- 처리 순서:
  1. JWT 서명·만료 검증 (위 검증 순서·알고리즘 고정 규칙 적용)
  1-1. 클레임 `tenant_id`가 해당 이벤트의 `event.tenant_id`와 같은지 확인 — 다르면 거절(다른 테넌트 토큰으로 이 이벤트에 들어오는 것 차단, DB 정의서 수정본에서 `event.tenant_id` 추가로 가능해짐)
  2. `SET jti:{jti} 1 NX EX <exp까지 남은 초 + 시계 오차>` — 실패하면(이미 존재) 재사용 공격으로 간주, 401 `JWT_REUSED`
  3. 같은 `event_id` + `user_id`로 기존 세션이 있는지 확인(`session:idx:{eventId}:{userId}` → 세션이 살아 있는지까지) → 있으면 재사용(진입 카운터 증가 없음), 없으면 신규 세션 생성(TTL 2시간) + 진입 카운터 `entry:count:{eventId}` +1. 조회와 생성은 원자적이지 않음 — 정상 사용자 전제(진행 문서 Next Plan N1)
  4. `Set-Cookie`(세션ID) + 302 (JWT 없는 대기방 주소로). 목적지는 values 파일 변수 `tetra.session.redirect-url` = **`/?event={eventId}`** (2026-10-02 확정). 도메인 첫 라벨은 UX용 해시값(event slug, 예 `k7f2q9`)이라 프런트가 숫자 `event_id`를 알 수 없으므로 쿼리로 넘긴다 — 프런트는 `?event=`를 최우선으로 읽고 03·04로 쿼리를 유지한다. 쿼리를 바꿔도 `SessionAuthFilter`가 세션의 이벤트와 다르면 403으로 막는다.
     - `Location`은 **반드시 상대 경로**(`/`로 시작, `//`로 시작하면 안 됨). 요청 Host로 절대 주소를 만들지 않음(오리진 Host가 들어오기 때문, 2절). 설정값이 상대 경로가 아니면 앱 기동 시 실패시킴.
     - `Set-Cookie`: `TETRA_SID=<세션ID 43자>; Path=/; Max-Age=7200; Secure; HttpOnly; SameSite=Lax`, **`Domain` 속성 없음**. `Secure`는 설정값으로 붙임.
     - **`Referrer-Policy: no-referrer`** (2026-10-01, 프론트 요청): 이 요청 URL에 JWT가 쿼리로 들어 있으므로, 이 주소가 Referer로 다른 곳(배너 이미지 서버, 테넌트 사이트 등)에 실려 나가지 않게 하는 값싼 보험. 302로 바로 넘어가 페이지로 열리지 않고, 브라우저 기본값(`strict-origin-when-cross-origin`)도 다른 사이트엔 도메인만 보내며, JWT는 1회용·1~2분 만료라 위험 자체는 작음. CloudFront 응답 헤더 정책으로 붙이면 이 경로만을 위한 behavior가 하나 더 필요해서 **Spring이 이 302 응답에 직접 붙임**.
     - **`Cache-Control: no-store`**: `Set-Cookie`가 들어 있는 응답이라 어떤 캐시에도 남지 않게 함(에러 응답과 같은 규칙).
- 요청마다 MySQL 접근 없음. 검증에 필요한 `event.public_key`·`event.tenant_id`·`event.start_at`은 기동 시 이벤트 메타 캐시로 올려둠(진행 문서 M2), 나머지는 전부 Redis.

### 4-2. `POST /api/issuance/events/{eventId}/ticket`

- 요청 본문 없음(쿠키 세션 기반). 대기실(03 화면) 진입 시 1회 호출.
- 처리: 서버 시각이 `event.start_at` 이전이면 Redis 호출 전에 409 `EVENT_NOT_STARTED`(시작 시각은 기동 시 이벤트 메타 캐시, 현재 시각은 `Clock`).
- **이벤트 종료 (B1, 2026-10-02 확정)**: 서버 시각이 `event.end_at` 이상이면 Redis 호출 전에 409 `EVENT_ENDED`. `end_at`도 이벤트 메타 캐시에 올린다. 판정은 시작 전 확인과 같은 방식(`Clock`, KST).
- 경로 `eventId`와 세션 `event_id` 일치는 `SessionAuthFilter`가 먼저 확인(다르면 403).
- 통과 시 **Lua 스크립트 1개**(`ticket-issue.lua`)로 한 번에 처리(왕복 1회가 목적):
  1. 세션이 그 사이 만료됐으면 번호를 소모하지 않고 중단(→ 401 `SESSION_NOT_FOUND`)
  1-1. 이미 claim(성공·품절)을 처리받은 사용자(`claim:done:{eventId}:{userId}` 있음)면 번호를 소모하지 않고 중단(→ 409 `ALREADY_CLAIMED`, 2026-10-02 결정 — 재입장해도 다시 기다리지 않게)
  2. `INCR ticket:seq:{eventId}` → 번호 채번
  3. 세션 해시에 `ticket_number` + `queue_entered_at`(epoch 밀리초, 번호표 발급 시각 D9) 기록. 재발급 시 덮어씀 — 이전 번호는 "구멍"이 되고 복구하지 않음, 의도된 동작
  4. 구멍 비율 카운터: 첫 발급 `INCR ticket:total:{eventId}`, 재발급 `INCR ticket:retry:{eventId}` (5절 5번)
- 응답: `{"ticketNumber": number}`. 재시도·새로고침 시에도 매번 새 번호 발급.
- MySQL 접근 없음.

### 4-3. `GET /api/issuance/events/{eventId}/queue/cursor`

- 응답: `{"cursor": number}` — 개인화하지 않음. 실제 응답은 공통 형식으로 감싼 `{"success":true,"data":{"cursor":number}}` (4-2 `ticketNumber`도 같음).
- **인증 없음(2026-10-01)**: `SessionAuthFilter` 적용 대상에서 이 경로를 제외하고, 세션 조회 로직을 넣지 않음.
  - 이유: CloudFront 캐시 히트 시 요청이 오리진에 닿지 않아 어차피 인증 검사가 안 됨. 커서 값은 이벤트 공통 비민감 정보이고, 보호 대상(쿠폰 재고)은 claim의 Lua가 별도로 막음.
  - 이 프로젝트는 Spring Security를 쓰지 않고 자체 Filter로 인증하므로, "permitAll"은 필터 제외 경로로 구현함.
- **캐시 헤더**: 성공 응답에 `Cache-Control: public, max-age=0, s-maxage=1`.
  - `s-maxage=1`: CloudFront 같은 공유 캐시는 1초 캐시. `max-age=0`: 브라우저는 캐시하지 않고 매번 요청(적응형 폴링 간격이 브라우저 캐시에 막히지 않게).
  - 에러 응답은 공통 규칙대로 `no-store`.
- **커서 역행**: 엣지마다 캐시가 따로라 클라이언트가 직전보다 작은 값을 받을 수 있음. 클라이언트가 받은 값 중 최댓값만 쓰도록 `폴링 클라이언트 구현 스펙.md`에 반영. 백엔드는 할 일 없음.
- 커서 증가는 캐시 미스로 오리진에 도달한 요청에서만 일어남. 캐시 미스는 엣지마다 초당 1회 수준이라 3초 락 윈도우를 채우기에 충분함.
- 이벤트 시작(`event.start_at`) 전에는 커서를 증가시키지 않음.
- **커서 증가 방식 — 락 기반 피기백** (별도 스케줄러/워커 없음, 폴링 요청 자체가 트리거를 겸함). 구현은 원자성을 위해 Lua(`cursor-advance.lua`):

  ```lua
  -- KEYS[1] advance-lock:{eventId}, KEYS[2] cursor:serving:{eventId}
  -- ARGV[1] 윈도우 밀리초(3000), ARGV[2] 증가량(300), ARGV[3] 이벤트 시작 여부 "1"/"0"
  if ARGV[3] == '1' and redis.call('SET', KEYS[1], '1', 'NX', 'PX', ARGV[1]) then
    redis.call('INCRBY', KEYS[2], ARGV[2])   -- 이 윈도우에 딱 1번만 성공한 요청이 증가시킴
  end
  return tonumber(redis.call('GET', KEYS[2]) or '0')
  ```

- **확정값: 3초당 300명 = 초당 100명 처리율** (이번 대화에서 확정). 5만 명 전원이 큐를 빠져나가는 데 약 8분 20초 소요 예상.
  - 이 숫자는 추정치입니다. 부하 테스트에서 claim 엔드포인트(Redis Lua + 성공/품절 시 MySQL 1행 INSERT)가 이 속도를 문제없이 받아내는지 반드시 확인하고, 필요하면 값만 조정합니다 — values 파일 `values.queue.cursor-step`(300)·`cursor-window`(3s)로 분리 완료, 코드 수정 불필요.
- 클라이언트 쪽 분기 로직(`gap = myTicketNumber - cursor`, 폴링 간격표)은 이미 확정되어 프론트 담당자에게 전달됨 — 백엔드는 이 계약(`data.cursor` 값 하나만 내려주는 것)만 지키면 됨. 폴링 스펙 예시 코드도 `data.cursor`를 읽도록 수정함(2026-10-01). 자세한 내용은 `폴링 클라이언트 구현 스펙.md` 참고(백엔드가 알 필요는 없음, 참고용).

### 4-4. `GET /api/issuance/events/{eventId}/coupons`

- 응답: `{"coupons":[{"couponId":1,"name":"PoC 쿠폰 1","description":"PoC 쿠폰입니다.","remaining":9}, ...]}` (couponId 순, 최대 10종). 이름·설명은 기동 시 메모리 캐시
- **잔여 매수는 반드시 Redis에서 읽습니다.** `coupon.stock_count`(MySQL)는 초기 기준값일 뿐 차감되지 않으므로, 여기서 MySQL을 읽으면 항상 "10장 남음"이라는 틀린 값이 나옵니다.
- 재고 워밍업: `coupon.stock_count` → `coupon:stock:{eventId}:{couponId}`(Redis). **Issuance Service 프로세스 기동 시**(배포 환경은 Pod가 뜰 때마다), 웹 서버가 요청을 받기 전에 실행(`service/CouponCatalog`). 기본 `if-absent`라 여러 Pod가 각자 실행해도 처음 1번만 채워지고 재시작·스케일아웃 때 차감분이 유지됨. `force`는 로컬 초기화 전용(배포 환경 금지). 이벤트 중 Redis 데이터가 사라지면 재고가 다시 채워질 위험 — Next Plan N3.

### 4-5. `POST /api/issuance/events/{eventId}/coupons/claim`

- 요청 본문 없음(일괄 발급, 개별 선택 없음).
- 서버 시각이 `event.end_at` 이상이면 Lua 전에 409 `EVENT_ENDED`(B1, 4-2와 같은 판정). 재고·`claim:done`·발급 이력 모두 바뀌지 않는다.
- 세션에 번호표가 없으면 Lua 전에 409 `TICKET_REQUIRED`(`queue_entered_at`이 없어 이력을 남길 수 없음).
- **Lua 스크립트 1개**(`coupon-claim.lua`)로 원자적 처리:
  1. `SET claim:done:{eventId}:{userId} 1 NX` — 이미 처리된 사용자면 즉시 거절(409 `ALREADY_CLAIMED`). 성공·품절 모두 표시하며 TTL 없음(재입장 후 중복 claim도 막기 위해, 정리는 Next Plan N4)
  2. 쿠폰 종류별로 `coupon:stock:{eventId}:{couponId}`가 0보다 크면 `DECR`해 1장씩 발급(D8), 모두 0이면 품절. 재고는 음수가 되지 않음
  3. 발급된 couponId 목록 반환(빈 목록 = 품절)
- 응답: 성공·품절 모두 **200** (2026-10-01 확정). 성공 `{"result":"SUCCESS","coupons":[{"couponId","name","description"}, ...]}`, 품절 `{"result":"SOLD_OUT","coupons":[]}` (공통 형식 `data` 안). 대부분 사용자가 품절을 받는 것이 정상 시나리오라 에러로 다루지 않음. 중복 claim(`ALREADY_CLAIMED`)·번호표 없음(`TICKET_REQUIRED`)은 409 에러.
- **MySQL 쓰기**: 이 호출이 완료되는 순간(성공이든 품절이든) `issuance_history`에 1행 INSERT — `tenant_id`/`event_id`/`user_id`/`ticket_number`/`queue_entered_at`은 세션에서, `served_at`=NULL(PoC 미사용), `result`=`SUCCESS`/`FAILED_SOLDOUT`. `(event_id, user_id)` UNIQUE 중복 키 오류(1062)는 "이미 기록됨"으로 처리(3절). Redis 차감 후 INSERT가 다른 이유로 실패하면 발급 결과는 그대로 응답하고 에러 로그만 남김(재시도·보상 없음, ADR-0002 트레이드오프).
- **미확정 항목**: "입장 자격 재검증"(번호 순서가 실제로 됐는지 claim 시점에 다시 확인할지)은 API 정의서에 "설계 진행 중"으로 남아있습니다. PoC 구현 기본값은 **생략**(세션 쿠키 유효성만 확인하고, 순서는 재검증하지 않음)으로 하고 진행합니다 — 순서를 어긴 입장이 있어도 재고 Lua가 초과발급은 막아주므로 PoC 안정성에는 영향 없습니다(이번 대화에서 결론 남). 재검증 로직이 필요해지면 이 claim Lua 앞단에 별도 체크를 추가하면 됩니다.

---

## 5. 이번 대화에서 추가로 확정된 사항 (원본 문서에 없던 내용)

1. **커서 증가 = 락 기반 피기백**, 3초당 300명(초당 100명) — 4-3절.
2. **issuance_history는 claim 완료 시점 1회 INSERT** — 세션/번호표 단계 MySQL 쓰기 없음 — 3절.
3. **쿠폰 잔여 수량은 Redis 기준 응답** — MySQL `stock_count`는 초기값 전용 — 4-4절.
4. **claim의 입장 자격 재검증은 PoC 범위에서 생략** — 4-5절.
5. **"구멍 비율"(재시도로 버려지는 번호표 비율) 측정은 선택 구현**: 치명적 문제가 아니므로 PoC 1차 완료를 막는 조건은 아님. 여유가 되면 4-2절 Lua에 `INCR ticket:total:{eventId}`(신규 발급 시) / `INCR ticket:retry:{eventId}`(재발급 시) 두 줄만 추가해서 부하테스트 리포트에 "구멍 비율 = retry ÷ total"을 남기는 것을 권장. (2026-10-01 구현: total은 신규 발급, retry는 재발급이므로 **전체 번호 중 버려진 비율은 retry ÷ (total + retry)**, retry ÷ total은 1인당 재발급 횟수로 함께 보고. 조회 `scripts/ticket-stats.sh`)
6. **(2026-10-01) 단일 호스트**: CloudFront 경로 라우팅으로 화면·API 모두 `{이벤트}.{tenant_id}.tetra.io` — CORS 없음. 오리진이 받는 Host는 오리진 주소(dev `origin.gamza-dev.shop`)라 302는 상대 경로만, 쿠키에 `Domain` 없음 — 2절·4-1절.
7. **(2026-10-01) 환경별 값은 values 파일로 분리**: `application.yml`에는 `${...}` 참조만, 실제 값은 `values/values-{환경}.yml`(Helm values 방식). 302 목적지처럼 나중에 정할 값도 여기서 변수로 둠. 변수 목록은 `PoC_개발_진행.md` 0-2절.
8. **(2026-10-01) 구현 세부 결정**(D1~D11, 상세는 `PoC_개발_진행.md` 0-1절):
   - Java 21 · Spring Boot 4.1.1 · Gradle(Kotlin DSL, Wrapper 9.7.1) · Spring Data Redis(Lettuce) — 3.x는 OSS 지원 종료로 Initializr에서 제공되지 않아 4.1.1로 변경
   - 테스트 시각 주입을 위해 `Clock` 빈 사용, 앱·JDBC 타임존 `Asia/Seoul`
   - 세션 쿠키 `TETRA_SID`, TTL 2시간. Redis 세션 `session:{sid}` 해시 + 역참조 `session:idx:{eventId}:{userId}`
   - `queue_entered_at`은 번호표 발급 시점에 기록하고, 재발급하면 새 번호·새 시각으로 갱신
   - claim "일괄 발급" = 재고가 남은 쿠폰 종류마다 1장씩 발급, 남은 종류가 하나도 없을 때만 품절
   - 이벤트 시작 전에는 커서를 증가시키지 않음
   - 테스트 JWT 키페어는 로컬 키 파일, 검증 공개키는 `event.public_key`
9. **(2026-10-01) 커서 캐싱 구체화**: 커서 API 인증 제거, `Cache-Control: public, max-age=0, s-maxage=1`, 에러 응답은 `no-store` + 정확한 상태 코드, SPA 복구는 CloudFront Function(custom error response 미사용), 클라이언트는 받은 cursor 최댓값만 사용 — 2절·4절·4-3절.
10. **(2026-10-01) JWT 구성 방식**: RS256(공개키만 DB 저장), 클레임 5개, `iss`/`aud` 생략, `event_id`로 공개키 선택 후 검증, RS256 고정, 쿼리스트링 로그 마스킹 — 4-1절.
11. **(2026-10-01) 품절은 에러가 아님**: claim 품절 응답은 200 + `result: SOLD_OUT` — 4-5절.
12. **(2026-10-01) 재고 워밍업 시점**: Issuance Service 프로세스 기동 시(Pod마다, `if-absent`) — 4-4절.
13. **(2026-10-01) 정상 사용자 전제**: 경합·비정상 사용 방어(세션 재사용 원자성 등)는 PoC 이후로 미룸. 목록은 `PoC_개발_진행.md`의 "Next Plan" 표(N1 세션 재사용 원자성, N2 클러스터 모드 Lua CROSSSLOT, N3 Redis 유실 시 재고 복원, N4 `claim:done` 정리).
14. **(2026-10-02) 이벤트 종료 처리 (B1)**: `end_at` 이후 번호표·claim은 409 `EVENT_ENDED`. 세션 발급·커서·쿠폰 목록은 막지 않음(읽기·입장만 — 들어와도 번호표에서 막힘) — 4-2·4-5절.
15. **(2026-10-02) 배포 순서**: ① 로컬 Docker Desktop에서 컨테이너로 확인 → ② 클라우드 K8s(EKS)로 이전 → ③ 그 위에서 부하테스트. 진행 문서 M9~M11·M8.
16. **(2026-10-02) 프런트 질문 결정**: 지터 상한은 프런트 설정(`config.ts`)으로 유지(백엔드가 내려주지 않음). claim 순서 재검증은 생략 유지(5절 4번). 이미 claim 처리된 사용자는 번호표에서 409 `ALREADY_CLAIMED`(4-2). 이벤트 정보는 조회 API `GET /api/issuance/events/{eventId}/info`로 내려준다(확정, 4-0절).

---

## 6. 아직 확인이 필요한 항목 (구현 중 막히면 여기부터 의심할 것)

| 항목 | 현재 상태 | PoC 기본값(가정) |
|---|---|---|
| `user_id` 최대 길이 | DB 정의서가 "128은 명세서 가정"이라고 표기 | ✅ 구현: `VARCHAR(128)`, 초과 시 400 `USER_ID_TOO_LONG` (`values.jwt.user-id-max-length`) |
| 커서 증가량(300/3초) | 추정치 | ✅ 설정값 분리 완료 — 부하테스트로 검증 후 values 파일만 조정 |
| claim 입장 자격 재검증 | 설계 진행 중 | 생략(5절 4번) — 재고 Lua가 초과 발급은 막음 |
| 구멍 비율 측정 | 선택 사항 | ✅ 구현 완료 (`scripts/ticket-stats.sh`) |
| 302 목적지(대기방 경로) | ✅ 확정(2026-10-02) | `/?event={eventId}` (`values.session.redirect-url`, 상대 경로만 허용) |

---

## 7. 패키지 구조 (구현 기준, ADR-0002 레이어드 아키텍처)

설계 때의 제안 구조에서 실제 구현은 아래와 같습니다(2026-10-01). 차이: 엔티티용 `domain/` 추가, Redis 접근을 키 묶음별 `*Store` 클래스로 분리, Lua 파일은 `resources/redis/scripts/`, 설정 클래스는 `config/`.

```
PoC/
├── docker-compose.yml               # 로컬 MySQL 8.0 + Valkey 7.2
├── db/init/                         # 01_schema · 02_seed (DB 정의서_2 그대로) · 03_local_test_key (생성 파일)
├── scripts/                         # 로컬 도구 (8절)
└── issuance-service/
    ├── values/values-local.yml      # 환경별 실제 값 (Helm values 방식, 5절 7번)
    └── src/main/
        ├── resources/
        │   ├── application.yml      # 구조와 ${values.*} 참조만
        │   └── redis/scripts/       # ticket-issue.lua · cursor-advance.lua · coupon-claim.lua
        └── java/io/tetra/issuance/
            ├── filter/              # JwtAuthFilter(/session 전용) · SessionAuthFilter(이벤트 API, cursor 제외) · VerifiedEntryToken
            ├── controller/          # IssuanceController — 5개 엔드포인트, 얇게 유지
            ├── service/             # SessionService(4-1) · TicketService(4-2) · QueueCursorService(4-3)
            │                        # CouponService(4-4) · ClaimService(4-5) · EventMetaCache · CouponCatalog(워밍업)
            ├── domain/              # Event · Coupon (읽기 전용) · IssuanceHistory (INSERT 전용)
            ├── repository/          # EventRepository · CouponRepository · IssuanceHistoryRepository (JPA)
            ├── redis/               # SessionKeys · SessionStore · EntryStore · TicketStore · CursorStore
            │                        # CouponStockStore · RedisScripts(Lua 등록)
            ├── common/              # ApiResponse · ErrorCode · BusinessException · GlobalExceptionHandler
            │                        # ErrorResponseWriter(필터 에러 응답) · PemPublicKeys
            └── config/              # TetraProperties(tetra.* 바인딩·기동 시 검증) · AppConfig(Clock) · FilterConfig
```

- `ClaimService`는 INSERT 1건뿐이라 별도 `@Transactional`을 두지 않음(리포지토리 저장 자체가 트랜잭션).
- 테스트는 `src/test/`에 단위·통합(Testcontainers MySQL·Valkey)·전체 흐름 테스트 111개(진행 문서 참고).

---

## 8. 로컬 개발 환경

AWS(ElastiCache/RDS)는 배포 단계에서만 쓰고, 로컬 개발은 docker-compose로 대체합니다.

실제 파일은 `PoC/docker-compose.yml`입니다(2026-10-01 생성). 요약:

| 서비스 | 이미지 | 비고 |
|---|---|---|
| `mysql` | `mysql:8.0` (CHECK 제약은 8.0.16 이상 필요, 8.0 최신 패치 사용) | DB `tetra_poc`, 앱 계정 `tetra`/`tetra`, root `devpassword`, 타임존 `+09:00`, `./db/init`을 init 디렉토리로 마운트 |
| `valkey` | `valkey/valkey:7.2` | 6379 포트 |

`db/init/01_schema.sql`에 3절 DDL, `02_seed.sql`에 `PoC 데이터베이스 정의서_2.md` 8장 Seed INSERT를 그대로 넣었습니다. `03_local_test_key.sql`은 `bash scripts/gen-test-keys.sh`가 테스트 키페어(`PoC/local-keys/`)와 함께 생성합니다(둘 다 git 제외). 따라서 처음 띄울 때 순서는 `gen-test-keys.sh` → `docker compose up -d --wait`입니다. Redis 쪽 `coupon:stock:*` 초기화(MySQL `stock_count` → Redis 워밍업)는 **Issuance Service 프로세스 기동 시(배포 환경은 Pod가 뜰 때마다, `if-absent`라 처음 1번만 채워짐)** `CouponCatalog`가 웹 서버가 요청을 받기 전에 실행합니다. 설계 때 제안한 `ApplicationReadyEvent`는 웹 서버가 열린 뒤라 그 사이 요청이 재고 0을 볼 수 있어 앞당겼습니다.

로컬 실행 순서: `bash scripts/gen-test-keys.sh` → `docker compose up -d --wait` → `cd issuance-service && ./gradlew bootRun` (상태 확인 `GET /actuator/health`)

### 컨테이너로 실행 (M10, 2026-10-02)

앱도 이미지로 띄우는 방식입니다. `docker-compose.yml`의 `issuance-service`는 `profiles: ["app"]`라 프로필을 줄 때만 뜹니다.

| 할 일 | 명령 (`PoC/`에서) |
|---|---|
| 앱까지 기동 (처음 또는 코드 변경 후) | `docker compose --profile app up -d --build --wait` |
| 코드 변경 없이 기동 | `docker compose --profile app up -d --wait` |
| 앱만 재시작 | `docker restart tetra-issuance` |
| 앱만 내리기 (DB는 유지) | `docker compose --profile app stop issuance-service` |
| 로그 | `docker logs -f tetra-issuance` |

- 설정은 `values/values-docker.yml`(`TETRA_ENV=docker`)입니다. local과 같고 DB·Redis 호스트만 서비스 이름(`mysql`, `valkey`)입니다.
- **bootRun과 같은 8080 포트**를 쓰므로 둘 중 하나만 띄웁니다.
- 이미지 빌드에서는 테스트를 돌리지 않습니다(Testcontainers가 Docker를 필요로 함). 테스트는 `./gradlew test`로 따로 합니다. 첫 빌드는 의존성 다운로드로 약 20분, 이후는 캐시로 빨라집니다.
- 공개키는 앱이 **기동할 때** 읽습니다. DB의 `event.public_key`를 바꿨다면 `docker restart tetra-issuance`가 필요합니다.
- `docker compose down -v`는 MySQL 볼륨을 지워 시드와 `03_local_test_key.sql`(로컬 테스트 키)로 되돌립니다. 테넌트 공개키를 등록해 둔 경우 다시 넣어야 합니다. 상태만 초기화할 때는 `reset-local.sh --yes`를 씁니다(공개키는 유지).

프런트 빌드본을 컨테이너에 붙여 보기 (`tetra-poc-front/`에서):

```bash
npm run build
VITE_API_PROXY_TARGET=http://localhost:8080 npx vite preview --port 4173
# 입장: bash scripts/issue-test-jwt.sh user-0001 1 poctenant001 240 http://localhost:4173  → 출력 URL을 브라우저로
```

`vite preview`는 `server.proxy` 설정을 그대로 쓰므로 `/api`가 컨테이너로 넘어가고, 세션 302(`/?event=1`)는 4173의 프런트로 돌아옵니다. `VITE_API_PROXY_TARGET`은 `.env.local`이 아니라 셸 환경변수로 줘야 합니다(통합 문서 I7).

### 로컬 도구 (`PoC/scripts/`, 2026-10-01)

| 스크립트 | 용도 |
|---|---|
| `gen-test-keys.sh` | 테스트 JWT 키페어 + `03_local_test_key.sql` 생성 (docker compose 첫 기동 전에 1회) |
| `issue-test-jwt.sh [user_id] …` | 테스트 입장 토큰 발급 → 세션 API URL 출력 (1회용) |
| `ticket-stats.sh [event_id]` | 진입 수·번호표 통계·구멍 비율 |
| `reset-local.sh --yes` | Redis 비우기 + 발급 이력 비우기 + 재고 다시 채우기 (앱 재시작 불필요) |
| `simulate-users.sh [N]` | 가상 사용자 N명 전체 흐름 확인 (기능 확인용, 부하테스트 도구 아님) |

---

## 9. 구현 순서 (마일스톤)

진행 상황은 `PoC_개발_진행.md`가 기준입니다. 2026-10-01 기준 1~7 완료(1차 완료 기준 충족). 이후 순서(2026-10-02): 9 → 10 → 11 → 8.

1. ✅ 프로젝트 스캐폴딩 — Spring Boot 프로젝트 생성, 7절 패키지 구조, docker-compose, DDL/seed 적용 확인
2. ✅ 공통 계층 — `ApiResponse`, `ErrorCode`, `GlobalExceptionHandler`, `JwtAuthFilter`·`SessionAuthFilter`(테스트용 JWT 발급 도구 포함)
3. ✅ 4-1 세션 API — jti 차단, 세션 재사용 분기까지 구현 + 테스트
4. ✅ 4-2 번호표 API — Lua 스크립트, 시작시각 체크, 구멍 비율 카운터 + 테스트
5. ✅ 4-3 커서 폴링 API — 락+피기백, 설정값 분리 + 동시 요청 테스트(락이 정말 1번만 성공하는지). 배포 환경 확인 2건(CloudFront 캐시 히트, `/api/*` 404) 남음
6. ✅ 4-4/4-5 쿠폰 목록·claim API — Redis 재고 워밍업, Lua 원자적 차감, MySQL 1회 INSERT + 테스트
7. ✅ 전체 플로우 통합 테스트(세션→티켓→폴링→클레임) + 로컬 초기화·시뮬레이션 스크립트
8. ⬜ 부하테스트 — 클라우드 K8s 위에서 5만 동시접속 시나리오, 커서 증가량(300/3초) 검증 (11 다음)
9. ⬜ 이벤트 종료 처리(B1) — `end_at` 이후 번호표·claim 409 `EVENT_ENDED` + 테스트
10. ⬜ 로컬 Docker 배포 확인 — 앱 컨테이너 이미지로 Docker Desktop에서 전체 흐름 확인
11. ⬜ 클라우드 K8s 이전 — EKS 매니페스트·values, CloudFront·ElastiCache·RDS 연결, 배포 환경 확인 항목

---

## 10. 참고 원본 문서

- `PoC 데이터베이스 정의서_2.md` — DB 정의 기준 문서(3절 DDL 출처, 2026-10-01 최신 수정본). 이전 `PoC 데이터베이스 정의서.pdf`, `_1.md`는 참고용
- `Claude outputs/Tetra_API_정의서.xlsx` — API 정의 전체(시트: API 엔드포인트 정의, POC 대상 API)
- `Claude outputs/ADR-0001-Issuance-Service-POC-트래픽-아키텍처.md` — 인프라 경로(2026-10-01 개정: 단일 호스트 + 전역 커서 반영)
- `Claude outputs/ADR-0002-Issuance-Service-애플리케이션-아키텍처.md` — 레이어드 아키텍처(7절 근거)
- `폴링 클라이언트 구현 스펙.md` — 프론트엔드 계약(4-3절에서 백엔드가 지켜야 할 응답 형식의 근거)
- Claude Artifact "Tetra 발급 플로우 전체 다이어그램" — 전체 시퀀스 그림

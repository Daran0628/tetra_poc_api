# Tetra Issuance Service — PoC 백엔드 개발 진행

- 작성일: 2026-10-01
- 기준 문서: [PoC_개발_플랜.md](PoC_개발_플랜.md) (설계·결정 사항은 항상 플랜 문서가 기준, 이 문서는 진행 순서·체크리스트·진행 현황 관리용)
- 사용법: 작업 완료 시 `[ ]` → `[x]`로 바꾸고, 하단 "진행 현황"과 "변경 이력"을 갱신

---

## 0. 진행 현황 요약

| 단계 | 내용 | 플랜 근거 | 상태 |
|---|---|---|---|
| M0 | 착수 전 결정 사항 확정 | 6절 + 본 문서 0-1 | ✅ 완료 |
| M1 | 프로젝트 스캐폴딩 · 로컬 환경 | 7절, 8절 | ✅ 완료 |
| M2 | 공통 계층 (응답/에러/필터/JWT 도구) | 2절, 7절 | ✅ 완료 |
| M3 | 세션 API `GET /session` | 4-1 | ✅ 완료 |
| M4 | 번호표 API `POST /ticket` | 4-2, 5-5 | ✅ 완료 |
| M5 | 커서 폴링 API `GET /queue/cursor` | 4-3, 5-1 | ✅ 완료 (배포 환경 확인 2건 남음) |
| M6 | 쿠폰 목록 · claim API | 3절, 4-4, 4-5, 5-2~5-4 | ✅ 완료 |
| M7 | 전체 플로우 통합 테스트 | 9절-7 | ✅ 완료 |
| M8 | 부하테스트 준비물 전달 | 9절-8 | ⬜ 대기 |

상태 표기: ⬜ 대기 / 🟨 진행 중 / ✅ 완료 / ⛔ 막힘

### 0-1. 플랜에 명시되지 않아 착수 전 정해야 할 항목

플랜 문서를 읽으며 구현에 필요하지만 값이 정해지지 않은 항목입니다. M0에서 확정하고 결정 내용을 이 표에 적어둡니다. 제안값은 별도 의견이 없을 때 그대로 쓸 기본값입니다.

| # | 항목 | 제안값(기본값) | 확정값 |
|---|---|---|---|
| D1 | 언어·프레임워크 버전 | Java 21, Spring Boot 3.x | ✅ Java 21 + **Spring Boot 4.1.1** (3.x는 OSS 지원 종료로 Initializr 미제공 → 2026-10-01 변경) |
| D2 | 빌드 도구 | Gradle (Kotlin DSL) | ✅ 제안값 |
| D3 | Redis 클라이언트 | Spring Data Redis + Lettuce (`RedisScript`로 Lua 등록) | ✅ 제안값 |
| D4 | 세션 쿠키 이름 · TTL | `TETRA_SID`, TTL = 이벤트 종료까지 또는 고정 2시간 | ✅ 제안값 — `TETRA_SID`, TTL 고정 2시간(변수로 분리, 0-2) |
| D5 | Redis 세션 키 구조 | `session:{sid}` (Hash: tenant_id, event_id, user_id, ticket_number, queue_entered_at) + 역참조 `session:idx:{eventId}:{userId}` → sid | ✅ 제안값 |
| D6 | 302 리다이렉트 목적지 | 설정값(`application.yml`)의 대기방 URL 템플릿 (`event.endpoint_url` 사용 여부 확인 필요) | ✅ 변수로 처리 — 값은 나중에 채움. 환경별 변수는 values 파일 하나로 묶음(0-2) |
| D7 | 쿠키 host-only 조건 | 정적 페이지(CloudFront)와 API가 같은 호스트로 노출된다는 전제 확인 (다르면 CORS·쿠키 정책 재검토) | ✅ 같은 호스트로 통일 — CloudFront 라우팅으로 화면·API 모두 `{이벤트}.{tenant_id}.tetra.io`. host-only 쿠키 + SameSite=Lax 유지, CORS 불필요(0-3) |
| D8 | claim "일괄 발급" 의미 | 재고가 남은 쿠폰 종류마다 1장씩 차감·발급, 남은 종류가 하나도 없을 때만 품절 | ✅ 제안값 |
| D9 | `queue_entered_at` 기록 시점 | 번호표 발급(Lua) 시점, 재발급 시 함께 갱신 | ✅ 번호표 발급 시점에 기록, 재발급 시 새 번호·새 시각으로 갱신 |
| D10 | 이벤트 시작 전 커서 동작 | 시작 전에는 커서를 증가시키지 않음 (`start_at` 체크를 커서 API에도 적용) | ✅ 시작 전에는 증가시키지 않음 |
| D11 | 테스트용 RSA 키페어 · 공개키 위치 | 로컬은 리소스 폴더 키 파일, 검증 공개키는 `event.public_key` 사용 | ✅ 제안값 |

### 0-2. 환경별 변수 파일 (values 파일, Helm `values.yaml` 방식)

환경마다 달라지거나 나중에 채울 값은 코드와 `application.yml`에 직접 쓰지 않고, **환경별 values 파일 하나에 모아 둡니다.** 값을 바꿀 때는 이 파일만 고치면 됩니다.

- 구조: `application.yml`에는 구조와 `${...}` 참조만 둡니다. 실제 값은 `values/values-{환경}.yml`에서 읽습니다(`spring.config.import`로 불러옴).
  - `values/values-local.yml`: 로컬 docker-compose용, M1에서 생성
  - `values/values-dev.yml`, `values/values-prod.yml`: 배포 단계에서 추가(EKS에서는 ConfigMap/Secret으로 주입)
- 비밀값(DB 비밀번호, 개인키 경로)은 로컬 파일에만 두고, 배포 환경에서는 Secret으로 넘깁니다.

| 변수 | 용도 | local 값 | 비고 |
|---|---|---|---|
| `tetra.session.redirect-url` | 세션 발급 후 302 목적지(대기방 경로 템플릿, `{eventId}` 치환). **상대 경로만 허용**(`/`로 시작, `//` 불가) | `/` (임시, 2026-10-01 — 절대 주소 `http://www.naver.com`에서 변경) | D6 — 나중에 값만 채우면 됨. 같은 호스트라 `/...` 상대 경로로 충분(0-3) |
| `tetra.session.cookie-name` | 세션 쿠키 이름 | `TETRA_SID` | D4 |
| `tetra.session.ttl` | 세션 유지 시간 | `2h` | D4 |
| `tetra.session.cookie-secure` | 쿠키 Secure 속성 | `true` | localhost는 브라우저가 보안 출처로 취급 |
| `tetra.queue.cursor-step` | 윈도우당 커서 증가량 | `300` | 부하테스트 후 조정 |
| `tetra.queue.cursor-window` | 커서 증가 윈도우(락 TTL) | `3s` | |
| `tetra.queue.cursor-cache-s-maxage` | 커서 응답 `Cache-Control`의 `s-maxage`(CDN 캐시 시간) | `1s` | 헤더는 `public, max-age=0, s-maxage={값}` |
| `tetra.jwt.user-id-max-length` | `user_id` 최대 길이 | `128` | 초과 시 400 |
| `tetra.jwt.clock-skew` | JWT 만료 판정 시 테넌트 서버와의 시계 오차 허용 | `5s` | 0~1분 |
| `tetra.jwt.max-ttl` | `exp`가 지금부터 이보다 멀면 거절 | `5m` | 비정상 장기 토큰 차단 |
| `tetra.jwt.test-private-key-path` | 테스트 JWT 서명용 개인키 | 로컬 키 파일 경로 | D11, local 전용 |
| `tetra.timezone` | 앱·JDBC 타임존 | `Asia/Seoul` | DB 저장 타임존 KST와 맞춤(M0-1 S3) |
| `management.endpoint.health.show-details` | `/actuator/health`에 db·redis 상세 상태 표시 여부 (values 키 `values.management.health-show-details`) | `always` | 배포 환경은 `never` 권장(내부 구성 노출 방지) |
| `tetra.stock.warmup-mode` | 재고 워밍업 방식 | `if-absent` | `force`는 로컬 초기화용 |
| `spring.datasource.url` / `username` / `password` | MySQL 접속 | docker-compose 값 | 배포 시 RDS |
| `spring.data.redis.host` / `port` | Redis 접속 | `localhost` / `6379` | 배포 시 ElastiCache |

새 변수가 생기면 이 표와 values 파일에 함께 추가합니다.

키 이름 규칙: values 파일에는 `values.*` 아래에 값만 둡니다(예: `values.session.redirect-url`, `values.db.host`). `application.yml`이 이 값을 `${values.…}`로 참조해 위 표의 앱 설정 키(`tetra.*`, `spring.*`)를 만듭니다. Helm의 values.yaml → 템플릿 관계와 같습니다.

### 0-3. D7 메모 — 화면과 API의 호스트

**확정 (2026-10-01)**: CloudFront에 경로별 라우팅(behavior)을 두어 API 요청도 CloudFront가 받습니다. 화면과 API의 호스트가 `{이벤트}.{tenant_id}.tetra.io` 하나로 통일됐습니다. ADR-0001의 `issuance.tetra.io` 별도 도메인 구성은 더 이상 유효하지 않습니다.

| 경로 | 오리진 |
|---|---|
| `/api/*` | ALB → Issuance Service |
| 그 외(대기실·발급 화면 정적 페이지) | S3 |

백엔드에 미치는 영향:

- 쿠키: host-only + SameSite=Lax 그대로 동작합니다. 같은 출처라서 CORS 설정과 `credentials: 'include'`가 필요 없습니다.
- 302 리다이렉트: `redirect-url`은 `/...` **상대 경로로만** 둡니다. 오리진이 받는 Host가 오리진 주소(dev `origin.gamza-dev.shop`)라 Host로 절대 주소를 만들면 브라우저가 오리진으로 가 버립니다(2026-10-01, 프론트 담당 공유).
- 쿠키: `Set-Cookie`에 `Domain` 속성을 넣지 않고, `Secure`는 요청 scheme이 아닌 설정값으로 붙입니다.
- 커서 CDN 캐시(2026-10-01 구체화): 커서 API는 인증 없이 `Cache-Control: public, max-age=0, s-maxage=1`로 응답하고, CloudFront가 경로만을 캐시 키로 1초 캐시합니다. 상세는 플랜 2절 behavior 표·4-3절.
- 나머지 `/api/*`: 캐시하지 않고, 쿠키를 오리진(ALB)까지 전달해야 합니다. 인프라 담당자에게 전달할 사항입니다.
- 에러 응답: 정확한 상태 코드 + `Cache-Control: no-store`. SPA 복구는 CloudFront Function으로 하므로 `/api/*` 에러가 `index.html`로 바뀌지 않습니다.
- 클라이언트 IP가 필요하면 `X-Forwarded-For` 헤더 기준으로 읽습니다(PoC에서는 사용하지 않음).

---

## M0. 착수 전 결정 사항 확정

- [x] 0-1 표의 D1~D11 확정값 기입
- [x] `PoC 데이터베이스 정의서.pdf` 8장 Seed INSERT 원문 확보 (M1에서 `02_seed.sql`로 그대로 옮김)
- [x] 플랜 6절 미확정 항목의 PoC 기본값 재확인 — 기본값 그대로 (2026-10-01)
  - [x] `user_id` 128자 초과 → 400 처리
  - [x] 커서 증가량 300 / 윈도우 3초 → 설정값으로 분리
  - [x] claim 입장 자격 재검증 → 생략
  - [x] 구멍 비율 측정 → 선택 구현
- [x] Seed 검토에서 나온 항목 결정 (아래 M0-1)
- [x] ERD 원칙 확정 — DB 정의서 DDL 그대로, 앱에서 변경 없음 (아래 M0-3)
- [x] DB 정의서 수정본(`_1.md` → `_2.md`) 반영 (아래 M0-4)
- [x] 로컬 개발 도구 준비 (아래 M0-2)

### M0-1. Seed 검토 결과 (2026-10-01)

PDF 8장 Seed를 읽으며 구현에 영향을 주는 점을 정리했습니다.

| # | 내용 | 영향 | 처리 |
|---|---|---|---|
| S1 | `event.public_key`가 빈 문자열(`''`) | D11은 JWT 검증 공개키를 `event.public_key`에서 읽는데, 시드대로면 검증할 키가 없음 | ✅ A 방식 — Seed 원본은 그대로 두고, 로컬 전용 `db/init/03_local_test_key.sql`이 테스트 공개키로 `event.public_key`를 UPDATE |
| S2 | 이벤트 기간이 2026-09-29 10:00 ~ 2026-10-29 10:00 (KST) | 오늘(10-01) 기준 이미 시작 상태라 "시작 전 거절" 테스트는 시각을 바꿔서 해야 함 | 테스트에서 시각을 주입(`Clock` 빈)해 검증 |
| S3 | DB 저장 타임존이 KST(Asia/Seoul) | `start_at` 비교, `served_at` 기록 시 앱 타임존이 다르면 9시간 어긋남 | JVM·JDBC 타임존을 `Asia/Seoul`로 고정(values 변수 `tetra.timezone`) |
| S4 | `issuance_history`에 `coupon_id` 없음 (PDF 9장 #5에서도 지적) | 일괄 발급(D8)으로 여러 장을 받아도 "어떤 쿠폰을 받았는지"는 DB에 남지 않음 | ✅ DB 정의서_1에서 "두지 않음"으로 확정(이벤트 단위 일괄 발급) |

### M0-3. ERD 원칙 (2026-10-01 확정)

- DB 정의서의 DDL(플랜 3절과 동일)을 **앱 쪽에서 변경 없이** 사용합니다. 테이블·컬럼·제약·인덱스 추가/삭제 없음. DB 정의서가 수정되면 그 문서를 따릅니다 — 현재 기준은 `PoC 데이터베이스 정의서_2.md`(M0-4).
- 흐름상 쓰지 않는 컬럼은 비워 둡니다.
  - NULL 허용 컬럼 → `NULL` (예: `event.live_path`, `event.subdomain`)
  - NOT NULL 컬럼 → `NULL`을 넣을 수 없으므로 PDF Seed와 같이 빈 문자열 `''` (예: `event.endpoint_url`, `event.banner_image_path`). NULL로 바꾸려면 DDL 변경이 필요해서 하지 않습니다.
- 5개 API가 실제로 읽는 컬럼: `event.start_at`, `event.public_key`, `event.tenant_id`, `coupon.*`. 쓰는 테이블은 `issuance_history` 하나(claim 시 1행 INSERT, `served_at`은 NULL — M0-4).
- DB 정의서 6장 비고의 "`queue_entered_at` = 세션 생성 시각"은 따르지 않고 **D9(번호표 발급 시각)를 유지**합니다(M0-4 Q1, 2026-10-01 확정). 컬럼 정의는 그대로입니다.
- 번호표 없이 claim이 들어오는 경우(`ticket_number`가 세션에 없음)는 `queue_entered_at`도 없어서 INSERT할 수 없으므로 거절합니다(`ErrorCode`: 번호표 없음). 순서 재검증이 아니라 세션 데이터 유무 확인입니다.

### M0-4. DB 정의서 수정본 반영 (2026-10-01)

기준 문서가 `PoC 데이터베이스 정의서.pdf` → `_1.md` → **`PoC 데이터베이스 정의서_2.md`**(최신)로 바뀌었습니다. C1~C6은 `_1.md`, C7은 `_2.md`에서 바뀐 내용입니다. 변경점과 앱 영향:

| # | 변경 | 앱 영향 | 반영 위치 |
|---|---|---|---|
| C1 | `tenant_id` `BINARY(16)` UUID → **`CHAR(12)` ascii_bin** 소문자·숫자 12자 (시드 `poctenant001`) | UUID 변환 유틸 불필요, `String` 매핑. JWT `tenant_id`도 같은 형식 | M2, M3 |
| C2 | **`event.tenant_id` 추가** (+ `idx_event_tenant`, FK 없음) | 세션 API에서 JWT `tenant_id` = `event.tenant_id` 확인 | 플랜 4-1, M3 |
| C3 | `issuance_history (event_id, user_id)` **UNIQUE** (`uk_issuance_event_user`) | 중복 키 오류 1062 → "이미 기록됨" 처리 | M6 |
| C4 | `served_at` **PoC 미사용(NULL)**, `result`는 `SUCCESS`·`FAILED_SOLDOUT`만 명시 | claim INSERT에서 `served_at` 제외 | M6 |
| C5 | `coupon_id` 두지 않음 확정 | 변경 없음 | M0-1 S4 |
| C6 | 컬럼 수 31 → 32 (event 14 → 15) | — | — |
| C7 | (`_2.md`) **`tenant_id` 형식 CHECK** `REGEXP '^[a-z0-9]{12}$'` — tenant·event·coupon에만, `issuance_history`는 앱이 보장. 운영 생성 규칙은 CSPRNG 난수(PoC 시드 `poctenant001`은 예외) | `issuance_history` INSERT 값은 검증된 JWT `tenant_id`라 별도 처리 불필요. 테넌트 생성은 PoC 범위 밖(Portal)이라 CSPRNG 생성 코드는 만들지 않음 | M3, M6 |

확인이 필요한 점:

| # | 내용 | 상태 |
|---|---|---|
| Q1 | DB 정의서 6장(`_2.md`에서도 그대로)은 `queue_entered_at`을 "세션 생성 시각과 동일"로 적었는데, D9는 "번호표 발급 시각(재발급 시 갱신)"으로 확정함. 어느 쪽을 따를지 | ✅ **D9 유지**(2026-10-01) — 번호표 발급 시각 기록, 재발급 시 갱신. 번호표 없는 claim은 거절(M0-3). DB 정의서 6장 비고 문구 수정을 DB 담당(박주연)에게 요청 필요 |

- [x] `db/init/01_schema.sql`·`02_seed.sql`을 정의서_2 기준으로 교체 후 DB 재생성(`docker compose down -v` → `up -d --wait`)·검증 (2026-10-01)
  - [x] 건수 tenant 1 / event 1 / coupon 10 / issuance_history 0, 재고 합계 100
  - [x] `tenant_id` = `poctenant001` (tenant·event·coupon 모두), `event.tenant_id` 컬럼 생성, `public_key` 450자 유지
  - [x] 형식 CHECK 3개(`chk_tenant_id_format`, `chk_event_tenant_id_format`, `chk_coupon_tenant_id_format`) 생성 — 대문자·11자·특수문자 값은 `ERROR 3819`로 거절, 올바른 12자는 통과
  - [x] `uk_issuance_event_user` UNIQUE — 같은 `(event_id, user_id)` 재 INSERT 시 `ERROR 1062` 확인 (테스트 행은 삭제)

### M0-2. 로컬 개발 도구 점검 (2026-10-01)

| 도구 | 상태 | 조치 |
|---|---|---|
| JDK 21 | ✅ Temurin 21.0.12.1 설치(winget). 시스템 `JAVA_HOME` = `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\`, PATH에서 JDK 8보다 앞에 등록 | VS Code·터미널을 새로 열어야 반영됨 |
| Gradle | 설치 불필요 | 프로젝트에 Gradle Wrapper(`gradlew`)를 포함해서 사용 |
| Docker | ✅ 29.6.1, Compose v5.2.0, 엔진 실행 확인 | — |
| Git | 2.55 설치됨. `c:\workspace\tetra`는 아직 git 저장소가 아님 | 저장소로 관리할지 결정(선택) |

---

## M1. 프로젝트 스캐폴딩 · 로컬 환경

- [x] `PoC/issuance-service/` Spring Boot 4.1.1 프로젝트 생성 (Initializr, 패키지 `io.tetra.issuance`, Gradle Wrapper 9.7.1) — `compileJava`·`compileTestJava` 성공
- [x] 의존성 추가: WebMVC, Data JPA, Data Redis, Validation, MySQL Driver, Lombok, Testcontainers(MySQL)
- [→] JWT 라이브러리 추가 — Initializr에 없어 M2로 이동
- [x] Testcontainers 이미지 맞추기 — `mysql:8.0`·`valkey/valkey:7.2`(docker-compose와 동일), DB `tetra_poc`, 타임존 +09:00, `../db/init` SQL을 컨테이너 init 폴더로 복사 (2026-10-01)
  - [x] 연결 확인 테스트 추가(`IssuanceServiceApplicationTests`): 컨텍스트 기동, 시드(tenant 1·`poctenant001`·재고 100·이력 0), 타임존 `+09:00`, Redis 쓰기/읽기 — `./gradlew test` 4개 모두 통과
  - 참고: `03_local_test_key.sql`은 git 제외 파일이라, 키를 생성하지 않은 환경의 테스트 DB는 `public_key`가 빈 값. JWT 테스트(M2·M3)는 테스트 안에서 키를 직접 만들어 쓰는 방식으로 설계
- [→] 플랜 7절 패키지 구조 — 빈 패키지는 git에 남지 않아, M2부터 클래스를 만들 때 해당 패키지를 함께 생성하는 것으로 M2로 이동
- [x] `PoC/docker-compose.yml` 작성 — `mysql:8.0`(실행 버전 8.0.46, 타임존 +09:00), `valkey/valkey:7.2`(7.2.14), 헬스체크 포함
- [x] `db/init/01_schema.sql` — DB 정의서 8장 DDL 그대로 (현재 `_2.md` 기준, M0-4)
- [x] `db/init/02_seed.sql` — DB 정의서 8장 Seed INSERT 그대로 (현재 `_2.md` 기준) (앞에 `SET NAMES utf8mb4; USE tetra_poc;`만 추가)
- [x] `db/init/03_local_test_key.sql` — `scripts/gen-test-keys.sh`가 생성 (RSA 2048 키페어 → `PoC/local-keys/`, 공개키 PEM으로 `event.public_key` UPDATE). 키와 03 파일은 `PoC/.gitignore`로 제외
- [x] `docker compose up -d --wait` 후 확인 (2026-10-01)
  - [x] 테이블 4개 생성, CHECK 제약 동작 확인 (잘못된 `account_status` INSERT → `ERROR 3819` 거절)
  - [x] seed 건수 확인 (tenant 1 / event 1 / coupon 10 / issuance_history 0, 재고 합계 100), 한글 정상
  - [x] `event.public_key` 450자 PEM 입력, `endpoint_url`=`''`, `live_path`·`subdomain`=NULL (M0-3)
  - [x] Valkey `PING` → `PONG`

> 로컬 DB 초기화: init SQL은 볼륨이 비어 있을 때만 실행됩니다. 처음부터 다시 만들려면 `docker compose down -v` 후 `docker compose up -d --wait`. 키를 새로 만들었다면(`--force`) 반드시 이 방법으로 다시 띄워야 공개키가 반영됩니다.
- [x] `application.yml` 기본 설정 — 구조와 `${values.*}` 참조만, `spring.config.import: file:./values/values-${TETRA_ENV:local}.yml` (2026-10-01). `application.properties` 삭제
  - JPA `ddl-auto: validate`(앱이 테이블 구조를 바꾸지 않음), `open-in-view: false`, JDBC `connectionTimeZone`·Hibernate `jdbc.time_zone`·Jackson `time-zone`을 `values.timezone`으로
  - 기동 확인: 경고 없이 `Started IssuanceServiceApplication`, HikariPool(MySQL) 연결 성공. Redis는 첫 사용 시 연결하는 방식이라 4번에서 별도 확인
- [x] `issuance-service/values/values-local.yml` 생성 — 0-2 표의 변수 전부, 미정 값(`redirect-url`)은 빈 값 (2026-10-01). 문법 검증은 앱 기동 시 확인
- [x] 애플리케이션 기동 및 MySQL·Redis 연결 확인 (2026-10-01)
  - Spring Boot Actuator 추가, `health` 엔드포인트만 공개(`/actuator/env`·`beans` 등은 404). 상세 표시는 values 변수 `management.health-show-details`
  - 로컬 docker-compose 기준 `GET /actuator/health` → 전체 `UP`, `db`(MySQL) `UP`, `redis`(7.2.4) `UP`, `liveness`·`readiness` 그룹 제공(EKS 프로브용)
  - 참고: `./gradlew bootRun`을 강제로 끊으면 앱 Java 프로세스가 남을 수 있음 → 8080 포트를 잡은 프로세스를 확인해 종료

---

## M2. 공통 계층

- [x] JWT 라이브러리 추가 (M1에서 이동) — `com.nimbusds:nimbus-jose-jwt:10.10`(최신 정식, Boot 4.1 BOM 미관리라 버전 명시, 추가 의존성 없음). **nimbus-jose-jwt로 결정**(2026-10-01): `JWSVerificationKeySelector`로 RS256 고정, `JWTClaimsSetAwareJWSKeySelector`로 `event_id`별 공개키 선택을 라이브러리 기능으로 처리 (jjwt는 둘 다 직접 구현 필요)
- [x] 플랜 7절 패키지 구조 — `common`·`config`·`domain`(엔티티용 추가)·`repository`·`service`·`filter`·`redis` 생성. `controller`는 M3에서 첫 컨트롤러와 함께, `redis/scripts`는 M4 Lua와 함께 — 클래스를 만들면서 `filter` / `controller` / `service` / `repository` / `redis` / `common` / `config` 패키지 생성 (M1에서 이동)
- [x] `ApiResponse<T>` — 성공 `{"success":true,"data":...}` / 실패 `{"success":false,"error":{"code","message"}}`, null 필드는 생략 (`common/ApiResponse.java`, 2026-10-01)
- [x] `ErrorCode` Enum + `BusinessException` — 코드·HTTP 상태·기본 메시지 (`common/ErrorCode.java`)
  - [x] JWT: `JWT_MISSING`·`JWT_MALFORMED`(400), `JWT_INVALID_SIGNATURE`·`JWT_EXPIRED`·`JWT_REUSED`(401)
  - [x] `user_id` 길이 초과 `USER_ID_TOO_LONG`(400), `tenant_id` 형식 `INVALID_TENANT_ID`(400), 테넌트 불일치 `TENANT_MISMATCH`(403)
  - [x] 세션 없음·만료 `SESSION_NOT_FOUND`(401), 경로 `eventId` 불일치 `SESSION_EVENT_MISMATCH`(403)
  - [x] 이벤트 없음 `EVENT_NOT_FOUND`(404), 시작 전 `EVENT_NOT_STARTED`(409)
  - [x] 중복 claim `ALREADY_CLAIMED`, 번호표 없음 `TICKET_REQUIRED`, 품절 `COUPON_SOLD_OUT`(모두 409) — 품절을 에러(409)로 줄지 정상 응답(200)으로 줄지는 M6에서 확정
  - [x] 공통: `INVALID_REQUEST`(400), `NOT_FOUND`(404), `METHOD_NOT_ALLOWED`(405), `INTERNAL_ERROR`(500)
- [x] `GlobalExceptionHandler` — 비즈니스 예외·잘못된 요청·없는 경로·메서드 불일치·예상 못 한 예외를 `ApiResponse`로 변환. 500은 내부 메시지를 응답에 넣지 않고 로그로만
  - [x] 테스트 `GlobalExceptionHandlerTest` 6개 통과 (성공 형식, 409+코드, 400, 없는 경로 404 JSON, 405, 500 내부 정보 미노출, 모든 에러 `no-store`)
- [x] `JwtAuthFilter` (`filter/JwtAuthFilter.java`, 2026-10-01) — `/api/issuance/session`에만 적용, RS256 서명·만료 검증 후 claims(`user_id`, `tenant_id`, `event_id`, `jti`, `exp`) 추출 (플랜 4-1 "JWT 구성 방식")
  - [x] 서명 미검증 상태로 `event_id`만 읽어 해당 이벤트 `public_key` 선택 → 서명 검증 (nimbus `JWTClaimsSetAwareJWSKeySelector`). 세션 값은 검증된 클레임(`VerifiedEntryToken`)에서만 사용
  - [x] 이벤트 없음 / `public_key` 빈 값이면 거절 (`EVENT_NOT_FOUND` 404, 키 없음은 경고 로그)
  - [x] `alg`는 RS256만 허용 (`none`·HS256 거절 → `JWT_INVALID_SIGNATURE` 401)
  - [x] 클레임 검사: `exp`(시계 오차 `jwt.clock-skew` 5s 허용 → `JWT_EXPIRED`), `exp` 상한 `jwt.max-ttl` 5m, `jti`·`user_id` 필수, `user_id` 128자, `tenant_id` 형식·`event.tenant_id` 일치(`TENANT_MISMATCH` 403)
  - [x] 이 경로의 모든 응답(에러 포함)에 `Referrer-Policy: no-referrer`
  - [x] 요청 로그에서 `JWT` 쿼리 파라미터 마스킹 — 거절 로그는 에러 코드만 남김(토큰 미기록 확인), Tomcat 접근 로그 형식을 `%U`(쿼리 제외)로 고정
- [x] `SessionAuthFilter` (`filter/SessionAuthFilter.java`, 2026-10-01) — `/api/issuance/events/*`에 등록, ticket·coupons·claim용. **커서 API 경로는 세션 조회 없이 통과**(플랜 4-3)
  - [x] `TETRA_SID` 쿠키 → 형식 검사(43자 URL-safe, 틀리면 Redis 조회 안 함) → `session:{sid}` 조회. 없음·만료·필드 깨짐 → 401 `SESSION_NOT_FOUND`
  - [x] 경로 `eventId` ≠ 세션 `event_id` → 403 `SESSION_EVENT_MISMATCH`, 숫자 아님 → 400 `INVALID_REQUEST`
  - [x] 경로는 디코딩·`;파라미터` 제거 후 판단, `/../`·`/./` 경로 조작은 커서 예외로 통과시키지 않음
  - [x] 통과 시 `IssuanceSession`을 요청에 실음 → 컨트롤러는 `SessionAuthFilter.sessionOf(request)`
- [x] Redis 세션 형식 확정 (D5, `redis/SessionKeys.java`) — `session:{sid}` Hash 필드 `tenant_id`·`event_id`·`user_id`·`ticket_number`·`queue_entered_at`(epoch 밀리초, D9). 번호표 전에는 뒤 두 필드 없음. 역참조 `session:idx:{eventId}:{userId}` = sid. 세션 ID는 256비트 난수 URL-safe Base64 43자 (`newSessionId()`, M3에서 사용)
- [x] 에러 응답 공통 헤더 — 정확한 상태 코드 + `Cache-Control: no-store`
  - [x] `GlobalExceptionHandler` (Controller·Service 예외)
  - [x] Filter 에러 응답 — Filter는 Controller 바깥이라 Handler가 못 잡음. `common/ErrorResponseWriter`로 같은 형식(ApiResponse + 상태 코드 + no-store) 응답
    - [x] `JwtAuthFilter` 적용
    - [x] `SessionAuthFilter` 적용
- [x] 테스트용 JWT 발급 도구 — 테스트 코드용 `support/TestJwtFactory` (2026-10-01)
  - 테스트 안에서 RSA 2048 키페어를 즉석 생성(로컬 키 파일 불필요), `publicKeyPem()`은 `event.public_key`와 같은 PEM 형식
  - 클레임 기본값 user-0001 / poctenant001 / event 1 / 90초 만료 / 무작위 jti, 각 값 변경·제거 가능
  - 정상(`sign`)·만료(`expired`)·다른 키 서명·페이로드 바꿔치기·`alg: none`·HS256(공개키를 HMAC 키로 쓰는 알고리즘 혼동 공격) 토큰 생성
  - 테스트 `TestJwtFactoryTest` 8개 통과 (로컬 `gen-test-keys.sh` 공개키와 PEM 형식 호환 포함)
- [→] 로컬 수동 테스트용 JWT 발급 CLI — M3로 이동
- [x] 설정 클래스 `TetraProperties` (`config/TetraProperties.java`, 2026-10-01) — `tetra.*`를 `ZoneId`·`Duration`·`Path`·enum으로 바인딩, 잘못된 값이면 기동 실패
  - [x] `session.redirect-url`: `/`로 시작하는 상대 경로만 (`http://…`, `//host`, `/\host`, 역슬래시·줄바꿈 거절 — 오리진 Host·오픈 리다이렉트·헤더 주입 방지)
  - [x] `queue.cursor-step` > 0, `cursor-window` ≥ 1초, `cursor-cache-s-maxage` ≥ 0, `session.ttl` > 0, `jwt.user-id-max-length` > 0, `stock.warmup-mode` ∈ {`if-absent`, `force`}
- [x] `Clock` 빈 (`config/AppConfig.java`) — `tetra.timezone`(Asia/Seoul) 기준. 현재 시각은 항상 이 Clock으로 얻고, 테스트에서는 `Clock.fixed`로 교체 (M0-1 S2)
  - [x] 테스트 `TetraPropertiesTest` 15개 통과 (정상 바인딩, Clock 타임존, 상대 경로 허용 3종, 거절 6종, 범위 위반 4종) + 실제 `values-local.yml`로 `contextLoads` 통과
- [x] 이벤트 메타 캐시 (`service/EventMetaCache.java`) — 기동 시(웹 서버가 요청 받기 전) `event`의 `start_at`·`public_key`·`tenant_id`를 메모리에 로딩, PEM은 이때 공개키 객체로 변환. 추가·변경 이벤트는 `reload()`로 다시 읽음(PoC는 자동 갱신 없음)
  - 읽기 전용 엔티티 `domain/Event`(필요 컬럼 4개, `CHAR(12)`·`INT UNSIGNED` 타입 지정) + `repository/EventRepository` — `ddl-auto: validate`로 DB 정의서 스키마와 일치 확인됨
- [x] `tenant_id` 형식 검증 (`JwtAuthFilter`) — 소문자·숫자 12자(`^[a-z0-9]{12}$`, DB CHECK와 같은 정규식), DB `CHAR(12)` ascii_bin이라 JPA에서는 `String`으로 매핑(변환 유틸 불필요, M0-4)

---

## M3. 세션 API — `GET /api/issuance/session`

- [x] 로컬 수동 테스트용 JWT 발급 CLI — `PoC/scripts/issue-test-jwt.sh [user_id] [event_id] [tenant_id] [ttl초] [base_url]` (openssl만 사용, `local-keys` 개인키로 RS256 서명 → 세션 API URL 출력)
- [x] JWT 서명·만료 검증 (M2 필터 연동) — M2 `JwtAuthFilter`에서 완료(`JwtAuthFilterTest`)
- [x] `user_id` 길이 128자 초과 시 400 — M2 `JwtAuthFilter`에서 완료(`JwtAuthFilterTest`)
- [x] `SET jti:{jti} 1 NX EX <exp까지 남은 초 + 시계 오차>` — 실패 시 401 `JWT_REUSED` (`redis/EntryStore`, `service/SessionService`)
- [x] 같은 `event_id` + `user_id`의 기존 세션 조회 (`session:idx:{eventId}:{userId}` → 세션이 살아 있는지까지 확인). 원자성은 Next Plan N1
  - [x] 있으면 재사용 (진입 카운터 증가 없음)
  - [x] 없으면 신규 세션 생성(해시·역참조 키 TTL 2h) + 진입 카운터 `entry:count:{eventId}` +1
- [x] `Set-Cookie` (HttpOnly · Secure · SameSite=Lax · Path=/ · Max-Age=7200, **Domain 속성 없음**) + 302 리다이렉트 (D6) — `controller/IssuanceController`
  - [x] `Location`은 설정값 그대로의 상대 경로(`{eventId}` 치환만). 요청 Host로 절대 주소를 만들지 않고 헤더를 직접 지정
  - [x] `server.tomcat.use-relative-redirects: true` 설정 (2026-10-01) — Boot 4.1.1 기본값이 `false`라 `sendRedirect("/")`도 Tomcat이 `http://{Host}/` 절대 주소로 바꾸기 때문
  - [x] `Secure`는 `tetra.session.cookie-secure` 설정값으로 (요청이 http로 들어와도 붙음)
  - [x] 302 응답에 `Referrer-Policy: no-referrer`(프론트 요청 — JWT가 든 URL이 Referer로 새지 않게), `Cache-Control: no-store`(Set-Cookie 응답 캐시 금지)
- [x] MySQL 접근이 없는지 확인 — 코드상 Redis(`EntryStore`·`SessionStore`)와 메모리 캐시(`EventMetaCache`)만 사용
- [x] 테스트 — `controller/SessionApiIntegrationTest` 9개(실제 Redis·필터) + M2 필터 테스트. 실제 앱(Tomcat)에서도 CLI 토큰으로 302·쿠키·`Location: /`(Host=오리진)·`JWT_REUSED`·Redis 세션 확인
  - [x] 정상 발급 → 쿠키 + 302
  - [x] 서명 위조 / 만료 JWT 거절 — M2 `JwtAuthFilter`에서 완료(`JwtAuthFilterTest`)
  - [x] JWT `tenant_id`가 `event.tenant_id`와 다르면 거절, 형식(12자 소문자·숫자)이 틀리면 400 — M2 `JwtAuthFilter`에서 완료(`JwtAuthFilterTest`)
  - [x] 다른 키로 서명한 토큰, `alg: none`·HS256 토큰 거절 — M2 `JwtAuthFilter`에서 완료(`JwtAuthFilterTest`)
  - [x] 존재하지 않는 `event_id` 토큰 거절 — M2 `JwtAuthFilter`에서 완료(`JwtAuthFilterTest`)
  - [x] 같은 jti 두 번째 요청 거절 (jti 키 TTL이 토큰 만료 무렵까지인지도 확인)
  - [x] 같은 사용자 재진입 시 세션 재사용 + 진입 카운터 미증가 (세션 만료 시 새 세션, 다른 사용자는 별도 세션 + 카운터 증가)
  - [x] 302 응답에 `Referrer-Policy: no-referrer`, `Cache-Control: no-store` 헤더 포함
  - [x] Host 헤더를 `origin.gamza-dev.shop`으로 보내도 `Location`이 상대 경로(`/...`) 그대로이고, `Set-Cookie`에 `Domain=`이 없음

---

## M4. 번호표 API — `POST /api/issuance/events/{eventId}/ticket`

- [x] 서버 시각 < `event.start_at`이면 Redis 호출 전에 409 `EVENT_NOT_STARTED` (`service/TicketService`, 이벤트 메타 캐시 + `Clock`, 2026-10-01)
- [x] `resources/redis/scripts/ticket-issue.lua` 작성 (왕복 1회, `redis/RedisScripts`로 등록, `redis/TicketStore`로 실행)
  - [x] 경로 `eventId`와 세션의 `event_id` 일치 검증 — `SessionAuthFilter`가 이미 확인하므로 Lua에서는 생략. 대신 세션이 그 사이 만료됐으면 번호를 소모하지 않고 -1 반환 → 401
  - [x] `INCR ticket:seq:{eventId}` 채번
  - [x] 세션 해시에 `ticket_number` + `queue_entered_at`(epoch 밀리초, D9) 기록 — 재발급 시 새 번호·새 시각으로 덮어씀
  - [x] 구멍 비율 측정 — 첫 발급 `INCR ticket:total:{eventId}`, 재발급 `INCR ticket:retry:{eventId}`. **구멍 비율 = retry ÷ (total + retry)**(전체 발급 번호 중 버려진 번호), 1인당 재발급 = retry ÷ total. 조회: `bash scripts/ticket-stats.sh [event_id]`
- [x] 응답 `{"success":true,"data":{"ticketNumber":number}}`
- [x] MySQL 접근이 없는지 확인 — 코드상 Redis + 메모리 캐시만
- [x] 테스트 — `TicketApiIntegrationTest` 4개 + `TicketServiceTest` 3개
  - [x] 시작 전 요청 거절 (시작 1초 전 409·Redis 미호출, 정각부터 발급 — 고정 Clock)
  - [x] 정상 채번, 세션에 번호·발급 시각 기록
  - [x] 재호출 시 새 번호로 덮어쓰기 + total 그대로·retry +1
  - [x] 다른 이벤트 `eventId`로 호출 시 거절 — `SessionAuthFilter`(403) 테스트로 확인
  - [x] 세션이 사라졌으면 Lua가 번호를 소모하지 않음
  - [x] 동시 200명 요청 시 번호 중복 없음·연속 번호
- [x] 실제 앱 확인 (2026-10-01): 가상 사용자 100명 입장 + 20명 새로고침 1회·5명 2회 → 실패 0, total 100 / retry 30 / seq 130, **구멍 비율 23.1%, 1인당 재발급 0.30** (기대값과 일치). 실제 비율은 사용자 새로고침 습관에 달려 있어 부하테스트에서 측정

---

## M5. 커서 폴링 API — `GET /api/issuance/events/{eventId}/queue/cursor`

- [x] 락 기반 피기백 구현 — `resources/redis/scripts/cursor-advance.lua` (2026-10-01): `SET advance-lock:{eventId} 1 NX PX {window}` 성공 시 `INCRBY cursor:serving:{eventId} {step}`, 현재 커서 반환
  - [x] 원자성을 위해 Lua로 구현 (`redis/CursorStore`, `service/QueueCursorService`)
  - [x] 커서 키가 없을 때 0으로 응답
- [x] 증가량(300)·윈도우(3초)를 values 파일 설정값에서 주입
- [x] D10 결정에 따라 이벤트 시작 전 커서 증가 막기 (Lua에 시작 여부 전달)
- [x] 응답 `{"success":true,"data":{"cursor":number}}` — 개인화 값 없음. 폴링 스펙 예시 코드를 이 형식(`data.cursor`)으로 수정
- [x] **인증 예외 처리** — `SessionAuthFilter`가 이 경로를 세션 조회 없이 통과 (M2-4)
- [x] **캐시 헤더** — 성공 `Cache-Control: public, max-age=0, s-maxage=1`(`tetra.queue.cursor-cache-s-maxage`), 에러 `no-store`(없는 이벤트 404, 잘못된 eventId 400)
- [x] 테스트 — `CursorApiIntegrationTest` 8개 + `QueueCursorServiceTest` 2개
  - [x] Lua를 동시에 1,000번 호출해도 한 윈도우에 정확히 1번(+300)만 증가 (윈도우 60초로 직접 호출)
  - [x] HTTP 동시 1,000건 동안 증가 횟수 ≤ 경과 윈도우 수 + 1 (1,000건 처리에 3초 넘게 걸려 윈도우가 지나는 것이 정상 — 첫 시도에서 확인)
  - [x] 같은 윈도우 안 재요청은 그대로, 윈도우 경과(락 만료) 후 다시 +300, 락 TTL 2~3초
  - [x] 설정값 반영 — 증가량·윈도우를 설정값으로 Lua에 전달하는 것을 단위 테스트로 확인
  - [x] 쿠키 없이 호출해도 200 + 커서 값, `Set-Cookie` 없음
  - [x] 성공 응답 캐시 헤더, 에러 응답 `no-store`
  - [x] 시작 전에는 증가 안 함, 정각부터 증가 (고정 Clock)
  - [ ] (배포 환경) 같은 엣지에서 1초 안에 연속 호출 시 두 번째 응답의 `x-cache: Hit from cloudfront` 확인, 부하테스트 시 캐시 히트율 측정
  - [ ] (배포 환경) 없는 `/api/*` 경로 호출 시 `index.html`이 아니라 실제 404 응답이 오는지 확인
- [x] 실제 앱 확인: 헤더 `public, max-age=0, s-maxage=1`, 약 3초마다 300 → 600 → 900 → 1200 → 1500, 없는 이벤트 404 `no-store`

---

## M6. 쿠폰 목록 · claim API

결정 (2026-10-01):
- **품절 응답 = A 방식**: 200 `{"success":true,"data":{"result":"SOLD_OUT","coupons":[]}}`. 성공은 200 `{"result":"SUCCESS","coupons":[...]}`. 대부분 사용자가 받는 정상 결과라 에러(4xx)로 다루지 않음. `ALREADY_CLAIMED`·`TICKET_REQUIRED`는 정상 흐름에서 일어나지 않으므로 409 에러 유지
- **재고 워밍업 시점 = Issuance Service 프로세스 시작 시**(로컬 `bootRun`, 배포는 Pod가 뜰 때마다). 여러 Pod가 각자 실행해도 `if-absent`라 처음 1번만 채워지고, 재시작·스케일아웃 때 차감분이 유지됨. Pod는 이벤트 시작 전(호스팅 시작 시점)부터 떠 있으므로 시작 시 재고는 준비된 상태. `force`는 로컬 초기화 전용(배포 환경 금지). Redis 데이터 유실 시 위험은 Next Plan N3

### M6-1. 재고 워밍업

- [x] 초기화 컴포넌트 `service/CouponCatalog` (2026-10-01) — 기동 시(웹 서버가 요청 받기 전, `InitializingBean`) MySQL `coupon.stock_count` → Redis `coupon:stock:{eventId}:{couponId}`. 쿠폰 이름·설명도 이때 메모리에 둠. (플랜의 `ApplicationReadyEvent`는 웹 서버가 열린 뒤라 그 사이 요청이 재고 0을 볼 수 있어 앞당김)
- [x] 재기동 시 이미 차감된 재고를 덮어쓰지 않도록 `SET NX` (`if-absent`), 로컬 리셋은 `warmup-mode: force`
- [x] `coupon.stock_count`를 UPDATE하는 코드 없음 — `domain/Coupon`은 `@Immutable` 읽기 전용

### M6-2. 쿠폰 목록 — `GET /api/issuance/events/{eventId}/coupons`

- [x] 쿠폰 이름·설명은 기동 시 캐시, 잔여 매수는 Redis `MGET` (`service/CouponService`, `redis/CouponStockStore`)
- [x] 응답: `{"success":true,"data":{"coupons":[{"couponId","name","description","remaining"}]}}` (couponId 순, 최대 10종)
- [x] 테스트: 잔여 매수가 Redis 기준(MySQL은 10 그대로)으로 나오는지

### M6-3. 쿠폰 발급 — `POST /api/issuance/events/{eventId}/coupons/claim`

- [x] 세션 쿠키 유효성 확인 (`SessionAuthFilter`) — 입장 자격 재검증은 생략(플랜 5-4). 번호표 없는 세션은 409 `TICKET_REQUIRED`
- [x] `resources/redis/scripts/coupon-claim.lua` 작성
  - [x] `SET claim:done:{eventId}:{userId} 1 NX` — 이미 처리된 사용자 즉시 거절(409 `ALREADY_CLAIMED`). 성공·품절 모두 표시
  - [x] 쿠폰 종류별 재고 > 0이면 `DECR` (D8), 전부 0이면 품절 — 재고는 음수가 되지 않음
  - [x] 발급된 couponId 목록 반환 (빈 목록 = 품절)
- [x] `issuance_history` 1행 INSERT — 성공·품절 모두 (`service/ClaimService`, `domain/IssuanceHistory`)
  - [x] 세션에서: `tenant_id`, `event_id`, `user_id`, `ticket_number`, `queue_entered_at`(KST)
  - [x] `served_at` = NULL(PoC 미사용), `result` = `SUCCESS` / `FAILED_SOLDOUT` 명시
  - [x] `(event_id, user_id)` UNIQUE 중복 키 오류(1062, `DataIntegrityViolationException`)는 "이미 기록됨"으로 처리(로그만, 응답은 정상)
- [x] Redis 차감 성공 후 INSERT 실패 시: 발급 결과는 그대로 응답 + 에러 로그(사용자·결과 포함), 재시도 없음 (리스크 표 "Redis ↔ MySQL 비원자성")
- [x] 응답: 성공·품절 모두 200 — `{"result":"SUCCESS","coupons":[{"couponId","name","description"}]}` / `{"result":"SOLD_OUT","coupons":[]}`. `ErrorCode.COUPON_SOLD_OUT` 삭제
- [x] 테스트 — `CouponClaimIntegrationTest` 9개
  - [x] 정상 발급 → 종류마다 1장(10장) + 재고 9 + history `SUCCESS` 1행(필드·`served_at` NULL·`ticket_number` 일치)
  - [x] 일부 종류만 남았으면 남은 종류만 발급
  - [x] 같은 사용자 재요청 → 409, 재고·history 변화 없음
  - [x] 재고 소진 후 요청 → 200 `SOLD_OUT` + history `FAILED_SOLDOUT`, 재고 음수 안 됨
  - [x] 번호표 없이 claim → 409, `claim:done`·재고·history 변화 없음
  - [x] history가 이미 있으면 1062 무시하고 정상 응답, 행 1개 유지
  - [x] 동시성: 150명 동시 claim → 정확히 10명 `SUCCESS`·140명 `SOLD_OUT`, 모든 재고 0(초과 발급 0), history 150행
  - [x] 워밍업 `if-absent`는 기존 값 유지, `force`는 덮어씀
- [x] 실제 앱 확인: 기동 로그 `Coupon stock warmup (IF_ABSENT): 10 of 10 keys written` → CLI 토큰 입장 → 번호표 131 → 쿠폰 10종 잔여 10 → claim `SUCCESS` 10장 → 재claim 409 → 잔여 9 → MySQL 이력 1행(`ticket_number` 131, `served_at` NULL, `SUCCESS`)

---

## M7. 전체 플로우 통합 테스트

- [x] Testcontainers(MySQL + Valkey) 기준 통합 테스트 구성 — `FullFlowIntegrationTest` (2026-10-01). 클라이언트 동작(폴링 스펙의 "받은 커서 최댓값 ≥ 내 번호면 claim")을 그대로 따라감, 3초 대기는 락 키 삭제로 대신
- [x] 시나리오: JWT 발급 → 세션(302·쿠키) → 번호표 → 커서 폴링(내 번호 도달까지) → 쿠폰 목록 → claim → 발급 이력 확인 (3초)
- [x] 다수 사용자 시나리오: 200명 진입 → **번호표 앞 10명이 10장씩 100장 성공, 190명 품절**, history 200행(SUCCESS 10 / FAILED_SOLDOUT 190) — 시드 재고가 10종 × 10장이고 한 사람이 종류마다 1장씩 받으므로(D8) 성공은 10명 (24초)
  - [x] 번호표 순서 = 발급 순서 확인 (성공한 10명이 번호가 가장 빠른 10명)
- [x] Redis 재고 합계 + 발급 장수 = 100 정합성 확인 (남은 재고 0 + 발급 100)
- [x] 로컬 데이터 초기화 — `bash scripts/reset-local.sh --yes` (`--yes` 없으면 실행 안 함): Valkey FLUSHDB → `issuance_history` TRUNCATE → MySQL `stock_count`로 재고 다시 채움. 앱 재시작 불필요. 테이블·시드·공개키까지 되돌리려면 `docker compose down -v && up -d --wait`
- [x] 실제 앱 시뮬레이션 — `bash scripts/simulate-users.sh [N]`: 초기화 후 200명 → SUCCESS 10 / SOLD_OUT 190 / 기타 0, 진입 200·번호 1~200·구멍 0%, MySQL 190/10행, 남은 재고 0 (6분 35초 — 대부분 Windows bash에서 사용자마다 openssl·curl 프로세스를 띄우는 비용이라 앱 성능과 무관, 부하테스트 도구가 아님)

---

## M8. 부하테스트 준비물 전달

- [ ] 부하테스트용 JWT 대량 발급 방법 (사용자 수만큼 서로 다른 `user_id`·`jti`)
- [ ] 5만 동시접속 시나리오 문서화 (진입 → 번호표 → 적응형 폴링 → claim)
- [ ] 검증 포인트 정리
  - [ ] 커서 증가량 300/3초(초당 100명)를 claim 엔드포인트가 받아내는지 (Redis Lua + MySQL INSERT)
  - [ ] 5만 명 처리 예상 소요 약 8분 20초 대비 실측
  - [ ] 초과 발급 0건, 중복 발급 0건
  - [ ] (선택) 구멍 비율 = retry ÷ total
- [ ] 관측 지표 정리 — API별 p95/p99 지연, 에러율, Redis CPU·커맨드 수, MySQL INSERT TPS
- [ ] 부하테스트 담당자에게 공유

---

## Next Plan (PoC 이후로 미룬 항목)

PoC는 **정상 사용자만 있다는 전제**로 진행합니다(2026-10-01 결정). 비정상 사용 패턴·경합에 대한 방어는 여기에 모아 두고 PoC 이후에 다룹니다.

| # | 항목 | PoC에서의 처리 | 나중에 할 일 |
|---|---|---|---|
| N1 | 세션 재사용 확인과 생성의 원자성 | "기존 세션 조회 → 없으면 생성"을 Redis 명령 여러 개로 나눠 처리. 같은 사용자가 거의 동시에 두 번 들어오면 세션이 2개 생길 수 있음(정상 사용자 전제라 무시) | 조회·생성·진입 카운터를 Lua 스크립트 하나로 묶어 원자적으로 처리 |
| N2 | Lua 스크립트의 여러 키가 서로 다른 해시 슬롯 | `ticket-issue.lua`가 `session:{sid}`와 `ticket:*:{eventId}`를 한 번에 다룸. 단일 노드(ElastiCache 클러스터 모드 꺼짐)에서는 문제없음 | 클러스터 모드로 가면 CROSSSLOT 오류 — 키에 해시 태그(예: `{e1}`)를 붙여 같은 슬롯으로 모으거나 스크립트를 나눔 |
| N3 | 이벤트 중 Redis 데이터 유실 시 재고 복원 | 재고 워밍업은 Issuance Service 시작(Pod마다) 시 `if-absent`로 실행. Redis 데이터가 사라진 뒤 Pod가 재시작하면 재고가 처음 값으로 다시 채워져 초과 발급 가능. `issuance_history`에 `coupon_id`가 없어 DB로 남은 재고를 계산할 수 없음 | 워밍업을 이벤트 배포 시 1회 작업으로 분리, 재고 키 지속성(AOF 등)·발급 내역 기반 복원 설계 |
| N4 | `claim:done:{eventId}:{userId}` 키 수명 | TTL 없이 남김 (세션보다 오래 살아야 재입장 후 중복 claim을 막을 수 있음) | 이벤트 종료(`end_at`) 이후 만료되도록 TTL 지정 또는 종료 후 일괄 정리 |

## 1차 완료 기준

- [x] 5개 API가 플랜 4절 계약대로 동작 (응답은 공통 형식 `data` 안, 2026-10-01)
- [x] 세션·번호표·커서 단계에서 MySQL 접근 0건, MySQL 쓰기는 claim 완료 시 1회뿐 — 코드상 이 세 단계는 Redis + 기동 시 메모리 캐시만 사용, MySQL 쓰기는 `ClaimService`의 INSERT 1곳
- [x] 초과 발급·중복 발급이 동시성 테스트에서 0건 — claim 동시 150명(성공 10·재고 0), 전체 흐름 200명, 같은 사용자 재claim 409
- [x] 커서 증가량·윈도우가 설정값으로 분리되어 코드 수정 없이 조정 가능 — `values.queue.cursor-step`·`cursor-window`
- [x] 로컬 docker-compose만으로 전체 플로우 재현 가능 — `gen-test-keys.sh` → `docker compose up` → `bootRun` → `reset-local.sh --yes` → `simulate-users.sh`
- (구멍 비율 측정은 1차 완료 조건이 아님 — 구현 완료, `ticket-stats.sh`)

**1차 완료 (2026-10-01)** — 남은 항목: M8 부하테스트 준비물, 배포 환경에서만 가능한 확인(M5 CloudFront 캐시 히트·`/api/*` 404), Next Plan N1~N4

---

## 리스크 · 메모

| 항목 | 내용 | 대응 |
|---|---|---|
| Redis ↔ MySQL 비원자성 | claim Lua로 재고 차감 후 history INSERT 실패 시 기록 누락 | PoC는 에러 로그로 추적, 운영 전 보완(아웃박스 등) 검토 |
| 커서가 실제 대기열보다 앞서감 | 폴링만 있으면 대기자 수와 무관하게 커서가 증가 | 품절 처리로 안정성 영향 없음, D10으로 시작 전 증가만 차단 |
| 재발급 번호 "구멍" | 새로고침마다 새 번호 발급, 이전 번호 복구 안 함 | 의도된 동작, 선택적으로 구멍 비율 측정 |
| `IN_PROGRESS`/`ABANDONED` 미저장 | 이탈률 집계 불가 | 플랜 3절에서 인지하고 넘어감, 필요 시 별도 설계 |
| CloudFront `/api/*` behavior 설정 | 쿠키가 오리진으로 전달되지 않거나, 커서 외 API가 캐시되면 세션 인증이 깨짐. cursor behavior가 `/api/*`보다 뒤에 있으면 캐시가 안 됨 | 플랜 2절 behavior 표를 인프라 담당자에게 전달하고 배포 시 확인 |
| 커서 역행 | 엣지마다 캐시가 따로라 클라이언트가 직전보다 작은 커서를 받을 수 있음 | 클라이언트가 받은 값의 최댓값만 사용(폴링 스펙 반영) |
| 오리진 Host | CloudFront → ALB 구간에서 Host가 오리진 주소로 바뀜. Host로 만든 절대 URL·쿠키 Domain은 사용자를 오리진으로 보냄 | 302는 상대 경로만, 쿠키 Domain 없음, Secure는 설정값. 설정 검증 + M3 테스트로 확인 |
| 커서 API 무인증 | 누구나 커서 값을 조회 가능 | 이벤트 공통 비민감 정보. 재고는 claim Lua가 보호 |

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-01 | 최초 작성 — PoC_개발_플랜.md 기준 진행 순서·체크리스트 정리 |
| 2026-10-01 | 문서를 `PoC/PLAN&DATA/`로 이동. D1~D6·D8~D11 확정, 0-2 values 파일 방식 추가, 0-3 D7 메모 추가 |
| 2026-10-01 | D7 확정 — CloudFront 라우팅으로 화면·API 호스트를 `{이벤트}.{tenant_id}.tetra.io`로 통일. CORS 변수·작업 제거, 0-3 갱신 |
| 2026-10-01 | ADR-0002 개정. M0 Seed 확보, M0-1 Seed 검토 결과·M0-2 도구 점검 추가, `tetra.timezone` 변수 추가 |
| 2026-10-01 | S1 A 방식 확정, 플랜 6절 기본값 확정, M0-3 ERD 원칙(변경 없음, 미사용 컬럼 비우기) 추가 |
| 2026-10-01 | M0 완료(JDK 21 설치, Docker 확인). D1을 Spring Boot 4.1.1로 변경. M1 프로젝트 생성 완료 |
| 2026-10-01 | 커서 캐싱 구체화 반영 — 커서 API 인증 제거, 캐시 헤더, 에러 `no-store`, M2·M5 체크리스트·리스크 추가 |
| 2026-10-01 | M1-2 완료 — docker-compose(MySQL 8.0.46, Valkey 7.2.14), init SQL 3개, 테스트 키 생성 스크립트, DB·Seed 검증 |
| 2026-10-01 | JWT 구성 방식 반영 — M2 `JwtAuthFilter` 세부 항목(키 선택 순서, RS256 고정, 로그 마스킹), M3 테스트 추가 |
| 2026-10-01 | DB 정의서 수정본(`_1.md`) 반영 — M0-4 추가(tenant_id CHAR(12), event.tenant_id, UNIQUE, served_at 미사용), M2·M3·M6 항목 갱신, Q1 확인 필요 |
| 2026-10-01 | DB 정의서 `_2.md` 반영 — C7(tenant_id 형식 CHECK, issuance_history 제외) 추가, 기준 문서를 `_2.md`로 변경 |
| 2026-10-01 | Q1 확정 — `queue_entered_at`은 D9(번호표 발급 시각) 유지 |
| 2026-10-01 | M0-4 완료 — init SQL을 정의서_2로 교체, 로컬 DB 재생성, CHECK·UNIQUE 동작 검증. M0 완료 |
| 2026-10-01 | M1-3 ① `values/values-local.yml` 생성, 0-2에 키 이름 규칙 추가 |
| 2026-10-01 | M1-3 ② `application.yml` 작성, `redirect-url` 임시값 `http://www.naver.com`, 앱 기동·MySQL 연결 확인 |
| 2026-10-01 | M1-3 ③ Testcontainers 이미지·init SQL을 docker-compose와 맞춤, 연결 확인 테스트 4개 통과 |
| 2026-10-01 | M1-3 ④ Actuator health 추가, 로컬 앱에서 MySQL·Redis `UP` 확인. M1 완료 |
| 2026-10-01 | M2-1 공통 응답·에러 처리(`ApiResponse`, `ErrorCode`, `BusinessException`, `GlobalExceptionHandler`) 완료, 테스트 6개 통과 |
| 2026-10-01 | 프론트 공유 반영 — 오리진 Host(`origin.gamza-dev.shop`) 때문에 302 상대 경로·쿠키 Domain 없음. `redirect-url` 임시값 `/`로 변경, M2·M3 항목 추가 |
| 2026-10-01 | `server.tomcat.use-relative-redirects: true` 추가 (Boot 기본 false라 sendRedirect가 Host로 절대 주소를 만듦) |
| 2026-10-01 | M2-2 `TetraProperties`(기동 시 검증) + `Clock` 빈 완료, 테스트 15개 통과 |
| 2026-10-01 | JWT 라이브러리 nimbus-jose-jwt로 결정. 프론트 요청 반영 — 세션 302 응답에 `Referrer-Policy: no-referrer`·`no-store` (M3) |
| 2026-10-01 | nimbus-jose-jwt 10.10 의존성 추가 |
| 2026-10-01 | M2-3② 테스트용 `TestJwtFactory` 완료(테스트 8개 통과), 로컬 CLI는 M3로 |
| 2026-10-01 | M2-3③ 이벤트 메타 캐시 + `JwtAuthFilter` 완료. 변수 `jwt.clock-skew`·`jwt.max-ttl` 추가, `test-private-key-path`를 문자열로 변경(Path 바인딩이 실제 기동에서 실패). 테스트 23개 추가(단위 19·통합 4), 전체 56개 통과, 로컬 앱 확인 |
| 2026-10-01 | M2-4 `SessionAuthFilter` + Redis 세션 형식(D5) 확정, 테스트 18개 추가(전체 74개 통과), 로컬 앱 확인. M2 완료 (로컬 JWT CLI는 M3) |
| 2026-10-01 | 원칙 추가: PoC는 정상 사용자 전제. 경합·비정상 사용 방어는 Next Plan으로 (N1 세션 재사용 원자성) |
| 2026-10-01 | M3 세션 API 완료 — `SessionService`(jti·세션 재사용·진입 카운터), `IssuanceController` 302·쿠키, 로컬 JWT CLI. 테스트 9개 추가(전체 83개 통과), 실제 앱 확인 |
| 2026-10-01 | M4 번호표 API 완료 — `ticket-issue.lua`(채번·세션 기록·total/retry), `TicketService`(시작 전 거절), 통계 스크립트. 테스트 7개 추가(전체 90개 통과), 100명 시뮬레이션 구멍 비율 23.1% 확인. Next Plan N2(클러스터 모드 CROSSSLOT) 추가 |
| 2026-10-01 | M5 커서 API 완료 — `cursor-advance.lua`, 캐시 헤더, 테스트 10개 추가(전체 100개 통과), 실제 앱 3초 증가 확인. 폴링 스펙 응답 형식을 `data.cursor`로 수정 |
| 2026-10-01 | M6 결정 — 품절 응답 A(200 + `result: SOLD_OUT`), 재고 워밍업 시점 = Issuance Service 시작(Pod마다, if-absent). Next Plan N3(Redis 유실 시 재고 복원) 추가 |
| 2026-10-01 | M6 쿠폰 목록·claim 완료 — `CouponCatalog`(워밍업), `coupon-claim.lua`, `ClaimService`(이력 1회 INSERT·1062 처리), 품절 200. 테스트 9개 추가(전체 109개 통과), 실제 앱 전체 흐름 확인. Next Plan N4 추가 |
| 2026-10-01 | M7 전체 흐름 완료 — `FullFlowIntegrationTest`(1명·200명), `reset-local.sh`, `simulate-users.sh`. 전체 111개 테스트 통과. 1차 완료 기준 충족 |
| 2026-10-01 | 플랜 문서를 실제 구현과 동기화 — 응답 형식·에러 코드 표, 4-1~4-5 처리 세부, 커서 Lua 예시, 6절 상태, 7절 실제 패키지 구조, 8절 워밍업 시점·실행 순서, 9절 진행 상태, 5절 11~13번 |
| 2026-10-01 | ADR-0001·0002를 구현 결과와 동기화 (커서 Lua·Redis 클러스터 모드 전제·재고 유실 위험·오리진 Host·Referrer-Policy / 필터 세부·Store 계층·메모리 캐시·기동 시 설정 검증·라이브러리 선택·품절 200) |

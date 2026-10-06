# Tetra PoC — 프런트·백엔드 통합 점검

- 작성일: 2026-10-01
- 대상 독자: 프런트 담당, 백엔드 담당 (연동 작업 전에 함께 볼 문서)
- 점검 대상
  - 프런트: `PoC_front/tetra-poc-front` 커밋 `4238ddc` "feat: 2단계 실제 API 연결 + 서빙 커서 캐시 대응"
  - 백엔드: `PoC/issuance-service` (1차 완료 시점, 테스트 111개 통과)
- 확인 방법: 프런트 코드 전체를 읽고, **실제로 프런트 개발 서버(Vite, 5173) → 백엔드(8080)를 연결해** API 5개를 호출해 응답과 프런트 코드의 해석을 비교했다. 프런트 단위 테스트 21개는 그대로 통과한다. 프런트 저장소의 코드는 수정하지 않았다.

---

## 1. 요약

**세션 발급(302·쿠키)은 그대로 연결된다. 나머지 API 4개는 지금 상태로 붙이면 화면이 동작하지 않는다.** 원인은 대부분 하나다 — 백엔드가 모든 응답을 공통 형식 `{"success":true,"data":{...}}`로 감싸는데, 프런트는 감싸지 않은 값을 기대한다. 프런트 문서도 이 API들의 응답을 "임시·미정"으로 두었으므로, 이 문서 3절의 확정 계약으로 맞추면 된다.

| # | 등급 | 항목 | 증상 (지금 그대로 연결하면) |
|---|---|---|---|
| I1 | ✅ 해결(86bafa0) | 공통 응답 형식(`data` 래퍼) 미처리 | 아래 I2~I4의 직접 원인 |
| I2 | ✅ 해결(86bafa0) | 번호표: 프런트 `res.ticket` ↔ 백엔드 `data.ticketNumber` | 번호가 `undefined` → 폴링이 시작되지 않아 **03 화면에서 영원히 대기** |
| I3 | ✅ 해결(86bafa0) | 커서: 프런트 `{cursor}` ↔ 백엔드 `{success, data:{cursor}}` | `parseCursorResponse`가 매번 실패 → 5회 후 **에러 화면** |
| I4 | ✅ 해결(86bafa0) | 쿠폰 목록: 프런트 배열 ↔ 백엔드 `data.coupons` | `coupons.map`에서 **04 화면 크래시** |
| I5 | ✅ 해결(86bafa0) | claim 결과: 프런트 `res.result`·`FAILED_SOLDOUT` ↔ 백엔드 `data.result`·`SOLD_OUT` | `result`가 `undefined`라 **발급에 성공한 사용자에게 "모두 소진" 모달**이 뜸 |
| I6 | ✅ 해결(38178bb) | 에러 응답 처리 없음 (번호표·쿠폰 목록·claim) | 시작 전(409)·세션 없음(401)·중복 claim(409) 등에서 화면이 멈추거나 아무 반응 없음 |
| I7 | 🟠 기능 | 로컬 프록시: `.env.local`의 `VITE_API_PROXY_TARGET`이 적용되지 않음 | `/api/*` 요청에 `index.html`이 200으로 돌아옴 (README 안내대로 하면 연결 안 됨) |
| I8 | ✅ 해결 | 이벤트 ID를 호스트에서 얻는 규칙 | 호스트 첫 라벨은 해시값이라 숫자 event_id가 없음 → **세션 302가 `/?event={eventId}`로 넘기도록 백엔드 반영(2026-10-02)**. 프런트는 코드 변경 없이 동작, 문서·폴백만 정리 권장 |
| I9 | 🟡 확인 | 이벤트 시작 시각: 프런트 더미(지금+15초) ↔ 백엔드 DB `start_at` | 카운트다운과 서버의 "시작 전 거절"이 서로 다른 시각을 봄 |
| I10 | 🟡 확인 | 세션 발급 실패 시 화면 | JWT 오류가 브라우저에 JSON 그대로 보임 |
| I11 | 🟡 확인 | 04 화면 "받을 수 있는 쿠폰 N장" | 재고 0인 종류도 세어 표시 |
| I12 | ℹ️ 참고 | 개발 서버에서 번호표 요청 2번 (StrictMode, 개발 모드 전용) | **운영 영향 없음** — `dist/` 빌드는 1번. 프런트 수정 불필요, 구멍 비율·번호 측정은 빌드 결과물로 (2-3절) |

맞는 부분(문제 없음)은 5절에 정리했다.

---

## 2. 항목별 상세

### I1~I5. 응답 형식 불일치 (실측)

프런트 개발 서버를 거쳐 실제로 호출한 결과다.

| API | 백엔드 실제 응답 | 프런트 코드가 읽는 값 | 결과 |
|---|---|---|---|
| `POST …/ticket` | `200 {"success":true,"data":{"ticketNumber":1}}` | `res.ticket` (`src/pages/QueueTicket.tsx` 16행) | `undefined` → `useQueuePolling`이 번호 미확정으로 보고 시작 안 함 |
| `GET …/queue/cursor` | `200 {"success":true,"data":{"cursor":600}}` | `parseCursorResponse(body)` (`src/api/index.ts` 109행) | 최상위에 `cursor`가 없어 예외 → 재시도 5회 → 에러 UI |
| `GET …/coupons` | `200 {"success":true,"data":{"coupons":[…10개]}}` | 배열로 사용 (`CouponIssuance.tsx` 18·66행) | 객체에 `.map` 호출 → TypeError |
| `POST …/coupons/claim` | `200 {"success":true,"data":{"result":"SUCCESS","coupons":[…10장]}}` | `res.result` (`CouponIssuance.tsx` 31행) | `undefined` → `result !== null`이라 모달이 열리고 `'SUCCESS'`가 아니므로 **품절 문구** 표시 |

- I5는 특히 위험하다. 화면이 깨지지 않고 "정상처럼" 틀린 결과를 보여 준다.
- 품절 값도 다르다. 프런트는 DB `issuance_history.result` 값(`FAILED_SOLDOUT`)을 따랐고, 백엔드 API는 `SOLD_OUT`으로 확정했다(DB에는 여전히 `FAILED_SOLDOUT`으로 저장). API 값과 DB 값은 별개다.
- 커서 응답 형식은 폴링 스펙 문서(`PoC/PLAN&DATA/폴링 클라이언트 구현 스펙.md`)를 2026-10-01에 `data.cursor`로 수정했지만, 프런트 코드와 프런트 `CLAUDE.md`(§5 "응답 `{ "cursor": number }` 확정")에는 아직 반영되지 않았다.

**제안 (프런트, `src/api/` 안에서만 수정 — 프런트 설계 원칙 그대로 화면 코드는 대부분 그대로):**

```ts
// 공통 응답을 벗겨 data 만 돌려주는 도우미 (예시)
type ApiOk<T> = { success: true; data: T }
type ApiFail = { success: false; error: { code: string; message: string } }

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { credentials: 'same-origin', ...init })
  const body = (await res.json().catch(() => null)) as ApiOk<T> | ApiFail | null
  if (!res.ok || !body || body.success !== true) {
    throw new ApiError(res.status, body && 'error' in body ? body.error.code : 'UNKNOWN')
  }
  return body.data
}

requestTicket → request<{ ticketNumber: number }>(…)      // 화면: res.ticketNumber
getCoupons    → (await request<{ coupons: Coupon[] }>(…)).coupons
claimCoupons  → request<{ result: 'SUCCESS' | 'SOLD_OUT'; coupons: … }>(…)
getQueueCursor→ parseCursorResponse((body as ApiOk<unknown>).data)  // index.html 방어는 그대로 유지
```

- `getQueueCursor`의 캐시 규칙(쿼리·`cache`·헤더·`credentials` 변경 금지)은 그대로 지킨다. 본문을 읽는 위치만 `data`로 바뀐다.
- `parseCursorResponse` 단위 테스트에 "공통 형식으로 감싼 정상 응답" 케이스를 추가하면 된다.

### I6. 에러 응답 처리

`requestTicket`·`getCoupons`·`claimCoupons`가 실패(reject)해도 화면에서 잡지 않는다. 폴링(커서)만 재시도·에러 UI가 있다. 백엔드가 실제로 돌려주는 에러와 지금 화면의 반응:

| 상황 | 백엔드 응답 | 지금 프런트 반응 | 제안 |
|---|---|---|---|
| 세션 없이 03·04 접근(세션 API를 거치지 않음, 쿠키 만료 2시간) | 401 `SESSION_NOT_FOUND` | 03: 번호 미확정으로 "-명"에서 멈춤 / 04: "불러오는 중..."에서 멈춤 | 안내 + 테넌트 페이지로 돌아가기 (화면설계서 미정 → TODO) |
| 이벤트 시작 전 번호표 요청 (I9) | 409 `EVENT_NOT_STARTED` | 03에서 멈춤 | 02로 돌려보내기 또는 안내 |
| 04에서 새로고침 후 다시 "쿠폰 받기" | 409 `ALREADY_CLAIMED` | 버튼만 다시 활성화, 아무 반응 없음 | "이미 발급 처리됨" 안내 (결과 재조회 API는 없음) |
| 03을 거치지 않고 04를 직접 열고 claim | 409 `TICKET_REQUIRED` | 아무 반응 없음 | 03으로 보내기 |
| 다른 이벤트 쿠키로 접근 | 403 `SESSION_EVENT_MISMATCH` | 멈춤 | 세션 없음과 같은 처리 |

전체 에러 코드 목록은 `PoC_개발_플랜.md` 4절 공통 표에 있다. 에러 화면은 프런트 `CLAUDE.md` §10에 "미정"으로 남아 있으므로, 최소한 **멈추지 않게(reject를 잡아 안내 문구 표시)** 하는 것을 먼저 제안한다.

### I7. 로컬 프록시 설정이 `.env.local`에서 읽히지 않음 (실측)

- `vite.config.ts`는 `process.env.VITE_API_PROXY_TARGET`을 읽는다. 그런데 Vite는 설정 파일을 평가할 때 `.env*` 파일을 `process.env`에 넣어 주지 않는다(`loadEnv`를 직접 불러야 함).
- 실측: `.env.local`에 `VITE_API_PROXY_TARGET=http://localhost:8080`만 두고 `npx vite`를 띄우면, `GET /api/issuance/events/1/queue/cursor`에 **`index.html`이 200(text/html)** 으로 돌아왔다. 셸 환경변수로 넘기면(`VITE_API_PROXY_TARGET=http://localhost:8080 npx vite`) 정상 프록시된다.
- 제안 (프런트): `defineConfig(({ mode }) => { const env = loadEnv(mode, process.cwd(), ''); … env.VITE_API_PROXY_TARGET … })`. 그 전까지는 셸 환경변수로 실행.
- 덤으로, 이 실측은 `parseCursorResponse`의 "index.html이 200으로 오는 경우 방어"가 실제로 필요한 상황임을 보여 준다.

### I8. 이벤트 ID — 호스트 형식이 문서마다 다름 (✅ 2026-10-02 해결)

| 문서 | 호스트 형식 |
|---|---|
| 프런트 `CLAUDE.md` §7, `src/lib/eventId.ts` | `{event id}.{tenant id}.루트도메인` — 첫 라벨이 **숫자 event_id**라고 보고 파싱 |
| `PoC 데이터베이스 정의서_2.md` 3장·9장 #8 | `{event-slug}.{tenant_id}.루트도메인` |
| 프런트 담당 공유 예시 (Referrer-Policy 설명) | `k7f2q9.poctenant001.gamza-dev.shop` — 첫 라벨이 숫자가 아님 |
| 백엔드 플랜·ADR | `{이벤트}.{tenant_id}.tetra.io` (형식 미지정) |

- 프런트 파싱 로직에 실제 예시를 넣어 보면 `1.poctenant001.gamza-dev.shop` → 1, `k7f2q9.poctenant001.gamza-dev.shop` → **null → 기본값 1**. 즉 slug 호스트에서는 경고 없이 항상 이벤트 1의 API를 부른다. 이벤트가 하나뿐인 PoC에서는 우연히 동작하지만, 이벤트가 둘 이상이면 틀린 이벤트로 요청한다.
- 백엔드는 경로의 `eventId`(숫자)만 받는다. slug → event_id 변환 API는 없다. DB에는 `event.subdomain` 컬럼(UNIQUE, 시드는 NULL)이 있다.
- **확정 (2026-10-02, 프런트 담당 답변)**
  - 도메인 첫 라벨은 **해시값(event slug, 예 `k7f2q9`)** 이다. 이벤트 신청 승인 워크플로우에서 만든다. 목적은 UX — `1.awvhaskdfj.tetra.io`처럼 숫자가 보이는 주소를 피하려는 것.
  - 별도 컬럼은 없고 `event.subdomain`에 `k7f2q9.poctenant001` 형태로 저장한다.
  - DB `event_id`는 그대로 `INT UNSIGNED AUTO_INCREMENT`. **API는 slug를 쓰지 않고 숫자 `event_id`를 파라미터로 받는다.**
- **적용한 방법 (A)**: 세션 발급 302 목적지를 `/?event={eventId}`로 바꿨다(values `tetra.session.redirect-url`). 브라우저는 `/?event=1` 같은 주소로 02에 도착하고, 프런트 `getEventId()`가 `?event=`를 최우선으로 읽으며 02 → 03 → 04 이동 때 쿼리를 그대로 넘기므로 **프런트 코드 변경 없이** 맞는 event_id로 API를 부른다. 새로고침해도 주소에 남는다. 사용자가 `?event=`를 바꾸면 `SessionAuthFilter`가 세션의 이벤트와 달라 403으로 막는다. 백엔드 테스트 112개 통과(`Location: /?event=1` 확인).
- **프런트 권장 정리** (기능 문제는 아님):
  - `CLAUDE.md` §7·`src/lib/eventId.ts` 주석의 "호스트 첫 라벨이 event id" 설명을 "첫 라벨은 해시값, event id는 `?event=`(세션 302가 붙여 줌)"로 수정. `?event=`는 개발용이 아니라 **운영 경로**가 된다.
  - `?event=`가 없고 호스트도 숫자가 아니면 지금은 조용히 기본값 1을 쓴다. 세션 없이 직접 들어온 경우라 어차피 API가 401을 주지만, 기본값 대신 "잘못된 접근" 처리로 바꾸는 것을 권장(I6과 함께).

### I9. 이벤트 시작 시각이 두 군데

- 프런트 02 카운트다운은 더미 `startAt`(지금 + 15초, `?startsIn=`)을 쓴다 — 이벤트 정보 API가 없기 때문(프런트 `CLAUDE.md` §5).
- 백엔드는 DB `event.start_at`(시드 2026-09-29 10:00 KST, 이미 지남) 기준으로 번호표·커서를 판단한다.
- 지금 시드로는 서버가 이미 "시작됨"이라 문제가 드러나지 않는다. 시작 시각이 미래인 이벤트로 테스트하면, 프런트 카운트다운이 끝나 03으로 가도 서버가 409를 줄 수 있다(I6과 겹침).
- 선택지: (a) PoC는 더미 유지 + 409 처리만 추가, (b) 이벤트 정보 조회 API 추가(백엔드 범위 밖이던 항목 — 추가하면 API 6개). **결정 필요 (6절 Q2)**.

### I10. 세션 발급 실패 화면

- 세션 API는 브라우저 이동(테넌트 302)으로 열린다. JWT가 만료·재사용·위조면 백엔드는 401/400 JSON을 돌려주고, 사용자는 그 JSON을 화면에 그대로 본다(프런트 코드는 이 경로에 관여하지 않음).
- 선택지: 백엔드가 실패 시에도 프런트 에러 경로로 302(예: `/?error=JWT_EXPIRED`), 또는 PoC에서는 그대로 둠. **결정 필요 (6절 Q3)** — PoC 정상 흐름에는 영향 없음.

### I11. 04 화면 쿠폰 개수 표시

- "받을 수 있는 쿠폰 {coupons.length}장"은 재고가 0인 종류도 센다. 백엔드는 재고가 남은 종류마다 1장씩만 발급한다(D8). `remaining`을 쓰면 정확히 표시할 수 있다(`coupons.filter(c => c.remaining > 0).length`).
- claim 성공 응답에는 실제로 발급된 쿠폰 목록(`data.coupons`)이 있으나 프런트는 쓰지 않는다. 화면설계서 05에 목록 표시가 없으면 그대로 둬도 된다.

---

## 2-1. 프런트 1차 수정 확인 요청에 대한 답 (2026-10-02)

프런트가 `src/api/unwrapApiEnvelope.ts`(봉투에서 `data`를 꺼내는 함수)를 추가해 커서와 공용 `request()`(번호표·쿠폰 목록·claim)에 적용했고, 백엔드에 "세 API도 이미 봉투 형식인지" 확인을 요청했다. 프런트 변경은 아직 원격에 올라오지 않아 설명 기준으로 답한다.

- **답: 예. API 5개 모두, 성공·에러 전부 처음부터 `{success, data}` / `{success:false, error:{code,message}}` 형식이다.** 형식이 바뀐 적은 없다 — 프런트가 지금까지 동작한 것은 실제 백엔드가 아니라 목(mock)과 임시 백엔드였기 때문이다. "전에는 되다가 지금 깨진" 경우는 없다.
- **봉투를 벗긴 뒤에도 남는 불일치** (I2·I4·I5 — 봉투만으로는 해결되지 않음):

  | API | 봉투를 벗긴 `data` | 프런트가 읽는 값 | 남는 문제 |
  |---|---|---|---|
  | 번호표 | `{"ticketNumber":1}` | `res.ticket` | 필드 이름이 달라 여전히 `undefined` → 03에서 대기 |
  | 쿠폰 목록 | `{"coupons":[…]}` | 배열 | 여전히 배열이 아님 → `.map` 크래시. `data.coupons`를 꺼내야 함 |
  | claim | `{"result":"SUCCESS"\|"SOLD_OUT","coupons":[…]}` | `'SUCCESS' \| 'FAILED_SOLDOUT'` | 성공은 맞게 동작. 품절은 `'SOLD_OUT'`이 와서 else 분기로 품절 문구가 우연히 맞게 뜸 — 타입을 `'SOLD_OUT'`으로 맞출 것 |
  | 커서 | `{"cursor":600}` | `parseCursorResponse` | ✅ 해결 |

- **"봉투가 아니면 그대로 통과" 안전장치는 빼는 것을 권장**: 백엔드는 봉투가 아닌 JSON을 보내지 않으므로, 그대로 통과하는 경우는 설정 오류 같은 비정상 응답뿐이다. 통과시키면 그 오류가 `undefined`로 숨어 버린다(I5처럼). 봉투가 아니면 실패로 처리하는 편이 원인을 찾기 쉽다. 커서는 `parseCursorResponse`가 한 번 더 막아 주지만 `request()` 쪽은 막는 장치가 없다.
- **에러 응답도 봉투**: HTTP 상태가 4xx/5xx이고 본문이 `{"success":false,"error":{"code":"SESSION_NOT_FOUND",…}}`이다. `res.ok`가 false일 때 본문의 `error.code`를 읽으면 I6(에러 처리)에 그대로 쓸 수 있다.
- **함께 알릴 변경 (Q1)**: 세션 302가 이제 `/?event={eventId}`(예 `/?event=1`)로 간다. 프런트는 코드 변경 없이 동작한다.

---

## 2-2. 프런트 2차 질문(응답 필드·에러 코드·Redis·시각)에 대한 답 (2026-10-02)

코드와 실제 응답으로 확인한 내용이다.

**응답 필드**
- 쿠폰 목록 항목: `couponId`(number), `name`, `description`, `remaining`(number) 4개. `description`은 DB NOT NULL이라 항상 문자열로 온다(빈 문자열일 수는 있음). 실제 예: `{"couponId":1,"name":"PoC 쿠폰 1","description":"PoC 쿠폰입니다.","remaining":9}`
- `remaining`은 **Redis 실시간 재고**(DB `stock_count`는 초기값일 뿐 바뀌지 않음). 목록을 연 뒤 claim 전까지 다른 사용자 발급으로 줄어들 수 있다. 발급 여부는 claim 시점의 Redis 재고로 결정된다.
- claim의 `data.coupons`는 **이번 요청으로 실제 발급된 쿠폰**(`couponId`·`name`·`description`)이다. 재고가 남은 종류마다 1장씩이라 일부 종류만 올 수 있고, 품절이면 빈 배열. 화면설계서에 목록 표시가 없으면 쓰지 않아도 된다.

**에러 코드** — 전체 표는 3절·`PoC_개발_플랜.md` 4절. API별:

| API | 올 수 있는 에러 |
|---|---|
| 번호표 | 401 `SESSION_NOT_FOUND`, 403 `SESSION_EVENT_MISMATCH`, 409 `EVENT_NOT_STARTED`, 404 `EVENT_NOT_FOUND`, 400 `INVALID_REQUEST`, 500 `INTERNAL_ERROR` |
| 커서 | 404 `EVENT_NOT_FOUND`, 400 `INVALID_REQUEST`, 500 (세션 관련 에러는 오지 않음 — 인증 없음) |
| 쿠폰 목록 | 401 `SESSION_NOT_FOUND`, 403 `SESSION_EVENT_MISMATCH`, 400, 500 |
| claim | 401 `SESSION_NOT_FOUND`, 403 `SESSION_EVENT_MISMATCH`, 409 `TICKET_REQUIRED`, 409 `ALREADY_CLAIMED`, 400, 500 |

- 세션 쿠키가 필요한 API는 세션 확인이 먼저라, 세션 없이 잘못된 메서드로 부르면 405가 아니라 401이 온다.
- **요청 제한·이벤트 종료는 백엔드에 없음**: 앱은 429를 보내지 않는다. 429·502·503·504가 오면 CloudFront·ALB 등 인프라가 만든 것이라 **봉투 형식이 아니다**(본문을 믿지 말고 상태 코드로 판단). 이벤트 종료(`end_at`) 검사도 없어 종료 후에도 번호표·claim이 동작한다 → 백엔드 할 일 B1.
- 세션 만료: 401 `SESSION_NOT_FOUND`.
- 재시도: **4xx는 재시도하지 않는다**(결과가 바뀌지 않음). 5xx·네트워크 오류·인프라 429/502/503/504만 재시도(간격을 늘려 가며 권장). 커서 폴링도 404·400이면 멈추는 것이 맞다.
- `ALREADY_CLAIMED`: 이 이벤트에서 이 사용자가 이미 claim을 **처리받은** 경우(성공·품절 모두)에만 온다. 세션이 아니라 사용자 기준이라 재입장해도 같다. 이전 결과가 품절이었을 수도 있으므로 "성공"으로 보여 주면 안 되고, "이미 참여한 이벤트입니다" 같은 중립 안내를 권장. 이전 결과를 다시 조회하는 API는 없다.

**번호표·커서·세션 (Redis)**
- 번호표: 같은 세션이 다시 부르면 **매번 새 번호**(중복 발급을 막지 않음, 이전 번호는 버려짐). 프런트 가정과 같다.
- 번호 연속성: 이벤트당 1부터 모든 사용자 공통으로 1씩 증가(`INCR`), 동시 요청에도 중복 없음. 재발급으로 버려진 번호 외에는 비지 않는다(세션이 만료돼 실패하면 번호를 소모하지 않음). 한 사용자의 번호가 연속인 것은 아니다.
- 커서: 오리진 값은 정상 상황에서 줄지 않는다(3초마다 +300, 시작 전에는 증가 없음). **Redis 데이터가 사라지면(장애·초기화) 0부터 다시 시작**해 크게 줄 수 있다 — 최댓값 유지 로직이 있으면 화면은 멈춰 보일 수 있으나 깨지지 않음. 엣지 캐시로 잠깐 작은 값이 오는 것은 기존 안내대로.
- 세션 TTL: **세션 생성 후 2시간 고정**, 대기·폴링 중에도 갱신되지 않는다(재입장으로 세션을 재사용해도 연장 안 됨). 커서는 인증이 없어 폴링 중 만료돼도 커서 응답은 정상이고, **만료는 다음 쿠폰 목록·claim에서 401 `SESSION_NOT_FOUND`로 드러난다.**

**시각**
- 서버 시각 API는 없다. 모든 응답에 `Date` 헤더가 붙는다(초 단위, GMT). 같은 출처라 JS에서 읽을 수 있다.
- 단 커서 응답은 CloudFront가 최대 약 1초 캐시하므로 `Date`가 그만큼 과거일 수 있다(`Age` 헤더가 붙으면 더해서 보정). 보정용으로는 캐시되지 않는 응답의 `Date`가 더 정확하다. 카운트다운 시작 시각 자체의 출처는 아직 결정 전(6절 Q2).

**백엔드 할 일·결정 (이 질문에서 나온 것)**
- B1 이벤트 종료 처리: ✅ **확정(2026-10-02)** — `end_at` 이후 번호표·claim은 409 `EVENT_ENDED`(재시도하지 않음). 세션 발급·커서·쿠폰 목록은 막지 않음. **구현 완료(2026-10-02, 테스트 118개 통과)** — 프런트에 알릴 것.
- B2 세션 TTL 갱신 여부: 지금은 2시간 고정. 대기가 2시간을 넘지 않는 PoC에서는 문제없음.

---

## 2-3. 프런트 수정본 재점검 — `86bafa0` (2026-10-02)

프런트 `86bafa0 fix: API 응답 봉투 형식 확정 반영 (번호표/쿠폰/claim)`을 받아 코드 검토 + **실제 브라우저(헤드리스 Edge)로 백엔드에 연결**해 확인했다.

| 확인 | 결과 |
|---|---|
| 코드 | `unwrapApiEnvelope`(봉투 아니면 실패, `error.code`를 `ApiError`에 담음), `ticketNumber`→`ticket`·`data.coupons`→배열·`SOLD_OUT`→`FAILED_SOLDOUT`을 `src/api/`에서 옮겨 담음. 커서 캐시 규칙 유지. 백엔드 계약과 충돌 없음 → **I1~I5 해결** |
| 프런트 단위 테스트·빌드 | 25개 통과, `npm run build` 성공 |
| 브라우저: 세션 URL → 302 → 02 | `/?event=1`로 도착, 대기방·카운트다운 표시, Redis에 세션 생성 |
| 브라우저: 03 → 번호표 → 커서 폴링 → 04 | 04에 "받을 수 있는 쿠폰 10장", 쿠폰 이름·설명이 실제 응답으로 표시 |
| 브라우저: 세션 없이 03·04 | 03 "-명", 04 "불러오는 중..."에서 멈춤 → **I6 그대로 남음** |
| claim(버튼 클릭) | 헤드리스로는 클릭하지 못해 API 단위로만 확인(2절). 사람이 브라우저에서 한 번 눌러 볼 것 |

**새로 찾은 것 — 개발 서버에서는 번호표 요청이 2번 나감 (I12)**
- `npm run dev`(개발 모드)에서는 React StrictMode가 effect를 두 번 실행해 03 진입 시 `POST /ticket`이 2번 나간다. 화면은 두 번째 응답만 쓰지만 서버는 번호를 2개 쓴다. 실측: 사용자 1명에 seq 2, retry 1, **구멍 비율 50%**.
- 운영 빌드(`npm run build` → `vite preview`)에서는 1번만 나간다. 실측: seq 1, retry 0, 구멍 비율 0%.
- 영향: **운영에는 없음**(S3·CloudFront에 올라가는 것은 `dist/`). 프런트 수정 불필요. 다만 **구멍 비율·번호 순서를 개발 서버로 측정하면 틀린 값**이 나오므로, 측정·시연은 빌드 결과물로(`npm run build && npx vite preview`).

**남은 프런트 작업**
- I6 에러 처리: `requestTicket`·`getCoupons`·`claimCoupons` 실패를 화면에서 잡아 안내(코드별 동작은 2-2절 기준). 이제 `ApiError.code`로 분기할 수 있다.
- 폴링 재시도: 4xx(`ApiError.status` 400~499)는 재시도하지 않기(2-2절 8번).
- `CLAUDE.md` §5 세션 발급 행의 "02(`/`)로 돌려보낸다" → `/?event={eventId}`로 수정, §7 호스트 규칙 정리(I8).

---

## 2-4. 프런트 3차 질문(이벤트 정보·종료·순서·재접근)에 대한 답 (2026-10-02)

| # | 질문 | 답 |
|---|---|---|
| 1 | 이벤트 정보(이름·시작/종료·배너·복귀 주소) 출처 | **확정**: 조회 API `GET /api/issuance/events/{eventId}/info`, 인증 없음, 응답 `{eventId,name,startAt,endAt,bannerUrl,returnUrl}`(시각은 `+09:00` ISO, 배너·복귀 주소는 빈 문자열 가능), CDN 60초 캐시. **구현 완료(2026-10-02)** |
| 1' | 지터 상한 | 프런트 설정 유지(백엔드가 내려주지 않음). 프런트 `config.ts`(1000)와 `CLAUDE.md`(2000) 값만 하나로 맞추면 됨 |
| 2 | 이벤트 종료 | **구현됨**: `end_at` 이후 번호표·claim이 409 `EVENT_ENDED`. 쿠폰 목록·커서·세션 발급은 해당 없음(정상 응답). 재시도하지 않음 |
| 3 | 순서 전 접근 | 서버는 순서(번호 ≤ 커서)를 **검증하지 않는다**(PoC 결정). 순서 전 claim도 재고가 있으면 성공한다 — 프런트가 gap ≤ 0일 때만 `/issue`로 가는 것이 순서를 지키는 유일한 장치. 쿠폰 목록은 세션만 있으면 되고 번호표·순서 불필요 |
| 4 | claim 후 재접근 | 쿠폰 목록: 정상 200(남은 재고 표시). 번호표: **409 `ALREADY_CLAIMED`로 막음**(2026-10-02 구현 — 다시 기다리지 않도록). claim을 다시 부르면 역시 409 `ALREADY_CLAIMED`. 성공·품절 모두 같은 코드라 "이미 참여한 이벤트" 안내 권장 |

---

## 2-5. 프런트 재점검 — `38178bb`·`76c831c` (2026-10-02)

| 확인 | 결과 |
|---|---|
| 에러 처리(I6) | ✅ 번호표·쿠폰 목록·claim 실패를 잡아 `error.code`별 문구로 표시(`errorNotice.ts`). `EVENT_ENDED`·`ALREADY_CLAIMED`(중립 문구) 포함 |
| 재시도 | ✅ 4xx는 재시도하지 않고 바로 에러 화면(`isRetryableError` — 5xx·네트워크·429만 재시도) |
| 이벤트 ID | ✅ 호스트 파싱 제거, `?event=`만 사용(I8 정리 완료) |
| 쿠폰 목록 | `remaining`은 화면에 쓰지 않기로 확정(타입에서 제거) — 백엔드 영향 없음 |
| 프런트 문서 | ✅ 에러 코드 표·재시도·세션·이벤트 정보 API 명세가 백엔드 계약과 일치 |
| 테스트·빌드 | 33개 통과, 빌드 성공 |
| 남은 것 | `getEventInfo`가 아직 더미(백엔드 구현 알림 대기) → **M12 구현 완료로 교체 가능**. 교체 시 02(`WaitingRoom`)의 `getEventInfo`에 실패 처리가 없음(04는 있음) |

---

## 2-6. 테넌트 메인 페이지 입장 연동 (2026-10-02)

부하를 보낼 테넌트사 메인 페이지(별도 개발자)가 입장 토큰을 Tetra로 넘기는 경로. 테넌트에 전달한 가이드 요약과 사내망 테스트 결과.

**전달 규격**
- 히든폼 `method="GET"`, `action="https://{event_slug}.{tenant_id}.{루트도메인}/api/issuance/session"`, 필드 `JWT`, **페이지 이동으로** 제출(fetch·XHR 금지). POST로 보내면 토큰이 검증 단계에서 소모된 뒤 405로 실패.
- 토큰: RS256(테넌트 개인키로 서버에서 서명), 클레임 `user_id`(≤128자, 사용자마다 다름)·`tenant_id`(`poctenant001`)·`event_id`(숫자 `1`)·`exp`(발급 후 60~120초, 5분 초과 거절)·`jti`(매번 새 UUID). `iat`는 검사하지 않음. 클릭마다 새 토큰, 부하 테스트에서도 가상 사용자마다 다른 `user_id`·`jti`.
- 실패 시 브라우저에 에러 JSON(`JWT_*`, `TENANT_MISMATCH`, `INVALID_TENANT_ID`, `USER_ID_TOO_LONG`, `EVENT_NOT_FOUND`).
- 키: PoC는 Tetra가 RSA 2048 키페어를 만들어 개인키를 전달, 공개키는 `event.public_key`에 등록.

**사내망 테스트 결과 (성공)**

| 항목 | 내용 |
|---|---|
| 주소 | `http://192.168.38.214:8080/api/issuance/session` (Tetra 담당 PC) |
| 제출 | `user-7bc6360420b4`, 2026-10-02T07:53:59.356Z (curl, 브라우저와 같은 GET) |
| 응답 | 302 `Location: /?event=1`, `Set-Cookie: TETRA_SID…; Max-Age=7200` |
| Tetra 확인 | Redis 세션 생성(tenant `poctenant001`, event `1`, TTL 2h) |

- 진행 중 발견: Tetra PC 시계가 약 15분 늦음(Windows 시간 동기화 꺼짐) — 테넌트가 Date 헤더로 먼저 발견, NTP 동기화 후 0초 차이로 테스트. **배포 환경 노드도 NTP 동기화 확인 항목에 포함할 것**(허용 오차 5초).
- 남은 확인: 대기방 배포 후 실제 이벤트 주소(https)로 브라우저 전체 흐름. 사내망 http는 `Secure` 쿠키가 저장되지 않아 이번 범위에서 제외(배포는 https라 `Secure` 유지).

---

## 2-8. 백엔드 변경 알림 — 인프라 요청 반영 (2026-10-06)

프런트에 전달할 것 (코드 수정 필요 없음, 동작만 바뀜):

| 변경 | 프런트 영향 |
|---|---|
| **claim을 다시 부르면 처음 결과를 200으로** 돌려준다(성공이면 같은 `coupons`, 품절이면 `SOLD_OUT`). 전에는 409 `ALREADY_CLAIMED` | claim 응답을 못 받았을 때(네트워크 끊김·타임아웃) **다시 claim해서 결과를 확정**할 수 있다. 5xx·네트워크 오류 재시도 규칙 그대로 두면 됨. claim에서 `ALREADY_CLAIMED` 처리 코드는 남겨 둬도 무해 |
| 재입장해서 **번호표**를 요청하면 여전히 409 `ALREADY_CLAIMED` | 변경 없음 (중립 문구 유지) |
| 번호표·쿠폰 목록·claim 성공 응답에 `Cache-Control: no-store` | 영향 없음 |

2-2절의 "이전 결과를 다시 조회하는 API는 없다"는 이 변경으로 claim 재호출이 그 역할을 한다.

---

## 2-7. 프런트 재점검 — `088d08a` (2026-10-02)

| 확인 | 결과 |
|---|---|
| 이벤트 정보 | ✅ `getEventInfo`가 `GET /api/issuance/events/{eventId}/info` 실제 호출(같은 호스트 상대 경로, 기본 fetch — CDN 캐시 유지). 더미는 개발용 `?startsIn=`일 때만 |
| 응답 검증 | ✅ `parseEventInfo` — 필드·타입이 백엔드 응답과 일치(시각 `+09:00` ISO, `bannerUrl`·`returnUrl` 빈 문자열 허용), `endAt` 추가 |
| 02 실패 처리 | ✅ `WaitingRoom`에서 정보 요청 실패 시 `ErrorNotice` |
| 테스트·빌드 | 37개 통과, 빌드 성공 |
| 실제 연결 | ✅ 빌드본(`vite preview`)을 앱 컨테이너(M10)에 붙여 02→03→04 claim 성공까지 브라우저 확인 |
| 남은 것 | 프런트 쪽 없음. 인프라에 CloudFront behavior `/api/issuance/events/*/info`(s-maxage 60) 전달(M11) |

---

## 3. 확정 API 계약 (백엔드 구현 기준)

모든 응답은 `{"success":true,"data":…}` / `{"success":false,"error":{"code","message"}}`. 에러 응답은 `Cache-Control: no-store`. 요청 본문은 모두 없음.

| API | 성공 (`data`) | 주요 에러 | 인증 |
|---|---|---|---|
| `GET /api/issuance/session?JWT=…` | `302`, `Location: /?event={eventId}`(설정값, 상대 경로 — 예 `/?event=1`), `Set-Cookie: TETRA_SID=…; Path=/; Max-Age=7200; Secure; HttpOnly; SameSite=Lax` (Domain 없음), `Referrer-Policy: no-referrer`, `no-store` | 400 `JWT_MISSING`·`JWT_MALFORMED`·`INVALID_TENANT_ID`·`USER_ID_TOO_LONG`, 401 `JWT_INVALID_SIGNATURE`·`JWT_EXPIRED`·`JWT_REUSED`, 403 `TENANT_MISMATCH`, 404 `EVENT_NOT_FOUND` | JWT |
| `POST /api/issuance/events/{eventId}/ticket` | `{"ticketNumber":1234}` — 호출마다 새 번호 | 401 `SESSION_NOT_FOUND`, 403 `SESSION_EVENT_MISMATCH`, 409 `EVENT_NOT_STARTED`·`EVENT_ENDED`·`ALREADY_CLAIMED` | 쿠키 |
| `GET /api/issuance/events/{eventId}/info` | `{"eventId":1,"name":"…","startAt":"2026-09-29T10:00:00+09:00","endAt":"…+09:00","bannerUrl":"","returnUrl":""}` — `Cache-Control: public, max-age=0, s-maxage=60` (2026-10-02 구현) | 404 `EVENT_NOT_FOUND`, 400 | 없음 |
| `GET /api/issuance/events/{eventId}/queue/cursor` | `{"cursor":300}` — `Cache-Control: public, max-age=0, s-maxage=1` | 404 `EVENT_NOT_FOUND`, 400 `INVALID_REQUEST` | 없음 |
| `GET /api/issuance/events/{eventId}/coupons` | `{"coupons":[{"couponId":1,"name":"PoC 쿠폰 1","description":"PoC 쿠폰입니다.","remaining":10}, …]}` (couponId 순) | 401, 403 | 쿠키 |
| `POST /api/issuance/events/{eventId}/coupons/claim` | 성공 `{"result":"SUCCESS","coupons":[{"couponId","name","description"}, …]}` / 품절 `{"result":"SOLD_OUT","coupons":[]}` — **둘 다 200** | 401, 403, 409 `EVENT_ENDED`·`TICKET_REQUIRED`. 다시 부르면 **처음 결과를 그대로 200**(2026-10-06, `ALREADY_CLAIMED` 안 옴) | 쿠키 |

---

## 4. 해야 할 일

### 프런트 (모두 `src/api/`·`vite.config.ts` 중심, 화면 코드는 I6·I11만)

- [ ] I1 공통 응답 형식을 벗기는 `request` 도우미, 실패 시 에러 코드를 담은 예외
- [ ] I2 `requestTicket` → `{ ticketNumber }` (03 화면의 `res.ticket` → `res.ticketNumber`)
- [ ] I3 `getQueueCursor` → `parseCursorResponse(body.data)` (캐시 규칙 유지), 테스트 케이스 추가
- [ ] I4 `getCoupons` → `data.coupons`
- [ ] I5 `claimCoupons` → `data.result`, 값 `'SUCCESS' | 'SOLD_OUT'`
- [ ] I6 번호표·쿠폰 목록·claim 실패 시 멈추지 않게(최소 안내 문구), 에러 코드별 처리는 6절 결정 후
- [ ] I7 `vite.config.ts`에 `loadEnv` 적용 (또는 README 안내를 셸 환경변수로 수정)
- [ ] 프런트 `CLAUDE.md` §5 API 표를 3절 계약으로 갱신 (현재 "임시·미정")
- [ ] I8 문서 정리 — `CLAUDE.md` §7·`eventId.ts` 주석: 첫 라벨은 해시값, event id는 `?event=`(운영 경로). 폴백 기본값 1 대신 잘못된 접근 처리 권장

### 백엔드

- [x] Q1 — 세션 302 목적지 `/?event={eventId}` (2026-10-02)
- [ ] 그 외 필수 수정 없음. 6절 결정에 따라 추가 작업 가능(Q2 이벤트 정보 API, Q3 세션 실패 리다이렉트)
- [x] 폴링 스펙 문서의 응답 형식 `data.cursor` 수정 (2026-10-01 완료)

### 함께

- [ ] 6절 결정 남은 2건 (Q2, Q3)
- [ ] 수정 후 4절 "로컬 연동 방법"으로 02 → 03 → 04 → 모달 흐름을 브라우저로 확인

---

## 5. 맞는 부분 (확인 완료)

| 항목 | 프런트 | 백엔드 | 확인 |
|---|---|---|---|
| 경로 | 같은 호스트 상대 경로 `/api/issuance/events/{eventId}/…` | 같음 | 코드 |
| 세션 진입 | 프런트는 세션 API를 호출하지 않음, 서버 302가 02로 보냄. `?event=`를 최우선으로 읽음 | `Location: /?event={eventId}` (상대 경로, 2026-10-02부터) | **실측(변경 전 `/`): Vite 프록시 경유로도 `location` 유지, 쿠키 정상** |
| 쿠키 | JS가 읽지 않음, 스토리지 미사용, `credentials: 'same-origin'` | HttpOnly·Domain 없음 | 실측: 로컬(localhost:5173)에서도 쿠키가 프런트 출처에 저장되어 다음 요청에 실림 |
| `user_id` | 요청 본문에 넣지 않음 | 세션에서 꺼냄 | 코드 |
| 커서 캐시 | 캐시 무효화 쿼리·`cache`·헤더·`credentials` 변경 없음 | `public, max-age=0, s-maxage=1`, 인증 없음 | 코드·실측 |
| 커서 역행 | `mergeCursor` 최댓값 유지 | 엣지 캐시로 역행 가능 | 폴링 스펙과 일치 |
| index.html 방어 | `parseCursorResponse` | CloudFront custom error response 미사용 + 에러 JSON | I7 실측에서 실제로 필요함을 확인 |
| 폴링 간격 | gap 기준 1/2/5/10초, `>` 경계 | 3초당 300 증가 | 스펙과 일치 |
| 새로고침 | 03 새로고침 시 새 번호 (저장 안 함) | 재발급 허용, `ticket:retry`로 측정 | 일치 |
| 시간대 | KST(+09:00) 해석 | KST 저장·Clock Asia/Seoul | 일치 |
| 라우팅 | `BrowserRouter`, `/api/*`를 프런트 경로로 안 씀 | CloudFront Function으로 SPA 복구 | 일치 |

---

## 6. 결정 필요

| # | 질문 | 선택지 | 제안 |
|---|---|---|---|
| Q1 | ~~호스트 첫 라벨이 해시값이면 프런트는 숫자 event_id를 어디서 받나 (I8)~~ | — | ✅ **A 적용(2026-10-02)**: 세션 302 → `/?event={eventId}` |
| Q2 | 카운트다운 시작 시각 출처 (I9) | (a) PoC는 더미 유지 + 409 처리 (b) 이벤트 정보 API 추가 | PoC는 (a) |
| Q3 | 세션 발급 실패 시 화면 (I10) | (a) JSON 그대로 (b) 백엔드가 `/?error=코드`로 302 | PoC는 (a), 운영 전 (b) 검토 |

---

## 7. 로컬 연동 방법

```bash
# 백엔드 (PoC 폴더)
bash scripts/gen-test-keys.sh            # 최초 1회
docker compose up -d --wait
(cd issuance-service && ./gradlew bootRun)
bash scripts/reset-local.sh --yes        # 깨끗한 상태로

# 프런트 (tetra-poc-front 폴더) — I7 수정 전에는 셸 환경변수로
VITE_API_PROXY_TARGET=http://localhost:8080 npx vite --port 5173

# 입장: 프런트 출처(5173)로 세션 URL을 만든다 → 브라우저에 붙여넣기
bash scripts/issue-test-jwt.sh user-0001 1 poctenant001 90 http://localhost:5173
# → 302로 http://localhost:5173/?event=1 (02 대기방) 에 쿠키와 함께 도착
```

- 세션 URL은 1회용(90초)이다. 다시 들어가려면 새로 발급한다.
- 세션 302가 `/?event=1`로 보내므로 로컬에서도 이벤트 ID는 `?event=`에서 읽힌다.
- 백엔드 시드 이벤트는 이미 시작된 상태라 02에서 바로 입장 버튼이 나온다(카운트다운을 보려면 `?startsIn=<초>` 더미).
- 백엔드를 컨테이너로 띄운 경우(M10): `docker compose --profile app up -d --wait` 후, 프런트는 빌드본으로 `npm run build && VITE_API_PROXY_TARGET=http://localhost:8080 npx vite preview --port 4173`, 세션 URL의 출처도 `http://localhost:4173`. bootRun과는 8080을 같이 쓸 수 없다.

---

## 8. 확인 근거

| 확인 | 결과 |
|---|---|
| 프런트 단위 테스트 `npm test` | 21개 통과 |
| `.env.local`만으로 프록시 | `/api/…/queue/cursor` → `200 text/html` (`<title>Tetra Issuance</title>`) |
| 셸 환경변수로 프록시 + CLI 토큰으로 세션 | `302`, `location: /`, `set-cookie: TETRA_SID=…; Path=/; Max-Age=7200; Secure; HttpOnly; SameSite=Lax`, `referrer-policy: no-referrer`, `cache-control: no-store` |
| 번호표 / 커서 / 쿠폰 목록 / claim / 재claim | 2절 I1~I5 표의 실제 응답. 프런트 해석: `res.ticket`=undefined, `parseCursorResponse` 실패, 배열 아님, `res.result`=undefined → 품절 문구, 재claim 409 `ALREADY_CLAIMED` |
| 호스트 파싱 (`eventId.ts` 로직) | `1.poctenant001.gamza-dev.shop`→1, `k7f2q9.poctenant001.gamza-dev.shop`→null(기본값 1) |
| 프런트 저장소 변경 | 없음 (`git status` 비어 있음, 의존성 `node_modules`만 설치 — git 제외 대상) |

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-06 | 2-8절 추가 — claim 재호출 시 처음 결과 200(인프라 B3), 성공 응답 no-store. 3절 계약표 갱신 |
| 2026-10-02 | 2-7절 추가 — 프런트 `088d08a` 재점검: `/info` 실제 호출·응답 검증·02 실패 처리 확인, 컨테이너 대상 브라우저 확인. 프런트 남은 일 없음. 7절에 컨테이너 방식 추가 |
| 2026-10-02 | 2-6절 추가 — 테넌트 메인 페이지 입장 연동 규격(GET 히든폼)과 사내망 테스트 성공 결과, 서버 시계 이슈 |
| 2026-10-01 | 최초 작성 — 프런트 `4238ddc` 기준 통합 점검 (차단 5, 기능 2, 확인 4), 확정 계약·해야 할 일·결정 3건 |
| 2026-10-01 | I8·Q1 보강 — event-slug 의미 정리(도메인용 무작위 값으로 추정), 선택지 A~C, A 제안 |
| 2026-10-02 | 2-5절 추가 — 프런트 38178bb·76c831c 재점검: I6 해결, 4xx 재시도 중단, ?event=만 사용. 남은 것: getEventInfo 실제 호출 교체, 02 실패 처리 |
| 2026-10-02 | 이벤트 정보 API 확정 — 경로 `/{eventId}/info`(CloudFront 경로 패턴 때문에 `/{eventId}` 대신), 3절 계약표 추가 |
| 2026-10-02 | 2-4절 추가 — 프런트 3차 질문 답(이벤트 정보 API 검토 중, 지터 프런트, EVENT_ENDED 구현, 순서 검증 없음, claim 후 번호표 409). 3절 계약표에 EVENT_ENDED·번호표 ALREADY_CLAIMED 반영 |
| 2026-10-02 | 2-3절 추가 — 프런트 `86bafa0` 재점검: I1~I5 해결, 브라우저로 02→03→04 실제 연결 확인, I6 잔존, I12(개발 모드 번호표 2회 요청) 신규 |
| 2026-10-02 | 2-2절 추가 — 프런트 2차 질문(쿠폰 필드·remaining 실시간·claim coupons 의미, API별 에러 코드, 429·종료·만료, 재시도, ALREADY_CLAIMED, 번호·커서·세션 TTL, Date 헤더) 답. 백엔드 할 일 B1(이벤트 종료)·B2(세션 TTL) |
| 2026-10-02 | 2-1절 추가 — 프런트 봉투 처리(unwrapApiEnvelope) 확인 요청에 답: 5개 API 모두 처음부터 봉투 형식, 봉투 이후에도 남는 불일치 3건(ticketNumber·data.coupons·SOLD_OUT), 통과 안전장치 제거 권장 |
| 2026-10-02 | Q1 해결 — 프런트 담당 확인(첫 라벨 = UX용 해시, `event.subdomain` 저장, API는 숫자 event_id). 백엔드 세션 302를 `/?event={eventId}`로 변경, 테스트 112개 통과. 3·5·7절 갱신 |

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
| I1 | 🔴 차단 | 공통 응답 형식(`data` 래퍼) 미처리 | 아래 I2~I4의 직접 원인 |
| I2 | 🔴 차단 | 번호표: 프런트 `res.ticket` ↔ 백엔드 `data.ticketNumber` | 번호가 `undefined` → 폴링이 시작되지 않아 **03 화면에서 영원히 대기** |
| I3 | 🔴 차단 | 커서: 프런트 `{cursor}` ↔ 백엔드 `{success, data:{cursor}}` | `parseCursorResponse`가 매번 실패 → 5회 후 **에러 화면** |
| I4 | 🔴 차단 | 쿠폰 목록: 프런트 배열 ↔ 백엔드 `data.coupons` | `coupons.map`에서 **04 화면 크래시** |
| I5 | 🔴 차단 | claim 결과: 프런트 `res.result`·`FAILED_SOLDOUT` ↔ 백엔드 `data.result`·`SOLD_OUT` | `result`가 `undefined`라 **발급에 성공한 사용자에게 "모두 소진" 모달**이 뜸 |
| I6 | 🟠 기능 | 에러 응답 처리 없음 (번호표·쿠폰 목록·claim) | 시작 전(409)·세션 없음(401)·중복 claim(409) 등에서 화면이 멈추거나 아무 반응 없음 |
| I7 | 🟠 기능 | 로컬 프록시: `.env.local`의 `VITE_API_PROXY_TARGET`이 적용되지 않음 | `/api/*` 요청에 `index.html`이 200으로 돌아옴 (README 안내대로 하면 연결 안 됨) |
| I8 | ✅ 해결 | 이벤트 ID를 호스트에서 얻는 규칙 | 호스트 첫 라벨은 해시값이라 숫자 event_id가 없음 → **세션 302가 `/?event={eventId}`로 넘기도록 백엔드 반영(2026-10-02)**. 프런트는 코드 변경 없이 동작, 문서·폴백만 정리 권장 |
| I9 | 🟡 확인 | 이벤트 시작 시각: 프런트 더미(지금+15초) ↔ 백엔드 DB `start_at` | 카운트다운과 서버의 "시작 전 거절"이 서로 다른 시각을 봄 |
| I10 | 🟡 확인 | 세션 발급 실패 시 화면 | JWT 오류가 브라우저에 JSON 그대로 보임 |
| I11 | 🟡 확인 | 04 화면 "받을 수 있는 쿠폰 N장" | 재고 0인 종류도 세어 표시 |

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

## 3. 확정 API 계약 (백엔드 구현 기준)

모든 응답은 `{"success":true,"data":…}` / `{"success":false,"error":{"code","message"}}`. 에러 응답은 `Cache-Control: no-store`. 요청 본문은 모두 없음.

| API | 성공 (`data`) | 주요 에러 | 인증 |
|---|---|---|---|
| `GET /api/issuance/session?JWT=…` | `302`, `Location: /?event={eventId}`(설정값, 상대 경로 — 예 `/?event=1`), `Set-Cookie: TETRA_SID=…; Path=/; Max-Age=7200; Secure; HttpOnly; SameSite=Lax` (Domain 없음), `Referrer-Policy: no-referrer`, `no-store` | 400 `JWT_MISSING`·`JWT_MALFORMED`·`INVALID_TENANT_ID`·`USER_ID_TOO_LONG`, 401 `JWT_INVALID_SIGNATURE`·`JWT_EXPIRED`·`JWT_REUSED`, 403 `TENANT_MISMATCH`, 404 `EVENT_NOT_FOUND` | JWT |
| `POST /api/issuance/events/{eventId}/ticket` | `{"ticketNumber":1234}` — 호출마다 새 번호 | 401 `SESSION_NOT_FOUND`, 403 `SESSION_EVENT_MISMATCH`, 409 `EVENT_NOT_STARTED` | 쿠키 |
| `GET /api/issuance/events/{eventId}/queue/cursor` | `{"cursor":300}` — `Cache-Control: public, max-age=0, s-maxage=1` | 404 `EVENT_NOT_FOUND`, 400 `INVALID_REQUEST` | 없음 |
| `GET /api/issuance/events/{eventId}/coupons` | `{"coupons":[{"couponId":1,"name":"PoC 쿠폰 1","description":"PoC 쿠폰입니다.","remaining":10}, …]}` (couponId 순) | 401, 403 | 쿠키 |
| `POST /api/issuance/events/{eventId}/coupons/claim` | 성공 `{"result":"SUCCESS","coupons":[{"couponId","name","description"}, …]}` / 품절 `{"result":"SOLD_OUT","coupons":[]}` — **둘 다 200** | 401, 403, 409 `TICKET_REQUIRED`·`ALREADY_CLAIMED` | 쿠키 |

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
- 백엔드 시드 이벤트는 이미 시작된 상태라 02의 더미 카운트다운(15초)이 끝나면 바로 진행된다.

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
| 2026-10-01 | 최초 작성 — 프런트 `4238ddc` 기준 통합 점검 (차단 5, 기능 2, 확인 4), 확정 계약·해야 할 일·결정 3건 |
| 2026-10-01 | I8·Q1 보강 — event-slug 의미 정리(도메인용 무작위 값으로 추정), 선택지 A~C, A 제안 |
| 2026-10-02 | 2-1절 추가 — 프런트 봉투 처리(unwrapApiEnvelope) 확인 요청에 답: 5개 API 모두 처음부터 봉투 형식, 봉투 이후에도 남는 불일치 3건(ticketNumber·data.coupons·SOLD_OUT), 통과 안전장치 제거 권장 |
| 2026-10-02 | Q1 해결 — 프런트 담당 확인(첫 라벨 = UX용 해시, `event.subdomain` 저장, API는 숫자 event_id). 백엔드 세션 302를 `/?event={eventId}`로 변경, 테스트 112개 통과. 3·5·7절 갱신 |

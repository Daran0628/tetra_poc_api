package io.tetra.issuance.controller;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.tetra.issuance.common.ApiResponse;
import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.filter.SessionAuthFilter;
import io.tetra.issuance.filter.VerifiedEntryToken;
import io.tetra.issuance.service.ClaimService;
import io.tetra.issuance.service.ClaimService.ClaimResult;
import io.tetra.issuance.service.CouponService;
import io.tetra.issuance.service.CouponService.CouponView;
import io.tetra.issuance.service.EventInfoService;
import io.tetra.issuance.service.EventInfoService.EventInfo;
import io.tetra.issuance.service.QueueCursorService;
import io.tetra.issuance.service.SessionService;
import io.tetra.issuance.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Issuance API 6개 (플랜 1절). 얇게 유지 — 요청 값 꺼내기·응답 만들기만 하고 로직은 Service 에 둔다 (ADR-0002 결정 3).
 */
@RestController
@RequestMapping("/api/issuance")
public class IssuanceController {

	private final SessionService sessionService;
	private final TicketService ticketService;
	private final QueueCursorService queueCursorService;
	private final CouponService couponService;
	private final ClaimService claimService;
	private final EventInfoService eventInfoService;
	private final TetraProperties properties;

	public IssuanceController(SessionService sessionService, TicketService ticketService,
			QueueCursorService queueCursorService, CouponService couponService, ClaimService claimService,
			EventInfoService eventInfoService, TetraProperties properties) {
		this.sessionService = sessionService;
		this.ticketService = ticketService;
		this.queueCursorService = queueCursorService;
		this.couponService = couponService;
		this.claimService = claimService;
		this.eventInfoService = eventInfoService;
		this.properties = properties;
	}

	/**
	 * 4-1 세션 발급: 테넌트 302 리다이렉트로 들어온 입장 토큰(JwtAuthFilter 가 검증) → 세션 쿠키 + 302 대기방.
	 * <ul>
	 * <li>Location 은 설정값 그대로의 상대 경로 — 요청 Host 는 오리진 주소라 절대 주소를 만들면 안 됨</li>
	 * <li>쿠키에 Domain 없음(host-only), Secure 는 요청 scheme 이 아닌 설정값</li>
	 * <li>no-store: Set-Cookie 응답이 어디에도 캐시되지 않게. Referrer-Policy 는 JwtAuthFilter 가 붙임</li>
	 * </ul>
	 */
	@GetMapping("/session")
	ResponseEntity<Void> session(HttpServletRequest request) {
		VerifiedEntryToken token = VerifiedEntryToken.from(request);
		String sid = sessionService.enter(token);

		TetraProperties.Session config = properties.session();
		ResponseCookie cookie = ResponseCookie.from(config.cookieName(), sid)
				.httpOnly(true)
				.secure(config.cookieSecure())
				.sameSite("Lax")
				.path("/")
				.maxAge(config.ttl())
				.build();

		return ResponseEntity.status(HttpStatus.FOUND)
				.header(HttpHeaders.LOCATION, config.redirectUrl().replace("{eventId}", Long.toString(token.eventId())))
				.header(HttpHeaders.SET_COOKIE, cookie.toString())
				.cacheControl(CacheControl.noStore())
				.build();
	}

	/**
	 * 4-0 이벤트 정보 (02 대기방 카운트다운용). 인증 없음(SessionAuthFilter 가 이 경로는 통과시킴).
	 * Cache-Control: public, max-age=0, s-maxage=60 — 5만 명이 02 를 열 때마다 부르므로 CloudFront 캐시 대상.
	 */
	@GetMapping("/events/{eventId}/info")
	ResponseEntity<ApiResponse<EventInfo>> eventInfo(@PathVariable long eventId) {
		EventInfo info = eventInfoService.find(eventId);
		return ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL,
						"public, max-age=0, s-maxage=" + properties.event().infoCacheSMaxage().toSeconds())
				.body(ApiResponse.ok(info));
	}

	/** 4-2 번호표 발급: 대기실(03 화면) 진입 시 호출. 재호출·새로고침 때마다 새 번호. */
	@PostMapping("/events/{eventId}/ticket")
	ApiResponse<TicketResponse> ticket(@PathVariable long eventId, HttpServletRequest request) {
		return ApiResponse.ok(new TicketResponse(ticketService.issue(SessionAuthFilter.sessionOf(request))));
	}

	record TicketResponse(long ticketNumber) {
	}

	/**
	 * 4-3 서빙 커서 폴링. 인증 없음(SessionAuthFilter 가 이 경로는 통과시킴), 응답은 이벤트 공통 값.
	 * Cache-Control: public, max-age=0, s-maxage=1 — CloudFront 같은 공유 캐시는 1초 캐시,
	 * 브라우저는 캐시하지 않음(적응형 폴링 간격이 브라우저 캐시에 막히지 않게). 에러는 GlobalExceptionHandler 가 no-store.
	 */
	@GetMapping("/events/{eventId}/queue/cursor")
	ResponseEntity<ApiResponse<CursorResponse>> cursor(@PathVariable long eventId) {
		long cursor = queueCursorService.current(eventId);
		return ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL,
						"public, max-age=0, s-maxage=" + properties.queue().cursorCacheSMaxage().toSeconds())
				.body(ApiResponse.ok(new CursorResponse(cursor)));
	}

	record CursorResponse(long cursor) {
	}

	/** 4-4 쿠폰 목록 + 잔여 수량(Redis 기준). */
	@GetMapping("/events/{eventId}/coupons")
	ApiResponse<CouponsResponse> coupons(@PathVariable long eventId) {
		return ApiResponse.ok(new CouponsResponse(couponService.list(eventId)));
	}

	record CouponsResponse(List<CouponView> coupons) {
	}

	/** 4-5 쿠폰 일괄 발급. 성공·품절 모두 200 (result 로 구분). 중복·번호표 없음은 409. */
	@PostMapping("/events/{eventId}/coupons/claim")
	ApiResponse<ClaimResult> claim(@PathVariable long eventId, HttpServletRequest request) {
		return ApiResponse.ok(claimService.claim(SessionAuthFilter.sessionOf(request)));
	}

}

package io.tetra.issuance.filter;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import io.tetra.issuance.common.BusinessException;
import io.tetra.issuance.common.ErrorCode;
import io.tetra.issuance.common.ErrorResponseWriter;
import io.tetra.issuance.redis.IssuanceSession;
import io.tetra.issuance.redis.SessionKeys;
import io.tetra.issuance.redis.SessionStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 이벤트 API(번호표·쿠폰 목록·claim) 세션 쿠키 인증 (ADR-0002 결정 2).
 * <ol>
 * <li>세션 쿠키(tetra.session.cookie-name) → Redis session:{sid} 조회. 없거나 만료면 401</li>
 * <li>경로의 eventId 와 세션의 event_id 가 다르면 403 (다른 이벤트 세션으로 접근 차단)</li>
 * <li>세션을 요청에 싣고 넘긴다 → {@link #sessionOf(HttpServletRequest)}</li>
 * </ol>
 * 커서 API(/queue/cursor)·이벤트 정보 API(/info)는 인증하지 않는다 (CDN 캐시 대상, 플랜 4-3·4-0) — 세션 조회 없이 통과.
 * 등록은 FilterConfig 에서 /api/issuance/events/* 에만 한다.
 */
public class SessionAuthFilter extends OncePerRequestFilter {

	public static final String URL_PATTERN = "/api/issuance/events/*";

	private static final Logger log = LoggerFactory.getLogger(SessionAuthFilter.class);
	private static final String ATTRIBUTE = IssuanceSession.class.getName();
	private static final Pattern EVENT_PATH = Pattern.compile("/api/issuance/events/([^/]+)(/.*)?");
	/** 인증 없는 공개 경로: 커서(4-3), 이벤트 정보(4-0) */
	private static final Pattern PUBLIC_PATH = Pattern.compile("/api/issuance/events/\\d+/(queue/cursor|info)");

	private final SessionStore sessionStore;
	private final String cookieName;
	private final ErrorResponseWriter errorWriter;

	public SessionAuthFilter(SessionStore sessionStore, String cookieName, ErrorResponseWriter errorWriter) {
		this.sessionStore = sessionStore;
		this.cookieName = cookieName;
		this.errorWriter = errorWriter;
	}

	/** 컨트롤러에서 현재 세션을 꺼낸다. 이 필터 적용 경로에서는 항상 존재. */
	public static IssuanceSession sessionOf(HttpServletRequest request) {
		return (IssuanceSession) request.getAttribute(ATTRIBUTE);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// 디코딩되고 ;파라미터가 제거된 경로 (예: "/cursor;x=1" → "/cursor")
		String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
		try {
			if (PUBLIC_PATH.matcher(path).matches() && !hasDotSegment(path)) {
				chain.doFilter(request, response); // 인증 없는 API
				return;
			}
			IssuanceSession session = authenticate(request, path);
			request.setAttribute(ATTRIBUTE, session);
		}
		catch (BusinessException e) {
			log.debug("Session rejected: {} {}", e.errorCode(), path);
			errorWriter.write(response, e.errorCode());
			return;
		}
		chain.doFilter(request, response);
	}

	private IssuanceSession authenticate(HttpServletRequest request, String path) {
		long pathEventId = pathEventId(path);
		String sid = sessionCookie(request)
				.filter(SessionKeys::isValidSessionId) // 형식이 틀리면 Redis 조회 안 함
				.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		IssuanceSession session = sessionStore.find(sid)
				.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		if (session.eventId() != pathEventId) {
			throw new BusinessException(ErrorCode.SESSION_EVENT_MISMATCH);
		}
		return session;
	}

	private static long pathEventId(String path) {
		Matcher m = EVENT_PATH.matcher(path);
		if (!m.matches() || hasDotSegment(path)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		try {
			return Long.parseLong(m.group(1));
		}
		catch (NumberFormatException e) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}

	/** "/../", "/./" 같은 경로 조작은 경로 판단을 흐리므로 거절한다. */
	private static boolean hasDotSegment(String path) {
		return path.contains("/../") || path.contains("/./") || path.endsWith("/..") || path.endsWith("/.");
	}

	private Optional<String> sessionCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		for (Cookie cookie : cookies) {
			if (cookieName.equals(cookie.getName())) {
				return Optional.ofNullable(cookie.getValue());
			}
		}
		return Optional.empty();
	}

}

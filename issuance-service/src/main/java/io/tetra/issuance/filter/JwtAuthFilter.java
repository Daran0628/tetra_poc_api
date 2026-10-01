package io.tetra.issuance.filter;

import java.io.IOException;
import java.security.Key;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import io.tetra.issuance.common.BusinessException;
import io.tetra.issuance.common.ErrorCode;
import io.tetra.issuance.common.ErrorResponseWriter;
import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.service.EventMeta;
import io.tetra.issuance.service.EventMetaCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 세션 API(GET /api/issuance/session?JWT=...) 전용 입장 토큰 검증 (플랜 4-1 "JWT 구성 방식").
 *
 * <ol>
 * <li>서명: RS256 만 허용. 토큰의 event_id 로 그 이벤트의 공개키를 골라 검증 (nimbus JWTClaimsSetAwareJWSKeySelector).
 * none·HS256 등 다른 알고리즘은 키를 주지 않아 실패한다 (알고리즘 혼동 공격 방지).</li>
 * <li>클레임: 서명이 확인된 뒤에만 검사 — exp(시계 오차 허용), exp 상한, jti, user_id 길이, tenant_id 형식·이벤트 소유 테넌트 일치.</li>
 * </ol>
 * 성공하면 {@link VerifiedEntryToken} 을 요청에 싣고 넘긴다. jti 1회 사용 확인(Redis)은 세션 서비스(M3)가 한다.
 * 이 경로의 모든 응답(에러 포함)에 Referrer-Policy: no-referrer — 주소에 JWT 가 들어 있어 Referer 로 새지 않게.
 * 등록은 FilterConfig 에서 이 경로에만 한다 (@Component 금지: 붙이면 모든 경로에 자동 등록됨).
 */
public class JwtAuthFilter extends OncePerRequestFilter {

	public static final String PATH = "/api/issuance/session";
	static final String TOKEN_PARAM = "JWT";

	private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
	/** DB CHECK chk_tenant_id_format 과 같은 규칙 */
	private static final Pattern TENANT_ID_FORMAT = Pattern.compile("[a-z0-9]{12}");

	private final EventMetaCache events;
	private final TetraProperties.Jwt config;
	private final Clock clock;
	private final ErrorResponseWriter errorWriter;
	private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

	public JwtAuthFilter(EventMetaCache events, TetraProperties.Jwt config, Clock clock, ErrorResponseWriter errorWriter) {
		this.events = events;
		this.config = config;
		this.clock = clock;
		this.errorWriter = errorWriter;
		processor.setJWTClaimsSetAwareJWSKeySelector(this::selectVerificationKey);
		// 클레임 검사는 아래 verifyClaims 에서 직접 한다 (에러 코드를 구분하고, 시각을 Clock 으로 통일하기 위해)
		processor.setJWTClaimsSetVerifier((claims, context) -> {
		});
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader("Referrer-Policy", "no-referrer");
		VerifiedEntryToken token;
		try {
			token = verify(request.getParameter(TOKEN_PARAM));
		}
		catch (BusinessException e) {
			// 토큰 값은 로그에 남기지 않는다 (쿼리스트링 마스킹 원칙)
			log.info("Entry token rejected: {}", e.errorCode());
			errorWriter.write(response, e.errorCode());
			return;
		}
		request.setAttribute(VerifiedEntryToken.ATTRIBUTE, token);
		chain.doFilter(request, response);
	}

	VerifiedEntryToken verify(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			throw new BusinessException(ErrorCode.JWT_MISSING);
		}
		JWTClaimsSet claims;
		try {
			claims = processor.process(rawToken, null);
		}
		catch (ParseException e) {
			throw new BusinessException(ErrorCode.JWT_MALFORMED);
		}
		catch (BadJOSEException | JOSEException e) {
			// 서명 불일치, 다른 키, alg none/HS256 등 허용하지 않는 알고리즘
			throw new BusinessException(ErrorCode.JWT_INVALID_SIGNATURE);
		}
		return verifyClaims(claims);
	}

	/**
	 * 서명 검증 전에 호출된다. 여기서 읽는 event_id 는 아직 믿을 수 없는 값이라 "어느 키로 검증할지" 고르는 데만 쓴다.
	 * 빈 목록을 돌려주면 nimbus 가 검증 실패로 처리한다.
	 */
	private List<? extends Key> selectVerificationKey(JWSHeader header, JWTClaimsSet unverifiedClaims,
			SecurityContext context) {
		if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())) {
			return List.of();
		}
		EventMeta event = findEvent(unverifiedClaims);
		if (!event.hasPublicKey()) {
			log.warn("Entry token for event {} rejected: no public key registered", event.eventId());
			throw new BusinessException(ErrorCode.EVENT_NOT_FOUND);
		}
		return List.of(event.publicKey());
	}

	private VerifiedEntryToken verifyClaims(JWTClaimsSet claims) {
		Instant now = clock.instant();

		Date exp = claims.getExpirationTime();
		if (exp == null) {
			throw new BusinessException(ErrorCode.JWT_MALFORMED);
		}
		Instant expiresAt = exp.toInstant();
		if (!expiresAt.plus(config.clockSkew()).isAfter(now)) {
			throw new BusinessException(ErrorCode.JWT_EXPIRED);
		}
		if (expiresAt.isAfter(now.plus(config.maxTtl()))) {
			log.info("Entry token rejected: exp too far in the future ({})", expiresAt);
			throw new BusinessException(ErrorCode.JWT_MALFORMED);
		}

		String jti = claims.getJWTID();
		if (jti == null || jti.isBlank()) {
			throw new BusinessException(ErrorCode.JWT_MALFORMED);
		}

		String userId = stringClaim(claims, "user_id");
		if (userId.length() > config.userIdMaxLength()) {
			throw new BusinessException(ErrorCode.USER_ID_TOO_LONG);
		}

		EventMeta event = findEvent(claims);
		String tenantId = stringClaim(claims, "tenant_id");
		if (!TENANT_ID_FORMAT.matcher(tenantId).matches()) {
			throw new BusinessException(ErrorCode.INVALID_TENANT_ID);
		}
		if (!tenantId.equals(event.tenantId())) {
			throw new BusinessException(ErrorCode.TENANT_MISMATCH);
		}

		return new VerifiedEntryToken(userId, tenantId, event.eventId(), jti, expiresAt);
	}

	private EventMeta findEvent(JWTClaimsSet claims) {
		Long eventId;
		try {
			eventId = claims.getLongClaim("event_id");
		}
		catch (ParseException e) {
			throw new BusinessException(ErrorCode.JWT_MALFORMED);
		}
		if (eventId == null) {
			throw new BusinessException(ErrorCode.JWT_MALFORMED);
		}
		return events.find(eventId).orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
	}

	private static String stringClaim(JWTClaimsSet claims, String name) {
		try {
			String value = claims.getStringClaim(name);
			if (value == null || value.isBlank()) {
				throw new BusinessException(ErrorCode.JWT_MALFORMED);
			}
			return value;
		}
		catch (ParseException e) {
			throw new BusinessException(ErrorCode.JWT_MALFORMED);
		}
	}

}

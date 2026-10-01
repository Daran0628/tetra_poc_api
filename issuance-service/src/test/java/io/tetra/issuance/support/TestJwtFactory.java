package io.tetra.issuance.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * 테스트용 입장 토큰(JWT) 발급기. 테넌트 역할을 흉내 낸다.
 * <ul>
 * <li>RSA 키페어를 즉석에서 만든다 — 로컬 키 파일(03_local_test_key.sql)에 의존하지 않아 어느 PC에서든 같은 결과</li>
 * <li>{@link #publicKeyPem()} 값을 테스트 DB의 event.public_key 에 넣으면 앱이 이 토큰을 검증할 수 있다</li>
 * <li>정상 토큰 외에 만료·다른 키 서명·alg none·HS256(알고리즘 혼동 공격) 토큰도 만든다</li>
 * </ul>
 * 클레임 구성은 플랜 4-1 "JWT 구성 방식": user_id, tenant_id, event_id, exp, jti.
 */
public final class TestJwtFactory {

	public static final String DEFAULT_USER_ID = "user-0001";
	public static final String DEFAULT_TENANT_ID = "poctenant001";
	public static final long DEFAULT_EVENT_ID = 1L;
	public static final Duration DEFAULT_TTL = Duration.ofSeconds(90);

	private final RSAKey rsaKey;

	private TestJwtFactory(RSAKey rsaKey) {
		this.rsaKey = rsaKey;
	}

	/** 새 RSA 2048 키페어로 발급기를 만든다. */
	public static TestJwtFactory withNewKeyPair() {
		try {
			return new TestJwtFactory(new RSAKeyGenerator(2048).generate());
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

	/** event.public_key 에 저장하는 형식(PEM, SPKI). scripts/gen-test-keys.sh 가 만드는 것과 같은 형식. */
	public String publicKeyPem() {
		try {
			byte[] der = rsaKey.toRSAPublicKey().getEncoded();
			String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
			return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 기본값(user-0001 / poctenant001 / event 1 / 90초 후 만료 / 무작위 jti)으로 시작하는 토큰 빌더. */
	public Builder token() {
		return new Builder();
	}

	public final class Builder {

		private String userId = DEFAULT_USER_ID;
		private String tenantId = DEFAULT_TENANT_ID;
		private Long eventId = DEFAULT_EVENT_ID;
		private Instant issuedAt = Instant.now();
		private Instant expiresAt = null;
		private String jti = UUID.randomUUID().toString();

		public Builder userId(String userId) { this.userId = userId; return this; }

		public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }

		public Builder eventId(Long eventId) { this.eventId = eventId; return this; }

		public Builder jti(String jti) { this.jti = jti; return this; }

		/** 발급 시각 기준 (기본: 지금). 만료 시각을 따로 안 정하면 발급 시각 + 90초. */
		public Builder issuedAt(Instant issuedAt) { this.issuedAt = issuedAt; return this; }

		public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }

		/** 이미 만료된 토큰 (1분 전 만료). */
		public Builder expired() {
			this.issuedAt = Instant.now().minusSeconds(150);
			this.expiresAt = Instant.now().minusSeconds(60);
			return this;
		}

		public JWTClaimsSet claims() {
			JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
					.issueTime(Date.from(issuedAt))
					.expirationTime(Date.from(expiresAt != null ? expiresAt : issuedAt.plus(DEFAULT_TTL)));
			if (userId != null) b.claim("user_id", userId);
			if (tenantId != null) b.claim("tenant_id", tenantId);
			if (eventId != null) b.claim("event_id", eventId);
			if (jti != null) b.jwtID(jti);
			return b.build();
		}

		/** 정상: 이 발급기의 개인키로 RS256 서명. */
		public String sign() {
			return signRs256(claims(), rsaKey);
		}

		/** 위조: 다른 키로 RS256 서명 (헤더·클레임은 정상). */
		public String signWithOtherKey() {
			return signRs256(claims(), withNewKeyPair().rsaKey);
		}

		/** 공격: 서명 없는 토큰 (alg: none). */
		public String unsigned() {
			return new PlainJWT(claims()).serialize();
		}

		/** 공격: 알고리즘 혼동 — 공개키 PEM 문자열을 HMAC 비밀키로 써서 HS256 서명. */
		public String hs256WithPublicKeyAsSecret() {
			try {
				SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims());
				jwt.sign(new MACSigner(publicKeyPem().getBytes(StandardCharsets.US_ASCII)));
				return jwt.serialize();
			}
			catch (JOSEException e) {
				throw new IllegalStateException(e);
			}
		}

		/** 위조: 정상 서명 후 페이로드만 바꿔치기 (서명 불일치). */
		public String tamperedPayload(String newUserId) {
			String[] parts = sign().split("\\.");
			JWTClaimsSet tampered = new JWTClaimsSet.Builder(claims()).claim("user_id", newUserId).build();
			String payload = Base64.getUrlEncoder().withoutPadding()
					.encodeToString(tampered.toString().getBytes(StandardCharsets.UTF_8));
			return parts[0] + "." + payload + "." + parts[2];
		}

	}

	private static String signRs256(JWTClaimsSet claims, RSAKey key) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

}

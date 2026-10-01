package io.tetra.issuance.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;

/** 테스트 도구가 의도한 토큰을 만드는지 검증 (정상은 검증 통과, 공격용은 실패). */
class TestJwtFactoryTest {

	private final TestJwtFactory factory = TestJwtFactory.withNewKeyPair();

	@Test
	void 정상_토큰은_PEM_공개키로_RS256_검증이_통과하고_클레임_5개를_담는다() throws Exception {
		SignedJWT jwt = SignedJWT.parse(factory.token().sign());

		assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
		assertThat(jwt.verify(new RSASSAVerifier(parsePem(factory.publicKeyPem())))).isTrue();
		var claims = jwt.getJWTClaimsSet();
		assertThat(claims.getStringClaim("user_id")).isEqualTo("user-0001");
		assertThat(claims.getStringClaim("tenant_id")).isEqualTo("poctenant001");
		assertThat(claims.getLongClaim("event_id")).isEqualTo(1L);
		assertThat(claims.getJWTID()).isNotBlank();
		assertThat(claims.getExpirationTime()).isAfter(java.util.Date.from(Instant.now()));
	}

	@Test
	void 토큰마다_jti가_다르다() throws Exception {
		String a = SignedJWT.parse(factory.token().sign()).getJWTClaimsSet().getJWTID();
		String b = SignedJWT.parse(factory.token().sign()).getJWTClaimsSet().getJWTID();
		assertThat(a).isNotEqualTo(b);
	}

	@Test
	void 만료_토큰은_exp가_과거다() throws Exception {
		var exp = SignedJWT.parse(factory.token().expired().sign()).getJWTClaimsSet().getExpirationTime();
		assertThat(exp).isBefore(java.util.Date.from(Instant.now()));
	}

	@Test
	void 다른_키로_서명한_토큰은_검증에_실패한다() throws Exception {
		SignedJWT jwt = SignedJWT.parse(factory.token().signWithOtherKey());
		assertThat(jwt.verify(new RSASSAVerifier(parsePem(factory.publicKeyPem())))).isFalse();
	}

	@Test
	void 페이로드를_바꿔치기한_토큰은_검증에_실패한다() throws Exception {
		SignedJWT jwt = SignedJWT.parse(factory.token().tamperedPayload("attacker"));
		assertThat(jwt.getJWTClaimsSet().getStringClaim("user_id")).isEqualTo("attacker");
		assertThat(jwt.verify(new RSASSAVerifier(parsePem(factory.publicKeyPem())))).isFalse();
	}

	@Test
	void 공격용_토큰은_헤더_alg가_none_또는_HS256이다() throws Exception {
		assertThat(JWTParser.parse(factory.token().unsigned()).getHeader().getAlgorithm().getName()).isEqualTo("none");
		assertThat(JWTParser.parse(factory.token().hs256WithPublicKeyAsSecret()).getHeader().getAlgorithm())
				.isEqualTo(JWSAlgorithm.HS256);
	}

	@Test
	void 클레임을_빼거나_바꿀_수_있다() throws Exception {
		var claims = SignedJWT.parse(factory.token().tenantId("othertenant1").eventId(null).jti(null).sign())
				.getJWTClaimsSet();
		assertThat(claims.getStringClaim("tenant_id")).isEqualTo("othertenant1");
		assertThat(claims.getClaim("event_id")).isNull();
		assertThat(claims.getJWTID()).isNull();
	}

	@Test
	void PEM_형식은_gen_test_keys_sh가_만드는_로컬_공개키와_같은_방식으로_읽힌다() throws Exception {
		assertThat(factory.publicKeyPem()).startsWith("-----BEGIN PUBLIC KEY-----\n")
				.endsWith("-----END PUBLIC KEY-----\n");
		Path local = Path.of("../local-keys/test-jwt-public.pem");
		assumeTrue(Files.exists(local), "로컬 키가 없으면 생략 (scripts/gen-test-keys.sh)");
		assertThat(parsePem(Files.readString(local)).getModulus().bitLength()).isEqualTo(2048);
	}

	/** event.public_key(PEM) → RSAPublicKey. 운영 코드도 같은 방식으로 읽을 예정. */
	static RSAPublicKey parsePem(String pem) throws Exception {
		String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
				.replace("-----END PUBLIC KEY-----", "")
				.replaceAll("\\s", "");
		byte[] der = Base64.getDecoder().decode(base64);
		return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
	}

}

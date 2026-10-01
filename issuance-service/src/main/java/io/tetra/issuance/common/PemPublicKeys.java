package io.tetra.issuance.common;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** event.public_key 에 저장된 PEM(SPKI, "-----BEGIN PUBLIC KEY-----") 을 RSA 공개키로 바꾼다. */
public final class PemPublicKeys {

	private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
	private static final String END = "-----END PUBLIC KEY-----";

	private PemPublicKeys() {
	}

	public static RSAPublicKey parseRsa(String pem) {
		if (pem == null || !pem.contains(BEGIN) || !pem.contains(END)) {
			throw new IllegalArgumentException("PEM 공개키 형식이 아닙니다");
		}
		String base64 = pem.replace(BEGIN, "").replace(END, "").replaceAll("\\s", "");
		try {
			byte[] der = Base64.getDecoder().decode(base64);
			return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
		}
		catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
			throw new IllegalArgumentException("RSA 공개키를 읽을 수 없습니다", e);
		}
	}

}

package io.tetra.issuance.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * event 테이블 (DB 정의서_2 4장) 중 Issuance Service 가 읽는 컬럼만 매핑한 읽기 전용 엔티티.
 * 이벤트 생성·수정은 Portal 영역이라 이 서비스는 쓰지 않는다.
 */
@Entity
@Immutable
@Table(name = "event")
public class Event {

	@Id
	@Column(name = "event_id")
	@JdbcTypeCode(SqlTypes.INTEGER) // INT UNSIGNED
	private Long eventId;

	@Column(name = "tenant_id", length = 12)
	@JdbcTypeCode(SqlTypes.CHAR) // CHAR(12) ascii_bin
	private String tenantId;

	@Column(name = "start_at")
	private LocalDateTime startAt;

	@Column(name = "public_key", length = 2048)
	private String publicKey;

	protected Event() {
	}

	public Long getEventId() {
		return eventId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public LocalDateTime getStartAt() {
		return startAt;
	}

	public String getPublicKey() {
		return publicKey;
	}

}

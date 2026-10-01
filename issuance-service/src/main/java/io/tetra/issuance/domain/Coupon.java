package io.tetra.issuance.domain;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * coupon 테이블 (DB 정의서_2 5장) 읽기 전용.
 * stock_count 는 신청 시점 기준값(고정) — 실시간 차감은 Redis 에서만 하고, 이 컬럼을 UPDATE 하는 코드는 만들지 않는다.
 */
@Entity
@Immutable
@Table(name = "coupon")
public class Coupon {

	@Id
	@Column(name = "coupon_id")
	@JdbcTypeCode(SqlTypes.INTEGER) // INT UNSIGNED
	private Long couponId;

	@Column(name = "event_id")
	@JdbcTypeCode(SqlTypes.INTEGER)
	private Long eventId;

	@Column(name = "tenant_id", length = 12)
	@JdbcTypeCode(SqlTypes.CHAR)
	private String tenantId;

	@Column(name = "name", length = 100)
	private String name;

	@Column(name = "description", length = 500)
	private String description;

	@Column(name = "stock_count")
	@JdbcTypeCode(SqlTypes.INTEGER)
	private Long stockCount;

	protected Coupon() {
	}

	public Long getCouponId() {
		return couponId;
	}

	public Long getEventId() {
		return eventId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public Long getStockCount() {
		return stockCount;
	}

}

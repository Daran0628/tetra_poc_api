package io.tetra.issuance.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * issuance_history 테이블 (DB 정의서_2 6장). claim 결과가 확정될 때 1회 INSERT 만 한다.
 * result 는 SUCCESS / FAILED_SOLDOUT 만 기록, served_at 은 PoC 미사용(NULL).
 */
@Entity
@Table(name = "issuance_history")
public class IssuanceHistory {

	public static final String RESULT_SUCCESS = "SUCCESS";
	public static final String RESULT_FAILED_SOLDOUT = "FAILED_SOLDOUT";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "issuance_id")
	private Long issuanceId;

	@Column(name = "tenant_id", length = 12)
	@JdbcTypeCode(SqlTypes.CHAR)
	private String tenantId;

	@Column(name = "event_id")
	@JdbcTypeCode(SqlTypes.INTEGER)
	private Long eventId;

	@Column(name = "user_id", length = 128)
	private String userId;

	@Column(name = "ticket_number")
	@JdbcTypeCode(SqlTypes.INTEGER)
	private Long ticketNumber;

	/** 번호표 발급 시각 (D9), KST */
	@Column(name = "queue_entered_at")
	private LocalDateTime queueEnteredAt;

	@Column(name = "served_at")
	private LocalDateTime servedAt;

	@Column(name = "result", length = 20)
	private String result;

	protected IssuanceHistory() {
	}

	public IssuanceHistory(String tenantId, long eventId, String userId, Long ticketNumber,
			LocalDateTime queueEnteredAt, String result) {
		this.tenantId = tenantId;
		this.eventId = eventId;
		this.userId = userId;
		this.ticketNumber = ticketNumber;
		this.queueEnteredAt = queueEnteredAt;
		this.servedAt = null;
		this.result = result;
	}

	public Long getIssuanceId() {
		return issuanceId;
	}

	public String getResult() {
		return result;
	}

}

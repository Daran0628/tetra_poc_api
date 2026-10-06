package io.tetra.issuance.service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;

import io.tetra.issuance.common.BusinessException;
import io.tetra.issuance.common.ErrorCode;
import io.tetra.issuance.config.TetraProperties;

/**
 * 이벤트 정보 (플랜 4-0). 02 대기방 카운트다운용 공개 정보 — 인증 없음, 기동 시 캐시에서 응답(MySQL 접근 없음).
 * 시각은 DB 의 KST 값을 +09:00 이 붙은 ISO 문자열로 내려 브라우저 시간대와 무관하게 해석되게 한다.
 */
@Service
public class EventInfoService {

	private final EventMetaCache events;
	private final ZoneId zone;

	public EventInfoService(EventMetaCache events, TetraProperties properties) {
		this.events = events;
		this.zone = properties.timezone();
	}

	/** bannerUrl·returnUrl 은 빈 문자열일 수 있다 */
	public record EventInfo(long eventId, String name, String startAt, String endAt, String bannerUrl,
			String returnUrl) {
	}

	public EventInfo find(long eventId) {
		EventMeta e = events.find(eventId).orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
		DateTimeFormatter iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
		return new EventInfo(e.eventId(), e.name(), e.startAt().atZone(zone).format(iso),
				e.endAt().atZone(zone).format(iso), e.bannerUrl(), e.returnUrl());
	}

}

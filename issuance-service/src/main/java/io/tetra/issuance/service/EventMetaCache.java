package io.tetra.issuance.service;

import java.security.interfaces.RSAPublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import io.tetra.issuance.common.PemPublicKeys;
import io.tetra.issuance.domain.Event;
import io.tetra.issuance.repository.EventRepository;

/**
 * 이벤트 메타(공개키·테넌트·시작 시각)를 기동 시 한 번 읽어 메모리에 둔다.
 * 요청마다 MySQL 을 읽지 않기 위함 (플랜 4-1). PEM 도 이때 미리 공개키 객체로 바꿔 둔다.
 * 기동 후 추가·변경된 이벤트는 {@link #reload()} 로 다시 읽는다 (PoC는 시드 이벤트 1개라 자동 갱신은 두지 않음).
 */
@Component
public class EventMetaCache implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(EventMetaCache.class);

	private final EventRepository eventRepository;
	private volatile Map<Long, EventMeta> byId = Map.of();

	public EventMetaCache(EventRepository eventRepository) {
		this.eventRepository = eventRepository;
	}

	/** 웹 서버가 요청을 받기 전에 채워 둔다. */
	@Override
	public void afterPropertiesSet() {
		reload();
	}

	public void reload() {
		Map<Long, EventMeta> loaded = new HashMap<>();
		for (Event e : eventRepository.findAll()) {
			loaded.put(e.getEventId(),
					new EventMeta(e.getEventId(), e.getTenantId(), e.getStartAt(), toPublicKey(e)));
		}
		byId = Map.copyOf(loaded);
		log.info("Event meta loaded: {} events ({} without public key)", loaded.size(),
				loaded.values().stream().filter(m -> !m.hasPublicKey()).count());
	}

	public Optional<EventMeta> find(long eventId) {
		return Optional.ofNullable(byId.get(eventId));
	}

	private static RSAPublicKey toPublicKey(Event e) {
		String pem = e.getPublicKey();
		if (pem == null || pem.isBlank()) {
			log.warn("Event {} has no public key — entry tokens for this event will be rejected", e.getEventId());
			return null;
		}
		try {
			return PemPublicKeys.parseRsa(pem);
		}
		catch (IllegalArgumentException ex) {
			log.error("Event {} public key is invalid — entry tokens for this event will be rejected", e.getEventId(), ex);
			return null;
		}
	}

}

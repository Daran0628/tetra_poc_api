package io.tetra.issuance.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import io.tetra.issuance.domain.Event;

public interface EventRepository extends JpaRepository<Event, Long> {
}

package io.tetra.issuance.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import io.tetra.issuance.domain.IssuanceHistory;

public interface IssuanceHistoryRepository extends JpaRepository<IssuanceHistory, Long> {
}

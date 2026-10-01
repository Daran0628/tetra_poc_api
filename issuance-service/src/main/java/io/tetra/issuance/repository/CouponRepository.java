package io.tetra.issuance.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import io.tetra.issuance.domain.Coupon;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

	List<Coupon> findAllByOrderByEventIdAscCouponIdAsc();

}

-- PoC 데이터베이스 정의서_2.md 8장 Seed INSERT 그대로
-- (앞의 SET NAMES / USE 두 줄만 추가: 한글 깨짐 방지, 대상 DB 명시)
SET NAMES utf8mb4;
USE tetra_poc;

SET @tenant_id = 'poctenant001';

INSERT INTO tenant (tenant_id, name, account_status)
VALUES (@tenant_id, '(주) PoC 테스트사', 'ACTIVE');

INSERT INTO event (event_id, tenant_id, name, type, start_at, end_at, hosting_start_at, hosting_end_at,
                   endpoint_url, event_status, hosting_status, live_path,
                   banner_image_path, public_key, subdomain)
VALUES (1, @tenant_id, 'PoC 쿠폰 발급 이벤트', 'COUPON',
        '2026-09-29 10:00:00', '2026-10-29 10:00:00',
        '2026-09-26 10:00:00', '2026-10-30 10:00:00',
        '', 'DEPLOYING', 'HOSTING', NULL, '', '', NULL);

INSERT INTO coupon (coupon_id, event_id, tenant_id, name, description, stock_count) VALUES
(1,  1, @tenant_id, 'PoC 쿠폰 1',  'PoC 쿠폰입니다.', 10),
(2,  1, @tenant_id, 'PoC 쿠폰 2',  'PoC 쿠폰입니다.', 10),
(3,  1, @tenant_id, 'PoC 쿠폰 3',  'PoC 쿠폰입니다.', 10),
(4,  1, @tenant_id, 'PoC 쿠폰 4',  'PoC 쿠폰입니다.', 10),
(5,  1, @tenant_id, 'PoC 쿠폰 5',  'PoC 쿠폰입니다.', 10),
(6,  1, @tenant_id, 'PoC 쿠폰 6',  'PoC 쿠폰입니다.', 10),
(7,  1, @tenant_id, 'PoC 쿠폰 7',  'PoC 쿠폰입니다.', 10),
(8,  1, @tenant_id, 'PoC 쿠폰 8',  'PoC 쿠폰입니다.', 10),
(9,  1, @tenant_id, 'PoC 쿠폰 9',  'PoC 쿠폰입니다.', 10),
(10, 1, @tenant_id, 'PoC 쿠폰 10', 'PoC 쿠폰입니다.', 10);

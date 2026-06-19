package com.schale.queue.core.domain.goods.repository;

import com.schale.queue.core.domain.goods.Goods;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 상품 Repository.
 *
 * <p>읽기 위주 도메인(ADR-001). 향후 목록 조회는 캐싱과 결합하여 최적화한다.
 */
public interface GoodsRepository extends JpaRepository<Goods, Long> {

    /** 지정 시각 이전에 오픈된(=판매 진행 중) 상품 목록. */
    List<Goods> findByOpenAtBefore(LocalDateTime time);
}

package com.schale.queue.core.domain.stock.repository;

import com.schale.queue.core.domain.stock.Stock;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * 재고 Repository.
 *
 * <p>선착순 동시성 제어의 핵심. 재고 차감 시 경합이 단일 행에 집중되므로(ADR-001),
 * <b>비관적 쓰기 락(PESSIMISTIC_WRITE)</b>으로 임계 구역을 직렬화한다.
 * 락 조회 → 차감 → 커밋까지 하나의 트랜잭션 안에서 수행해야 효과가 있다.
 *
 * <p>{@code jakarta.persistence.lock.timeout} 힌트로 락 대기 시간을 제한하여,
 * 극심한 경합 상황에서 무한 대기(및 커넥션 고갈)를 방지한다. (단위: ms, DB 의존)
 */
public interface StockRepository extends JpaRepository<Stock, Long> {

    /**
     * PK 기준 비관적 쓰기 락 조회. 재고 차감 트랜잭션의 진입점.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select s from Stock s where s.id = :id")
    Optional<Stock> findByIdWithPessimisticLock(@Param("id") Long id);

    /**
     * 상품 ID 기준 비관적 쓰기 락 조회. 상품 단위로 재고를 차감하는 주문 흐름에서 사용한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select s from Stock s where s.goodsId = :goodsId")
    Optional<Stock> findByGoodsIdWithPessimisticLock(@Param("goodsId") Long goodsId);

    /**
     * 락 없는 일반 조회(상품 ID 기준). 단순 재고 노출/조회용.
     */
    Optional<Stock> findByGoodsId(Long goodsId);
}

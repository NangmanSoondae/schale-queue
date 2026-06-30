package com.schale.queue.core.domain.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.schale.queue.core.domain.order.event.OrderCancelledEvent;
import com.schale.queue.core.domain.order.event.OrderCompletedEvent;
import com.schale.queue.core.domain.settlement.repository.SettlementRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link SettlementService} 단위 테스트 — 수수료 차감 적재·반제·멱등(ADR-002 후방 컨슈머 확장).
 *
 * <p>DB 없이 레포지토리를 mock 으로 대체해 (1) 수수료/지급액 계산, (2) 이미 정산된 주문의 멱등 스킵,
 * (3) 취소 반제의 상태 전이와 (4) 미정산 주문 취소의 no-op 를 격리 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock private SettlementRepository settlementRepository;

    private SettlementService service;

    /** 결정적 검증을 위한 고정 시계(UTC). */
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-06-30T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new SettlementService(settlementRepository, fixedClock);
        ReflectionTestUtils.setField(service, "commissionRate", 0.03);
    }

    @Test
    @DisplayName("주문완료: 수수료(3%)를 차감해 PENDING_PAYOUT 으로 적재한다")
    void settles_with_commission_deducted() {
        OrderCompletedEvent event = OrderCompletedEvent.of(1001L, 42L, 100_000L);
        given(settlementRepository.existsByOrderId(1001L)).willReturn(false);

        service.settle(event);

        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        then(settlementRepository).should().save(captor.capture());
        Settlement saved = captor.getValue();
        assertThat(saved.getGrossAmount()).isEqualTo(100_000L);
        assertThat(saved.getFeeAmount()).isEqualTo(3_000L);     // floor(100000 * 0.03)
        assertThat(saved.getNetAmount()).isEqualTo(97_000L);
        assertThat(saved.getStatus()).isEqualTo(SettlementStatus.PENDING_PAYOUT);
        assertThat(saved.getOrderId()).isEqualTo(1001L);
        assertThat(saved.getMemberId()).isEqualTo(42L);
        assertThat(saved.getSettledAt()).isEqualTo(LocalDateTime.now(fixedClock));
    }

    @Test
    @DisplayName("수수료는 원 단위로 버림(floor)한다")
    void floors_fee_to_won() {
        OrderCompletedEvent event = OrderCompletedEvent.of(1002L, 42L, 33_333L);
        given(settlementRepository.existsByOrderId(1002L)).willReturn(false);

        service.settle(event);

        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        then(settlementRepository).should().save(captor.capture());
        // 33333 * 0.03 = 999.99 → 999 버림, 지급액 32334
        assertThat(captor.getValue().getFeeAmount()).isEqualTo(999L);
        assertThat(captor.getValue().getNetAmount()).isEqualTo(32_334L);
    }

    @Test
    @DisplayName("이미 정산된 주문이면 적재를 건너뛴다(멱등)")
    void skips_already_settled_order() {
        OrderCompletedEvent event = OrderCompletedEvent.of(1001L, 42L, 100_000L);
        given(settlementRepository.existsByOrderId(1001L)).willReturn(true);

        service.settle(event);

        then(settlementRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("주문취소: 기존 정산이 있으면 REVERSED 로 반제한다")
    void reverses_existing_settlement() {
        Settlement existing =
            Settlement.settle(1001L, 42L, 100_000L, 0.03, LocalDateTime.now(fixedClock));
        given(settlementRepository.findByOrderId(1001L)).willReturn(Optional.of(existing));
        OrderCancelledEvent event =
            OrderCancelledEvent.of(1001L, 42L, OrderCancelledEvent.REASON_PAYMENT_EXPIRED);

        service.reverse(event);

        assertThat(existing.getStatus()).isEqualTo(SettlementStatus.REVERSED);
    }

    @Test
    @DisplayName("미정산 주문(예: 결제 만료) 취소는 no-op")
    void noop_when_no_settlement_to_reverse() {
        given(settlementRepository.findByOrderId(2002L)).willReturn(Optional.empty());
        OrderCancelledEvent event =
            OrderCancelledEvent.of(2002L, 42L, OrderCancelledEvent.REASON_PAYMENT_EXPIRED);

        service.reverse(event);

        then(settlementRepository).should().findByOrderId(2002L);
        then(settlementRepository).should(never()).save(any());
    }
}

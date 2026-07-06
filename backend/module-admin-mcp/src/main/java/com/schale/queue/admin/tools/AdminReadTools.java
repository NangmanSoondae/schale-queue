package com.schale.queue.admin.tools;

import com.schale.queue.core.domain.goods.GoodsService;
import com.schale.queue.core.domain.queue.QueueProperties;
import com.schale.queue.core.domain.queue.QueueService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * AI 어드민 읽기 전용 툴 5종(ADR-009 1차).
 *
 * <p>도메인 규칙이 있는 조회(Goods 판매 판정, 대기열)는 core 서비스를 재사용하고,
 * 리포트성 집계(주문/정산/재고 스냅샷)는 읽기 전용 {@link JdbcTemplate} 로 수행한다 —
 * JPA 엔티티·락 경로를 우회해 판매 경로와 간섭하지 않는다.
 *
 * <p>반환 레코드는 그대로 JSON 직렬화되어 MCP 클라이언트(Claude)에 전달된다.
 * 설명(description)은 AI 가 오용하지 않도록 단위·시간대·의미를 명시한다.
 */
@Component
@RequiredArgsConstructor
public class AdminReadTools {

    private final GoodsService goodsService;
    private final QueueService queueService;
    private final QueueProperties queueProperties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // --- list_goods ---------------------------------------------------------

    public record GoodsSummary(Long id, String name, long priceKrw, String openAtUtc,
                               boolean saleOpen, Integer maxPurchasePerMember, long paymentTimeoutMinutes) {
    }

    @Tool(name = "list_goods", description = """
        전체 상품 목록을 조회한다. priceKrw=판매가(원), openAtUtc=판매 오픈 시각(UTC),
        saleOpen=현재 판매중 여부(서버 UTC 판정), maxPurchasePerMember=1인 구매 한도(null=무제한),
        paymentTimeoutMinutes=결제창 수명(분).""")
    public List<GoodsSummary> listGoods() {
        LocalDateTime now = LocalDateTime.now(clock);
        return goodsService.findAll().stream()
            .map(g -> new GoodsSummary(g.getId(), g.getName(), g.getPrice(), g.getOpenAt().toString(),
                !now.isBefore(g.getOpenAt()), g.getMaxPurchasePerMember(), g.paymentTimeout().toMinutes()))
            .toList();
    }

    // --- stock_status -------------------------------------------------------

    public record StockStatus(Long goodsId, String goodsName, int total, int available,
                              int reserved, int sold, boolean invariantOk) {
    }

    @Tool(name = "stock_status", description = """
        상품별 재고 3-카운터를 조회한다. available=구매 가능, reserved=결제 대기 중 예약,
        sold=판매 확정. invariantOk=합계 불변식(total = available+reserved+sold) 성립 여부 —
        false 가 하나라도 있으면 정합성 사고이므로 즉시 보고할 것.""")
    public List<StockStatus> stockStatus() {
        return jdbc.query("""
                select g.id, g.name, s.total_quantity, s.available_quantity, s.reserved_quantity, s.sold_quantity
                  from stock s join goods g on g.id = s.goods_id order by g.id
                """,
            (rs, i) -> {
                int total = rs.getInt(3);
                int available = rs.getInt(4);
                int reserved = rs.getInt(5);
                int sold = rs.getInt(6);
                return new StockStatus(rs.getLong(1), rs.getString(2), total, available, reserved, sold,
                    total == available + reserved + sold);
            });
    }

    // --- queue_status -------------------------------------------------------

    public record QueueStatus(List<GoodsQueue> activeQueues, int admitBatchSize, String admitInterval,
                              String note) {
        public record GoodsQueue(Long goodsId, long waiting) {
        }
    }

    @Tool(name = "queue_status", description = """
        활성 대기열 현황을 조회한다. waiting=상품별 현재 대기 인원(Redis ZSET),
        admitBatchSize/admitInterval=워커의 입장 처리 설정(주기당 배치). 대기열이 비어 있으면
        activeQueues 가 빈 배열이다(폭주 상황이 아니라는 뜻).""")
    public QueueStatus queueStatus() {
        List<QueueStatus.GoodsQueue> queues = queueService.activeGoods().stream()
            .sorted()
            .map(goodsId -> new QueueStatus.GoodsQueue(goodsId, queueService.size(goodsId)))
            .toList();
        return new QueueStatus(queues,
            queueProperties.getConsumer().getBatchSize(),
            queueProperties.getConsumer().getInterval().toString(),
            "예상 소진 시간(초) ≈ waiting ÷ (batchSize × 1000ms/interval)");
    }

    // --- order_summary ------------------------------------------------------

    public record OrderStatusSummary(String status, long count, long amountKrw) {
    }

    @Tool(name = "order_summary", description = """
        최근 N시간(UTC 기준)의 주문을 상태별로 집계한다. PENDING=결제 대기, COMPLETED=결제 완료,
        CANCELLED=취소/만료. amountKrw=해당 상태 주문 총액(원). sinceHours 기본 24, 최대 720.""")
    public List<OrderStatusSummary> orderSummary(
        @ToolParam(description = "집계 구간(시간). 1~720, 기본 24") Integer sinceHours) {
        LocalDateTime since = LocalDateTime.now(clock).minusHours(clampHours(sinceHours));
        return jdbc.query("""
                select order_status, count(*), coalesce(sum(total_amount), 0)
                  from orders where created_at >= ? group by order_status order by order_status
                """,
            (rs, i) -> new OrderStatusSummary(rs.getString(1), rs.getLong(2), rs.getLong(3)),
            since);
    }

    // --- settlement_summary -------------------------------------------------

    public record SettlementSummary(String status, long count, long grossKrw, long feeKrw, long netKrw) {
    }

    @Tool(name = "settlement_summary", description = """
        최근 N시간(UTC 기준)의 정산 원장을 상태별로 집계한다. PENDING_PAYOUT=지급 대기,
        REVERSED=취소 역정산. grossKrw=주문 총액, feeKrw=플랫폼 수수료, netKrw=판매자 지급액(원).
        sinceHours 기본 24, 최대 720.""")
    public List<SettlementSummary> settlementSummary(
        @ToolParam(description = "집계 구간(시간). 1~720, 기본 24") Integer sinceHours) {
        LocalDateTime since = LocalDateTime.now(clock).minusHours(clampHours(sinceHours));
        return jdbc.query("""
                select status, count(*), coalesce(sum(gross_amount), 0),
                       coalesce(sum(fee_amount), 0), coalesce(sum(net_amount), 0)
                  from settlement where settled_at >= ? group by status order by status
                """,
            (rs, i) -> new SettlementSummary(rs.getString(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5)),
            since);
    }

    /** AI 가 극단값을 넣어도 조회 폭을 한 달로 제한한다(전 테이블 스캔 방지). */
    private static int clampHours(Integer hours) {
        if (hours == null) {
            return 24;
        }
        return Math.max(1, Math.min(720, hours));
    }
}

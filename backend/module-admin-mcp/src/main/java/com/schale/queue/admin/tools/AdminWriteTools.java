package com.schale.queue.admin.tools;

import com.schale.queue.core.domain.goods.Goods;
import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import com.schale.queue.core.domain.stock.Stock;
import com.schale.queue.core.domain.stock.repository.StockRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 어드민 쓰기 툴 3종(ADR-009 2차).
 *
 * <p><b>안전장치</b>:
 * <ul>
 *   <li>이 컴포넌트의 툴들은 {@code schale.admin.write-enabled=true} 일 때만 MCP 에 노출된다
 *       ({@link com.schale.queue.admin.config.AdminToolConfig} 게이트) — 기본은 읽기 전용.</li>
 *   <li>파괴적/즉시 효력 변경(재고 감소, 오픈 시각 변경)은 {@code confirm=true} 를 요구한다 —
 *       AI 가 대화 맥락을 오해해 단독 실행하는 실수를 한 단계 차단(운영자 재확인 강제).</li>
 *   <li>모든 결과는 변경 전/후 값을 함께 반환해, AI 가 운영자에게 정확히 보고할 수 있게 한다.</li>
 * </ul>
 *
 * <p>검증은 도메인이 소유한다: 가격·한도·결제창(1~30분) 규칙은 {@link Goods} 빌더가,
 * 재고 감소 하한(available 이상 감소 불가)은 원자적 가드 UPDATE 가 강제한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminWriteTools {

    private final GoodsRepository goodsRepository;
    private final StockRepository stockRepository;
    private final JdbcTemplate jdbc;

    // --- create_goods -------------------------------------------------------

    public record CreateGoodsResult(Long goodsId, String name, long priceKrw, String openAtUtc,
                                    int maxPurchasePerMember, long paymentTimeoutMinutes, int initialStock) {
    }

    @Tool(name = "create_goods", description = """
        신규 상품과 재고를 등록한다. openAtUtc 는 UTC ISO-8601(예: 2026-07-10T01:00:00) —
        한국시간이 아니라 UTC 다(KST-9h). maxPurchasePerMember 는 1인 구매 한도(P-O3),
        paymentTimeoutMinutes 는 결제창 수명 1~30분(P-O2, 범위 밖이면 거부됨).
        initialStock 은 판매 가능 재고 수량이다.""")
    @Transactional
    public CreateGoodsResult createGoods(
        @ToolParam(description = "상품명") String name,
        @ToolParam(description = "판매가(원, 양수)") long priceKrw,
        @ToolParam(description = "판매 오픈 시각 — UTC ISO-8601 (예: 2026-07-10T01:00:00)") String openAtUtc,
        @ToolParam(description = "1인 구매 한도(1 이상)") int maxPurchasePerMember,
        @ToolParam(description = "결제창 수명(분, 1~30)") int paymentTimeoutMinutes,
        @ToolParam(description = "초기 재고 수량(0 이상)") int initialStock) {

        if (priceKrw <= 0 || maxPurchasePerMember < 1 || initialStock < 0) {
            throw new IllegalArgumentException(
                "가격은 양수, 한도는 1 이상, 초기 재고는 0 이상이어야 합니다.");
        }
        Goods goods = goodsRepository.save(Goods.builder()
            .name(name)
            .price(priceKrw)
            .openAt(parseUtc(openAtUtc))
            .maxPurchasePerMember(maxPurchasePerMember)
            .paymentTimeoutMinutes(paymentTimeoutMinutes)   // 1~30 검증은 빌더(P-O2)
            .build());
        stockRepository.save(Stock.builder()
            .goodsId(goods.getId())
            .totalQuantity(initialStock)
            .availableQuantity(initialStock)
            .reservedQuantity(0)
            .soldQuantity(0)
            .build());
        log.info("[admin-write] 상품 등록 goodsId={} name={} stock={}", goods.getId(), name, initialStock);
        return new CreateGoodsResult(goods.getId(), name, priceKrw, goods.getOpenAt().toString(),
            maxPurchasePerMember, paymentTimeoutMinutes, initialStock);
    }

    // --- adjust_stock -------------------------------------------------------

    public record AdjustStockResult(Long goodsId, int delta, int totalAfter, int availableAfter, String note) {
    }

    @Tool(name = "adjust_stock", description = """
        재고를 증감한다(total 과 available 이 함께 delta 만큼 변한다 — reserved/sold 는 불변).
        delta 양수=입고, 음수=차감. ⚠️ 감소는 confirm=true 필수(파괴적 변경 — 운영자에게
        수량과 사유를 재확인받은 뒤에만 호출할 것). available 보다 큰 감소는 거부된다
        (예약/판매분은 건드릴 수 없다).""")
    public AdjustStockResult adjustStock(
        @ToolParam(description = "상품 ID") long goodsId,
        @ToolParam(description = "증감 수량(양수=입고, 음수=차감)") int delta,
        @ToolParam(description = "감소 시 필수 — 운영자 재확인 후 true") Boolean confirm) {

        if (delta == 0) {
            throw new IllegalArgumentException("delta 는 0 이 될 수 없습니다.");
        }
        if (delta < 0 && !Boolean.TRUE.equals(confirm)) {
            throw new IllegalStateException(
                "재고 감소는 파괴적 변경입니다 — 운영자에게 수량·사유를 재확인받고 confirm=true 로 다시 호출하세요.");
        }
        // 원자적 가드 UPDATE: available+delta >= 0 조건을 DB 가 강제한다(락 경합 없이 단일 문장).
        // 판매 경로(비관 락 reserve/confirm)와는 행 잠금 수준에서 자연 직렬화된다.
        int updated = jdbc.update("""
                update stock set total_quantity = total_quantity + ?,
                                 available_quantity = available_quantity + ?
                 where goods_id = ? and available_quantity + ? >= 0
                """, delta, delta, goodsId, delta);
        if (updated == 0) {
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from stock where goods_id = ?)", Boolean.class, goodsId));
            throw new IllegalStateException(exists
                ? "가용 재고보다 큰 감소는 불가합니다(예약/판매분 보호). 현재 available 을 stock_status 로 확인하세요."
                : "재고가 존재하지 않습니다. goodsId=" + goodsId);
        }
        var row = jdbc.queryForMap(
            "select total_quantity, available_quantity from stock where goods_id = ?", goodsId);
        log.info("[admin-write] 재고 조정 goodsId={} delta={}", goodsId, delta);
        return new AdjustStockResult(goodsId, delta,
            ((Number) row.get("total_quantity")).intValue(),
            ((Number) row.get("available_quantity")).intValue(),
            delta > 0 ? "입고 완료" : "차감 완료(운영자 확인됨)");
    }

    // --- update_goods_open_at -----------------------------------------------

    public record UpdateOpenAtResult(Long goodsId, String previousOpenAtUtc, String newOpenAtUtc) {
    }

    @Tool(name = "update_goods_open_at", description = """
        상품의 판매 오픈 시각(UTC)을 변경한다. ⚠️ confirm=true 필수 — 앞당기면 즉시 판매가
        시작될 수 있고, 미루면 진행 중 대기열에 영향을 준다. 운영자에게 변경 전/후 시각을
        재확인받은 뒤에만 호출할 것. openAtUtc 는 UTC ISO-8601(KST-9h).""")
    @Transactional
    public UpdateOpenAtResult updateGoodsOpenAt(
        @ToolParam(description = "상품 ID") long goodsId,
        @ToolParam(description = "새 오픈 시각 — UTC ISO-8601") String openAtUtc,
        @ToolParam(description = "운영자 재확인 후 true (필수)") Boolean confirm) {

        if (!Boolean.TRUE.equals(confirm)) {
            throw new IllegalStateException(
                "오픈 시각 변경은 판매 시작 시점을 바꿉니다 — 운영자에게 변경 전/후를 재확인받고 confirm=true 로 다시 호출하세요.");
        }
        LocalDateTime newOpenAt = parseUtc(openAtUtc);
        String previous = jdbc.queryForObject(
            "select open_at from goods where id = ?", String.class, goodsId);
        if (previous == null) {
            throw new IllegalArgumentException("상품이 존재하지 않습니다. goodsId=" + goodsId);
        }
        jdbc.update("update goods set open_at = ? where id = ?", newOpenAt, goodsId);
        log.info("[admin-write] 오픈 시각 변경 goodsId={} {} -> {}", goodsId, previous, newOpenAt);
        return new UpdateOpenAtResult(goodsId, previous, newOpenAt.toString());
    }

    private static LocalDateTime parseUtc(String openAtUtc) {
        try {
            return LocalDateTime.parse(openAtUtc);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                "openAtUtc 형식 오류 — UTC ISO-8601 로 입력하세요(예: 2026-07-10T01:00:00). 입력=" + openAtUtc);
        }
    }
}

package com.schale.queue.api.queue;

import com.schale.queue.api.queue.dto.QueuePositionResponse;
import com.schale.queue.core.domain.goods.GoodsService;
import com.schale.queue.core.domain.queue.AdmissionTokenService;
import com.schale.queue.core.domain.queue.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 대기열 API. 진입(enqueue)과 실시간 순번/입장 스트림(SSE)을 제공한다(Phase 3 ⑤).
 *
 * <p>회원 식별은 주문 API 와 동일하게 {@code X-Member-Id} 헤더로 받는다(입장 권한 ≠ 인증, P-Q3).
 */
@Tag(name = "Queue", description = "대기열 진입 및 실시간 순번/입장 알림 API")
@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;
    private final QueueStreamService queueStreamService;
    private final GoodsService goodsService;
    private final AdmissionTokenService admissionTokenService;

    @Operation(
        summary = "대기열 진입",
        description = "상품 대기열에 진입하고 현재 순번을 반환한다(P-Q1 FIFO). 이미 대기 중이면 기존 순번을 유지한다(P-Q2). "
            + "유효한 입장 토큰 보유자는 재진입하지 않고 position=0 을 반환한다(이미 입장 상태). "
            + "상품이 없으면 404, 판매 시작(openAt) 전이면 409."
    )
    @PostMapping("/{goodsId}/entries")
    public ResponseEntity<QueuePositionResponse> enter(
        @PathVariable Long goodsId,
        @RequestHeader("X-Member-Id") Long memberId
    ) {
        // 판매 시작 게이트(UC-02, 리뷰 M5). 대기열(Redis) 도메인은 Goods(DB) 에 결합시키지 않고
        // 게이트를 앞단에서 통과시킨다. 존재하지 않는 goodsId 의 큐 영구 잔류도 함께 차단.
        goodsService.checkSaleOpen(goodsId);

        // 유령 엔트리 방지(리뷰2 M-7): 유효한 입장 토큰 보유자(admitted 직후 뒤로가기/재진입)는 이미
        // 큐를 통과한 상태다 — 재enqueue 하면 본인은 SSE 초기 push 로 즉시 다시 admitted 되지만,
        // ZSET 에 남은 유령 엔트리를 워커가 나중에 pop 하며 admit 배치 예산 1개를 소각하고(실제
        // 대기자 1명의 입장이 밀림) waiting 도 부풀린다. position=0(입장 상태)으로 응답만 한다.
        if (admissionTokenService.hasValidToken(goodsId, memberId)) {
            return ResponseEntity.ok(new QueuePositionResponse(0, queueService.size(goodsId)));
        }

        queueService.enqueue(goodsId, memberId);
        long position = queueService.getPosition(goodsId, memberId).orElse(0L);
        long waiting = queueService.size(goodsId);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new QueuePositionResponse(position, waiting));
    }

    @Operation(
        summary = "대기 순번 조회",
        description = "현재 대기 순번과 총 대기 인원을 1회 조회한다(P-Q1, ZRANK O(log N)). 대기 중이 아니면 position=0."
    )
    @GetMapping("/{goodsId}/position")
    public QueuePositionResponse position(
        @PathVariable Long goodsId,
        @RequestHeader("X-Member-Id") Long memberId
    ) {
        long position = queueService.getPosition(goodsId, memberId).orElse(0L);
        long waiting = queueService.size(goodsId);
        return new QueuePositionResponse(position, waiting);
    }

    @Operation(
        summary = "실시간 대기 상태 구독(SSE)",
        description = "순번 갱신을 'position' 이벤트로 주기 전송하고, 입장 토큰이 발급되면 'admitted' 이벤트를 보낸 뒤 스트림을 종료한다."
    )
    @GetMapping(value = "/{goodsId}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
        @PathVariable Long goodsId,
        @RequestHeader("X-Member-Id") Long memberId
    ) {
        return queueStreamService.subscribe(goodsId, memberId);
    }
}

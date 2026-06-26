package com.schale.queue.api.bench;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부하 측정 전용 벤치 컨트롤러 — <b>느린(블로킹) 다운스트림</b> 시뮬레이션.
 *
 * <p>VT 의 처리량 우위는 "요청당 블로킹이 충분히 길어 플랫폼 스레드풀(200)이 고갈되는" 워크로드에서만
 * 드러난다(부하 리포트 §VT 비교). 실제 enqueue 는 너무 빨라 그 구간을 못 만들므로, 의도적으로
 * {@code Thread.sleep} 으로 블로킹하는 엔드포인트를 두어 VT on/off 처리량을 대조한다.
 *
 * <p>운영 노출 금지: {@code schale.bench.enabled=true} 일 때만 빈이 등록된다(기본 비활성).
 */
@Tag(name = "Bench", description = "부하 측정 전용(느린 다운스트림 시뮬레이션) — 기본 비활성")
@RestController
@RequestMapping("/api/v1/bench")
@ConditionalOnProperty(name = "schale.bench.enabled", havingValue = "true")
public class BenchController {

    @Operation(summary = "느린 다운스트림 시뮬레이션", description = "ms 만큼 블로킹 후 응답. VT vs 플랫폼 처리량 대조용.")
    @GetMapping("/slow")
    public String slow(@RequestParam(defaultValue = "100") long ms) throws InterruptedException {
        // 느린 외부 I/O 를 흉내 내는 블로킹. 플랫폼 스레드면 캐리어가 묶이고(풀 고갈),
        // 가상 스레드면 캐리어를 반납하고 대기한다 — 여기서 VT 처리량 우위가 드러난다.
        Thread.sleep(ms);
        return "ok";
    }
}

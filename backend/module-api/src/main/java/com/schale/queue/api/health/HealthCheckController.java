package com.schale.queue.api.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서버 상태 점검(Health Check) 컨트롤러.
 */
@Tag(name = "Health Check", description = "서버 가동 상태 점검 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthCheckController {

    private final HealthService healthService;

    @Operation(
        summary = "서버 헬스 체크",
        description = "서버 가동 메시지와 현재 서버 시간을 반환한다. 로드밸런서/모니터링의 생존 확인용."
    )
    @GetMapping("/health")
    public HealthResponse health() {
        return healthService.check();
    }
}

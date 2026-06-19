package com.schale.queue.api.health;

import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 헬스 체크 도메인 서비스.
 *
 * <p>컨트롤러가 직접 시간을 만들지 않고 서비스에 위임함으로써, 응답 생성 로직을
 * 단위 테스트에서 손쉽게 검증/대체(mock)할 수 있도록 한다.
 */
@Service
public class HealthService {

    private static final String RUNNING_MESSAGE = "Schale Queue Server is Running!";

    public HealthResponse check() {
        return new HealthResponse(RUNNING_MESSAGE, LocalDateTime.now());
    }
}

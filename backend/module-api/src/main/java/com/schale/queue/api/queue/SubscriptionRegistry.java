package com.schale.queue.api.queue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 활성 SSE 구독 레지스트리. {@code (goodsId, memberId)} 당 하나의 {@link SseEmitter} 를 보관한다.
 *
 * <p>이 레지스트리는 <b>인스턴스 로컬</b>이다. SSE 연결은 그 연결을 수락한 API 인스턴스에만
 * 존재하므로, 폴러도 같은 인스턴스의 emitter 만 갱신하면 된다(스케일아웃 시 조율 불필요).
 */
@Component
public class SubscriptionRegistry {

    /** 구독 식별자. 레코드라 equals/hashCode 가 값 기반으로 제공된다. */
    public record Subscription(Long goodsId, Long memberId) {
    }

    private final Map<Subscription, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 구독을 등록한다. 같은 키의 기존 연결이 있으면(재연결) 그 emitter 를 반환하니, 호출 측이 정리한다.
     *
     * @return 교체된 이전 emitter, 없으면 {@code null}
     */
    public SseEmitter add(Subscription key, SseEmitter emitter) {
        return emitters.put(key, emitter);
    }

    /** 키-emitter 쌍이 일치할 때만 제거한다(다른 스레드가 이미 새 연결로 교체했으면 건드리지 않는다). */
    public void remove(Subscription key, SseEmitter emitter) {
        emitters.remove(key, emitter);
    }

    /** 폴링용 불변 스냅샷. 순회 중 원본이 변경돼도 안전하다. */
    public Map<Subscription, SseEmitter> snapshot() {
        return Map.copyOf(emitters);
    }

    /** 현재 활성 구독 수(모니터링/테스트용). */
    public int size() {
        return emitters.size();
    }
}

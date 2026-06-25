package com.schale.queue.core.domain.queue;

import java.time.Clock;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 전방 진입 대기열 도메인 서비스(Redis Sorted Set 기반, ADR-002 §2).
 *
 * <p>폭증 트래픽을 DB 로 직접 흘리지 않고 ZSET 대기열에 먼저 쌓아 평탄화한다.
 * <b>선착순(FIFO) 공정성</b>(P-Q1)을 위해 score 를 진입 시각 기반으로 두되,
 * 같은 밀리초에 몰린 동시 진입은 <b>단조 증가 시퀀스</b>로 도착 순서를 보존한다(아래 참조).
 * {@code ZRANK} 로 실시간 순번을 조회한다.
 *
 * <p><b>score 설계(P-Q1)</b>: {@code score = millis × 10^d + (시퀀스 mod 10^d)}.
 * <ul>
 *   <li>1차 정렬 = 진입 시각(ms): 시간 순서를 보장하고, score 에서 진입 시각을 역산할 수 있다.</li>
 *   <li>동점 해소 = 진입 시퀀스({@code INCR queue:{goodsId}:seq}): 같은 ms 진입자를 <b>도착 순서</b>로
 *       구분한다. memberId 사전순 같은 편향을 배제하고 충돌을 없앤다.</li>
 *   <li>{@code d}({@link QueueProperties#getScoreTiebreakDigits()})는 외부화·정밀도 검증된다.</li>
 * </ul>
 *
 * <p>진입 시각은 주입된 {@link Clock} 에서 얻어 테스트 결정성을 확보한다.
 * 정해진 처리량만큼 앞에서 꺼내 입장시키는 소비({@link #dequeue})는 module-worker 의
 * Consumer 가 호출하며, 본 서비스는 그 <b>원자적 1회 연산</b>만 제공한다.
 */
@Service
@RequiredArgsConstructor
public class QueueService {

    /**
     * 대기열 소비를 <b>단일 원자 연산</b>으로 수행하는 Lua 스크립트.
     *
     * <p>{@code ZPOPMIN} 으로 score 최솟값(선착순 맨 앞) {@code count} 명을 꺼내고,
     * 큐가 비면 활성 레지스트리에서 빼고({@code SREM})  시퀀스 카운터까지 정리한다({@code DEL}).
     * 세 연산을 한 스크립트로 묶어 Redis 단일 스레드에서 불가분하게 실행하므로,
     * "비었다고 판단 → SREM" 사이에 끼어든 동시 enqueue 가 레지스트리에서 <b>잘못 제거되어
     * 대기자가 영영 소비되지 않는</b> 경합을 차단한다(enqueue 는 ZADD 뒤 SADD 순서를 지켜 보완).
     *
     * <ul>
     *   <li>KEYS: [1]=대기열 ZSET, [2]=활성 레지스트리 Set, [3]=진입 시퀀스</li>
     *   <li>ARGV: [1]=최대 인원(count), [2]=goodsId(활성 Set 멤버)</li>
     *   <li>반환: 꺼낸 memberId 문자열 목록(도착 순서)</li>
     * </ul>
     */
    private static final RedisScript<List> DEQUEUE_SCRIPT = new DefaultRedisScript<>("""
        local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
        local members = {}
        for i = 1, #popped, 2 do
            members[#members + 1] = popped[i]
        end
        if redis.call('ZCARD', KEYS[1]) == 0 then
            redis.call('SREM', KEYS[2], ARGV[2])
            redis.call('DEL', KEYS[3])
        end
        return members
        """, List.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final QueueProperties properties;

    /**
     * 대기열에 진입시킨다. {@code score = millis × 10^d + (시퀀스 mod 10^d)} 로 ZADD 한다.
     *
     * <p><b>P-Q2 중복 진입</b>: 이미 대기 중인 회원은 {@code ZADD NX} 시맨틱으로
     * 신규 발급 없이 <b>기존 순번(score)을 유지</b>한다(뒤로 밀지 않음).
     *
     * @return 신규 진입이면 {@code true}, 이미 대기 중이어서 무시되면 {@code false}
     */
    public boolean enqueue(Long goodsId, Long memberId) {
        long scale = properties.scoreScale();
        Long seq = redis.opsForValue().increment(QueueKeys.sequence(goodsId));
        long tiebreak = (seq == null ? 0L : seq) % scale;
        // millis × scale 은 2^53 미만(QueueProperties.validate 보장)이라 double 로 정확히 표현된다.
        double score = (double) clock.millis() * scale + tiebreak;

        Boolean added = redis.opsForZSet().addIfAbsent(
            QueueKeys.waitingQueue(goodsId),
            String.valueOf(memberId),
            score
        );
        // 활성 레지스트리에 등록(Worker 소비 대상 발견용). ZADD 뒤에 SADD 해야
        // dequeue 의 빈 큐 정리(SREM)와 안전하게 교차한다(위 DEQUEUE_SCRIPT 주석 참조).
        // 멱등하므로 중복 진입(addIfAbsent=false)이어도 그대로 호출한다.
        redis.opsForSet().add(QueueKeys.activeGoods(), String.valueOf(goodsId));
        return Boolean.TRUE.equals(added);
    }

    /**
     * 현재 대기 순번(1-based). "내 앞에 (반환값-1)명"이 남아 있다는 의미.
     *
     * @return 대기 중이면 1 이상의 순번, 대기 중이 아니면(입장 완료/이탈) 빈 값
     */
    public OptionalLong getPosition(Long goodsId, Long memberId) {
        Long rank = redis.opsForZSet().rank(QueueKeys.waitingQueue(goodsId), String.valueOf(memberId));
        return rank == null ? OptionalLong.empty() : OptionalLong.of(rank + 1);
    }

    /** 현재 대기 인원 수. */
    public long size(Long goodsId) {
        Long count = redis.opsForZSet().zCard(QueueKeys.waitingQueue(goodsId));
        return count == null ? 0L : count;
    }

    /**
     * 대기열 맨 앞에서 최대 {@code count} 명을 <b>원자적으로</b> 꺼낸다(선착순 소비, P-Q4).
     *
     * <p>{@link #DEQUEUE_SCRIPT} 로 {@code ZPOPMIN} + 빈 큐 정리(SREM/DEL)를 한 번에 수행한다.
     * 꺼낸 회원은 더 이상 대기열에 없으므로, 호출 측(Worker)이 곧바로 입장 토큰을 발급한다.
     *
     * @param count 이번에 꺼낼 최대 인원(throttle batch). 0 이하면 아무것도 하지 않는다.
     * @return 꺼낸 memberId 목록(도착 순서). 큐가 비어 있으면 빈 목록.
     */
    @SuppressWarnings("unchecked")
    public List<Long> dequeue(Long goodsId, long count) {
        if (count <= 0) {
            return List.of();
        }
        List<String> members = redis.execute(
            DEQUEUE_SCRIPT,
            List.of(QueueKeys.waitingQueue(goodsId), QueueKeys.activeGoods(), QueueKeys.sequence(goodsId)),
            String.valueOf(count), String.valueOf(goodsId));
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().map(Long::valueOf).toList();
    }

    /**
     * 대기자가 있는 활성 큐의 goodsId 집합(Worker 소비 대상 발견). 비어 있으면 빈 집합.
     */
    public Set<Long> activeGoods() {
        Set<String> ids = redis.opsForSet().members(QueueKeys.activeGoods());
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return ids.stream().map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }
}

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
 * 정해진 처리량만큼 앞에서 꺼내 입장시키는 소비({@link #dequeueAndAdmit})는 module-worker 의
 * Consumer 가 호출하며, 본 서비스는 그 <b>원자적 1회 연산</b>만 제공한다.
 *
 * <p><b>원자성 원칙(2026-07-02 리뷰 H2·M1)</b>: 멀티스텝 Redis 흐름(진입 = 시퀀스+ZADD+레지스트리,
 * 소비 = pop+토큰 발급+빈 큐 정리)은 전부 Lua 스크립트 <b>하나</b>로 묶는다. 부분 실패로 생기는
 * 중간 상태(고아 큐, 토큰 없는 이탈자)는 재시도로 복구할 수 없기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class QueueService {

    /**
     * 대기열 진입을 <b>단일 원자 연산</b>으로 수행하는 Lua 스크립트.
     *
     * <p>과거엔 INCR(시퀀스) → ZADD NX → SADD(활성 레지스트리) 세 호출이 분리되어 있어,
     * ZADD 성공 후 SADD 전에 연결이 끊기면 <b>대기자는 ZSET 에 있는데 레지스트리엔 없는 고아 큐</b>가
     * 생겼다(Worker 는 레지스트리로만 소비 대상을 발견 → 영영 소비 안 됨, 2026-07-02 리뷰 M1).
     * 한 스크립트로 묶어 전부 반영되거나 전부 반영되지 않게 한다. INCR 와 ZADD 가 불가분이 되면서,
     * 빈 큐 정리(DEQUEUE_ADMIT_SCRIPT 의 시퀀스 DEL)와 교차해 시퀀스가 되감기던 동점 편향도 함께 사라진다.
     *
     * <ul>
     *   <li>KEYS: [1]=대기열 ZSET, [2]=활성 레지스트리 Set, [3]=진입 시퀀스</li>
     *   <li>ARGV: [1]=진입 시각 millis(주입 Clock — 테스트 결정성), [2]=tie-break 배율(10^d),
     *       [3]=memberId, [4]=goodsId</li>
     *   <li>반환: 1=신규 진입, 0=이미 대기 중(기존 순번 유지, P-Q2)</li>
     * </ul>
     */
    private static final RedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>("""
        local scale = tonumber(ARGV[2])
        local seq = redis.call('INCR', KEYS[3])
        local score = tonumber(ARGV[1]) * scale + (seq % scale)
        local added = redis.call('ZADD', KEYS[1], 'NX', score, ARGV[3])
        redis.call('SADD', KEYS[2], ARGV[4])
        return added
        """, Long.class);

    /**
     * 대기열 소비 + <b>입장 토큰 발급</b>을 <b>단일 원자 연산</b>으로 수행하는 Lua 스크립트.
     *
     * <p>{@code ZPOPMIN} 으로 선착순 맨 앞 {@code count} 명을 꺼내고, <b>같은 스크립트 안에서</b>
     * 각자에게 입장 토큰({@code SET NX PX}, P-Q3 고정창)을 발급한다. 과거엔 pop 과 발급이 분리되어
     * 있어, pop 후 발급 도중 워커가 죽으면 <b>큐에서도 빠지고 토큰도 없는 회원이 영구 유실</b>됐다
     * (2026-07-02 리뷰 H2 — 입장 파이프라인의 유일한 at-most-once 구멍). Redis 스크립트는 전체가
     * 실행되거나 전혀 실행되지 않으므로 이 크래시 창이 사라진다.
     *
     * <p>큐가 비면 활성 레지스트리에서 빼고({@code SREM}) 시퀀스 카운터까지 정리한다({@code DEL}) —
     * 동시 enqueue(위 ENQUEUE_SCRIPT)와는 스크립트 단위로 직렬화되어 경합이 없다.
     *
     * <ul>
     *   <li>KEYS: [1]=대기열 ZSET, [2]=활성 레지스트리 Set, [3]=진입 시퀀스</li>
     *   <li>ARGV: [1]=최대 인원(count), [2]=goodsId, [3]=토큰 키 프리픽스({@code admission:{goodsId}:}),
     *       [4]=토큰 TTL millis</li>
     *   <li>반환: [1]=신규 발급 토큰 수, [2]=꺼낸 memberId 목록(도착 순서).
     *       발급 수 &lt; 목록 크기인 경우 = 유효 토큰 보유자의 재진입(SET NX 가 기존 TTL 보존)</li>
     * </ul>
     */
    private static final RedisScript<List> DEQUEUE_ADMIT_SCRIPT = new DefaultRedisScript<>("""
        local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
        local members = {}
        local issued = 0
        for i = 1, #popped, 2 do
            local member = popped[i]
            members[#members + 1] = member
            if redis.call('SET', ARGV[3] .. member, '1', 'NX', 'PX', ARGV[4]) then
                issued = issued + 1
            end
        end
        if redis.call('ZCARD', KEYS[1]) == 0 then
            redis.call('SREM', KEYS[2], ARGV[2])
            redis.call('DEL', KEYS[3])
        end
        return {issued, members}
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
        // millis × scale 은 2^53 미만(QueueProperties.validate 보장)이라 Lua double 로도 정확히 표현된다.
        Long added = redis.execute(
            ENQUEUE_SCRIPT,
            List.of(QueueKeys.waitingQueue(goodsId), QueueKeys.activeGoods(), QueueKeys.sequence(goodsId)),
            String.valueOf(clock.millis()),
            String.valueOf(properties.scoreScale()),
            String.valueOf(memberId),
            String.valueOf(goodsId));
        return added != null && added == 1L;
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
     * 대기열 맨 앞에서 최대 {@code count} 명을 꺼내 <b>같은 원자 연산 안에서 입장 토큰까지 발급</b>한다
     * (선착순 소비 P-Q4 + 토큰 발급 P-Q3).
     *
     * <p>{@link #DEQUEUE_ADMIT_SCRIPT} 가 {@code ZPOPMIN} + 토큰 {@code SET NX PX} + 빈 큐 정리를
     * 한 번에 수행하므로, "큐에서는 빠졌는데 토큰은 없는" 중간 상태가 존재하지 않는다(리뷰 H2).
     *
     * @param count 이번에 꺼낼 최대 인원(throttle batch). 0 이하면 아무것도 하지 않는다.
     * @return 꺼낸 회원 목록(도착 순서)과 신규 발급 토큰 수. 큐가 비어 있으면 {@link AdmitResult#EMPTY}.
     */
    @SuppressWarnings("unchecked")
    public AdmitResult dequeueAndAdmit(Long goodsId, long count) {
        if (count <= 0) {
            return AdmitResult.EMPTY;
        }
        List<Object> result = redis.execute(
            DEQUEUE_ADMIT_SCRIPT,
            List.of(QueueKeys.waitingQueue(goodsId), QueueKeys.activeGoods(), QueueKeys.sequence(goodsId)),
            String.valueOf(count),
            String.valueOf(goodsId),
            QueueKeys.admissionPrefix(goodsId),
            String.valueOf(properties.getAdmissionTtl().toMillis()));
        if (result == null || result.size() < 2) {
            return AdmitResult.EMPTY;
        }
        long issued = ((Number) result.get(0)).longValue();
        List<String> members = (List<String>) result.get(1);
        if (members == null || members.isEmpty()) {
            return AdmitResult.EMPTY;
        }
        return new AdmitResult(members.stream().map(Long::valueOf).toList(), issued);
    }

    /**
     * {@link #dequeueAndAdmit} 의 결과. {@code issued < members.size()} 인 경우는
     * 유효 토큰 보유자의 재진입(SET NX 가 기존 TTL 고정창을 보존, P-Q3)뿐이다.
     *
     * @param members 큐에서 꺼내 입장 처리된 memberId 목록(도착 순서)
     * @param issued  이번에 <b>새로</b> 발급된 토큰 수
     */
    public record AdmitResult(List<Long> members, long issued) {
        public static final AdmitResult EMPTY = new AdmitResult(List.of(), 0L);
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

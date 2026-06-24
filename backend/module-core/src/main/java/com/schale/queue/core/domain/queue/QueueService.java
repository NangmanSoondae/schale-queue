package com.schale.queue.core.domain.queue;

import java.time.Clock;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * 정해진 처리량만큼 앞에서 꺼내 입장시키는 소비(dequeue)는 module-worker 의
 * Consumer 책임이며 본 서비스 범위 밖이다(다음 슬라이스 ③).
 */
@Service
@RequiredArgsConstructor
public class QueueService {

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
}

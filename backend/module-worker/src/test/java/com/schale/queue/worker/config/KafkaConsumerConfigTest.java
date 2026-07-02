package com.schale.queue.worker.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * 컨슈머 실패 방어 설정 검증(H1). 핵심 계약 두 가지를 고정한다:
 * ① 최종 실패 레코드는 원본과 같은 파티션의 {@code <토픽>.DLT} 로 회수된다(유실 대신 보존).
 * ② 에러 핸들러 빈이 생성된다 — Boot 자동구성이 유일한 CommonErrorHandler 빈을 리스너 컨테이너에
 *    자동 적용하므로, 빈 존재 자체가 '기본 9회 재시도 후 스킵(유실)' 동작의 교체를 의미한다.
 */
class KafkaConsumerConfigTest {

    @Test
    @DisplayName("DLT 목적지는 원본 토픽 + .DLT, 같은 파티션이다")
    void dltDestination_appendsSuffix_keepsPartition() {
        ConsumerRecord<Object, Object> record =
            new ConsumerRecord<>("order.completed", 2, 42L, "key", "value");

        TopicPartition dest = KafkaConsumerConfig.dltDestination(record, new RuntimeException("boom"));

        assertThat(dest.topic()).isEqualTo("order.completed.DLT");
        assertThat(dest.partition()).isEqualTo(2);
    }

    @Test
    @DisplayName("에러 핸들러 빈이 DLT recoverer 와 함께 생성된다")
    void errorHandlerBean_created() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();

        DefaultErrorHandler handler =
            config.kafkaErrorHandler(new KafkaProperties(), new JacksonConfig().objectMapper());

        assertThat(handler).isNotNull();
    }
}

package com.schale.queue.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * 컨슈머 실패 방어 설정(ADR-007 보강, 2026-07-02 전체 리뷰 H1).
 *
 * <p>Spring Kafka 기본값은 두 가지 무유실 구멍을 남긴다:
 * <ol>
 *   <li><b>포이즌 필</b> — 역직렬화 불가 레코드가 {@code poll()} 안에서 터지면 같은 오프셋을 무한
 *       재폴링해 파티션이 영구 정지한다. → application.yml 의 {@code ErrorHandlingDeserializer} 가
 *       실패를 레코드로 감싸 이 클래스의 에러 핸들러로 넘긴다.</li>
 *   <li><b>재시도 소진 후 스킵</b> — 기본 핸들러는 0ms 간격 9회 재시도 뒤 오프셋을 커밋하고 레코드를
 *       버린다(로그만 남음). DB 순단 1초면 정산 이벤트가 조용히 유실된다. → 지수 백오프로 순단을
 *       흡수하고, 최종 실패는 버리는 대신 <b>DLT({@code <원본토픽>.DLT})로 발행해 보존</b>한다.</li>
 * </ol>
 *
 * <p>DLT 발행 템플릿은 실패 시점에 따라 값 타입이 달라 두 벌을 둔다:
 * 역직렬화 실패는 원본 {@code byte[]} 그대로(원인 조사용 무손실 보존), 리스너 처리 실패는 이미
 * 역직렬화된 이벤트 객체를 JSON 으로 재직렬화해 보낸다(릴레이의 raw JSON 규약과 동일 형태).
 * 예외 클래스·메시지·원본 토픽/오프셋은 recoverer 가 Kafka 헤더로 자동 첨부한다.
 *
 * <p>재시도가 백오프 동안 해당 파티션 소비를 막지만(순서 보존 대가) 최대 누적 ~2분이라 수용 가능하고,
 * 모든 컨슈머가 {@code processed_event} 멱등 가드를 갖춰 재시도 중복도 안전하다.
 * Boot 자동구성은 유일한 {@code CommonErrorHandler} 빈을 리스너 컨테이너 팩토리에 자동 적용한다.
 */
@Configuration
public class KafkaConsumerConfig {

    /** DLT 토픽 접미사. {@code order.completed} → {@code order.completed.DLT} */
    static final String DLT_SUFFIX = ".DLT";

    /** 재시도 정책: 1s 시작, 2배 증가, 상한 30s, 최대 7회 재시도(누적 ~2분) 후 DLT 로 회수. */
    private static final long RETRY_INITIAL_MS = 1_000L;
    private static final double RETRY_MULTIPLIER = 2.0;
    private static final long RETRY_MAX_INTERVAL_MS = 30_000L;
    private static final int RETRY_MAX_RETRIES = 7;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
        Map<String, Object> producerProps = kafkaProperties.buildProducerProperties(null);

        // 역직렬화 실패용: ErrorHandlingDeserializer 가 보존한 원본 바이트를 그대로 DLT 에 싣는다.
        Map<String, Object> bytesProps = new HashMap<>(producerProps);
        bytesProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        KafkaOperations<String, byte[]> bytesTemplate =
            new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(bytesProps));

        // 리스너 처리 실패용: 역직렬화된 이벤트 객체를 JSON 문자열로 재직렬화(타입 헤더 없이 — 릴레이 규약).
        JsonSerializer<Object> jsonSerializer = new JsonSerializer<>(objectMapper);
        jsonSerializer.setAddTypeInfo(false);
        KafkaOperations<String, Object> jsonTemplate = new KafkaTemplate<>(
            new DefaultKafkaProducerFactory<>(producerProps, new StringSerializer(), jsonSerializer));

        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        templates.put(Object.class, jsonTemplate);
        DeadLetterPublishingRecoverer recoverer =
            new DeadLetterPublishingRecoverer(templates, KafkaConsumerConfig::dltDestination);

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(RETRY_MAX_RETRIES);
        backOff.setInitialInterval(RETRY_INITIAL_MS);
        backOff.setMultiplier(RETRY_MULTIPLIER);
        backOff.setMaxInterval(RETRY_MAX_INTERVAL_MS);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    /** 원본과 같은 파티션의 {@code <토픽>.DLT} 로 보낸다(파티션 보존 — 원본 순서 추적 용이). */
    static TopicPartition dltDestination(ConsumerRecord<?, ?> record, Exception ex) {
        return new TopicPartition(record.topic() + DLT_SUFFIX, record.partition());
    }
}

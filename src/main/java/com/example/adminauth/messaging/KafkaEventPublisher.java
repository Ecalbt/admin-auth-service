package com.example.adminauth.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publisher đẩy event lên Kafka thông qua KafkaTemplate, ghi nhận metrics qua Micrometer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventPublisher {

    private final KafkaTemplate<String, SpecificRecord> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public void publishSync(String topic, String key, SpecificRecord record, long timeoutSeconds) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CompletableFuture<SendResult<String, SpecificRecord>> future = kafkaTemplate.send(topic, key, record);
            SendResult<String, SpecificRecord> result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            meterRegistry.counter("outbox.published", "topic", topic).increment();
            sample.stop(meterRegistry.timer("outbox.publish.latency", "topic", topic));
            log.info("KafkaEventPublisher: published event to topic={}, key={}, partition={}, offset={}",
                    topic, key, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (Exception e) {
            meterRegistry.counter("outbox.failed", "topic", topic).increment();
            log.error("KafkaEventPublisher: failed to publish to topic={}, key={}: {}", topic, key, e.getMessage());
            throw e;
        }
    }
}

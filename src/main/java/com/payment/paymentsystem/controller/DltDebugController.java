package com.payment.paymentsystem.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.*;

/**
 * Internal debug endpoint for inspecting Dead Letter Topic contents.
 * Hidden from Swagger; not for client use. Spins up a one-shot consumer,
 * polls everything in the DLT, returns it as JSON.
 */
@Hidden
@RestController
@RequestMapping("/api/internal/dlt")
public class DltDebugController {

    private final String bootstrapServers;
    private final String dltTopic;

    public DltDebugController(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.kafka.topics.payment-requested}") String mainTopic) {
        this.bootstrapServers = bootstrapServers;
        this.dltTopic = mainTopic + "-dlt";   // lowercase with hyphen
    }

    @GetMapping("/messages")
    public List<Map<String, Object>> readDlt() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", "dlt-debug-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", "false");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(dltTopic));

            // First poll: triggers partition assignment, often returns nothing
            consumer.poll(Duration.ofSeconds(5));

            // Now seek to the beginning of every assigned partition
            consumer.seekToBeginning(consumer.assignment());

            List<Map<String, Object>> result = new ArrayList<>();
            int emptyPolls = 0;
            while (emptyPolls < 2) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> record : records.records(dltTopic)) {
                    result.add(recordToMap(record));
                }
            }
            return result;
        }
    }

    private Map<String, Object> recordToMap(ConsumerRecord<String, String> record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("partition", record.partition());
        map.put("offset", record.offset());
        map.put("timestamp", record.timestamp());
        map.put("key", record.key());
        map.put("value", record.value());
        Map<String, String> headers = new LinkedHashMap<>();
        record.headers().forEach(h ->
                headers.put(h.key(), new String(h.value())));
        map.put("headers", headers);
        return map;
    }
}

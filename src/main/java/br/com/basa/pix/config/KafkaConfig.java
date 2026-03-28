package br.com.basa.pix.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${pix.kafka.topic}")
    private String pixTransferTopic;

    @Bean
    public NewTopic pixTransferEventsTopic() {
        return TopicBuilder.name(pixTransferTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}

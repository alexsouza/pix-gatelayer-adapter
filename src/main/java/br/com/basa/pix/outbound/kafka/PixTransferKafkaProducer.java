package br.com.basa.pix.outbound.kafka;

import br.com.basa.pix.domain.model.PixTransfer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class PixTransferKafkaProducer {

    private final KafkaTemplate<String, PixTransfer> kafkaTemplate;

    @Value("${pix.kafka.topic}")
    private String topic;

    public void send(PixTransfer transfer) {
        var message = MessageBuilder
                .withPayload(transfer)
                .setHeader(KafkaHeaders.TOPIC, topic)
                .setHeader(KafkaHeaders.KEY, transfer.getTransactionId())
                .setHeader("correlationId", transfer.getCorrelationId().getBytes(StandardCharsets.UTF_8))
                .build();

        kafkaTemplate.send(message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Falha ao publicar evento Pix [transactionId={}]: {}", transfer.getTransactionId(), ex.getMessage());
                    } else {
                        log.info("Evento Pix publicado [transactionId={}, offset={}]",
                                transfer.getTransactionId(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}

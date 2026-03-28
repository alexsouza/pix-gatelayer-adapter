package br.com.basa.pix.inbound.kafka;

import br.com.basa.pix.application.service.PixTransferService;
import br.com.basa.pix.domain.model.PixTransfer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class PixTransferKafkaListener {

    private final PixTransferService pixTransferService;

    @KafkaListener(topics = "${pix.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(ConsumerRecord<String, PixTransfer> record) {
        PixTransfer transfer = record.value();

        Header correlationHeader = record.headers().lastHeader("correlationId");
        if (correlationHeader != null) {
            transfer.setCorrelationId(new String(correlationHeader.value(), StandardCharsets.UTF_8));
        }

        log.info("Evento Pix recebido do Kafka [transactionId={}, correlationId={}]",
                transfer.getTransactionId(), transfer.getCorrelationId());

        pixTransferService.processTransfer(transfer);
    }
}

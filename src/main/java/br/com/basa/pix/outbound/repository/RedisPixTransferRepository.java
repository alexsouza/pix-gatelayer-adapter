package br.com.basa.pix.outbound.repository;

import br.com.basa.pix.domain.model.PixTransfer;
import br.com.basa.pix.domain.model.TransferStatus;
import br.com.basa.pix.domain.repository.PixTransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisPixTransferRepository implements PixTransferRepository {

    private static final String KEY_PREFIX = "pix:transfer:";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, PixTransfer> redisTemplate;

    @Override
    public boolean saveIfAbsent(PixTransfer transfer) {
        String key = KEY_PREFIX + transfer.getIdempotencyKey();
        Boolean inserted = redisTemplate.opsForValue().setIfAbsent(key, transfer, TTL);
        if (Boolean.FALSE.equals(inserted)) {
            log.warn("Requisição duplicada detectada [idempotencyKey={}]", transfer.getIdempotencyKey());
            return false;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + transfer.getTransactionId(), transfer, TTL);
        return true;
    }

    @Override
    public void updateStatus(String transactionId, TransferStatus status) {
        String key = KEY_PREFIX + transactionId;
        PixTransfer transfer = redisTemplate.opsForValue().get(key);
        if (transfer != null) {
            transfer.setStatus(status);
            transfer.setUpdatedAt(java.time.Instant.now());
            redisTemplate.opsForValue().set(key, transfer, TTL);
        }
    }

    @Override
    public Optional<PixTransfer> findById(String transactionId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + transactionId));
    }
}

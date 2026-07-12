package finance.finflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import finance.finflow.module.IdempotencyRecord;
import finance.finflow.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String PREFIX = "idempotency:";
    private static final Duration TTL   = Duration.ofHours(24);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public <T> Optional<T> check(String key, String requestHash, Class<T> responseType) {
        String redisKey = PREFIX + key;

        // Fast path: Redis
        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            CachedRecord record = read(cached, CachedRecord.class);
            if (!record.requestHash().equals(requestHash)) {
                throw new IllegalStateException("Idempotency key reused with a different request: " + key);
            }
            return Optional.of(read(record.response(), responseType));
        }

        // Fallback: DB
        return idempotencyRecordRepository.findByIdempotencyKey(key)
                .map(existing -> {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        throw new IllegalStateException("Idempotency key reused with a different request: " + key);
                    }
                    // Backfill Redis so next hit is served from cache
                    redisTemplate.opsForValue().set(
                            redisKey,
                            serialize(new CachedRecord(existing.getRequestHash(), existing.getResponse())),
                            TTL
                    );
                    return read(existing.getResponse(), responseType);
                });
    }

    @SneakyThrows
    public void save(String key, String requestHash, UUID entityId, Object response) {
        String responseJson = serialize(response);

        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(key);
        record.setRequestHash(requestHash);
        record.setTransactionId(entityId);
        record.setResponse(responseJson);
        idempotencyRecordRepository.save(record);

        redisTemplate.opsForValue().set(
                PREFIX + key,
                serialize(new CachedRecord(requestHash, responseJson)),
                TTL
        );
    }

    @SneakyThrows
    private String serialize(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    @SneakyThrows
    private <T> T read(String json, Class<T> type) {
        return objectMapper.readValue(json, type);
    }

    public record CachedRecord(String requestHash, String response) {}
}

package ar.edu.utn.frc.tup.p4.usersservice.auth.store.impl;

import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisTokenStore implements TokenStore {

    private static final String SESSION_PREFIX = "session:";
    private static final String REFRESH_PREFIX = "refresh:";
    private static final String REVOKED_FAMILY_PREFIX = "refresh:familia-revocada:";
    private static final String LOGIN_FAILURE_PREFIX = "ratelimit:login:";
    private static final String RATE_LIMIT_PREFIX = "ratelimit:";

    /**
     * Atomically INCR and set TTL on the first failure so a crash between the
     * two commands cannot leave a counter without expiry.
     */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            """
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            return n
            """,
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisTokenStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public void guardarSesion(UUID userId, String sid) {
        redis.opsForValue().set(SESSION_PREFIX + userId, sid);
    }

    @Override
    public Optional<String> sidDe(UUID userId) {
        return Optional.ofNullable(redis.opsForValue().get(SESSION_PREFIX + userId));
    }

    @Override
    public void borrarSesion(UUID userId) {
        redis.delete(SESSION_PREFIX + userId);
    }

    @Override
    public void guardarRefresh(String jti, RefreshData data, Duration ttl) {
        redis.opsForValue().set(REFRESH_PREFIX + jti, write(data), ttl);
    }

    @Override
    public Optional<RefreshData> refresh(String jti) {
        return Optional.ofNullable(redis.opsForValue().get(REFRESH_PREFIX + jti))
                .map(this::read);
    }

    @Override
    public void revocarRefresh(String jti) {
        redis.delete(REFRESH_PREFIX + jti);
    }

    @Override
    public void revocarFamilia(String familyId) {
        redis.opsForValue().set(
                REVOKED_FAMILY_PREFIX + familyId,
                "1",
                Duration.ofDays(7));
    }

    @Override
    public boolean familiaRevocada(String familyId) {
        return Boolean.TRUE.equals(redis.hasKey(REVOKED_FAMILY_PREFIX + familyId));
    }

    @Override
    public int incrementarFallos(String key, Duration ventana) {
        Long failures = redis.execute(
                INCR_WITH_TTL,
                List.of(LOGIN_FAILURE_PREFIX + key),
                String.valueOf(ventana.toSeconds()));
        return failures == null ? 0 : failures.intValue();
    }

    @Override
    public void limpiarFallos(String key) {
        redis.delete(LOGIN_FAILURE_PREFIX + key);
    }

    @Override
    public int incrementarUso(String bucket, String key, Duration ventana) {
        Long usos = redis.execute(
                INCR_WITH_TTL,
                List.of(RATE_LIMIT_PREFIX + bucket + ":" + key),
                String.valueOf(ventana.toSeconds()));
        return usos == null ? 0 : usos.intValue();
    }

    private String write(RefreshData data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private RefreshData read(String json) {
        try {
            return mapper.readValue(json, RefreshData.class);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

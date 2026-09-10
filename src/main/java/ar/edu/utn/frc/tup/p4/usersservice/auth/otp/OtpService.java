package ar.edu.utn.frc.tup.p4.usersservice.auth.otp;

import ar.edu.utn.frc.tup.p4.usersservice.config.OtpProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

/**
 * DEC-33 - shared OTP engine for two-factor authentication and email validation.
 */
@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Atomically compare-and-delete the OTP (and its attempts key) so a single
     * code cannot be accepted twice under concurrent verification.
     */
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('DEL', KEYS[1], KEYS[2])
              return 1
            end
            return 0
            """,
            Long.class);

    /**
     * Atomically INCR and set TTL on the first attempt so a crash between the
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

    private static final long ATTEMPTS_TTL_SECONDS = Duration.ofHours(1).toSeconds();

    private final StringRedisTemplate redis;
    private final OtpProperties properties;

    public OtpService(StringRedisTemplate redis, OtpProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public String generar(String key, Duration ttl) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(key, code, ttl);
        redis.delete(attemptsKey(key));
        return code;
    }

    /**
     * Returns the same error for wrong, expired, and unknown codes.
     */
    public void verificar(String key, String code) {
        Long consumed = redis.execute(CONSUME, List.of(key, attemptsKey(key)), code);
        if (consumed != null && consumed == 1L) {
            return;
        }

        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            throw ApiException.invalidCode();
        }

        Long attempts = redis.execute(
                INCR_WITH_TTL,
                List.of(attemptsKey(key)),
                String.valueOf(ATTEMPTS_TTL_SECONDS));
        if (attempts != null && attempts >= properties.maxIntentos()) {
            redis.delete(key);
            redis.delete(attemptsKey(key));
        }
        throw ApiException.invalidCode();
    }

    private String attemptsKey(String key) {
        return key + ":intentos";
    }
}

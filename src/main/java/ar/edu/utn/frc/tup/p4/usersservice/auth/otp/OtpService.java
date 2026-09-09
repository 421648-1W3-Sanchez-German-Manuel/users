package ar.edu.utn.frc.tup.p4.usersservice.auth.otp;

import ar.edu.utn.frc.tup.p4.usersservice.config.OtpProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * DEC-33 - shared OTP engine for two-factor authentication and email validation.
 */
@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

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
        String expectedCode = redis.opsForValue().get(key);
        if (expectedCode == null) {
            throw ApiException.invalidCode();
        }

        if (!expectedCode.equals(code)) {
            Long attempts = redis.opsForValue().increment(attemptsKey(key));
            if (attempts != null && attempts == 1L) {
                redis.expire(attemptsKey(key), Duration.ofHours(1));
            }
            if (attempts != null && attempts >= properties.maxIntentos()) {
                redis.delete(key);
                redis.delete(attemptsKey(key));
            }
            throw ApiException.invalidCode();
        }

        redis.delete(key);
        redis.delete(attemptsKey(key));
    }

    private String attemptsKey(String key) {
        return key + ":intentos";
    }
}

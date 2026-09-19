package ar.edu.utn.frc.tup.p4.usersservice.auth.store.impl;

import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor.SecondFactorProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The boundary lives in {@code auth/}, so delegating to {@code TokenStore} and
 * {@code SecondFactorProvider} from here crosses nothing: both are
 * {@code auth/} talking to {@code auth/}. That is the whole point — it keeps
 * those two out of {@code users/}, where SPEC §5.3 rule U4 forbids them.
 */
@Component
public class RedisEphemeralTokenService implements EphemeralTokenService {

    private final StringRedisTemplate redis;
    private final TokenStore tokens;
    private final SecondFactorProvider secondFactor;

    public RedisEphemeralTokenService(StringRedisTemplate redis, TokenStore tokens,
                                      SecondFactorProvider secondFactor) {
        this.redis = redis;
        this.tokens = tokens;
        this.secondFactor = secondFactor;
    }

    @Override
    public void save(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    @Override
    public Optional<String> consume(String key) {
        return Optional.ofNullable(redis.opsForValue().getAndDelete(key));
    }

    @Override
    public Optional<String> find(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    @Override
    public void verifySecondFactor(UUID userId, String code) {
        secondFactor.verify(userId, code);
    }

    @Override
    public void deleteSession(UUID userId) {
        tokens.deleteSession(userId);
    }
}

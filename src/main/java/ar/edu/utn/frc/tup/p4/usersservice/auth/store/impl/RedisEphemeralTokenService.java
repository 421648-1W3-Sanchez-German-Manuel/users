package ar.edu.utn.frc.tup.p4.usersservice.auth.store.impl;

import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RedisEphemeralTokenService implements EphemeralTokenService {

    private final StringRedisTemplate redis;

    public RedisEphemeralTokenService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void guardar(String key, String valor, Duration ttl) {
        redis.opsForValue().set(key, valor, ttl);
    }

    @Override
    public Optional<String> consumir(String key) {
        return Optional.ofNullable(redis.opsForValue().getAndDelete(key));
    }

    @Override
    public Optional<String> verificar(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }
}

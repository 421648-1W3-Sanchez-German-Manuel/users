package ar.edu.utn.frc.tup.p4.usersservice.users.providers;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class GitProviderClientRegistry {

    private final Map<GitProvider, GitProviderClient> byProvider = new EnumMap<>(GitProvider.class);

    public GitProviderClientRegistry(List<GitProviderClient> clients) {
        for (GitProviderClient client : clients) {
            byProvider.put(client.provider(), client);
        }
    }

    public GitProviderClient require(GitProvider provider) {
        GitProviderClient client = byProvider.get(provider);
        if (client == null) {
            throw ApiException.providerNotSupported();
        }
        return client;
    }
}

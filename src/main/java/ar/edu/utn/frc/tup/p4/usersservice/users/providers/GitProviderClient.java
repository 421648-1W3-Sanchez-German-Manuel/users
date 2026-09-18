package ar.edu.utn.frc.tup.p4.usersservice.users.providers;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;

import java.net.URI;

/** A git hosting provider, as seen from the domain. One implementation per provider. */
public interface GitProviderClient {

    GitProvider provider();

    /** URL the browser is sent to so the provider can ask for consent. */
    URI authorizationUrl(String state);

    /** Exchanges the authorization code for the account owner's identity. DEC-GL-02. */
    GitProviderIdentity exchange(String authorizationCode);
}

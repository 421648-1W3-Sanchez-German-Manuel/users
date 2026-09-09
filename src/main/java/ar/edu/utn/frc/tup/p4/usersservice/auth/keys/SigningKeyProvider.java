package ar.edu.utn.frc.tup.p4.usersservice.auth.keys;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

/** Provides mounted signing keys without defining how they are stored. */
public interface SigningKeyProvider {

    RSAKey claveDeFirma();

    JWKSet jwksPublico();
}

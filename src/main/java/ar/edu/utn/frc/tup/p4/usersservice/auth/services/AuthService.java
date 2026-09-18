package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.auth.tokens.TokenClaims;
import ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor.SecondFactorProvider;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import ar.edu.utn.frc.tup.p4.usersservice.config.OtpProperties;
import ar.edu.utn.frc.tup.p4.usersservice.config.RateLimitProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final CredentialService credentials;     // The gateway to users/.
    private final SecondFactorProvider secondFactor;
    private final TokenService tokens;
    private final TokenStore store;
    private final EphemeralTokenService ephemeralTokens;
    private final JwtProperties jwt;
    private final RateLimitProperties rate;
    private final OtpProperties otpProps;

    public AuthService(CredentialService credentials, SecondFactorProvider secondFactor,
                       TokenService tokens, TokenStore store, EphemeralTokenService ephemeralTokens,
                       JwtProperties jwt, RateLimitProperties rate,
                       OtpProperties otpProps) {
        this.credentials = credentials; this.secondFactor = secondFactor;
        this.tokens = tokens; this.store = store; this.ephemeralTokens = ephemeralTokens;
        this.jwt = jwt; this.rate = rate; this.otpProps = otpProps;
    }

    /** Phase 1: validates credentials and triggers 2FA. Does NOT issue tokens. */
    @Transactional
    public LoginResponse login(String email, String password) {
        String key = email.toLowerCase(Locale.ROOT);

        // DEC-42: the limit is checked BEFORE spending a BCrypt (~100 ms).
        if (store.incrementFailures(key, rate.loginWindow()) > rate.loginMaxFailures()) {
            throw ApiException.tooManyAttempts(rate.loginWindow());
        }

        var verifiedCredentials = credentials.verifyCredentials(key, password);
        if (verifiedCredentials == null) throw ApiException.invalidCredentials();

        store.clearFailures(key);   // A successful attempt does not consume the budget.

        // Limit ISSUED challenges. A correct password is no longer enough to send
        // unlimited emails: someone who stole it could flood the account owner's
        // inbox, even though that owner is precisely who 2FA must protect.
        // This budget is separate from login failures. If they shared a key, a
        // successful login would clear it and make the limit ineffective.
        if (store.incrementUsage("2fa", key, rate.twoFactorWindow()) > rate.twoFactorMaxChallenges()) {
            throw ApiException.tooManyAttempts(rate.twoFactorWindow());
        }

        String challengeId = UUID.randomUUID().toString();
        // The challenge and code must have the SAME lifetime. With a hardcoded
        // five-minute value, raising users.otp.two-factor-ttl to PT10M broke every
        // login between minutes 5 and 10: live code, expired challenge, invalid-code.
        // It was a configuration-only change with no compiler or test warning.
        ephemeralTokens.save("desafio:" + challengeId, verifiedCredentials.userId().toString(),
                otpProps.twoFactorTtl());
        secondFactor.generateChallenge(verifiedCredentials.userId(), verifiedCredentials.email(),
                verifiedCredentials.firstNames());

        return new LoginResponse(challengeId, "Te enviamos un code por email.");
    }

    /** Phase 2: verifies the code and only then issues tokens. */
    @Transactional
    public TokenResponse verifyTwoFactor(String challengeId, String code) {
        UUID userId = ephemeralTokens.find("desafio:" + challengeId)
                .map(UUID::fromString)
                .orElseThrow(ApiException::invalidCode);

        secondFactor.verify(userId, code);
        ephemeralTokens.consume("desafio:" + challengeId);

        return issueTokenPair(userId);
    }

    /**
     * DEC-22 - the post-2FA login is the ONLY operation that writes
     * session:{userId}. Refresh does not modify it.
     */
    @Transactional
    public TokenResponse issueTokenPair(UUID userId) {
        String sid = UUID.randomUUID().toString();
        store.saveSession(userId, sid);
        return issueWithSessionId(userId, sid, UUID.randomUUID().toString());
    }

    TokenResponse issueWithSessionId(UUID userId, String sid, String familyId) {
        var tokenData = credentials.tokenData(userId);   // See Task 14, Step 3.

        TokenClaims claims = TokenClaims.forPerson(userId, tokenData.roles(), sid,
                tokenData.accountStatus(), tokenData.mustChangePassword(), tokenData.firstLogin()).build();

        String refreshJti = UUID.randomUUID().toString();
        store.saveRefresh(refreshJti,
                new TokenStore.RefreshData(userId, sid, familyId), jwt.refreshTtl());

        return new TokenResponse(tokens.signPersonToken(claims), refreshJti, jwt.accessTtl().toSeconds());
    }

    /**
     * DEC-22 - four steps, and step 3 is the one this spec adds over what
     * the flow manifest section 10 says ("checking here or letting it fail at the gateway
     * are equivalent"). They are NOT: the refresh lives 7 DAYS. Without the check,
     * a superseded device retains a long-lived, stealable credential tied to a
     * session that no longer exists.
     */
    @Transactional
    public TokenResponse refresh(String refreshJti) {
        // Reuse detection: a rotated token coming back is a theft signal. The
        // whole family dies with it.
        //
        // The prefix must NOT start with "refresh:": that namespace belongs to
        // RedisTokenStore (REFRESH_PREFIX and REVOKED_FAMILY_PREFIX). With
        // "refresh:rotado:", sending refreshToken="rotado:<jti>" made
        // store.refresh() read this same key, whose value is a bare familyId
        // rather than RefreshData JSON, resulting in 500 instead of 401.
        var rotated = ephemeralTokens.find(rotatedKey(refreshJti));
        if (rotated.isPresent()) {
            store.revokeFamily(rotated.get());
            throw ApiException.sessionClosed();
        }

        // The exits of this method return a SESSION type, not invalid-credentials:
        // nobody mistyped a password, the session stopped existing. The frontend
        // branches on type, and with invalid-credentials it would show "wrong
        // username or password" in a flow where neither was requested.
        var data = store.refresh(refreshJti).orElseThrow(ApiException::sessionClosed);

        // 1-2. Revoked family means a previous theft signal.
        if (store.isFamilyRevoked(data.familyId())) {
            throw ApiException.sessionClosed();
        }

        // 3. Is the session still the current one? If not, there was a newer login:
        // that case has its own type, which is the only message useful to the
        // person ("you signed in on another device").
        String currentSessionId = store.findSessionId(data.userId()).orElse(null);
        if (currentSessionId == null || !currentSessionId.equals(data.sid())) {
            store.revokeFamily(data.familyId());
            throw currentSessionId == null ? ApiException.sessionClosed() : ApiException.sessionSuperseded();
        }

        // 4. Rotate the REFRESH (not the sid). Reuse detection: the old one dies,
        // and its key marks the family for the rest of the refresh life.
        store.revokeRefresh(refreshJti);
        ephemeralTokens.save(rotatedKey(refreshJti), data.familyId(), jwt.refreshTtl());
        return issueWithSessionId(data.userId(), data.sid(), data.familyId());
    }

    private String rotatedKey(String jti) { return "rotado:refresh:" + jti; }

    /**
     * DEC-02 + DEC-22: one of only two deletions of session:{userId}.
     *
     * The refresh token supplied in the body must BELONG TO THE CALLER. Without
     * that filter, anyone who knows another user's jti can terminate the entire
     * token family from their own session: an on-demand logout of another user.
     */
    @Transactional
    public void logout(UUID userId, String refreshJti) {
        if (refreshJti != null) {
            store.refresh(refreshJti)
                    .filter(d -> d.userId().equals(userId))
                    .ifPresent(d -> {
                        store.revokeFamily(d.familyId());
                        store.revokeRefresh(refreshJti);
                    });
        }
        store.deleteSession(userId);
    }
}

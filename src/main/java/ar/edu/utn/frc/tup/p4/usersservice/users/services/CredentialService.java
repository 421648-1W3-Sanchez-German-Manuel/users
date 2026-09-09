package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;

import java.util.List;
import java.util.UUID;

/**
 * THE DOOR between modules. auth/ consumes it through a direct Java call,
 * NEVER over HTTP: there is no network hop between auth/ and users/.
 * The password hash never leaves this call.
 */
public interface CredentialService {

    record VerifiedCredentials(UUID userId, List<Role> roles, AccountStatus accountStatus,
                                   boolean mustChangePassword, String email, String firstNames) { }

    /** null if the user does not exist OR the password is wrong - without telling them apart. */
    VerifiedCredentials verifyCredentials(String email, String plainPassword);

    /**
     * DEC-23 - the est/pwd/onb claims come from the row, not from another token.
     *  AuthService consumes it when issuing and when refreshing (tasks 13 and 14).
     */
    record TokenData(List<Role> roles, AccountStatus accountStatus,
                      boolean mustChangePassword, boolean firstLogin) { }

    TokenData tokenData(UUID userId);

    /** null if it does not exist. The constant response is built by PasswordService (task 15). */
    record ResetData(UUID userId, String email, String firstNames) { }

    ResetData findForPasswordReset(String email);

    void updatePassword(UUID userId, String newPlainPassword);

    boolean verifyPasswordOf(UUID userId, String plainPassword);
}
package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** DEC-38 - length, not complexity. */
public final class PasswordPolicy {

    public static final int MIN_CHARACTERS = 12;
    /** BCrypt truncates at 72 BYTES. This is not a cosmetic limit. */
    public static final int MAX_BYTES = 72;

    private static final Set<String> COMMON = loadCommonPasswords();

    private PasswordPolicy() { }

    public static void validate(String plain) {
        if (plain == null || plain.length() < MIN_CHARACTERS) {
            throw ApiException.validation(
                    "The password must be at least " + MIN_CHARACTERS + " characters long.");
        }
        if (plain.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.validation(
                    "The password cannot exceed " + MAX_BYTES + " bytes. "
                    + "Accented characters take more than one byte.");
        }
        if (COMMON.contains(plain.toLowerCase(Locale.ROOT))) {
            throw ApiException.validation("That password is too common. Choose another one.");
        }
    }

    private static Set<String> loadCommonPasswords() {
        var in = PasswordPolicy.class.getResourceAsStream("/security/common-passwords.txt");
        if (in == null) return Set.of();
        try (var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return r.lines().map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        } catch (Exception e) {
            throw new IllegalStateException("Could not load the common-password list", e);
        }
    }
}
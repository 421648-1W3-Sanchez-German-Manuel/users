package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

/**
 * DEC-45c - registry of every supported email and its event metadata.
 */
public enum EmailType {

    TWO_FACTOR_CODE("code-2fa.html", "email.2fa.subject", "EMAIL_2FA"),
    ACCOUNT_ACTIVATION(
            "account-activation.html",
            "email.activation.subject",
            "EMAIL_ACTIVACION_CUENTA"),
    RESET_PASSWORD(
            "reset-password.html",
            "email.reset.subject",
            "EMAIL_RESET_PASSWORD"),
    REQUEST_PENDING(
            "whitelist-request-pending.html",
            "email.request.subject",
            "EMAIL_SOLICITUD_PENDIENTE"),
    WHITELISTING_RESOLVED(
            "whitelist-request-resolved.html",
            "email.whitelisting.subject",
            "EMAIL_HABILITACION_RESUELTA"),
    BREAKGLASS_ALERT(
            "breakglass-alert.html",
            "email.breakglass.subject",
            "EMAIL_ALERTA_BREAKGLASS"),
    WHITELIST_SUBMISSION(
            "whitelist-submission.html",
            "email.wl.request.subject",
            "EMAIL_WHITELIST_SOLICITUD"),
    WHITELIST_DECISION(
            "whitelist-decision.html",
            "email.wl.resolved.subject",
            "EMAIL_WHITELIST_RESUELTA");

    private final String template;
    private final String subjectKey;
    private final String eventType;

    EmailType(String template, String subjectKey, String eventType) {
        this.template = template;
        this.subjectKey = subjectKey;
        this.eventType = eventType;
    }

    public String template() {
        return template;
    }

    public String subjectKey() {
        return subjectKey;
    }

    public String eventType() {
        return eventType;
    }
}

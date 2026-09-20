package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

/**
 * DEC-45c - registry of every supported email and its event metadata.
 */
public enum EmailType {

    TWO_FACTOR_CODE(
            "code-2fa.html",
            "email.2fa.subject",
            "TWO-FACTOR-EMAIL-PREPARED"),
    ACCOUNT_ACTIVATION(
            "account-activation.html",
            "email.activation.subject",
            "ACCOUNT-ACTIVATION-EMAIL-PREPARED"),
    RESET_PASSWORD(
            "reset-password.html",
            "email.reset.subject",
            "PASSWORD-RESET-EMAIL-PREPARED"),
    REQUEST_PENDING(
            "whitelist-request-pending.html",
            "email.request.subject",
            "REQUEST-PENDING-EMAIL-PREPARED"),
    WHITELISTING_RESOLVED(
            "whitelist-request-resolved.html",
            "email.whitelisting.subject",
            "ENABLING-RESOLVED-EMAIL-PREPARED"),
    BREAKGLASS_ALERT(
            "breakglass-alert.html",
            "email.breakglass.subject",
            "BREAKGLASS-ALERT-EMAIL-PREPARED"),
    WHITELIST_SUBMISSION(
            "whitelist-submission.html",
            "email.wl.request.subject",
            "WHITELIST-SUBMISSION-EMAIL-PREPARED"),
    WHITELIST_DECISION(
            "whitelist-decision.html",
            "email.wl.resolved.subject",
            "WHITELIST-DECISION-EMAIL-PREPARED");

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

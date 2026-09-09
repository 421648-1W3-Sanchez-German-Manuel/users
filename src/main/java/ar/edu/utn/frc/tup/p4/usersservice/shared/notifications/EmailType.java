package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

/**
 * DEC-45c - registry of every supported email and its event metadata.
 */
public enum EmailType {

    CODIGO_2FA("code-2fa.html", "email.2fa.asunto", "EMAIL_2FA"),
    ACTIVACION_CUENTA(
            "account-activation.html",
            "email.activacion.asunto",
            "EMAIL_ACTIVACION_CUENTA"),
    RESET_PASSWORD(
            "reset-password.html",
            "email.reset.asunto",
            "EMAIL_RESET_PASSWORD"),
    SOLICITUD_PENDIENTE(
            "whitelist-request-pending.html",
            "email.request.asunto",
            "EMAIL_SOLICITUD_PENDIENTE"),
    HABILITACION_RESUELTA(
            "whitelist-request-resolved.html",
            "email.habilitacion.asunto",
            "EMAIL_HABILITACION_RESUELTA"),
    ALERTA_BREAKGLASS(
            "breakglass-alert.html",
            "email.breakglass.asunto",
            "EMAIL_ALERTA_BREAKGLASS"),
    WHITELIST_SOLICITUD(
            "whitelist-submission.html",
            "email.wl.request.asunto",
            "EMAIL_WHITELIST_SOLICITUD"),
    WHITELIST_RESUELTA(
            "whitelist-decision.html",
            "email.wl.resuelta.asunto",
            "EMAIL_WHITELIST_RESUELTA");

    private final String plantilla;
    private final String claveAsunto;
    private final String eventType;

    EmailType(String plantilla, String claveAsunto, String eventType) {
        this.plantilla = plantilla;
        this.claveAsunto = claveAsunto;
        this.eventType = eventType;
    }

    public String plantilla() {
        return plantilla;
    }

    public String claveAsunto() {
        return claveAsunto;
    }

    public String eventType() {
        return eventType;
    }
}

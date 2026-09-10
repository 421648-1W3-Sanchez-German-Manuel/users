package ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor;

import ar.edu.utn.frc.tup.p4.usersservice.auth.otp.OtpService;
import ar.edu.utn.frc.tup.p4.usersservice.config.OtpProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Component
public class EmailOtpProvider implements SecondFactorProvider {

    private final OtpService otp;
    private final OtpProperties props;
    private final NotificationEventPublisher mails;

    public EmailOtpProvider(OtpService otp, OtpProperties props, NotificationEventPublisher mails) {
        this.otp = otp; this.props = props; this.mails = mails;
    }

    @Override
    @Transactional
    public String generarDesafio(UUID userId, String email, String firstNames) {
        String code = otp.generar(key(userId), props.dosfaTtl());
        mails.enviar(EmailType.TWO_FACTOR_CODE, email, Map.of("firstNames", firstNames, "code", code));
        return code;   // NUNCA se loguea ni se devuelve al cliente
    }

    @Override
    public void verificar(UUID userId, String code) { otp.verificar(key(userId), code); }

    private String key(UUID userId) { return "2fa:" + userId; }
}
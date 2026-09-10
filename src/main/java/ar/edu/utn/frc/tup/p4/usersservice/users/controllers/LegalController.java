package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** DEC-31 - public with NO token: it has to be readable BEFORE having an account. */
@RestController
@RequestMapping("/api/users/public/legal")
public class LegalController {

    private final String version;

    public LegalController(@Value("${users.legal.terms-version}") String version) {
        this.version = version;
    }

    @GetMapping("/terms")
    public Map<String, String> tyc() throws Exception {
        String texto = new String(new ClassPathResource("legal/terms-" + version + ".md")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return Map.of("version", version, "texto", texto);
    }
}

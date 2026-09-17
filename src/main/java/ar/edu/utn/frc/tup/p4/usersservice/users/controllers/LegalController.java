package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** DEC-31 - public with NO token: it has to be readable BEFORE having an account. */
@Tag(name = "Legal", description = "Current terms and conditions.")
@RestController
@RequestMapping("${app.api.public-path}/legal")
public class LegalController {

    private final String version;

    public LegalController(@Value("${users.legal.terms-version}") String version) {
        this.version = version;
    }

    @Operation(summary = "Current terms and conditions",
               description = """
                        Deliberately public and available WITHOUT a token: it must be readable
                        BEFORE creating an account. Requiring a token to read what must be accepted
                        to create an account would be circular.

                        The `version` field is sent as `termsVersion` during registration.""")
    @ApiResponse(responseCode = "200",
                  description = "`version` (the current version) and `texto` (the Markdown content).")
    @GetMapping("/terms")
    public Map<String, String> terms() throws Exception {
        String content = new String(new ClassPathResource("legal/terms-" + version + ".md")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return Map.of("version", version, "texto", content);
    }
}

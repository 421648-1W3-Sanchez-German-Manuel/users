package ar.edu.utn.frc.tup.p4.usersservice.config;

import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.AccountGateInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AccountGateInterceptor gates;
    private final String publicPath;
    private final String privatePath;

    public WebConfig(AccountGateInterceptor gates,
            @Value("${app.api.public-path}") String publicPath,
            @Value("${app.api.private-path}") String privatePath) {
        this.gates = gates;
        this.publicPath = publicPath;
        this.privatePath = privatePath;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gates)
                .addPathPatterns(privatePath + "/**")
                .excludePathPatterns(publicPath + "/**");   // Public routes have no gates.
    }
}

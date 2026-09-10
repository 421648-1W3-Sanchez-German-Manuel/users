package ar.edu.utn.frc.tup.p4.usersservice.config;

import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.AccountGateInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AccountGateInterceptor gates;

    public WebConfig(AccountGateInterceptor gates) { this.gates = gates; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gates)
                .addPathPatterns("/api/users/**")
                .excludePathPatterns("/api/users/public/**");   // publicas: sin gates
    }
}

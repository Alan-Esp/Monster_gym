package com.monstergym.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ActiveEmployeeInterceptor activeEmployee;

    public WebConfig(ActiveEmployeeInterceptor activeEmployee) {
        this.activeEmployee = activeEmployee;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(activeEmployee).addPathPatterns("/api/**").excludePathPatterns("/api/auth/**");
    }
}

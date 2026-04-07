package com.cpa.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // SPA history fallback for console-vue routes, e.g. /console-vue/login
        registry.addViewController("/console-vue/").setViewName("forward:/console-vue/index.html");
        registry.addViewController("/console-vue/{path:[^\\.]*}").setViewName("forward:/console-vue/index.html");
    }
}


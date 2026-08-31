package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // allowedOriginPatterns (not allowedOrigins) so the
                // "*.vercel.app" wildcard actually works -- Vercel gives
                // every deployment its own preview subdomain in addition to
                // the main production one, and this covers all of them
                // without needing to update this list per-deploy. Local dev
                // (Vite's default port) still works alongside it.
                registry.addMapping("/**")
                        .allowedOriginPatterns("http://localhost:5173", "https://*.vercel.app")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
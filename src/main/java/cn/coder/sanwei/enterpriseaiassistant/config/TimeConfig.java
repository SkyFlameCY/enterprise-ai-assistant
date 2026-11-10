package cn.coder.sanwei.enterpriseaiassistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * TimeConfig
 *
 * @author caiyang
 * @date 2026/11/5
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

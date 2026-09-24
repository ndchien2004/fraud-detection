package com.frauddetection.scoring.rules;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RulesConfiguration {

    @Bean
    RuleEngine ruleEngine(@Value("${app.rules.file}") String file) {
        return new RuleEngine(Path.of(file));
    }
}

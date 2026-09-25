package com.frauddetection.scoring.model;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RiskModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RiskModelConfiguration.class);

    /** The trained ONNX model if the file exists, otherwise the hand-written weighted score. */
    @Bean
    RiskModel riskModel(@Value("${app.model.file}") String file) {
        Path path = Path.of(file);
        if (Files.isRegularFile(path)) {
            log.info("Using ONNX model {}", path.toAbsolutePath().normalize());
            return new OnnxRiskModel(path);
        }
        log.warn("No model at {} - falling back to the weighted score (run model-training/train.py)",
                path.toAbsolutePath().normalize());
        return new WeightedScoreModel();
    }
}

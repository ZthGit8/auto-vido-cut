package com.vide.autovidocut.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class AppPaths {

    @Getter
    @Value("${app.paths.uploads:./uploads}")
    private String uploads;

    @Getter
    @Value("${app.paths.outputs:./outputs}")
    private String outputs;

    @Getter
    @Value("${app.paths.tmp:./tmp}")
    private String tmp;

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(Path.of(uploads));
        Files.createDirectories(Path.of(outputs));
        Files.createDirectories(Path.of(tmp));
    }
}
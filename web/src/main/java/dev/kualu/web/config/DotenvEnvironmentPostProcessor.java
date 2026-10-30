package dev.kualu.web.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads project-root {@code .env} into Spring's environment so secrets can live
 * in the same file as CLI credentials. Real environment variables still win.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (Path candidate : candidateFiles()) {
            if (Files.isRegularFile(candidate)) {
                Map<String, Object> values = parse(candidate);
                if (!values.isEmpty()) {
                    environment.getPropertySources().addLast(
                            new MapPropertySource("dotenv[" + candidate + "]", values));
                }
                return;
            }
        }
    }

    private static Path[] candidateFiles() {
        Path userDir = Path.of("").toAbsolutePath();
        return new Path[] {
                userDir.resolve(".env"),
                userDir.getParent() != null ? userDir.getParent().resolve(".env") : userDir.resolve(".env"),
                userDir.resolve("..").resolve(".env")
        };
    }

    private static Map<String, Object> parse(Path file) {
        Map<String, Object> values = new LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(file)) {
            lines.forEach(line -> {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    return;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    return;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            });
        } catch (IOException e) {
            // ignore unreadable dotenv; required keys fail later with a clear message
        }
        return values;
    }
}

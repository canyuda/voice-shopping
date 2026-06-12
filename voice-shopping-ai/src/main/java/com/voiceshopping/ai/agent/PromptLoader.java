package com.voiceshopping.ai.agent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Loads prompt template files from classpath under the {@code prompts/} directory.
 */
@Component
public class PromptLoader {

    private static final String PROMPTS_DIR = "prompts/";

    /**
     * Loads a prompt file by name. The {@code prompts/} prefix is added automatically.
     *
     * @param filename prompt file name, e.g. "intent.txt"
     * @return file content as UTF-8 string
     * @throws RuntimeException if the file does not exist or cannot be read
     */
    public String load(String filename) {
        String path = PROMPTS_DIR + filename;
        try {
            return Files.readString(
                    new ClassPathResource(path).getFile().toPath(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt: " + path, e);
        }
    }
}

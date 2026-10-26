package com.waypoint.service;

import com.waypoint.enums.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
public class MyHrDevelopmentCredentialLogger {

    private static final Logger logger = LoggerFactory.getLogger(MyHrDevelopmentCredentialLogger.class);
    private final boolean enabled;
    private final boolean openTerminal;

    @org.springframework.beans.factory.annotation.Autowired
    public MyHrDevelopmentCredentialLogger(
            @Value("${waypoint.myhr.dev-credentials.enabled:false}") boolean enabled,
            @Value("${waypoint.myhr.dev-credentials.open-terminal:true}") boolean openTerminal) {
        this.enabled = enabled;
        this.openTerminal = openTerminal;
    }

    public MyHrDevelopmentCredentialLogger(boolean enabled) {
        this(enabled, false);
    }

    public void output(String loginId, String temporaryPassword, UserRole role) {
        if (!enabled) {
            return;
        }

        System.out.println("==================================================");
        System.out.println("My HR development credential");
        System.out.println("Login ID: " + loginId);
        System.out.println("Role: " + role);
        System.out.println("Temporary Password: " + temporaryPassword);
        System.out.println("==================================================");
        logger.info("My HR development credential\nLogin ID: {}\nRole: {}\nTemporary Password: {}",
                loginId, role, temporaryPassword);

        Path credentialsFile = writeCredentialsFile(loginId, temporaryPassword, role);

        if (openTerminal && credentialsFile != null) {
            openTerminalWindow(credentialsFile, loginId);
        }
    }

    private Path writeCredentialsFile(String loginId, String temporaryPassword, UserRole role) {
        try {
            Path currentDir = Paths.get("").toAbsolutePath();
            Path targetDir = currentDir;
            if (currentDir.getFileName() != null && currentDir.getFileName().toString().equalsIgnoreCase("backend")) {
                if (currentDir.getParent() != null) {
                    targetDir = currentDir.getParent();
                }
            }
            Path file = targetDir.resolve("dev-credentials.txt");
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String content = String.format(
                    "==================================================%n" +
                    "WAYPOINT - My HR Development Credential%n" +
                    "Generated At:       %s%n" +
                    "Login ID:           %s%n" +
                    "Role:               %s%n" +
                    "Temporary Password: %s%n" +
                    "==================================================%n" +
                    "Note: Use this temporary password to log in for the first time.%n" +
                    "==================================================%n",
                    timestamp, loginId, role, temporaryPassword
            );
            Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            Path logFile = targetDir.resolve("dev-credentials.log");
            Files.writeString(logFile, content + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            return file;
        } catch (Exception e) {
            logger.debug("Could not write dev-credentials file: {}", e.getMessage());
            return null;
        }
    }

    private void openTerminalWindow(Path file, String loginId) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return;
        }
        if ("true".equalsIgnoreCase(System.getProperty("java.awt.headless", "false"))
                || Boolean.getBoolean("waypoint.myhr.dev-credentials.suppress-window")) {
            return;
        }
        try {
            String title = "Waypoint Dev Credentials - " + loginId;
            new ProcessBuilder(
                    "cmd.exe", "/c", "start",
                    "\"" + title + "\"",
                    "cmd.exe", "/k",
                    "title " + title + " && type \"" + file.toAbsolutePath() + "\" && echo. && echo [Terminal window opened by Waypoint for development testing - close when done]"
            ).start();
        } catch (Exception e) {
            logger.debug("Could not launch terminal window: {}", e.getMessage());
        }
    }
}

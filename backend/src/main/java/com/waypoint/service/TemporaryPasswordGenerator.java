package com.waypoint.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class TemporaryPasswordGenerator {

    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%&*?";
    private static final String ALL = LOWER + UPPER + DIGITS + SYMBOLS;
    private static final int LENGTH = 16;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        char[] password = new char[LENGTH];
        password[0] = randomCharacter(LOWER);
        password[1] = randomCharacter(UPPER);
        password[2] = randomCharacter(DIGITS);
        password[3] = randomCharacter(SYMBOLS);
        for (int index = 4; index < password.length; index++) {
            password[index] = randomCharacter(ALL);
        }
        for (int index = password.length - 1; index > 0; index--) {
            int swapIndex = secureRandom.nextInt(index + 1);
            char value = password[index];
            password[index] = password[swapIndex];
            password[swapIndex] = value;
        }
        return new String(password);
    }

    private char randomCharacter(String characters) {
        return characters.charAt(secureRandom.nextInt(characters.length()));
    }
}

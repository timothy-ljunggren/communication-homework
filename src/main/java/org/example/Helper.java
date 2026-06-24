package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Helper utilities for reading user input from the console.
 */
public class Helper {

    private static final BufferedReader READER =
            new BufferedReader(new InputStreamReader(System.in));

    /**
     * Prints a prompt to the user and reads one line from stdin.
     *
     * @param message the prompt printed
     * @return the trimmed input, or "" on error / EOF
     */
    public static String readLine(String message) {
        System.out.print(message + " > ");
        try {
            String line = READER.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to read input: " + e.getMessage());
            return "";
        }
    }
}

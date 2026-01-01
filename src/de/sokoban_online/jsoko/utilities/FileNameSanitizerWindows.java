package de.sokoban_online.jsoko.utilities;

import java.util.Arrays;
import java.util.List;

public class FileNameSanitizerWindows {

    // List of invalid characters for Windows file names
    private static final String INVALID_CHARACTERS = "<>:\"/\\|?*";

    // List of reserved names for Windows
    private static final List<String> RESERVED_NAMES = Arrays.asList(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    public static String sanitizeFilename(String filename) {
        // Step 1: Replace invalid characters with an underscore '_'
        for (char c : INVALID_CHARACTERS.toCharArray()) {
            filename = filename.replace(c, '_');
        }

        // Step 2: Trim leading and trailing whitespace or dots
        filename = filename.trim();
        filename = filename.replaceAll("\\.+$", "");  // Remove trailing dots

        // Step 3: Check for reserved names and append an underscore if necessary
        if (RESERVED_NAMES.contains(filename.toUpperCase())) {
            filename = filename + "_";
        }

        // Step 4: Ensure the length does not exceed 255 characters
        if (filename.length() > 255) {
            filename = filename.substring(0, 255);
        }

        return filename;
    }
}


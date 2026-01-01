package de.sokoban_online.jsoko.utilities;

    public class FileNameSanitizerLinux {

        // Invalid characters for Linux file names
        private static final char INVALID_CHARACTER = '/';

        // Method to sanitize a filename for Linux
        public static String sanitizeFilename(String filename) {
            // Step 1: Replace invalid character '/' with an underscore '_'
            filename = filename.replace(INVALID_CHARACTER, '_');

            // Step 2: Remove null bytes if any
            filename = filename.replace("\0", "");

            // Step 3: Trim leading and trailing whitespace
            filename = filename.trim();

            // Step 4: Avoid starting with a dot or dash by prepending an underscore
            if (filename.startsWith(".") || filename.startsWith("-")) {
                filename = "_" + filename;
            }

            // Step 5: Ensure filename is not empty after sanitization
            if (filename.isEmpty()) {
                filename = "default_filename"; // Provide a default name if empty
            }

            return filename;
        }
    }

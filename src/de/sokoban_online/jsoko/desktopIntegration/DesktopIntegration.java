/**
 *  JSoko - A Java implementation of the game of Sokoban
 *  Copyright (c) 2020 by Matthias Meger, Germany
 *
 *  This file is part of JSoko.
 *
 *  JSoko is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.sokoban_online.jsoko.desktopIntegration;

import java.awt.Desktop;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.sokoban_online.jsoko.resourceHandling.Settings;
import de.sokoban_online.jsoko.utilities.OSSpecific.OSSpecific;

public class DesktopIntegration {

    private final static Pattern SIMPLE_CHARS = Pattern.compile("[a-zA-Z0-9]");

    /**
     * Opens the mail client and inserts a default text for the suggestion for improvement mail.
     * @throws URISyntaxException
     * @throws IOException
     */
    public static void sendSuggestionForImprovement() throws URISyntaxException, IOException {

            String url =
                "mailTo:JSoko@mail.de?subject=" + encodeUnusualChars("JSoko - Suggestion for improvement") +
                "&body=";

               String bodyText =
                  "Hello JSoko team.\n\n"                      +
                  "I'd like to make the following suggestion for improvement: \n\n" +
                  "[insert here a description of the suggestion]\n\n" +
                  "Regards \n"                                 +
                  "[your name] \n";
             url += encodeUnusualChars(bodyText);

            URI mailTo = new URI(url);
            Desktop.getDesktop().mail(mailTo);
    }

    /**
     * Opens the mail client and inserts a default text for the bug report mail.
     * @throws URISyntaxException
     * @throws IOException
     */
    public static void reportBug() throws URISyntaxException, IOException {

            String url =
                "mailTo:JSoko@mail.de?subject=" + encodeUnusualChars("JSoko - Bug Report") +
                "&body=";

               String bodyText =
                  "Hello JSoko team.\n\n"                      +
                  "I'd like to report a bug in JSoko version " + Settings.PROGRAM_VERSION      + ".\n" +
                  "I'm using the operating system: "           + OSSpecific.OSType.getOSType() + ".\n\n" +
                  "[insert here a description of the bug and - if possible - how to reproduce the bug]\n\n" +
                  "Regards \n"                                 +
                  "[your name] \n";
             url += encodeUnusualChars(bodyText);

            URI mailTo = new URI(url);
            Desktop.getDesktop().mail(mailTo);
    }

    private static String encodeUnusualChars(String aText) throws UnsupportedEncodingException {

        StringBuilder result = new StringBuilder();
        CharacterIterator iter = new StringCharacterIterator(aText);
        for(char c = iter.first(); c != CharacterIterator.DONE; c = iter.next()) {
            char[] chars = {c};
            String character = new String(chars);
            if(isSimpleCharacter(character)){
                result.append(c);
            }
            else {
                hexEncode(character, "UTF-8", result);
            }
        }

        return result.toString();
    }

    private static boolean isSimpleCharacter(String aCharacter){
        Matcher matcher = SIMPLE_CHARS.matcher(aCharacter);
        return matcher.matches();
    }

    /**
     * For the given character and encoding, appends one or more hex-encoded characters.
     * For double-byte characters, two hex-encoded items will be appended.
     * @throws UnsupportedEncodingException
     */
    private static void hexEncode(String character, String encoding, StringBuilder out) throws UnsupportedEncodingException {

        String HEX_DIGITS = "0123456789ABCDEF";

        byte[] bytes = character.getBytes(encoding);
        for (byte b : bytes) {
            out.append('%');
            out.append(HEX_DIGITS.charAt((b & 0xf0) >> 4));
            out.append(HEX_DIGITS.charAt(b & 0xf));
        }
    }

}

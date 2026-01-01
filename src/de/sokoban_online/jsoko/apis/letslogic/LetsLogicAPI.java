package de.sokoban_online.jsoko.apis.letslogic;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import de.sokoban_online.jsoko.resourceHandling.Settings;
import de.sokoban_online.jsoko.utilities.Utilities;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles all data transfers with LetsLogic.com
 * LetsLogic is a website where users can register themselves
 * and play levels. The solutions are saved and points are rewarded
 * for good solutions.
 * JSoko can submit new solutions for the user to LetsLogic.
 */
public class LetsLogicAPI {

    private final Gson gson;       // For json parsing
    private final String UTF8 = StandardCharsets.UTF_8.name();


    /**
     * Creates a new object for communicating with LetsLogic.com
     */
    public LetsLogicAPI() {
        this.gson = new Gson();
    }

    /**
     * Returns the data of all levels stored for the passed collection ID.
     *
     * @param collectionID the Letslogic ID of the collection
     * @return a {@code List} of the {@code LetsLogicLevel}s.
     */
    public List<LetsLogicLevel> getLevels(int collectionID) throws IOException {

        String postData = String.format("key=%s", urlEncode(Settings.letsLogicAPIKey));
        URL url = new URL("https://letslogic.com/api/v1/collection/" + collectionID);

        HttpURLConnection connection = null;

        try {
            connection = getConnection(postData, url);

            writeDataToConnection(postData, connection);

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {

                //String responseMessage = connection.getResponseMessage();

                //System.out.printf("\nstatus: %s, message: %s %n%n", connection.getResponseCode(), responseMessage);

                //for (Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                //	System.out.println(header.getKey() + "=" + header.getValue());
                //}

                try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                    LetsLogicLevel[] levels = gson.fromJson(reader, LetsLogicLevel[].class);
                    List<LetsLogicLevel> levelsList = new ArrayList<>(Arrays.asList(levels));
                    levelsList.forEach(LetsLogicLevel::convertAndCheckMapValues);

                    return levelsList;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect(); // Hier wird die Verbindung geschlossen
            }
        }

        return Collections.emptyList();
    }

    /**
     * Returns the data of all solutions stored for the user.
     *
     * @return a {@code List} of the {@code LetsLogicUserSolutionData}s.
     */
    public List<LetsLogicUserSolutionData> getUserSolutionData(String filename) throws IOException {

//		String postData = String.format("key=%s", urlEncode(userKey) );
//		URL url = new URL( "https://letslogic.com/api/v1/records" );
//
//		HttpURLConnection connection = getConnection(postData, url);
//
//		writeDataToConnection(postData, connection);
//
//		if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {

        List<LetsLogicUserSolutionData> solutionDataList = new ArrayList<>();

        // One example line of data:
        // {"34":{"blue":{"rank":1,"points":10,"moves":130,"pushes":43},"green":{"rank":1,"points":10,"moves":130,"pushes":43}},

        try (InputStreamReader reader = new InputStreamReader(Utilities.getInputStream(filename))) {

            JsonObject jsonObject = gson.fromJson(reader, JsonObject.class);

            for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {

                LetsLogicUserSolutionData solutionData = gson.fromJson(entry.getValue(), LetsLogicUserSolutionData.class);
                try {
                    String levelID = entry.getKey();
                    solutionData.levelID = Integer.parseInt(levelID);
                } catch (Exception e) {
                    continue;
                }

                solutionDataList.add(solutionData);
            }

            return solutionDataList;
        }
//		}

//		return Collections.emptyList();
    }

    /**
     * Submits the passed solution for the specified LetsLogic level ID.
     *
     * @param levelID  the LetsLogic ID of the level to submit the solution for
     * @param solution the solution as LURD-string
     * @return the response data for the submission
     */
    public Optional<LetsLogicSendSolutionAnswer> submitSolution(int levelID, String solution) throws IOException {

        if (levelID <= 0) {
            return Optional.empty();
        }

        Optional<LetsLogicSendSolutionAnswer> answer = trySubmitSolution(levelID, solution);

        return answer;
    }


    /**
     * Submits the passed solution for the specified LetsLogic level ID.
     *
     * @param levelID  the LetsLogic ID of the level to submit the solution for
     * @param solution the solution as LURD-string
     * @return the response data for the submission
     */
    public Optional<LetsLogicSendSolutionAnswer> trySubmitSolution(int levelID, String solution) {

        HttpURLConnection connection = null;

        try {
            String postData = String.format("key=%s&solution=%s", urlEncode(Settings.letsLogicAPIKey), urlEncode(solution));

            URL url = new URL("https://letslogic.com/api/v1/level/" + levelID);

            connection = getConnection(postData, url);

            writeDataToConnection(postData, connection);

//			printDebugInfo(connection);

            return connection.getResponseCode() == HttpURLConnection.HTTP_OK ?
                    parseSubmitSolutionSuccess(connection) : parseSubmitSolutionError(connection);

        } catch (Exception e) {
            LetsLogicSendSolutionAnswer answer = new LetsLogicSendSolutionAnswer();
            answer.errorMessage = e.toString(); // return cause + message (e.g. "java.net.UnknownHostException: letslogic.com")
            return Optional.of(answer);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void printDebugInfo(HttpURLConnection connection) {
        try {
            String responseMessage = connection.getResponseMessage();

            System.out.printf("\nstatus: %s, message: %s %n%n", connection.getResponseCode(), responseMessage);

            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                System.out.println(header.getKey() + "=" + header.getValue());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private Optional<LetsLogicSendSolutionAnswer> parseSubmitSolutionSuccess(HttpURLConnection connection) throws IOException {

//      Debug output
//try (BufferedReader buffer = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
//	buffer.lines().forEach(System.out::println);
//}
//printDebugInfo(connection);

        LetsLogicSendSolutionAnswer result = null;
        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
            result = gson.fromJson(reader, LetsLogicSendSolutionAnswer.class);
        }

        return Optional.ofNullable(result);
    }

    private static Optional<LetsLogicSendSolutionAnswer> parseSubmitSolutionError(HttpURLConnection connection) throws IOException {

        try (BufferedReader buffer = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
            LetsLogicSendSolutionAnswer answer = new LetsLogicSendSolutionAnswer();
            answer.errorMessage = buffer.lines().collect(Collectors.joining("\n"));
            return Optional.of(answer);
        }
    }

    /**
     * Returns the data of all LetsLogic collections.
     *
     * @return a {@code List} of the {@code LetsLogicLevelCollection}s.
     */
    public List<LetsLogicLevelCollection> getCollections() throws IOException {

        String postData = String.format("key=%s", urlEncode(Settings.letsLogicAPIKey));

        URL url = new URL("https://letslogic.com/api/v1/collections");

        HttpURLConnection connection = getConnection(postData, url);

        writeDataToConnection(postData, connection);

        if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {

            //				try (BufferedReader buffer = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            //		            buffer.lines().forEach(System.out::println);
            //		        }

            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                LetsLogicLevelCollection[] collections = gson.fromJson(reader, LetsLogicLevelCollection[].class);
                List<LetsLogicLevelCollection> collectionsList = new ArrayList<>(Arrays.asList(collections));
                return collectionsList;
            }
        }

        return Collections.emptyList();
    }

    private static void writeDataToConnection(String postData, HttpURLConnection connection) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(postData);
            writer.flush();
        }
    }

    private String urlEncode(String string) throws UnsupportedEncodingException {
        return URLEncoder.encode(string, UTF8);
    }

    private HttpURLConnection getConnection(String postData, URL url) throws IOException {

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoInput(true);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=" + UTF8);
        connection.setRequestProperty("Content-Length", String.valueOf(postData.getBytes(UTF8).length));
        connection.setRequestProperty("User-Agent", "JSoko " + Settings.PROGRAM_VERSION);

        return connection;
    }


    /**
     * The Object returned by LetsLogic after a solution has been submitted.
     */
    public static class LetsLogicSendSolutionAnswer {

        /**
         * In case of no error, the "error" isn't part of the json.
         * In case of error, ONLY the "error" is part of the json.
         * Hence, it's either only an error message returned or a result message with the results.
         * <p>
         * Example Letslogic success return messages:
         * - "Well done! You have completed this puzzle in 75 move(s) and 19 pushe(s)."
         * - "Level successfully completed, however, you have not improved on your existing solutions."
         * - ...
         */

        public String result = "";
        @SerializedName("error")
        public String errorMessage = "";
        @SerializedName("blue")
        public Result movesSolutionResult = null;
        @SerializedName("green")
        public Result pushesSolutionResult = null;

        public static class Result {
            public int rank = 0;    // Position in the ranking of the solutions
            public int points = 0;    // Points rewarded for the solution
            public int moves = 0;    // Number of moves
            public int pushes = 0;    // Number of pushes

            @Override
            public String toString() {
                return String.format("rank: %d, points: %d, moves/pushes: %d/%d", rank, points, moves, pushes);
            }
        }

        @Override
        public String toString() {
            if (errorMessage.trim().isEmpty()) {
                return String.format("Result message: %s\nMoves solution result: %s\nPushes solution result: %s", result, movesSolutionResult, pushesSolutionResult);
            }

            return String.format("Result message: %s\nerror message: %s\nMoves solution result: %s\nPushes solution result: %s", result, errorMessage, movesSolutionResult, pushesSolutionResult);
        }
    }

    /**
     * The object representing a LetsLogic level collection.
     */
    static class LetsLogicLevelCollection {
        public int id = -1;
        public String title = "";
        public String author = "";
        public String description = "";
        @SerializedName("levels")
        public int levelCount = 0;

        @Override
        public String toString() {
            return String.format("ID: %d, title: %s, author: %s, description: %s, number of levels: %d", id, title, author, description, levelCount);
        }
    }

    /**
     * Object returned by LetsLogic in case of error.
     */
    static class LetsLogicError {
        @SerializedName("error")
        public String errorMessage = "";
    }

    /**
     * The object representing a LetsLogic level.
     */
    static class LetsLogicLevel {
        public int id = -1;
        public int height = -1;
        public int width = -1;
        public String title = "";
        public String author = "";
        public String map = "";
        @SerializedName("blue_moves")
        public int bestMovesSolution_moves = -1;
        @SerializedName("blue_pushes")
        public int bestMovesSolution_pushes = -1;
        @SerializedName("green_moves")
        public int bestPushesSolution_moves = -1;
        @SerializedName("green_pushes")
        public int bestPushesSolution_pushes = -1;


        /**
         * Converts the map to a more readable format.
         */
        public void convertAndCheckMapValues() {

            map = map.replace('0', ' ').replace('1', '#').replace('2', '@').replace('3', '$').replace('4', '.').replace('5', '*').replace('6', '+').replace('7', '-');

            if (height < 0) {
                height = 0;
            }
            if (width < 0) {
                width = 0;
            }

            if (map.length() == height * width) {
                String newMap = "";
                for (int row = 0; row < map.length(); row += width) {
                    newMap += map.substring(row, row + width) + "\n";
                }
                map = newMap;
            }
        }

        @Override
        public String toString() {
            String string = "title: " + title;
            string += "\n\n" + map;
            string += "\nauthor: " + author;
            string += "\nID: " + id;

            string += "\nbest moves solution: " + bestMovesSolution_moves + "/" + bestMovesSolution_pushes;
            string += "\nbest pushes solution: " + bestPushesSolution_moves + "/" + bestPushesSolution_pushes;

            return string;
        }
    }

    /**
     * The object representing a LetsLogic level collection.
     */
    public static class LetsLogicUserSolutionData {

        public int levelID = -1;

        @SerializedName("blue")
        public LetsLogicUserSolutionData.Result movesSolutionResult = null;
        @SerializedName("green")
        public LetsLogicUserSolutionData.Result pushesSolutionResult = null;

        public static class Result {
            public int rank = 0;    // Position in the ranking of the solutions
            public int points = 0;    // Points rewarded for the solution
            public int moves = 0;    // Number of moves
            public int pushes = 0;    // Number of pushes

            @Override
            public String toString() {
                return String.format("rank: %d, points: %d, moves/pushes: %d/%d", rank, points, moves, pushes);
            }
        }
    }
}

package de.sokoban_online.jsoko;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JOptionPane;

import de.sokoban_online.jsoko.leveldata.LevelCollection;
import de.sokoban_online.jsoko.leveldata.LevelsIO;
import de.sokoban_online.jsoko.optimizer.Optimizer.OptimizationMethod;
import de.sokoban_online.jsoko.resourceHandling.Settings;

public class OptimizerAsPlugin {

    public static JSoko application;

    public static OptimizerSettings settings = new OptimizerSettings();

    public static String foundErrors = "";    // Errors found by parsing the parameters

    public static boolean isOptimizerPluginModus = false; // NOT stored in settings


    /**
     * Checks whether JSoko has been called from YASC. In that case JSoko is only
     * used as an optimizer plugin of YASC. The user can't do anything with JSoko in that case.
     * JSoko optimizes the provided level and then closes.
     *
     * @param parameters  the parameters passed to JSoko
     */
    public static void checkParameters(String[] parameters) {

        if(Arrays.stream(parameters).noneMatch(p -> p.contains("task"))) {
            isOptimizerPluginModus = false; // not started as plugin
            return;
        }


        Map<String, List<String>> parsedParameters = parseParameters(parameters);

        for (String parameter : parsedParameters.keySet()) {

            List<String> values = parsedParameters.get(parameter);

            parseLevelPath(parameter, values);
            parseOutputFile(parameter, values);
            parseTask(parameter, values);
            parseOptimizationMethod(parameter, values);
            parseVicinityValues(parameter, values);
            parseInterativeOptimization(parameter, values);
            parsePreservePlayerEndPosition(parameter, values);
            parseShowOptimizerGUI(parameter, values);
            parseNumberOfCPUs(parameter, values);
            parseMaxBoxConfigurations(parameter, values);
            parseTimeLimit(parameter, values);

        }

        // Check obligatory parameters.
        if(settings.outputPath == null) {
            logError("no file path for result file found.");
        }
        if(settings.collection == null) {
            logError("no path to level found.");
        }

        if(settings.vicinityRestrictions.isEmpty()) {
            settings.vicinityRestrictions.add(999);        // use 999 for one box as default value
        }

        // Close JSoko in case of errors.
        if(!foundErrors.isEmpty()) {

            JOptionPane.showMessageDialog(application, foundErrors, "Error", JOptionPane.ERROR_MESSAGE);

            output(foundErrors);
            System.out.println(foundErrors);

            System.exit(1);
        }
    }

    private static void parseTimeLimit(String parameter, List<String> values) {

        if(parameter.equalsIgnoreCase("time-limit-minutes")) {

            if(values.isEmpty()) {
                logError("Error: no attributes given for parameter: " + parameter);
                return;
            }

            try {
                int value = Integer.parseInt(values.get(0).trim());
                if(value < 0) {
                    logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+value);
                } else {
                    settings.maxRuntimeInMinutes = value;
                }
            }catch(Exception e) {
                logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+values);
            }
        }
    }

    /**
     * Parses the number of box configurations to be used for optimizing.
     * Note: this is an unofficial parameter since this may result in too much RAM to be used.
     * @param parameter
     * @param values
     */
    private static void parseMaxBoxConfigurations(String parameter, List<String> values) {
        if(parameter.equalsIgnoreCase("maximum-box-configurations")) {

            if(values.isEmpty()) {
                logError("Error: no attributes given for parameter: " + parameter);
                return;
            }

            try {
                int value = Integer.parseInt(values.get(0).trim());
                if(value < 0 || value > 1_000_000) {
                    logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+value);
                } else {
                    settings.maximumBoxConfigurations = value == 0 ?  -1 : value;
                }
            }catch(Exception e) {
                logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+values);
            }
        }
    }

    /**
     * Parses the number of CPUs to be used for optimizing.
     * @param parameter
     * @param values
     */
    private static void parseNumberOfCPUs(String parameter, List<String> values) {
        if(parameter.equalsIgnoreCase("cpus")) {

            if(values.isEmpty()) {
                logError("Error: no attributes given for parameter: " + parameter);
                return;
            }

            try {
                int value = Integer.parseInt(values.get(0).trim());
                if(value < 0) {
                    logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+value);
                } else {
                    if(value != 0) {        // note: value == 0 means: use default value
                        settings.cpusToUse = value;
                    }
                }
            }catch(Exception e) {
                logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+values);
            }
        }
    }

    /**
     * Parses whether the optimizer GUI shall be visible while optimizing.
     * @param parameter
     * @param values
     */
    private static void parseShowOptimizerGUI(String parameter, List<String> values) {
        if(parameter.equalsIgnoreCase("show-window")) {     // Specifies whether the GUI is visible or not

            try {
                int value = Integer.parseInt(values.get(0).trim());
                if(value < 0 || value > 2) {
                    logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+value);
                } else {
                    if(value == 0) {    // "normal"
                        settings.showOptimizerWindow     = true;
                        settings.minimizeOptimizerWindow = false;
                    }
                    if(value == 1) {    // "minimized"
                        settings.showOptimizerWindow     = true;
                        settings.minimizeOptimizerWindow = true;
                    }
                    if(value == 2) {    // "hidden"
                        settings.showOptimizerWindow     = false;
                        settings.minimizeOptimizerWindow = false;
                    }
                }
            }catch(Exception e) {
                logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+values);
            }
        }
    }

    /**
     * Parses whether the optimizer shall preserve the player end position in all result solutions.
     * @param parameter
     * @param values
     */
    private static void parsePreservePlayerEndPosition(String parameter, List<String> values) {
        if(parameter.equalsIgnoreCase("preserve-player-end-position")) {

            if(values.isEmpty()) {
                logError("Error: no attributes given for parameter: " + parameter);
                return;
            }

            try {
                int value = Integer.parseInt(values.get(0).trim());
                if(value != 0 && value != 1) {
                    logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+value);
                } else {
                    settings.preservePlayerEndPosition = value == 1;
                }
            }catch(Exception e) {
                logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+values);
            }
        }
    }

    /**
     * Parses whether the optimizer shall use iterative optimizing.
     * @param parameter
     * @param values
     */
    private static void parseInterativeOptimization(String parameter, List<String> values) {
        if(parameter.equalsIgnoreCase("iterative-optimization")) {

            if(values.isEmpty()) {
                logError("Error: no attributes given for parameter: " + parameter);
                return;
            }

            try {
                int value = Integer.parseInt(values.get(0));
                if(value != 0 && value != 1) {
                    logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+value);
                } else {
                    settings.iterativeOptimization = value == 1;
                }
            }catch(Exception e) {
                logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+values);
            }
        }
    }

    /**
     * Parses the vicinity squares to be used for every box for the optimizer.
     * @param parameter
     * @param values
     */
    private static void parseVicinityValues(String parameter, List<String> values) {
        if(parameter.equalsIgnoreCase("box-1-squares") || parameter.equalsIgnoreCase("box-2-squares")
        || parameter.equalsIgnoreCase("box-4-squares") || parameter.equalsIgnoreCase("box-3-squares")) {

            if(values.isEmpty()) {
                logError("Error: no squares for box given. Parameter: " + parameter);
                return;
            }

            try {
                int squareCount = Integer.parseInt(values.get(0).trim());
                if(squareCount > 0) {
                    settings.vicinityRestrictions.add(squareCount);
                }
            }catch(Exception e) {
                logError("Error: parameter value is invalid. Parameter: "+ parameter + " | value: "+values);
            }
        }
    }

    /**
     * Parses the method to be used for optimizing the solutions.
     * @param parameter
     * @param values
     */
    private static void parseOptimizationMethod(String parameter, List<String> values) {
        if(parameter.equalsIgnoreCase("optimization-method")) {
            if(values.isEmpty()) {
                logError("Error: no method given.");
            }

            try {
                int optimizationMethod = Integer.parseInt(values.get(0).trim());
                settings.method = OptimizationMethod.values()[optimizationMethod];
            }catch(Exception e) {
                logError("Error: no method given. Received: "+values.get(0));
            }
        }
    }

    /**
     * Parses the task to be performed by this plugin: optimizing or solving.
     * @param parameter
     * @param values
     */
    private static void parseTask(String parameter, List<String> values) {
        if(parameter.equalsIgnoreCase("task")) {
            if(values.contains("optimize")) {
                isOptimizerPluginModus = true;
            } else {
                isOptimizerPluginModus = false;
                logError("Error: no valid task. Received task: "+values);
            }
        }
    }

    /**
     * Parses the file to write the results of the optimizing run to.
     * @param parameter
     * @param values
     */
    private static void parseOutputFile(String parameter, List<String> values) {
        if(parameter.equalsIgnoreCase("outputFile")) {
            if(values.isEmpty()) {
                logError("Error: no file name for saving optimizing results given!");
                return;
            }
            String outputFilePath = values.get(0);

            try {
                settings.outputPath = Paths.get(outputFilePath);

                // Test writing to the file.
                BufferedWriter writer = Files.newBufferedWriter(settings.outputPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
                writer.write("");
                writer.close();
            }
            catch(Exception e) {
                logError("Error: output file invalid: " + outputFilePath + "   : "+e.getLocalizedMessage());
            }
        }
    }

    /**
     * Parses the path to the file containing the level and solutions to be optimized.
     *
     * @param parameter
     * @param values
     */
    private static void parseLevelPath(String parameter, List<String> values) {

        if(parameter.equalsIgnoreCase("level-path")) {
            if(values.isEmpty()) {
                logError("Error: no level path given!");
                return;
            }
            String collectionPath = values.get(0);

            File collectionFile = Paths.get(collectionPath).toFile();

            LevelCollection collection = getLevelCollection(collectionFile.getAbsolutePath());
            if(collection == null || collection.isEmpty()) {
                logError("Error: no levels found in file " + collectionPath);
                return;
            }
            settings.inputFilePath = collectionFile;
            settings.collection = collection;
        }
    }

    private static LevelCollection getLevelCollection(String levelPath) {
        try {
            return new LevelsIO(application).getLevelCollectionFromFile(levelPath);
        } catch (IOException e) {}

        return null;
    }

    private static void logError(String errorMessage) {
        foundErrors += errorMessage + "\n";
    }

    /**
     * Parses the parameters passed to JSoko and returns
     * them as map, where the key is the parameter name and the value(s)
     * is/are the options passed for that parameter.
     *
     * @param parameters parameters passed to JSoko
     * @return the parsed parameters in a Map
     */
    private static Map<String, List<String>> parseParameters(String[] parameters) {


        Map<String, List<String>> parsedParameters = new HashMap<>();

        List<String> options = null;

        for (final String parameter : parameters) {
            if (parameter.charAt(0) == '-') {
                if (parameter.length() < 2) {
                    continue;
                }

                options = new ArrayList<>();
                parsedParameters.put(parameter.substring(1).toLowerCase(), options);
            }
            else if (options != null) {
                options.add(parameter);
            }
            else {
                parsedParameters.put("level-path", Arrays.asList(parameter));
            }
        }

        return parsedParameters;
    }


    /**
     * Writes the passed error string to the output file to be read from the caller of this plugin.
     */
    public static void output(String string) {
        outputString("::"+string); // "::" so the caller can see it's not a solution
    }

    /**
     * Writes the passed string to the output file to be read from the caller of this plugin.
     */
    public static void outputString(String outputString) {
        if(settings.outputPath == null) {
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(settings.outputPath, StandardOpenOption.APPEND, StandardOpenOption.SYNC)) {
            writer.write(outputString);
        } catch (Exception e) { /* do nothing. */ }
    }

    /* The settings to be used. */
    public static class OptimizerSettings {
        public LevelCollection collection = null;
        public File inputFilePath = null;

        public OptimizationMethod method = OptimizationMethod.PUSHES_MOVES;
        public List<Integer> vicinityRestrictions = new ArrayList<>();
        public boolean iterativeOptimization = true;
        public int maximumBoxConfigurations = 0;
        public boolean preservePlayerEndPosition = false;
        public int cpusToUse = getCPUCoresCountInitialValue();

        public long maxRuntimeInMinutes = 0;    // Time for the optimizer until it has to be stopped

        public boolean showOptimizerWindow = true;
        public boolean minimizeOptimizerWindow = false;

        Path outputPath = null;

        /**
         * Return 75% of the available cores as default value when the program starts
         * for the very first time.
         * Otherwise, return the number stored in the Settings.
         * @return the number of CPU cores to use
         */
        private static int getCPUCoresCountInitialValue() {
            if(Settings.CPUCoresToUse > Runtime.getRuntime().availableProcessors()) {
                return (int) Math.ceil(Runtime.getRuntime().availableProcessors() * 0.75d);
            }
            return Settings.CPUCoresToUse;
        }
    }
}

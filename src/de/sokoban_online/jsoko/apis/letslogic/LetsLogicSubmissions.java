package de.sokoban_online.jsoko.apis.letslogic;

import static de.sokoban_online.jsoko.resourceHandling.Settings.LetslogicSubmitSolutions.ALL_LEVELS_OF_COLLECTIONS;
import static de.sokoban_online.jsoko.resourceHandling.Settings.LetslogicSubmitSolutions.ONLY_CURRENT_LEVEL;

import java.awt.Cursor;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import javax.swing.SwingUtilities;

import de.sokoban_online.jsoko.JSoko;
import de.sokoban_online.jsoko.apis.letslogic.LetsLogicAPI.LetsLogicSendSolutionAnswer;
import de.sokoban_online.jsoko.gui.GUI;
import de.sokoban_online.jsoko.leveldata.Level;
import de.sokoban_online.jsoko.leveldata.LevelCollection;
import de.sokoban_online.jsoko.leveldata.LevelsIO;
import de.sokoban_online.jsoko.leveldata.solutions.Solution;
import de.sokoban_online.jsoko.leveldata.solutions.SolutionsManager;
import de.sokoban_online.jsoko.resourceHandling.Settings;
import de.sokoban_online.jsoko.resourceHandling.Texts;
import de.sokoban_online.jsoko.utilities.Debug;
import de.sokoban_online.jsoko.utilities.Utilities;

public class LetsLogicSubmissions {

    private final GUI applicationGUI;

    private final LevelsIO levelIO;
    private final JSoko application;

    public Thread letslogicSubmitThread = new Thread();

    private final SubmitToLetsLogic letslogicSubmit = new SubmitToLetsLogic();

    public LetsLogicSubmissions(JSoko application) {
        this.application = application;
        this.applicationGUI = application.applicationGUI;
        this.levelIO = application.levelIO;
    }

    /**
     * Submits the solutions to Letslogic.
     * The submission is executed asynchronously.
     */
    public void submitToLetslogic() {

        applicationGUI.letslogicStatusText.setText("");  // delete all previous messages

        if (Settings.letsLogicAPIKey.isEmpty()) {
            applicationGUI.letslogicStatusText.setText("Error. LetsLogic API Key not set.");
            return;
        }

        // Stop threads that may already been submitting solutions.
        letslogicSubmitThread.interrupt();
        try {
            letslogicSubmitThread.join(1000);
        } catch (InterruptedException e) { /* do nothing */ }

        letslogicSubmitThread = new Thread( () -> {

            SwingUtilities.invokeLater(() -> applicationGUI.letslogicPanel.setCursor(new Cursor(Cursor.WAIT_CURSOR)));

            letslogicSubmit.addText(Texts.getText("letslogic.submittingBetterSolutionsStarted") + "\n");

            if (Settings.letslogicSubmitSolutionsSetting == ALL_LEVELS_OF_COLLECTIONS) {
                submitToLetslogicCollections();
            } else {
                submitToLetslogicLevels();
            }

            letslogicSubmit.addText("\n" + Texts.getText("letslogic.submittingBetterSolutionsEnded"));

            SwingUtilities.invokeLater(() -> {
//                applicationGUI.letslogicStatusText.setCaretPosition(0); // Jump to the first line
                applicationGUI.letslogicPanel.setCursor(Cursor.getDefaultCursor());
            });
        });
        letslogicSubmitThread.setDaemon(true);
        letslogicSubmitThread.start();
    }

    private void submitToLetslogicLevels() {

        final List<Level> levelsToSubmit = Settings.letslogicSubmitSolutionsSetting == ONLY_CURRENT_LEVEL ?
                                            Collections.singletonList(application.currentLevel) :  // Only submit for current level
                                            application.currentLevelCollection.getLevels();

        letslogicSubmit.submit(levelsToSubmit);
    }

    public void submitToLetslogicCollections() {

        ArrayList<File> collectionFiles = applicationGUI.getFilesForLoading(Texts.getText("letslogic.chooseCollections"));
        collectionFiles.sort( (o1, o2) -> o1.getAbsolutePath().compareToIgnoreCase(o2.getAbsolutePath()) );

        try {
            for(File file : collectionFiles) {

                LevelCollection col = levelIO.getLevelCollectionFromFile(file.getAbsolutePath());

                letslogicSubmit.addText(Texts.getText("collection") + ": " + col.getTitle());
                letslogicSubmit.addText("\n");

                letslogicSubmit.submit(col.getLevels());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    class SubmitToLetsLogic {

        final LetsLogicAPI l = new LetsLogicAPI();

        private final static int MOVES_PUSHES_SOLUTION = 0;
        private final static int PUSHES_MOVES_SOLUTION = 1;

        private final List<Level> levels = new ArrayList<>();

        public void submit(List<Level> levelsToSubmit) {
            this.levels.clear();
            this.levels.addAll(levelsToSubmit);

            for (Level level : levels) {

                if (Thread.currentThread().isInterrupted()) {
                    return;
                }

                if (level.getLetsLogicID() == -1) {
                    addHeaderTextForLevel(level);
                    addText(Texts.getText("letslogic.noLetsLogicLevelID"));
                    continue;
                }

                submitSolutionsForLevel(null, level);
            }
        }

        private void submitSolutionsForLevel(Collection collection, Level level) {

            try {
                SolutionsManager solutionsManger = level.getSolutionsManager();

                if(solutionsManger.getSolutionCount() > 0) {

                    Solution movesSolution = solutionsManger.getBestMovesSolution();
                    Solution pushesSolution = solutionsManger.getBestPushesSolution();

                    submitNewSolution(level, movesSolution, MOVES_PUSHES_SOLUTION);
                    submitNewSolution(level, pushesSolution, PUSHES_MOVES_SOLUTION);

                    displayMessageWhenLetslogicSolutionIsBetter(level, movesSolution, pushesSolution, level.getLetsLogicID());
                }
            } catch (Exception e) {
                e.printStackTrace();
                applicationGUI.letslogicStatusText.setText(e.getLocalizedMessage());
            }
        }

        /** Submits the passed solution if it is better than any previous submitted solutions (moves/pushes solution or pushes/moves solution). */
        private void submitNewSolution(Level level, Solution solution, int kindOfSolution) throws IOException {

            SentLetslogicSolutions alreadySubmittedSolutions = levelIO.database.getSentLetslogicSolutions(Settings.letsLogicAPIKey, level.getLetsLogicID());

            boolean isBetterSolution = kindOfSolution == MOVES_PUSHES_SOLUTION ?
                    alreadySubmittedSolutions == null || alreadySubmittedSolutions.isWorseMovesSolutionThan(solution) :
                    alreadySubmittedSolutions == null || alreadySubmittedSolutions.isWorsePushesSolutionThan(solution);

            if(!isBetterSolution) {
                return;
            }

            Optional<LetsLogicSendSolutionAnswer> answer = l.submitSolution(level.getLetsLogicID(), solution.lurd);
            answer.ifPresent( submitResult -> {

                    if(!submitResult.errorMessage.isEmpty()) {
                        addHeaderTextForLevel(level);
                        addText(submitResult.errorMessage);
                        return;
                    }

                    // Save the new results in the database.
                    if(alreadySubmittedSolutions != null) {
                        levelIO.database.deleteSentLetslogicSolution(alreadySubmittedSolutions);
                    }
                    saveNewSolutionValues(Settings.letsLogicAPIKey, level.getLetsLogicID(), submitResult);

                    if(submitResult.result.contains("you have not improved")) {
//                        addText(Texts.getText("letslogic.noImprovement") + "\n"); // creates too many messages in log
                    } else {
                        String outputText = kindOfSolution == MOVES_PUSHES_SOLUTION ?
                                Texts.getText("bestMovesSolution") + ": " + solution + "\n" :
                                Texts.getText("bestPushesSolution") + ": " + solution + "\n";
                        outputText += submitResult + "\n";

                        addHeaderTextForLevel(level);
                        addText(outputText);
                    }
            });
        }

        /** Adds the header text for a level to the log. */
        private void addHeaderTextForLevel(Level level) {
            String string = Texts.getText("level") + ": " + level.getNumber() + " - " + level.getTitle() + "  id: "+level.getLetsLogicID(); // Level data

            addText(string);
        }

        /**
         * Checks whether the stored solutions at Letslogic.com are better than the just submitted ones.
         * If yes a message about this is displayed to the user.
         *
         * @param movesPushesSolution  submitted moves/pushes solution
         * @param pushesMovesSolution  submitted pushes/moves solution
         * @param letslogicID          letslogic database ID of the level the solutions have been submitted for
         */
        private void displayMessageWhenLetslogicSolutionIsBetter(Level level, Solution movesPushesSolution, Solution pushesMovesSolution, int letslogicID) {

            SentLetslogicSolutions letslogicSolutions = levelIO.database.getSentLetslogicSolutions(Settings.letsLogicAPIKey, letslogicID);

            if(letslogicSolutions == null) {
                if(Debug.isDebugModeActivated) {
                    System.out.println("no submitted solutions found for ID: "+letslogicID);
                }
                return;
            }

            // Check for a better moves solution
            if(Utilities.intCompare2Pairs(letslogicSolutions.bestMovesMoveCount, movesPushesSolution.movesCount,
                                          letslogicSolutions.bestMovesPushCount, movesPushesSolution.pushesCount) < 0) {
                addHeaderTextForLevel(level);
                addText(Texts.getText("letslogic.betterSolutionAtLetslogic") + "\n");
            }

            // Check for a better pushes solution
            if(Utilities.intCompare2Pairs(letslogicSolutions.bestPushesPushCount, pushesMovesSolution.pushesCount,
                                          letslogicSolutions.bestPushesMoveCount, pushesMovesSolution.movesCount) < 0) {
                addHeaderTextForLevel(level);
                addText(Texts.getText("letslogic.betterSolutionAtLetslogic") + "\n");
            }
        }

        /**
         * Saves the new best solution values for the given letslogicLevelID and apiKey.
         *
         * @param apiKey       the apiKey used to submit the solution
         * @param letslogicLevelID  the ID of the level in the Letslogic database
         * @param submitResult the result answer from Letslogic for the submission
         */
        private void saveNewSolutionValues(String apiKey, int letslogicLevelID, LetsLogicSendSolutionAnswer submitResult) {

            // Delete the old data if there is any.
            SentLetslogicSolutions alreadySubmittedSolutions = levelIO.database.getSentLetslogicSolutions(Settings.letsLogicAPIKey, letslogicLevelID);
            if(alreadySubmittedSolutions != null) {
                levelIO.database.deleteSentLetslogicSolution(alreadySubmittedSolutions);
            }

            SentLetslogicSolutions bestSolutionsData = new SentLetslogicSolutions();
            bestSolutionsData.apiKey              = Settings.letsLogicAPIKey;
            bestSolutionsData.letslogicLevelID    = letslogicLevelID;
            bestSolutionsData.bestMovesMoveCount  = submitResult.movesSolutionResult.moves;
            bestSolutionsData.bestMovesPushCount  = submitResult.movesSolutionResult.pushes;
            bestSolutionsData.bestPushesMoveCount = submitResult.pushesSolutionResult.moves;
            bestSolutionsData.bestPushesPushCount = submitResult.pushesSolutionResult.pushes;
            try {
                levelIO.database.insertSentLetslogicSolution(bestSolutionsData);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        void addText(String text) {
            SwingUtilities.invokeLater( () -> applicationGUI.letslogicStatusText.append(text + "\n") );
        }
    }
}

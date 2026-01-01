/**
 * JSoko - A Java implementation of the game of Sokoban
 * Copyright (c) 2017 by Matthias Meger, Germany
 * <p>
 * This file is part of JSoko.
 * <p>
 * JSoko is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.sokoban_online.jsoko.optimizer;

import static de.sokoban_online.jsoko.board.Directions.getAxisOfDirection;
import static de.sokoban_online.jsoko.board.Directions.getOppositeDirection;
import static de.sokoban_online.jsoko.optimizer.Optimizer.OptimizerStatus.RUNNING;
import static java.util.stream.Collectors.toCollection;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import de.sokoban_online.jsoko.board.Board;
import de.sokoban_online.jsoko.board.DirectionConstants;
import de.sokoban_online.jsoko.optimizer.AllMetricsOptimizer.BoardPosition;
import de.sokoban_online.jsoko.optimizer.AllMetricsOptimizer.BoardPositionWithPushDirection;
import de.sokoban_online.jsoko.optimizer.AllMetricsOptimizer.BoardPositionsStorage;
import de.sokoban_online.jsoko.optimizer.AllMetricsOptimizer.BoardPositionsStorage.Statistics;
import de.sokoban_online.jsoko.optimizer.AllMetricsOptimizer.BoardPositionsStorage.StorageBoardPosition;
import de.sokoban_online.jsoko.optimizer.AllMetricsOptimizer.PriorityQueueOptimizer;
import de.sokoban_online.jsoko.optimizer.GUI.OptimizerGUISuperClass;
import de.sokoban_online.jsoko.optimizer.dataStructures.BitVector;
import de.sokoban_online.jsoko.optimizer.dataStructures.BoardPositionQueue;
import de.sokoban_online.jsoko.optimizer.dataStructures.BoxConfigurationStorageHashSet;
import de.sokoban_online.jsoko.optimizer.dataStructures.BoxPositions;
import de.sokoban_online.jsoko.optimizer.dataStructures.PlayerPositionsQueue;
import de.sokoban_online.jsoko.resourceHandling.Settings.SearchDirection;
import de.sokoban_online.jsoko.resourceHandling.Texts;
import de.sokoban_online.jsoko.utilities.Debug;
import de.sokoban_online.jsoko.utilities.Utilities;

/**
 * This code is heavily inspired by the code from Sébastien Gouëzel. It's a
 * migrated version of his C++ coding. Hence, a big "thank you" to Sébastien
 * Gouëzel, for inventing the "Vicinity search" optimization method, and
 * generously sharing information on the method and its implementation. By
 * sharing his ideas and insights on the subject, he has made a significant and
 * lasting contribution to the Sokoban game itself, transcending the
 * implementation of the algorithm in this optimizer.
 * <p>
 * This class offers the functionality of optimizing a set of existing
 * solutions.  The incantation logic is as follows:
 * <p>
 * The constructor gets passed the most basic data of the optimization job,
 * most notably the board, inside which solutions will be optimized.
 * The constructor already starts a background thread to identify (compute
 * and collect) a certain kind of deadlock configurations.
 * <p>
 * The real optimization job is started by invocation of the method
 * <code>startOptimizer</code>, which creates a new thread to do the job
 * by invocation of our <code>run</code> method.
 * <p>
 * The optimization can be stopped prematurely by invocation of the method
 * <code>stopOptimizer</code>, which sets a global flag, that is frequently
 * checked by the worker thread.
 * <p>
 * New solutions found by this class are communicated by invocation of the
 * GUI notification callback method <code>newFoundSolution</code>,
 * in the middle of its work, and by <code>optimizerEnded</code> when the
 * optimizer has completed its work.
 */
public class Optimizer implements Runnable, DirectionConstants {

    /**
     * Constants that represent the method used for optimizing a solution.
     */
    public enum OptimizationMethod {
        /**
         * Optimize for moves, then for pushes as a tie-break.
         */
        MOVES_PUSHES {
            @Override
            public String toString() {
                return Texts.getText("optimizer.movesPushesOptimizationMethod");
            }
        },
        /**
         * Optimize for pushes, then for moves as a tie-break.
         */
        PUSHES_MOVES {
            @Override
            public String toString() {
                return Texts.getText("optimizer.pushesMovesOptimizationMethod");
            }
        },
        /**
         * Optimize for moves, then for pushes and then for the secondary metrics.
         */
        MOVES_PUSHES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS {
            @Override
            public String toString() {
                return Texts.getText("optimizer.movesAllMetricsOptimizationMethod");
            }
        },
        /**
         * Optimize for pushes, then for moves and then for the secondary metrics.
         */
        PUSHES_MOVES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS {
            @Override
            public String toString() {
                return Texts.getText("optimizer.pushesAllMetricsOptimizationMethod");
            }
        },
        /**
         * Optimize for moves, then for highest pushes.
         */
        MOVES_HIGHEST_PUSHES {
            @Override
            public String toString() {
                return Texts.getText("optimizer.movesHighestPushesOptimizationMethod");
            }
        },
        /**
         * Optimize for box lines, then for moves.
         */
        BOXLINES_MOVES {
            @Override
            public String toString() {
                return Texts.getText("optimizer.boxlinesMovesOptimizationMethod");
            }
        },
        /**
         * Optimize for box lines, then for pushes.
         */
        BOXLINES_PUSHES {
            @Override
            public String toString() {
                return Texts.getText("optimizer.boxlinesPushesOptimizationMethod");
            }
        },
        /**
         * Optimize for box lines.
         */
        BOXLINES {
            @Override
            public String toString() {
                return Texts.getText("boxLinesOnly");
            }
        },
        /**
         * Optimize for box changes, then moves.
         */
        BOXCHANGES_MOVES {
            @Override
            public String toString() {
                return Texts.getText("optimizer.boxChangesMovesOptimizationMethod");
            }
        },
        /**
         * Optimize for box changes, then pushes.
         */
        BOXCHANGES_PUSHES {
            @Override
            public String toString() {
                return Texts.getText("optimizer.boxChangesPushesOptimizationMethod");
            }
        },
        /**
         * Optimize for box changes.
         */
        BOXCHANGES {
            @Override
            public String toString() {
                return Texts.getText("boxChanges");
            }
        },
        PUSHINGSESSIONS_MOVES {
            @Override
            public String toString() {
                return Texts.getText("optimizer.pushingSessionsMovesOptimizationMethod");
            }
        },
        PUSHINGSESSIONS_PUSHES {
            @Override
            public String toString() {
                return Texts.getText("optimizer.pushingSessionsPushesOptimizationMethod");
            }
        },
        MAX_UNTOUCHED_BOXES {
            @Override
            public String toString() {
                return Texts.getText("optimizer.maxUntouchedBoxesOptimizationMethod");
            }
        },
        AVOID_MARKED_POSITIONS {
            @Override
            public String toString() {
                return Texts.getText("optimizer.avoidMarkedPositionsOptimizationMethod");
            }
        },
    }

    /**
     * Constants for the optimizer status.
     */
    enum OptimizerStatus {
        /**
         * Optimizer is actively running.
         */
        RUNNING,
        /**
         * Optimizer stopped for new iteration
         */
        STOPPED_FOR_IMMEDIATE_NEW_ITERATION,
        /**
         * Optimizer has finished.
         */
        ENDED,
        /**
         * Optimizer has stopped by the user.
         */
        STOPPED_BY_USER,
        /**
         * Optimizer has stopped due to a failure.
         */
        STOPPED_DUE_TO_FAILURE,
        /**
         * Optimizer has stopped due to no more RAM.
         */
        STOPPED_DUE_TO_OUT_OF_MEMORY
    }

    /**
     * Meaning: Nothing, none, empty.
     */
    final static public int NONE = -1;

    // A board of size 10*10 has 100 squares. Hence, to store whether there is a box/player
    // at a specific position 200 bits are needed. However, due to the fact that the boxes
    // and the player can't access every square (walls, deadlocks) the number of accessible
    // squares is smaller than 100. Hence, the total number of accessible squares for
    // the player and the total number of accessible squares for the boxes is determined.
    // Only the accessible squares are considered.
    // For instance: the player can only access 70 of the 100 squares. Then the bit array
    // would only contain 70 bits. To convert a external position (regarding all 100 squares)
    // to an internal position (regarding only 70 squares as relevant) these arrays are created.
    final int[] boxInternalToExternalPosition;
    final int[] boxExternalToInternalPosition;
    final int[] playerInternalToExternalPosition;
    final int[] playerExternalToInternalPosition;

    /**
     * Number of valid (= no deadlock) box positions on the board.
     */
    int boxPositionsCount;

    /**
     * Number of accessible player positions on the board (presumed no box is on the board).
     */
    int playerSquaresCount;

    /**
     * The initial board as it is passed to this class.
     */
    final OptimizerBoard initialBoard;

    // Due to the calculation of internal positions (see comment above) these
    // arrays are necessary. They store which box/player square is reachable in the 4
    final int[][] boxNeighbor = new int[4][];
    final int[][] playerSquareNeighbor = new int[4][];

    // Which internal player position corresponds to which internal box position.
    // playerPositionToBoxPosition[10] = 5; -> the 10th player accessible square
    // is the 5th box-accessible square on the board.
    final int[] playerPositionToBoxPosition;

    /**
     * The internal board, containing the level elements.
     */
    OptimizerBoard board;

    /**
     * Storage for the box configurations
     */
    private BoxConfigurationStorageHashSet boxConfigurationStorage;

    /**
     * Status of the optimizer (running, stopped, ...)
     * This status is frequently checked by the working thread of the optimizer,
     * since it is also used to stop it via {@link #stopOptimizer()}.
     */
    protected volatile OptimizerStatus optimizerStatus = OptimizerStatus.ENDED;

    /**
     * Used to identify deadlock box configurations.
     */
    private final DeadlockIdentification deadlockIdentification;

    private AllMetricsOptimizer optimizer = null;

    /**
     * The object which has started the optimizer and displays the GUI.
     * It is to be notified about new solutions, too.
     */
    final OptimizerGUISuperClass optimizerGUI;

    /**
     * ExecutorService for the box configuration generation.
     * Global variable for being able of calling "shutdownNow".
     */
    private ExecutorService executorGeneration;

    /**
     * Start parameters for the optimizer passed from the GUI
     */

    /**
     * The number of generated box configurations directly influences the RAM usage of the optimizer.
     * Due to possible RAM limitations, the user may manually set the maximum number of box configurations
     * to be generated in the GUI. The set value is saved in this variable. If it is "-1" the user hasn't
     * manually set a maximum.
     */
    private int userSetMaximumNoOfBoxConfigurations = NONE;

    /**
     * Flag, indicating whether the optimizer is to search for new solutions
     * until no better solutions are found. If this flag is set, the optimizer
     * uses a found new solution as seed for a new optimizing search
     * until a duplicate solution is found. While optimizing, the optimizer is
     * using the same settings that have been passed from the optimizer GUI.
     */
    private boolean isIterationEnabled = false;

    /**
     * Flag, indicating whether the iteration is to be stopped when
     * none of the moves or pushes value has changed in the last iteration.
     */
    private boolean isIterationStoppedWhenNoPrimaryMetricChanged;

    /**
     * Flag, indicating whether the iteration is to be stopped when
     * any improvement has been found. The solution is then returned
     * and a new iteration is started immediately.
     */
    private boolean immediateNewIterationWhenImprovement;


    /**
     * Flag, indicating whether the optimizer is to save only the last found solution.
     * If this flag is set to false, then the optimizer publishes all found solutions
     * in the GUI when using the iterating optimizing.
     */
    private boolean isOnlyLastSolutionToBeSaved = false;

    /**
     * Flag, indicating whether the optimizer has to retain the final position
     * of the player in the generated solutions.
     * This can be helpful if the user has split a level into sub-levels
     * and wants the sub-levels to be optimized independently.  To combine
     * two such sub-solutions into a larger complete solution, the final
     * position of the player in the sub-solutions may be important.
     */
    private boolean isPlayerEndPositionToBePreserved = false;

    /**
     * The Maximum number of CPUs the optimizer is allowed to use.
     */
    private int maxCPUsToBeUsedCount = 1;

    /**
     * The user can choose to only optimize a part of the solution, e.g. only
     * the part from push 40 through push 100.
     * In this case the optimizer internally will create a kind of dummy level
     * from this pushes range, and optimize that.  However, the user should
     * see moves and pushes numbers according to the original solution / level,
     * and not according to this internal setup.  Therefore, all data displayed
     * for the user has to add these prefix/suffix values to the computed
     * internal metrics.
     */
    private int prefixMovesCount, prefixPushesCount, postfixMovesCount, postfixPushesCount = 0;
    private int prefixAxisOfLastPush;

    // Number of vicinity squares used for generating deviation box configurations.
    protected int[] numberOfVicinitySquaresABoxMayEnter;

    // Squares the boxes may be repositioned to. The user may restrict the squares to specific squares.
    private boolean[] relevantBoxPositions = null;

    // Positions the player must not enter. This is used by the UnusedFloorsFinder.
    private boolean[] isForbiddenExternalPosition = null;

    /**
     * The solutions to be used for searching for a better solution
     */
    private ArrayList<OptimizerSolution> basisSolutions;

    /**
     * Method of optimization: pushes/moves, moves/pushes, ...
     */
    private OptimizationMethod optimizingMethod;

    /**
     * Flag indicating whether the optimizer is to find the best vicinity settings instead of optimizing a solution.
     */
    private boolean isOptimizerToFindBestVicinitySettings = false;

    /**
     * Thread this optimizer instance is running in (used to call "interrupt").
     */
    Thread mainOptimizerThread;


    /**
     * Creates an object for optimizing a solution.<br>
     * The created optimizer automatically starts a new thread which identifies deadlocks.
     * However, if another optimizer instance is passed, then the deadlocks from that optimizer are used.
     * This is useful when only a pushes range of a specific solution is to be optimized because the deadlocks
     * then can be reused to some degree and don't have to be recalculated.
     *
     * @param mainBoard                       the main board of the game
     * @param optimizerGUI                    the GUI for this optimizer
     * @param optimizerForDeadlockInheritance optimizer the deadlocks should be taken from
     */
    public Optimizer(Board mainBoard, OptimizerGUISuperClass optimizerGUI, Optimizer optimizerForDeadlockInheritance, int maxCPUsToBeUsedCount) {

        this.optimizerGUI = optimizerGUI;

        // Convert the board into the internal optimizer board format.
        board = new OptimizerBoard(mainBoard.width, mainBoard.height, mainBoard.playerPosition);
        for (int square = 0; square < board.size; square++) {
            if (mainBoard.isOuterSquareOrWall(square)) {
                board.setWall(square);
            }
            if (mainBoard.isBox(square)) {
                board.setBox(square);
            }
            if (mainBoard.isGoal(square)) {
                board.setGoal(square);
            }
            if (mainBoard.isSimpleDeadlockSquare(square) || mainBoard.isAdvancedSimpleDeadlockSquareForwards(square)) {
                board.setDeadlock(square);
            }

            /*
             * Count the box-accessible squares and the player accessible squares.
             */
            if (!board.isWallOrDeadlock(square)) {
                boxPositionsCount++;
            }
            if (!board.isWall(square)) {
                playerSquaresCount++;
            }
        }

        // Arrays for converting between internal position and external position (internal = only valid squares).
        // Actually, the "XY" isn't really coordinates in a 2-dimensional array; it's a (y*width+x) index into a 1-dimensional vector
        playerInternalToExternalPosition = new int[playerSquaresCount];
        boxInternalToExternalPosition = new int[boxPositionsCount];
        playerPositionToBoxPosition = new int[playerSquaresCount];
        playerExternalToInternalPosition = new int[board.size];
        boxExternalToInternalPosition = new int[board.size];

        // Fill the converting arrays: external <-> internal square positions.
        for (int externalPosition = 0, internalBoxPosition = 0, internalPlayerPosition = 0; externalPosition < board.size; externalPosition++) {

            // As start the squares are not reachable for player / box. -1 = inaccessible
            boxExternalToInternalPosition[externalPosition] = NONE;
            playerExternalToInternalPosition[externalPosition] = NONE;

            // Check if the position is accessible for a box.
            if (!board.isWallOrDeadlock(externalPosition)) {
                boxInternalToExternalPosition[internalBoxPosition] = externalPosition;
                boxExternalToInternalPosition[externalPosition] = internalBoxPosition++;
            }

            // Check if the position is accessible for the player. (The player
            // can enter every square except of wall and exterior squares).
            if (!board.isWall(externalPosition)) {

                playerInternalToExternalPosition[internalPlayerPosition] = externalPosition;
                playerExternalToInternalPosition[externalPosition] = internalPlayerPosition;

                // If the square is also accessible (no deadlock, no wall, ...) for a box, then the square
                // is accessible for both. The array "playerPositionToBoxPosition" holds the information
                // which internal player position maps to which internal box position.
                // "internalBoxPosition - 1" because it has already been increased by 1.
                playerPositionToBoxPosition[internalPlayerPosition] =
                        boxExternalToInternalPosition[externalPosition] != NONE ? internalBoxPosition - 1 : NONE;

                internalPlayerPosition++;
            }
        }

        // * Fill the arrays holding the information: If a box on INTERNAL square
        // * position s1 is pushed to a direction d then the new INTERNAL square
        // * position is s2. If it isn't accessible for a box (wall or deadlock
        // * square) then NONE is returned.
        boxNeighbor[UP] = new int[boxPositionsCount];
        boxNeighbor[DOWN] = new int[boxPositionsCount];
        boxNeighbor[LEFT] = new int[boxPositionsCount];
        boxNeighbor[RIGHT] = new int[boxPositionsCount];

        for (int position = 0, externalBoxPosition; position < boxPositionsCount; position++) {

            externalBoxPosition = boxInternalToExternalPosition[position];

            if (boxInternalToExternalPosition[position] >= board.width) {
                boxNeighbor[UP][position] = boxExternalToInternalPosition[externalBoxPosition - board.width];
            } else {
                boxNeighbor[UP][position] = NONE;
            }

            if (boxInternalToExternalPosition[position] < (board.height - 1) * board.width) {
                boxNeighbor[DOWN][position] = boxExternalToInternalPosition[externalBoxPosition + board.width];
            } else {
                boxNeighbor[DOWN][position] = NONE;
            }

            if ((boxInternalToExternalPosition[position] % board.width) > 0) {
                boxNeighbor[LEFT][position] = boxExternalToInternalPosition[externalBoxPosition - 1];
            } else {
                boxNeighbor[LEFT][position] = NONE;
            }

            if ((boxInternalToExternalPosition[position] % board.width) < board.width - 1) {
                boxNeighbor[RIGHT][position] = boxExternalToInternalPosition[externalBoxPosition + 1];
            } else {
                boxNeighbor[RIGHT][position] = NONE;
            }
        }

        /*
         * Fill arrays holding the information: If the player on INTERNAL square
         * position x moves to a direction, then the new INTERNAL square position
         * (= the new player position) is y.
         */
        playerSquareNeighbor[UP] = new int[playerSquaresCount];
        playerSquareNeighbor[DOWN] = new int[playerSquaresCount];
        playerSquareNeighbor[LEFT] = new int[playerSquaresCount];
        playerSquareNeighbor[RIGHT] = new int[playerSquaresCount];

        for (int position = 0; position < playerSquaresCount; position++) {

            playerSquareNeighbor[UP][position] = playerInternalToExternalPosition[position] >= board.width ?
                    playerExternalToInternalPosition[playerInternalToExternalPosition[position] - board.width] : NONE;

            playerSquareNeighbor[DOWN][position] = playerInternalToExternalPosition[position] < (board.height - 1) * board.width ?
                    playerExternalToInternalPosition[playerInternalToExternalPosition[position] + board.width] : NONE;

            playerSquareNeighbor[LEFT][position] = (playerInternalToExternalPosition[position] % board.width) > 0 ?
                    playerExternalToInternalPosition[playerInternalToExternalPosition[position] - 1] : NONE;

            playerSquareNeighbor[RIGHT][position] = (playerInternalToExternalPosition[position] % board.width) < board.width - 1 ?
                    playerExternalToInternalPosition[playerInternalToExternalPosition[position] + 1] : NONE;
        }

        // Create a board object containing the initial board.
        initialBoard = board.getClone();

        // Identify and store deadlock box configurations.
        deadlockIdentification = new DeadlockIdentification(this, mainBoard, maxCPUsToBeUsedCount);

        if (optimizerForDeadlockInheritance != null) {
            deadlockIdentification.inheritDeadlockBoxConfigurations(optimizerForDeadlockInheritance.deadlockIdentification);
        } else {
            deadlockIdentification.identifyDeadlocksInExtraThread();
        }

//        Debug.debugApplication.board = mainBoard; // for debugging        // for debugging pushes ranges it's possible to set the main board
        // to the optimizer created board. This, however, means the real board
        // can't be used for playing anymore. Hence, this is only for debugging.
    }


    /**
     * Searches for the best solution in "the neighborhood" of the passed
     * solutions and returns the best found solution.
     * This method returns quickly, after starting a new thread performing the real search.
     *
     * @param numberOfSquaresABoxMayLeaveItsPath   array indicating how far some boxes may leave their positions, at most
     * @param relevantBoxSquares                   positions marked as relevant by the user
     * @param forbiddenPositions                   positions the player must not enter (used by the UnusedFloorFinder)
     * @param basisSolutions                       the solutions to be used for finding a new better solution
     * @param optimizingType                       type of optimization
     * @param maxBoxConfigurations                 maximum number of box configurations to be generated
     *                                             or "-1" if this number should be set automatically by the optimizer
     * @param isIterationEnabled                   whether the optimizer shall search for better solutions
     *                                             until no better solution is found using the current settings
     * @param immediateNewIterationWhenImprovement immediately start a new iteration when any improvement has been found
     * @param isOnlyLastSolutionToBeSaved          indicating whether only the last solution is saved in iterating mode or all solutions
     * @param isPlayerEndPositionFixed             flag indicating whether the player must end
     *                                             at the same location as in the basis solution
     * @param maxCPUsToBeUsedCount                 Maximum number of CPUs the optimizer may use
     * @param prefixMovesCount                     number of moves that have to be added to the solution at the beginning
     *                                             because the user optimizes only a part of the whole solution
     * @param prefixPushesCount                    number of pushes that have to be added to the solution at the beginning
     *                                             because the user optimizes only a part of the whole solution
     * @param suffixMovesCount                     number of moves  that have to be added to the solution at the end
     *                                             because the user optimizes only a part of the whole solution
     * @param suffixPushesCount                    number of pushes that have to be added to the solution at the end
     *                                             because the user optimizes only a part of the whole solution
     * @param prefixAxisOfLastPush                 the axis of the prefix push. This is needed to check whether the box line continues / the next pushed box is a box change
     * @return <code>true</code> if everything is ok, and
     * <code>false</code> if the optimizer is not started due to invalid parameters
     */
    public boolean startOptimizer(
            final int[] numberOfSquaresABoxMayLeaveItsPath,
            final boolean[] relevantBoxSquares,
            final boolean[] forbiddenPositions,
            final ArrayList<OptimizerSolution> basisSolutions,
            final OptimizationMethod optimizingType,
            final int maxBoxConfigurations,
            final boolean isIterationEnabled,
            final boolean isOnlyLastSolutionToBeSaved,
            final boolean isIterationStoppedWhenNoPrimaryMetricChanged,
            final boolean immediateNewIterationWhenImprovement,
            final boolean isPlayerEndPositionFixed,
            final int maxCPUsToBeUsedCount,
            final int prefixMovesCount,
            final int prefixPushesCount,
            final int suffixMovesCount,
            final int suffixPushesCount,
            final int prefixAxisOfLastPush) {

        // Check the passed parameters.
        if (numberOfSquaresABoxMayLeaveItsPath == null || basisSolutions == null) {
            return false;
        }

        // Set start parameter for the optimizer passed from the GUI.
        this.numberOfVicinitySquaresABoxMayEnter = numberOfSquaresABoxMayLeaveItsPath;
        this.basisSolutions = basisSolutions;
        this.optimizingMethod = optimizingType;
        this.userSetMaximumNoOfBoxConfigurations = maxBoxConfigurations;
        this.isIterationEnabled = isIterationEnabled;
        this.isIterationStoppedWhenNoPrimaryMetricChanged = isIterationStoppedWhenNoPrimaryMetricChanged;
        this.immediateNewIterationWhenImprovement = immediateNewIterationWhenImprovement;
        this.isOnlyLastSolutionToBeSaved = isOnlyLastSolutionToBeSaved;
        this.isPlayerEndPositionToBePreserved = isPlayerEndPositionFixed;
        this.maxCPUsToBeUsedCount = maxCPUsToBeUsedCount;
        this.prefixMovesCount = prefixMovesCount;
        this.prefixPushesCount = prefixPushesCount;
        this.postfixMovesCount = suffixMovesCount;
        this.postfixPushesCount = suffixPushesCount;
        this.prefixAxisOfLastPush = prefixAxisOfLastPush;

        // The user has selected the external positions in the GUI.
        // The square positions have to be converted to internal positions.
        this.relevantBoxPositions = new boolean[boxPositionsCount];
        for (int squareNo = 0; squareNo < boxPositionsCount; squareNo++) {
            this.relevantBoxPositions[squareNo] = relevantBoxSquares[boxInternalToExternalPosition[squareNo]];
        }

        this.isForbiddenExternalPosition = forbiddenPositions;

        // Create a new thread for the optimizer.
        mainOptimizerThread = new Thread(this, "main optimizer thread");
        mainOptimizerThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                stopOptimizer();
                optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_FAILURE;
                e.printStackTrace();
            }
        });

        // This thread should run in background, because it may take a lot of CPU power to optimize the solution.
        mainOptimizerThread.setPriority(Thread.MIN_PRIORITY);

        // The optimizer is to be stopped when the whole program is stopped.
        mainOptimizerThread.setDaemon(true);

        // Start the optimizer.  This invokes our "run()" method (see below).
        mainOptimizerThread.start();

        // Return "optimizer is started".
        return true;
    }

    /**
     * This method does the main work of the optimizer (in a separate thread).
     * Deadlock identification is done in a separate thread, started already
     * from the constructor, and may or may not be ready, while we here
     * are called indirectly from <code>startOptimizer</code>.
     *
     * @see java.lang.Thread#run()
     * @see #startOptimizer(int[], boolean[], boolean[], ArrayList, OptimizationMethod, int, boolean, boolean, boolean, boolean, boolean, int, int, int, int, int, int)
     */
    @Override
    public void run() {

        // Create a list for storing all found solutions.
        List<OptimizerSolution> foundSolutions = new ArrayList<>();

        // The best found solution.
        OptimizerSolution bestFoundSolution = null;

        // Set the initial status.
        optimizerStatus = RUNNING;

        // Determine the best solution of the passed solutions. It's the
        // solution that is used for optimizing.
        OptimizerSolution currentSolution = basisSolutions.get(0);
        for (OptimizerSolution solution : basisSolutions) {
            if (optimizingMethod == OptimizationMethod.MOVES_PUSHES || optimizingMethod == OptimizationMethod.MOVES_PUSHES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS) {
                if (solution.isBetterMovesPushesThan(currentSolution)) {
                    currentSolution = solution;
                }
            }
            if (optimizingMethod == OptimizationMethod.PUSHES_MOVES || optimizingMethod == OptimizationMethod.PUSHES_MOVES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS) {
                if (solution.isBetterPushesMovesThan(currentSolution)) {
                    currentSolution = solution;
                }
            }
            if (optimizingMethod == OptimizationMethod.MOVES_HIGHEST_PUSHES) {
                if (solution.isBetterMovesHighestPushesThan(currentSolution)) {
                    currentSolution = solution;
                }
            }
            if (optimizingMethod == OptimizationMethod.BOXLINES_PUSHES) {
                if (solution.isBetterBoxLinesPushesAllMetricsThan(currentSolution)) {
                    currentSolution = solution;
                }
            }
            if (optimizingMethod == OptimizationMethod.BOXLINES_MOVES) {
                if (solution.isBetterBoxLinesMovesAllMetricsThan(currentSolution)) {
                    currentSolution = solution;
                }
            }
            if (optimizingMethod == OptimizationMethod.BOXCHANGES_PUSHES) {
                if (solution.isBetterBoxChangesPushesThan(currentSolution)) {
                    currentSolution = solution;
                }
            }
            if (optimizingMethod == OptimizationMethod.BOXCHANGES_MOVES) {
                if (solution.isBetterBoxChangesMovesThan(currentSolution)) {
                    currentSolution = solution;
                }
            }
            if (optimizingMethod == OptimizationMethod.PUSHINGSESSIONS_MOVES) {
                if (solution.isBetterPushingSessionsMovesThan(currentSolution)) {
                    currentSolution = solution;
                }
            }
            if (optimizingMethod == OptimizationMethod.PUSHINGSESSIONS_PUSHES) {
                if (solution.isBetterPushingSessionsPushesThan(currentSolution)) {
                    currentSolution = solution;
                }
            }
            if (optimizingMethod == OptimizationMethod.MAX_UNTOUCHED_BOXES ||
                optimizingMethod == OptimizationMethod.AVOID_MARKED_POSITIONS) {
                currentSolution = solution; // just use any
            }

            // Add the solutions to the found solutions list. If one of them is
            // found again, the optimizer must be stopped to avoid a loop.
            foundSolutions.add(solution);
        }

        // The deadlock detection may still be running. The deadlocks are used
        // during the generation of box configurations. However, the optimizer
        // can just use the already generated deadlocks. Hence, stop
        // the deadlock identification thread.
        deadlockIdentification.stopDeadlockIdentificationThread();


        // The box configurations used to generate further box configurations for the search.
        List<BoxConfiguration> basisBoxConfigurations = new ArrayList<>();

        int iterationCount = 0;        // number of optimizing iterations
        long startTimeOptimizer = System.currentTimeMillis();

        long timeLastIteration = 0;   // Time for the last finished iteration

        // Optimize the solution. If "isIterationEnabled" is true, any new-found
        // solution is optimized, too, until a duplicate solution has been found.
        do {
            // Optimizer iteration body ...

            final OptimizerSolution oldBestSolution = currentSolution;    // backup the current best solution

            long startTimeIteration = System.currentTimeMillis();
            iterationCount++;

            // Set the start board position on the board for the new optimizing run.
            board = initialBoard.getClone();

            // Show the user how many solutions are to be optimized in the optimizer log.
            optimizerGUI.addLogText(Texts.getText("optimizer.xSelectedSolutions", basisSolutions.size()));

            // Add the box configurations of all solutions the user has selected to an own list.
            ArrayList<ArrayList<BoxConfiguration>> boxConfigurationsOfSolutions = new ArrayList<>();
            for (OptimizerSolution solution : basisSolutions) {
                boxConfigurationsOfSolutions.add(getBoxConfigurationsFromSolution(solution));
            }

            // The box configurations are now added to a set, ordered by pushes.
            // The user may choose more than one solution to generate
            // box configurations from. Therefore, a set is used to avoid duplicates.
            // The box configurations are ordered by pushes in the set,
            // because the generation of permutations may be done in several steps
            // due to insufficient available memory. In that case it's better when
            // the box configurations of the different solutions are mixed up
            // instead of having first the box configurations of the first solution,
            // then the one of the second solution and so on.
            LinkedHashSet<BoxConfiguration> uniqueBoxConfigurations = new LinkedHashSet<>();
            for (int boxConfigurationNo = 0; boxConfigurationsOfSolutions.size() > 0; boxConfigurationNo++) {

                // Add the "boxConfigurationNo"th box configuration of every solution.
                for (int listNo = 0; listNo < boxConfigurationsOfSolutions.size(); listNo++) {

                    // Get the box configuration list of the solution.
                    ArrayList<BoxConfiguration> boxConfigurationList = boxConfigurationsOfSolutions.get(listNo);

                    // If the current "solution" doesn't have that many box
                    //  configurations, delete it from the list and continue
                    // with the box configurations of the next solution.
                    if (boxConfigurationList.size() == boxConfigurationNo) {
                        boxConfigurationsOfSolutions.remove(listNo--);
                        continue;
                    }

                    // Add the box configuration to the set.
                    uniqueBoxConfigurations.add(boxConfigurationList.get(boxConfigurationNo));
                }
            }

            // It's more convenient to work with a list. Hence, add
            // the box configurations to a list.
            // This list is used for generating permutation box configurations.
            basisBoxConfigurations.clear(); // just to get sure it's empty
            basisBoxConfigurations.addAll(uniqueBoxConfigurations);

            // The optimizer may have to split the optimizing due to
            // insufficient memory. In this case, this loop ensures that the
            // optimization is done step by step. Hence, the optimizer
            // continues while there are box configurations left to generate
            // new box configurations from and the last run has found
            // a valid solution and the optimizer is still running.
            while (basisBoxConfigurations.size() > 0 && currentSolution != null && optimizerStatus == RUNNING) {

                long startTimeInterval = System.currentTimeMillis();

                try {
                    // Optimize the solution stored in "currentSolution".
                    // The new-found solution is immediately stored as a new basis solution for the next run.
                    // =====>>> The MAIN WORK is done HERE.
                    currentSolution = searchForBetterSolution(currentSolution, basisBoxConfigurations);

                    // If the optimizer has run out of memory
                    if (optimizerStatus == OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY) {
                        optimizerStatus = RUNNING;

                        // Inform the user about error.
                        optimizerGUI.addLogText(Texts.getText("optimizer.stoppedDueToOutOfMemory", 70));

                        // Reduce the maximum number of box configurations
                        // to be generated to 70%.
                        // For this, the "user set" variable is used
                        // because it overrules the calculated value.
                        userSetMaximumNoOfBoxConfigurations = (int) (boxConfigurationStorage.getCapacity() * 0.7);
                    }

                } catch (OutOfMemoryError e) {
                    // Display a message so the user can react to the error.
                    optimizerGUI.uncaughtException(Thread.currentThread(), e);
                    JOptionPane.showMessageDialog(optimizerGUI, Texts.getText("optimizer.boxConfigurationCountTooHigh"), Texts.getText("note"), JOptionPane.WARNING_MESSAGE);
                    break;
                } finally {
                    // Delete the huge array for freeing RAM.
                    boxConfigurationStorage = null;
                    System.gc();

                    // Save the new solution as the best found solution. The user may stop
                    // the optimizer, and if the optimizing was split due to insufficient
                    //  memory, the best found solution so far has to be saved.
                    if (currentSolution != null) {
                        bestFoundSolution = currentSolution;
                    }
                }


                if (currentSolution != null && optimizerStatus == RUNNING && Debug.isDebugModeActivated) {
                    long duration = (System.currentTimeMillis() - startTimeInterval) / 1000;

                    optimizerGUI.addLogTextDebug("\n\nZeit letztes Intervall: " + duration + "s");

                    if (isIterationEnabled) {
                        long iterationTime = (System.currentTimeMillis() - startTimeIteration) / 1000;
                        optimizerGUI.addLogTextDebug("Zeit insgesamt seit Start von Iteration Nr. " + iterationCount + ": " + iterationTime + "s");
                    }

                    long timeTotalForOptimizing = (System.currentTimeMillis() - startTimeOptimizer) / 1000;
                    float hours = timeTotalForOptimizing / 3600;
                    float minutes = (timeTotalForOptimizing % 3600) / 60;
                    float seconds = timeTotalForOptimizing % 60;
                    String timeString = String.format(Locale.GERMAN, "%2.0fh %2.0fmin %2.0fs", hours, minutes, seconds);
                    optimizerGUI.addLogTextDebug("Gesamtzeit seit Start Optimierer: " + timeTotalForOptimizing + "s  [" + timeString + "]");


                    if (timeLastIteration > 0) {
                        optimizerGUI.addLogTextDebug("Zur Info: Gesamtzeit Iteration Nr. " + (iterationCount - 1) + ": " + timeLastIteration + "s");
                    }

                    optimizerGUI.addLogTextDebug("\n\n");

                }
            }

            if (isIterationEnabled && Debug.isDebugModeActivated) {
                long iterationTime = (System.currentTimeMillis() - startTimeIteration) / 1000;
                optimizerGUI.addLogTextDebug("Iteration " + iterationCount + " abgeschlossen. Zeit: " + iterationTime + "s");

                timeLastIteration = iterationTime;

                long timeTotalForOptimizing = (System.currentTimeMillis() - startTimeOptimizer) / 1000;
                float hours = timeTotalForOptimizing / 3600;
                float minutes = (timeTotalForOptimizing % 3600) / 60;
                float seconds = timeTotalForOptimizing % 60;
                String timeString = String.format(Locale.GERMAN, "%2.0fh %2.0fmin %2.0fs", hours, minutes, seconds);
                optimizerGUI.addLogTextDebug("Gesamtzeit seit Start Optimierer: " + timeTotalForOptimizing + "s  [" + timeString + "]\n\n");
            }

            // If no solution has been found (optimizer stopped by user, ...)
            // or the found solution has already been found earlier, stop the optimizer.
            if (currentSolution == null || foundSolutions.contains(currentSolution)) {
                break;
            }

            // Add the found solution to the found solutions list. If it is found again,
            // the optimizer must be stopped to avoid a loop. Therefore, we have to remember it.
            foundSolutions.add(currentSolution);

            // Save the new solution as the best found solution.
            bestFoundSolution = currentSolution;

            // Show the metrics of the solution in the optimizer log.
            optimizerGUI.addLogText(
                    "\n" + Texts.getText("foundSolution")
                            + " " + (prefixMovesCount + currentSolution.movesCount + postfixMovesCount)
                            + " " + Texts.getText("moves")
                            + ", " + (prefixPushesCount + currentSolution.pushesCount + postfixPushesCount)
                            + " " + Texts.getText("pushes"));

            // Make some space before the log of the next iteration begins.
            optimizerGUI.addLogText("\n\n");

            if (isIterationStoppedWhenNoPrimaryMetricChanged) {       // Immediately return the current best solution when no optimized metric has changed
                if (!isSolution1BetterAccordingToOptimizingMethod(currentSolution, oldBestSolution)) {
                    break;
                }
            }

            // The GUI is informed about the solutions to be optimized next.
            // Even in case several solutions have been selected for optimization,
            // the old best solution is removed and the new solution added.
            // The new-found solution is most likely very similar to the old best solution,
            // hence we keep the other solutions and just remove the old best solution.
            // (see mail suggestion from JPM 30.03.2021)
            basisSolutions.remove(oldBestSolution);
            basisSolutions.add(currentSolution);

            // Inform the GUI about the new solution.
            if (isIterationEnabled && !isOnlyLastSolutionToBeSaved) {
                optimizerGUI.newFoundSolution(currentSolution, basisSolutions);
            }

        } while (optimizerStatus == RUNNING && isIterationEnabled);

        // Inform the GUI about the new status "optimizer ended"
        // and pass the best found solution.
        optimizerGUI.optimizerEnded(bestFoundSolution);

        // Set a new status.
        optimizerStatus = OptimizerStatus.ENDED;

    }

    private boolean isSolution1BetterAccordingToOptimizingMethod(OptimizerSolution solution1, OptimizerSolution solution2) {

        switch (optimizingMethod) {
            case MOVES_PUSHES:
                return solution1.isBetterMovesPushesThan(solution2);
            case MOVES_PUSHES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS:
                return solution1.isBetterMovesPushesAllMetricsThan(solution2);
            case PUSHES_MOVES:
                return solution1.isBetterPushesMovesThan(solution2);
            case PUSHES_MOVES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS:
                return solution1.isBetterPushesMovesAllMetricsThan(solution2);
            case MOVES_HIGHEST_PUSHES:
                return solution1.isBetterMovesHighestPushesThan(solution2);
            case BOXLINES_PUSHES:
                return solution1.isBetterBoxLinesPushesAllMetricsThan(solution2);
            case BOXLINES_MOVES:
                return solution1.isBetterBoxLinesMovesAllMetricsThan(solution2);
            case BOXCHANGES_PUSHES:
                return solution1.isBetterBoxChangesPushesThan(solution2);
            case BOXCHANGES_MOVES:
                return solution1.isBetterBoxChangesMovesThan(solution2);
            case PUSHINGSESSIONS_MOVES:
                return solution1.isBetterPushingSessionsMovesThan(solution2);
            case PUSHINGSESSIONS_PUSHES:
                return solution1.isBetterPushingSessionsPushesThan(solution2);
            default:
                return false;
        }
    }

    /**
     * Forces this optimizer to stop.
     */
    public void stopOptimizer() {

        // Stop the deadlock identification thread which may still be running.
        deadlockIdentification.stopDeadlockIdentificationThread();

        // The optimizer hasn't to be stopped when it isn't running anymore.
        if (optimizerStatus != RUNNING) {
            return;
        }

        // This stops the optimizer. All methods must check this status continuously.
        // The optimizer thread is not interrupted but ends "normally".
        optimizerStatus = OptimizerStatus.STOPPED_BY_USER;

        // Stop all tasks that are running for generating new box configurations.
        if (executorGeneration != null) {
            executorGeneration.shutdownNow();
        }

        // Interrupt this thread and all dependent threads (in the executors) so the optimizer can stop very soon.
        mainOptimizerThread.interrupt();
    }

    /**
     * Generates box configuration "near" to the basis solution and searches
     * the best possible solution by doing a Breath First Search over all
     * generated box configurations. The found solution is returned.
     *
     * @param currentBestSolution    the currently best found solution
     * @param basisBoxConfigurations the box configurations taken for generating permutation box configurations. The used ones are removed.
     * @return the best found solution
     */
    private OptimizerSolution searchForBetterSolution(
            final OptimizerSolution currentBestSolution,
            final List<BoxConfiguration> basisBoxConfigurations) {

        // The optimized solution that is returned.
        OptimizerSolution sol = new OptimizerSolution();

        // Set the start board position on the board for the new optimizing run.
        board = initialBoard.getClone();

        // Calculate the maximum number of box configurations that may be generated.
        int maxBoxConfigurationsCount = getMaxBoxConfigurationCount(currentBestSolution);

        // Display the calculated number of box configurations in the log.
        optimizerGUI.addLogText("\n" + Texts.getText("optimizer.maxBoxConfigurationsSetTo", maxBoxConfigurationsCount));

        // Create a storage for the box configurations.
        boxConfigurationStorage = new BoxConfigurationStorageHashSet(this, maxBoxConfigurationsCount, boxPositionsCount);

        // DEBUG: special method for internal use.
        if (isOptimizerToFindBestVicinitySettings) {
            new BoxConfigurationGeneration().getOptimalVicinitySettings(maxBoxConfigurationsCount, basisBoxConfigurations);
            return null;
        }

        // Check whether the optimizer is still running.
        if (optimizerStatus != RUNNING) {
            boxConfigurationStorage = null;
            return null;
        }

        // Fill ArrayData with all box configurations "near" to the box configurations that occur in the passed solution.
        new BoxConfigurationGeneration().generatePermutationBoxConfigurations(currentBestSolution, basisBoxConfigurations);

        // Check whether the optimizer is still running.
        if (optimizerStatus != RUNNING) {
            boxConfigurationStorage = null;
            return null;
        }

        // Show the number of generated box configurations.
        optimizerGUI.addLogText(Texts.getText("optimizer.generatedBoxConfigurationsCount", boxConfigurationStorage.getSize()));
        deadlockIdentification.debugShowStatistics();

        // Do all moves of the current best solution to create the "end board" where all boxes are on goals.
        OptimizerBoard endBoard = initialBoard.getClone();
        for (int direction : currentBestSolution.solution) {
            endBoard.doMovement(direction);
        }

        // Add log entry showing that the search begins.
        optimizerGUI.addLogText("\n" + Texts.getText("optimizer.searchBegins"));

        long begtime = System.currentTimeMillis();

        // Optimize the solution using the requested optimization method.
        switch (optimizingMethod) {

            case MOVES_PUSHES:
                optimizer = new MovesPushesOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case PUSHES_MOVES:
                optimizer = new PushesMovesOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case PUSHES_MOVES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS:
                optimizer = new PushesMovesAllMetricsOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case MOVES_PUSHES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS:
                optimizer = new MovesPushesAllMetricsOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case MOVES_HIGHEST_PUSHES:
                optimizer = new MovesHighestPushesOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case BOXLINES_PUSHES:
                optimizer = new BoxLinesPushesOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case BOXLINES_MOVES:
                optimizer = new BoxLinesMovesOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case BOXLINES:
                sol = findBestSolutionPathBoxLines(initialBoard, endBoard, currentBestSolution);
                break;

            case BOXCHANGES_MOVES:
                optimizer = new BoxChangesMovesOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case BOXCHANGES_PUSHES:
                optimizer = new BoxChangesPushesOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case PUSHINGSESSIONS_MOVES:
                optimizer = new PushingSessionsMovesOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case PUSHINGSESSIONS_PUSHES:
                optimizer = new PushingSessionsPushesOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case MAX_UNTOUCHED_BOXES:
                optimizer = new MaximumUntouchedBoxesOptimizer();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;

            case AVOID_MARKED_POSITIONS:
                optimizer = new UnusedFloorsFinder();
                sol = optimizer.findBestSolutionPath(initialBoard, endBoard, currentBestSolution);
                break;
        }

        if (Debug.isDebugModeActivated) { // DEBUG ONLY: display time for vicinity search
            long didmillis = System.currentTimeMillis() - begtime;
            optimizerGUI.addLogTextDebug(String.format("Time for vicinity search: %dms\n", didmillis));
        }

        // Try to free RAM.
        System.gc();

        optimizer = null;

        // Return the found solution (or "null" when no solution has been found).
        return sol;
    }

    /**
     * Returns whether the optimizer is still running.
     *
     * @return <code>true</code> the optimizer is still running, <code>false</code> otherwise (for instance user has stopped the optimizer)
     */
    public boolean isOptimizerRunning() {
        return optimizerStatus == RUNNING;
    }

    /**
     * Returns whether the optimizer has ended normally that is:
     * * not stopped by the user
     * * no failure occured
     *
     * @return <code>true</code> the optimizer has ended normally, <code>false</code> otherwise (for instance user has stopped the optimizer)
     */
    public boolean hasOptimizerEndedNormally() {
        return optimizerStatus == OptimizerStatus.ENDED;
    }

    /**
     * Calculates and returns the maximum number of box configurations to be generated.
     * <p>.
     * Depending on the internal data structures and the available RAM the optimizer may restrict
     * the number of box configurations to be generated. This method calculates the maximum number
     * of box configurations that may be used in order not to cause an out-of-memory error or
     * internal data overflow.
     *
     * @param currentBestSolution the best of the solutions to be optimized
     * @return maximum number of box configurations to be generated
     */
    private int getMaxBoxConfigurationCount(final OptimizerSolution currentBestSolution) {

        long maxBoxConfigurationsCount = userSetMaximumNoOfBoxConfigurations;

        // The "all metrics" methods can handle more RAM than the other methods.
        // Hence, the RAM is calculated in a different way.
        if (optimizingMethod == OptimizationMethod.PUSHES_MOVES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS ||
                optimizingMethod == OptimizationMethod.MOVES_PUSHES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS ||
                optimizingMethod == OptimizationMethod.PUSHES_MOVES ||
                optimizingMethod == OptimizationMethod.MOVES_PUSHES ||
                optimizingMethod == OptimizationMethod.MOVES_HIGHEST_PUSHES ||
                optimizingMethod == OptimizationMethod.BOXLINES_MOVES |
                optimizingMethod == OptimizationMethod.BOXLINES_PUSHES ||
                optimizingMethod == OptimizationMethod.BOXCHANGES_MOVES ||
                optimizingMethod == OptimizationMethod.BOXCHANGES_PUSHES ||
                optimizingMethod == OptimizationMethod.PUSHINGSESSIONS_MOVES ||
                optimizingMethod == OptimizationMethod.PUSHINGSESSIONS_PUSHES ||
                optimizingMethod == OptimizationMethod.MAX_UNTOUCHED_BOXES ||
                optimizingMethod == OptimizationMethod.AVOID_MARKED_POSITIONS) {

            // The max number of box configurations is the important value for the RAM usage.
            // The higher this value, the more box configurations are generated and the more board positions are
            // visited during the vicinity search (and have to be stored in a storage and open queues).

            // Calculate the ram per box configuration in the box configuration storage:
            // 5 bytes for hash table, rest for box configuration data (see class: BoxConfigurationStorageHashSet).
            final int bytesPerBoxConfiguration = 5 + (boxPositionsCount - 1) / 8 + 1;

            // Calculate the RAM usage per board position in the storage. This depends on how many moves the solution has. Since the moves can
            // even increase while optimizing for pushes we have to estimate the maximal moves - we take 3 times the number of moves of the solution.
            int maxSecondaryMetric = 0;
            if (optimizingMethod == OptimizationMethod.PUSHES_MOVES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS ||
                    optimizingMethod == OptimizationMethod.PUSHES_MOVES) {
                maxSecondaryMetric = PushesMovesAllMetricsOptimizer.MAXIMAL_MOVES_FACTOR * currentBestSolution.movesCount;
            } else {
                maxSecondaryMetric = MovesPushesAllMetricsOptimizer.MAXIMAL_PUSHES_FACTOR * currentBestSolution.pushesCount;
            }

            final int bytesPerBoardPosition = 4 * BoardPositionsStorage.getStorageSizePerBoardPositionInInts(maxSecondaryMetric);

            // The number of board positions per box configuration heavily depends on the level. Usually it's not higher than 6.
            // Hence, 6 is taken as upper bound. That means on average 6 board positions may be visited for every box configuration.
            // However, when optimizing with more than 2 boxes 7 is a safer value to not use too much RAM:
            final int boardPositionsPerBoxConfiguration = numberOfVicinitySquaresABoxMayEnter.length > 2 ? 7 : 6;

            // Calculate the total ram usage per box configuration for both storages:
            // bytesPerBoxConfiguration  for storing a box configuration in the box configuration storage
            // 4 bytes per reference to a BoardPositionsArray
            // 32 bytes per BoardPositionsArray object itself (see class: BoardPositionsStorage)
            // "bytesPerBoardPosition" for every board position (which upper bound number is estimated to be 6 times the number of box configurations)
            // This calculation isn't very exact due to variations between 32bit vs. 64bit, alignments, ...
            final int totalRAMPerBoxConfigurationInBytes = bytesPerBoxConfiguration + 4 + 32 + boardPositionsPerBoxConfiguration * bytesPerBoardPosition;

            // Calculate the maximum number of box configurations that are to be generated. We reserve 1/5 of RAM and 80 MiB (for system having very few
            // available RAM where 1/5 is too few) for open queues and other things (like false estimation of totalRAMPerBoxConfigurationInBytes.
            // If the user has set the maximum manually, this value is taken in every case.
            if (userSetMaximumNoOfBoxConfigurations == NONE) {
                maxBoxConfigurationsCount = (Utilities.getMaxUsableRAMInBytes() * 4 / 5 - 80 * 1024 * 1024) / totalRAMPerBoxConfigurationInBytes;
            }
        } else {
            // The needed RAM can't be calculated in advance because it is impossible to calculate the number of board positions that will
            // be visited in the vicinity search. A rough calculation is:
            // The vicinity search starts with two queues, each 4 MiB.
            // BoxConfiguration storage hash table:
            //  -- 5 bytes for the hash table (1,25 * 4 bytes for an integer)
            //  -- and the data usage for every box configuration.
            // Visited data array: one bit for every player-reachable square (two bits for box lines optimizer)
            // The remaining RAM is divided by 3.
            // If the user has manually set a maximum, this maximum is used.
            if (maxBoxConfigurationsCount == -1) {
                long spareRAM = 80 * 1024 * 1024; // reserve 80 MiB to avoid out of memory
                long useRAM = Utilities.getMaxUsableRAMInBytes() - spareRAM;
                int ramPerBoxConfVisitedFlags = optimizingMethod == OptimizationMethod.BOXLINES ? 2 * playerSquaresCount / 8 : playerSquaresCount / 8;
                int ramPerBoxConf = (5 + ((boxPositionsCount - 1) / 8 + 1) + ramPerBoxConfVisitedFlags);
                maxBoxConfigurationsCount = useRAM / ramPerBoxConf / 3;
            }

            // Avoid integer overflows.
            if (maxBoxConfigurationsCount > Integer.MAX_VALUE / playerSquaresCount) {
                maxBoxConfigurationsCount = Integer.MAX_VALUE / playerSquaresCount;
            }
        }

        // The optimizer must at least be able to store all box configurations of the basis solution.
        // Otherwise, it can't find a new path to the end board position.
        // To let the optimizer have some RAM for optimizing twice the needed size is used as the minimum.
        if (maxBoxConfigurationsCount < currentBestSolution.pushesCount * 2) {
            throw new OutOfMemoryError();
        }

        // The storage has an internal maximal capacity.
        if (maxBoxConfigurationsCount > BoxConfigurationStorageHashSet.MAX_CAPACITY) {
            maxBoxConfigurationsCount = BoxConfigurationStorageHashSet.MAX_CAPACITY;
        }

        if (optimizingMethod == OptimizationMethod.BOXLINES) {
            maxBoxConfigurationsCount = Math.min(maxBoxConfigurationsCount, Integer.MAX_VALUE / 2 / (playerSquaresCount + 1) - 8); // maximum number of box configurations so the BitVector can still be used for marking reached ones
        }

        return (int) maxBoxConfigurationsCount;
    }

    /* Returns the best found solution so far. */
    public OptimizerSolution getBestFoundSolutionSoFar() {
        return optimizer == null ? null : optimizer.getBestFoundSolution();
    }

    /**
     * Class for generating box configurations.
     * <p>
     * A box configuration contains the information where the boxes on the board are.<br>
     * Every push creates another box configuration than the one before.<br>
     * This method takes all box configurations in the solution to be optimized as basis.<br>
     * For each of them further box configurations are generated and saved by moving some
     * boxes in them.<br>
     * <p>
     * Example:<br>
     * after 5 pushes the box configuration of the solution may contain the following box positions:<br>
     * [10, 17, 23, 42, 60]<br>
     * This box configuration is now used to generate new ones.<br>
     * The user may have set (in the optimizer GUI) that one box may be repositioned.<br>
     * Hence, the following box configurations may be generated:<br>
     * - [ 5, 17, 23, 42, 60]<br>
     * - [16, 17, 23, 42, 60]<br>
     * - [23, 17, 23, 42, 60]<br>
     * - [10, 49, 23, 42, 60]<br>
     * - [10, 17, 23, 81, 60]<br>
     * ...<br>
     * It's always only one box that is moved.<br>
     * The user can restrict the number of boxes to be moved and the positions
     * to be used for moving boxes.
     */
    private class BoxConfigurationGeneration {

        /**
         * Generates all box configurations that are in the vicinity of the box
         * configurations passed in the list "basisBoxConfigurations". Thereby, the
         * limits set by the user are taken into account.
         * <p>
         * Box configurations that have been successfully used for generating
         * new box configurations are removed from {@code basisBoxConfigurations}.
         *
         * @param currentBestSolution    the currently best found solution
         * @param basisBoxConfigurations the box configurations taken for generating permutation box
         *                               configurations. The used ones are removed.
         */
        private void generatePermutationBoxConfigurations(final OptimizerSolution currentBestSolution, final List<BoxConfiguration> basisBoxConfigurations) {

            // Determine and save the nearest box-accessible squares for every square.
            int[][] reachablePositions = determineAllAccessiblePositions();

            // Time measuring.
            long time = System.currentTimeMillis();

            // Create an executor for the box configuration generation.
            executorGeneration = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);

            List<Future> generationTasks = new ArrayList<>(currentBestSolution.pushesCount);

            // Display a status message for the first step (if a high depth is used it's possible that the first display
            // of a message otherwise would take a rather long time which results in the deadlock identifying method to be shown
            // for quite a long time although the generation phase has already been started). The generation internally always starts with
            // push 0. However, when the user selects to optimize the pushes range 40 - 60 then the display starts with 40 so the user isn't confused.
            optimizerGUI.setInfoText(Texts.getText("generatingBoxConfigurations") + "0%");

            // Set a log entry.
            optimizerGUI.addLogText("\n" + Texts.getText("optimizer.boxConfigurationGenerationStarted", basisBoxConfigurations.size()));

            // The box configurations of the current best solution are added at once. This ensures all box configurations of the
            // solution are added, even in the case that the hash table is "full" at some time while generating. This way the vicinity
            // search can always find a path to solve the level by using the added box configurations.
            for (BoxConfiguration boxConfiguration : getBoxConfigurationsFromSolution(currentBestSolution)) {
                boxConfigurationStorage.add(boxConfiguration);
            }

            // Generate permutation box configurations from all passed basis box configurations.
            for (BoxConfiguration boxConfiguration : basisBoxConfigurations) {

                if (optimizerStatus != RUNNING) {
                    break;
                }

                // The basis box configuration is added immediately. This ensures all basis box configurations are added,
                // even in the case that the hash table is "full" at some time later while generating. These basis box configurations are
                // important for the vicinity search because they are known to be part of a valid solution path. Hence, reaching such
                // a box configuration may result in a new path to solve the level.
                // So adding the basis box configurations here may result in slightly better results in the vicinity search.
                boxConfigurationStorage.add(boxConfiguration);

                // Generate permutation box configurations in a new task.
                BoxConfigurationGenerator generation = new BoxConfigurationGenerator(
                        numberOfVicinitySquaresABoxMayEnter, reachablePositions, boxConfiguration, boxConfigurationStorage, deadlockIdentification);


                // Add the generation step to the executor for execution and collect the "future" objects in a list. This may throw a RejectedExecutionException.
                try {
                    generationTasks.add(executorGeneration.submit(generation));
                } catch (RejectedExecutionException e) {
                    // Exception is thrown when the user stops the optimizer and this coding still tries to submit new tasks.
                    // In this case the generation has to be stopped, too.
                    if (optimizerStatus != OptimizerStatus.STOPPED_BY_USER) {
                        optimizerStatus = OptimizerStatus.STOPPED_BY_USER;
                        if (Debug.isDebugModeActivated) {
                            optimizerGUI.addLogTextDebug("Failure: optimizer running but rejected execution");
                        }
                    }
                    return;
                }
            }

            // All tasks have been submitted. The executor can shutdown.
            executorGeneration.shutdown();

            try {
                // Number of successfully executed generation tasks.
                int successfullGenerationTasksCount = 0;

                // Wait for every generation step having finished and display a message after every finished step.
                for (Future<BoxConfigurationGenerator> future : generationTasks) {

                    // Wait for the next generation task having finished. The task throws an ExecutionException when:
                    // - the user stopping the optimizer - then the whole optimizer is stopped)
                    // - the storage being too small - then the optimizing is done using only the already generated box configurations
                    // - other exceptions are thrown while generating - then the optimizing is done using only the already generated box configurations
                    try {
                        future.get();
                    } catch (ExecutionException e) {
                        // The first unsuccessful generation stops the whole generation.
                        executorGeneration.shutdownNow();

                        // The optimizer will start a new run using the remaining box configurations.
                        // To avoid endless loops one box configuration is removed in any case.
                        if (successfullGenerationTasksCount == 0) {
                            basisBoxConfigurations.remove(0);
                        }

                        break;

                    }

                    basisBoxConfigurations.remove(0); // Successful generated, hence don't use this box configuration for the next run
                    successfullGenerationTasksCount++;

                    // Display a status message after every completed generation step.
                    optimizerGUI.setInfoText(Texts.getText("generatingBoxConfigurations") + (successfullGenerationTasksCount * 100 / generationTasks.size()) + "%");
                }

                // If optimization has to be split into several parts, inform the user about this.
                if (basisBoxConfigurations.size() != 0) {
                    optimizerGUI.addLogText(Texts.getText("optimizer.splitOfSolution", basisBoxConfigurations.size()));
                }

                // The storage can optimize its size because at this moment the total number of box configurations is known.
                boxConfigurationStorage.optimizeSize();

                // Show the time needed for generating the box configurations.
                optimizerGUI.addLogText(Texts.getText("optimizer.generationFinished", (System.currentTimeMillis() - time) / 1000f));

            } catch (OutOfMemoryError e) {
                if (Debug.isDebugModeActivated) {
                    e.printStackTrace();
                }
                throw (e);
            } catch (InterruptedException e) {
                optimizerStatus = OptimizerStatus.STOPPED_BY_USER;
                executorGeneration.shutdownNow();
            } catch (Exception e) {
                // Catch all other possible Exceptions (ExecutionException, RejectedExecutionException, ...)
                if (Debug.isDebugModeActivated) {
                    e.printStackTrace();
                }
                optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_FAILURE;
                executorGeneration.shutdownNow();
            }

        }

        /**
         * DEBUG: this method is only used in debug mode.
         * <p>.
         * Finds the vicinity settings as high as possible that will not generate more box configurations
         * than the passed maximum number of box configurations.<br>
         * If two boxes may be repositioned during the generation, only the settings of the second box
         * are adjusted by this method.
         * If three boxes may be repositioned during the generation, only the settings of the third box
         * are adjusted by this method.
         *
         * @param maxBoxConfigurationsCount maximum number of box configurations to be generated
         * @param basisBoxConfigurations    the box configurations to generate new box configurations from
         */
        private void getOptimalVicinitySettings(final long maxBoxConfigurationsCount, final List<BoxConfiguration> basisBoxConfigurations) {

            if (numberOfVicinitySquaresABoxMayEnter == null) {
                return;
            }

            // Determine and save the nearest box-accessible squares for every square.
            final int[][] reachablePositions = determineAllAccessiblePositions();

            // Executor for generating box configurations in multiple threads.
            executorGeneration = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);

            // If the user has set only one vicinity setting then the best value for this setting is searched.
            // If the user has set two vicinity settings then the best value for the second setting is searched.
            int boxSettingToFind = numberOfVicinitySquaresABoxMayEnter.length - 1;

            // All values that are not changed by this method are always to be written to the log -> create a proper string for this.
            String firstVicinityValues = "";
            if (boxSettingToFind >= 1) {
                firstVicinityValues += (numberOfVicinitySquaresABoxMayEnter[0] - 1) + "/";
            }
            if (boxSettingToFind == 2) {
                firstVicinityValues += (numberOfVicinitySquaresABoxMayEnter[1] - 1) + "/";
            }


            // List of all generating threads.
            List<Future> generationTasks = new ArrayList<>(6);

            optimizerGUI.setInfoText("Ermittle beste Werte für Umgebungsfelder");
            optimizerGUI.addLogTextDebug("\nUngefähre Ermittlung der besten Settings:");

            int minSetting = 1;        // The vicinity settings have to be in the range of 0-999.
            int maxSetting = 1000;  // However, internally the optimizer always adds 1 to the settings set by the user => use 1 - 1000.

            // Set the start value depending on the number of vicinity settings for a good performance
            int setting = boxSettingToFind == 0 ? 100 : numberOfVicinitySquaresABoxMayEnter[boxSettingToFind - 1];
            long lastBoxConfigurationCount = -1; // To detect whether more box configurations have been generated than in the previous run

            // Only use this estimation method if there are 9 or more board positions in the solution. Otherwise, it's efficient enough to use the exact method immediately.
            if (basisBoxConfigurations.size() >= 9) {


                while (minSetting <= maxSetting && optimizerStatus == RUNNING) {

                    // Set the current number of vicinity squares to be used for generating. The vicinity squares setting must be sorted because
                    // this is what the original method also does!
                    numberOfVicinitySquaresABoxMayEnter[boxSettingToFind] = setting;
                    int[] vicinitySettings = numberOfVicinitySquaresABoxMayEnter.clone();
                    Arrays.sort(vicinitySettings);

                    try {
                        // Use 9 box configurations to generate new ones as a sample to estimate the total number of generated box configurations.
                        for (int index = 0; index < 3; index++) {

                            // Add the first three board positions.
                            BoxConfiguration boxConfiguration = basisBoxConfigurations.get(index);
                            BoxConfigurationGenerator generation = new BoxConfigurationGenerator(vicinitySettings, reachablePositions, boxConfiguration, boxConfigurationStorage, deadlockIdentification);
                            generationTasks.add(executorGeneration.submit(generation));

                            // Add six board positions from the middle of the solution.
                            boxConfiguration = basisBoxConfigurations.get(basisBoxConfigurations.size() / 3 + (index * 2) - 1);
                            generation = new BoxConfigurationGenerator(vicinitySettings, reachablePositions, boxConfiguration, boxConfigurationStorage, deadlockIdentification);
                            generationTasks.add(executorGeneration.submit(generation));

                            // Add the last three board positions of the solution.
                            boxConfiguration = basisBoxConfigurations.get(basisBoxConfigurations.size() - 1 - index);
                            generation = new BoxConfigurationGenerator(vicinitySettings, reachablePositions, boxConfiguration, boxConfigurationStorage, deadlockIdentification);
                            generationTasks.add(executorGeneration.submit(generation));

                        }

                        // Wait until all generation threads have finished.
                        for (Future future : generationTasks) {
                            future.get();
                        }

                    } catch (Exception e) {
                        // RejectedExecutionException from executor, CancellationException from task, ... all means generation must be stopped
                    }

                    // Estimate the box configurations count when all box configurations would have been used for generating.
                    long boxConfigurationsCount = boxConfigurationStorage.getSize() * (long) basisBoxConfigurations.size() / generationTasks.size();

                    // Display estimate to the user. (setting - 1) because the GUI values are always off by 1!
                    optimizerGUI.addLogTextDebug(String.format("Schätzung bei Nachbarfelder settings: %s%d = %,d", firstVicinityValues, (setting - 1), boxConfigurationsCount));

                    // If the estimate is too large, or we hit the maximum capacity, we have to use a lower value.
                    if (boxConfigurationsCount > maxBoxConfigurationsCount || boxConfigurationsCount == boxConfigurationStorage.getCapacity()) {
                        maxSetting = setting - 1;
                    } else {
                        // If we have generated as many box configurations as before, we can stop because increasing the settings won't change the result anymore.
                        if (boxConfigurationsCount == lastBoxConfigurationCount || boxConfigurationsCount == maxBoxConfigurationsCount) {
                            break;
                        }
                        // We may generate more box configurations because we haven't reached the set maximum number of box configurations.
                        minSetting = setting + 1;
                    }

                    lastBoxConfigurationCount = boxConfigurationsCount;

                    // Binary search for the best setting.
                    setting = (minSetting + maxSetting) / 2;

                    generationTasks.clear();
                    boxConfigurationStorage.clear();
                }
            }

            optimizerGUI.addLogTextDebug("Beste ermittelte Anzahl Nachbarfelder: " + firstVicinityValues + (setting - 1));

            /**
             * The "setting" is an estimated value. Now the real value is calculated to ensure we get the correct value.
             */

            optimizerGUI.addLogTextDebug("\nGenaue Ermittlung der besten Settings:");

            // Settings can either be increased (lastDirection = 1) or be decreased (lastDirection = -1).
            int lastDirection = 0;

            // The number of box configurations generated by the previous run using the previous settings.
            lastBoxConfigurationCount = -1;

            // Do the same search again but this time using all box configurations for generating new ones.
            while (setting > 0 && setting <= 1000 && optimizerStatus == RUNNING) {

                // Set the current number of vicinity squares to be used for generating. The vicinity squares setting must be sorted because
                // this is what the original method also does!
                numberOfVicinitySquaresABoxMayEnter[boxSettingToFind] = setting;
                int[] vicinitySettings = numberOfVicinitySquaresABoxMayEnter.clone();
                Arrays.sort(vicinitySettings);

                // Generate successor box configurations from all the passed box configurations.
                try {
                    for (BoxConfiguration boxConfiguration : basisBoxConfigurations) {
                        BoxConfigurationGenerator generation = new BoxConfigurationGenerator(vicinitySettings, reachablePositions, boxConfiguration, boxConfigurationStorage, deadlockIdentification);
                        generationTasks.add(executorGeneration.submit(generation));
                    }

                    for (Future future : generationTasks) {
                        future.get();
                    }
                } catch (RejectedExecutionException | CancellationException | InterruptedException | ExecutionException e) {
                    /* Generation must be stopped */
                }

                int boxConfigurationsCount = boxConfigurationStorage.getSize();
                optimizerGUI.addLogTextDebug(String.format("Anzahl bei Nachbarfelder settings: %s%d = %,d", firstVicinityValues, (setting - 1), boxConfigurationsCount));

                // If the estimate is too large, or we hit the maximum capacity, we have to use a lower value.
                if (boxConfigurationsCount > maxBoxConfigurationsCount || boxConfigurationsCount == boxConfigurationStorage.getCapacity()) {
                    setting--;

                    // If the previous run has increased the settings then we would just go back to the same setting now -> break!
                    if (lastDirection == 1) {
                        break;
                    }
                    lastDirection = -1;
                } else {
                    // If the previous run has decreased the settings then we would just go back to the same setting now -> break!
                    // Also break if the same number of box configurations has been generated as before, although the settings have been increased.
                    if (lastDirection == -1 || lastBoxConfigurationCount == boxConfigurationsCount) {
                        break;
                    }

                    setting++;
                    lastDirection = +1;
                }

                lastBoxConfigurationCount = boxConfigurationsCount;

                generationTasks.clear();
                boxConfigurationStorage.clear();

            }

            System.gc();
            optimizerGUI.addLogTextDebug("Beste ermittelte Anzahl Nachbarfelder: " + firstVicinityValues + (setting - 1));
            optimizerGUI.setInfoText("Beste ermittelte Anzahl Nachbarfelder: " + firstVicinityValues + (setting - 1));

        }


        /**
         * Generate arrays containing all positions that are accessible for a box.
         * This is done for every position because the accessible positions are
         * ordered by distance to a specific other position. This is important,
         * because the user may limit the distance how far away from the original
         * box position a box may be repositioned.<br>
         * depth = 10 means: take the first 10 positions from the array, i.e.
         * the "nearest" 10 positions around the box (also counting the square
         * the box is located on).
         */
        private int[][] determineAllAccessiblePositions() {

            // Indicates whether a position has already been visited. This isn't
            // a boolean array for avoiding an initialization every time
            // a new position is investigated.
            // Instead of that, a timestamp technique is used.
            int[] isVisited = new int[board.size];
            int visitedMarker = 1; // Marker for visited positions

            // Positions that are reachable from a specific position.
            // This array is also used as queue for the search.
            int[] reachablePositionsFrom = new int[board.size];

            // End of the stack.
            int endOfQueue = 0;

            // Reachable positions from a specific position.
            int[][] reachablePositions = new int[boxPositionsCount][];

            // Determine the reachable positions starting from every internal box position.
            for (int boxPosition = 0; boxPosition < boxPositionsCount; boxPosition++) {

                // Set a new visited marker for the new box.
                visitedMarker++;

                // Start with empty queue.
                endOfQueue = 0;

                // The first accessible position is the position itself.
                reachablePositionsFrom[endOfQueue++] = boxPosition;

                // Mark the position as been reached by setting the current timestamp.
                isVisited[boxPosition] = visitedMarker;

                // While there are positions on the stack.
                for (int readIndex = 0; readIndex < endOfQueue; readIndex++) {

                    final int currentPosition = reachablePositionsFrom[readIndex];

                    for (int direction = 0; direction < 4; direction++) {

                        // Returns box neighbor position in the given direction.
                        // If this new position isn't accessible for a box (wall
                        // or deadlock position) then -1 is returned.
                        int newPosition = boxNeighbor[direction][currentPosition];

                        // If the position is accessible for a box and it hasn't
                        // been reached before then add it as reachable position.
                        if (newPosition >= 0 && isVisited[newPosition] != visitedMarker) {

                            // Mark the position as visited.
                            isVisited[newPosition] = visitedMarker;

                            // Only add those positions that have been marked
                            // as relevant by the user.
                            if (relevantBoxPositions[boxPosition]) {
                                reachablePositionsFrom[endOfQueue++] = newPosition;
                            }
                        }
                    }

                    // If the optimizer is to be stopped, return immediately.
                    // Our results are incomplete, but they are not going to be used.
                    if (optimizerStatus != RUNNING) {
                        return reachablePositions;
                    }
                }

                // Copy the reachable positions from the current position to the main array.
                reachablePositions[boxPosition] = Arrays.copyOf(reachablePositionsFrom, endOfQueue);
            }

            return reachablePositions;
        }
    }

    /**
     * Class for searching for a better solution by only using the generated
     * box configurations.<br>
     * The new solution is optimized for: <br>
     * 1. pushes<br>
     * 2. moves<br>
     * Before this method is called the box configurations to be used must have been generated.
     */
    public class PushesMovesOptimizer extends PushesMovesAllMetricsOptimizer {

        public PushesMovesOptimizer() {
            solutionReconstruction = new SolutionReconstructionPushesMoves();
        }

        public class SolutionReconstructionPushesMoves extends SolutionReconstructionPrimaryMetricsOnly {

            @Override
            protected int getMoves(StorageBoardPosition boardPosition) {
                return boardPosition.secondaryMetric;
            }

            @Override
            protected int getPushes(StorageBoardPosition boardPosition) {
                return boardPosition.primaryMetric;
            }
        }
    }


    /**
     * Class for searching for a better solution by only using the generated
     * box configurations.<br>
     * The new solution is optimized for: <br>
     * 1. moves<br>
     * 2. pushes<br>
     * <p>
     * Before this method is called, the box configurations to be used must have been generated.
     */
    private class MovesPushesOptimizer extends MovesPushesAllMetricsOptimizer {

        public MovesPushesOptimizer() {
            solutionReconstruction = new SolutionReconstructionMovesPushes();
        }

        /**
         * The main optimizer search stores all board positions in the storage.
         * This method reconstructs a solution path using those board positions.
         */
        protected class SolutionReconstructionMovesPushes extends SolutionReconstructionPrimaryMetricsOnly {

            @Override
            protected int getMoves(StorageBoardPosition boardPosition) {
                return boardPosition.primaryMetric;
            }

            @Override
            protected int getPushes(StorageBoardPosition boardPosition) {
                return boardPosition.secondaryMetric;
            }
        }
    }


    /**
     * Breadth-First-Search that searches for the shortest solution path
     * by only using the generated box configurations.
     * <p>
     * The path is optimal regarding:
     * <ol>
     *  <li>box lines</li>
     * </ol>
     *
     * @param initialBoard        the board at level start
     * @param endBoard            the end board having all boxes on a goal
     * @param currentBestSolution the currently best solution
     * @return the found solution
     */
    private OptimizerSolution findBestSolutionPathBoxLines(
            final OptimizerBoard initialBoard,
            final OptimizerBoard endBoard,
            final OptimizerSolution currentBestSolution) {

        // The index of a box configuration in the main box configuration storage.
        int boxConfigurationIndex;

        // Positions of the player and a box.
        int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, newBoxPositionSameBoxLine;

        // Current number of box lines during the search for a better solution.
        int currentBoxLinesCount = 1;

        // Last board position of the current box lines depth. (All board positions after this board position have one more box line)
        int lastBoardPositionWithCurrentBoxLinesNumber = -1;

        // This number is used for reconstructing the path of the new better solution. If the optimizer is stopped by the user
        // or due to an "out of memory" exception this is the number of the board position the path can be reconstructed from.
        // (every time a board position is added to the pushes queue a counter is increased. Hence, every board position has a "number").
        int newBestPathBoardPositionNumber = -1;

        // Used to store the real positions of the boxes of a box configuration.
        // Usually just the index of a box configuration in the global array is used but for doing pushes it's necessary to copy the real positions in this byte array.
        BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

        // Used to calculate the player reachable positions.
        final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

        // Number of board positions added to / read from the queue.
        // "One" board position = two ints, because it's:
        // 1. the index of the board position in the global board position array
        // 2. the push ancestor of the board position
        int boardPositionsAddedToQueueCount = 0;
        int boardPositionsReadFromQueueCount = 0;

        // Get the index of the initial box configuration.
        packBoxConfiguration(boxConfiguration, initialBoard);
        int startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

        // Calculate the index of the initial board position.
        int initialBoardPositionIndex = startBoxConfigurationIndex * playerSquaresCount + playerExternalToInternalPosition[initialBoard.playerPosition];

        // Get the index of the target box configuration -> box configuration having all boxes on a goal.
        packBoxConfiguration(boxConfiguration, endBoard);
        int targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

        // Calculate the end position of the player in the basis solution. This information is needed if the user requested the player to also end
        // at this position in all new found better solutions.
        int targetPlayerPosition = playerExternalToInternalPosition[endBoard.playerPosition];

        // Set the start board position on the board again.
        board = initialBoard.getClone();

        // Create an board positions set for storing the information which board position has already been reached.
        // "2*" because the axis of every push is also stored!
        BitVector reachedBoardPositions = new BitVector(2 * boxConfigurationStorage.getSize() * (playerSquaresCount + 1));

        // Create the queues holding the board positions that are generated during the search. Only board positions are stored that
        // result due to doing a push -> moves are ignored.
        BoardPositionQueue boardPositionsQueue = new BoardPositionQueue(false, 1 << 20);

        // Add the start board position to the queue and increase the number of added board positions.
        boardPositionsQueue.add(initialBoardPositionIndex, 0);
        boardPositionsAddedToQueueCount++;

        // Mark this board position as last one having the current number of box lines.
        lastBoardPositionWithCurrentBoxLinesNumber = boardPositionsAddedToQueueCount;

        // The search starts with 1 box lines -> display a message to inform the user.
        optimizerGUI.setInfoText(Texts.getText("vicinitySearchBoxLinesDepth") + currentBoxLinesCount);

        try {

            BreadthFirstSearch:
            while (optimizerStatus == RUNNING) {

                // Get next board position out of the queue (and also jump over the push ancestor in the queue by reading it).
                int currentBoardPosition = boardPositionsQueue.removeBoardPosition();
                /* pushAncestor = */
                boardPositionsQueue.removeBoardPosition();
                boardPositionsReadFromQueueCount++; // Board position and its ancestor count as 1 board position.

                // Calculate the box configuration index and the player position.
                int currentBoxConfigurationIndex = currentBoardPosition / playerSquaresCount;
                playerPosition = currentBoardPosition - playerSquaresCount * currentBoxConfigurationIndex;

                // Mark the board position as been reached from both axis (as soon as a board position is taken out of the
                // open queue there can't be a path from which this board position is reached with as much box lines as it is reached by the current path).
                int indexVisitedData = (currentBoardPosition) << 1;
                reachedBoardPositions.setVisited(indexVisitedData);
                reachedBoardPositions.setVisited(++indexVisitedData);

                // currentBoxConfigurationIndex is just an index in the global array where all box configurations are stored.
                // Now copy the box configuration represented by that index into a local variable.
                boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                // At the start player position to the queue.
                playerPositions.addFirst(playerPosition, 0);

                // Do moves until all reachable squares have been visited.
                while ((playerPosition = playerPositions.remove()) != NONE) {

                    // Try to move the player to every direction and do pushes if necessary.
                    for (int direction = 0; direction < 4; direction++) {

                        // If the new position is a wall immediately continue with the next direction.
                        if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                            continue;
                        }

                        // Is there a box at the new player position?
                        if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfigurationStorage.isBoxAtPosition(currentBoxConfigurationIndex, boxPosition)) {

                            // Is the new box position not valid or is it blocked by another box then continue with the next direction.
                            if ((newBoxPosition = boxNeighbor[direction][boxPosition]) == NONE || boxConfigurationStorage.isBoxAtPosition(currentBoxConfigurationIndex, newBoxPosition)) {
                                continue;
                            }

                            // Push is possible => do the push.
                            boxConfiguration.moveBox(boxPosition, newBoxPosition);

                            // Check whether it's one of the allowed box configurations.
                            if ((boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration)) != NONE) {

                                // Calculate the new board position index.
                                int newBoardPositionIndex = boxConfigurationIndex * playerSquaresCount + newPlayerPosition;

                                // 0 = horizontally, 1 = vertically
                                int pushAxis = direction >> 1;

                                // If this board position has been reached before it must be discarded.
                                // Note: for levels starting with the target board position we also have to to continue!
                                if (!reachedBoardPositions.isVisited((newBoardPositionIndex << 1) | pushAxis) || boxConfigurationIndex == targetBoxConfigurationIndex) {

                                    // Add the board position to the open queue and mark it as visited.
                                    boardPositionsQueue.add(newBoardPositionIndex, boardPositionsReadFromQueueCount);
                                    boardPositionsAddedToQueueCount++;
                                    reachedBoardPositions.setVisited((newBoardPositionIndex << 1) | pushAxis);

                                    // Check if the target box configuration has been found. If the player must end at the same location
                                    // as in the basis solution this is also checked.
                                    if (boxConfigurationIndex == targetBoxConfigurationIndex) {
                                        if (!isPlayerEndPositionToBePreserved || newPlayerPosition == targetPlayerPosition) {
                                            newBestPathBoardPositionNumber = boardPositionsAddedToQueueCount;
                                            break BreadthFirstSearch;
                                        }
                                        // To catch all possible solutions the search must go on using this board position.
                                        // However, the rest of the coding presumes that a solution must end with a push in
                                        // every case. Hence, this discards this board position with the risk of loosing some solutions.
                                        boardPositionsQueue.removeLastBoardPosition();
                                        boardPositionsQueue.removeLastBoardPosition();
                                        boardPositionsAddedToQueueCount--;
                                    }

                                    // The board position before the push can be marked as visited from both axis, too.
                                    // Either it has already been marked, anyway, or all paths to this board position are't better than
                                    // the current one and therefore can be discarded.
                                    indexVisitedData = (currentBoxConfigurationIndex * playerSquaresCount + playerPosition) << 1;
                                    reachedBoardPositions.setVisited(indexVisitedData);
                                    reachedBoardPositions.setVisited(++indexVisitedData);

                                    // Remove the box from the new position for continuing pushing this box line in the coming "do" loop.
                                    boxConfiguration.removeBox(newBoxPosition);

                                    // The box line starts with the position of the just pushed box.
                                    newBoxPositionSameBoxLine = newBoxPosition;

                                    // Do all possible "same box line" pushes
                                    while ((newBoxPositionSameBoxLine = boxNeighbor[direction][newBoxPositionSameBoxLine]) != NONE &&
                                            !boxConfigurationStorage.isBoxAtPosition(currentBoxConfigurationIndex, newBoxPositionSameBoxLine)) {

                                        // The box can be pushed one square further -> set the box and the player to their new positions.
                                        boxConfiguration.addBox(newBoxPositionSameBoxLine);
                                        newPlayerPosition = playerSquareNeighbor[direction][newPlayerPosition];

                                        // Get the index of the box configuration. If it is invalid the  box configuration must be discarded.
                                        if ((boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration)) == NONE) {
                                            boxConfiguration.removeBox(newBoxPositionSameBoxLine);
                                            break;
                                        }

                                        // Calculate the new board position index.
                                        newBoardPositionIndex = boxConfigurationIndex * playerSquaresCount + newPlayerPosition;

                                        // The box configuration index has been calculated -> the box can be removed for preparing the next push).
                                        boxConfiguration.removeBox(newBoxPositionSameBoxLine);

                                        // If this board position has already been reached before the loop can be stopped.
                                        // Note: for levels starting with the target board position we also have to to continue!
                                        if (reachedBoardPositions.isVisited((newBoardPositionIndex << 1) | pushAxis) && boxConfigurationIndex != targetBoxConfigurationIndex) {
                                            break;
                                        }

                                        // Add the board position to the open queue and mark it as visited.
                                        boardPositionsQueue.add(newBoardPositionIndex, boardPositionsAddedToQueueCount++);
                                        reachedBoardPositions.setVisited((newBoardPositionIndex << 1) | pushAxis);

                                        // Check if the target box configuration has been found. If the player must end at the same location
                                        // as in the basis solution this is also checked.
                                        if (boxConfigurationIndex == targetBoxConfigurationIndex) {
                                            if (!isPlayerEndPositionToBePreserved || newPlayerPosition == targetPlayerPosition) {
                                                newBestPathBoardPositionNumber = boardPositionsAddedToQueueCount;
                                                break BreadthFirstSearch;
                                            }
                                            // To catch all possible solutions the search must go on using this board position.
                                            // However, the rest of the coding presumes that a solution must end with a push in
                                            // every case. Hence this discards this board position with the risk of loosing some solutions.
                                            boardPositionsQueue.removeLastBoardPosition();
                                            boardPositionsQueue.removeLastBoardPosition();
                                            boardPositionsAddedToQueueCount--;
                                        }

                                    }
                                }
                            }

                            // Undo the push to reuse the box configuration for the next push.
                            boxConfiguration.moveBox(newBoxPosition, boxPosition);
                        } else {
                            // It's a move without a push => add the new player position for further searching.
                            playerPositions.addIfNew(newPlayerPosition);
                        }
                    }
                }

                // Display a status message if a new box lines depth has been reached.
                if (boardPositionsReadFromQueueCount == lastBoardPositionWithCurrentBoxLinesNumber) {
                    currentBoxLinesCount++;
                    optimizerGUI.setInfoText(Texts.getText("vicinitySearchBoxLinesDepth") + currentBoxLinesCount);

                    // Save the last board position number of the current box lines depth.
                    lastBoardPositionWithCurrentBoxLinesNumber = boardPositionsAddedToQueueCount;
                }
            }

        } catch (OutOfMemoryError e) {

            // The program ran out of memory. Delete the queue.
            boardPositionsQueue = null;

            // Set the new optimizer status.
            optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;

            // Return with no solution.
            return null;
        }

        // Inform the user about the RAM that is available after the search has ended.
        optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMInBytes() / 1024 / 1024));

        // If no solution has been found return immediately.
        if (newBestPathBoardPositionNumber == NONE) {
            return null;
        }

        // Reconstruct the solution and return the new solution.
        return reconstructSolution(startBoxConfigurationIndex, newBestPathBoardPositionNumber, boardPositionsQueue, currentBestSolution);
    }

    /**
     * Reconstructs the found solution and returns it as {@code OptimizerSolution}.
     *
     * @param startBoxConfigurationIndex the index of the start box configuration
     * @param lastBoardPositionNumber    the number of the last board position found by the search
     *                                   (usually the end board position where all boxes are on goals)
     * @param boardPositionsQueue        the queue all board positions are stored in
     * @param basisSolution              solution that has been used as basis for the optimization
     * @return the reconstructed solution returned as {@code OptimizerSolution}
     */
    private OptimizerSolution reconstructSolution(
            final int startBoxConfigurationIndex,
            final int lastBoardPositionNumber,
            final BoardPositionQueue boardPositionsQueue,
            final OptimizerSolution basisSolution) {

        // Create a new solution object.
        OptimizerSolution sol = new OptimizerSolution();

        // Create an list for storing all push board positions of the new path.
        List<Integer> newSolutionBoardPositions = new ArrayList<>();

        /*
         * Search and store all board positions of the found path in "newSolutionBoardPositions".
         */
        for (int boardPositionNumber = lastBoardPositionNumber; boardPositionNumber != 0; ) {

            // Search the current board position in the queue. Note: the board positions are stored:
            // boardPosition, pushPredecessor; boardPosition, pushPredecessor, ...
            // The board positions are taken out from the end to the start of the queue.
            int pushAncestorIndex = boardPositionsQueue.removeLastBoardPosition();
            newSolutionBoardPositions.add(0, boardPositionsQueue.removeLastBoardPosition());

            // Jump to the ancestor board position and decrease the counter appropriately (all over jumped board positions are deleted from the queue).
            boardPositionsQueue.jumpXBoardPositionsBackwards(2 * (boardPositionNumber - pushAncestorIndex - 1));
            boardPositionNumber = pushAncestorIndex;
        }

        // If the optimizer has stopped this means that the best found path
        // so far isn't a complete solution. Then the solution has to be
        // created using the missing part from the basis solution.
        // This can only be done when optimizing for pushes/moves, because
        // only then "lastBoardPositionNumber" is a board position of the
        // basis solution.
        if (optimizerStatus != RUNNING && optimizingMethod == OptimizationMethod.PUSHES_MOVES) {

            /*
             * Calculate the board positions of the basis solution that has been optimized.
             */
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);
            ArrayList<Integer> basisSolutionBoardPositionIndices = new ArrayList<>();

            OptimizerBoard tmpBoard = initialBoard.getClone();

            for (int movement : basisSolution.solution) {

                // Do the move on the board.
                if (tmpBoard.doMovement(movement)) {     // "doMovements" returns true, when the move results in a push.

                    // Get the box configuration from the board.
                    packBoxConfiguration(boxConfiguration, tmpBoard);

                    // Get the index of the box configuration in the board position array.
                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

                    // Calculate the board position index.
                    int boardPositionIndex = boxConfigurationIndex * playerSquaresCount + playerExternalToInternalPosition[tmpBoard.playerPosition];

                    // Save the board position indices.
                    basisSolutionBoardPositionIndices.add(boardPositionIndex);
                }
            }

            int lastBoardPositionIndexNewSolution = newSolutionBoardPositions.get(newSolutionBoardPositions.size() - 1);

            // Search the last reached board position of the new path in the basis solution.
            // All following board positions are then taken from the basis solution to
            // construct a new solution.
            for (int pushNo = basisSolutionBoardPositionIndices.size(); --pushNo != -1; ) {

                if (basisSolutionBoardPositionIndices.get(pushNo) == lastBoardPositionIndexNewSolution) {

                    // Add the board positions of the basis solution beginning at "pushNo" to complete the solution.
                    newSolutionBoardPositions.addAll(basisSolutionBoardPositionIndices.subList(pushNo + 1, basisSolutionBoardPositionIndices.size()));
                    break;
                }
            }
        }


        // The player moves in the new solution.
        ArrayList<Byte> solutionMoves = new ArrayList<>(10000);

        // The start is the initial box configuration.
        int previousBoxConfigurationIndex = startBoxConfigurationIndex;

        // The direction of a move / push.
        byte direction = 0;

        // Create the solution to be returned.
        for (int pushNo = 1; pushNo < newSolutionBoardPositions.size(); pushNo++) {

            int currentBoardPosition = newSolutionBoardPositions.get(pushNo);
            int boxConfigurationIndex = currentBoardPosition / playerSquaresCount;
            int playerPosition = currentBoardPosition - playerSquaresCount * boxConfigurationIndex;

            // The player's position in the next board position is the square where the pushed box has been located before the push had been made.
            int newBoxPosition = playerPositionToBoxPosition[playerPosition];

            // Determine the direction of the push.
            for (direction = 0; direction < 4; direction++) {
                int squareNeighbor = boxNeighbor[direction][newBoxPosition];
                if (squareNeighbor != NONE &&
                        boxConfigurationStorage.isBoxAtPosition(boxConfigurationIndex, squareNeighbor) &&
                        !boxConfigurationStorage.isBoxAtPosition(previousBoxConfigurationIndex, squareNeighbor)) {
                    break;
                }
            }

            int currentExternalPlayerPosition = playerInternalToExternalPosition[playerPosition];
            int externalPlayerPositionBeforePush = playerInternalToExternalPosition[playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]];

            // Get the moves to be done to reach the new player position.
            int[] movements = board.playerPath.getMovesTo(externalPlayerPositionBeforePush);

            // Add the moves to the solution and adjust the move counter.
            for (int move : movements) {
                solutionMoves.add((byte) move);
            }
            sol.movesCount += movements.length;

            // Do the push on the board.
            board.removeBox(currentExternalPlayerPosition);
            board.setBox(2 * currentExternalPlayerPosition - externalPlayerPositionBeforePush);
            board.playerPosition = currentExternalPlayerPosition;

            // Save the push in the solution.
            solutionMoves.add(direction);

            // Adjust the moves and pushes.
            sol.movesCount++;
            sol.pushesCount++;

            // The current box configuration is the previous box configuration
            // for the next loop.
            previousBoxConfigurationIndex = boxConfigurationIndex;
        }

        // Copy the solution into an array having the correct size.
        sol.solution = new byte[solutionMoves.size()];
        for (int moveNo = 0; moveNo < sol.solution.length; moveNo++) {
            sol.solution[moveNo] = solutionMoves.get(moveNo);
        }

        return sol;
    }

    /**
     * Optimizes the passed solution regarding box changes.
     *
     * @param basisSolution the solution to be optimized
     * @return the optimized solution or <code>null</code> if no better solution
     * has been found
     */
    public OptimizerSolution reduceBoxChanges(final OptimizerSolution basisSolution) {

        // Set the start board position on the board. "board" may have been changed by other methods.
        board = initialBoard.getClone();

        // The board "after the last between box push".
        OptimizerBoard boardAfterLastBetweenPushOfAnotherBox = board.getClone();
        int playerPositionAfterLastBetweenBoxPush;

        // The board "after the previous push".
        OptimizerBoard boardAfterPreviousPush = board.getClone();

        // Player position directly before the current box is pushed again.
        int playerPositionBeforeNextPushOfCurrentBox = 0;

        // Position of the pushed box.
        int currentBoxPosition = NONE;

        // Contain "old" and "new" box position and are used as return variables for called methods.
        BoxPositions boxPositions = new BoxPositions();
        BoxPositions boxPositionsTemp = new BoxPositions();

        // Copy the passed basis Solution to a local variable.
        // The secondary metrics aren't filled with valid values in this method.
        OptimizerSolution solution = basisSolution.clone();
        solution.boxChanges = 0;
        solution.boxLines = 0;
        solution.pushingSessions = 0;

        // The index of the previous push in the solution.
        int movementNumberPreviousPush = 0;

        // For storing the player positions before the pushes that might be brought forward.
        int[] playerPositionBeforePushWithIndexOf = new int[solution.movesCount];

        // Index in the original solution of specific pushes.
        int indexNextPushOfCurrentBox;
        int indexLastBetweenPushOfAnotherBox;
        int indexPredecessorPush;
        int indexLastBroughtForwardPush;

        // Squares that are visited in a specific part of the solution.
        boolean[] isForbiddenSquare = new boolean[board.size];

        // Return value of this method. Is true, when at least one optimization has been found.
        boolean atLeastOneOptimizationFound = false;

        // Indices of the successor pushes.
        int[] indexSuccessorPush = new int[solution.movesCount];

        /*
         * How this algorithm works:
         *
         * Definitions:
         *    Movement: a push or move in the solution
         *    Index of movement: index of a movement in the solution. This is also equal to the number of moves done so far.
         *
         * Example solution:
         *
         *         |- push sequence 1 -|   |-  push sequence 2  -|   |-rest of original solution-|
         * a...b...c...d...a...d...e...f...b...c...d...g....h....i...j...k...l...m...n...o...p...q
         * 								   X
         * ... = arbitrary number of moves
         * letters = pushes of a specific box
         *
         * The algorithm loops through the whole solution. For every push the
         * following is done:
         *
         * Example:
         * We are have just done the first push of box "c".
         * The box is compared with the previous pushed box which is
         * "b" -> box change. Now the algorithm treats "b" as
         * "current relevant box". It searches for the next push of "b". The
         * pushes from "c" to "f" shown as "push sequence 1" are
         * "between pushes". To optimize the box changes this algorithm tries to
         * bring forward push sequence 2 right before push sequence 1.
         *
         * This is done the following:
         * 1. Mark every square in push sequence 1 that is visited by a box or the player.
         * 2. Up from the next push of "b" (marked with a "X") all pushes are checked for not interfering
         *    with the marked squares from 1. In the example the pushes included in
         *    the push sequence 2 don't interfere with the marked squares.
         * 3. Check if it is possible to bring forward pushes from push sequence 2.
         *    If this is possible and the number of moves is <= the number of
         *    moves in the original solution an improvement has been found.
         *
         * The new solution may look like this:
         *         |-brought forward-|   |- push sequence 1 -|            |-rest of original solution-|
         * a...b...b.....c.....d.....g...c...d...a...d...e...f...h....i...j...k...l...m...n...o...p...q
         *
         * The pushes "b", "c", "d" and "g" could be brought forward without
         * increasing the number of moves. The pushes "h" and "i" couldn't be
         * brought forward without increasing the number of moves and therefore
         * they stay where they are.
         *
         *
         *
         * Some variables that are used in the algorithm described using the
         * example:
         *
         *         |- push sequence 1 -|   |-  push sequence 2  -|   |-rest of original solution-|
         * a...b...c...d...a...d...e...f...b...c...d...g....h....i...j...k...l...m...n...o...p...q
         *
         * currentBox: current box in the example would be "b".
         * currentBoxPosition: the position of "b" after the first push of "b"
         * currentMovementNo: in the example, it would be the first push of "c"
         * (there we detect that a box change has occurred).
         * indexLastBetweenPushOfAnotherBox: this would be the index of the push
         * of box "f". ("f" is the last box that is pushed before the relevant
         * box "b" is pushed again). indexNextPushOfCurrentBox: this would be
         * the index of the second push of box "b".
         *
         * "index" = movementNo = index in the solution
         *
         * Important: "b" is the relevant box although the current movementNo
         * (and therefore the currently pushed box) is "c"! It's first when "c"
         * is pushed that the algorithm detects that a box change has occurred
         * and therefore the last / previous push (the push of "b") is taken as
         * the relevant push.
         */

        // Loop over the whole solution.
        for (int currentMovementNo = 0; currentMovementNo < solution.movesCount; currentMovementNo++) {

            // Save the board state before the "current push" is been made.
            OptimizerBoard boardBeforeCurrentPush = board.getClone();

            // Do the movement on the board (move or push). If it wasn't a push
            // immediately continue with the next movement.
            if (!board.doMovementWithBoxPositions(solution.solution[currentMovementNo], boxPositions)) {
                continue;
            }

            /*
             * Ok, we have reached the next push in the solution. Attention:
             * currentBoxPosition is
             * "the box position that is currently relevant for this algorithm".
             * In fact it's the position of the previously done push! The just
             * pushed box position is stored in boxPositions.newPosition!
             */

            // Check if the previous pushed box is the same box than the just
            // pushed box.
            if (boxPositions.oldPosition == currentBoxPosition || currentBoxPosition == NONE) {
                // It's the same box as before => no box change
                currentBoxPosition = boxPositions.newPosition;
            } else {
                // Indicating whether an optimization for the current push has been found.
                boolean hasImprovementBeenFound = false;

                /*
                 * A box change has occurred. Now the next push of that box is
                 * searched.
                 */

                // Start to search for the next push of the current box by the
                // next movement.
                indexNextPushOfCurrentBox = currentMovementNo + 1;

                // We have just pushed another box than the "current box". Hence
                // the current index represents a push of another box.
                indexLastBetweenPushOfAnotherBox = currentMovementNo;

                // Initialize the box position with an invalid value.
                boxPositionsTemp.oldPosition = NONE;

                // Do all pushes until the current box is pushed again.
                while (indexNextPushOfCurrentBox < solution.movesCount && boxPositionsTemp.oldPosition != currentBoxPosition) {

                    // Save the player position before the next push of the
                    // current box is to be done.
                    playerPositionBeforeNextPushOfCurrentBox = board.playerPosition;

                    // Do the movement and thereby update the boxPositions of
                    // the pushed box in "boxPositionsTemp".
                    if (board.doMovementWithBoxPositions(solution.solution[indexNextPushOfCurrentBox], boxPositionsTemp)) {

                        // A push has been done. If it wasn't the current box
                        // save the index of this
                        // movement -> determine the movement index of the last
                        // pushed box before the current box is pushed again.
                        if (boxPositionsTemp.oldPosition != currentBoxPosition) {
                            indexLastBetweenPushOfAnotherBox = indexNextPushOfCurrentBox;
                        }
                    }
                    indexNextPushOfCurrentBox++;
                }

                /*
                 * All movements of the other boxes till the current box is
                 * pushed again have been made.
                 *
                 * indexLastBetweenPushOfAnotherBox = the index in the solution
                 * of the movement where the last "other" box is pushed before
                 * the current box is pushed again indexNextPushOfCurrentBox =
                 * the index of the movement where the current box is pushed
                 * again
                 */

                // Position "indexNextPushOfCurrentBox" exactly at the index of
                // the next push of the current box.
                if (boxPositionsTemp.oldPosition == currentBoxPosition) {
                    indexNextPushOfCurrentBox--;
                }

                // If we have already reached the end of the solution there is
                // no more push that could be brought forward.
                if (indexNextPushOfCurrentBox < solution.movesCount) {

                    // Set the board to the state as it has been directly BEFORE
                    // the push of "current box" again.
                    board = boardBeforeCurrentPush.getClone();

                    // Do all movements from currentMovementNo to the last push
                    // of another box before the current box is pushed again.
                    // Thereby mark all reached squares (also all squares that
                    // are reached by the player only!)
                    //
                    // Description:
                    // 1. box a is pushed
                    // 2. a lot of other boxes are pushed
                    // 3. box a is pushed again
                    // Here all movements of 1. and 2. are done again and the
                    // visited squares are marked.
                    Arrays.fill(isForbiddenSquare, false);

                    int moveNo = 0;
                    isForbiddenSquare[board.playerPosition] = true;
                    for (moveNo = currentMovementNo; moveNo <= indexLastBetweenPushOfAnotherBox; moveNo++) {
                        if (board.doMovementWithBoxPositions(solution.solution[moveNo], boxPositionsTemp)) {
                            isForbiddenSquare[boxPositionsTemp.oldPosition] = true;
                            isForbiddenSquare[boxPositionsTemp.newPosition] = true;
                        } else {
                            isForbiddenSquare[board.playerPosition] = true;
                        }
                    }
                    playerPositionAfterLastBetweenBoxPush = board.playerPosition;

                    /*
                     * The marked squares mustn't been visited again to avoid
                     * incompatible parts of the solution.
                     */

                    // Now we have done the last between push. Until the current
                    // box is pushed again there
                    // might be moves of the player. These moves are done now.
                    for (; moveNo < indexNextPushOfCurrentBox; moveNo++) {
                        board.doMovement(solution.solution[moveNo]);
                    }

                    /*
                     * Now we are exactly situated before the next push of
                     * "current box".
                     */

                    boolean collision = false;
                    indexPredecessorPush = indexNextPushOfCurrentBox;

                    // Do all movements that don't "interfere" with the marked
                    // squares. That means we search pushes
                    // that are completely independent from the marked squares.
                    // We have to save the player position before every push
                    // because later we want to bring forward push by push
                    // and we have to search a path for the player to the
                    // "rest of the not brought forward pushes".
                    while (moveNo < solution.movesCount && !collision) {

                        playerPositionBeforePushWithIndexOf[indexPredecessorPush] = board.playerPosition;
                        indexSuccessorPush[indexPredecessorPush] = moveNo;

                        if (board.doMovementWithBoxPositions(solution.solution[moveNo], boxPositionsTemp)) {
                            indexPredecessorPush = moveNo;
                            if (isForbiddenSquare[boxPositionsTemp.oldPosition] || isForbiddenSquare[boxPositionsTemp.newPosition]) {
                                collision = true;
                            }
                        } else {
                            if (isForbiddenSquare[board.playerPosition]) {
                                collision = true;
                            }
                        }
                        moveNo++;
                    }

                    /*
                     * Now we know that it's possible to run to movement l-2
                     * without any interferences (= without visiting any of the
                     * marked squares).
                     */

                    // The loop above may be aborted because a marked square has
                    // been visited. Therefore
                    // do all movements till the next push to determine and
                    // store the player position before it.
                    playerPositionBeforePushWithIndexOf[indexPredecessorPush] = board.playerPosition;

                    {
                        int movementNo = 0;
                        for (movementNo = moveNo; movementNo < solution.movesCount && !board.doMovement(solution.solution[movementNo]); movementNo++) {
                            playerPositionBeforePushWithIndexOf[indexPredecessorPush] = board.playerPosition;
                        }
                        indexSuccessorPush[indexPredecessorPush] = movementNo;
                    }

                    // Restore the board as it was directly before the current
                    // push has been made.
                    board = boardAfterPreviousPush.getClone();
                    board.playerPosition = boardBeforeCurrentPush.playerPosition;

                    // Do all movements from currentMovementNo to the last push
                    // of another box before the current box is pushed again.
                    for (int movementNo = currentMovementNo; movementNo <= indexLastBetweenPushOfAnotherBox; movementNo++) {
                        board.doMovement(solution.solution[movementNo]);
                    }
                    boardAfterLastBetweenPushOfAnotherBox = board.getClone();

                    // Restore the board as it was after the last push has been
                    // made (currently a push has been
                    // made, the last push is the push before the current push).
                    board = boardAfterPreviousPush.getClone();

                    /*
                     * We now have to check whether the next push of the current
                     * box can be brought forward. Therefore the player has to
                     * find a way to the correct position to do that push
                     * already now.
                     */

                    // Get the distance of the player to the position before the
                    // next push of the current box.
                    int distanceToPositionBeforeNextPushOfCurrentBox = board.playerPath.getDistanceTo(playerPositionBeforeNextPushOfCurrentBox);

                    // If the player can reach that position, there might be the
                    // chance to do that push
                    // already now and by doing this to reduce the box changes.
                    if (distanceToPositionBeforeNextPushOfCurrentBox != -1) {

                        // The board is already at the correct state. Now set
                        // the player to the position before the next push
                        // of current box.
                        board.playerPosition = playerPositionBeforeNextPushOfCurrentBox;

                        indexLastBroughtForwardPush = indexNextPushOfCurrentBox - 1;
                        int solutionLength = solution.movesCount;

                        // Try to bring forward as many pushes as possible while
                        // regarding the number of moves not becoming
                        // more than the current number of moves.
                        // (l-2 = last movement that can be done without
                        // collision with the marked squares)
                        for (int movementNo = indexNextPushOfCurrentBox; movementNo < moveNo - 1; movementNo++) {

                            // Do all movements on both boards:
                            // On the global "Board" the new variant is played
                            // that means the next push of the current box
                            // (and following pushes) are already done now.
                            // On "boardAfterLastBetweenPushOfAnotherBox" the
                            // original solution is played as it is.
                            boardAfterLastBetweenPushOfAnotherBox.playerPosition = board.playerPosition;
                            board.doMovement(solution.solution[movementNo]);
                            if (boardAfterLastBetweenPushOfAnotherBox.doMovement(solution.solution[movementNo])) {

                                // Distances of the player to specific squares, used to determine
                                // whether a new variant of the solution has more, equal or less moves
                                // than the original solution.
                                int d1, d2;

                                // Ok, we have done a push. We know have to
                                // check whether the part we have skipped
                                // (we have
                                // done the next push of the current push
                                // already now and skipped all between
                                // pushes of other boxes)
                                // can still be done. Therefore the player must
                                // get to the correct positions.
                                // d1 = distance of the player from the current
                                // position to the start of the skipped
                                // solution part
                                // d2 = distance of the player from the end of
                                // the skipped solution part to the new
                                // beginning of the
                                // rest of the original solution
                                if ((d1 = board.playerPath.getDistanceTo(boardBeforeCurrentPush.playerPosition)) != -1
                                        && (d2 = boardAfterLastBetweenPushOfAnotherBox.playerPath.getDistance(playerPositionAfterLastBetweenBoxPush, playerPositionBeforePushWithIndexOf[movementNo])) != -1) {

                                    // Ok, after the new variant is played the
                                    // player can go back to the skipped
                                    // part, and after that skipped
                                    // part is done go back to the original
                                    // solution. This means the box changes can
                                    // be reduced. The question is:
                                    // Does this new variant need more moves as
                                    // the old one? (pushes are the same of
                                    // course, because they are just reordered).
                                    // Description:
                                    // Original part of
                                    // solution/part1/part2/rest of original
                                    // solution
                                    // We are trying to switch the order of
                                    // part1 and part2 in the solution.
                                    // This is done by bringing forward movement
                                    // be movement of part2 just before part1.
                                    int length = movementNumberPreviousPush                    // moves till end of "Original part of solution"
                                            + distanceToPositionBeforeNextPushOfCurrentBox                // distance to start of part2
                                            + movementNo - indexNextPushOfCurrentBox + 1                // length of the already brought forward movements of part2
                                            + d1                                                        // distance to start of part1
                                            + indexLastBetweenPushOfAnotherBox - currentMovementNo + 1  // length of part1
                                            + d2                                                        // distance to "rest of original solution"
                                            + solution.movesCount - indexSuccessorPush[movementNo] + 1; // length of "rest of original solution"

                                    // If the new number of moves of the
                                    // solution is equal or less than before, we
                                    // have found an improvement.
                                    if (length <= solutionLength) {
                                        solutionLength = length;
                                        indexLastBroughtForwardPush = movementNo;
                                    }
                                }
                            }
                        }

                        // If at least one push has been brought forward an
                        // improvement has been found.
                        if (indexLastBroughtForwardPush > indexNextPushOfCurrentBox - 1) {

                            hasImprovementBeenFound = true;

                            /*
                             * We have found an improvement by bringing forward
                             * some pushes. Now we have to create a new solution
                             * containing this improvement.
                             */

                            // Copy the board state
                            // "after the last between push of another box" into
                            // the corresponding board.
                            board = boardAfterPreviousPush.getClone();
                            board.playerPosition = boardBeforeCurrentPush.playerPosition;
                            for (int movementNo = currentMovementNo; movementNo <= indexLastBetweenPushOfAnotherBox; movementNo++) {
                                board.doMovement(solution.solution[movementNo]);
                            }
                            boardAfterLastBetweenPushOfAnotherBox = board.getClone();

                            // Set the main board as it was after the previous
                            // push (that is the push of "current box"! which
                            // now is
                            // to be followed by another push of that box).
                            board = boardAfterPreviousPush.getClone();

                            // Create a new solution (initialized with the
                            // current solution).
                            byte[] modifiedSolution = solution.solution.clone();

                            // Beginning from "currentSolutionLength" we have
                            // found a better path.
                            int currentSolutionLength = movementNumberPreviousPush + 1;

                            // Store the best player path in the new solution
                            // and add its length to "currentSolutionLength".
                            // Now the solution contains the path of the player
                            // to the "brought forward part".
                            int[] movements = board.playerPath.getMovesTo(playerPositionBeforeNextPushOfCurrentBox);
                            for (int move : movements) {
                                modifiedSolution[currentSolutionLength++] = (byte) move;
                            }

                            // Copy the brought forward part into the new
                            // solution.
                            System.arraycopy(solution.solution, indexNextPushOfCurrentBox, modifiedSolution, currentSolutionLength, indexLastBroughtForwardPush - indexNextPushOfCurrentBox + 1);

                            // Add the length of the brought forward part to the
                            // current solution length.
                            currentSolutionLength += indexLastBroughtForwardPush - indexNextPushOfCurrentBox + 1;

                            // Set the player position directly before the next
                            // push of the current box.
                            board.playerPosition = playerPositionBeforeNextPushOfCurrentBox;

                            // Do all of the brought forward pushes (and the
                            // necessary moves, of course).
                            for (int movementNo = indexNextPushOfCurrentBox; movementNo <= indexLastBroughtForwardPush; movementNo++) {
                                boardAfterLastBetweenPushOfAnotherBox.playerPosition = board.playerPosition;
                                board.doMovement(solution.solution[movementNo]);
                                boardAfterLastBetweenPushOfAnotherBox.doMovement(solution.solution[movementNo]);
                            }
                            movements = board.playerPath.getMovesTo(boardBeforeCurrentPush.playerPosition);
                            for (int move : movements) {
                                modifiedSolution[currentSolutionLength++] = (byte) move;
                            }

                            // Copy the part we have skipped into the new
                            // solution and increase the currentSolutionLength
                            // by the length of this part.
                            System.arraycopy(solution.solution, currentMovementNo, modifiedSolution, currentSolutionLength, indexLastBetweenPushOfAnotherBox - currentMovementNo + 1);
                            currentSolutionLength += indexLastBetweenPushOfAnotherBox - currentMovementNo + 1;

                            // Store the best player path to
                            // "the rest of the original solution" into the new
                            // solution and add its length to
                            // "currentSolutionLength".
                            movements = boardAfterLastBetweenPushOfAnotherBox.playerPath.getMoves(playerPositionAfterLastBetweenBoxPush, playerPositionBeforePushWithIndexOf[indexLastBroughtForwardPush]);
                            for (int move : movements) {
                                modifiedSolution[currentSolutionLength++] = (byte) move;
                            }

                            // Copy the "rest of the original solution" into the
                            // new solution and calculate the new solution
                            // length.
                            System.arraycopy(solution.solution, indexSuccessorPush[indexLastBroughtForwardPush], modifiedSolution, currentSolutionLength, solution.movesCount - indexSuccessorPush[indexLastBroughtForwardPush]);
                            currentSolutionLength += solution.movesCount - indexSuccessorPush[indexLastBroughtForwardPush];

                            // Save the new number of moves and the new
                            // solution.
                            solution.movesCount = currentSolutionLength;
                            solution.solution = modifiedSolution;
                        }
                    }
                }

                if (!hasImprovementBeenFound) {
                    // No improvement found. Hence, just do the push as in the
                    // original solution for
                    // restoring the original board.
                    currentBoxPosition = boxPositions.newPosition;

                    board = boardBeforeCurrentPush.getClone();
                    board.doMovement(solution.solution[currentMovementNo]);
                } else {
                    // The solution has changed. The search is set back to the
                    // last push to search
                    // for further improvements of the (modified) solution.
                    board = boardAfterPreviousPush.getClone();
                    currentMovementNo = movementNumberPreviousPush;

                    // Set the flag, indicating that at least one optimization
                    // has been found.
                    atLeastOneOptimizationFound = true;
                }

            }

            // Backup the current board as "previous" board.
            boardAfterPreviousPush = board.getClone();
            movementNumberPreviousPush = currentMovementNo;
        }

        return atLeastOneOptimizationFound ? solution : null;
    }

    /**
     * Prints the current status of the optimizer.
     * <p>
     * The current pushes depth investigated is displayed
     * as well as the current best solution and the found
     * improvement in moves and pushes.
     *
     * @param currentBestSolution    current best found solution
     * @param pushesDepth            the pushes depth the optimizer is currently processing
     * @param foundPushesImprovement the found improvement for the pushes number
     * @param foundMovesImprovement  the found improvement for the moves number
     */
    private void printStatusPushesMovesOptimizing(
            final OptimizerSolution currentBestSolution,
            final int pushesDepth,
            final int foundPushesImprovement,
            final int foundMovesImprovement) {
        SwingUtilities.invokeLater(() -> optimizerGUI.setInfoText(
                Texts.getText("vicinitySearchPushesDepth")
                        + (prefixPushesCount + pushesDepth)
                        + "    "
                        + Texts.getText("currentBestSolutionMovesPushes")
                        + (prefixMovesCount + currentBestSolution.movesCount
                        + postfixMovesCount - foundMovesImprovement)
                        + "/"
                        + (prefixPushesCount + currentBestSolution.pushesCount
                        + postfixPushesCount - foundPushesImprovement)
                        + " ("
                        + (-foundMovesImprovement)
                        + "/"
                        + (-foundPushesImprovement)
                        + ")"
        ));
    }

    /**
     * Creates a new {@code BoxConfiguration} from the passed board.
     *
     * @param newPackedBoxConfiguration {@code BoxConfiguration} to be filled with the box data
     * @param board                     {@code OptimizerBoard} used to get the box positions
     */
    private void packBoxConfiguration(final BoxConfiguration newPackedBoxConfiguration, final OptimizerBoard board) {

        // Loop over all internal box positions.
        for (int boxPosition = 0; boxPosition < boxPositionsCount; boxPosition++) {

            // Convert from internal to external, since the OptimizerBoard uses the "normal" board positions,
            // whereas the BoxConfiguration uses only those positions that are valid box positions (no dead squares).
            if (board.isBox(boxInternalToExternalPosition[boxPosition])) {
                newPackedBoxConfiguration.addBox(boxPosition);
            } else {
                newPackedBoxConfiguration.removeBox(boxPosition);
            }
        }
    }

    /**
     * Returns all box configurations that occur in the passed solution.
     * <p>
     * The box configurations are ordered in the way they occur in the solution.
     * This method assumes the current board is set to the start board position.
     *
     * @param solution the solution whose box configurations are to be returned
     * @return the box configurations of the passed solution stored in an
     * <code>ArrayList</code>
     */
    private ArrayList<BoxConfiguration> getBoxConfigurationsFromSolution(final OptimizerSolution solution) {

        // A box configuration.
        BoxConfiguration boxConfiguration = null;

        // ArrayList to be returned, containing all box configurations of the
        // passed solution.  Since we know beforehand the exact number of
        // contained pushes, we can specify exactly the optimal capacity:
        // one larger than the number of pushes (we include both ends).
        ArrayList<BoxConfiguration> boxConfigurations = new ArrayList<>(solution.pushesCount + 1);

        // Do all moves of the solution and save every box configuration
        // that occurs.
        OptimizerBoard tmpBoard = initialBoard.getClone();
        for (int moveNo = 0; moveNo < solution.movesCount; ) {

            // Create a box configuration from the current board.
            boxConfiguration = new BoxConfiguration(boxPositionsCount);
            packBoxConfiguration(boxConfiguration, tmpBoard);

            // Add the box configuration to the list.
            boxConfigurations.add(boxConfiguration);

            // Do the next push on the board.
            while (!tmpBoard.doMovement(solution.solution[moveNo++])) {
                // Just did a push ...
            }
        }

        // Add the last box configuration.
        boxConfiguration = new BoxConfiguration(boxPositionsCount);
        packBoxConfiguration(boxConfiguration, tmpBoard);
        boxConfigurations.add(boxConfiguration);

        return boxConfigurations;
    }

    /**
     * Super class for all optimizer searches that consider all 5 metrics: moves, pushes,
     * box lines, box changes and pushing sessions.
     */
    private abstract class AllMetricsOptimizer {

        // Number of threads used for the search.
        protected final int FORWARD_SEARCH_THREADS_COUNT = (maxCPUsToBeUsedCount + 1) / 2;
        protected final int BACKWARD_SEARCH_THREADS_COUNT = maxCPUsToBeUsedCount / 2;

        // Open queue for the forward and backward search.
        protected PriorityQueueOptimizer openQueueForward = null;
        protected PriorityQueueOptimizer openQueueBackward = null;

        // The search for a new solution compares the reached board positions
        // with the board positions of the current best solution.
        // If any of them is reached better, then the search saves the number
        // of moves/pushes that it has been reached better with.
        protected int movesImprovementForwardSearch = 0;
        protected int pushesImprovementForwardSearch = 0;
        protected int movesImprovementBackwardSearch = 0;
        protected int pushesImprovementBackwardSearch = 0;

        // The search directions don't meet if the user stops the optimizer or an error occurs (e.g. "out of memory").
        // To be able to reconstruct the solution in this case,
        // the reconstruction method needs to know how far the search directions have come.
        // In these variables, the information is stored what was the last
        // (with the most pushes/pulls) reached board position of each search direction.
        protected BoardPosition lastVisitedBoardPositionOfOriginalSolutionForward = null;
        protected BoardPosition lastVisitedBoardPositionOfOriginalSolutionBackward = null;

        // The number of moves and pushes needed to reach the new best solution.
        protected volatile int newBestSolutionMovesCount = -1;
        protected volatile int newBestSolutionPushesCount = -1;

        // All reached board positions are saved in this storage.
        protected BoardPositionsStorage boardPositionStorage = null;

        // The board positions of the solution to be optimized.
        // Note: OptimizerBoardPosition also stores the direction of the push, since this is needed for calculating
        // the secondary metrics (box changes, ...). However, during the search a "board position" is only
        // represented by the "box configuration index" and the player position (but not the push direction).
        protected ArrayList<BoardPosition> boardPositionsSolutionToBeOptimized = null;

        // The solution to be optimized.
        protected OptimizerSolution solutionToBeOptimized = null;

        // The box configuration which represents the index of the "solved" box configuration (having all boxes on goals).
        protected int targetBoxConfigurationIndex = 0;

        // The index of the start box configuration of the level.
        protected int startBoxConfigurationIndex = 0;

        protected abstract OptimizerSolution getBestFoundSolution();

        protected abstract OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentBestSolution);

        /**
         * Returns a list of all board positions the passed solution
         * consists of. The board positions contain information
         * about with which metrics they have in the solution (pushes, moves,...)
         *
         * @param currentBestSolution solution whose board positions are to be returned
         * @return the board positions that make up the passed solution
         */
        protected ArrayList<BoardPosition> getSolutionBoardPositions(final OptimizerSolution currentBestSolution) {

            // The board positions of the passed solution holding also the metrics for every board position (pushes, moves, box lines, ...)
            ArrayList<BoardPosition> boardPositions = new ArrayList<>(currentBestSolution.pushesCount);

            // Used to store the real box positions of a box configuration.
            // Usually just the index of a box configuration in the global array
            // is used, but for doing pushes, it's necessary to copy the real positions in this byte array.
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Create an own board to play the solution on.
            OptimizerBoard ownBoard = initialBoard.getClone();


            /*
             * Calculate the metrics for every board position of the current best solution.
             */
            int lastPushedBoxPosition = NONE; // the first push must be counted as box change, hence initialize with -1
            boolean lastMovementWasMove = true;
            for (int moveNo = 0, pushNo = 0, boxLines = 0, boxChanges = 0, pushingSessions = 0; moveNo < currentBestSolution.movesCount; moveNo++) {

                // Do the move on the board. "doMovements" returns true, when the move results in a push.
                if (ownBoard.doMovement(currentBestSolution.solution[moveNo])) {

                    // Get the box configuration from the board.
                    packBoxConfiguration(boxConfiguration, ownBoard);

                    // Get the index of the box configuration in the box configuration storage.
                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

                    int newPlayerPosition = playerExternalToInternalPosition[ownBoard.playerPosition];

                    // Count the number of pushes.
                    pushNo++;

                    if (newPlayerPosition != lastPushedBoxPosition || lastMovementWasMove) {
                        boxLines++;
                    }
                    if (newPlayerPosition != lastPushedBoxPosition) {
                        boxChanges++;
                    }
                    if (lastMovementWasMove) {
                        pushingSessions++;
                    }

                    // Add all board positions with their metrics to the array.
                    boardPositions.add(new BoardPosition(moveNo + 1, pushNo, boxLines, boxChanges, pushingSessions, boxConfigurationIndex, newPlayerPosition));

                    // This has been a push.
                    lastMovementWasMove = false;

                    // Calculate the new box position and convert it into a player position for an easier comparison with the next player position.
                    int directionOffset = ownBoard.directionOffsets[currentBestSolution.solution[moveNo] & 3];
                    lastPushedBoxPosition = playerExternalToInternalPosition[ownBoard.playerPosition + directionOffset];
                } else {
                    // This has been a move.
                    lastMovementWasMove = true;
                }
            }

            return boardPositions;
        }

        /**
         * Calculates all start board positions for the backward search and
         * adds them to the open queue for the backward search and also
         * stores them in the position storage for being able of detecting
         * duplicate board positions.
         */
        protected void addBackwardBoardPositions() {

            // Read the end position of the player in the solution (player position of the last board position of solution).
            int playerEndPosition = boardPositionsSolutionToBeOptimized.get(boardPositionsSolutionToBeOptimized.size() - 1).playerPosition;

            // If the player end position must be preserved, then only take the end position.
            if (isPlayerEndPositionToBePreserved) {
                long indexTargetBoardPosition = boardPositionStorage.addIfBetter(0, 0, targetBoxConfigurationIndex, playerEndPosition, SearchDirection.BACKWARD);
                openQueueBackward.add(indexTargetBoardPosition);
                return;
            }

            // Add all backward search start board positions to the backward queue.
            // The backward search must start with every possible end board position.
            // Therefore, the player is placed next to every box.
            for (int playerPositionBeforePull = 0; playerPositionBeforePull < playerSquaresCount; playerPositionBeforePull++) {

                // Check whether the player can pull a box to any direction.
                for (int direction = 0; direction < 4; direction++) {

                    int playerPositionAfterPull = -1;
                    int boxPositionBeforePull = -1;
                    int boxPositionAfterPull = -1;
                    int boxPosition = -1;

                    if (
                        // Check whether the position the player will be at after the pull is accessible for the player (no wall or box)
                            (playerPositionAfterPull = playerSquareNeighbor[direction][playerPositionBeforePull]) != NONE &&
                                    ((boxPosition = playerPositionToBoxPosition[playerPositionAfterPull]) == NONE || !boxConfigurationStorage.isBoxAtPosition(targetBoxConfigurationIndex, boxPosition))
                                    &&
                                    // Is the new box position accessible for the box (no deadlock, no wall and no other box at the position).
                                    (boxPositionAfterPull = playerPositionToBoxPosition[playerPositionBeforePull]) != NONE &&
                                    !boxConfigurationStorage.isBoxAtPosition(targetBoxConfigurationIndex, boxPositionAfterPull)
                                    &&
                                    // Check whether there is a box to be pulled.
                                    (boxPositionBeforePull = boxNeighbor[getOppositeDirection(direction)][boxPositionAfterPull]) != NONE &&
                                    boxConfigurationStorage.isBoxAtPosition(targetBoxConfigurationIndex, boxPositionBeforePull)) {

                        // A pull is possible. Hence, add the board position as start position for the backward search.
                        long indexTargetBoardPosition = boardPositionStorage.addIfBetter(0, 0, targetBoxConfigurationIndex, playerPositionBeforePull, SearchDirection.BACKWARD);

                        // There exist levels where all boxes are on goals right from the beginning. In this case, the end board position is
                        // already stored, and we get a negative index which must be ignored!
                        openQueueBackward.add(Math.abs(indexTargetBoardPosition));

                        // A pull is possible from this player position => no more checks for possible pulls from this position are necessary.
                        // (since both axes are checked again for possible pulls when the board position is taken out of the open queue).
                        break;
                    }
                }
            }
        }

        /**
         * Returns the top left reachable player position in the passed box configuration starting from the passed player position.
         * For optimization methods that don't care about moves, the player position is normalized to the top left reachable player position.
         * This way all box configurations having the player in the same access corral are considered as equal which avoids duplicate board positions.
         * */
        int getTopLeftReachablePlayerPosition(int playerPosition, BoxConfiguration boxConfiguration) {

            final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);
            playerPositions.addFirst(playerPosition, 0);

            int newPlayerPosition = 0;
            int boxPosition = 0;
            int topLeftPlayerPosition = playerPosition;

            while ((playerPosition = playerPositions.remove()) != NONE) {
                for (int direction = 0; direction < 4; direction++) {
                    if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||                                              // a wall
                       ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) ||   // a box
                       isForbiddenExternalPosition[playerInternalToExternalPosition[newPlayerPosition]]) {                                                                                   // forbidden position
                        continue;
                    }

                    playerPositions.addIfNew(newPlayerPosition);
                    topLeftPlayerPosition = Math.min(topLeftPlayerPosition, newPlayerPosition);
                }
            }

            return topLeftPlayerPosition;
        }

        /**
         * Constructs a {@code OptimizerSolution} from the passed board positions and returns it.
         *
         * @param solutionBoardPositions board positions of a solution
         * @return {@code OptimizerSolution} created from the passed board positions
         */
        protected OptimizerSolution createSolutionFromBoardPositions(ArrayList<BoardPosition> solutionBoardPositions) {

            // The player moves in the new solution.
            ArrayList<Byte> solutionMoves = new ArrayList<>(10000);

            OptimizerBoard boardToPlay = initialBoard.getClone();

            for(int position=0; position<boardToPlay.size; position++) {        // forbidden positions are not allowed to be visited
                if (isForbiddenExternalPosition[position]) {
                    boardToPlay.setWall(position);
                }
            }

            HashSet<Integer> untouchedBoxPositions = new HashSet<>();       // The positions of the boxes that have not been pushed yet.
            for (int position = 0; position < initialBoard.size; position++) {   // This information is only needed for the "untouched box" optimization method.
                if (initialBoard.isBox(position)) {
                    untouchedBoxPositions.add(position);
                }
            }

            OptimizerSolution solution = new OptimizerSolution();

            int lastPushedBoxPosition = -1;

            // The start is the initial box configuration.
            int previousBoxConfigurationIndex = startBoxConfigurationIndex;

            // Create the solution to be returned.
            for (BoardPosition boardPosition : solutionBoardPositions) {

                // Calculate the box position before the push.
                int boxPositionBeforePush = 0;
                for(int position=0; position<boxPositionsCount; position++) {
                    if (boxConfigurationStorage.isBoxAtPosition(previousBoxConfigurationIndex, position) && !boxConfigurationStorage.isBoxAtPosition(boardPosition.boxConfigurationIndex, position)) {
                        boxPositionBeforePush = position;
                        break;
                    }
                }

                // Calculate the direction of the push and the box position after the push.
                byte direction= 0;
                int boxPositionAfterPush = 0;
                for (direction = 0; direction < 4; direction++) {
                    boxPositionAfterPush = boxNeighbor[direction][boxPositionBeforePush];
                    if (boxPositionAfterPush != NONE &&
                            boxConfigurationStorage.isBoxAtPosition(boardPosition.boxConfigurationIndex, boxPositionAfterPush) &&
                            !boxConfigurationStorage.isBoxAtPosition(previousBoxConfigurationIndex, boxPositionAfterPush)) {
                        break;
                    }
                }

                // Calculate the player position before the push (some methods use normalized player positions, hence boardPosition.playerPosition can't be used).
                int currentInternalPlayerPosition = playerExternalToInternalPosition[boxInternalToExternalPosition[boxPositionBeforePush]];

                int currentExternalPlayerPosition = playerInternalToExternalPosition[currentInternalPlayerPosition];
                int externalPlayerPositionBeforePush = playerInternalToExternalPosition[playerSquareNeighbor[getOppositeDirection(direction)][currentInternalPlayerPosition]];

                // Get the moves to be done to reach the new player position.
                int[] moves = boardToPlay.playerPath.getMovesTo(externalPlayerPositionBeforePush);

                if (moves == null) return null;

                // Add the moves to the solution and adjust the moves counter.
                for (int move : moves) {
                    solutionMoves.add((byte) move);
                }
                solution.movesCount += moves.length;

                // Do the push on the board.
                boardToPlay.removeBox(currentExternalPlayerPosition);
                boardToPlay.setBox(2 * currentExternalPlayerPosition - externalPlayerPositionBeforePush);
                boardToPlay.playerPosition = currentExternalPlayerPosition;

                untouchedBoxPositions.remove(currentExternalPlayerPosition); // If the player pushes a box, then the box is not untouched anymore.

                // Save the push in the solution.
                solutionMoves.add(direction);

                // Adjust the metrics: moves, pushes, box lines, box changes and pushing sessions.
                solution.movesCount++;
                solution.pushesCount++;
                if (currentExternalPlayerPosition != lastPushedBoxPosition || moves.length > 0) {
                    solution.boxLines++;
                }
                if (currentExternalPlayerPosition != lastPushedBoxPosition) {
                    solution.boxChanges++;
                }
                if (moves.length > 0 || solution.movesCount == 1) {  // If the first move is a push then also increase pushingSessions
                    solution.pushingSessions++;
                }

                lastPushedBoxPosition = 2 * currentExternalPlayerPosition - externalPlayerPositionBeforePush;

                // The current box configuration is the previous box configuration for the next loop.
                previousBoxConfigurationIndex = boardPosition.boxConfigurationIndex;
            }

            // Copy the solution into an array having the correct size.
            solution.solution = new byte[solution.movesCount];
            for (int moveNo = 0; moveNo < solution.solution.length; moveNo++) {
                solution.solution[moveNo] = solutionMoves.get(moveNo);
            }

            solution.untouchedBoxCount = untouchedBoxPositions.size();

            return solution;
        }

        /**
         * Calculates the highest number of moves per push in a solution passed as board positions.
         *
         * @return the highest number of moves per push
         */
        protected int getHighestMoveChange(ArrayList<BoardPosition> boardPositionsOfSolution) {
            int highestMoveChange = 0;
            int previousMovesValue = 0;
            for (BoardPosition boardPosition : boardPositionsOfSolution) {
                if (boardPosition.moves - previousMovesValue > highestMoveChange) {
                    highestMoveChange = boardPosition.moves - previousMovesValue;
                }
                previousMovesValue = boardPosition.moves;
            }
            return highestMoveChange;
        }

        /**
         * DEBUG ONLY: displays a statistic about the last optimizer run.
         */
        protected void debugDisplayStatistic() {
            Statistics storageStatistics = boardPositionStorage.getStatistic();
            String logText =
                    String.format("\nStored board positions count: %,11d \n", boardPositionStorage.getNumberOfStoredBoardPositions()) +
                            String.format("Generated box configuration:  %,11d \n", boxConfigurationStorage.getSize()) +
                            String.format("Board positions per box configuration: %,.2f \n", (boardPositionStorage.getNumberOfStoredBoardPositions() / (float) boxConfigurationStorage.getSize())) +
                            String.format("Maximum no. of collisions: %,d \n", storageStatistics.maxCollisions.get()) +
                            String.format("Total no. of collisions: %,d \n", storageStatistics.totalCollisions.get()) +
                            String.format("Average no. of collisions: %,.2f (add / modify)\n", (((double) storageStatistics.totalCollisions.get()) / boardPositionStorage.getNumberOfStoredBoardPositions())) +
                            String.format("Better revisited board positions count: %,d \n", storageStatistics.betterRevisitedCount.get()) +
                            String.format("Total revisited board positions count: %,d \n", storageStatistics.totalRevisitedCount.get()) +
                            String.format("Maximum number of board positions per slot: %,d \n", storageStatistics.highestBoardPositionsPerSlotCount) +
                            String.format("Average number of board positions per slot: %,.2f \n", storageStatistics.averageBoardPositionsPerSlotCount) +
                            String.format("Number of empty slots: %,d  in %% of total slots: %,.2f \n", storageStatistics.emptySlotsCount, (float) 100 * storageStatistics.emptySlotsCount / boxConfigurationStorage.getSize()) +
                            String.format("Total (all threads) time in hash table: %,dms\n\n", storageStatistics.time.get() / 1000000);
            optimizerGUI.addLogTextDebug(logText);

            // TODO: hashtable statistic: how many board positions having x pushes and y moves

        }

        /**
         * The main optimizer search stores all board positions in the storage.
         * This method reconstructs a solution path using those board positions.
         */
        protected abstract class SolutionReconstructionPrimaryMetricsOnly extends SolutionReconstructionAllMetrics {

            public SolutionReconstructionPrimaryMetricsOnly() {
                super(BoardPosition.PUSHES_MOVES_COMPARATOR);    // just set any comparator -> it isn't used in this class anyhow.
            }

            /**
             * Stores a meeting point of the search
             * <p>
             * Every time the forward search and the backward search meet
             * each other, this method is called because this point is later
             * used to reconstruct the found solution board positions.
             *
             * @param boxConfigurationIndex the index of the box configuration where the searches have met
             * @param playerPosition        player position of the board position where the searches have met
             * @param lastMovedBoxPosition  position of the last moved box
             */
            @Override
            public void addMeetingPoint(int boxConfigurationIndex, int playerPosition, int lastMovedBoxPosition) {

                // Since we are only interested in the primary metrics, we just have to store one meeting point.
                if (meetingBoardPositionData.size() == 0) {
                    super.addMeetingPoint(boxConfigurationIndex, playerPosition, lastMovedBoxPosition);
                }
            }

            /**
             * A forward search for reconstructing the best solution by using the
             * saved board positions from the backward search.
             * <p>
             * The backward search has saved the reached board positions and its
             * metrics in the board position storage. The end board position of
             * the backward search is used to reconstruct the solution path.
             *
             * @param meetingBoardPositionData data about where the forward search and the backward search have met each other.
             * @return {@code OptimizerBoardPosition}s of the found path to the end board position
             */
            @Override
            protected ArrayList<BoardPosition> forwardReconstruction(MeetingBoardPositionData meetingBoardPositionData) {

                // Open queue for the search.
                final ArrayDeque<BoardPositionWithPushDirection> openQueueForward = new ArrayDeque<>(10000);

                // Add the board position as start for the search.
                final BoardPositionWithPushDirection startBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, -1, null);
                openQueueForward.add(startBoardPosition);

                // Set the moves and pushes of the board position this search starts with.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, SearchDirection.BACKWARD);
                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                int startBoardPositionMoves = getMoves(boardPosition);
                int startBoardPositionPushes = getPushes(boardPosition);

                // Last board position of the best found path to the initial board position of the level.
                BoardPositionWithPushDirection bestEndBoardPosition = null;

                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                // Used to store the real positions of the boxes of a box configuration. Usually just the index of a box configuration in
                // the global array is used, but for doing pushes it's necessary to copy the real positions in this byte array.
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                // Process all board positions in the queue as long as the optimizer is running.
                MainLoop:
                while (optimizerStatus == RUNNING) {

                    // Take board position from the queue.
                    BoardPositionWithPushDirection currentBoardPosition = openQueueForward.pollLast();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }

                    // Create a box configuration from the index of the box configuration.
                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);

                    playerPositions.addFirst(currentBoardPosition.playerPosition, 0);

                    // Old and new position of the player and a box.
                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // Try to move the player to every direction and do pushes if necessary.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Check whether the new box position is no deadlock square and not blocked by another box.
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        // Get the board position index from the storage.
                                        boardPositionIndex = boardPositionStorage.getBoardPositionIndex(boxConfigurationIndex, newPlayerPosition, SearchDirection.BACKWARD);
                                        if (boardPositionIndex != BoardPositionsStorage.NONE) {

                                            boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                                            int backwardMoves = getMoves(boardPosition);
                                            int backwardPushes = getPushes(boardPosition);

                                            int newBoardPositionMoves = currentBoardPosition.moves + playerPositions.movesDepth + 1;
                                            int newBoardPositionPushes = currentBoardPosition.pushes + 1;

                                            // Check if this board position is on the best found path.
                                            if (backwardMoves == startBoardPositionMoves - newBoardPositionMoves &&
                                                    backwardPushes == startBoardPositionPushes - newBoardPositionPushes) {

                                                // Create a new board position to be added to the open queue.
                                                BoardPositionWithPushDirection newBoardPosition = new BoardPositionWithPushDirection(
                                                        newBoardPositionMoves, newBoardPositionPushes,
                                                        0, 0, 0,
                                                        boxConfigurationIndex, newPlayerPosition, direction, currentBoardPosition);

                                                if (backwardMoves == 0) {
                                                    bestEndBoardPosition = newBoardPosition;
                                                    break MainLoop;
                                                } else {
                                                    openQueueForward.addLast(newBoardPosition);
                                                }

                                                // We have found the next board position on the best solution path -> immediately continue with this board position.
                                                continue MainLoop;
                                            }
                                        }
                                    }
                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }

                // Reconstruct the found path and return an array list of the found board positions.
                ArrayList<BoardPosition> foundPath = new ArrayList<>();
                for (BoardPositionWithPushDirection currentBoardPosition = bestEndBoardPosition; currentBoardPosition != null; currentBoardPosition = currentBoardPosition.previous) {
                    foundPath.add(0, currentBoardPosition);
                }

                return foundPath;
            }

            /**
             * A backward search for reconstructing the best solution by using the
             * saved board positions from the forward search.
             * <p>
             * The forward search has saved the reached board positions and its
             * metrics in the board position storage. The end board position of
             * the forward search is used to reconstruct the solution path.
             *
             * @param meetingBoardPositionData data about the board position where the searches have met each other
             * @return {@code OptimizerBoardPosition}s of the found path to the start board position
             */
            @Override
            protected ArrayList<BoardPosition> backwardReconstruction(MeetingBoardPositionData meetingBoardPositionData) {

                // Open queue for the search.
                final ArrayDeque<BoardPositionWithPushDirection> openQueue = new ArrayDeque<>(10000);

                // Add the board position as start for the search.
                final BoardPositionWithPushDirection startBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, -1, null);
                openQueue.add(startBoardPosition);

                // Set the moves and pushes of the board position this search starts with.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, SearchDirection.FORWARD);
                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                int startBoardPositionMoves = getMoves(boardPosition);
                int startBoardPositionPushes = getPushes(boardPosition);

                // Last board position of the best found path to the initial board position of the level.
                BoardPositionWithPushDirection bestEndBoardPosition = null;

                /* The reachable player positions are calculated in this object. */
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                // For storing the positions of all boxes on the board.
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                MainLoop:
                while (optimizerStatus == RUNNING) {

                    // Take the board position from the queue having the lowest metrics (pushes/moves).
                    BoardPositionWithPushDirection currentBoardPosition = openQueue.pollLast();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }

                    // Create a box configuration by copying the data of the indexed box configuration.
                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);
                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;

                    // Add the start player position and set the current moves depth.
                    playerPositions.addFirst(currentBoardPosition.playerPosition, 0);

                    // Move the player to all reachable positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // Try to move the player to every direction and check whether it's possible to do pulls.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall or a box.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                    (boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {
                                continue;
                            }

                            // The player can move to the new position => store the new position in the queue (if it hasn't been reached, yet).
                            playerPositions.addIfNew(newPlayerPosition);

                            // Check whether there is a box which can be pulled by moving to the new player position. If not, continue with the next move.
                            if ((playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]) == NONE ||  // position next to the player where a box might be
                                    (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                          // position of a possible box to be pulled
                                    !boxConfiguration.isBoxAtPosition(boxPosition) ||                                                      // there is a box next to the player
                                    (newBoxPosition = playerPositionToBoxPosition[playerPosition]) == NONE) {                                      // valid box position
                                continue;
                            }

                            // Pull is possible => do the pull.
                            boxConfiguration.moveBox(boxPosition, newBoxPosition);

                            int boxConfigurationIndex;

                            // The box configuration must be one of the board positions found during the vicinity search.
                            if ((boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration)) != NONE &&
                                    (boardPositionIndex = boardPositionStorage.getBoardPositionIndex(currentBoardPosition.boxConfigurationIndex, playerPosition, SearchDirection.FORWARD)) != BoardPositionsStorage.NONE) {

                                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                                int forwardMoves = getMoves(boardPosition);
                                int forwardPushes = getPushes(boardPosition);

                                // Calculate the moves and pushes of the board position BEFORE the pull has been made!
                                // This must be done to compare them with the stored board positions of the forward search,
                                // which has stored the board positions AFTER a push has been made.
                                int newBoardPositionMoves = currentBoardPosition.moves + playerPositions.movesDepth;
                                int newBoardPositionPushes = currentBoardPosition.pushes;

                                // Check if this board position is on the best found path (-> having the correct metrics).
                                if (forwardMoves == startBoardPositionMoves - newBoardPositionMoves &&
                                        forwardPushes == startBoardPositionPushes - newBoardPositionPushes) {

                                    /* Calculate the new metrics of the board position, that is: AFTER the pull. */
                                    newBoardPositionMoves++;
                                    newBoardPositionPushes++;

                                    // Create a new board position to be added to the open queue.
                                    BoardPositionWithPushDirection newBoardPosition = new BoardPositionWithPushDirection(
                                            newBoardPositionMoves, newBoardPositionPushes,
                                            0, 0, 0,
                                            boxConfigurationIndex, newPlayerPosition, direction, currentBoardPosition);

                                    // Backward can't always reach the start board position because the player needn't be
                                    // next to a box in the start board position. Hence, last board position to search for
                                    // is one having one push. The initial board position (having 0 pushes) is already known
                                    // in advance, hence it needn't be in the reconstructed path.
                                    if (forwardPushes == 1) {
                                        bestEndBoardPosition = newBoardPosition;
                                        break MainLoop;
                                    } else {
                                        // In this backward search we only know that the board position BEFORE the pull has
                                        // been on the best solution path. However, we know have made the pull, and we don't know
                                        // if a pull on the other axis might also be possible. Either of those pushes might be
                                        // the only one that is part of the best solution path. Hence, we have to continue
                                        // the loop over all 4 directions here (in contrast to the forward search which can
                                        // continue with the MainLoop immediately in such a case).
                                        openQueue.addLast(newBoardPosition);
                                    }
                                }
                            }

                            // Undo the pull to reuse the box configuration for the next pull.
                            boxConfiguration.moveBox(newBoxPosition, boxPosition);

                        } // Loop that continues moving the player to all 4 directions until all positions have been visited
                    } // Loop that moves the player to all reachable positions
                }

                // Reconstruct the found path and return an array list of the found board positions.
                ArrayList<BoardPosition> foundPath = new ArrayList<>();

                for (BoardPositionWithPushDirection currentBoardPosition = bestEndBoardPosition; currentBoardPosition != null; currentBoardPosition = currentBoardPosition.previous) {

                    // Note: The search saved the board positions AFTER the pull! However,
                    // we need a forward path, which means the board positions after
                    // a push has been done - which on the other hand means BEFORE the pull.
                    BoardPositionWithPushDirection newBoardPosition = currentBoardPosition;

                    if (currentBoardPosition.pushDirection != -1) {

                        boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);
                        int newPlayerPosition = playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPosition.playerPosition];
                        int boxPosition = playerPositionToBoxPosition[newPlayerPosition];
                        int newBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][newPlayerPosition]];
                        boxConfiguration.moveBox(boxPosition, newBoxPosition);

                        newBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration), newPlayerPosition, -1, null);

                        foundPath.add(newBoardPosition);
                    }

                }

                return foundPath;
            }
        }

        /**
         * The main optimizer search stores all board positions in the storage.
         * Only two metrics (pushes and moves for instance) are saved.
         * This class does a new search over the stored board positions using all 5 metrics.
         */
        protected abstract class SolutionReconstructionAllMetrics {

            // Several threads are accessing this queue at the same time. Hence, a thread safe queue has to be used.
            protected final ConcurrentHashMap<MeetingBoardPositionData, MeetingBoardPositionData> meetingBoardPositionData = new ConcurrentHashMap<>();

            // Best found solution of all solutions reconstructed from the stored data in meetingBoardPositionData.
            protected final AtomicReference<OptimizerSolution> bestFoundSolution = new AtomicReference<>();

            // Comparator for comparing two BoardPositions -> determines whether a moves/pushes search is done or a pushes/moves search.
            protected final Comparator<BoardPosition> COMPARATOR;

            /**
             * Stores the index of the board position where the forward and the backward
             * search have met each other. Additionally, the last moved box position
             * is stored for calculating box changes, box lines, and pushing sessions.
             */
            protected class MeetingBoardPositionData {

                private final int boxConfigurationIndex;
                private final int playerPosition;
                private final int lastMovedBoxPosition;

                /**
                 * A storage for saving the data where the optimizer searches have met each other.
                 *
                 * @param boxConfigurationIndex the box configuration index of the board position where the searches have met
                 * @param playerPosition        the player position of the board position where the searches have met
                 * @param lastMovedBoxPosition  the position of the last pushes/pulled box
                 */
                public MeetingBoardPositionData(int boxConfigurationIndex, int playerPosition, int lastMovedBoxPosition) {
                    this.boxConfigurationIndex = boxConfigurationIndex;
                    this.playerPosition = playerPosition;
                    this.lastMovedBoxPosition = lastMovedBoxPosition;
                }

                @Override
                public int hashCode() {
                    final int prime = 31;
                    int result = prime + boxConfigurationIndex;
                    result = prime * result + lastMovedBoxPosition;
                    result = prime * result + playerPosition;
                    return result;
                }

                @Override
                public boolean equals(Object obj) {
                    if (this == obj) {
                        return true;
                    }
                    if (obj == null) {
                        return false;
                    }
                    if (getClass() != obj.getClass()) {
                        return false;
                    }
                    MeetingBoardPositionData other = (MeetingBoardPositionData) obj;
                    if (boxConfigurationIndex != other.boxConfigurationIndex ||
                            lastMovedBoxPosition != other.lastMovedBoxPosition ||
                            playerPosition != other.playerPosition) {
                        return false;
                    }
                    return true;
                }
            }

            /**
             * Creates a reconstruction object used to search the best solution by using only the
             * board positions generated by the optimizer vicinity search.<br>
             * The search considers all 5 metrics. Which solution is the best is determined by
             * the passed comparator.
             *
             * @param comparator {@code Comparator} for determining the best solution
             */
            public SolutionReconstructionAllMetrics(Comparator<BoardPosition> comparator) {
                COMPARATOR = comparator;
            }

            /**
             * Stores a meeting point of the search
             * <p>
             * Every time the forward search and the backward search meet
             * each other this method is called because this point is later
             * used to reconstruct the found solution board positions.
             *
             * @param boxConfigurationIndex the index of the box configuration where the searches have met
             * @param playerPosition        player position of the board position where the searches have met
             * @param lastMovedBoxPosition  position of the last moved box
             */
            public void addMeetingPoint(int boxConfigurationIndex, int playerPosition, int lastMovedBoxPosition) {
                MeetingBoardPositionData meetingData = new MeetingBoardPositionData(boxConfigurationIndex, playerPosition, lastMovedBoxPosition);
                meetingBoardPositionData.putIfAbsent(meetingData, meetingData);
            }

            /**
             * Deletes all stored meeting points.
             */
            public void removeAllMeetingPoints() {
                meetingBoardPositionData.clear();
            }

            /**
             * Determines the best solution that can be constructed using the board positions
             * that have been stored during the vicinity search. The best solution is found
             * by performing a complete new search considering all 5 metrics.
             *
             * @return the best found solution
             */
            public OptimizerSolution getBestSolution() {

                // Backup the optimizer status since it may be set back to running.
                OptimizerStatus optimizerStatusBackup = optimizerStatus;

                // When the optimizer search couldn't finish normally, we can nevertheless check whether the search has already found
                // a better solution. Hence, the optimizer is set back to "running" for the reconstruction.
                if (optimizerStatus == OptimizerStatus.STOPPED_BY_USER || optimizerStatus == OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY) {
                    optimizerStatus = RUNNING;
                }

                // If no meeting points have been found (user stopped the search, out of memory error, ...),
                // the solution is reconstructed by filling the gap between the forward search and the backward search with board positions
                // of the original solution. Since in this case, there is always just one solution, only one thread needs to do the work.
                if (meetingBoardPositionData.isEmpty()) {

                    // The board positions of the new best found solution.
                    ArrayList<BoardPosition> newSolutionBoardPositions = new ArrayList<>();

                    // Add all board positions of the solution which the forward search has found. Sanity Check: in the rare case
                    // that the search stops before the first board position of the original solution has been reached, there is nothing to reconstruct.
                    if (lastVisitedBoardPositionOfOriginalSolutionForward != null) {
                        newSolutionBoardPositions = backwardReconstruction(
                                new MeetingBoardPositionData(lastVisitedBoardPositionOfOriginalSolutionForward.boxConfigurationIndex,
                                        lastVisitedBoardPositionOfOriginalSolutionForward.playerPosition, -1));
                    }

                    /*
                     *  Since the search directions haven't met, we have to fill the gap in the middle using board positions
                     *  of the solution that has been used for optimizing. This is done now.
                     */

                    // If the forward search hasn't reached any board position of the basis solution, we have to fill the gap
                    // beginning by the very first board position of the basis solution we have => set the flag to true.
                    boolean forwardBoardPositionReached = lastVisitedBoardPositionOfOriginalSolutionForward == null;
                    for (BoardPosition basisSolutionBoardPosition : boardPositionsSolutionToBeOptimized) {

                        // The first relevant board position is the one after the last visited board position of the forward search.
                        if (!forwardBoardPositionReached && basisSolutionBoardPosition.equals(lastVisitedBoardPositionOfOriginalSolutionForward)) {
                            forwardBoardPositionReached = true;
                            continue;
                        }

                        // If we have reached the last visited board position of the backward search, we can stop.
                        if (basisSolutionBoardPosition.equals(lastVisitedBoardPositionOfOriginalSolutionBackward)) {
                            break;
                        }

                        // The search directions haven't met each other. Add board positions that are missing for a complete solution.
                        if (forwardBoardPositionReached) {
                            newSolutionBoardPositions.add(basisSolutionBoardPosition);
                        }

                    }

                    // Add all board positions of the solution which the backward search has found. Sanity Check: in the rare case
                    // that the search stops before the first board position of the original solution has been reached, there is nothing to reconstruct.
                    if (lastVisitedBoardPositionOfOriginalSolutionBackward != null) {
                        newSolutionBoardPositions.addAll(forwardReconstruction(
                                new MeetingBoardPositionData(lastVisitedBoardPositionOfOriginalSolutionBackward.boxConfigurationIndex,
                                        lastVisitedBoardPositionOfOriginalSolutionBackward.playerPosition, -1)));
                    }

                    // Create the new solution and set it as the best found solution.
                    OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);
                    bestFoundSolution.set(newSolution);
                } else {
                    // Reconstruct the solutions using the meeting points using several threads.
                    reconstructFoundSolutions();
                }

                // Set back the old optimizer status if the optimizer hasn't been stopped.
                if (optimizerStatus == RUNNING) {
                    optimizerStatus = optimizerStatusBackup;
                }

                return bestFoundSolution.get();
            }

            /**
             * Reconstructs the solutions from the found meeting points.
             * <p>
             * The forward and the backward search have stored the found board positions in the storage.
             * When both search directions meet each other, the data of this "meeting point" is saved
             * in {@link #meetingBoardPositionData}. This method searches in the stored board positions
             * starting at those meeting points and reconstructs the solution path, that is:
             * the board positions from the initial board position to the end board position.
             */
            protected void reconstructFoundSolutions() {

                final ExecutorService executor = Executors.newFixedThreadPool(FORWARD_SEARCH_THREADS_COUNT + BACKWARD_SEARCH_THREADS_COUNT);
                final ConcurrentLinkedQueue<MeetingBoardPositionData> meetingBoardPositions = new ConcurrentLinkedQueue<>(meetingBoardPositionData.values());

                final Runnable reconstruction = () -> {
                    MeetingBoardPositionData meetingData = null;

                    // Reconstruct and check solutions until all data have been processed.
                    while ((meetingData = meetingBoardPositions.poll()) != null && optimizerStatus == RUNNING) {

                        long time = System.currentTimeMillis();

                        // First reconstruct the solution part from the meeting point of the initial board position in the level
                        // using a backward search.
                        // Then add the board positions of the solution from the meeting point to the end of the solution.
                        // Result: all board positions of the solution.
                        ArrayList<BoardPosition> newSolutionBoardPositions = backwardReconstruction(meetingData);
                        ArrayList<BoardPosition> boardPositionsForwardReconstruction = forwardReconstruction(meetingData);

                        // If the optimizer has been stopped, not all board positions have been reconstructed and therefore the solution isn't valid.
                        if (optimizerStatus == RUNNING) {

                            // The backward reconstruction ended with the board position, the forward search
                            // used as start board position. To avoid a duplicate, the first board position is therefore removed.
                            if (boardPositionsForwardReconstruction.size() > 0) {
                                boardPositionsForwardReconstruction.remove(0);
                            }

                            // Combine the board positions of the forward and the backward search to a whole solution.
                            newSolutionBoardPositions.addAll(boardPositionsForwardReconstruction);

                            OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);

                            // Check whether this is a new best solution. If yes, set it as the new best solution using "Compare and swap".
                            // This also works for pushes/moves optimizing, because then all solutions have the same number of pushes.
                            OptimizerSolution currentBestSolution = null;
                            do {
                                currentBestSolution = bestFoundSolution.get();
                            } while (newSolution.isBetterMovesPushesAllMetricsThan(currentBestSolution) && !bestFoundSolution.compareAndSet(currentBestSolution, newSolution));


                            if (Debug.isDebugModeActivated) {
                                optimizerGUI.addLogTextDebug("Solution reconstruction time: " + (System.currentTimeMillis() - time) + "  Solution: " + newSolution);
                            }
                        }
                    }
                };

                for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                    executor.execute(reconstruction);
                }
                Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            }

            /**
             * Get the moves value from the passed board position.
             *
             * @param boardPosition board position to extract the number of moves
             * @return the number of moves
             */
            protected abstract int getMoves(StorageBoardPosition boardPosition);

            /**
             * Get the pushes value from the passed board position.
             *
             * @param boardPosition board position to extract the number of pushes from
             * @return the number of pushes
             */
            protected abstract int getPushes(StorageBoardPosition boardPosition);


            /**
             * A forward search for reconstructing the best solution by using the
             * saved board positions from the backward search.
             * <p>
             * The backward search has saved the reached board positions and its
             * metrics in the board position storage. The end board position of
             * the backward search is used to reconstruct the solution path.
             *
             * @param meetingBoardPositionData data about where the forward search and the backward search have met each other.
             * @return {@code OptimizerBoardPosition}s of the found path to the end board position
             */
            protected ArrayList<BoardPosition> forwardReconstruction(MeetingBoardPositionData meetingBoardPositionData) {

                // Used for detecting duplicate board positions.
                final HashMap<BoardPositionWithPushDirection, BoardPositionWithPushDirection> storage = new HashMap<>();

                // Open queue for the search.
                final PriorityQueue<BoardPositionWithPushDirection> openQueueForward = new PriorityQueue<>(10000, COMPARATOR);

                // Add the board position as start for the search.
                final BoardPositionWithPushDirection startBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, -1, null);
                openQueueForward.add(startBoardPosition);

                // Set the moves and pushes of the board position this search starts with.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, SearchDirection.BACKWARD);
                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                int startBoardPositionMoves = getMoves(boardPosition);
                int startBoardPositionPushes = getPushes(boardPosition);

                // Last board position of the best found path to the initial board position of the level.
                BoardPositionWithPushDirection bestEndBoardPosition = null;

                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                // Used to store the real positions of the boxes of a box configuration. Usually just the index of a box configuration in
                // the global array is used but for doing pushes it's necessary to copy the real positions in this byte array.
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                // Process all board positions in the queue as long as the optimizer is running.
                while (optimizerStatus == RUNNING) {

                    // Take board position from the queue.
                    BoardPositionWithPushDirection currentBoardPosition = openQueueForward.poll();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }

                    // Create a box configuration from the index of the box configuration.
                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);

                    playerPositions.addFirst(currentBoardPosition.playerPosition, 0);

                    // Old and new position of the player and a box.
                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    // Calculate the position of the last pushed box. The first board position can be identified by having a push direction of -1.
                    int lastPushedBoxPosition = currentBoardPosition.pushDirection == -1 ? meetingBoardPositionData.lastMovedBoxPosition :
                            playerPositionToBoxPosition[playerSquareNeighbor[currentBoardPosition.pushDirection][currentBoardPosition.playerPosition]];

                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // Try to move the player to every direction and do pushes if necessary.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Check whether the new box position is no deadlock square and not blocked by another box.
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        // Get the board position index from the storage.
                                        boardPositionIndex = boardPositionStorage.getBoardPositionIndex(boxConfigurationIndex, newPlayerPosition, SearchDirection.BACKWARD);
                                        if (boardPositionIndex != BoardPositionsStorage.NONE) {

                                            boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                                            int backwardMoves = getMoves(boardPosition);
                                            int backwardPushes = getPushes(boardPosition);

                                            int newBoardPositionMoves = currentBoardPosition.moves + playerPositions.movesDepth + 1;
                                            int newBoardPositionPushes = currentBoardPosition.pushes + 1;

                                            // Check if this board position is on the best found path.
                                            if (backwardMoves == startBoardPositionMoves - newBoardPositionMoves &&
                                                    backwardPushes == startBoardPositionPushes - newBoardPositionPushes) {

                                                int boxLines = currentBoardPosition.boxLines;
                                                int boxChanges = currentBoardPosition.boxChanges;
                                                int pushingSessions = currentBoardPosition.pushingSessions;
                                                boolean lastMovementWasMove = playerPositions.movesDepth != 0;// check whether a move has been done

                                                if (boxPosition != lastPushedBoxPosition || lastMovementWasMove) {
                                                    boxLines++;
                                                }
                                                if (boxPosition != lastPushedBoxPosition) {
                                                    boxChanges++;
                                                }
                                                if (lastMovementWasMove) {
                                                    pushingSessions++;
                                                }

                                                // Create a new board position to be added to the open queue.
                                                BoardPositionWithPushDirection newBoardPosition = new BoardPositionWithPushDirection(
                                                        newBoardPositionMoves, newBoardPositionPushes,
                                                        boxLines, boxChanges, pushingSessions,
                                                        boxConfigurationIndex, newPlayerPosition, direction, currentBoardPosition);

                                                if (backwardMoves == 0) {

                                                    // Check whether the new board position has been reached better than the current
                                                    // best end board position. If yes, then set the new board position as the new best one.
                                                    if (COMPARATOR.compare(newBoardPosition, bestEndBoardPosition) < 0) {
                                                        bestEndBoardPosition = newBoardPosition;
                                                    }
                                                } else {
                                                    BoardPositionWithPushDirection duplicateBoardPosition = storage.get(newBoardPosition);

                                                    // If the new board position hasn't been reached before, or it has been reached
                                                    // better, then store it and add it to the open queue.
                                                    if (duplicateBoardPosition == null || COMPARATOR.compare(newBoardPosition, duplicateBoardPosition) < 0) {

                                                        // Replace the old with the new board position.
                                                        storage.put(newBoardPosition, newBoardPosition);

                                                        // Add the board position to the open queue for further searching.
                                                        openQueueForward.add(newBoardPosition);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }

                // Reconstruct the found path and return an array list of the found board positions.
                ArrayList<BoardPosition> foundPath = new ArrayList<>();
                for (BoardPositionWithPushDirection currentBoardPosition = bestEndBoardPosition; currentBoardPosition != null; currentBoardPosition = currentBoardPosition.previous) {
                    foundPath.add(0, currentBoardPosition);
                }

                return foundPath;
            }

            /**
             * A backward search for reconstructing the best solution by using the
             * saved board positions from the forward search.
             * <p>
             * The forward search has saved the reached board positions and its
             * metrics in the board position storage. The end board position of
             * the forward search is used to reconstruct the solution path.
             *
             * @param meetingBoardPositionData data about the board position where the searches have met each other
             * @return {@code OptimizerBoardPosition}s of the found path to the start board position
             */
            protected ArrayList<BoardPosition> backwardReconstruction(MeetingBoardPositionData meetingBoardPositionData) {

                // Used for detecting duplicate board positions.
                final HashMap<BoardPositionWithPushDirection, BoardPositionWithPushDirection> storage = new HashMap<>();

                // Open queue for the search.
                final PriorityQueue<BoardPositionWithPushDirection> openQueue = new PriorityQueue<>(10000, COMPARATOR);

                // Add the board position as start for the search.
                final BoardPositionWithPushDirection startBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, -1, null);
                openQueue.add(startBoardPosition);

                // Set the moves and pushes of the board position this search starts with.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, SearchDirection.FORWARD);
                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                int startBoardPositionMoves = getMoves(boardPosition);
                int startBoardPositionPushes = getPushes(boardPosition);

                // Last board position of the best found path to the initial board position of the level.
                BoardPositionWithPushDirection bestEndBoardPosition = null;

                /* The reachable player positions are calculated in this object. */
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                // For storing the positions of all boxes on the board.
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    // Take the board position from the queue having the lowest metrics (pushes/moves).
                    BoardPositionWithPushDirection currentBoardPosition = openQueue.poll();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }

                    // Create a box configurations by copying the data of the indexed box configuration.
                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);
                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;

                    // Calculate the position of the last pulled box. The first board position can be identified by having a push direction of -1.
                    int lastPulledBoxPosition = currentBoardPosition.pushDirection == -1 ? meetingBoardPositionData.lastMovedBoxPosition :
                            playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPosition.playerPosition]];

                    // Add the start player position and set the current move depth.
                    playerPositions.addFirst(currentBoardPosition.playerPosition, 0);

                    // Move the player to all reachable positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // Try to move the player to every direction and check whether it's possible to do pulls.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall or a box.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                    (boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {
                                continue;
                            }

                            // The player can move to the new position => store the new position in the queue (if it hasn't been reached, yet).
                            playerPositions.addIfNew(newPlayerPosition);

                            // Check whether there is a box that can be pulled by moving to the new player position. If not, continue with the next move.
                            if ((playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]) == NONE ||  // position next to the player where a box might be
                                    (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                          // position of a possible box to be pulled
                                    !boxConfiguration.isBoxAtPosition(boxPosition) ||                                                      // there is a box next to the player
                                    (newBoxPosition = playerPositionToBoxPosition[playerPosition]) == NONE) {                                      // valid box position
                                continue;
                            }

                            // Pull is possible => do the pull.
                            boxConfiguration.moveBox(boxPosition, newBoxPosition);

                            int boxConfigurationIndex;

                            // The box configuration must be one of the board positions found during the vicinity search.
                            if ((boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration)) != NONE &&
                                    (boardPositionIndex = boardPositionStorage.getBoardPositionIndex(currentBoardPosition.boxConfigurationIndex, playerPosition, SearchDirection.FORWARD)) != BoardPositionsStorage.NONE) {

                                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                                int forwardMoves = getMoves(boardPosition);
                                int forwardPushes = getPushes(boardPosition);

                                // Calculate the moves and pushes of the board position BEFORE the pull has been made!
                                // This must be done to compare them with the stored board positions of the forward search,
                                // which has stored the board positions AFTER a push has been made.
                                int newBoardPositionMoves = currentBoardPosition.moves + playerPositions.movesDepth;
                                int newBoardPositionPushes = currentBoardPosition.pushes;

                                // Check if this board position is on the best found path (-> having the correct metrics).
                                if (forwardMoves == startBoardPositionMoves - newBoardPositionMoves &&
                                        forwardPushes == startBoardPositionPushes - newBoardPositionPushes) {

                                    /* Calculate the new metrics of the board position, that is: AFTER the pull. */
                                    newBoardPositionMoves++;
                                    newBoardPositionPushes++;

                                    int boxLines = currentBoardPosition.boxLines;
                                    int boxChanges = currentBoardPosition.boxChanges;
                                    int pushingSessions = currentBoardPosition.pushingSessions;
                                    boolean lastMovementWasMove = playerPositions.movesDepth != 0;// check whether a move has been done

                                    if (boxPosition != lastPulledBoxPosition || lastMovementWasMove) {
                                        boxLines++;
                                    }
                                    if (boxPosition != lastPulledBoxPosition) {
                                        boxChanges++;
                                    }
                                    if (lastMovementWasMove) {
                                        pushingSessions++;
                                    }

                                    // Create a new board position to be added to the open queue.
                                    BoardPositionWithPushDirection newBoardPosition = new BoardPositionWithPushDirection(
                                            newBoardPositionMoves, newBoardPositionPushes,
                                            boxLines, boxChanges, pushingSessions,
                                            boxConfigurationIndex, newPlayerPosition, direction, currentBoardPosition);

                                    // Backward can't always reach the start board position because the player needn't be
                                    // next to a box in the start board position. Hence, last board position to search for
                                    // is one having one push. The initial board position (having 0 pushes) is already known
                                    // in advance, hence it needn't be in the reconstructed path.
                                    if (forwardPushes == 1) {

                                        // Check whether the new board position has been reached better than the current
                                        // best end board position. If yes, then set the new board position as new best one.
                                        if (COMPARATOR.compare(newBoardPosition, bestEndBoardPosition) < 0) {
                                            bestEndBoardPosition = newBoardPosition;
                                        }
                                    } else {
                                        BoardPositionWithPushDirection duplicateBoardPosition = storage.get(newBoardPosition);

                                        // If the new board position hasn't been reached before, or it has been reached
                                        // better, then store it and add it to the open queue.
                                        if (duplicateBoardPosition == null || COMPARATOR.compare(newBoardPosition, duplicateBoardPosition) < 0) {

                                            // Replace the old with the new board position.
                                            storage.put(newBoardPosition, newBoardPosition);

                                            // Add the board position to the open queue for further searching.
                                            openQueue.add(newBoardPosition);
                                        }
                                    }
                                }
                            }

                            // Undo the pull to reuse the box configuration for the next pull.
                            boxConfiguration.moveBox(newBoxPosition, boxPosition);

                        } // Loop that continues moving the player to all 4 directions until all positions have been visited
                    } // Loop that moves the player to all reachable positions
                }

                // Reconstruct the found path and return an array list of the found board positions.
                ArrayList<BoardPosition> foundPath = new ArrayList<>();

                for (BoardPositionWithPushDirection currentBoardPosition = bestEndBoardPosition; currentBoardPosition != null; currentBoardPosition = currentBoardPosition.previous) {

                    /*
                     Note: The search saved the board positions AFTER the pull!
                     However, we need a forward path, which means the board positions after
                     a push has been done - which, on the other hand, means BEFORE the pull.
                    */

                    BoardPositionWithPushDirection newBoardPosition = currentBoardPosition;

                    if (currentBoardPosition.pushDirection != -1) {

                        boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);
                        int newPlayerPosition = playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPosition.playerPosition];
                        int boxPosition = playerPositionToBoxPosition[newPlayerPosition];
                        int newBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][newPlayerPosition]];
                        boxConfiguration.moveBox(boxPosition, newBoxPosition);

                        newBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration), newPlayerPosition, -1, null);

                        foundPath.add(newBoardPosition);
                    }

                }

                return foundPath;
            }
        }
    }

    /**
     * Class for searching for a better solution by only using the generated
     * box configurations.<br>
     * The new solution is optimized for: <br>
     * 1. pushes<br>
     * 2. moves<br>
     * 3. box lines<br>
     * 4. box changes<br>
     * 5. pushing sessions<br>
     * Before this method is called the box configurations to be used must
     * have been generated.
     */
    private class PushesMovesAllMetricsOptimizer extends AllMetricsOptimizer {

        /**
         * The board positions are stored in the priority queue ordered by their order values calculated by the {@link #boardPositionStorage}.
         * The order value is calculated: primaryMetric * (estimated maximum number of secondaryMetric) + secondaryMetric
         * Since we don't know the maximum number of moves of the new solution the optimizer can only estimate that value.
         * This constant is used to estimate the maximum value by multiplying the moves count of the solution to be optimized by this factor.
         */
        public static final int MAXIMAL_MOVES_FACTOR = 3;

        // Pushes depth of both searches
        protected final AtomicInteger pushesDepthBackwardSearch = new AtomicInteger(-1);
        protected final AtomicInteger pushesDepthForwardSearch = new AtomicInteger(-1);

        // Collects the solutions found during the search and can reconstruct the player moves to the solution.
        public SolutionReconstructionAllMetrics solutionReconstruction = new SolutionReconstructionPushesMoves();


        /**
         * Creates a new object for searching for a better solution regarding
         * first pushes and second moves.
         */
        public PushesMovesAllMetricsOptimizer() {
        }

        /**
         * Optimizes the passed solution by only using the generated box configurations.<br>
         * The path is optimal regarding: <br>
         * 1. pushes<br>
         * 2. moves<br>
         *
         * @param initialBoard        the board at level start
         * @param endBoard            the end board having all boxes on a goal
         * @param currentBestSolution the currently best solution
         * @return the best found solution
         */
        public OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentBestSolution) {

            // Save the passed solution, so it can be accessed in all methods.
            this.solutionToBeOptimized = currentBestSolution;

            // Used to store the real box positions of the box configuration.
            // Usually just the index of a box configuration in the global array
            // is used, but for doing pushes it's necessary to copy the real
            // positions in this byte array.
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Yet no solution has been found. However, the current best solution is used as upper bound for the pushes and moves.
            newBestSolutionMovesCount = currentBestSolution.movesCount;
            newBestSolutionPushesCount = currentBestSolution.pushesCount;

            // Get the index of the initial box configuration.
            packBoxConfiguration(boxConfiguration, initialBoard);
            startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            // Get a list of all board positions of the solution.
            boardPositionsSolutionToBeOptimized = getSolutionBoardPositions(currentBestSolution);

            // Calculate the highest move increase per push in the solution.
            int highestMoveChange = getHighestMoveChange(boardPositionsSolutionToBeOptimized);

            // Moves and pushes are stored in a "order value" for the priority queue. The order value is calculated:
            // pushes * (estimated maximum number of moves) + moves
            // This optimizer just knows that the value will change by 1 push (which is multiplied by the estimated
            // maximum number of moves) and x moves.
            // The highest move change in the current best solution is "highestMoveChange".
            // We estimate that the highest move change in the new solution is at most 10 times higher than that.
            // If it is indeed higher than that, this will slow down the optimizer.
            final int estimatedMaximumNewMovesCount = MAXIMAL_MOVES_FACTOR * currentBestSolution.movesCount;
            final int maximumOrderValueChange = 1 * estimatedMaximumNewMovesCount + 10 * highestMoveChange; // = 1 Push * maximumMovesCount + 10 * highest move change
            final int minimumOrderValueChange = 1 * estimatedMaximumNewMovesCount + 1;                   // = 1 Push and 1 move

            // Create the storage.
            boardPositionStorage = new BoardPositionsStorage(boxConfigurationStorage.getSize(), playerSquaresCount, 2, estimatedMaximumNewMovesCount, estimatedMaximumNewMovesCount);

            // Create the open queues for both search
            openQueueForward = PriorityQueueOptimizer.getInstance(minimumOrderValueChange, maximumOrderValueChange, boardPositionStorage, FORWARD_SEARCH_THREADS_COUNT);
            openQueueBackward = BACKWARD_SEARCH_THREADS_COUNT == 0 ?
                    PriorityQueueOptimizer.getInstance(2, 2, boardPositionStorage, 1) : // Dummy queue for storing the start positions
                    PriorityQueueOptimizer.getInstance(minimumOrderValueChange, maximumOrderValueChange, boardPositionStorage, BACKWARD_SEARCH_THREADS_COUNT);

            // Add the start board position for the forward search to the open queue
            // and store it in the reachedPositionsStorage for detecting duplicates.
            long indexStartBoardPosition = boardPositionStorage.addIfBetter(0, 0, startBoxConfigurationIndex, playerExternalToInternalPosition[initialBoard.playerPosition], SearchDirection.FORWARD);
            openQueueForward.add(indexStartBoardPosition);

            // Add start board positions for the backward search.
            addBackwardBoardPositions();

            // Execute the optimizer searches.
            final ExecutorService executor = Executors.newFixedThreadPool(FORWARD_SEARCH_THREADS_COUNT + BACKWARD_SEARCH_THREADS_COUNT);
            for (int threadNo = 0; threadNo < FORWARD_SEARCH_THREADS_COUNT; threadNo++) {
                executor.execute(new PushesMovesForwardSearch(threadNo));
            }
            for (int threadNo = 0; threadNo < BACKWARD_SEARCH_THREADS_COUNT; threadNo++) {
                executor.execute(new PushesMovesBackwardSearch(threadNo));
            }

            Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            if (Debug.isDebugModeActivated) {
                debugDisplayStatistic();
            }

            // Inform the user about the RAM that is available after the search has ended.
            optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMinMiB()));

            // TODO: display message that solution is reconstructed (may take several seconds!)

            // The optimizer has only been stopped to start a new iteration with the new best found solution.
            // Hence, the optimizer is still running.
            if (optimizerStatus == OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION) {
                optimizerStatus = RUNNING;
            }

            // Return the best found solution (or null if no solution has been found, when the
            // optimizer has been stopped by user, out of memory, ...)
            return getBestFoundSolution();
        }

        @Override
        public OptimizerSolution getBestFoundSolution() {
            synchronized (mainOptimizerThread) {
                // Initialize the best found solution since we search for a new one.
                // This is necessary at each call, since the user can request to get
                // the best solution at any time during the search, which means
                // this coding may be executed several times.
                solutionReconstruction.bestFoundSolution.set(null);

                return solutionReconstruction.getBestSolution();
            }
        }

        /**
         * A forward search considering first pushes, second moves.
         */
        private class PushesMovesForwardSearch implements Runnable {

            /**
             * Unique ID for every thread for accessing the priority queue.
             */
            private final int priorityQueueThreadID;

            /**
             * Creates a {@code Runnable} that performs a forward search for the optimizer.
             *
             * @param priorityQueueThreadID unique ID for the priority queue
             */
            public PushesMovesForwardSearch(int priorityQueueThreadID) {
                this.priorityQueueThreadID = priorityQueueThreadID;
            }

            /**
             * Starts a new forward search.
             */
            @Override
            public void run() {

                try {
                    // Start a new forward search.
                    forwardSearch();

                } catch (OutOfMemoryError e) {
                    // Stop the optimizer by setting the proper stop reason.
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }

            /**
             * Forward search searching from the start board position forward
             * (-> pushing boxes) until it meets the backward search.
             */
            private void forwardSearch() {

                // Object holding all information about one board position.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Used to calculate the player-reachable positions.
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                // Used to store the real positions of the boxes of a box configuration. Usually just the index of a box configuration in
                // the global array is used, but for doing pushes it's necessary to copy the real positions in this byte array.
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);


                while (optimizerStatus == RUNNING) {

                    // Take board position from the queue.
                    long currentBoardPositionIndex = openQueueForward.removeFirst(priorityQueueThreadID);

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPositionIndex == NONE) {
                        break;
                    }

                    // Mark as processed - BEFORE the real metrics are read. This ensures that when
                    // the board position is reached better in the meantime, the new better values are read.
                    boardPositionStorage.markAsProcessed(currentBoardPositionIndex);

                    // Get the values from the board position.
                    boardPositionStorage.fillBoardPosition(currentBoardPositionIndex, boardPosition);

                    // At least one push and one move is done -> adjust the metrics.
                    int movesDepth = boardPosition.secondaryMetric + 1;
                    int pushesDepth = boardPosition.primaryMetric + 1;
                    int currentBoxConfigurationIndex = boardPosition.boxConfigurationIndex;

                    // If a new pushes depth has been reached then:
                    // - discard the board position when the total depth (forward + backward search) is higher than the current best solution pushes count
                    // - inform the user about this new depth and the currently best found metrics
                    int currentDepth = pushesDepthForwardSearch.get();
                    if (pushesDepth > currentDepth) {
                        // Note: this isn't completely thread safe:
                        // - a slow thread may still be adding board positions having less pushes, hence no "break" here.
                        // - in a rare case both directions may get a "false" be checking the depth and then both increase
                        //   the total depth to a higher value than "newBestSolutionPushesCount". This is no problem.
                        if (pushesDepth + pushesDepthBackwardSearch.get() > newBestSolutionPushesCount) {
                            continue;
                        }
                        if (pushesDepthForwardSearch.compareAndSet(currentDepth, pushesDepth)) {
                            checkForImprovement();
                        }
                    }

                    playerPositions.addFirst(boardPosition.playerPosition, movesDepth);

                    // Old and new position of the player and a box.
                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    // Create a box configuration from the index of the box configuration.
                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                    // Breadth-first search over all reachable player positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // Try to move the player to every direction and do pushes if necessary.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Is the possible new position of the box no deadlock square and not blocked by another box?
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        long addedBoardPositionIndex = boardPositionStorage.addIfBetter(pushesDepth, playerPositions.movesDepth, boxConfigurationIndex, newPlayerPosition, SearchDirection.FORWARD);
                                        if (addedBoardPositionIndex != BoardPositionsStorage.NONE) {

                                            // Check whether a solution has been found due to meeting the backward search -> negative index is returned.
                                            if (addedBoardPositionIndex < 0) {
                                                addedBoardPositionIndex = -addedBoardPositionIndex;
                                                collectBestSolutions(SearchDirection.FORWARD, playerPositions.movesDepth, pushesDepth, boxConfigurationIndex, newPlayerPosition, newBoxPosition);
                                            }

                                            // Add the board position for further searching - even when the searches have met, this search might just have
                                            // reached a suboptimal backwards board position and therefore must continue the search with the current board position.
                                            openQueueForward.add(addedBoardPositionIndex);
                                        }
                                    }
                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }
            }

            /**
             * This method informs the user whether a better path to any of the board positions of the solution to be optimized
             * has been found and about the current pushes depth that the search has reached.
             */
            private void checkForImprovement() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Identify the board position having the highest number of pushes that has already been reached by
                // the forward search and is part of the basis solution to be optimized and check for any improvements.
                // Ignore the last board position because there are levels that start with all boxes on goals.
                // For these levels start and end board position are the same.
                for (int index = boardPositionsSolutionToBeOptimized.size() - 1; --index != -1; ) {

                    // Get the next board position of the basis solution.
                    BoardPosition basisSolutionBoardPosition = boardPositionsSolutionToBeOptimized.get(index);

                    // Check whether the board position has already been reached by the forward search.
                    long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(basisSolutionBoardPosition, SearchDirection.FORWARD);
                    if (boardPositionIndex != BoardPositionsStorage.NONE) {

                        boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);

                        // Calculate how much better the board position has been reached compared to the basis solution.
                        int pushesDif = basisSolutionBoardPosition.pushes - boardPosition.primaryMetric;
                        int movesDif = basisSolutionBoardPosition.moves - boardPosition.secondaryMetric;

                        // Backward search reads these variables, too, hence synchronize on application.
                        synchronized (mainOptimizerThread) {

                            // The pushes range searches for improvements for forward and backwards search mustn't overlap!
                            if (lastVisitedBoardPositionOfOriginalSolutionBackward != null && basisSolutionBoardPosition.pushes >= lastVisitedBoardPositionOfOriginalSolutionBackward.pushes) {
                                continue;
                            }

                            if (pushesDif > pushesImprovementForwardSearch || pushesDif == pushesImprovementForwardSearch && movesDif > movesImprovementForwardSearch) {
                                pushesImprovementForwardSearch = pushesDif;
                                movesImprovementForwardSearch = movesDif;

                                if (immediateNewIterationWhenImprovement && optimizerStatus == RUNNING) {
                                    optimizerStatus = OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION;
                                }
                            }

                            // Save the last visited board position of the original solution if it has been reached on the currently best known path.
                            if (pushesImprovementForwardSearch == pushesDif && movesImprovementForwardSearch == movesDif) {
                                lastVisitedBoardPositionOfOriginalSolutionForward = basisSolutionBoardPosition;
                            }
                        }

                        break;
                    }
                }

                // Display the new pushes depth and the current best solution metrics.
                displayIntermediateResult();

            } // end of method "checkForImprovement"
        }

        /**
         * A backward search considering first pushes, second moves.
         */
        private class PushesMovesBackwardSearch implements Runnable {

            /**
             * The reachable player positions are calculated in this object.
             */
            private final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

            /**
             * Unique ID for every thread for accessing the priority queue.
             */
            private final int priorityQueueThreadID;

            /**
             * Creates a {@code Runnable} that performs a backward search for the optimizer.
             *
             * @param priorityQueueThreadID unique ID for the priority queue
             */
            public PushesMovesBackwardSearch(int priorityQueueThreadID) {
                this.priorityQueueThreadID = priorityQueueThreadID;
            }

            /**
             * Starts a new backward search.
             */
            @Override
            public void run() {

                try {
                    // Start the backward search.
                    backwardSearch();

                } catch (OutOfMemoryError e) {
                    // Stop the optimizer by setting the proper stop reason.
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }


            /**
             * Backward search searching from the end board positions (all boxes
             * on a goal) backwards (-> pulling boxes) until it meets the forward search.
             */
            private void backwardSearch() {

                // Object holding all information about one board position (boxConfigurationIndex, metrics, ...)
                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                /*
                 *  Used to store the real positions of the boxes of a box
                 *  configuration. Usually just the index of a box configuration
                 *  in the global array is used but for doing pushes it's necessary
                 *  to work with the actual box positions.
                 */
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);


                while (optimizerStatus == RUNNING) {

                    // Take the board position from the queue having the lowest metrics (pushes/moves).
                    long currentBoardPositionIndex = openQueueBackward.removeFirst(priorityQueueThreadID);

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPositionIndex == NONE) {
                        break;
                    }

                    // Mark as processed - BEFORE the real metrics are read. This ensures that when
                    // the board position is reached better in the meantime, the new better values are read.
                    boardPositionStorage.markAsProcessed(currentBoardPositionIndex);

                    // Get the values from the board position.
                    boardPositionStorage.fillBoardPosition(currentBoardPositionIndex, boardPosition);

                    // Since we now do a pull the successor board position will have at least one more push and one more move.
                    boardPosition.primaryMetric++;
                    boardPosition.secondaryMetric++;

                    // If a new pushes depth has been reached then:
                    // - discard the board position when the total depth (forward + backward search) is higher than the current best solution pushes count
                    // - inform the user about this new depth and the currently best found metrics
                    int currentDepth = pushesDepthBackwardSearch.get();
                    if (boardPosition.primaryMetric > currentDepth) {
                        // Note: this isn't completely thread safe:
                        // - a slow thread may still be adding board positions having less pushes, hence no "break" here.
                        // - in a rare case both directions may get a "false" be checking the depth and then both increase
                        //   the total depth to a higher value than "newBestSolutionPushesCount". This is no problem.
                        if (boardPosition.primaryMetric + pushesDepthForwardSearch.get() > newBestSolutionPushesCount) {
                            continue;
                        }
                        if (pushesDepthBackwardSearch.compareAndSet(currentDepth, boardPosition.primaryMetric)) {
                            checkForImprovement();
                        }
                    }

                    // The box configuration is just an index in the data where the box positions are stored. This data
                    // is now copied to an local array "boxConfiguration".
                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, boardPosition.boxConfigurationIndex);

                    /*
                     * The forward search stores the board positions AFTER a push has been done. In order to be able to
                     * compare backward board positions with those stored by the forward search the backward search
                     * stores the board positions BEFORE a pull has been made.
                     * Hence, the actual pull has to be done now, after the board position has been taken out of the
                     * open queue. The search then continues taking the board position after the pull as basis for further pulls.
                     * Note: a pull can be done horizontally or vertically. Hence, two possible pulls might be possible from the board position taken out of the queue!
                     */

                    int newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;
                    int playerPosition = boardPosition.playerPosition;

                    // Calculate the new box position (must be a valid one because otherwise the board position hadn't been saved in the open queue).
                    newBoxPosition = playerPositionToBoxPosition[playerPosition];

                    // Do all possible pulls (max 2 pulls are possible - one per axis).
                    for (int direction = 0; direction < 4; direction++) {

                        // If any neighbor on this axis is a wall then no pull on this axis is possible => continue with the other axis.
                        if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                (playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]) == NONE) {
                            direction |= 1; // 0=UP, 1=DOWN, 2=LEFT, 3=RIGHT
                            continue;
                        }

                        // Immediately continue with the next direction if the pull to the direction isn't possible.
                        if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition) ||   // player can't reach new position due to a box
                                (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                                    // position of the box to be pulled is an invalid (deadlock square)
                                !boxConfiguration.isBoxAtPosition(boxPosition)) {                                                                // there is no box to be pulled
                            continue;
                        }

                        // A pull to the current direction is possible. This means a pull to the opposite direction is impossible.
                        direction |= 1; // 0=UP, 1=DOWN, 2=LEFT, 3=RIGHT

                        // Do the pull.
                        boxConfiguration.moveBox(boxPosition, newBoxPosition);

                        // Adjust the board position data because a pull has been made.
                        boardPosition.boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);    // new box configuration index
                        //						boardPosition.playerPosition 		= newPlayerPosition;													// is set in next "if"-Condition
                        //						boardPosition.primaryMetric++;																			    // already set above the for-loop
                        //						boardPosition.secondaryMetric++;																			// already set above the for-loop
                        //						boardPosition.previousBoardPositionIndex = currentBoardPositionIndex;										// already set above the for-loop

                        // When the board position has been added to the open queue only one possible pull has been checked for resulting in a box configuration
                        // stored in boxConfigurationStorage. Since we here do pulls on both axis the boxConfigurationIndex must be checked for being "NONE"!
                        if (boardPosition.boxConfigurationIndex != NONE) {

                            // Set the player position after the pull.
                            boardPosition.playerPosition = newPlayerPosition;

                            // The board position AFTER the pull has been "recreated". Use this board position for generating successor board positions.
                            generateSuccessorBoardPositions(boardPosition, boxConfiguration);
                        }

                        // Undo the pull.
                        boxConfiguration.moveBox(newBoxPosition, boxPosition);

                    } // Loop that recreates the "real board positions" taken out of the queue (-> board positions AFTER the pull)
                } // End of while taking new board positions from the open queue
            }

            /**
             * Main backward search: takes the passed <code>BoardPosition</code>
             * as basis and generates all possible successor board positions.
             * These generated board positions are stored in the open queue and
             * later taken as new basis board positions to generate successors from.
             *
             * @param currentBoardPosition the board positions to generate successor board positions from
             * @param boxConfiguration     the box configuration of the passed current board position
             */
            private void generateSuccessorBoardPositions(final StorageBoardPosition currentBoardPosition, final BoxConfiguration boxConfiguration) {

                int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;

                // Moves and pushes depth - despite the backward search is pulling the boxes we use
                // the term pushes here to use just one term in forward and backward search.
                // Note: although we move the player and make pulls the depths start at the values
                // in the currentBoardPosition because this method saves the board positions
                // BEFORE the pulls are made. Hence, the successor board position has the same
                // box configuration but only another player position.
                int movesDepth = currentBoardPosition.secondaryMetric;
                int pushesDepth = currentBoardPosition.primaryMetric;

                // Add the start player position and set the current moves depth.
                playerPositions.addFirst(currentBoardPosition.playerPosition, movesDepth);

                while ((playerPosition = playerPositions.remove()) != NONE) {

                    // Flag, indicating whether there has been done a pull from a specific board position. When one pull is possible the board position is put into the
                    // open queue. Another pull on the other axis would just put the same board position into the queue for the second time.
                    boolean boardPositionHasBeenStored = false;

                    // Try to move the player to every direction and check whether it's possible to do pulls.
                    for (int dir = 0; dir < 4; dir++) {

                        // Immediately continue with the next direction if the player can't move to the new position due to a wall or a box.
                        if ((newPlayerPosition = playerSquareNeighbor[dir][playerPosition]) == NONE ||
                                (boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {
                            continue;
                        }

                        // The player can move to the new position => store the new position in the queue (if it hasn't been reached, yet).
                        playerPositions.addIfNew(newPlayerPosition);

                        // Don't add the board position a second time when a pull on the other axis is possible, too.
                        // Just continue with moving the player to every neighbor square.
                        if (boardPositionHasBeenStored) {
                            continue;
                        }

                        // Check whether there is a box which can be pulled by moving to the new player position. If not, continue with the next move.
                        if ((playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(dir)][playerPosition]) == NONE ||  // position next to the player where a box might be
                                (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                    // position of a possible box to be pulled
                                !boxConfiguration.isBoxAtPosition(boxPosition) ||                                                // there is a box next to the player
                                (newBoxPosition = playerPositionToBoxPosition[playerPosition]) == NONE) {                                // valid box position
                            continue;
                        }

                        // Pull is possible => do the pull.
                        boxConfiguration.moveBox(boxPosition, newBoxPosition);

                        // Check whether it's one of the allowed box configurations.
                        if (boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration) != NONE) {

                            // Since the board position BEFORE the pull is stored another pull on the other axis would
                            // just put the same board position into the queue for the second time. Hence, set a flag to avoid this.
                            boardPositionHasBeenStored = true;

                            // Add the board position BEFORE the pull has been made! This must be done to store the same board positions
                            // as the forward search, which stores the board positions AFTER a push has been made.
                            long addedBoardPositionIndex = boardPositionStorage.addIfBetter(pushesDepth, playerPositions.movesDepth, currentBoardPosition.boxConfigurationIndex, playerPosition, SearchDirection.BACKWARD);
                            if (addedBoardPositionIndex != BoardPositionsStorage.NONE) {

                                // Check whether a solution has been found due to meeting the forward search -> negative index is returned.
                                if (addedBoardPositionIndex < 0) {
                                    addedBoardPositionIndex = -addedBoardPositionIndex;
                                    collectBestSolutions(SearchDirection.BACKWARD, playerPositions.movesDepth, pushesDepth, currentBoardPosition.boxConfigurationIndex, playerPosition, newBoxPosition);
                                }

                                // Add the board position for further searching - even when the searches have met this search might just have
                                // reached a sub optimal backwards board position and therefore must continue the search with the current board position.
                                openQueueBackward.add(addedBoardPositionIndex);
                            }
                        }

                        // Undo the pull to reuse the box configuration for the next pull.
                        boxConfiguration.moveBox(newBoxPosition, boxPosition);

                    } // Loop that continues moving the player to all 4 directions until all positions have been visited
                } // Loop that moves the player to all reachable positions
            }

            /**
             * This method checks whether a better path to any of the board positions of the solution to be optimized
             * has been found. If yes, then the improvement is displayed.
             */
            private void checkForImprovement() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Identify the board position having the highest number of pulls that has already been reached by
                // the backward search and is part of the basis solution to be optimized and check for any improvements.
                // Ignore the first board position because there are levels that start with all boxes on goals.
                // For these levels start and end board position are the same.
                for (int i = 1; i < boardPositionsSolutionToBeOptimized.size(); i++) {

                    BoardPosition basisSolutionBoardPosition = boardPositionsSolutionToBeOptimized.get(i);

                    long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(basisSolutionBoardPosition, SearchDirection.BACKWARD);
                    if (boardPositionIndex != BoardPositionsStorage.NONE) {

                        boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);

                        // Since this is the backward search we first have to calculate the number of moves and pushes the "board position" is
                        // away from the end board position, since that is the number of pulls in the basis solution needed to reach "board position".
                        int pushesDif = solutionToBeOptimized.pushesCount - basisSolutionBoardPosition.pushes - boardPosition.primaryMetric;
                        int movesDif = solutionToBeOptimized.movesCount - basisSolutionBoardPosition.moves - boardPosition.secondaryMetric;

                        // Forward search reads these variables, too, hence synchronize.
                        synchronized (mainOptimizerThread) {

                            // The pushes ranges searches for improvements for forward and backwards search mustn't overlap!
                            if (lastVisitedBoardPositionOfOriginalSolutionForward != null && basisSolutionBoardPosition.pushes <= lastVisitedBoardPositionOfOriginalSolutionForward.pushes) {
                                continue;
                            }

                            if (pushesDif > pushesImprovementBackwardSearch || pushesDif == pushesImprovementBackwardSearch && movesDif > movesImprovementBackwardSearch) {
                                pushesImprovementBackwardSearch = pushesDif;
                                movesImprovementBackwardSearch = movesDif;

                                if (immediateNewIterationWhenImprovement && optimizerStatus == RUNNING) {
                                    optimizerStatus = OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION;
                                }
                            }

                            // Save the last visited board position of the original solution if it has been reached on the currently best known path.
                            if (pushesImprovementBackwardSearch == pushesDif && movesImprovementBackwardSearch == movesDif) {
                                lastVisitedBoardPositionOfOriginalSolutionBackward = basisSolutionBoardPosition;
                            }
                        }

                        break;
                    }
                }

                // Display the new pushes depth and the current best solution metrics.
                displayIntermediateResult();

            } // end of method "checkForImprovement"
        }

        /**
         * Calculates and displays the current pushes depth of the search and the current best solution metrics.
         */
        private void displayIntermediateResult() {

            synchronized (mainOptimizerThread) {

                // Calculate the new improvements and new best solution metrics synchronized with the forward search.
                int totalFoundMovesImprovement = movesImprovementForwardSearch + movesImprovementBackwardSearch;
                int totalFoundPushesImprovement = pushesImprovementForwardSearch + pushesImprovementBackwardSearch;

                int newMovesCount = solutionToBeOptimized.movesCount - totalFoundMovesImprovement;
                int newPushesCount = solutionToBeOptimized.pushesCount - totalFoundPushesImprovement;

                // Set the new metrics when a new best solution has been found.
                if (newPushesCount < newBestSolutionPushesCount || newPushesCount == newBestSolutionPushesCount && newMovesCount < newBestSolutionMovesCount) {
                    newBestSolutionMovesCount = newMovesCount;
                    newBestSolutionPushesCount = newPushesCount;
                } else {
                    // If the search directions have met, a new better solution may have been found. Then this method is called
                    // by method "collectBestSolutions" which hasn't updated the moves/pushes improvement values. Hence check
                    // whether the current best solution has better metrics than the currently known improvements.
                    // These new metrics are just calculated to display the correct improvement values.
                    if (newBestSolutionPushesCount < newPushesCount || newBestSolutionPushesCount == newPushesCount && newBestSolutionMovesCount < newMovesCount) {
                        totalFoundMovesImprovement = solutionToBeOptimized.movesCount - newBestSolutionMovesCount;
                        totalFoundPushesImprovement = solutionToBeOptimized.pushesCount - newBestSolutionPushesCount;
                    }
                }

                // Display a status message to show the current depth and the current found improvement.
                optimizerGUI.setInfoText(
                        Texts.getText("vicinitySearchPushesDepth")
                                + (prefixPushesCount + pushesDepthForwardSearch.get() + pushesDepthBackwardSearch.get())
                                + "    "
                                + Texts.getText("currentBestSolutionMovesPushes")
                                + (prefixMovesCount + newBestSolutionMovesCount + postfixMovesCount)
                                + "/"
                                + (prefixPushesCount + newBestSolutionPushesCount + postfixPushesCount)
                                + " (" + (-totalFoundMovesImprovement) + "/" + (-totalFoundPushesImprovement) + ")"
                );
            }
        }


        /**
         * Checks whether the search has found a new best solution. If yes, then the data of this new solution is saved.
         *
         * @param searchDirection       search direction of the search that has found the solution
         * @param movesDepth            number of moves of the search direction that has found the solution
         * @param pushesDepth           number of pushes of the search direction that has found the solution
         * @param boxConfigurationIndex index of the box configuration where both search directions have met each other
         * @param playerPosition        player position of the board position where both search directions have met each other
         * @param lastMovedBoxPosition  position of the last pushed/pulled box
         */
        private void collectBestSolutions(
                final SearchDirection searchDirection,
                final int movesDepth,
                final int pushesDepth,
                final int boxConfigurationIndex,
                final int playerPosition,
                final int lastMovedBoxPosition) {
            // Get the metrics of the other search direction.
            SearchDirection otherSearchDirection = searchDirection == SearchDirection.FORWARD ? SearchDirection.BACKWARD : SearchDirection.FORWARD;
            long boardPositionIndexOtherDirection = boardPositionStorage.getBoardPositionIndex(boxConfigurationIndex, playerPosition, otherSearchDirection);

            final StorageBoardPosition boardPositionOtherDirection = new StorageBoardPosition();
            boardPositionStorage.fillBoardPosition(boardPositionIndexOtherDirection, boardPositionOtherDirection);

            int newSolutionPrimaryMetric = pushesDepth + boardPositionOtherDirection.primaryMetric;
            int newSolutionSecondaryMetric = movesDepth + boardPositionOtherDirection.secondaryMetric;

            // Check if the new solution is at least as good as the current best one. Synchronized because the metrics of
            // the current best solution may change in the meantime.
            synchronized (mainOptimizerThread) {
                if (newSolutionPrimaryMetric < newBestSolutionPushesCount ||
                        newSolutionPrimaryMetric == newBestSolutionPushesCount && newSolutionSecondaryMetric <= newBestSolutionMovesCount) {

                    // Only solutions having the best metrics have to be reconstructed. If this solution is a new best one then the
                    // other solutions (represented by the meeting point of both search directions) can be deleted.
                    if (newSolutionPrimaryMetric < newBestSolutionPushesCount || newSolutionSecondaryMetric < newBestSolutionMovesCount) {
                        solutionReconstruction.removeAllMeetingPoints();
                    }

                    // Save the data where the search directions have met (-> solution has been found) for reconstructing them after the search is over.
                    solutionReconstruction.addMeetingPoint(boxConfigurationIndex, playerPosition, lastMovedBoxPosition);

                    newBestSolutionMovesCount = newSolutionSecondaryMetric;
                    newBestSolutionPushesCount = newSolutionPrimaryMetric;

                    if (Debug.isDebugModeActivated) {
                        optimizerGUI.addLogTextDebug("New best solution found: " + searchDirection + ": M/P: " + (newBestSolutionMovesCount) + "/" + (newBestSolutionPushesCount));
                    }

                    // Display the new best solution metrics.
                    displayIntermediateResult();
                }
            }
        }

        /**
         * The main optimizer search stores all board positions in the storage.
         * Only two metrics (pushes and moves for instance) are saved.
         * This class does a new search using over the stored board positions using all 5 metrics.
         */
        public class SolutionReconstructionPushesMoves extends SolutionReconstructionAllMetrics {

            /**
             * Creates a reconstruction object used to search the best solution by using only the
             * board positions generated by the optimizer vicinity search.<br>
             * The search considers all 5 metrics in the following order:
             * 1. pushes
             * 2. moves
             * 3. box lines
             * 4. box changes
             * 5. pushing sessions
             */
            public SolutionReconstructionPushesMoves() {
                super(BoardPosition.PUSHES_MOVES_COMPARATOR);
            }

            @Override
            protected int getMoves(StorageBoardPosition boardPosition) {
                return boardPosition.secondaryMetric;
            }

            @Override
            protected int getPushes(StorageBoardPosition boardPosition) {
                return boardPosition.primaryMetric;
            }

        }
    }


    /**
     * Class for searching for a better solution by only using the generated
     * box configurations.<br>
     * The new solution is optimized for: <br>
     * 1. moves<br>
     * 2. pushes<br>
     * 3. box lines<br>
     * 4. box changes<br>
     * 5. pushing sessions<br>
     * Before this method is called the box configurations to be used must
     * have been generated.
     */
    private class MovesPushesAllMetricsOptimizer extends AllMetricsOptimizer {

        /**
         * The board positions are stored in the priority queue ordered by their order values calculated by the {@link #boardPositionStorage}.
         * The order value is calculated: primaryMetric * (estimated maximum number of secondaryMetric) + secondaryMetric
         * Since we don't know the maximum number of pushes of the new solution the optimizer can only estimate that value.
         * This constant is used to estimate the maximum value by multiplying the pushes count of the solution to be optimized by this factor.
         */
        public static final int MAXIMAL_PUSHES_FACTOR = 3;

        // Moves depth that the search directions have reached.
        private final AtomicInteger movesDepthBackwardSearch = new AtomicInteger(-1);
        private final AtomicInteger movesDepthForwardSearch = new AtomicInteger(-1);

        // Collects the solutions found during the search and can reconstruct the player moves to the solution.
        protected SolutionReconstructionAllMetrics solutionReconstruction = new SolutionReconstructionMovesPushes();

        /**
         * Creates a new object for searching for a better solution regarding
         * first moves and second pushes.
         */
        public MovesPushesAllMetricsOptimizer() {
        }


        /**
         * Optimizes the passed solution by only using the generated box configurations.<br>
         * The path is optimal regarding: <br>
         * 1. moves<br>
         * 2. pushes<br>
         *
         * @param initialBoard        the board at level start
         * @param endBoard            the end board having all boxes on a goal
         * @param currentBestSolution the currently best solution
         * @return the best found solution
         */
        public OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentBestSolution) {

            // Save the passed solution so it can be accessed in all methods.
            this.solutionToBeOptimized = currentBestSolution;

            // Used to store the real positions of the boxes of a box configuration.
            // Usually just the index of a box configuration in the global array
            // is used but for doing pushes it's necessary to copy the real positions in this byte array.
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Yet, no solution has been found. However, the current best solution is used as upper bound for the pushes and moves.
            newBestSolutionMovesCount = currentBestSolution.movesCount;
            newBestSolutionPushesCount = currentBestSolution.pushesCount;

            // Get the index of the initial box configuration.
            packBoxConfiguration(boxConfiguration, initialBoard);
            startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            // Get a list of all board positions of the solution.
            boardPositionsSolutionToBeOptimized = getSolutionBoardPositions(currentBestSolution);

            // Calculate the highest move increase per push in the solution.
            int highestMoveChange = getHighestMoveChange(boardPositionsSolutionToBeOptimized);

            // Moves and pushes are stored in a "order value" for the priority queue. The order value is calculated:
            // moves * (estimated maximum number of pushes) + pushes
            // This optimizer just knows that the value will change by x moves (this value is multiplied by the estimated
            // maximum number of pushes) and 1 push.
            // The highest move change in the current best solution is "highestMoveChange".
            // We estimate that the highest move change in the new solution is at most 10 times higher than that.
            // If it is indeed higher than that this will slow down the optimizer.
            final int estimatedMaximumNewPushesCount = Math.min(MAXIMAL_PUSHES_FACTOR * currentBestSolution.pushesCount, currentBestSolution.movesCount);
            int maximumOrderValueChange = 10 * highestMoveChange * estimatedMaximumNewPushesCount + 1; // = primary metric change * maxSecondaryMetric  +  secondary metric change
            final int minimumOrderValueChange = 1 * estimatedMaximumNewPushesCount + 1; // = 1 move and 1 push

            maximumOrderValueChange = maximumOrderValueChange < 0 ? Integer.MAX_VALUE / 4 : Math.min(maximumOrderValueChange, Integer.MAX_VALUE / 4);  // avoid crash for levels like Letslogic id 100664

            // Create the storage.
            boardPositionStorage = new BoardPositionsStorage(boxConfigurationStorage.getSize(), playerSquaresCount, 2, estimatedMaximumNewPushesCount, currentBestSolution.movesCount);

            // Create the open queues for both search
            openQueueForward = PriorityQueueOptimizer.getInstance(minimumOrderValueChange, maximumOrderValueChange, boardPositionStorage, FORWARD_SEARCH_THREADS_COUNT);
            openQueueBackward = BACKWARD_SEARCH_THREADS_COUNT == 0 ?
                    PriorityQueueOptimizer.getInstance(2, 2, boardPositionStorage, 1) : // Dummy queue for storing the start positions
                    PriorityQueueOptimizer.getInstance(minimumOrderValueChange, maximumOrderValueChange, boardPositionStorage, BACKWARD_SEARCH_THREADS_COUNT);

            // Add the start board position for the forward search to the open queue
            // and store it in the reachedPositionsStorage for detecting duplicates.
            long indexStartBoardPosition = boardPositionStorage.addIfBetter(0, 0, startBoxConfigurationIndex, playerExternalToInternalPosition[initialBoard.playerPosition], SearchDirection.FORWARD);
            openQueueForward.add(indexStartBoardPosition);

            // Add start board positions for the backward search.
            addBackwardBoardPositions();


            // Execute the optimizer searches.
            final ExecutorService executor = Executors.newFixedThreadPool(FORWARD_SEARCH_THREADS_COUNT + BACKWARD_SEARCH_THREADS_COUNT);
            for (int threadNo = 0; threadNo < FORWARD_SEARCH_THREADS_COUNT; threadNo++) {
                executor.execute(new MovesPushesForwardSearch(threadNo));
            }
            for (int threadNo = 0; threadNo < BACKWARD_SEARCH_THREADS_COUNT; threadNo++) {
                executor.execute(new MovesPushesBackwardSearch(threadNo));
            }

            Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            if (Debug.isDebugModeActivated) {
                debugDisplayStatistic();
            }

            // Inform the user about the RAM that is available after the search has ended.
            optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMinMiB()));

            // The optimizer has only been stopped to start a new iteration with the new best found solution.
            // Hence, the optimizer is still running.
            if (optimizerStatus == OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION) {
                optimizerStatus = RUNNING;
            }

            // Return the best found solution (or null if no solution has been found, due
            // to stop of the optimizer by user, out of memory, ...)
            return getBestFoundSolution();
        }

        @Override
        public OptimizerSolution getBestFoundSolution() {
            synchronized (mainOptimizerThread) {
                return solutionReconstruction.getBestSolution();
            }
        }


        /**
         * A forward search considering first moves, second pushes.
         */
        private class MovesPushesForwardSearch implements Runnable {

            /**
             * Unique ID for every thread for accessing the priority queue.
             */
            private final int priorityQueueThreadID;

            /**
             * Creates a {@code Runnable} that performs a forward search for the optimizer.
             *
             * @param priorityQueueThreadID unique ID for the priority queue
             */
            public MovesPushesForwardSearch(int priorityQueueThreadID) {
                this.priorityQueueThreadID = priorityQueueThreadID;
            }

            /**
             * Starts a new forward search.
             */
            @Override
            public void run() {

                try {
                    // Start a new forward search.
                    forwardSearch();

                } catch (OutOfMemoryError e) {
                    // Stop the optimizer by setting the proper stop reason.
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }

            /**
             * Forward search searching from the start board position forward
             * (-> pushing boxes) until it meets the backward search.
             */
            private void forwardSearch() {

                // Object holding all information about one board position.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Used to calculate the player reachable positions.
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                // Used to store the real positions of the boxes of a box configuration. Usually just the index of a box configuration in
                // the global array is used but for doing pushes it's necessary to copy the real positions in this byte array.
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);


                while (optimizerStatus == RUNNING) {

                    // Take board position from the queue.
                    long currentBoardPositionIndex = openQueueForward.removeFirst(priorityQueueThreadID);

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPositionIndex == NONE) {
                        break;
                    }

                    // Mark as processed - BEFORE the real metrics are read. This ensures that when
                    // the board position is reached better in the meantime the new better
                    // values are read.
                    boardPositionStorage.markAsProcessed(currentBoardPositionIndex);

                    // Get the values from the board position.
                    boardPositionStorage.fillBoardPosition(currentBoardPositionIndex, boardPosition);

                    // At least one push and one move is done -> adjust the metrics.
                    int movesDepth = boardPosition.primaryMetric + 1;
                    int pushesDepth = boardPosition.secondaryMetric + 1;
                    int currentBoxConfigurationIndex = boardPosition.boxConfigurationIndex;

                    // If a new moves depth has been reached then:
                    // - discard the board position when the total depth (forward + backward search) is higher than the current best solution moves count
                    // - inform the user about this new depth and the currently best found metrics
                    int currentDepth = movesDepthForwardSearch.get();
                    if (movesDepth > currentDepth) {
                        // Note: this isn't completely thread safe:
                        // - a slow thread may still be adding board positions having less moves, hence no "break" here.
                        // - in a rare case both directions may get a "false" be checking the depth and then both increase
                        //   the total depth to a higher value than "newBestSolutionMovesCount". This is no problem.
                        if (movesDepth + movesDepthBackwardSearch.get() > newBestSolutionMovesCount) {
                            continue;
                        }
                        if (movesDepthForwardSearch.compareAndSet(currentDepth, movesDepth)) {
                            checkForImprovement();
                        }
                    }

                    playerPositions.addFirst(boardPosition.playerPosition, movesDepth);

                    // Old and new position of the player and a box.
                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    // Create a box configuration from the index of the box configuration.
                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                    // Breadth first search over all reachable player positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // Try to move the player to every direction and do pushes if necessary.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Is the possible new position of the box no deadlock square and not blocked by another box?
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        long addedBoardPositionIndex = boardPositionStorage.addIfBetter(playerPositions.movesDepth, pushesDepth, boxConfigurationIndex, newPlayerPosition, SearchDirection.FORWARD);
                                        if (addedBoardPositionIndex != BoardPositionsStorage.NONE) {

                                            // Check whether a solution has been found due to meeting the backward search -> negative index is returned.
                                            if (addedBoardPositionIndex < 0) {
                                                addedBoardPositionIndex = -addedBoardPositionIndex;
                                                collectBestSolutions(SearchDirection.FORWARD, playerPositions.movesDepth, pushesDepth, boxConfigurationIndex, newPlayerPosition, newBoxPosition);
                                            }

                                            // Add the board position for further searching - even when the searches have met this search might just have
                                            // reached a suboptimal backwards board position and therefore must continue the search with the current board position.
                                            openQueueForward.add(addedBoardPositionIndex);
                                        }
                                    }
                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }
            }

            /**
             * This method informs the user whether a better path to any of the board positions of the solution to be optimized
             * has been found and about the current moves depth that the search has reached.
             */
            private void checkForImprovement() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Identify the board position having the highest number of pushes that has already been reached by
                // the forward search and is part of the basis solution to be optimized and check for any improvements.
                // Ignore the last board position because there are levels which start with all boxes on goals.
                // For these levels start and end board position are the same.
                for (int index = boardPositionsSolutionToBeOptimized.size() - 1; --index != -1; ) {

                    // Get the next board position of the basis solution.
                    BoardPosition basisSolutionBoardPosition = boardPositionsSolutionToBeOptimized.get(index);

                    // Check whether the board position has already been reached by the forward search.
                    long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(basisSolutionBoardPosition, SearchDirection.FORWARD);
                    if (boardPositionIndex != BoardPositionsStorage.NONE) {

                        boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);

                        // Calculate how much better the board position has been reached compared to the basis solution.
                        int movesDif = basisSolutionBoardPosition.moves - boardPosition.primaryMetric;
                        int pushesDif = basisSolutionBoardPosition.pushes - boardPosition.secondaryMetric;

                        // Backward search reads these variables, too, hence synchronize on optimizerThread.
                        synchronized (mainOptimizerThread) {

                            // The pushes ranges for forward and backwards search mustn't overlap!
                            if (lastVisitedBoardPositionOfOriginalSolutionBackward != null && basisSolutionBoardPosition.pushes >= lastVisitedBoardPositionOfOriginalSolutionBackward.pushes) {
                                continue;
                            }

                            if (movesDif > movesImprovementForwardSearch || movesDif == movesImprovementForwardSearch && pushesDif > pushesImprovementForwardSearch) {
                                movesImprovementForwardSearch = movesDif;
                                pushesImprovementForwardSearch = pushesDif;

                                if (immediateNewIterationWhenImprovement && optimizerStatus == RUNNING) {
                                    optimizerStatus = OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION;
                                }
                            }

                            // Save the last visited board position of the original solution if it has been reached on the currently best known path.
                            if (pushesImprovementForwardSearch == pushesDif && movesImprovementForwardSearch == movesDif) {
                                lastVisitedBoardPositionOfOriginalSolutionForward = basisSolutionBoardPosition;
                            }
                        }

                        break;
                    }
                }

                // Display the new pushes depth and the current best solution metrics.
                displayIntermediateResult();

            } // end of method "checkForImprovement"
        }

        /**
         * A backward search considering first moves, second pushes.
         */
        private class MovesPushesBackwardSearch implements Runnable {

            /**
             * The reachable player positions are calculated in this object.
             */
            private final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

            /**
             * Unique ID for every thread for accessing the priority queue.
             */
            private final int priorityQueueThreadID;

            /**
             * Creates a {@code Runnable} that performs a backward search for the optimizer.
             *
             * @param priorityQueueThreadID unique ID for the priority queue
             */
            public MovesPushesBackwardSearch(int priorityQueueThreadID) {
                this.priorityQueueThreadID = priorityQueueThreadID;
            }

            /**
             * Starts a new backward search.
             */
            @Override
            public void run() {

                try {
                    // Start the backward search.
                    backwardSearch();

                } catch (OutOfMemoryError e) {
                    // Stop the optimizer by setting the proper stop reason.
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }


            /**
             * Backward search searching from the end board positions (all boxes
             * on a goal) backwards (-> pulling boxes) until it meets the forward search.
             */
            private void backwardSearch() {

                // Object holding all information about one board position (boxConfigurationIndex, metrics, ...)
                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                /*
                 *  Used to store the real positions of the boxes of a box configuration.
                 *  Usually just the index of a box configuration in the global array is
                 *  used but for doing pushes it's necessary to work with the actual box positions.
                 */
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);


                while (optimizerStatus == RUNNING) {

                    // Take the board position from the queue having the lowest metrics (pushes/moves).
                    long currentBoardPositionIndex = openQueueBackward.removeFirst(priorityQueueThreadID);

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPositionIndex == NONE) {
                        break;
                    }

                    // Mark as processed - BEFORE the real metrics are read. This ensures that when
                    // the board position is reached better in the meantime the new better values are read.
                    boardPositionStorage.markAsProcessed(currentBoardPositionIndex);

                    // Get the values from the board position.
                    boardPositionStorage.fillBoardPosition(currentBoardPositionIndex, boardPosition);

                    // At least one "push" and one move is done -> adjust the metrics.
                    boardPosition.primaryMetric++;
                    boardPosition.secondaryMetric++;

                    // If a new pushes depth has been reached then:
                    // - discard the board position when the total depth (forward + backward search) is higher than the current best solution pushes count
                    // - inform the user about this new depth and the currently best found metrics
                    int currentDepth = movesDepthBackwardSearch.get();
                    if (boardPosition.primaryMetric > currentDepth) {
                        // Note: this isn't completely thread safe:
                        // - a slow thread may still be adding board positions having less moves, hence no "break" here.
                        // - in a rare case both directions may get a "false" be checking the depth and then both increase
                        //   the total depth to a higher value than "newBestSolutionMovesCount". This is no problem.
                        if (boardPosition.primaryMetric + movesDepthForwardSearch.get() > newBestSolutionMovesCount) {
                            continue;
                        }
                        if (movesDepthBackwardSearch.compareAndSet(currentDepth, boardPosition.primaryMetric)) {
                            checkForImprovement();
                        }
                    }

                    // The box configuration is just an index in the data where the box positions are stored. This data
                    // is now copied to an local array "boxConfiguration".
                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, boardPosition.boxConfigurationIndex);

                    /*
                     * The forward search stores the board positions AFTER a push has been done. In order to be able to
                     * compare backward board positions with those stored by the forward search the backward search
                     * stores the board positions BEFORE a pull has been made.
                     * Hence, the actual pull has to be done now, after the board position has been taken out of the
                     * open queue. The search then continues taking the board position after the pull as basis for further pulls.
                     * Note: a pull can be done horizontally or vertically. Hence, two possible pulls might be possible from the board position taken out of the queue!
                     */

                    int newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;
                    int playerPosition = boardPosition.playerPosition;

                    // Calculate the new box position (must be a valid one because otherwise the board position hadn't been saved in the open queue).
                    newBoxPosition = playerPositionToBoxPosition[playerPosition];

                    // Do all possible pulls (max 2 pulls are possible - one per axis).
                    for (int direction = 0; direction < 4; direction++) {

                        // If any neighbor on this axis is a wall then no pull on this axis is possible => continue with the other axis.
                        if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                (playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]) == NONE) {
                            direction |= 1; // 0=UP, 1=DOWN, 2=LEFT, 3=RIGHT
                            continue;
                        }

                        // Immediately continue with the next direction if the pull to the direction isn't possible.
                        if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition) ||   // player can't reach new position due to a box
                                (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                                    // position of the box to be pulled is an invalid (deadlock square)
                                !boxConfiguration.isBoxAtPosition(boxPosition)) {                                                                // there is no box to be pulled
                            continue;
                        }

                        // A pull to the current direction is possible. This means a pull to the opposite direction is impossible.
                        direction |= 1; // 0=UP, 1=DOWN, 2=LEFT, 3=RIGHT

                        // Do the pull.
                        boxConfiguration.moveBox(boxPosition, newBoxPosition);

                        // Adjust the board position data because a pull has been made.
                        boardPosition.boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);    // new box configuration index
                        //						boardPosition.playerPosition 		= newPlayerPosition;													// is set in next "if"-Condition
                        //						boardPosition.primaryMetric++;																			    // already set above the for-loop
                        //						boardPosition.secondaryMetric++;																			// already set above the for-loop
                        //						boardPosition.previousBoardPositionIndex = currentBoardPositionIndex;										// already set above the for-loop

                        // When the board position has been added to the open queue only one possible pull has been checked for resulting in a box configuration
                        // stored in boxConfigurationStorage. Since we here do pulls on both axis the boxConfigurationIndex must be checked for being "NONE"!
                        if (boardPosition.boxConfigurationIndex != NONE) {

                            // Set the player position after the pull.
                            boardPosition.playerPosition = newPlayerPosition;

                            // The board position AFTER the pull has been "recreated". Use this board position for generating successor board positions.
                            generateSuccessorBoardPositions(boardPosition, boxConfiguration);
                        }

                        // Undo the pull.
                        boxConfiguration.moveBox(newBoxPosition, boxPosition);

                    } // Loop that recreates the "real board positions" taken out of the queue (-> board positions AFTER the pull)
                } // End of while taking new board positions from the open queue
            }

            /**
             * Main backward search: takes the passed <code>BoardPosition</code>
             * as basis and generates all possible successor board positions.
             * These generated board positions are stored in the open queue and
             * later taken as new basis board positions to generate successors from.
             *
             * @param currentBoardPosition the board positions to generate successor board positions from
             * @param boxConfiguration     the box configuration of the passed current board position
             */
            private void generateSuccessorBoardPositions(final StorageBoardPosition currentBoardPosition, final BoxConfiguration boxConfiguration) {

                int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;

                // Moves and pushes depth - despite the backward search is pulling the boxes we use
                // the term pushes here to use just one term in forward and backward search.
                // Note: although we move the player and make pulls the depths start at the values
                // in the currentBoardPosition because this method saves the board positions
                // BEFORE the pulls are made. Hence, the successor board position has the same
                // box configuration but only another player position.
                int movesDepth = currentBoardPosition.primaryMetric;
                int pushesDepth = currentBoardPosition.secondaryMetric;

                // Add the start player position and set the current moves depth.
                playerPositions.addFirst(currentBoardPosition.playerPosition, movesDepth);

                while ((playerPosition = playerPositions.remove()) != NONE) {

                    // Flag, indicating whether there has been done a pull from a specific board position. When one pull is possible the board position is put into the
                    // open queue. Another pull on the other axis would just put the same board position into the queue for the second time.
                    boolean boardPositionHasBeenStored = false;

                    // Try to move the player to every direction and check whether it's possible to do pulls.
                    for (int dir = 0; dir < 4; dir++) {

                        // Immediately continue with the next direction if the player can't move to the new position due to a wall or a box.
                        if ((newPlayerPosition = playerSquareNeighbor[dir][playerPosition]) == NONE ||
                                (boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {
                            continue;
                        }

                        // The player can move to the new position => store the new position in the queue (if it hasn't been reached, yet).
                        playerPositions.addIfNew(newPlayerPosition);

                        // Don't add the board position a second time when a pull on the other axis is possible, too.
                        // Just continue with moving the player to every neighbor square.
                        if (boardPositionHasBeenStored) {
                            continue;
                        }

                        // Check whether there is a box which can be pulled by moving to the new player position. If not, continue with the next move.
                        if ((playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(dir)][playerPosition]) == NONE ||  // position next to the player where a box might be
                                (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                    // position of a possible box to be pulled
                                !boxConfiguration.isBoxAtPosition(boxPosition) ||                                                // there is a box next to the player
                                (newBoxPosition = playerPositionToBoxPosition[playerPosition]) == NONE) {                                // valid box position
                            continue;
                        }

                        // Pull is possible => do the pull.
                        boxConfiguration.moveBox(boxPosition, newBoxPosition);

                        // Check whether it's one of the allowed box configurations.
                        if (boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration) != NONE) {

                            // Since the board position BEFORE the pull is stored another pull on the other axis would
                            // just put the same board position into the queue for the second time. Hence, set a flag to avoid this.
                            boardPositionHasBeenStored = true;

                            // Add the board position BEFORE the pull has been made! This must be done to store the same board positions
                            // as the forward search, which stores the board positions AFTER a push has been made.
                            long addedBoardPositionIndex = boardPositionStorage.addIfBetter(playerPositions.movesDepth, pushesDepth, currentBoardPosition.boxConfigurationIndex, playerPosition, SearchDirection.BACKWARD);
                            if (addedBoardPositionIndex != BoardPositionsStorage.NONE) {

                                // Check whether a solution has been found due to meeting the forward search -> negative index is returned.
                                if (addedBoardPositionIndex < 0) {
                                    addedBoardPositionIndex = -addedBoardPositionIndex;
                                    collectBestSolutions(SearchDirection.BACKWARD, playerPositions.movesDepth, pushesDepth, currentBoardPosition.boxConfigurationIndex, playerPosition, newBoxPosition);
                                }

                                // Add the board position for further searching - even when the searches have met this search might just have
                                // reached a sub optimal backwards board position and therefore must continue the search with the current board position.
                                openQueueBackward.add(addedBoardPositionIndex);
                            }
                        }

                        // Undo the pull to reuse the box configuration for the next pull.
                        boxConfiguration.moveBox(newBoxPosition, boxPosition);

                    } // Loop that continues moving the player to all 4 directions until all positions have been visited
                } // Loop that moves the player to all reachable positions
            }

            /**
             * This method checks whether a better path to any of the board positions of the solution to be optimized
             * has been found. If yes, then the improvement is displayed.
             */
            private void checkForImprovement() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Identify the board position having the highest number of pulls that has already been reached by
                // the backward search and is part of the basis solution to be optimized and check for any improvements.
                // Ignore the first board position because there are levels which start with all boxes on goals.
                // For these levels start and end board position are the same.
                for (int i = 1; i < boardPositionsSolutionToBeOptimized.size(); i++) {

                    BoardPosition basisSolutionBoardPosition = boardPositionsSolutionToBeOptimized.get(i);

                    long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(basisSolutionBoardPosition, SearchDirection.BACKWARD);
                    if (boardPositionIndex != BoardPositionsStorage.NONE) {

                        boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);

                        // Since this is the backward search we first have to calculate the number of moves and pushes the "board position" is
                        // away from the end board position, since that is the number of pulls in the basis solution needed to reach "board position".
                        int movesDif = solutionToBeOptimized.movesCount - basisSolutionBoardPosition.moves - boardPosition.primaryMetric;
                        int pushesDif = solutionToBeOptimized.pushesCount - basisSolutionBoardPosition.pushes - boardPosition.secondaryMetric;

                        // Forward search reads these variables, too, hence synchronize.
                        synchronized (mainOptimizerThread) {

                            // The pushes ranges searches for improvements for forward and backwards search mustn't overlap!
                            if (lastVisitedBoardPositionOfOriginalSolutionForward != null && basisSolutionBoardPosition.pushes <= lastVisitedBoardPositionOfOriginalSolutionForward.pushes) {
                                continue;
                            }

                            if (movesDif > movesImprovementBackwardSearch || movesDif == movesImprovementBackwardSearch && pushesDif > pushesImprovementBackwardSearch) {
                                pushesImprovementBackwardSearch = pushesDif;
                                movesImprovementBackwardSearch = movesDif;

                                if (immediateNewIterationWhenImprovement && optimizerStatus == RUNNING) {
                                    optimizerStatus = OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION;
                                }
                            }

                            // Save the last visited board position of the original solution if it has been reached on the currently best known path.
                            if (pushesImprovementBackwardSearch == pushesDif && movesImprovementBackwardSearch == movesDif) {
                                lastVisitedBoardPositionOfOriginalSolutionBackward = basisSolutionBoardPosition;
                            }
                        }

                        break;
                    }
                }

                // Display the new pushes depth and the current best solution metrics.
                displayIntermediateResult();

            } // end of method "checkForImprovement"
        }

        /**
         * Calculates and displays the current pushes depth of the search and the current best solution metrics.
         */
        private void displayIntermediateResult() {

            synchronized (mainOptimizerThread) {

                // Calculate the new improvements and new best solution metrics synchronized with the forward search.
                int totalFoundMovesImprovement = movesImprovementForwardSearch + movesImprovementBackwardSearch;
                int totalFoundPushesImprovement = pushesImprovementForwardSearch + pushesImprovementBackwardSearch;

                int newMovesCount = solutionToBeOptimized.movesCount - totalFoundMovesImprovement;
                int newPushesCount = solutionToBeOptimized.pushesCount - totalFoundPushesImprovement;

                // Set the new metrics when a new best solution has been found.
                if (newMovesCount < newBestSolutionMovesCount || newMovesCount == newBestSolutionMovesCount && newPushesCount < newBestSolutionPushesCount) {
                    newBestSolutionMovesCount = newMovesCount;
                    newBestSolutionPushesCount = newPushesCount;
                } else {
                    // If the search directions have met, a new better solution may have been found. Then this method is called
                    // by method "collectBestSolutions" which hasn't updated the moves/pushes improvement values. Hence check
                    // whether the current best solution has better metrics than the currently known improvements.
                    // These new metrics are just calculated to display the correct improvement values.
                    if (newBestSolutionMovesCount < newMovesCount || newBestSolutionMovesCount == newMovesCount && newBestSolutionPushesCount < newPushesCount) {
                        totalFoundMovesImprovement = solutionToBeOptimized.movesCount - newBestSolutionMovesCount;
                        totalFoundPushesImprovement = solutionToBeOptimized.pushesCount - newBestSolutionPushesCount;
                    }
                }

                int movesDepth = prefixMovesCount + movesDepthForwardSearch.get() + movesDepthBackwardSearch.get();
                int bestSolutionMovesCount = prefixMovesCount + newBestSolutionMovesCount + postfixMovesCount;
                int bestSolutionPushesCount = prefixPushesCount + newBestSolutionPushesCount + postfixPushesCount;

                // Display a status message to show the current depth and the current found improvement.
                optimizerGUI.setInfoText(
                        Texts.getText("vicinitySearchMovesDepth") + movesDepth + "    "
                                + Texts.getText("currentBestSolutionMovesPushes") + bestSolutionMovesCount + "/" + bestSolutionPushesCount
                                + " (" + (-totalFoundMovesImprovement) + "/" + (-totalFoundPushesImprovement) + ")"
                );
            }
        }


        /**
         * Checks whether the search has found a new best solution. If yes, then the data of this new solution is saved.
         *
         * @param searchDirection       search direction of the search that has found the solution
         * @param movesDepth            number of moves of the search direction that has found the solution
         * @param pushesDepth           number of pushes of the search direction that has found the solution
         * @param boxConfigurationIndex index of the box configuration where both search directions have met each other
         * @param playerPosition        player position of the board position where both search directions have met each other
         * @param lastMovedBoxPosition  position of the last pushed/pulled box
         */
        private void collectBestSolutions(
                final SearchDirection searchDirection,
                final int movesDepth,
                final int pushesDepth,
                final int boxConfigurationIndex,
                final int playerPosition,
                final int lastMovedBoxPosition) {
            // Get the metrics of the other search direction.
            SearchDirection otherSearchDirection = searchDirection == SearchDirection.FORWARD ? SearchDirection.BACKWARD : SearchDirection.FORWARD;
            long boardPositionIndexOtherDirection = boardPositionStorage.getBoardPositionIndex(boxConfigurationIndex, playerPosition, otherSearchDirection);

            final StorageBoardPosition boardPositionOtherDirection = new StorageBoardPosition();
            boardPositionStorage.fillBoardPosition(boardPositionIndexOtherDirection, boardPositionOtherDirection);

            int newSolutionPrimaryMetric = movesDepth + boardPositionOtherDirection.primaryMetric;
            int newSolutionSecondaryMetric = pushesDepth + boardPositionOtherDirection.secondaryMetric;

            // Check if the new solution is at least as good as the current best one. Synchronized because the metrics of
            // the current best solution may change in the meantime.
            synchronized (mainOptimizerThread) {
                if (newSolutionPrimaryMetric < newBestSolutionMovesCount ||
                        newSolutionPrimaryMetric == newBestSolutionMovesCount && newSolutionSecondaryMetric <= newBestSolutionPushesCount) {

                    // Only solutions having the best metrics have to be reconstructed. If this solution is a new best one then the
                    // other solutions (represented by the meeting point of both search directions) can be deleted.
                    if (newSolutionPrimaryMetric < newBestSolutionMovesCount || newSolutionSecondaryMetric < newBestSolutionPushesCount) {
                        solutionReconstruction.removeAllMeetingPoints();
                    }

                    // Save the data where the search directions have met (-> solution has been found) for reconstructing them after the search is over.
                    solutionReconstruction.addMeetingPoint(boxConfigurationIndex, playerPosition, lastMovedBoxPosition);

                    newBestSolutionMovesCount = newSolutionPrimaryMetric;
                    newBestSolutionPushesCount = newSolutionSecondaryMetric;

                    if (Debug.isDebugModeActivated) {
                        optimizerGUI.addLogTextDebug("New best solution found: " + searchDirection + ": M/P: " + (newBestSolutionMovesCount) + "/" + (newBestSolutionPushesCount));
                    }

                    // Display the new best solution metrics.
                    displayIntermediateResult();
                }
            }
        }


        /**
         * The main optimizer search stores all board positions in the storage.
         * Only two metrics (pushes and moves for instance) are saved.
         * This class does a new search using over the stored board positions using all 5 metrics.
         */
        protected class SolutionReconstructionMovesPushes extends SolutionReconstructionAllMetrics {

            /**
             * Creates a reconstruction object used to search the best solution by using only the
             * board positions generated by the optimizer vicinity search.<br>
             * The search considers all 5 metrics in the following order:
             * 1. moves
             * 2. pushes
             * 3. box lines
             * 4. box changes
             * 5. pushing sessions
             */
            public SolutionReconstructionMovesPushes() {
                super(BoardPosition.MOVES_PUSHES_COMPARATOR);
            }

            @Override
            protected int getMoves(StorageBoardPosition boardPosition) {
                return boardPosition.primaryMetric;
            }

            @Override
            protected int getPushes(StorageBoardPosition boardPosition) {
                return boardPosition.secondaryMetric;
            }

        }
    }

    /**
     * Class for searching for a solution by only using the generated box configurations.<br>
     * The new solution is optimized for: <br>
     * 1. box lines<br>
     * 2. pushes<br>
     * Before this method is called the box configurations to be used must have been generated.
     */
    private class BoxLinesPushesOptimizer extends AllMetricsOptimizer {

        private final AtomicInteger boxLinesDepthSearch = new AtomicInteger(-1);

        protected volatile int newBestSolutionBoxLinesCount = -1;
        protected int boxLinesImprovement = 0;
        private final SolutionReconstructionBoxLinesPushes solutionReconstruction = new SolutionReconstructionBoxLinesPushes();

        LevelSolvedCheck levelSolvedCheck;

        /**
         * Optimizes the passed solution by only using the generated box configurations.<br>
         * The path is optimal regarding: <br>
         * 1. box lines<br>
         * 2. pushes<br>
         *
         * @param initialBoard        the board at level start
         * @param endBoard            the end board having all boxes on a goal
         * @param currentBestSolution the currently best solution
         * @return the best found solution
         */
        public OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentBestSolution) {
            // Used to store the real positions of the boxes of a box configuration.
            // Usually just the index of a box configuration in the global array
            // is used but for doing pushes it's necessary to copy the real positions in this byte array.
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Get the index of the initial box configuration.
            packBoxConfiguration(boxConfiguration, initialBoard);
            startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);


            levelSolvedCheck = new LevelSolvedCheck(endBoard);


            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            // Get a list of all board positions of the solution.
            boardPositionsSolutionToBeOptimized = getSolutionBoardPositions(currentBestSolution);

            // Yet, no solution has been found. However, the current best solution is used as upper bound for the box lines and pushes.
            BoardPosition endBoardPosition = boardPositionsSolutionToBeOptimized.get(boardPositionsSolutionToBeOptimized.size() - 1);
            newBestSolutionBoxLinesCount = endBoardPosition.boxLines;
            newBestSolutionPushesCount = endBoardPosition.pushes;

            currentBestSolution.movesCount = endBoardPosition.moves;
            currentBestSolution.pushesCount = endBoardPosition.pushes;
            currentBestSolution.boxChanges = endBoardPosition.boxChanges;
            currentBestSolution.boxLines = endBoardPosition.boxLines;
            currentBestSolution.pushingSessions = endBoardPosition.pushingSessions;

            this.solutionToBeOptimized = currentBestSolution;

            // The order value is calculated:
            // boxLinesCount * (estimated maximum number of pushes+1) + pushesCount
            // This optimizer just knows that the value will change by 1 box line (which is multiplied by the estimated
            // maximum number of pushes) and x pushes.
            // x is estimated not to be higher than 2 times the highest number of pushes in one box line of the solution to be optimized.
            // We estimate the new solution not having more than 4 times the number of pushes than the solution to be optimized.
            final int estimatedMaximumNewPushesCount = 4 * currentBestSolution.pushesCount;
            final int maximumOrderValueChange = 1 * (estimatedMaximumNewPushesCount + 1) + 1; // 1 box line and 1 push
            final int minimumOrderValueChange = 1;                    // = 1 push

            boardPositionStorage = new BoardPositionsStorage(boxConfigurationStorage.getSize(), playerSquaresCount, 2, estimatedMaximumNewPushesCount, estimatedMaximumNewPushesCount);

            openQueueForward = PriorityQueueOptimizer.getInstance(minimumOrderValueChange, maximumOrderValueChange, boardPositionStorage, maxCPUsToBeUsedCount);


            // Add the start board position for the forward search to the open queue
            // and store it in the reachedPositionsStorage for detecting duplicates.
            int interalPlayerStartPosition = playerExternalToInternalPosition[initialBoard.playerPosition];

            // If we don't optimize a pushes range there is no prefix push. In this case we can take any axis as start,
            // because the first push is counted as new box change/box line anyway.
            int firstPushAxis = prefixAxisOfLastPush == NONE ? AXIS_HORIZONTAL : prefixAxisOfLastPush;

            long indexStartBoardPosition = boardPositionStorage.addIfBetter(0, 0, startBoxConfigurationIndex, (interalPlayerStartPosition << 1) | firstPushAxis, SearchDirection.FORWARD);
            openQueueForward.add(indexStartBoardPosition);

            // Execute the optimizer searches.
            final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
            for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                executor.execute(new BoxLinesPushesForwardSearch(threadNo));
            }

            Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            if (Debug.isDebugModeActivated) {
                debugDisplayStatistic();
            }

            // Inform the user about the RAM that is available after the search has ended.
            optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMinMiB()));

            // The optimizer has only been stopped to start a new iteration with the new best found solution.
            // Hence, the optimizer is still running.
            if (optimizerStatus == OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION) {
                optimizerStatus = RUNNING;
            }

            // Return the best found solution (or null if no solution has been found, due
            // to stop of the optimizer by user, out of memory, ...)
            return getBestFoundSolution();
        }

        @Override
        public OptimizerSolution getBestFoundSolution() {
            synchronized (mainOptimizerThread) {
                return solutionReconstruction.getBestSolution();
            }
        }

        /**
         * A forward search considering first box lines, second pushes.
         */
        private class BoxLinesPushesForwardSearch implements Runnable {

            /** Unique ID for every thread for accessing the priority queue. */
            private final int priorityQueueThreadID;

            /**
             * Creates a {@code Runnable} that performs a forward search for the optimizer.
             *
             * @param priorityQueueThreadID unique ID for the priority queue
             */
            public BoxLinesPushesForwardSearch(int priorityQueueThreadID) {
                this.priorityQueueThreadID = priorityQueueThreadID;
            }

            /**
             * Starts a new forward search.
             */
            @Override
            public void run() {

                try {
                    forwardSearch();
                } catch (OutOfMemoryError e) {
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }

            /**
             * Forward search searching from the start board position forward
             * (-> pushing boxes) until it meets the backward search.
             */
            private void forwardSearch() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    long currentBoardPositionIndex = openQueueForward.removeFirst(priorityQueueThreadID);
                    if (currentBoardPositionIndex == NONE) {
                        break;
                    }

                    // Mark as processed - BEFORE the real metrics are read. This ensures that when
                    // the board position is reached better in the meantime the new better values are read.
                    boardPositionStorage.markAsProcessed(currentBoardPositionIndex);

                    boardPositionStorage.fillBoardPosition(currentBoardPositionIndex, boardPosition);

                    int axis = boardPosition.playerPosition & 1; // Axis is stored in first bit
                    boardPosition.playerPosition >>>= 1;         // Calculate the real player position (axis is stored in first bit)

                    int boxLinesDepth = boardPosition.primaryMetric;
                    int pushesDepth = boardPosition.secondaryMetric + 1; // one push is done in every case
                    int currentBoxConfigurationIndex = boardPosition.boxConfigurationIndex;

                    int currentDepth = boxLinesDepthSearch.get();
                    if (boxLinesDepth > currentDepth) {
                        if (boxLinesDepth > newBestSolutionBoxLinesCount) {
                            continue;
                        }
                        if (boxLinesDepthSearch.compareAndSet(currentDepth, boxLinesDepth)) {
                            checkForImprovement();
                        }
                    }

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                    playerPositions.addFirst(boardPosition.playerPosition, 0);

                    // Breadth-First-Search over all reachable player positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Is the neighbor position of the box no deadlock square and not blocked by another box?
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        // First push is always a new boxLine unless we optimize a pushesRange, in which case there is a previous push
                                        // whose axis has been passed to the optimizer.
                                        int newBoxLines = boardPosition.primaryMetric;
                                        if ((pushesDepth == 1 && prefixAxisOfLastPush == NONE) || playerPositions.movesDepth != 0 || getAxisOfDirection(direction) != axis) {
                                            newBoxLines++;
                                        }

                                        int newPlayerPositionWithAxis = (newPlayerPosition << 1) | getAxisOfDirection(direction); // player position is shifted to store the axis, too

                                        long addedBoardPositionIndex = boardPositionStorage.addIfBetter(newBoxLines, pushesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, SearchDirection.FORWARD);
                                        if (addedBoardPositionIndex != BoardPositionsStorage.NONE) {
                                            if (levelSolvedCheck.isTargetBoardPosition(boxConfigurationIndex, newPlayerPosition)) {
                                                collectBestSolutions(newBoxLines, pushesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, direction);
                                            } else {
                                                openQueueForward.add(addedBoardPositionIndex);
                                            }
                                        }
                                    }
                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }
            }

            /**
             * This method informs the user whether a better path to any of the board positions of the solution to be optimized
             * has been found and about the current box lines depth that the search has reached.
             */
            private void checkForImprovement() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Identify the board position having the highest number of pushes that has already been reached by
                // the forward search and is part of the basis solution to be optimized and check for any improvements.
                // Ignore the last board position because there are levels which start with all boxes on goals.
                // For these levels start and end board position are the same.
                for (int index = boardPositionsSolutionToBeOptimized.size() - 1; --index != -1; ) {

                    BoardPosition basisSolutionBoardPosition = boardPositionsSolutionToBeOptimized.get(index);

                    // Check whether the board position has already been reached by the forward search.
                    long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(basisSolutionBoardPosition, SearchDirection.FORWARD);
                    if (boardPositionIndex != BoardPositionsStorage.NONE) {

                        boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);

                        // Calculate how much better the board position has been reached compared to the basis solution.
                        int boxLinesDif = basisSolutionBoardPosition.boxLines - boardPosition.primaryMetric;
                        int pushesDif = basisSolutionBoardPosition.pushes - boardPosition.secondaryMetric;

                        synchronized (mainOptimizerThread) {
                            if (boxLinesDif > boxLinesImprovement ||
                                    boxLinesDif == boxLinesImprovement && pushesDif > pushesImprovementForwardSearch) {
                                boxLinesImprovement = boxLinesDif;
                                pushesImprovementForwardSearch = pushesDif;

                                if (immediateNewIterationWhenImprovement && optimizerStatus == RUNNING) {
                                    optimizerStatus = OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION;
                                }
                            }

                            // Save the last visited board position of the original solution if it has been reached on the currently best known path.
                            if (boxLinesImprovement == boxLinesDif && pushesImprovementForwardSearch == pushesDif) {
                                lastVisitedBoardPositionOfOriginalSolutionForward = basisSolutionBoardPosition;
                            }
                        }

                        break;
                    }
                }

                displayIntermediateResult();
            }
        }


        /**
         * Returns a list of all board positions the passed solution
         * consists of. The board positions containing information
         * about with which metrics they have in the solution (pushes, moves,...)
         *
         * @param currentBestSolution solution whose board positions are to be returned
         * @return the board positions of the passed solution consists of
         */
        @Override
        protected ArrayList<BoardPosition> getSolutionBoardPositions(final OptimizerSolution currentBestSolution) {

            return getBoardPositions(initialBoard, currentBestSolution.solution, prefixAxisOfLastPush)
                    .map(bp -> {
                        int playerPositionWithAxis = (bp.playerPosition << 1) | getAxisOfDirection(bp.pushDirection);
                        return new BoardPosition(bp.moves, bp.pushes, bp.boxLines, bp.boxChanges, bp.pushingSessions, bp.boxConfigurationIndex, playerPositionWithAxis);
                    }).collect(toCollection(ArrayList::new));
        }

        /**
         * Calculates and displays the current box lines depth of the search and the current best solution metrics.
         */
        private void displayIntermediateResult() {

            synchronized (mainOptimizerThread) {

                int totalFoundBoxLinesImprovement = boxLinesImprovement;
                int totalFoundPushesImprovement = pushesImprovementForwardSearch;

                int newBoxLinesCount = solutionToBeOptimized.boxLines - totalFoundBoxLinesImprovement;
                int newPushesCount = solutionToBeOptimized.pushesCount - totalFoundPushesImprovement;

                // Set the new metrics when a new best solution has been found.
                if (newBoxLinesCount < newBestSolutionBoxLinesCount || newBoxLinesCount == newBestSolutionBoxLinesCount && newPushesCount < newBestSolutionPushesCount) {
                    newBestSolutionBoxLinesCount = newBoxLinesCount;
                    newBestSolutionPushesCount = newPushesCount;
                }

                // Display a status message to show the current depth and the current found improvement.
                // If a only a specific pushes range of the whole solution is optimized,
                // only the metrics of this pushes range are displayed (without the prefix and suffix moves
                // that must be added to get the whole solution).
                optimizerGUI.setInfoText(
                        Texts.getText("vicinitySearchBoxLinesDepth")
                                + boxLinesDepthSearch.get()
                                + "    "
                                + Texts.getText("bestSolution") + ": "
                                + newBestSolutionBoxLinesCount
                                + "/"
                                + newBestSolutionPushesCount
                                + " (" + (-totalFoundBoxLinesImprovement) + "/" + (-totalFoundPushesImprovement) + ")"
                );
            }
        }


        /**
         * Checks whether the search has found a new best solution. If yes, then the data of this new solution is saved.
         *
         * @param boxLinesDepth         number of box lines of the search direction that has found the solution
         * @param pushesDepth           number of pushes of the search direction that has found the solution
         * @param boxConfigurationIndex index of the box configuration
         * @param playerPosition        player position of the board position
         * @param lastMoveDirection     direction of the last move
         */
        private void collectBestSolutions(
                final int boxLinesDepth,
                final int pushesDepth,
                final int boxConfigurationIndex,
                final int playerPosition,
                final int lastMoveDirection) {

            // Check if the new solution is at least as good as the current best one. Synchronized because the metrics of
            // the current best solution may otherwise change in the meantime.
            synchronized (mainOptimizerThread) {
                if (boxLinesDepth < newBestSolutionBoxLinesCount ||
                        boxLinesDepth == newBestSolutionBoxLinesCount && pushesDepth <= newBestSolutionPushesCount) {

                    // Only solutions having the best metrics have to be reconstructed. If this solution is a new best one then the
                    // other solutions (represented by the meeting point) can be deleted.
                    if (boxLinesDepth < newBestSolutionBoxLinesCount || pushesDepth < newBestSolutionPushesCount) {
                        solutionReconstruction.removeAllEndBoardPositions();

                        newBestSolutionBoxLinesCount = boxLinesDepth;
                        newBestSolutionPushesCount = pushesDepth;

                        boxLinesImprovement = solutionToBeOptimized.boxLines - newBestSolutionBoxLinesCount;
                        pushesImprovementForwardSearch = solutionToBeOptimized.pushesCount - newBestSolutionPushesCount;

                        displayIntermediateResult();
                    }

                    solutionReconstruction.addEndBoardPosition(boxConfigurationIndex, playerPosition, lastMoveDirection);

                    if (Debug.isDebugModeActivated) {
                        optimizerGUI.addLogTextDebug("New best solution found: M/P: " + (newBestSolutionBoxLinesCount) + "/" + (newBestSolutionPushesCount));
                    }
                }
            }
        }


        /**
         * The main optimizer search stores all board positions in the storage.
         * This class does a new search over the stored board positions in order to reconstruct the best solution
         * regarding first box lines and second pushes.
         */
        protected class SolutionReconstructionBoxLinesPushes {

            private final Set<EndBoardPositionData> endBoardPositionSet = ConcurrentHashMap.newKeySet();

            private final AtomicReference<OptimizerSolution> bestFoundSolution = new AtomicReference<>();
            private final Comparator<BoardPosition> COMPARATOR;

            /**
             * Creates a reconstruction object used to search the best solution by using only the
             * board positions generated by the optimizer vicinity search.<br>
             * The search considers all 5 metrics. Which solution is the best is determined by
             * the passed comparator.
             */
            public SolutionReconstructionBoxLinesPushes() {
                COMPARATOR = BoardPosition.BOXLINES_PUSHES_COMPARATOR;
            }

            /**
             * Stores an end board position.
             *
             * @param boxConfigurationIndex the index of the box configuration where the searches have met
             * @param playerPosition        player position of the board position where the searches have met
             * @param moveDirection         direction of the last move
             */
            public void addEndBoardPosition(int boxConfigurationIndex, int playerPosition, int moveDirection) {
                EndBoardPositionData meetingData = new EndBoardPositionData(boxConfigurationIndex, playerPosition, moveDirection);
                endBoardPositionSet.add(meetingData);
            }

            /**
             * Deletes all stored meeting points.
             */
            public void removeAllEndBoardPositions() {
                endBoardPositionSet.clear();
            }

            /**
             * Determines the best solution that can be constructed using the board positions
             * that have been stored during the vicinity search. The best solution is found
             * by performing a complete new search considering all 5 metrics.
             *
             * @return the best found solution
             */
            public OptimizerSolution getBestSolution() {

                // Backup the optimizer status since it may be set back to running.
                OptimizerStatus optimizerStatusBackup = optimizerStatus;

                // When the optimizer search couldn't finish normally we can nevertheless check whether the search has already found
                // a better solution. Hence, the optimizer is set back to "running" for the reconstruction.
                if (optimizerStatus == OptimizerStatus.STOPPED_BY_USER || optimizerStatus == OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY) {
                    optimizerStatus = RUNNING;
                }

                // If the optimizer hasn't found an end board position (user stopped the search, out of memory error, ...),
                // the solution is reconstructed by filling the gap between the last found board position and the end board position
                // with board positions of the original solution. Since in this case there is always just one solution only one thread needs to do the work.
                if (endBoardPositionSet.isEmpty()) {

                    // The board positions of the new best found solution.
                    ArrayList<BoardPosition> newSolutionBoardPositions = new ArrayList<>();

                    // Add all board positions of the solution which the search has found. Sanity check: in the rare case
                    // that the search stops before the first board position of the original solution has been reached there is nothing to reconstruct.
                    if (lastVisitedBoardPositionOfOriginalSolutionForward != null) {
                        newSolutionBoardPositions = backwardReconstruction(
                                new EndBoardPositionData(lastVisitedBoardPositionOfOriginalSolutionForward.boxConfigurationIndex,
                                        lastVisitedBoardPositionOfOriginalSolutionForward.playerPosition,
                                        getMoveDirection(lastVisitedBoardPositionOfOriginalSolutionForward)));
                    }

                    // If the forward search hasn't reached any board position of the basis solution we have to fill the gap
                    // beginning by the very first board position of the basis solution we have => set the flag to true.
                    boolean forwardBoardPositionReached = lastVisitedBoardPositionOfOriginalSolutionForward == null;
                    for (BoardPosition basisSolutionBoardPosition : boardPositionsSolutionToBeOptimized) {

                        // The first relevant board position is the one after the last visited board position of the search.
                        if (!forwardBoardPositionReached && basisSolutionBoardPosition.equals(lastVisitedBoardPositionOfOriginalSolutionForward)) {
                            forwardBoardPositionReached = true;
                            continue;
                        }

                        // Add board positions that are missing for a complete solution.
                        if (forwardBoardPositionReached) {
                            BoardPosition boardPosition = new BoardPosition(0, 0, 0, 0, 0, basisSolutionBoardPosition.boxConfigurationIndex, basisSolutionBoardPosition.playerPosition >>> 1);
                            newSolutionBoardPositions.add(boardPosition);
                        }

                    }

                    // Create the new solution and set it as best new-found solution.
                    OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);
                    bestFoundSolution.set(newSolution);
                } else {
                    reconstructFoundSolutions();
                }

                // Set back the old optimizer status if the optimizer hasn't been stopped.
                if (optimizerStatus == RUNNING) {
                    optimizerStatus = optimizerStatusBackup;
                }

                return bestFoundSolution.get();
            }

            /**
             * Returns the direction of the move performed to reach the passed board position.
             *
             * @param boardPosition the board position
             * @return the direction of the move
             */
            private int getMoveDirection(BoardPosition boardPosition) {

                int axisOfMove = boardPosition.playerPosition & 1;
                int moveDirection = 0;
                for (; moveDirection < 4; moveDirection++) {
                    int playerNeighbor, boxPosition;
                    if (getAxisOfDirection(moveDirection) == axisOfMove
                            && (playerNeighbor = playerSquareNeighbor[moveDirection][boardPosition.playerPosition >>> 1]) != NONE
                            && (boxPosition = playerPositionToBoxPosition[playerNeighbor]) != NONE
                            && boxConfigurationStorage.isBoxAtPosition(boardPosition.boxConfigurationIndex, boxPosition)) {
                        return moveDirection;
                    }
                }

                return NONE;
            }

            /**
             * Reconstructs the solutions from the found end board positions.
             */
            private void reconstructFoundSolutions() {

                final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
                final ConcurrentLinkedQueue<EndBoardPositionData> meetingBoardPositions = new ConcurrentLinkedQueue<>(endBoardPositionSet);

                final Runnable reconstruction = () -> {
                    EndBoardPositionData meetingData = null;

                    // Reconstruct and check solutions until all data have been processed.
                    while ((meetingData = meetingBoardPositions.poll()) != null && optimizerStatus == RUNNING) {

                        long time = System.currentTimeMillis();

                        ArrayList<BoardPosition> newSolutionBoardPositions = backwardReconstruction(meetingData);

                        // If the optimizer has been stopped not all board positions have been reconstructed and therefore the solution isn't valid.
                        if (optimizerStatus == RUNNING) {

                            OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);

                            // Check whether this is a new best solution.
                            OptimizerSolution currentBestSolution = null;
                            do {
                                currentBestSolution = bestFoundSolution.get();
                            } while (newSolution.isBetterBoxLinesPushesAllMetricsThan(currentBestSolution) && !bestFoundSolution.compareAndSet(currentBestSolution, newSolution));

                            if (Debug.isDebugModeActivated) {
                                optimizerGUI.addLogTextDebug("Solution reconstruction time: " + (System.currentTimeMillis() - time) + "  Solution: " + newSolution);
                            }
                        }
                    }
                };

                for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                    executor.execute(reconstruction);
                }
                Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            }

            /**
             * Get the moves value from the passed board position.
             *
             * @param boardPosition board position to extract the number of moves
             * @return the number of moves
             */
            private int getBoxLines(StorageBoardPosition boardPosition) {
                return boardPosition.primaryMetric;
            }


            /**
             * Get the pushes value from the passed board position.
             *
             * @param boardPosition board position to extract the number of pushes from
             * @return the number of pushes
             */
            private int getPushes(StorageBoardPosition boardPosition) {
                return boardPosition.secondaryMetric;
            }


            /**
             * A backward search for reconstructing the best solution by using the
             * saved board positions from the forward search.
             * <p>
             * The forward search has saved the reached board positions and its
             * metrics in the board position storage. The end board position of
             * the forward search is used to reconstruct the solution path.
             *
             * @param meetingBoardPositionData data of an end board position
             * @return {@code OptimizerBoardPosition}s of the found path to the start board position
             */
            private ArrayList<BoardPosition> backwardReconstruction(EndBoardPositionData meetingBoardPositionData) {

                final PriorityQueue<BoardPositionWithPushDirection> openQueue = new PriorityQueue<>(10000, COMPARATOR);

                // Add the board position as start for the search. The player position is stored without the axis (the axis information was only used in the forward search).
                int pullDirection = getOppositeDirection(meetingBoardPositionData.lastMoveDirection);    // we like to pull instead of push
                final BoardPositionWithPushDirection startBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition >>> 1, pullDirection, null);
                openQueue.add(startBoardPosition);


                // Set the box lines and pushes of the board position this search starts with.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, SearchDirection.FORWARD);
                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                int startBoardPositionBoxLines = getBoxLines(boardPosition);
                int startBoardPositionPushes = getPushes(boardPosition);

                // Last board position of the best found path to the initial board position of the level.
                BoardPositionWithPushDirection bestEndBoardPosition = null;

                if (startBoardPositionPushes <= 1) {
                    return new ArrayList<>(Collections.singletonList(startBoardPosition));
                }

                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    BoardPositionWithPushDirection currentBoardPosition = openQueue.poll();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;

                    int currentBoardPositionPlayerPosition = currentBoardPosition.playerPosition;

                    // Calculate the position of the last pulled box. The first board position can be identified by having a push direction of -1.
                    int lastPulledBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPositionPlayerPosition]];

                    // Add the start player position and set the current moves depth.
                    playerPositions.addFirst(currentBoardPositionPlayerPosition, 0);

                    playerPositionBFS:
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // Try to move the player to every direction and check whether it's possible to do pulls.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall or a box.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                    (boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {
                                continue;
                            }

                            // The player can move to the new position => store the new position in the queue (if it hasn't been reached, yet).
                            playerPositions.addIfNew(newPlayerPosition);

                            // Check whether there is a box which can be pulled by moving to the new player position. If not, continue with the next move.
                            if ((playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]) == NONE ||  // position next to the player where a box might be
                                    (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                       // position of a possible box to be pulled
                                    !boxConfiguration.isBoxAtPosition(boxPosition) ||                                                     // there is a box next to the player
                                    (newBoxPosition = playerPositionToBoxPosition[playerPosition]) == NONE) {                                     // valid box position
                                continue;
                            }

                            // Pull is possible => do the pull.
                            boxConfiguration.moveBox(boxPosition, newBoxPosition);

                            int boxConfigurationIndex;

                            final int playerPositionWithAxis = (playerPosition << 1) | getAxisOfDirection(direction);

                            // The box configuration must be one of the board positions found during the vicinity search.
                            if ((boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration)) != NONE &&
                                    (boardPositionIndex = boardPositionStorage.getBoardPositionIndex(currentBoardPosition.boxConfigurationIndex, playerPositionWithAxis, SearchDirection.FORWARD)) != BoardPositionsStorage.NONE) {

                                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                                int forwardBoxLines = getBoxLines(boardPosition);
                                int forwardPushes = getPushes(boardPosition);

                                // Calculate the box lines and pushes of the board position directly BEFORE the pull has been made!
                                // This must be done to compare them with the stored board positions of the forward search,
                                // which has stored the board positions AFTER a push has been made.
                                int newBoardPositionPushes = currentBoardPosition.pushes;
                                int newBoardPositionBoxLines = currentBoardPosition.boxLines;

                                boolean lastMovementWasMove = playerPositions.movesDepth != 0;// check whether a move has been done

                                if (boxPosition != lastPulledBoxPosition || lastMovementWasMove) {
                                    newBoardPositionBoxLines++;
                                }


                                // Check if this board position is on the best found path (-> having the correct metrics).
                                if (forwardBoxLines == startBoardPositionBoxLines - newBoardPositionBoxLines &&
                                        forwardPushes == startBoardPositionPushes - newBoardPositionPushes) {

                                    /* Calculate the pushes of the board position, that is: AFTER the pull. */
                                    newBoardPositionPushes++;

                                    // Create a new board position to be added to the open queue.
                                    BoardPositionWithPushDirection newBoardPosition = new BoardPositionWithPushDirection(0, newBoardPositionPushes, newBoardPositionBoxLines, 0, 0,
                                            boxConfigurationIndex, newPlayerPosition, direction, currentBoardPosition);

                                    // This backward search can't always reach the start board position because the player needn't to be
                                    // next to a box in the start board position. Hence, the last board position to search for
                                    // is the one having one push. The initial board position (having 0 pushes) is already known
                                    // in advance and can therefore be discarded.
                                    if (forwardPushes == 1) {
                                        // Check whether the new board position has been reached better than the current
                                        // best end board position. If yes, then set the new board position as new best one.
                                        if (COMPARATOR.compare(newBoardPosition, bestEndBoardPosition) < 0) {
                                            bestEndBoardPosition = newBoardPosition;
                                        }
                                    } else {
                                        openQueue.add(newBoardPosition);
                                        break playerPositionBFS;
                                    }
                                }
                            }

                            // Undo the pull to reuse the box configuration for the next pull.
                            boxConfiguration.moveBox(newBoxPosition, boxPosition);

                        } // Loop that continues moving the player to all 4 directions until all positions have been visited
                    } // Loop that moves the player to all reachable positions
                }

                // Reconstruct the found path and return an array list of the found board positions.
                ArrayList<BoardPosition> foundPath = new ArrayList<>();

                for (BoardPositionWithPushDirection currentBoardPosition = bestEndBoardPosition;
                     currentBoardPosition != null && currentBoardPosition.previous != null; currentBoardPosition = currentBoardPosition.previous) {

                    // Note: The search saved the board positions AFTER the pull! However,
                    // we need a forward path, which means the board positions after
                    // a push has been done - which on the other hand means BEFORE the pull.

                    BoardPosition newBoardPosition = currentBoardPosition;

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);
                    int newPlayerPosition = playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPosition.playerPosition];
                    int boxPosition = playerPositionToBoxPosition[newPlayerPosition];
                    int newBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][newPlayerPosition]];
                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                    newBoardPosition = new BoardPosition(0, 0, 0, 0, 0, boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration), newPlayerPosition);

                    foundPath.add(newBoardPosition);
                }

                return foundPath;
            }
        }
    }

    private class LevelSolvedCheck {

        private int targetPlayerPosition = -1;
        private int targetBoxConfigurationIndex = -1;
        private final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

        public LevelSolvedCheck(OptimizerBoard endBoard) {
            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            // Calculate the end position of the player in the basis solution. This information is needed if the user requested
            // the player to also end at this position in all new found better solutions.
            targetPlayerPosition = playerExternalToInternalPosition[endBoard.playerPosition];
        }

        public boolean isTargetBoardPosition(int boxConfigurationIndex, int playerPosition) {
            return boxConfigurationIndex == targetBoxConfigurationIndex &&
                    (!isPlayerEndPositionToBePreserved || playerPosition == targetPlayerPosition);
        }
    }


    /**
     * Class for searching for a solution by only using the generated box configurations.<br>
     * The new solution is optimized for: <br>
     * 1. box lines<br>
     * 2. moves<br>
     * Before this method is called the box configurations to be used must have been generated.
     */
    private class BoxLinesMovesOptimizer extends AllMetricsOptimizer {

        private final AtomicInteger boxLinesDepthSearch = new AtomicInteger(-1);

        protected volatile int newBestSolutionBoxLinesCount = -1;
        protected int boxLinesImprovement = 0;
        private final SolutionReconstructionBoxLinesMoves solutionReconstruction = new SolutionReconstructionBoxLinesMoves();

        private int internalPlayerStartPosition = -1;

        LevelSolvedCheck levelSolvedCheck;

        /**
         * Optimizes the passed solution by only using the generated box configurations.<br>
         * The path is optimal regarding: <br>
         * 1. box lines<br>
         * 2. moves<br>
         *
         * @param initialBoard        the board at level start
         * @param endBoard            the end board having all boxes on a goal
         * @param currentBestSolution the currently best solution
         * @return the best found solution
         */
        public OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentBestSolution) {

            // Used to store the real positions of the boxes of a box configuration.
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Get the index of the initial box configuration.
            packBoxConfiguration(boxConfiguration, initialBoard);
            startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            levelSolvedCheck = new LevelSolvedCheck(endBoard);


            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            // Get a list of all board positions of the solution.
            boardPositionsSolutionToBeOptimized = getSolutionBoardPositions(currentBestSolution);

            // Yet, no solution has been found. However, the current best solution is used as upper bound for the box lines and moves.
            BoardPosition endBoardPosition = boardPositionsSolutionToBeOptimized.get(boardPositionsSolutionToBeOptimized.size() - 1);
            newBestSolutionBoxLinesCount = endBoardPosition.boxLines;
            newBestSolutionMovesCount = endBoardPosition.moves;

            currentBestSolution.movesCount = endBoardPosition.moves;
            currentBestSolution.pushesCount = endBoardPosition.pushes;
            currentBestSolution.boxChanges = endBoardPosition.boxChanges;
            currentBestSolution.boxLines = endBoardPosition.boxLines;
            currentBestSolution.pushingSessions = endBoardPosition.pushingSessions;

            this.solutionToBeOptimized = currentBestSolution;

            int highestMoveChangePerBoxLine = getHighestMoveChangePerBoxLine(boardPositionsSolutionToBeOptimized);

            // The order value is calculated:
            // boxLinesCount * (estimated maximum number of moves+1) + movesCount
            // The box lines may increase by 0 or 1 per push. However, the move increase per push isn't known.
            // It is estimated not to be higher than 5 times the highest number of moves in one box line of the solution to be optimized.
            // Additionally, we estimate the new solution not having more than MAXIMAL_MOVES_FACTOR times the number of moves than the solution to be optimized.
            final int estimatedMaximumNewMovesCount = PushesMovesAllMetricsOptimizer.MAXIMAL_MOVES_FACTOR * currentBestSolution.movesCount;
            final int maximumOrderValueChange = 1 * (estimatedMaximumNewMovesCount + 1) + 5 * highestMoveChangePerBoxLine;
            final int minimumOrderValueChange = 1;     // 0 box lines and 1 move

            boardPositionStorage = new BoardPositionsStorage(boxConfigurationStorage.getSize(), playerSquaresCount, 2, estimatedMaximumNewMovesCount, estimatedMaximumNewMovesCount);

            openQueueForward = PriorityQueueOptimizer.getInstance(minimumOrderValueChange, maximumOrderValueChange, boardPositionStorage, maxCPUsToBeUsedCount);


            // Add the start board position for the forward search to the open queue
            // and store it in the reachedPositionsStorage for detecting duplicates.
            internalPlayerStartPosition = playerExternalToInternalPosition[initialBoard.playerPosition];

            // If we don't optimize a pushes range there is no prefix push. In this case we can take any axis as start,
            // because the first push is counted as new box change/box line anyway.
            int firstPushAxis = prefixAxisOfLastPush == NONE ? AXIS_HORIZONTAL : prefixAxisOfLastPush;

            long indexStartBoardPosition = boardPositionStorage.addIfBetter(0, 0, startBoxConfigurationIndex, (internalPlayerStartPosition << 1) | firstPushAxis, SearchDirection.FORWARD);
            openQueueForward.add(indexStartBoardPosition);

            // Execute the optimizer searches.
            final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
            for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                executor.execute(new BoxLinesMovesForwardSearch(threadNo));
            }

            Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            if (Debug.isDebugModeActivated) {
                debugDisplayStatistic();
            }

            // Inform the user about the RAM that is available after the search has ended.
            optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMinMiB()));

            // The optimizer has only been stopped to start a new iteration with the new best found solution.
            // Hence, the optimizer is still running.
            if (optimizerStatus == OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION) {
                optimizerStatus = RUNNING;
            }

            // Return the best found solution (or null if no solution has been found, due
            // to stop of the optimizer by user, out of memory, ...)
            return getBestFoundSolution();
        }

        @Override
        public OptimizerSolution getBestFoundSolution() {
            synchronized (mainOptimizerThread) {
                return solutionReconstruction.getBestSolution();
            }
        }

        /**
         * Calculates the highest number of moves per box line in a solution passed as board positions.
         *
         * @return the highest number of moves per box line
         */
        private int getHighestMoveChangePerBoxLine(ArrayList<BoardPosition> boardPositionsOfSolution) {
            int highestMoveChange = 0;
            int previousMovesValue = 0;
            int previousBoxLinesValue = 0;
            BoardPosition lastBoardPosition = null;
            for (BoardPosition boardPosition : boardPositionsOfSolution) {
                if (boardPosition.boxLines > previousBoxLinesValue) {
                    previousBoxLinesValue = boardPosition.boxLines;
                    highestMoveChange = Math.max(boardPosition.moves - previousMovesValue, highestMoveChange);
                    previousMovesValue = boardPosition.moves;
                }
                lastBoardPosition = boardPosition;
            }

            if (lastBoardPosition != null) {    // the last box line end with the last board position.
                highestMoveChange = Math.max(lastBoardPosition.moves - previousMovesValue, highestMoveChange);
            }

            return highestMoveChange;
        }

        /**
         * A forward search considering first box lines, second moves.
         */
        private class BoxLinesMovesForwardSearch implements Runnable {

            /**
             * Unique ID for every thread for accessing the priority queue.
             */
            private final int priorityQueueThreadID;

            /**
             * Creates a {@code Runnable} that performs a forward search for the optimizer.
             *
             * @param priorityQueueThreadID unique ID for the priority queue
             */
            public BoxLinesMovesForwardSearch(int priorityQueueThreadID) {
                this.priorityQueueThreadID = priorityQueueThreadID;
            }

            /**
             * Starts a new forward search.
             */
            @Override
            public void run() {

                try {
                    forwardSearch();

                } catch (OutOfMemoryError e) {
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }

            /**
             * Forward search searching from the start board position forward
             * (-> pushing boxes) until it meets the backward search.
             */
            private void forwardSearch() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    long currentBoardPositionIndex = openQueueForward.removeFirst(priorityQueueThreadID);
                    if (currentBoardPositionIndex == NONE) {
                        break;
                    }

                    // Mark as processed - BEFORE the real metrics are read. This ensures that when
                    // the board position is reached better in the meantime the new better values are read.
                    boardPositionStorage.markAsProcessed(currentBoardPositionIndex);

                    boardPositionStorage.fillBoardPosition(currentBoardPositionIndex, boardPosition);

                    int axis = boardPosition.playerPosition & 1; // Axis is stored in first bit
                    boardPosition.playerPosition >>>= 1;         // Calculate the real player position (axis is stored in first bit)

                    int boxLinesDepth = boardPosition.primaryMetric;
                    int movesDepth = boardPosition.secondaryMetric;
                    int currentBoxConfigurationIndex = boardPosition.boxConfigurationIndex;

                    int currentDepth = boxLinesDepthSearch.get();
                    if (boxLinesDepth > currentDepth) {
                        if (boxLinesDepth > newBestSolutionBoxLinesCount) {
                            continue;
                        }
                        if (boxLinesDepthSearch.compareAndSet(currentDepth, boxLinesDepth)) {
                            checkForImprovement();
                        }
                    }

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                    playerPositions.addFirst(boardPosition.playerPosition, 0);

                    // Breadth First Search over all reachable player positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Is the neighbor position of the box no deadlock square and not blocked by another box?
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        // First push is always a new boxLine unless we optimize a pushesRange, in which case there is a previous push
                                        // whose axis has been passed to the optimizer.
                                        int newBoxLines = boardPosition.primaryMetric;
                                        if ((movesDepth == 0 && prefixAxisOfLastPush == NONE) || playerPositions.movesDepth != 0 || getAxisOfDirection(direction) != axis) {
                                            newBoxLines++;
                                        }

                                        int newMovesDepth = boardPosition.secondaryMetric + playerPositions.movesDepth + 1;
                                        int newPlayerPositionWithAxis = (newPlayerPosition << 1) | getAxisOfDirection(direction); // player position is shifted to store the axis, too

                                        long addedBoardPositionIndex = boardPositionStorage.addIfBetter(newBoxLines, newMovesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, SearchDirection.FORWARD);
                                        if (addedBoardPositionIndex != BoardPositionsStorage.NONE) {
                                            if (levelSolvedCheck.isTargetBoardPosition(boxConfigurationIndex, newPlayerPosition)) {
                                                collectBestSolutions(newBoxLines, newMovesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, direction);
                                            } else {
                                                openQueueForward.add(addedBoardPositionIndex);
                                            }
                                        }
                                    }
                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }
            }

            /**
             * This method informs the user whether a better path to any of the board positions of the solution to be optimized
             * has been found and about the current box lines depth that the search has reached.
             */
            private void checkForImprovement() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Identify the board position having the highest number of pushes that has already been reached by
                // the forward search and is part of the basis solution to be optimized and check for any improvements.
                // Ignore the last board position because there are levels which start with all boxes on goals.
                // For these levels start and end board position are the same.
                for (int index = boardPositionsSolutionToBeOptimized.size() - 1; --index != -1; ) {

                    BoardPosition basisSolutionBoardPosition = boardPositionsSolutionToBeOptimized.get(index);

                    // Check whether the board position has already been reached by the forward search.
                    long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(basisSolutionBoardPosition, SearchDirection.FORWARD);
                    if (boardPositionIndex != BoardPositionsStorage.NONE) {

                        boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);

                        // Calculate how much better the board position has been reached compared to the basis solution.
                        int boxLinesDif = basisSolutionBoardPosition.boxLines - boardPosition.primaryMetric;
                        int movesDif = basisSolutionBoardPosition.moves - boardPosition.secondaryMetric;

                        synchronized (mainOptimizerThread) {
                            if (boxLinesDif > boxLinesImprovement ||
                                    boxLinesDif == boxLinesImprovement && movesDif > movesImprovementForwardSearch) {
                                boxLinesImprovement = boxLinesDif;
                                movesImprovementForwardSearch = movesDif;

                                if (immediateNewIterationWhenImprovement && optimizerStatus == RUNNING) {
                                    optimizerStatus = OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION;
                                }
                            }

                            // Save the last visited board position of the original solution if it has been reached on the currently best known path.
                            if (boxLinesImprovement == boxLinesDif && movesImprovementForwardSearch == movesDif) {
                                lastVisitedBoardPositionOfOriginalSolutionForward = basisSolutionBoardPosition;
                            }
                        }

                        break;
                    }
                }

                displayIntermediateResult();
            }
        }

        /**
         * Returns a list of all board positions the passed solution
         * consists of. The board positions containing information
         * about with which metrics they have in the solution (moves, pushes, ...)
         *
         * @param currentBestSolution solution whose board positions are to be returned
         * @return the board positions of the passed solution consists of
         */
        @Override
        protected ArrayList<BoardPosition> getSolutionBoardPositions(final OptimizerSolution currentBestSolution) {

            return getBoardPositions(initialBoard, currentBestSolution.solution, prefixAxisOfLastPush)
                    .map(bp -> {
                        int playerPositionWithAxis = (bp.playerPosition << 1) | getAxisOfDirection(bp.pushDirection);
                        return new BoardPosition(bp.moves, bp.pushes, bp.boxLines, bp.boxChanges, bp.pushingSessions, bp.boxConfigurationIndex, playerPositionWithAxis);
                    }).collect(toCollection(ArrayList::new));
        }

        /**
         * Calculates and displays the current box lines depth of the search and the current best solution metrics.
         */
        private void displayIntermediateResult() {

            synchronized (mainOptimizerThread) {

                int totalFoundBoxLinesImprovement = boxLinesImprovement;
                int totalFoundMovesImprovement = movesImprovementForwardSearch;

                int newBoxLinesCount = solutionToBeOptimized.boxLines - totalFoundBoxLinesImprovement;
                int newMovesCount = solutionToBeOptimized.movesCount - totalFoundMovesImprovement;

                // Set the new metrics when a new best solution has been found.
                if (newBoxLinesCount < newBestSolutionBoxLinesCount || newBoxLinesCount == newBestSolutionBoxLinesCount && newMovesCount < newBestSolutionMovesCount) {
                    newBestSolutionBoxLinesCount = newBoxLinesCount;
                    newBestSolutionMovesCount = newMovesCount;
                }

                // Display a status message to show the current depth and the current found improvement.
                // If a only a specific pushes range of the whole solution is optimized,
                // only the metrics of this pushes range are displayed (without the prefix and suffix moves
                // that must be added to get the whole solution).
                optimizerGUI.setInfoText(
                        Texts.getText("vicinitySearchBoxLinesDepth")
                                + boxLinesDepthSearch.get()
                                + "    "
                                + Texts.getText("bestSolution") + ": "
                                + newBestSolutionBoxLinesCount
                                + "/"
                                + newBestSolutionMovesCount
                                + " (" + (-totalFoundBoxLinesImprovement) + "/" + (-totalFoundMovesImprovement) + ")"
                );
            }
        }


        /**
         * Checks whether the search has found a new best solution. If yes, then the data of this new solution is saved.
         *
         * @param boxLinesDepth         number of box lines of the search direction that has found the solution
         * @param movesDepth            number of moves of the search direction that has found the solution
         * @param boxConfigurationIndex index of the box configuration
         * @param playerPosition        player position of the board position
         * @param lastMoveDirection     direction of the last move
         */
        private void collectBestSolutions(
                final int boxLinesDepth,
                final int movesDepth,
                final int boxConfigurationIndex,
                final int playerPosition,
                final int lastMoveDirection) {

            // Check if the new solution is at least as good as the current best one. Synchronized because the metrics of
            // the current best solution may otherwise change in the meantime.
            synchronized (mainOptimizerThread) {
                if (boxLinesDepth < newBestSolutionBoxLinesCount ||
                        boxLinesDepth == newBestSolutionBoxLinesCount && movesDepth <= newBestSolutionMovesCount) {

                    // Only solutions having the best metrics have to be reconstructed. If this solution is a new best one then the
                    // other solutions (represented by the meeting point) can be deleted.
                    if (boxLinesDepth < newBestSolutionBoxLinesCount || movesDepth < newBestSolutionMovesCount) {
                        solutionReconstruction.removeAllEndBoardPositions();

                        newBestSolutionBoxLinesCount = boxLinesDepth;
                        newBestSolutionMovesCount = movesDepth;

                        boxLinesImprovement = solutionToBeOptimized.boxLines - newBestSolutionBoxLinesCount;
                        movesImprovementForwardSearch = solutionToBeOptimized.movesCount - newBestSolutionMovesCount;

                        displayIntermediateResult();
                    }

                    solutionReconstruction.addEndBoardPosition(boxConfigurationIndex, playerPosition, lastMoveDirection);

                    if (Debug.isDebugModeActivated) {
                        optimizerGUI.addLogTextDebug("New best solution found: BoxLines/Moves: " + (newBestSolutionBoxLinesCount) + "/" + (newBestSolutionMovesCount));
                    }
                }
            }
        }

        /**
         * The main optimizer search stores all board positions in the storage.
         * This class does a new search over the stored board positions in order to reconstruct the best solution
         * regarding first box lines and second moves.
         */
        protected class SolutionReconstructionBoxLinesMoves {

            private final Set<EndBoardPositionData> endBoardPositionSet = ConcurrentHashMap.newKeySet();

            private final AtomicReference<OptimizerSolution> bestFoundSolution = new AtomicReference<>();
            private final Comparator<BoardPosition> COMPARATOR;

            /**
             * Creates a reconstruction object used to search the best solution by using only the
             * board positions generated by the optimizer vicinity search.<br>
             * The search considers all 5 metrics. Which solution is the best is determined by
             * the passed comparator.
             */
            public SolutionReconstructionBoxLinesMoves() {
                COMPARATOR = BoardPosition.BOXLINES_MOVES_COMPARATOR;
            }

            /**
             * Stores an end board position.
             *
             * @param boxConfigurationIndex the index of the box configuration where the searches have met
             * @param playerPosition        player position of the board position where the searches have met
             * @param moveDirection         direction of the last move
             */
            public void addEndBoardPosition(int boxConfigurationIndex, int playerPosition, int moveDirection) {
                EndBoardPositionData meetingData = new EndBoardPositionData(boxConfigurationIndex, playerPosition, moveDirection);
                endBoardPositionSet.add(meetingData);
            }

            /**
             * Deletes all stored meeting points.
             */
            public void removeAllEndBoardPositions() {
                endBoardPositionSet.clear();
            }

            /**
             * Determines the best solution that can be constructed using the board positions
             * that have been stored during the vicinity search. The best solution is found
             * by performing a complete new search considering all 5 metrics.
             *
             * @return the best found solution
             */
            public OptimizerSolution getBestSolution() {

                // Backup the optimizer status since it may be set back to running.
                OptimizerStatus optimizerStatusBackup = optimizerStatus;

                // When the optimizer search couldn't finish normally we can nevertheless check whether the search has already found
                // a better solution. Hence, the optimizer is set back to "running" for the reconstruction.
                if (optimizerStatus == OptimizerStatus.STOPPED_BY_USER || optimizerStatus == OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY) {
                    optimizerStatus = RUNNING;
                }

                // If the optimizer hasn't found an end board position (user stopped the search, out of memory error, ...),
                // the solution is reconstructed by filling the gap between the last found board position and the end board position
                // with board positions of the original solution. Since in this case there is always just one solution only one thread needs to do the work.
                if (endBoardPositionSet.isEmpty()) {

                    // The board positions of the new best found solution.
                    ArrayList<BoardPosition> newSolutionBoardPositions = new ArrayList<>();

                    // Add all board positions of the solution which the search has found. Sanity check: in the rare case
                    // that the search stops before the first board position of the original solution has been reached there is nothing to reconstruct.
                    if (lastVisitedBoardPositionOfOriginalSolutionForward != null) {
                        newSolutionBoardPositions = backwardReconstruction(
                                new EndBoardPositionData(lastVisitedBoardPositionOfOriginalSolutionForward.boxConfigurationIndex,
                                        lastVisitedBoardPositionOfOriginalSolutionForward.playerPosition,
                                        getMoveDirection(lastVisitedBoardPositionOfOriginalSolutionForward)));
                    }

                    // If the forward search hasn't reached any board position of the basis solution we have to fill the gap
                    // beginning by the very first board position of the basis solution we have => set the flag to true.
                    boolean forwardBoardPositionReached = lastVisitedBoardPositionOfOriginalSolutionForward == null;
                    for (BoardPosition basisSolutionBoardPosition : boardPositionsSolutionToBeOptimized) {

                        // The first relevant board position is the one after the last visited board position of the search.
                        if (!forwardBoardPositionReached && basisSolutionBoardPosition.equals(lastVisitedBoardPositionOfOriginalSolutionForward)) {
                            forwardBoardPositionReached = true;
                            continue;
                        }

                        // Add board positions that are missing for a complete solution.
                        if (forwardBoardPositionReached) {
                            BoardPosition boardPosition = new BoardPosition(0, 0, 0, 0, 0, basisSolutionBoardPosition.boxConfigurationIndex, basisSolutionBoardPosition.playerPosition >>> 1);
                            newSolutionBoardPositions.add(boardPosition);
                        }

                    }

                    // Create the new solution and set it as best new found solution.
                    OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);
                    bestFoundSolution.set(newSolution);
                } else {
                    reconstructFoundSolutions();
                }

                // Set back the old optimizer status if the optimizer hasn't been stopped.
                if (optimizerStatus == RUNNING) {
                    optimizerStatus = optimizerStatusBackup;
                }

                return bestFoundSolution.get();
            }

            /**
             * Returns the direction of the move performed to reach the passed board position.
             *
             * @param boardPosition the board position
             * @return the direction of the move
             */
            private int getMoveDirection(BoardPosition boardPosition) {

                int axisOfMove = boardPosition.playerPosition & 1;
                int moveDirection = 0;
                for (; moveDirection < 4; moveDirection++) {
                    int playerNeighbor, boxPosition;
                    if (getAxisOfDirection(moveDirection) == axisOfMove
                            && (playerNeighbor = playerSquareNeighbor[moveDirection][boardPosition.playerPosition >>> 1]) != NONE
                            && (boxPosition = playerPositionToBoxPosition[playerNeighbor]) != NONE
                            && boxConfigurationStorage.isBoxAtPosition(boardPosition.boxConfigurationIndex, boxPosition)) {
                        return moveDirection;
                    }
                }

                return NONE;
            }

            /**
             * Reconstructs the solutions from the found end board positions.
             */
            private void reconstructFoundSolutions() {

                final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
                final ConcurrentLinkedQueue<EndBoardPositionData> meetingBoardPositions = new ConcurrentLinkedQueue<>(endBoardPositionSet);

                final Runnable reconstruction = () -> {
                    EndBoardPositionData meetingData = null;

                    // Reconstruct and check solutions until all data have been processed.
                    while ((meetingData = meetingBoardPositions.poll()) != null && optimizerStatus == RUNNING) {

                        long time = System.currentTimeMillis();

                        ArrayList<BoardPosition> newSolutionBoardPositions = backwardReconstruction(meetingData);

                        // If the optimizer has been stopped not all board positions have been reconstructed and therefore the solution isn't valid.
                        if (optimizerStatus == RUNNING) {

                            OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);

                            // Check whether this is a new best solution.
                            OptimizerSolution currentBestSolution = null;
                            do {
                                currentBestSolution = bestFoundSolution.get();
                            } while (newSolution.isBetterBoxLinesMovesAllMetricsThan(currentBestSolution) && !bestFoundSolution.compareAndSet(currentBestSolution, newSolution));

                            if (Debug.isDebugModeActivated) {
                                optimizerGUI.addLogTextDebug("Solution reconstruction time: " + (System.currentTimeMillis() - time) + "  Solution: " + newSolution);
                            }
                        }
                    }
                };

                for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                    executor.execute(reconstruction);
                }
                Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            }

            /**
             * Get the box lines value from the passed board position.
             *
             * @param boardPosition board position to extract the number of moves
             * @return the number of box lines
             */
            private int getBoxLines(StorageBoardPosition boardPosition) {
                return boardPosition.primaryMetric;
            }


            /**
             * Get the moves value from the passed board position.
             *
             * @param boardPosition board position to extract the number of moves from
             * @return the number of moves
             */
            private int getMoves(StorageBoardPosition boardPosition) {
                return boardPosition.secondaryMetric;
            }


            /**
             * A backward search for reconstructing the best solution by using the
             * saved board positions from the forward search.
             * <p>
             * The forward search has saved the reached board positions and its
             * metrics in the board position storage. The end board position of
             * the forward search is used to reconstruct the solution path.
             *
             * @param meetingBoardPositionData data of an end board position
             * @return {@code OptimizerBoardPosition}s of the found path to the start board position
             */
            private ArrayList<BoardPosition> backwardReconstruction(EndBoardPositionData meetingBoardPositionData) {

                final PriorityQueue<BoardPositionWithPushDirection> openQueue = new PriorityQueue<>(10000, COMPARATOR);

                // Add the board position as start for the search. The player position is stored without the axis (the axis information was only used in the forward search).
                int pullDirection = getOppositeDirection(meetingBoardPositionData.lastMoveDirection);    // we like to pull instead of push
                final BoardPositionWithPushDirection startBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition >>> 1, pullDirection, null);
                openQueue.add(startBoardPosition);


                // Set the box lines and moves of the board position this search starts with.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, SearchDirection.FORWARD);
                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                int startBoardPositionBoxLines = getBoxLines(boardPosition);
                int startBoardPositionMoves = getMoves(boardPosition);

                // Last board position of the best found path to the initial board position of the level.
                BoardPositionWithPushDirection bestEndBoardPosition = null;

                if (startBoardPositionMoves <= 1) {
                    return new ArrayList<>(Collections.singletonList(startBoardPosition));
                }

                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    BoardPositionWithPushDirection currentBoardPosition = openQueue.poll();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;

                    int currentBoardPositionPlayerPosition = currentBoardPosition.playerPosition;

                    // Calculate the position of the last pulled box. The first board position can be identified by having a push direction of -1.
                    int lastPulledBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPositionPlayerPosition]];

                    // Add the start player position and set the current moves depth.
                    playerPositions.addFirst(currentBoardPositionPlayerPosition, 0);

                    playerPositionBFS:
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // This backward search can't always reach the start board position because the player
                        // needn't to be next to a box in the start board position. Hence, the last board position
                        // to search for is the one having one push. The initial board position (having 0 pushes)
                        // is already known in advance and can therefore be discarded.
                        // => If we have reached the start box configuration by the last push then the current
                        // board position must be the one having 1 push.
                        if (currentBoardPosition.boxConfigurationIndex == startBoxConfigurationIndex && playerPosition == internalPlayerStartPosition
                                && currentBoardPosition.pushes > 0) {        // There exist levels which starts with a solved board position -> check for at least one done push
                            // Check whether the new board position has been reached better than the current
                            // best end board position. If yes, then set the new board position as new best one.
                            if (COMPARATOR.compare(currentBoardPosition, bestEndBoardPosition) < 0) {
                                bestEndBoardPosition = currentBoardPosition;
                            }

                            continue;
                        }

                        // Try to move the player to every direction and check whether it's possible to do pulls.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall or a box.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                    (boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {
                                continue;
                            }

                            // The player can move to the new position => store the new position in the queue (if it hasn't been reached, yet).
                            playerPositions.addIfNew(newPlayerPosition);

                            // Check whether there is a box which can be pulled by moving to the new player position. If not, continue with the next move.
                            if ((playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]) == NONE ||  // position next to the player where a box might be
                                    (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                         // position of a possible box to be pulled
                                    !boxConfiguration.isBoxAtPosition(boxPosition) ||                                                         // there is a box next to the player
                                    (newBoxPosition = playerPositionToBoxPosition[playerPosition]) == NONE) {                                         // valid box position
                                continue;
                            }

                            // Pull is possible => do the pull.
                            boxConfiguration.moveBox(boxPosition, newBoxPosition);

                            int newBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

                            final int playerPositionWithAxis = (playerPosition << 1) | getAxisOfDirection(direction);

                            // The box configuration must be one of the board positions found during the vicinity search.
                            if (newBoxConfigurationIndex != NONE &&
                                    (boardPositionIndex = boardPositionStorage.getBoardPositionIndex(currentBoardPosition.boxConfigurationIndex, playerPositionWithAxis, SearchDirection.FORWARD)) != BoardPositionsStorage.NONE) {

                                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                                int forwardBoxLines = getBoxLines(boardPosition);
                                int forwardMoves = getMoves(boardPosition);

                                // Calculate the box lines and moves of the board position directly BEFORE the pull has been made!
                                // This must be done to compare them with the stored board positions of the forward search,
                                // which has stored the board positions AFTER a push has been made.
                                int newBoardPositionMoves = currentBoardPosition.moves + playerPositions.movesDepth;
                                int newBoardPositionPushes = currentBoardPosition.pushes;
                                int newBoardPositionBoxLines = currentBoardPosition.boxLines;

                                boolean lastMovementWasMove = playerPositions.movesDepth != 0;// check whether a move has been done

                                if (boxPosition != lastPulledBoxPosition || lastMovementWasMove) {
                                    newBoardPositionBoxLines++;
                                }

                                // Check if this board position is on the best found path (-> having the correct metrics).
                                if (forwardBoxLines == startBoardPositionBoxLines - newBoardPositionBoxLines &&
                                        forwardMoves == startBoardPositionMoves - newBoardPositionMoves) {

                                    /* Calculate the new metrics of the board position, that is: AFTER the pull. */
                                    newBoardPositionMoves++;
                                    newBoardPositionPushes++;

                                    // Create a new board position to be added to the open queue.
                                    BoardPositionWithPushDirection newBoardPosition = new BoardPositionWithPushDirection(newBoardPositionMoves,
                                            newBoardPositionPushes, newBoardPositionBoxLines, 0, 0,
                                            newBoxConfigurationIndex, newPlayerPosition, direction, currentBoardPosition);

                                    openQueue.add(newBoardPosition);

                                    break playerPositionBFS; // we only search any path regarding box lines and moves
                                }
                            }

                            boxConfiguration.moveBox(newBoxPosition, boxPosition);        // Undo the pull to reuse the box configuration for the next pull.

                        } // Loop that continues moving the player to all 4 directions until all positions have been visited
                    } // Loop that moves the player to all reachable positions
                }

                // Reconstruct the found path and return an array list of the found board positions.
                ArrayList<BoardPosition> foundPath = new ArrayList<>();

                for (BoardPositionWithPushDirection currentBoardPosition = bestEndBoardPosition;
                     currentBoardPosition != null && currentBoardPosition.previous != null; currentBoardPosition = currentBoardPosition.previous) {

                    // Note: The search saved the board positions AFTER the pull! However,
                    // we need a forward path, which means the board positions after
                    // a push has been done - which on the other hand means BEFORE the pull.

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);
                    int newPlayerPosition = playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPosition.playerPosition];
                    int boxPosition = playerPositionToBoxPosition[newPlayerPosition];
                    int newBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][newPlayerPosition]];
                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                    BoardPosition newBoardPosition = new BoardPosition(0, 0, 0, 0, 0, boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration), newPlayerPosition);

                    foundPath.add(newBoardPosition);
                }

                return foundPath;
            }
        }
    }

    /**
     * Class for searching a solution by only using the generated box configurations.<br>
     * The new solution is optimized for: <br>
     * 1. moves<br>
     * 2. highest pushes<br>
     * Before this method is called the box configurations to be used must have been generated.
     */
    private class MovesHighestPushesOptimizer extends AllMetricsOptimizer {

        private final AtomicInteger movesDepthForwardSearch = new AtomicInteger(-1);

        protected volatile int newBestSolutionMovesCount = -1;

        private final SolutionReconstructionMovesHighestPushes solutionReconstruction = new SolutionReconstructionMovesHighestPushes();

        private int newSolutionMaxPushesCount;

        LevelSolvedCheck levelSolvedCheck;


        /**
         * Optimizes the passed solution by only using the generated box configurations.<br>
         * The path is optimal regarding: <br>
         * 1. moves<br>
         * 2. highest pushes<br>
         * <p>
         * The common coding for the search is used for searching for *highest* pushes.
         * In order to do this the search doesn't use the real pushes value for the search but
         * "newSolutionMaxPushesCount - real pushes value". This way higher pushes result in
         * a lower value and the common codings can be used (for instance the priority queue).
         *
         * @param initialBoard        the board at level start
         * @param endBoard            the end board having all boxes on a goal
         * @param currentBestSolution the currently best solution
         * @return the best found solution
         */
        public OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentBestSolution) {

            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Get the index of the initial box configuration.
            packBoxConfiguration(boxConfiguration, initialBoard);
            startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            levelSolvedCheck = new LevelSolvedCheck(endBoard);

            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            boardPositionsSolutionToBeOptimized = getSolutionBoardPositions(currentBestSolution);

            // Yet, no solution has been found. However, the current best solution is used as upper bound for the moves and pushes.
            BoardPosition endBoardPosition = boardPositionsSolutionToBeOptimized.get(boardPositionsSolutionToBeOptimized.size() - 1);
            newBestSolutionMovesCount = endBoardPosition.moves;
            newBestSolutionPushesCount = endBoardPosition.pushes;

            newSolutionMaxPushesCount = endBoardPosition.moves;    // the new solution pushes can't be higher than the number of moves

            // Set the metrics of the solution. Since we search for the highest pushes we use a high pushes value
            // and subtract the real pushes value.
            currentBestSolution.movesCount = endBoardPosition.moves;
            currentBestSolution.pushesCount = endBoardPosition.pushes;

            this.solutionToBeOptimized = currentBestSolution;

            // Calculate the highest move increase per push in the solution.
            int highestMoveChange = getHighestMoveChange(boardPositionsSolutionToBeOptimized);

            // Since we optimize for moves and then HIGHEST pushes we negate the pushes (secondary metric) on the fly
            // when we interact with the storage so board positions having higher pushes are considered "better".
            boardPositionStorage = new BoardPositionsStorage(boxConfigurationStorage.getSize(), playerSquaresCount, 2, newSolutionMaxPushesCount, endBoardPosition.moves) {
                @Override
                public long addIfBetter(int moves, int pushes, int boxConfigurationIndex, int playerPosition) {
                    pushes = newSolutionMaxPushesCount - pushes;
                    return super.addIfBetter(moves, pushes, boxConfigurationIndex, playerPosition);
                }

                @Override
                public void fillBoardPosition(long boardPositionIndex, StorageBoardPosition boardPosition) {
                    super.fillBoardPosition(boardPositionIndex, boardPosition);
                    boardPosition.secondaryMetric = newSolutionMaxPushesCount - boardPosition.secondaryMetric;
                }

                public long getBoardPositionIndex(BoardPosition boardPosition) {
                    return super.getBoardPositionIndex(boardPosition, SearchDirection.FORWARD);
                }
            };

            // The order value for 0 moves and 0 pushes must be 0.
            // A new order value must be calculated after every push.
            // We can calculate the minimum and maximum order value change by passing the minimum and maximum metric changes per push.
            // However, the optimizer doesn't know how many moves could be done for doing one push.
            // The highest number of move for pushing a box in the current best solution is "highestMoveChange".
            // We estimate that the highest move change in the new solution is at most 5 times higher than that.
            // If it is indeed higher than that the optimizer will be slowed down.
            final int maximumOrderValueChange = (int) boardPositionStorage.getOrderValueFromMetrics(5 * highestMoveChange, newSolutionMaxPushesCount - 1); // x moves and 1 push
            final int minimumOrderValueChange = (int) boardPositionStorage.getOrderValueFromMetrics(1, newSolutionMaxPushesCount - 1);                   // 1 move and 1 push

            // The priority queue reads the values (moves and pushes) from the storage we provide. Therefore the priority queue automatically sorts
            // the board positions according to our overwritten methods (see above anonymous sub class of "BoardPositionsStorage"), that is: moves, then highest pushes.
            openQueueForward = PriorityQueueOptimizer.getInstance(minimumOrderValueChange, maximumOrderValueChange, boardPositionStorage, maxCPUsToBeUsedCount);

            int interalPlayerStartPosition = playerExternalToInternalPosition[initialBoard.playerPosition];

            long indexStartBoardPosition = boardPositionStorage.addIfBetter(0, 0, startBoxConfigurationIndex, interalPlayerStartPosition);
            openQueueForward.add(indexStartBoardPosition);

            // Execute the optimizer searches.
            final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
            for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                executor.execute(new MovesHighestPushesForwardSearch(threadNo));
            }

            Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            if (Debug.isDebugModeActivated) {
                debugDisplayStatistic();
            }

            // Inform the user about the RAM that is available after the search has ended.
            optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMinMiB()));

            // The optimizer has only been stopped to start a new iteration with the new best found solution.
            // Hence, the optimizer is still running.
            if (optimizerStatus == OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION) {
                optimizerStatus = RUNNING;
            }

            // Return the best found solution (or null if no solution has been found, due
            // to stop of the optimizer by user, out of memory, ...)
            return getBestFoundSolution();
        }


        @Override
        public OptimizerSolution getBestFoundSolution() {
            synchronized (mainOptimizerThread) {
                return solutionReconstruction.getBestSolution();
            }
        }

        /**
         * A forward search considering first moves, second-highest pushes.
         */
        private class MovesHighestPushesForwardSearch implements Runnable {

            /**
             * Unique ID for every thread for accessing the priority queue.
             */
            private final int priorityQueueThreadID;

            /**
             * Creates a {@code Runnable} that performs a forward search for the optimizer.
             *
             * @param priorityQueueThreadID unique ID for the priority queue
             */
            public MovesHighestPushesForwardSearch(int priorityQueueThreadID) {
                this.priorityQueueThreadID = priorityQueueThreadID;
            }

            /**
             * Starts a new forward search.
             */
            @Override
            public void run() {

                try {
                    forwardSearch();
                } catch (OutOfMemoryError e) {
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }

            /**
             * Forward search searching from the start board position forward (-> pushing boxes).
             */
            private void forwardSearch() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    long currentBoardPositionIndex = openQueueForward.removeFirst(priorityQueueThreadID);
                    if (currentBoardPositionIndex == NONE) {
                        break;
                    }

                    // Mark as processed - BEFORE the real metrics are read. This ensures that when
                    // the board position is reached better in the meantime, the new better values are read.
                    boardPositionStorage.markAsProcessed(currentBoardPositionIndex);

                    boardPositionStorage.fillBoardPosition(currentBoardPositionIndex, boardPosition);

                    // One push and one move is done -> adjust the metrics.
                    int movesDepth = boardPosition.primaryMetric + 1;
                    int pushesDepth = boardPosition.secondaryMetric + 1;
                    int currentBoxConfigurationIndex = boardPosition.boxConfigurationIndex;

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                    // Add the player position in the current moves depth to the BFS. The BFS object (playerPositions)
                    // automatically increases the moves depth during the BFS.
                    playerPositions.addFirst(boardPosition.playerPosition, movesDepth);

                    // Breadth-First-Search over all reachable player positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Is the neighbor position of the box no deadlock square and not blocked by another box?
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the generated box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        long addedBoardPositionIndex = boardPositionStorage.addIfBetter(playerPositions.movesDepth, pushesDepth, boxConfigurationIndex, newPlayerPosition);
                                        if (addedBoardPositionIndex != BoardPositionsStorage.NONE) {
                                            if (levelSolvedCheck.isTargetBoardPosition(boxConfigurationIndex, newPlayerPosition)) {
                                                collectBestSolutions(playerPositions.movesDepth, pushesDepth, boxConfigurationIndex, newPlayerPosition, direction);
                                            } else {
                                                if (!isMaxMovesDepthReached(playerPositions.movesDepth)) {
                                                    openQueueForward.add(addedBoardPositionIndex);
                                                }
                                            }
                                        }
                                    }
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition); // Undo push -> reuse box configuration for next push.
                                }
                            } else {
                                playerPositions.addIfNew(newPlayerPosition);   // No wall, no box => the player can move to the new position.
                            }
                        }
                    }
                }
            }

            private boolean isMaxMovesDepthReached(int movesDepth) {

                // If a new moves depth has been reached then:
                // - discard the board position when the depth is higher than the current best solution moves count
                // - inform the user about this new depth and the currently best found metrics
                int currentDepth = movesDepthForwardSearch.get();

                if (movesDepth > currentDepth) {
                    // Note: this isn't completely thread safe:
                    // - a slow thread may still be adding board positions having less moves, hence no "break" here.
                    // - in a rare case both directions may get a "false" be checking the depth and then both increase
                    //   the total depth to a higher value than "newBestSolutionMovesCount". This is no problem.
                    if (movesDepth > newBestSolutionMovesCount) {
                        return true;
                    }
                    if (movesDepthForwardSearch.compareAndSet(currentDepth, movesDepth)) {
                        checkForImprovement();
                    }
                }

                return false;
            }

            /**
             * This method informs the user whether a better path to any of the board positions of the solution to be optimized
             * has been found and about the current moves depth that the search has reached.
             */
            private void checkForImprovement() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Identify the board position having the highest number of pushes that has already been reached by
                // the forward search and is part of the basis solution to be optimized and check for any improvements.
                // Ignore the last board position because there are levels that start with all boxes on goals.
                // For these levels start and end board position are the same.
                for (int index = boardPositionsSolutionToBeOptimized.size() - 1; --index != -1; ) {

                    BoardPosition basisSolutionBoardPosition = boardPositionsSolutionToBeOptimized.get(index);

                    // Check whether the search has already reached the board position.
                    long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(basisSolutionBoardPosition, SearchDirection.FORWARD);
                    if (boardPositionIndex != BoardPositionsStorage.NONE) {

                        boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);

                        // Calculate how much better the board position has been reached compared to the basis solution.
                        int movesDif = basisSolutionBoardPosition.moves - boardPosition.primaryMetric;
                        int pushesDif = basisSolutionBoardPosition.pushes - boardPosition.secondaryMetric;

                        synchronized (mainOptimizerThread) {
                            // Note: since we search for HIGHEST pushes, lower pushesDif is better!
                            if (movesDif > movesImprovementForwardSearch ||
                                    movesDif == movesImprovementForwardSearch && pushesDif < pushesImprovementForwardSearch) {
                                movesImprovementForwardSearch = movesDif;
                                pushesImprovementForwardSearch = pushesDif;

                                if (immediateNewIterationWhenImprovement && optimizerStatus == RUNNING) {
                                    optimizerStatus = OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION;
                                }
                            }

                            // Save the last visited board position of the original solution if it has been reached on the currently best known path.
                            if (pushesImprovementForwardSearch == pushesDif && movesImprovementForwardSearch == movesDif) {
                                lastVisitedBoardPositionOfOriginalSolutionForward = basisSolutionBoardPosition;
                            }
                        }

                        break;
                    }
                }

                displayIntermediateResult();
            }
        }

        /**
         * Calculates and displays the current moves depth of the search and the current best solution metrics.
         */
        private void displayIntermediateResult() {

            synchronized (mainOptimizerThread) {

                int newMovesCount = solutionToBeOptimized.movesCount - movesImprovementForwardSearch;
                int newPushesCount = solutionToBeOptimized.pushesCount - pushesImprovementForwardSearch;

                // Set the new metrics when the new best solution has been found - higher pushes are better for this search!
                if (newMovesCount < newBestSolutionMovesCount || newMovesCount == newBestSolutionMovesCount && newPushesCount > newBestSolutionPushesCount) {
                    newBestSolutionMovesCount = newMovesCount;
                    newBestSolutionPushesCount = newPushesCount;
                }

                int movesDepth = prefixMovesCount + movesDepthForwardSearch.get() + postfixMovesCount;
                int bestSolutionMovesCount = prefixMovesCount + newBestSolutionMovesCount + postfixMovesCount;
                int bestSolutionPushesCount = prefixPushesCount + newBestSolutionPushesCount + postfixPushesCount;

                // Display a status message to show the current depth and the current found improvement.
                optimizerGUI.setInfoText(
                        Texts.getText("vicinitySearchMovesDepth") + movesDepth +
                                "    " +
                                Texts.getText("currentBestSolutionMovesPushes") + bestSolutionMovesCount + "/" + bestSolutionPushesCount +
                                " (" + (-movesImprovementForwardSearch) + "/" + (-pushesImprovementForwardSearch) + ")"
                );
            }
        }

        /**
         * Checks whether the solution found by the search is a new best solution.
         * If yes, then the data of this new solution is saved.
         *
         * @param movesDepth            number of moves of the search direction that has found the solution
         * @param pushesDepth           number of pushes of the search direction that has found the solution
         * @param boxConfigurationIndex index of the box configuration
         * @param playerPosition        player position of the board position
         * @param lastMoveDirection     direction of the last move
         */
        private void collectBestSolutions(
                final int movesDepth,
                final int pushesDepth,
                final int boxConfigurationIndex,
                final int playerPosition,
                final int lastMoveDirection) {

            // Check if the new solution is at least as good as the current best one. Synchronized because the metrics of
            // the current best solution may otherwise change in the meantime.
            synchronized (mainOptimizerThread) {
                if (movesDepth < newBestSolutionMovesCount ||
                        movesDepth == newBestSolutionMovesCount && pushesDepth >= newBestSolutionPushesCount) { // >= since higher pushes are better for this search!

                    // Only solutions having the best metrics have to be reconstructed. If this solution is the new best, then the
                    // other solutions (represented by the meeting point) can be deleted.
                    if (movesDepth < newBestSolutionMovesCount || pushesDepth > newBestSolutionPushesCount) { // we search for higher pushes!
                        solutionReconstruction.removeAllEndBoardPositions();

                        newBestSolutionMovesCount = movesDepth;
                        newBestSolutionPushesCount = pushesDepth;

                        movesImprovementForwardSearch = solutionToBeOptimized.movesCount - newBestSolutionMovesCount;
                        pushesImprovementForwardSearch = solutionToBeOptimized.pushesCount - newBestSolutionPushesCount;

                        displayIntermediateResult();
                    }

                    solutionReconstruction.addEndBoardPosition(boxConfigurationIndex, playerPosition, lastMoveDirection);

                    if (Debug.isDebugModeActivated) {
                        optimizerGUI.addLogTextDebug("New best solution found: M/P: " + (newBestSolutionMovesCount) + "/" + (newBestSolutionPushesCount));
                    }
                }
            }
        }

        /**
         * The main optimizer search stores all board positions in the storage.
         * This class does a new search over the stored board positions to reconstruct
         * the best solution regarding first moves and then MOST pushes.
         */
        protected class SolutionReconstructionMovesHighestPushes {

            private final Set<EndBoardPositionData> endBoardPositionSet = ConcurrentHashMap.newKeySet();

            private final AtomicReference<OptimizerSolution> bestFoundSolution = new AtomicReference<>();
            private final Comparator<BoardPosition> COMPARATOR;

            /**
             * Creates a reconstruction object used to search the best solution by using only the
             * board positions generated by the optimizer vicinity search.<br>
             */
            public SolutionReconstructionMovesHighestPushes() {
                COMPARATOR = (boardPosition1, boardPosition2) -> {
                    if (boardPosition1 == null && boardPosition2 == null) {
                        return 0;
                    }
                    if (boardPosition1 == null) {
                        return +1;
                    }
                    if (boardPosition2 == null) {
                        return -1;
                    }
                    return Utilities.intComparePairs(boardPosition1.moves, boardPosition2.moves,
                            -boardPosition1.pushes, -boardPosition2.pushes); // highest pushes!
                };
            }

            /**
             * Stores an end board position.
             *
             * @param boxConfigurationIndex the index of the box configuration where the searches have met
             * @param playerPosition        player position of the board position where the searches have met
             * @param moveDirection         direction of the last move
             */
            public void addEndBoardPosition(int boxConfigurationIndex, int playerPosition, int moveDirection) {
                EndBoardPositionData meetingData = new EndBoardPositionData(boxConfigurationIndex, playerPosition, moveDirection);
                endBoardPositionSet.add(meetingData);
            }

            /**
             * Deletes all stored meeting points.
             */
            public void removeAllEndBoardPositions() {
                endBoardPositionSet.clear();
            }

            /**
             * Determines the best solution that can be constructed using the board positions
             * that have been stored during the vicinity search.
             *
             * @return the best found solution
             */
            public OptimizerSolution getBestSolution() {

                // Backup the optimizer status since it may be set back to running.
                OptimizerStatus optimizerStatusBackup = optimizerStatus;

                // When the optimizer search couldn't finish normally we can nevertheless check whether the search has already found
                // a better solution. Hence, the optimizer is set back to "running" for the reconstruction.
                if (optimizerStatus == OptimizerStatus.STOPPED_BY_USER || optimizerStatus == OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY) {
                    optimizerStatus = RUNNING;
                }

                // If the optimizer hasn't found an end board position (user stopped the search, out of memory error, ...),
                // the solution is reconstructed by filling the gap between the last found board position and the end board position
                // with board positions of the original solution. Since in this case there is always just one solution only one thread needs to do the work.
                if (endBoardPositionSet.isEmpty()) {

                    // The board positions of the new best found solution.
                    ArrayList<BoardPosition> newSolutionBoardPositions = new ArrayList<>();

                    // Add all board positions of the solution which the search has found. Sanity check: in the rare case
                    // that the search stops before the first board position of the original solution has been reached there is nothing to reconstruct.
                    if (lastVisitedBoardPositionOfOriginalSolutionForward != null) {
                        newSolutionBoardPositions = backwardReconstruction(
                                new EndBoardPositionData(lastVisitedBoardPositionOfOriginalSolutionForward.boxConfigurationIndex,
                                        lastVisitedBoardPositionOfOriginalSolutionForward.playerPosition,
                                        getMoveDirection(lastVisitedBoardPositionOfOriginalSolutionForward)));
                    }

                    // If the forward search hasn't reached any board position of the basis solution we have to fill the gap
                    // beginning by the very first board position of the basis solution we have => set the flag to true.
                    boolean forwardBoardPositionReached = lastVisitedBoardPositionOfOriginalSolutionForward == null;
                    for (BoardPosition basisSolutionBoardPosition : boardPositionsSolutionToBeOptimized) {

                        // The first relevant board position is the one after the last visited board position of the search.
                        if (!forwardBoardPositionReached && basisSolutionBoardPosition.equals(lastVisitedBoardPositionOfOriginalSolutionForward)) {
                            forwardBoardPositionReached = true;
                            continue;
                        }

                        // Add board positions that are missing for a complete solution.
                        if (forwardBoardPositionReached) {
                            BoardPosition boardPosition = new BoardPosition(0, 0, 0, 0, 0, basisSolutionBoardPosition.boxConfigurationIndex, basisSolutionBoardPosition.playerPosition);
                            newSolutionBoardPositions.add(boardPosition);
                        }

                    }

                    // Create the new solution and set it as best new found solution.
                    OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);
                    bestFoundSolution.set(newSolution);
                } else {
                    reconstructFoundSolutions();
                }

                // Set back the old optimizer status if the optimizer hasn't been stopped.
                if (optimizerStatus == RUNNING) {
                    optimizerStatus = optimizerStatusBackup;
                }

                return bestFoundSolution.get();
            }

            /**
             * Returns the direction of the move performed to reach the passed board position.
             *
             * @param boardPosition the board position
             * @return the direction of the move
             */
            private int getMoveDirection(BoardPosition boardPosition) {

                int axisOfMove = boardPosition.playerPosition & 1;
                int moveDirection = 0;
                for (; moveDirection < 4; moveDirection++) {
                    int playerNeighbor, boxPosition;
                    if (getAxisOfDirection(moveDirection) == axisOfMove
                            && (playerNeighbor = playerSquareNeighbor[moveDirection][boardPosition.playerPosition]) != NONE
                            && (boxPosition = playerPositionToBoxPosition[playerNeighbor]) != NONE
                            && boxConfigurationStorage.isBoxAtPosition(boardPosition.boxConfigurationIndex, boxPosition)) {
                        return moveDirection;
                    }
                }

                return NONE;
            }

            /**
             * Reconstructs the solutions from the found end board positions.
             */
            private void reconstructFoundSolutions() {

                final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
                final ConcurrentLinkedQueue<EndBoardPositionData> meetingBoardPositions = new ConcurrentLinkedQueue<>(endBoardPositionSet);

                final Runnable reconstruction = () -> {
                    EndBoardPositionData meetingData = null;

                    // Reconstruct and check solutions until all data have been processed.
                    while ((meetingData = meetingBoardPositions.poll()) != null && optimizerStatus == RUNNING) {

                        long time = System.currentTimeMillis();

                        ArrayList<BoardPosition> newSolutionBoardPositions = backwardReconstruction(meetingData);

                        // If the optimizer has been stopped not all board positions have been reconstructed and therefore the solution isn't valid.
                        if (optimizerStatus == RUNNING) {

                            OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);

                            // Check whether this is a new best solution.
                            OptimizerSolution currentBestSolution = null;
                            do {
                                currentBestSolution = bestFoundSolution.get();
                            } while (newSolution.isBetterMovesHighestPushesThan(currentBestSolution) && !bestFoundSolution.compareAndSet(currentBestSolution, newSolution));

                            if (Debug.isDebugModeActivated) {
                                optimizerGUI.addLogTextDebug("Solution reconstruction time: " + (System.currentTimeMillis() - time) + "  Solution: " + newSolution);
                            }
                        }
                    }
                };

                for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                    executor.execute(reconstruction);
                }
                Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            }

            /**
             * Get the moves value from the passed board position.
             *
             * @param boardPosition board position to extract the number of moves
             * @return the number of moves
             */
            private int getMoves(StorageBoardPosition boardPosition) {
                return boardPosition.primaryMetric;
            }

            /**
             * Get the pushes value from the passed board position.
             *
             * @param boardPosition board position to extract the number of pushes from
             * @return the number of pushes
             */
            private int getPushes(StorageBoardPosition boardPosition) {
                return boardPosition.secondaryMetric;
            }


            /**
             * A backward search for reconstructing the best solution by using the
             * saved board positions from the forward search.
             * <p>
             * The forward search has saved the reached board positions and its
             * metrics in the board position storage. The end board position of
             * the forward search is used to reconstruct the solution path.
             *
             * @param endBoardPositionData data of an end board position
             * @return {@code OptimizerBoardPosition}s of the found path to the start board position
             */
            private ArrayList<BoardPosition> backwardReconstruction(EndBoardPositionData endBoardPositionData) {

                final HashMap<BoardPositionWithPushDirection, BoardPositionWithPushDirection> storage = new HashMap<>();
                final PriorityQueue<BoardPositionWithPushDirection> openQueue = new PriorityQueue<>(10000, COMPARATOR);

                // Add the board position as start for the search.
                int pullDirection = getOppositeDirection(endBoardPositionData.lastMoveDirection);    // we like to pull instead of push
                final BoardPositionWithPushDirection startBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, endBoardPositionData.boxConfigurationIndex, endBoardPositionData.playerPosition, pullDirection, null);
                storage.put(startBoardPosition, startBoardPosition);
                openQueue.add(startBoardPosition);

                // Set the moves and pushes of the board position this search starts with.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(endBoardPositionData.boxConfigurationIndex, endBoardPositionData.playerPosition, SearchDirection.FORWARD);
                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                int startBoardPositionMoves = getMoves(boardPosition);
                int startBoardPositionPushes = getPushes(boardPosition);

                // Last board position of the best found path to the initial board position of the level.
                BoardPositionWithPushDirection bestEndBoardPosition = null;

                if (startBoardPositionPushes <= 1) {
                    return new ArrayList<>(Collections.singletonList(startBoardPosition));
                }

                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    BoardPositionWithPushDirection currentBoardPosition = openQueue.poll();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;

                    int currentBoardPositionPlayerPosition = currentBoardPosition.playerPosition;

                    // Add the start player position and set the current moves depth.
                    playerPositions.addFirst(currentBoardPositionPlayerPosition, 0);

                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // Try to move the player to every direction and check whether it's possible to do pulls.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall or a box.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                    (boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {
                                continue;
                            }

                            // The player can move to the new position => store the new position in the queue (if it hasn't been reached, yet).
                            playerPositions.addIfNew(newPlayerPosition);

                            // Check whether there is a box which can be pulled by moving to the new player position. If not, continue with the next move.
                            if ((playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]) == NONE ||  // position next to the player where a box might be
                                    (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                       // position of a possible box to be pulled
                                    !boxConfiguration.isBoxAtPosition(boxPosition) ||                                                     // there is a box next to the player
                                    (newBoxPosition = playerPositionToBoxPosition[playerPosition]) == NONE) {                                     // valid box position
                                continue;
                            }

                            // Pull is possible => do the pull.
                            boxConfiguration.moveBox(boxPosition, newBoxPosition);

                            int boxConfigurationIndex;

                            // The box configuration must be one of the board positions found during the vicinity search.
                            if ((boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration)) != NONE &&
                                    (boardPositionIndex = boardPositionStorage.getBoardPositionIndex(currentBoardPosition.boxConfigurationIndex, playerPosition, SearchDirection.FORWARD)) != BoardPositionsStorage.NONE) {

                                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                                int forwardMoves = getMoves(boardPosition);
                                int forwardPushes = getPushes(boardPosition);

                                // Calculate the moves and pushes of the board position directly BEFORE the pull has been made!
                                // This must be done to compare them with the stored board positions of the forward search,
                                // which has stored the board positions AFTER a push has been made.
                                int newBoardPositionMoves = currentBoardPosition.moves + playerPositions.movesDepth;
                                int newBoardPositionPushes = currentBoardPosition.pushes;

                                // Check if this board position is on the best found path (-> having the correct metrics).
                                if (forwardMoves == startBoardPositionMoves - newBoardPositionMoves &&
                                        forwardPushes == startBoardPositionPushes - newBoardPositionPushes) {

                                    /* Calculate the new metrics of the board position, that is: AFTER the pull. */
                                    newBoardPositionMoves++;
                                    newBoardPositionPushes++;

                                    // Create a new board position to be added to the open queue.
                                    BoardPositionWithPushDirection newBoardPosition = new BoardPositionWithPushDirection(
                                            newBoardPositionMoves, newBoardPositionPushes, 0, 0, 0,
                                            boxConfigurationIndex, newPlayerPosition, direction, currentBoardPosition);

                                    // Backward can't always reach the start board position because the player needn't to be
                                    // next to a box in the start board position. Hence, last board position to search for
                                    // is one having one push. The initial board position (having 0 pushes) is already known
                                    // in advance, hence it needn't to be in the reconstructed path.
                                    if (forwardPushes == 1) {

                                        // Check whether the new board position has been reached better than the current
                                        // best end board position. If yes, then set the new board position as new best one.
                                        if (COMPARATOR.compare(newBoardPosition, bestEndBoardPosition) < 0) {
                                            bestEndBoardPosition = newBoardPosition;
                                        }
                                    } else {
                                        BoardPositionWithPushDirection duplicateBoardPosition = storage.get(newBoardPosition);

                                        // If the new board position hasn't been reached before or it has been reached
                                        // better then store it and add it to the open queue.
                                        if (duplicateBoardPosition == null || COMPARATOR.compare(newBoardPosition, duplicateBoardPosition) < 0) {
                                            storage.put(newBoardPosition, newBoardPosition); // Replace the old with the new board position.
                                            openQueue.add(newBoardPosition);                 // Add board position for further searching.
                                        }
                                    }
                                }
                            }
                            boxConfiguration.moveBox(newBoxPosition, boxPosition);             // Undo for reusing the box configuration for the next pull.

                        } // Loop that continues moving the player to all 4 directions until all positions have been visited
                    } // Loop that moves the player to all reachable positions
                }

                // Reconstruct the found path and return an array list of the found board positions.
                ArrayList<BoardPosition> foundPath = new ArrayList<>();

                for (BoardPositionWithPushDirection currentBoardPosition = bestEndBoardPosition;
                     currentBoardPosition != null && currentBoardPosition.previous != null; currentBoardPosition = currentBoardPosition.previous) {

                    // Note: The search saved the board positions AFTER the pull! However,
                    // we need a forward path, which means the board positions after
                    // a push has been done - which on the other hand means BEFORE the pull.

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);
                    int newPlayerPosition = playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPosition.playerPosition];
                    int boxPosition = playerPositionToBoxPosition[newPlayerPosition];
                    int newBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][newPlayerPosition]];
                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                    BoardPosition newBoardPosition = new BoardPosition(0, 0, 0, 0, 0, boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration), newPlayerPosition);

                    foundPath.add(newBoardPosition);
                }

                return foundPath;
            }
        }
    }

    /**
     * Class for searching for a solution by only using the generated box configurations.<br>
     * The new solution is optimized for: <br>
     * 1. box changes<br>
     * 2. pushes<br>
     * Before this method is called the box configurations to be used must have been generated.
     */
    private class BoxChangesPushesOptimizer extends AllMetricsOptimizer {

        private final AtomicInteger boxChangesDepthSearch = new AtomicInteger(-1);

        protected volatile int newBestSolutionBoxChangesCount = -1;
        protected int boxChangesImprovement = 0;
        private final SolutionReconstructionBoxChangesPushes solutionReconstruction = new SolutionReconstructionBoxChangesPushes();

        LevelSolvedCheck levelSolvedCheck;

        /**
         * Optimizes the passed solution by only using the generated box configurations.<br>
         * The path is optimal regarding: <br>
         * 1. box changes<br>
         * 2. pushes<br>
         *
         * @param initialBoard        the board at level start
         * @param endBoard            the end board having all boxes on a goal
         * @param currentBestSolution the currently best solution
         * @return the best found solution
         */
        public OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentBestSolution) {
            // Used to store the real positions of the boxes of a box configuration.
            // Usually just the index of a box configuration in the global array
            // is used, but for doing pushes, it's necessary to copy the real positions in this byte array.
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Get the index of the initial box configuration.
            packBoxConfiguration(boxConfiguration, initialBoard);
            startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);


            levelSolvedCheck = new LevelSolvedCheck(endBoard);


            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            // Get a list of all board positions of the solution.
            boardPositionsSolutionToBeOptimized = getSolutionBoardPositions(currentBestSolution);

            // Yet, no solution has been found. However, the current best solution is used as upper bound for the box changes and pushes.
            BoardPosition endBoardPosition = boardPositionsSolutionToBeOptimized.get(boardPositionsSolutionToBeOptimized.size() - 1);
            newBestSolutionBoxChangesCount = endBoardPosition.boxChanges;
            newBestSolutionPushesCount = endBoardPosition.pushes;

            currentBestSolution.movesCount = endBoardPosition.moves;
            currentBestSolution.pushesCount = endBoardPosition.pushes;
            currentBestSolution.boxChanges = endBoardPosition.boxChanges;
            currentBestSolution.boxLines = endBoardPosition.boxLines;
            currentBestSolution.pushingSessions = endBoardPosition.pushingSessions;

            this.solutionToBeOptimized = currentBestSolution;

            // The order value is calculated:
            // boxChangesCount * (estimated maximum number of pushes+1) + pushesCount
            // We estimate the new solution not having more than 10 times the number of pushes than the solution to be optimized.
            final int estimatedMaximumNewPushesCount = 10 * currentBestSolution.pushesCount;
            final int maximumOrderValueChange = 1 * (estimatedMaximumNewPushesCount + 1) + 1; // 1 box change  and 1 push
            final int minimumOrderValueChange = 1;                                             // 0 box changes and 1 push

            boardPositionStorage = new BoardPositionsStorage(boxConfigurationStorage.getSize(), playerSquaresCount, 2, estimatedMaximumNewPushesCount, estimatedMaximumNewPushesCount);

            openQueueForward = PriorityQueueOptimizer.getInstance(minimumOrderValueChange, maximumOrderValueChange, boardPositionStorage, maxCPUsToBeUsedCount);


            // Add the start board position for the forward search to the open queue
            // and store it in the reachedPositionsStorage for detecting duplicates.
            int internalPlayerStartPosition = playerExternalToInternalPosition[initialBoard.playerPosition];

            // If we don't optimize a pushes range there is no prefix push. In this case we can take any axis as start,
            // because the first push is counted as new box change/box line anyway.
            int firstPushAxis = prefixAxisOfLastPush == NONE ? AXIS_HORIZONTAL : prefixAxisOfLastPush;

            long indexStartBoardPosition = boardPositionStorage.addIfBetter(0, 0, startBoxConfigurationIndex, (internalPlayerStartPosition << 1) | firstPushAxis, SearchDirection.FORWARD);
            openQueueForward.add(indexStartBoardPosition);

            // Execute the optimizer searches.
            final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
            for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                executor.execute(new BoxChangesPushesForwardSearch(threadNo));
            }

            Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            if (Debug.isDebugModeActivated) {
                debugDisplayStatistic();
            }

            // Inform the user about the RAM that is available after the search has ended.
            optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMinMiB()));

            // The optimizer has only been stopped to start a new iteration with the new best found solution.
            // Hence, the optimizer is still running.
            if (optimizerStatus == OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION) {
                optimizerStatus = RUNNING;
            }

            // Return the best found solution (or null if no solution has been found, due
            // to stop of the optimizer by user, out of memory, ...)
            return getBestFoundSolution();
        }

        @Override
        public OptimizerSolution getBestFoundSolution() {
            synchronized (mainOptimizerThread) {
                return solutionReconstruction.getBestSolution();
            }
        }

        /**
         * A forward search considering first box changes, second pushes.
         */
        private class BoxChangesPushesForwardSearch implements Runnable {

            /**
             * Unique ID for every thread for accessing the priority queue.
             */
            private final int priorityQueueThreadID;

            /**
             * Creates a {@code Runnable} that performs a forward search for the optimizer.
             *
             * @param priorityQueueThreadID unique ID for the priority queue
             */
            public BoxChangesPushesForwardSearch(int priorityQueueThreadID) {
                this.priorityQueueThreadID = priorityQueueThreadID;
            }

            /**
             * Starts a new forward search.
             */
            @Override
            public void run() {

                try {
                    forwardSearch();
                } catch (OutOfMemoryError e) {
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }

            /**
             * Forward search searching from the start board position forward
             * (-> pushing boxes) until it meets the backward search.
             */
            private void forwardSearch() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    long currentBoardPositionIndex = openQueueForward.removeFirst(priorityQueueThreadID);
                    if (currentBoardPositionIndex == NONE) {
                        break;
                    }

                    // Mark as processed - BEFORE the real metrics are read. This ensures that when
                    // the board position is reached better in the meantime the new better values are read.
                    boardPositionStorage.markAsProcessed(currentBoardPositionIndex);
                    boardPositionStorage.fillBoardPosition(currentBoardPositionIndex, boardPosition);

                    int lastPushedBoxPosition = NONE;
                    if (boardPosition.secondaryMetric == 0 && prefixAxisOfLastPush == NONE) {  // Start board position and no pushes range optimization
                        lastPushedBoxPosition = NONE;
                    } else {
                        int pushDirection = getPushDirection(boardPosition);
                        int playerNeighbor = playerSquareNeighbor[pushDirection][boardPosition.playerPosition >>> 1];
                        lastPushedBoxPosition = playerPositionToBoxPosition[playerNeighbor];
                    }

                    int axis = boardPosition.playerPosition & 1; // Axis is stored in first bit
                    boardPosition.playerPosition >>>= 1;         // Calculate the real player position (axis is stored in first bit)

                    int boxChangesDepth = boardPosition.primaryMetric;
                    int pushesDepth = boardPosition.secondaryMetric + 1; // one push is done in every case
                    int currentBoxConfigurationIndex = boardPosition.boxConfigurationIndex;

                    int currentDepth = boxChangesDepthSearch.get();
                    if (boxChangesDepth > currentDepth) {
                        if (boxChangesDepth > newBestSolutionBoxChangesCount) {
                            continue;
                        }
                        if (boxChangesDepthSearch.compareAndSet(currentDepth, boxChangesDepth)) {
                            checkForImprovement();
                        }
                    }

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                    playerPositions.addFirst(boardPosition.playerPosition, 0);

                    // Breadth First Search over all reachable player positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Is the neighbor position of the box no deadlock square and not blocked by another box?
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        // The first push is always considered a box change unless we optimize a pushesRange, in which case there is a previous push
                                        // whose axis has been passed to the optimizer.
                                        int newBoxChanges = boardPosition.primaryMetric;
                                        if ((pushesDepth == 1 && prefixAxisOfLastPush == NONE) || boxPosition != lastPushedBoxPosition) {
                                            newBoxChanges++;
                                        }
                                        int newPlayerPositionWithAxis = (newPlayerPosition << 1) | getAxisOfDirection(direction); // player position is shifted to store the axis, too

                                        long addedBoardPositionIndex = boardPositionStorage.addIfBetter(newBoxChanges, pushesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, SearchDirection.FORWARD);
                                        if (addedBoardPositionIndex != BoardPositionsStorage.NONE) {
                                            if (levelSolvedCheck.isTargetBoardPosition(boxConfigurationIndex, newPlayerPosition)) {
                                                collectBestSolutions(newBoxChanges, pushesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, direction);
                                            } else {
                                                openQueueForward.add(addedBoardPositionIndex);
                                            }
                                        }
                                    }
                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }
            }

            /**
             * This method informs the user whether a better path to any of the board positions of the solution to be optimized
             * has been found and about the current box changes depth that the search has reached.
             */
            private void checkForImprovement() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Identify the board position having the highest number of pushes that has already been reached by
                // the forward search and is part of the basis solution to be optimized and check for any improvements.
                // Ignore the last board position because there are levels which start with all boxes on goals.
                // For these levels start and end board position are the same.
                for (int index = boardPositionsSolutionToBeOptimized.size() - 1; --index != -1; ) {

                    BoardPosition basisSolutionBoardPosition = boardPositionsSolutionToBeOptimized.get(index);

                    // Check whether the board position has already been reached by the forward search.
                    long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(basisSolutionBoardPosition, SearchDirection.FORWARD);
                    if (boardPositionIndex != BoardPositionsStorage.NONE) {

                        boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);

                        // Calculate how much better the board position has been reached compared to the basis solution.
                        int boxChangesDif = basisSolutionBoardPosition.boxChanges - boardPosition.primaryMetric;
                        int pushesDif = basisSolutionBoardPosition.pushes - boardPosition.secondaryMetric;

                        synchronized (mainOptimizerThread) {
                            if (boxChangesDif > boxChangesImprovement ||
                                    boxChangesDif == boxChangesImprovement && pushesDif > pushesImprovementForwardSearch) {
                                boxChangesImprovement = boxChangesDif;
                                pushesImprovementForwardSearch = pushesDif;

                                if (immediateNewIterationWhenImprovement && optimizerStatus == RUNNING) {
                                    optimizerStatus = OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION;
                                }
                            }

                            // Save the last visited board position of the original solution if it has been reached on the currently best known path.
                            if (boxChangesImprovement == boxChangesDif && pushesImprovementForwardSearch == pushesDif) {
                                lastVisitedBoardPositionOfOriginalSolutionForward = basisSolutionBoardPosition;
                            }
                        }

                        break;
                    }
                }

                displayIntermediateResult();
            }
        }

        /**
         * Returns a list of all board positions the passed solution
         * consists of. The board positions contain information
         * about with which metrics they have in the solution (pushes, moves,...)
         *
         * @param currentBestSolution solution whose board positions are to be returned
         * @return the board positions of the passed solution
         */
        @Override
        protected ArrayList<BoardPosition> getSolutionBoardPositions(final OptimizerSolution currentBestSolution) {

            return getBoardPositions(initialBoard, currentBestSolution.solution, prefixAxisOfLastPush)
                    .map(bp -> {
                        int playerPositionWithAxis = (bp.playerPosition << 1) | getAxisOfDirection(bp.pushDirection);
                        return new BoardPosition(bp.moves, bp.pushes, bp.boxChanges, bp.boxChanges, bp.pushingSessions, bp.boxConfigurationIndex, playerPositionWithAxis);
                    }).collect(toCollection(ArrayList::new));
        }

        /**
         * Calculates and displays the current box changes depth of the search and the current best solution metrics.
         */
        private void displayIntermediateResult() {

            synchronized (mainOptimizerThread) {

                int newBoxChangesCount = solutionToBeOptimized.boxChanges - boxChangesImprovement;
                int newPushesCount = solutionToBeOptimized.pushesCount - pushesImprovementForwardSearch;

                // Set the new metrics when a better solution has been found.
                if (newBoxChangesCount < newBestSolutionBoxChangesCount || newBoxChangesCount == newBestSolutionBoxChangesCount && newPushesCount < newBestSolutionPushesCount) {
                    newBestSolutionBoxChangesCount = newBoxChangesCount;
                    newBestSolutionPushesCount = newPushesCount;
                }

                // Display a status message to show the current depth and the current found improvement.
                // If a only a specific pushes range of the whole solution is optimized,
                // only the metrics of this pushes range are displayed (without the prefix and suffix moves
                // that must be added to get the whole solution).
                optimizerGUI.setInfoText(
                        Texts.getText("vicinitySearchBoxChangesDepth")
                                + boxChangesDepthSearch.get()
                                + "    "
                                + Texts.getText("bestSolution") + ": "
                                + newBestSolutionBoxChangesCount
                                + "/"
                                + newBestSolutionPushesCount
                                + " (" + (-boxChangesImprovement) + "/" + (-pushesImprovementForwardSearch) + ")"
                );
            }
        }


        /**
         * Checks whether the search has found a new best solution. If yes, then the data of this new solution is saved.
         *
         * @param boxChangesDepth       number of box changes of the search direction that has found the solution
         * @param pushesDepth           number of pushes of the search direction that has found the solution
         * @param boxConfigurationIndex index of the box configuration
         * @param playerPosition        player position of the board position
         * @param lastMoveDirection     direction of the last move
         */
        private void collectBestSolutions(
                final int boxChangesDepth,
                final int pushesDepth,
                final int boxConfigurationIndex,
                final int playerPosition,
                final int lastMoveDirection) {

            // Check if the new solution is at least as good as the current best one. Synchronized because the metrics of
            // the current best solution may otherwise change in the meantime.
            synchronized (mainOptimizerThread) {
                if (boxChangesDepth < newBestSolutionBoxChangesCount ||
                        boxChangesDepth == newBestSolutionBoxChangesCount && pushesDepth <= newBestSolutionPushesCount) {

                    // Only solutions having the best metrics have to be reconstructed. If this solution is a new best one then the
                    // other solutions (represented by the meeting point) can be deleted.
                    if (boxChangesDepth < newBestSolutionBoxChangesCount || pushesDepth < newBestSolutionPushesCount) {
                        solutionReconstruction.removeAllEndBoardPositions();

                        newBestSolutionBoxChangesCount = boxChangesDepth;
                        newBestSolutionPushesCount = pushesDepth;

                        boxChangesImprovement = solutionToBeOptimized.boxChanges - newBestSolutionBoxChangesCount;
                        pushesImprovementForwardSearch = solutionToBeOptimized.pushesCount - newBestSolutionPushesCount;

                        displayIntermediateResult();
                    }

                    solutionReconstruction.addEndBoardPosition(boxConfigurationIndex, playerPosition, lastMoveDirection);

                    if (Debug.isDebugModeActivated) {
                        optimizerGUI.addLogTextDebug("New best solution found: boxChanges/Pushes: " + (newBestSolutionBoxChangesCount) + "/" + (newBestSolutionPushesCount));
                    }
                }
            }
        }

        /**
         * Returns the direction of the move performed to reach the passed board position.
         *
         * @param boardPosition the board position
         * @return the direction of the move
         */
        private int getMoveDirection(BoardPosition boardPosition) {

            int axisOfMove = boardPosition.playerPosition & 1;
            int moveDirection = 0;
            for (; moveDirection < 4; moveDirection++) {
                int playerNeighbor, boxPosition;
                if (getAxisOfDirection(moveDirection) == axisOfMove
                        && (playerNeighbor = playerSquareNeighbor[moveDirection][boardPosition.playerPosition >>> 1]) != NONE
                        && (boxPosition = playerPositionToBoxPosition[playerNeighbor]) != NONE
                        && boxConfigurationStorage.isBoxAtPosition(boardPosition.boxConfigurationIndex, boxPosition)) {
                    return moveDirection;
                }
            }

            return NONE;
        }

        /**
         * Returns the direction of the push performed to reach the passed board position.
         *
         * @param boardPosition the board position
         * @return the direction of the push
         */
        private int getPushDirection(StorageBoardPosition boardPosition) {

            int axisOfMove = boardPosition.playerPosition & 1;
            int playerPosition = boardPosition.playerPosition >>> 1;

            for (int pushDirection = 0; pushDirection < 4; pushDirection++) {
                int playerNeighbor, boxPosition;
                if (getAxisOfDirection(pushDirection) == axisOfMove
                        && (playerNeighbor = playerSquareNeighbor[pushDirection][playerPosition]) != NONE
                        && (boxPosition = playerPositionToBoxPosition[playerNeighbor]) != NONE
                        && boxConfigurationStorage.isBoxAtPosition(boardPosition.boxConfigurationIndex, boxPosition)) {
                    return pushDirection;
                }
            }

            return NONE;
        }

        /**
         * The main optimizer search stores all board positions in the storage.
         * This class does a new search over the stored board positions in order to reconstruct the best solution
         * regarding first box changes and second pushes.
         */
        protected class SolutionReconstructionBoxChangesPushes {

            private final Set<EndBoardPositionData> endBoardPositionSet = ConcurrentHashMap.newKeySet();

            private final AtomicReference<OptimizerSolution> bestFoundSolution = new AtomicReference<>();
            private final Comparator<BoardPosition> COMPARATOR;


            /**
             * Creates a reconstruction object used to search the best solution by using only the
             * board positions generated by the optimizer vicinity search.<br>
             * The search considers all 5 metrics. Which solution is the best is determined by
             * the passed comparator.
             */
            public SolutionReconstructionBoxChangesPushes() {
                COMPARATOR = (boardPosition1, boardPosition2) -> {
                    if (boardPosition1 == null && boardPosition2 == null) {
                        return 0;
                    }
                    if (boardPosition1 == null) {
                        return +1;
                    }
                    if (boardPosition2 == null) {
                        return -1;
                    }
                    return Utilities.intComparePairs(boardPosition1.boxChanges, boardPosition2.boxChanges,
                            boardPosition1.pushes, boardPosition2.pushes);
                };
            }

            /**
             * Stores an end board position.
             *
             * @param boxConfigurationIndex the index of the box configuration where the searches have met
             * @param playerPosition        player position of the board position where the searches have met
             * @param moveDirection         direction of the last move
             */
            public void addEndBoardPosition(int boxConfigurationIndex, int playerPosition, int moveDirection) {
                EndBoardPositionData meetingData = new EndBoardPositionData(boxConfigurationIndex, playerPosition, moveDirection);
                endBoardPositionSet.add(meetingData);
            }

            /**
             * Deletes all stored meeting points.
             */
            public void removeAllEndBoardPositions() {
                endBoardPositionSet.clear();
            }

            /**
             * Determines the best solution that can be constructed using the board positions
             * that have been stored during the vicinity search.
             *
             * @return the best found solution
             */
            public OptimizerSolution getBestSolution() {

                // Backup the optimizer status since it may be set back to running.
                OptimizerStatus optimizerStatusBackup = optimizerStatus;

                // When the optimizer search couldn't finish normally we can nevertheless check whether the search has already found
                // a better solution. Hence, the optimizer is set back to "running" for the reconstruction.
                if (optimizerStatus == OptimizerStatus.STOPPED_BY_USER || optimizerStatus == OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY) {
                    optimizerStatus = RUNNING;
                }

                // If the optimizer hasn't found an end board position (user stopped the search, out of memory error, ...),
                // the solution is reconstructed by filling the gap between the last found board position and the end board position
                // with board positions of the original solution. Since in this case, there is always just one solution, only one thread needs to do the work.
                if (endBoardPositionSet.isEmpty()) {

                    // The board positions of the new best found solution.
                    ArrayList<BoardPosition> newSolutionBoardPositions = new ArrayList<>();

                    // Add all board positions of the solution which the search has found. Sanity check: in the rare case
                    // that the search stops before the first board position of the original solution has been reached there is nothing to reconstruct.
                    if (lastVisitedBoardPositionOfOriginalSolutionForward != null) {
                        newSolutionBoardPositions = backwardReconstruction(
                                new EndBoardPositionData(lastVisitedBoardPositionOfOriginalSolutionForward.boxConfigurationIndex,
                                        lastVisitedBoardPositionOfOriginalSolutionForward.playerPosition,
                                        getMoveDirection(lastVisitedBoardPositionOfOriginalSolutionForward)));
                    }

                    // If the forward search hasn't reached any board position of the basis solution, we have to fill the gap
                    // beginning by the very first board position of the basis solution, we have => set the flag to true.
                    boolean forwardBoardPositionReached = lastVisitedBoardPositionOfOriginalSolutionForward == null;
                    for (BoardPosition basisSolutionBoardPosition : boardPositionsSolutionToBeOptimized) {

                        // The first relevant board position is the one after the last visited board position of the search.
                        if (!forwardBoardPositionReached && basisSolutionBoardPosition.equals(lastVisitedBoardPositionOfOriginalSolutionForward)) {
                            forwardBoardPositionReached = true;
                            continue;
                        }

                        // Add board positions that are missing for a complete solution.
                        if (forwardBoardPositionReached) {
                            BoardPosition boardPosition = new BoardPosition(0, 0, 0, 0, 0, basisSolutionBoardPosition.boxConfigurationIndex, basisSolutionBoardPosition.playerPosition >>> 1);
                            newSolutionBoardPositions.add(boardPosition);
                        }
                    }

                    // Create the new solution and set it as best new found solution.
                    OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);
                    bestFoundSolution.set(newSolution);
                } else {
                    reconstructFoundSolutions();
                }

                // Set back the old optimizer status if the optimizer hasn't been stopped.
                if (optimizerStatus == RUNNING) {
                    optimizerStatus = optimizerStatusBackup;
                }

                return bestFoundSolution.get();
            }

            /**
             * Reconstructs the solutions from the found end board positions.
             */
            private void reconstructFoundSolutions() {

                final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
                final ConcurrentLinkedQueue<EndBoardPositionData> meetingBoardPositions = new ConcurrentLinkedQueue<>(endBoardPositionSet);

                final Runnable reconstruction = () -> {
                    EndBoardPositionData meetingData = null;

                    // Reconstruct and check solutions until all data have been processed.
                    while ((meetingData = meetingBoardPositions.poll()) != null && optimizerStatus == RUNNING) {

                        long time = System.currentTimeMillis();

                        ArrayList<BoardPosition> newSolutionBoardPositions = backwardReconstruction(meetingData);

                        // If the optimizer has been stopped, not all board positions have been reconstructed and therefore the solution isn't valid.
                        if (optimizerStatus == RUNNING) {

                            OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);

                            // Check whether this is a new best solution.
                            OptimizerSolution currentBestSolution = null;
                            do {
                                currentBestSolution = bestFoundSolution.get();
                            } while (newSolution.isBetterBoxChangesPushesThan(currentBestSolution) && !bestFoundSolution.compareAndSet(currentBestSolution, newSolution));

                            if (Debug.isDebugModeActivated) {
                                optimizerGUI.addLogTextDebug("Solution reconstruction time: " + (System.currentTimeMillis() - time) + "  Solution: " + newSolution);
                            }
                        }
                    }
                };

                for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                    executor.execute(reconstruction);
                }
                Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            }

            /**
             * Get the moves value from the passed board position.
             *
             * @param boardPosition board position to extract the number of moves
             * @return the number of moves
             */
            private int getBoxChanges(StorageBoardPosition boardPosition) {
                return boardPosition.primaryMetric;
            }


            /**
             * Get the pushes value from the passed board position.
             *
             * @param boardPosition board position to extract the number of pushes from
             * @return the number of pushes
             */
            private int getPushes(StorageBoardPosition boardPosition) {
                return boardPosition.secondaryMetric;
            }


            /**
             * A backward search for reconstructing the best solution by using the
             * saved board positions from the forward search.
             * <p>
             * The forward search has saved the reached board positions and its
             * metrics in the board position storage. The end board position of
             * the forward search is used to reconstruct the solution path.
             *
             * @param meetingBoardPositionData data of an end board position
             * @return {@code OptimizerBoardPosition}s of the found path to the start board position
             */
            private ArrayList<BoardPosition> backwardReconstruction(EndBoardPositionData meetingBoardPositionData) {

                final PriorityQueue<BoardPositionWithPushDirection> openQueue = new PriorityQueue<>(10000, COMPARATOR);

                // Add the board position as start for the search. The player position is stored without the axis (the axis information was only used in the forward search).
                int pullDirection = getOppositeDirection(meetingBoardPositionData.lastMoveDirection);    // we like to pull instead of push
                final BoardPositionWithPushDirection startBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition >>> 1, pullDirection, null);
                openQueue.add(startBoardPosition);

                // Set the box changes and pushes of the board position this search starts with.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, SearchDirection.FORWARD);
                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                int startBoardPositionBoxChanges = getBoxChanges(boardPosition);
                int startBoardPositionPushes = getPushes(boardPosition);

                // Last board position of the best found path to the initial board position of the level.
                BoardPositionWithPushDirection bestEndBoardPosition = null;

                if (startBoardPositionPushes <= 1) {
                    return new ArrayList<>(Collections.singletonList(startBoardPosition));
                }

                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    BoardPositionWithPushDirection currentBoardPosition = openQueue.poll();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;

                    int currentBoardPositionPlayerPosition = currentBoardPosition.playerPosition;

                    // Calculate the position of the last pulled box. The first board position can be identified by having a push direction of -1.
                    int lastPulledBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPositionPlayerPosition]];

                    // Add the start player position and set the current moves depth.
                    playerPositions.addFirst(currentBoardPositionPlayerPosition, 0);

                    playerPositionBFS:
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // Try to move the player to every direction and check whether it's possible to do pulls.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall or a box.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                    (boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {
                                continue;
                            }

                            // The player can move to the new position => store the new position in the queue (if it hasn't been reached, yet).
                            playerPositions.addIfNew(newPlayerPosition);

                            // Check whether there is a box that can be pulled by moving to the new player position. If not, continue with the next move.
                            if ((playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]) == NONE ||  // position next to the player where a box might be
                                    (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                       // position of a possible box to be pulled
                                    !boxConfiguration.isBoxAtPosition(boxPosition) ||                                                     // there is a box next to the player
                                    (newBoxPosition = playerPositionToBoxPosition[playerPosition]) == NONE) {                                     // valid box position
                                continue;
                            }

                            // Pull is possible => do the pull.
                            boxConfiguration.moveBox(boxPosition, newBoxPosition);

                            int boxConfigurationIndex;

                            final int playerPositionWithAxis = (playerPosition << 1) | getAxisOfDirection(direction);

                            // The box configuration must be one of the board positions found during the vicinity search.
                            if ((boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration)) != NONE &&
                                    (boardPositionIndex = boardPositionStorage.getBoardPositionIndex(currentBoardPosition.boxConfigurationIndex, playerPositionWithAxis, SearchDirection.FORWARD)) != BoardPositionsStorage.NONE) {

                                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                                int forwardBoxChanges = getBoxChanges(boardPosition);
                                int forwardPushes = getPushes(boardPosition);

                                // Calculate the box changes and pushes of the board position directly BEFORE the pull has been made!
                                // This must be done to compare them with the stored board positions of the forward search,
                                // which has stored the board positions AFTER a push has been made.
                                int newBoardPositionPushes = currentBoardPosition.pushes;
                                int newBoardPositionBoxChanges = currentBoardPosition.boxChanges;

                                if (boxPosition != lastPulledBoxPosition) {
                                    newBoardPositionBoxChanges++;
                                }

                                // Check if this board position is on the best found path (-> having the correct metrics).
                                if (forwardBoxChanges == startBoardPositionBoxChanges - newBoardPositionBoxChanges &&
                                        forwardPushes == startBoardPositionPushes - newBoardPositionPushes) {

                                    /* Calculate the pushes of the board position, that is: AFTER the pull. */
                                    newBoardPositionPushes++;

                                    // Create a new board position to be added to the open queue.
                                    BoardPositionWithPushDirection newBoardPosition = new BoardPositionWithPushDirection(0, newBoardPositionPushes, 0, newBoardPositionBoxChanges, 0,
                                            boxConfigurationIndex, newPlayerPosition, direction, currentBoardPosition);

                                    // This backward search can't always reach the start board position because the player needn't to be
                                    // next to a box in the start board position. Hence, the last board position to search for
                                    // is the one having one push. The initial board position (having 0 pushes) is already known
                                    // in advance and can therefore be discarded.
                                    if (forwardPushes == 1) {
                                        // Check whether the new board position has been reached better than the current
                                        // best end board position. If yes, then set the new board position as new best one.
                                        if (COMPARATOR.compare(newBoardPosition, bestEndBoardPosition) < 0) {
                                            bestEndBoardPosition = newBoardPosition;
                                        }
                                    } else {
                                        openQueue.add(newBoardPosition);
                                        break playerPositionBFS;
                                    }
                                }
                            }

                            // Undo the pull to reuse the box configuration for the next pull.
                            boxConfiguration.moveBox(newBoxPosition, boxPosition);

                        } // Loop that continues moving the player to all 4 directions until all positions have been visited
                    } // Loop that moves the player to all reachable positions
                }

                // Reconstruct the found path and return an array list of the found board positions.
                ArrayList<BoardPosition> foundPath = new ArrayList<>();

                for (BoardPositionWithPushDirection currentBoardPosition = bestEndBoardPosition;
                     currentBoardPosition != null && currentBoardPosition.previous != null; currentBoardPosition = currentBoardPosition.previous) {

                    // Note: The search saved the board positions AFTER the pull! However,
                    // we need a forward path, which means the board positions after
                    // a push has been done - which on the other hand means BEFORE the pull.


                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);
                    int newPlayerPosition = playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPosition.playerPosition];
                    int boxPosition = playerPositionToBoxPosition[newPlayerPosition];
                    int newBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][newPlayerPosition]];
                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                    BoardPosition newBoardPosition = new BoardPosition(0, 0, 0, 0, 0, boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration), newPlayerPosition);

                    foundPath.add(newBoardPosition);
                }

                return foundPath;
            }
        }
    }

    /**
     * Class for searching for a solution by only using the generated box configurations.<br>
     * The new solution is optimized for: <br>
     * 1. box changes<br>
     * 2. moves<br>
     * Before this method is called the box configurations to be used must have been generated.
     */
    private class BoxChangesMovesOptimizer extends AllMetricsOptimizer {

        private final AtomicInteger boxChangesDepthSearch = new AtomicInteger(-1);

        protected volatile int newBestSolutionBoxChangesCount = -1;
        protected int boxChangesImprovement = 0;
        private final SolutionReconstructionBoxChangesMoves solutionReconstruction = new SolutionReconstructionBoxChangesMoves();

        private int internalPlayerStartPosition = -1;

        LevelSolvedCheck levelSolvedCheck;

        /**
         * Optimizes the passed solution by only using the generated box configurations.<br>
         * The path is optimal regarding: <br>
         * 1. box changes<br>
         * 2. moves<br>
         *
         * @param initialBoard        the board at level start
         * @param endBoard            the end board having all boxes on a goal
         * @param currentBestSolution the currently best solution
         * @return the best found solution
         */
        public OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentBestSolution) {

            // Used to store the real positions of the boxes of a box configuration.
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Get the index of the initial box configuration.
            packBoxConfiguration(boxConfiguration, initialBoard);
            startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            levelSolvedCheck = new LevelSolvedCheck(endBoard);


            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            // Get a list of all board positions of the solution.
            boardPositionsSolutionToBeOptimized = getSolutionBoardPositions(currentBestSolution);

            // Yet, no solution has been found. However, the current best solution is used as upper bound for the box changes and moves.
            BoardPosition endBoardPosition = boardPositionsSolutionToBeOptimized.get(boardPositionsSolutionToBeOptimized.size() - 1);
            newBestSolutionBoxChangesCount = endBoardPosition.boxChanges;
            newBestSolutionMovesCount = endBoardPosition.moves;

            currentBestSolution.movesCount = endBoardPosition.moves;
            currentBestSolution.pushesCount = endBoardPosition.pushes;
            currentBestSolution.boxChanges = endBoardPosition.boxChanges;
            currentBestSolution.boxLines = endBoardPosition.boxLines;
            currentBestSolution.pushingSessions = endBoardPosition.pushingSessions;

            this.solutionToBeOptimized = currentBestSolution;

            // The order value is calculated:
            // boxChangesCount * (estimated maximum number of moves+1) + movesCount
            // The box changes may increase by 0 or 1 per push. However, the move increase per push isn't known.
            // We estimate the new solution not having more than MAXIMAL_MOVES_FACTOR times the number of moves than the solution to be optimized.
            final int estimatedMaximumNewMovesCount = PushesMovesAllMetricsOptimizer.MAXIMAL_MOVES_FACTOR * currentBestSolution.movesCount;
            final int maximumOrderValueChange = 1 * (estimatedMaximumNewMovesCount + 1) + estimatedMaximumNewMovesCount;    // 1 box change and x moves
            final int minimumOrderValueChange = 1;                                                                    // 0 box changes and 1 move

            boardPositionStorage = new BoardPositionsStorage(boxConfigurationStorage.getSize(), playerSquaresCount, 2, estimatedMaximumNewMovesCount, estimatedMaximumNewMovesCount);

            openQueueForward = PriorityQueueOptimizer.getInstance(minimumOrderValueChange, maximumOrderValueChange, boardPositionStorage, maxCPUsToBeUsedCount);


            // Add the start board position for the forward search to the open queue
            // and store it in the reachedPositionsStorage for detecting duplicates.
            internalPlayerStartPosition = playerExternalToInternalPosition[initialBoard.playerPosition];

            // If we don't optimize a pushes range there is no prefix push. In this case we can take any axis as start,
            // because the first push is counted as new box change/box line anyway.
            int firstPushAxis = prefixAxisOfLastPush == NONE ? AXIS_HORIZONTAL : prefixAxisOfLastPush;

            long indexStartBoardPosition = boardPositionStorage.addIfBetter(0, 0, startBoxConfigurationIndex, (internalPlayerStartPosition << 1) | firstPushAxis, SearchDirection.FORWARD);
            openQueueForward.add(indexStartBoardPosition);

            // Execute the optimizer searches.
            final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
            for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                executor.execute(new BoxChangesMovesForwardSearch(threadNo));
            }

            Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            if (Debug.isDebugModeActivated) {
                debugDisplayStatistic();
            }

            // Inform the user about the RAM that is available after the search has ended.
            optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMinMiB()));

            // The optimizer has only been stopped to start a new iteration with the new best found solution.
            // Hence, the optimizer is still running.
            if (optimizerStatus == OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION) {
                optimizerStatus = RUNNING;
            }

            // Return the best found solution (or null if no solution has been found, due
            // to stop of the optimizer by user, out of memory, ...)
            return getBestFoundSolution();
        }


        @Override
        public OptimizerSolution getBestFoundSolution() {
            synchronized (mainOptimizerThread) {
                return solutionReconstruction.getBestSolution();
            }
        }

        /**
         * A forward search considering first box changes, second moves.
         */
        private class BoxChangesMovesForwardSearch implements Runnable {

            /**
             * Unique ID for every thread for accessing the priority queue.
             */
            private final int priorityQueueThreadID;

            /**
             * Creates a {@code Runnable} that performs a forward search for the optimizer.
             *
             * @param priorityQueueThreadID unique ID for the priority queue
             */
            public BoxChangesMovesForwardSearch(int priorityQueueThreadID) {
                this.priorityQueueThreadID = priorityQueueThreadID;
            }

            /**
             * Starts a new forward search.
             */
            @Override
            public void run() {

                try {
                    forwardSearch();
                } catch (OutOfMemoryError e) {
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }

            /**
             * Forward search searching from the start board position forward
             * (-> pushing boxes) until it meets the backward search.
             */
            private void forwardSearch() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    long currentBoardPositionIndex = openQueueForward.removeFirst(priorityQueueThreadID);
                    if (currentBoardPositionIndex == NONE) {
                        break;
                    }

                    // Mark as processed - BEFORE the real metrics are read. This ensures that when
                    // the board position is reached better in the meantime the new better values are read.
                    boardPositionStorage.markAsProcessed(currentBoardPositionIndex);

                    boardPositionStorage.fillBoardPosition(currentBoardPositionIndex, boardPosition);

                    int lastPushedBoxPosition = NONE;
                    if (boardPosition.secondaryMetric == 0 && prefixAxisOfLastPush == NONE) {  // Start board position and no pushes range optimization
                        lastPushedBoxPosition = NONE;
                    } else {
                        int pushDirection = getMoveDirection(boardPosition);
                        int playerNeighbor = playerSquareNeighbor[pushDirection][boardPosition.playerPosition >>> 1];
                        lastPushedBoxPosition = playerPositionToBoxPosition[playerNeighbor];
                    }

                    int axis = boardPosition.playerPosition & 1; // Axis is stored in first bit
                    boardPosition.playerPosition >>>= 1;         // Calculate the real player position (axis is stored in first bit)

                    int boxChangesDepth = boardPosition.primaryMetric;
                    int movesDepth = boardPosition.secondaryMetric;
                    int currentBoxConfigurationIndex = boardPosition.boxConfigurationIndex;

                    int currentDepth = boxChangesDepthSearch.get();
                    if (boxChangesDepth > currentDepth) {
                        if (boxChangesDepth > newBestSolutionBoxChangesCount) {
                            continue;
                        }
                        if (boxChangesDepthSearch.compareAndSet(currentDepth, boxChangesDepth)) {
                            checkForImprovement();
                        }
                    }

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                    playerPositions.addFirst(boardPosition.playerPosition, 0);

                    // Breadth First Search over all reachable player positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Is the neighbor position of the box no deadlock square and not blocked by another box?
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        // First push is always a new boxLine unless we optimize a pushesRange, in which case there is a previous push
                                        // whose axis has been passed to the optimizer.
                                        int newBoxChanges = boardPosition.primaryMetric;
                                        if ((movesDepth == 0 && prefixAxisOfLastPush == NONE) || boxPosition != lastPushedBoxPosition) {
                                            newBoxChanges++;
                                        }

                                        int newMovesDepth = boardPosition.secondaryMetric + playerPositions.movesDepth + 1;

                                        int newPlayerPositionWithAxis = (newPlayerPosition << 1) | getAxisOfDirection(direction); // player position is shifted to store the axis, too

                                        long addedBoardPositionIndex = boardPositionStorage.addIfBetter(newBoxChanges, newMovesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, SearchDirection.FORWARD);
                                        if (addedBoardPositionIndex != BoardPositionsStorage.NONE) {
                                            if (levelSolvedCheck.isTargetBoardPosition(boxConfigurationIndex, newPlayerPosition)) {
                                                collectBestSolutions(newBoxChanges, newMovesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, direction);
                                            } else {
                                                openQueueForward.add(addedBoardPositionIndex);
                                            }
                                        }
                                    }
                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }
            }

            /**
             * This method informs the user whether a better path to any of the board positions of the solution to be optimized
             * has been found and about the current box changes depth that the search has reached.
             */
            private void checkForImprovement() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Identify the board position having the highest number of pushes that has already been reached by
                // the forward search and is part of the basis solution to be optimized and check for any improvements.
                // Ignore the last board position because there are levels which start with all boxes on goals.
                // For these levels start and end board position are the same.
                for (int index = boardPositionsSolutionToBeOptimized.size() - 1; --index != -1; ) {

                    BoardPosition basisSolutionBoardPosition = boardPositionsSolutionToBeOptimized.get(index);

                    // Check whether the board position has already been reached by the forward search.
                    long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(basisSolutionBoardPosition, SearchDirection.FORWARD);
                    if (boardPositionIndex != BoardPositionsStorage.NONE) {

                        boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);

                        // Calculate how much better the board position has been reached compared to the basis solution.
                        int boxChangesDif = basisSolutionBoardPosition.boxChanges - boardPosition.primaryMetric;
                        int movesDif = basisSolutionBoardPosition.moves - boardPosition.secondaryMetric;

                        synchronized (mainOptimizerThread) {
                            if (boxChangesDif > boxChangesImprovement ||
                                    boxChangesDif == boxChangesImprovement && movesDif > movesImprovementForwardSearch) {
                                boxChangesImprovement = boxChangesDif;
                                movesImprovementForwardSearch = movesDif;

                                if (immediateNewIterationWhenImprovement && optimizerStatus == RUNNING) {
                                    optimizerStatus = OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION;
                                }
                            }

                            // Save the last visited board position of the original solution if it has been reached on the currently best known path.
                            if (boxChangesImprovement == boxChangesDif && movesImprovementForwardSearch == movesDif) {
                                lastVisitedBoardPositionOfOriginalSolutionForward = basisSolutionBoardPosition;
                            }
                        }

                        break;
                    }
                }

                displayIntermediateResult();
            }
        }

        /**
         * Returns a list of all board positions the passed solution
         * consists of. The board positions containing information
         * about with which metrics they have in the solution (moves, pushes, ...)
         *
         * @param currentBestSolution solution whose board positions are to be returned
         * @return the board positions of the passed solution consists of
         */
        @Override
        protected ArrayList<BoardPosition> getSolutionBoardPositions(final OptimizerSolution currentBestSolution) {

            return getBoardPositions(initialBoard, currentBestSolution.solution, prefixAxisOfLastPush)
                    .map(bp -> {
                        int playerPositionWithAxis = (bp.playerPosition << 1) | getAxisOfDirection(bp.pushDirection);
                        return new BoardPosition(bp.moves, bp.pushes, bp.boxChanges, bp.boxChanges, bp.pushingSessions, bp.boxConfigurationIndex, playerPositionWithAxis);
                    }).collect(toCollection(ArrayList::new));
        }

        /**
         * Calculates and displays the current box changes depth of the search and the current best solution metrics.
         */
        private void displayIntermediateResult() {

            synchronized (mainOptimizerThread) {

                int newBoxChangesCount = solutionToBeOptimized.boxChanges - boxChangesImprovement;
                int newMovesCount = solutionToBeOptimized.movesCount - movesImprovementForwardSearch;

                // Set the new metrics when a new best solution has been found.
                if (newBoxChangesCount < newBestSolutionBoxChangesCount || newBoxChangesCount == newBestSolutionBoxChangesCount && newMovesCount < newBestSolutionMovesCount) {
                    newBestSolutionBoxChangesCount = newBoxChangesCount;
                    newBestSolutionMovesCount = newMovesCount;
                }

                // Display a status message to show the current depth and the current found improvement.
                // If a only a specific pushes range of the whole solution is optimized,
                // only the metrics of this pushes range are displayed (without the prefix and suffix moves
                // that must be added to get the whole solution).
                optimizerGUI.setInfoText(
                        Texts.getText("vicinitySearchBoxChangesDepth")
                                + boxChangesDepthSearch.get()
                                + "    "
                                + Texts.getText("bestSolution") + ": "
                                + newBestSolutionBoxChangesCount
                                + "/"
                                + newBestSolutionMovesCount
                                + " (" + (-boxChangesImprovement) + "/" + (-movesImprovementForwardSearch) + ")"
                );
            }
        }


        /**
         * Checks whether the search has found a new best solution. If yes, then the data of this new solution is saved.
         *
         * @param boxChangesDepth       number of box changes of the search direction that has found the solution
         * @param movesDepth            number of moves of the search direction that has found the solution
         * @param boxConfigurationIndex index of the box configuration
         * @param playerPosition        player position of the board position
         * @param lastMoveDirection     direction of the last move
         */
        private void collectBestSolutions(
                final int boxChangesDepth,
                final int movesDepth,
                final int boxConfigurationIndex,
                final int playerPosition,
                final int lastMoveDirection) {

            // Check if the new solution is at least as good as the current best one. Synchronized because the metrics of
            // the current best solution may otherwise change in the meantime.
            synchronized (mainOptimizerThread) {
                if (boxChangesDepth < newBestSolutionBoxChangesCount ||
                        boxChangesDepth == newBestSolutionBoxChangesCount && movesDepth <= newBestSolutionMovesCount) {

                    // Only solutions having the best metrics have to be reconstructed. If this solution is a new best one then the
                    // other solutions (represented by the meeting point) can be deleted.
                    if (boxChangesDepth < newBestSolutionBoxChangesCount || movesDepth < newBestSolutionMovesCount) {
                        solutionReconstruction.removeAllEndBoardPositions();

                        newBestSolutionBoxChangesCount = boxChangesDepth;
                        newBestSolutionMovesCount = movesDepth;

                        boxChangesImprovement = solutionToBeOptimized.boxChanges - newBestSolutionBoxChangesCount;
                        movesImprovementForwardSearch = solutionToBeOptimized.movesCount - newBestSolutionMovesCount;

                        displayIntermediateResult();
                    }

                    solutionReconstruction.addEndBoardPosition(boxConfigurationIndex, playerPosition, lastMoveDirection);

                    if (Debug.isDebugModeActivated) {
                        optimizerGUI.addLogTextDebug("New best solution found: BoxChanges/Moves: " + (newBestSolutionBoxChangesCount) + "/" + (newBestSolutionMovesCount));
                    }
                }
            }
        }

        /**
         * Returns the direction of the move performed to reach the passed board position.
         *
         * @param boardPosition the board position
         * @return the direction of the move
         */
        private int getMoveDirection(StorageBoardPosition boardPosition) {

            int axisOfMove = boardPosition.playerPosition & 1;
            int playerPosition = boardPosition.playerPosition >>> 1;

            for (int moveDirection = 0; moveDirection < 4; moveDirection++) {
                int playerNeighbor, boxPosition;
                if (getAxisOfDirection(moveDirection) == axisOfMove
                        && (playerNeighbor = playerSquareNeighbor[moveDirection][playerPosition]) != NONE
                        && (boxPosition = playerPositionToBoxPosition[playerNeighbor]) != NONE
                        && boxConfigurationStorage.isBoxAtPosition(boardPosition.boxConfigurationIndex, boxPosition)) {
                    return moveDirection;
                }
            }

            return NONE;
        }

        /**
         * The main optimizer search stores all board positions in the storage.
         * This class does a new search over the stored board positions in order to reconstruct the best solution
         * regarding first box changes and second moves.
         */
        protected class SolutionReconstructionBoxChangesMoves {

            private final Set<EndBoardPositionData> endBoardPositionSet = ConcurrentHashMap.newKeySet();

            private final AtomicReference<OptimizerSolution> bestFoundSolution = new AtomicReference<>();
            private final Comparator<BoardPosition> COMPARATOR;

            /**
             * Creates a reconstruction object used to search the best solution by using only the
             * board positions generated by the optimizer vicinity search.<br>
             * The search considers all 5 metrics. Which solution is the best is determined by
             * the passed comparator.
             */
            public SolutionReconstructionBoxChangesMoves() {
                COMPARATOR = (boardPosition1, boardPosition2) -> {
                    if (boardPosition1 == null && boardPosition2 == null) {
                        return 0;
                    }
                    if (boardPosition1 == null) {
                        return +1;
                    }
                    if (boardPosition2 == null) {
                        return -1;
                    }
                    return Utilities.intComparePairs(
                            boardPosition1.boxChanges, boardPosition2.boxChanges,
                            boardPosition1.moves, boardPosition2.moves);
                };
            }

            /**
             * Stores an end board position.
             *
             * @param boxConfigurationIndex the index of the box configuration where the searches have met
             * @param playerPosition        player position of the board position where the searches have met
             * @param moveDirection         direction of the last move
             */
            public void addEndBoardPosition(int boxConfigurationIndex, int playerPosition, int moveDirection) {
                EndBoardPositionData meetingData = new EndBoardPositionData(boxConfigurationIndex, playerPosition, moveDirection);
                endBoardPositionSet.add(meetingData);
            }

            /**
             * Deletes all stored meeting points.
             */
            public void removeAllEndBoardPositions() {
                endBoardPositionSet.clear();
            }

            /**
             * Determines the best solution that can be constructed using the board positions
             * that have been stored during the vicinity search. The best solution is found
             * by performing a complete new search considering all 5 metrics.
             *
             * @return the best found solution
             */
            public OptimizerSolution getBestSolution() {

                // Backup the optimizer status since it may be set back to running.
                OptimizerStatus optimizerStatusBackup = optimizerStatus;

                // When the optimizer search couldn't finish normally we can nevertheless check whether the search has already found
                // a better solution. Hence, the optimizer is set back to "running" for the reconstruction.
                if (optimizerStatus == OptimizerStatus.STOPPED_BY_USER || optimizerStatus == OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY) {
                    optimizerStatus = RUNNING;
                }

                // If the optimizer hasn't found an end board position (user stopped the search, out of memory error, ...),
                // the solution is reconstructed by filling the gap between the last found board position and the end board position
                // with board positions of the original solution. Since in this case there is always just one solution only one thread needs to do the work.
                if (endBoardPositionSet.isEmpty()) {

                    // The board positions of the new best found solution.
                    ArrayList<BoardPosition> newSolutionBoardPositions = new ArrayList<>();

                    // Add all board positions of the solution which the search has found. Sanity check: in the rare case
                    // that the search stops before the first board position of the original solution has been reached there is nothing to reconstruct.
                    if (lastVisitedBoardPositionOfOriginalSolutionForward != null) {
                        newSolutionBoardPositions = backwardReconstruction(
                                new EndBoardPositionData(lastVisitedBoardPositionOfOriginalSolutionForward.boxConfigurationIndex,
                                        lastVisitedBoardPositionOfOriginalSolutionForward.playerPosition,
                                        getMoveDirection(lastVisitedBoardPositionOfOriginalSolutionForward)));
                    }

                    // If the forward search hasn't reached any board position of the basis solution we have to fill the gap
                    // beginning by the very first board position of the basis solution we have => set the flag to true.
                    boolean forwardBoardPositionReached = lastVisitedBoardPositionOfOriginalSolutionForward == null;
                    for (BoardPosition basisSolutionBoardPosition : boardPositionsSolutionToBeOptimized) {

                        // The first relevant board position is the one after the last visited board position of the search.
                        if (!forwardBoardPositionReached && basisSolutionBoardPosition.equals(lastVisitedBoardPositionOfOriginalSolutionForward)) {
                            forwardBoardPositionReached = true;
                            continue;
                        }

                        // Add board positions that are missing for a complete solution.
                        if (forwardBoardPositionReached) {
                            BoardPosition boardPosition = new BoardPosition(0, 0, 0, 0, 0, basisSolutionBoardPosition.boxConfigurationIndex, basisSolutionBoardPosition.playerPosition >>> 1);
                            newSolutionBoardPositions.add(boardPosition);
                        }

                    }

                    // Create the new solution and set it as best new-found solution.
                    OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);
                    bestFoundSolution.set(newSolution);
                } else {
                    reconstructFoundSolutions();
                }

                // Set back the old optimizer status if the optimizer hasn't been stopped.
                if (optimizerStatus == RUNNING) {
                    optimizerStatus = optimizerStatusBackup;
                }

                return bestFoundSolution.get();
            }

            /**
             * Returns the direction of the move performed to reach the passed board position.
             *
             * @param boardPosition the board position
             * @return the direction of the move
             */
            private int getMoveDirection(BoardPosition boardPosition) {

                int axisOfMove = boardPosition.playerPosition & 1;
                int moveDirection = 0;
                for (; moveDirection < 4; moveDirection++) {
                    int playerNeighbor, boxPosition;
                    if (getAxisOfDirection(moveDirection) == axisOfMove
                            && (playerNeighbor = playerSquareNeighbor[moveDirection][boardPosition.playerPosition >>> 1]) != NONE
                            && (boxPosition = playerPositionToBoxPosition[playerNeighbor]) != NONE
                            && boxConfigurationStorage.isBoxAtPosition(boardPosition.boxConfigurationIndex, boxPosition)) {
                        return moveDirection;
                    }
                }

                return NONE;
            }

            /**
             * Reconstructs the solutions from the found end board positions.
             */
            private void reconstructFoundSolutions() {

                final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
                final ConcurrentLinkedQueue<EndBoardPositionData> meetingBoardPositions = new ConcurrentLinkedQueue<>(endBoardPositionSet);

                final Runnable reconstruction = () -> {
                    EndBoardPositionData meetingData = null;

                    // Reconstruct and check solutions until all data have been processed.
                    while ((meetingData = meetingBoardPositions.poll()) != null && optimizerStatus == RUNNING) {

                        long time = System.currentTimeMillis();

                        ArrayList<BoardPosition> newSolutionBoardPositions = backwardReconstruction(meetingData);

                        // If the optimizer has been stopped not all board positions have been reconstructed and therefore the solution isn't valid.
                        if (optimizerStatus == RUNNING) {

                            OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);

                            // Check whether this is a new best solution.
                            OptimizerSolution currentBestSolution = null;
                            do {
                                currentBestSolution = bestFoundSolution.get();
                            } while (newSolution.isBetterBoxChangesMovesThan(currentBestSolution) && !bestFoundSolution.compareAndSet(currentBestSolution, newSolution));

                            if (Debug.isDebugModeActivated) {
                                optimizerGUI.addLogTextDebug("Solution reconstruction time: " + (System.currentTimeMillis() - time) + "  Solution: " + newSolution);
                            }
                        }
                    }
                };

                for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                    executor.execute(reconstruction);
                }
                Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            }

            /**
             * Get the box changes value from the passed board position.
             *
             * @param boardPosition board position to extract the number of moves
             * @return the number of box changes
             */
            private int getBoxChanges(StorageBoardPosition boardPosition) {
                return boardPosition.primaryMetric;
            }


            /**
             * Get the moves value from the passed board position.
             *
             * @param boardPosition board position to extract the number of moves from
             * @return the number of moves
             */
            private int getMoves(StorageBoardPosition boardPosition) {
                return boardPosition.secondaryMetric;
            }


            /**
             * A backward search for reconstructing the best solution by using the
             * saved board positions from the forward search.
             * <p>
             * The forward search has saved the reached board positions and its
             * metrics in the board position storage. The end board position of
             * the forward search is used to reconstruct the solution path.
             *
             * @param meetingBoardPositionData data of an end board position
             * @return {@code OptimizerBoardPosition}s of the found path to the start board position
             */
            private ArrayList<BoardPosition> backwardReconstruction(EndBoardPositionData meetingBoardPositionData) {

                final PriorityQueue<BoardPositionWithPushDirection> openQueue = new PriorityQueue<>(10000, COMPARATOR);

                // Add the board position as start for the search. The player position is stored without the axis (the axis information was only used in the forward search).
                int pullDirection = getOppositeDirection(meetingBoardPositionData.lastMoveDirection);    // we like to pull instead of push
                final BoardPositionWithPushDirection startBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition >>> 1, pullDirection, null);
                openQueue.add(startBoardPosition);


                // Set the box changes and moves of the board position this search starts with.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, SearchDirection.FORWARD);
                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                int startBoardPositionBoxChanges = getBoxChanges(boardPosition);
                int startBoardPositionMoves = getMoves(boardPosition);

                // Last board position of the best found path to the initial board position of the level.
                BoardPositionWithPushDirection bestEndBoardPosition = null;

                if (startBoardPositionMoves <= 1) {
                    return new ArrayList<>(Collections.singletonList(startBoardPosition));
                }

                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    BoardPositionWithPushDirection currentBoardPosition = openQueue.poll();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;

                    int currentBoardPositionPlayerPosition = currentBoardPosition.playerPosition;

                    // Calculate the position of the last pulled box. The first board position can be identified by having a push direction of -1.
                    int lastPulledBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPositionPlayerPosition]];

                    // Add the start player position and set the current move depth.
                    playerPositions.addFirst(currentBoardPositionPlayerPosition, 0);

                    playerPositionBFS:
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // This backward search can't always reach the start board position because the player
                        // needn't be next to a box in the start board position. Hence, the last board position
                        // to search for is the one having one push. The initial board position (having 0 pushes)
                        // is already known in advance and can therefore be discarded.
                        // => If we have reached the start box configuration by the last push then the current
                        // board position must be the one having 1 push.
                        if (currentBoardPosition.boxConfigurationIndex == startBoxConfigurationIndex && playerPosition == internalPlayerStartPosition
                                && currentBoardPosition.pushes > 0) {        // There exist levels which starts with a solved board position -> check for at least one done push
                            // Check whether the new board position has been reached better than the current
                            // best end board position. If yes, then set the new board position as new best one.
                            if (COMPARATOR.compare(currentBoardPosition, bestEndBoardPosition) < 0) {
                                bestEndBoardPosition = currentBoardPosition;
                            }

                            continue;
                        }

                        // Try to move the player to every direction and check whether it's possible to do pulls.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall or a box.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                    (boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {
                                continue;
                            }

                            // The player can move to the new position => store the new position in the queue (if it hasn't been reached, yet).
                            playerPositions.addIfNew(newPlayerPosition);

                            // Check whether there is a box which can be pulled by moving to the new player position. If not, continue with the next move.
                            if ((playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]) == NONE ||  // position next to the player where a box might be
                                    (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                         // position of a possible box to be pulled
                                    !boxConfiguration.isBoxAtPosition(boxPosition) ||                                                             // there is a box next to the player
                                    (newBoxPosition = playerPositionToBoxPosition[playerPosition]) == NONE) {                                         // valid box position
                                continue;
                            }

                            // Pull is possible => do the pull.
                            boxConfiguration.moveBox(boxPosition, newBoxPosition);

                            int newBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

                            final int playerPositionWithAxis = (playerPosition << 1) | getAxisOfDirection(direction);

                            // The box configuration must be one of the board positions found during the vicinity search.
                            if (newBoxConfigurationIndex != NONE &&
                                    (boardPositionIndex = boardPositionStorage.getBoardPositionIndex(currentBoardPosition.boxConfigurationIndex, playerPositionWithAxis, SearchDirection.FORWARD)) != BoardPositionsStorage.NONE) {

                                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                                int forwardBoxChanges = getBoxChanges(boardPosition);
                                int forwardMoves = getMoves(boardPosition);

                                // Calculate the box changes and moves of the board position directly BEFORE the pull has been made!
                                // This must be done to compare them with the stored board positions of the forward search,
                                // which has stored the board positions AFTER a push has been made.
                                int newBoardPositionMoves = currentBoardPosition.moves + playerPositions.movesDepth;
                                int newBoardPositionPushes = currentBoardPosition.pushes;
                                int newBoardPositionBoxChanges = currentBoardPosition.boxChanges;

                                if (boxPosition != lastPulledBoxPosition) {
                                    newBoardPositionBoxChanges++;
                                }

                                // Check if this board position is on the best found path (-> having the correct metrics).
                                if (forwardBoxChanges == startBoardPositionBoxChanges - newBoardPositionBoxChanges &&
                                        forwardMoves == startBoardPositionMoves - newBoardPositionMoves) {

                                    /* Calculate the new metrics of the board position, that is: AFTER the pull. */
                                    newBoardPositionMoves++;
                                    newBoardPositionPushes++;

                                    // Create a new board position to be added to the open queue.
                                    BoardPositionWithPushDirection newBoardPosition = new BoardPositionWithPushDirection(newBoardPositionMoves,
                                            newBoardPositionPushes, 0, newBoardPositionBoxChanges, 0,
                                            newBoxConfigurationIndex, newPlayerPosition, direction, currentBoardPosition);

                                    openQueue.add(newBoardPosition);

                                    break playerPositionBFS; // we only search any path regarding box changes and moves
                                }
                            }

                            boxConfiguration.moveBox(newBoxPosition, boxPosition);        // Undo the pull to reuse the box configuration for the next pull.

                        } // Loop that continues moving the player to all 4 directions until all positions have been visited
                    } // Loop that moves the player to all reachable positions
                }

                // Reconstruct the found path and return an array list of the found board positions.
                ArrayList<BoardPosition> foundPath = new ArrayList<>();

                for (BoardPositionWithPushDirection currentBoardPosition = bestEndBoardPosition;
                     currentBoardPosition != null && currentBoardPosition.previous != null; currentBoardPosition = currentBoardPosition.previous) {

                    // Note: The search saved the board positions AFTER the pull! However,
                    // we need a forward path, which means the board positions after
                    // a push has been done - which on the other hand means BEFORE the pull.

                    BoardPosition newBoardPosition = currentBoardPosition;

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);
                    int newPlayerPosition = playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPosition.playerPosition];
                    int boxPosition = playerPositionToBoxPosition[newPlayerPosition];
                    int newBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][newPlayerPosition]];
                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                    newBoardPosition = new BoardPosition(0, 0, 0, 0, 0, boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration), newPlayerPosition);

                    foundPath.add(newBoardPosition);
                }

                return foundPath;
            }
        }
    }

    /**
     * Class for searching for a solution by only using the generated box configurations.<br>
     * The new solution is optimized for: <br>
     * 1. pushing sessions<br>
     * 2. moves<br>
     * Before this method is called the box configurations to be used must have been generated.
     */
    private class PushingSessionsMovesOptimizer extends AllMetricsOptimizer {

        private final AtomicInteger pushingSessionsDepthSearch = new AtomicInteger(-1);

        protected volatile int newBestSolutionPushingSessionsCount = -1;
        protected int pushingSessionImprovement = 0;
        private final SolutionReconstructionPushingSessionsMoves solutionReconstruction = new SolutionReconstructionPushingSessionsMoves();

        private int internalPlayerStartPosition = -1;

        LevelSolvedCheck levelSolvedCheck;

        /**
         * Optimizes the passed solution by only using the generated box configurations.<br>
         * The path is optimal regarding: <br>
         * 1. pushing sessions<br>
         * 2. moves<br>
         *
         * @param initialBoard        the board at level start
         * @param endBoard            the end board having all boxes on a goal
         * @param currentBestSolution the currently best solution
         * @return the best found solution
         */
        public OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentBestSolution) {

            // Used to store the real positions of the boxes of a box configuration.
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Get the index of the initial box configuration.
            packBoxConfiguration(boxConfiguration, initialBoard);
            startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            levelSolvedCheck = new LevelSolvedCheck(endBoard);


            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            // Get a list of all board positions of the solution.
            boardPositionsSolutionToBeOptimized = getSolutionBoardPositions(currentBestSolution);
            // Yet no solution has been found. However, the current best solution is used as upper bound for the pushing sessions and moves.
            BoardPosition endBoardPosition = boardPositionsSolutionToBeOptimized.get(boardPositionsSolutionToBeOptimized.size() - 1);
            newBestSolutionPushingSessionsCount = endBoardPosition.pushingSessions;
            newBestSolutionMovesCount = endBoardPosition.moves;

            currentBestSolution.movesCount = endBoardPosition.moves;
            currentBestSolution.pushesCount = endBoardPosition.pushes;
            currentBestSolution.boxChanges = endBoardPosition.boxChanges;
            currentBestSolution.boxLines = endBoardPosition.boxLines;
            currentBestSolution.pushingSessions = endBoardPosition.pushingSessions;

            this.solutionToBeOptimized = currentBestSolution;

            // The order value is calculated:
            // pushingSessionCount * (estimated maximum number of moves+1) + movesCount
            // The pushing sessions may increase by 0 or 1 per push. However, the move increase per push isn't known.
            // We estimate the new solution not having more than MAXIMAL_MOVES_FACTOR times the number of moves than the solution to be optimized.
            final int estimatedMaximumNewMovesCount = PushesMovesAllMetricsOptimizer.MAXIMAL_MOVES_FACTOR * currentBestSolution.movesCount;
            final int maximumOrderValueChange = 1 * (estimatedMaximumNewMovesCount + 1) + estimatedMaximumNewMovesCount;    // 1 pushing session and x moves
            final int minimumOrderValueChange = 1;                                                                    // 0 pushing sessions and 1 move

            boardPositionStorage = new BoardPositionsStorage(boxConfigurationStorage.getSize(), playerSquaresCount, 2, estimatedMaximumNewMovesCount, estimatedMaximumNewMovesCount);

            openQueueForward = PriorityQueueOptimizer.getInstance(minimumOrderValueChange, maximumOrderValueChange, boardPositionStorage, maxCPUsToBeUsedCount);


            // Add the start board position for the forward search to the open queue
            // and store it in the reachedPositionsStorage for detecting duplicates.
            internalPlayerStartPosition = playerExternalToInternalPosition[initialBoard.playerPosition];

            // If we don't optimize a pushes range, there is no prefix push. In this case, we can take any axis as start,
            // because the first push is counted as new box change/box line anyway.
            int firstPushAxis = prefixAxisOfLastPush == NONE ? AXIS_HORIZONTAL : prefixAxisOfLastPush;

            long indexStartBoardPosition = boardPositionStorage.addIfBetter(0, 0, startBoxConfigurationIndex, (internalPlayerStartPosition << 1) | firstPushAxis, SearchDirection.FORWARD);
            openQueueForward.add(indexStartBoardPosition);

            // Execute the optimizer searches.
            final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
            for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                executor.execute(new PushingSessionsMovesForwardSearch(threadNo));
            }

            Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            if (Debug.isDebugModeActivated) {
                debugDisplayStatistic();
            }

            // Inform the user about the RAM that is available after the search has ended.
            optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMinMiB()));

            // The optimizer has only been stopped to start a new iteration with the new best found solution.
            // Hence, the optimizer is still running.
            if (optimizerStatus == OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION) {
                optimizerStatus = RUNNING;
            }

            // Return the best found solution (or null if no solution has been found, due
            // to stop of the optimizer by user, out of memory, ...)
            return getBestFoundSolution();
        }


        @Override
        public OptimizerSolution getBestFoundSolution() {
            synchronized (mainOptimizerThread) {
                return solutionReconstruction.getBestSolution();
            }
        }

        /**
         * A forward search considering first pushing sessions, second moves.
         */
        private class PushingSessionsMovesForwardSearch implements Runnable {

            /**
             * Unique ID for every thread for accessing the priority queue.
             */
            private final int priorityQueueThreadID;

            /**
             * Creates a {@code Runnable} that performs a forward search for the optimizer.
             *
             * @param priorityQueueThreadID unique ID for the priority queue
             */
            public PushingSessionsMovesForwardSearch(int priorityQueueThreadID) {
                this.priorityQueueThreadID = priorityQueueThreadID;
            }

            /**
             * Starts a new forward search.
             */
            @Override
            public void run() {

                try {
                    forwardSearch();
                } catch (OutOfMemoryError e) {
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }

            /**
             * Forward search searching from the start board position forward
             * (-> pushing boxes) until it meets the backward search.
             */
            private void forwardSearch() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    long currentBoardPositionIndex = openQueueForward.removeFirst(priorityQueueThreadID);
                    if (currentBoardPositionIndex == NONE) {
                        break;
                    }

                    // Mark as processed - BEFORE the real metrics are read. This ensures that when
                    // the board position is reached better in the meantime, the new better values are read.
                    boardPositionStorage.markAsProcessed(currentBoardPositionIndex);

                    boardPositionStorage.fillBoardPosition(currentBoardPositionIndex, boardPosition);

                    int axis = boardPosition.playerPosition & 1; // Axis is stored in the first bit
                    boardPosition.playerPosition >>>= 1;         // Calculate the real player position (axis is stored in the first bit)

                    int pushingSessionsDepth = boardPosition.primaryMetric;
                    int movesDepth = boardPosition.secondaryMetric;
                    int currentBoxConfigurationIndex = boardPosition.boxConfigurationIndex;

                    int currentDepth = pushingSessionsDepthSearch.get();
                    if (pushingSessionsDepth > currentDepth) {
                        if (pushingSessionsDepth > newBestSolutionPushingSessionsCount) {
                            continue;
                        }
                        if (pushingSessionsDepthSearch.compareAndSet(currentDepth, pushingSessionsDepth)) {
                            checkForImprovement();
                        }
                    }

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                    playerPositions.addFirst(boardPosition.playerPosition, 0);

                    // Breadth-First-Search over all reachable player positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Is the neighbor position of the box no deadlock square and not blocked by another box?
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        // The first push is always a new pushing sessions unless we optimize a pushesRange,
                                        // in which case there is a previous push whose axis has been passed to the optimizer.
                                        int newPushingSessions = boardPosition.primaryMetric;
                                        if ((movesDepth == 0 && prefixAxisOfLastPush == NONE) || playerPositions.movesDepth > 0) {
                                            newPushingSessions++;
                                        }

                                        int newMovesDepth = boardPosition.secondaryMetric + playerPositions.movesDepth + 1;

                                        int newPlayerPositionWithAxis = (newPlayerPosition << 1) | getAxisOfDirection(direction); // player position is shifted to store the axis, too

                                        long addedBoardPositionIndex = boardPositionStorage.addIfBetter(newPushingSessions, newMovesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, SearchDirection.FORWARD);
                                        if (addedBoardPositionIndex != BoardPositionsStorage.NONE) {
                                            if (levelSolvedCheck.isTargetBoardPosition(boxConfigurationIndex, newPlayerPosition)) {
                                                collectBestSolutions(newPushingSessions, newMovesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, direction);
                                            } else {
                                                openQueueForward.add(addedBoardPositionIndex);
                                            }
                                        }
                                    }
                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }
            }

            /**
             * This method informs the user whether a better path to any of the board positions of the solution to be optimized
             * has been found and about the current pushing sessions depth that the search has reached.
             */
            private void checkForImprovement() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Identify the board position having the highest number of pushes that has already been reached by
                // the forward search and is part of the basis solution to be optimized and check for any improvements.
                // Ignore the last board position because there are levels that start with all boxes on goals.
                // For these levels start and end board position are the same.
                for (int index = boardPositionsSolutionToBeOptimized.size() - 1; --index != -1; ) {

                    BoardPosition basisSolutionBoardPosition = boardPositionsSolutionToBeOptimized.get(index);

                    // Check whether the board position has already been reached by the forward search.
                    long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(basisSolutionBoardPosition, SearchDirection.FORWARD);
                    if (boardPositionIndex != BoardPositionsStorage.NONE) {

                        boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);

                        // Calculate how much better the board position has been reached compared to the basis solution.
                        int pushingSessionsDif = basisSolutionBoardPosition.pushingSessions - boardPosition.primaryMetric;
                        int movesDif = basisSolutionBoardPosition.moves - boardPosition.secondaryMetric;

                        synchronized (mainOptimizerThread) {
                            if (pushingSessionsDif > pushingSessionImprovement ||
                                    pushingSessionsDif == pushingSessionImprovement && movesDif > movesImprovementForwardSearch) {
                                pushingSessionImprovement = pushingSessionsDif;
                                movesImprovementForwardSearch = movesDif;

                                if (immediateNewIterationWhenImprovement && optimizerStatus == RUNNING) {
                                    optimizerStatus = OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION;
                                }
                            }

                            // Save the last visited board position of the original solution if it has been reached on the currently best known path.
                            if (pushingSessionImprovement == pushingSessionsDif && movesImprovementForwardSearch == movesDif) {
                                lastVisitedBoardPositionOfOriginalSolutionForward = basisSolutionBoardPosition;
                            }
                        }

                        break;
                    }
                }

                displayIntermediateResult();
            }
        }

        /**
         * Returns a list of all board positions the passed solution
         * consists of. The board positions contain information
         * about with which metrics they have in the solution (moves, pushes, ...)
         *
         * @param currentBestSolution solution whose board positions are to be returned
         * @return the board positions of the passed solution
         */
        @Override
        protected ArrayList<BoardPosition> getSolutionBoardPositions(final OptimizerSolution currentBestSolution) {

            return getBoardPositions(initialBoard, currentBestSolution.solution, prefixAxisOfLastPush)
                    .map(bp -> {
                        int playerPositionWithAxis = (bp.playerPosition << 1) | getAxisOfDirection(bp.pushDirection);
                        return new BoardPosition(bp.moves, bp.pushes, bp.boxChanges, bp.boxChanges, bp.pushingSessions, bp.boxConfigurationIndex, playerPositionWithAxis);
                    }).collect(toCollection(ArrayList::new));
        }

        /**
         * Calculates and displays the current pushing session depth of the search and the current best solution metrics.
         */
        private void displayIntermediateResult() {

            synchronized (mainOptimizerThread) {

                int newPushingSessionCount = solutionToBeOptimized.pushingSessions - pushingSessionImprovement;
                int newMovesCount = solutionToBeOptimized.movesCount - movesImprovementForwardSearch;

                // Set the new metrics when a better solution has been found.
                if (newPushingSessionCount < newBestSolutionPushingSessionsCount || newPushingSessionCount == newBestSolutionPushingSessionsCount && newMovesCount < newBestSolutionMovesCount) {
                    newBestSolutionPushingSessionsCount = newPushingSessionCount;
                    newBestSolutionMovesCount = newMovesCount;
                }

                // Display a status message to show the current depth and the current found improvement.
                // If a only a specific pushes range of the whole solution is optimized,
                // only the metrics of this pushes range are displayed (without the prefix and suffix moves
                // that must be added to get the whole solution).
                optimizerGUI.setInfoText(
                        Texts.getText("vicinitySearchPushingSessionDepth")
                                + pushingSessionsDepthSearch.get()
                                + "    "
                                + Texts.getText("bestSolution") + ": "
                                + newBestSolutionPushingSessionsCount
                                + "/"
                                + newBestSolutionMovesCount
                                + " (" + (-pushingSessionImprovement) + "/" + (-movesImprovementForwardSearch) + ")"
                );
            }
        }

        /**
         * Checks whether the search has found a new best solution. If yes, then the data of this new solution is saved.
         *
         * @param pushingSessionDepth   number of pushing sessions of the search direction that has found the solution
         * @param movesDepth            number of moves of the search direction that has found the solution
         * @param boxConfigurationIndex index of the box configuration
         * @param playerPosition        player position of the board position
         * @param lastMoveDirection     direction of the last move
         */
        private void collectBestSolutions(
                final int pushingSessionDepth,
                final int movesDepth,
                final int boxConfigurationIndex,
                final int playerPosition,
                final int lastMoveDirection) {

            // Check if the new solution is at least as good as the current best one. Synchronized because the metrics of
            // the current best solution may otherwise change in the meantime.
            synchronized (mainOptimizerThread) {
                if (pushingSessionDepth < newBestSolutionPushingSessionsCount ||
                        pushingSessionDepth == newBestSolutionPushingSessionsCount && movesDepth <= newBestSolutionMovesCount) {

                    // Only solutions having the best metrics have to be reconstructed. If this solution is a new best solution,
                    // then the other solutions (represented by the meeting point) can be deleted.
                    if (pushingSessionDepth < newBestSolutionPushingSessionsCount || movesDepth < newBestSolutionMovesCount) {
                        solutionReconstruction.removeAllEndBoardPositions();

                        newBestSolutionPushingSessionsCount = pushingSessionDepth;
                        newBestSolutionMovesCount = movesDepth;

                        pushingSessionImprovement = solutionToBeOptimized.pushingSessions - newBestSolutionPushingSessionsCount;
                        movesImprovementForwardSearch = solutionToBeOptimized.movesCount - newBestSolutionMovesCount;

                        displayIntermediateResult();
                    }

                    solutionReconstruction.addEndBoardPosition(boxConfigurationIndex, playerPosition, lastMoveDirection);

                    if (Debug.isDebugModeActivated) {
                        optimizerGUI.addLogTextDebug("New best solution found: PushingSessions/Moves: " + (newBestSolutionPushingSessionsCount) + "/" + (newBestSolutionMovesCount));
                    }
                }
            }
        }

        /**
         * The main optimizer search stores all board positions in the storage.
         * This class does a new search over the stored board positions in order to reconstruct the best solution
         * regarding first pushing sessions and second moves.
         */
        protected class SolutionReconstructionPushingSessionsMoves {

            private final Set<EndBoardPositionData> endBoardPositionSet = ConcurrentHashMap.newKeySet();

            private final AtomicReference<OptimizerSolution> bestFoundSolution = new AtomicReference<>();
            private final Comparator<BoardPosition> COMPARATOR;

            /**
             * Creates a reconstruction object used to search the best solution by using only the
             * board positions generated by the optimizer vicinity search.<br>
             * The search considers all 5 metrics. Which solution is the best is determined by
             * the passed comparator.
             */
            public SolutionReconstructionPushingSessionsMoves() {
                COMPARATOR = (boardPosition1, boardPosition2) -> {
                    if (boardPosition1 == null && boardPosition2 == null) {
                        return 0;
                    }
                    if (boardPosition1 == null) {
                        return +1;
                    }
                    if (boardPosition2 == null) {
                        return -1;
                    }
                    return Utilities.intComparePairs(
                            boardPosition1.pushingSessions, boardPosition2.pushingSessions,
                            boardPosition1.moves, boardPosition2.moves);
                };
            }

            /**
             * Stores an end board position.
             *
             * @param boxConfigurationIndex the index of the box configuration where the searches have met
             * @param playerPosition        player position of the board position where the searches have met
             * @param moveDirection         direction of the last move
             */
            public void addEndBoardPosition(int boxConfigurationIndex, int playerPosition, int moveDirection) {
                EndBoardPositionData meetingData = new EndBoardPositionData(boxConfigurationIndex, playerPosition, moveDirection);
                endBoardPositionSet.add(meetingData);
            }

            /**
             * Deletes all stored meeting points.
             */
            public void removeAllEndBoardPositions() {
                endBoardPositionSet.clear();
            }

            /**
             * Determines the best solution that can be constructed using the board positions
             * that have been stored during the vicinity search. The best solution is found
             * by performing a complete new search considering all 5 metrics.
             *
             * @return the best found solution
             */
            public OptimizerSolution getBestSolution() {

                // Backup the optimizer status since it may be set back to running.
                OptimizerStatus optimizerStatusBackup = optimizerStatus;

                // When the optimizer search couldn't finish normally, we can nevertheless check whether the search has already found
                // a better solution. Hence, the optimizer is set back to "running" for the reconstruction.
                if (optimizerStatus == OptimizerStatus.STOPPED_BY_USER || optimizerStatus == OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY) {
                    optimizerStatus = RUNNING;
                }

                // If the optimizer hasn't found an end board position (user stopped the search, out of memory error, ...),
                // the solution is reconstructed by filling the gap between the last found board position and the end board position
                // with board positions of the original solution. Since in this case there is always just one solution only one thread needs to do the work.
                if (endBoardPositionSet.isEmpty()) {

                    // The board positions of the new best found solution.
                    ArrayList<BoardPosition> newSolutionBoardPositions = new ArrayList<>();

                    // Add all board positions of the solution which the search has found. Sanity check: in the rare case
                    // that the search stops before the first board position of the original solution has been reached, there is nothing to reconstruct.
                    if (lastVisitedBoardPositionOfOriginalSolutionForward != null) {
                        newSolutionBoardPositions = backwardReconstruction(
                                new EndBoardPositionData(lastVisitedBoardPositionOfOriginalSolutionForward.boxConfigurationIndex,
                                        lastVisitedBoardPositionOfOriginalSolutionForward.playerPosition,
                                        getMoveDirection(lastVisitedBoardPositionOfOriginalSolutionForward)));
                    }

                    // If the forward search hasn't reached any board position of the basis solution we have to fill the gap
                    // beginning by the very first board position of the basis solution we have => set the flag to true.
                    boolean forwardBoardPositionReached = lastVisitedBoardPositionOfOriginalSolutionForward == null;
                    for (BoardPosition basisSolutionBoardPosition : boardPositionsSolutionToBeOptimized) {

                        // The first relevant board position is the one after the last visited board position of the search.
                        if (!forwardBoardPositionReached && basisSolutionBoardPosition.equals(lastVisitedBoardPositionOfOriginalSolutionForward)) {
                            forwardBoardPositionReached = true;
                            continue;
                        }

                        // Add board positions that are missing for a complete solution.
                        if (forwardBoardPositionReached) {
                            BoardPosition boardPosition = new BoardPosition(0, 0, 0, 0, 0, basisSolutionBoardPosition.boxConfigurationIndex, basisSolutionBoardPosition.playerPosition >>> 1);
                            newSolutionBoardPositions.add(boardPosition);
                        }

                    }

                    // Create the new solution and set it as best new-found solution.
                    OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);
                    bestFoundSolution.set(newSolution);
                } else {
                    reconstructFoundSolutions();
                }

                // Set back the old optimizer status if the optimizer hasn't been stopped.
                if (optimizerStatus == RUNNING) {
                    optimizerStatus = optimizerStatusBackup;
                }

                return bestFoundSolution.get();
            }

            /**
             * Returns the direction of the move performed to reach the passed board position.
             *
             * @param boardPosition the board position
             * @return the direction of the move
             */
            private int getMoveDirection(BoardPosition boardPosition) {

                int axisOfMove = boardPosition.playerPosition & 1;
                int moveDirection = 0;
                for (; moveDirection < 4; moveDirection++) {
                    int playerNeighbor, boxPosition;
                    if (getAxisOfDirection(moveDirection) == axisOfMove
                            && (playerNeighbor = playerSquareNeighbor[moveDirection][boardPosition.playerPosition >>> 1]) != NONE
                            && (boxPosition = playerPositionToBoxPosition[playerNeighbor]) != NONE
                            && boxConfigurationStorage.isBoxAtPosition(boardPosition.boxConfigurationIndex, boxPosition)) {
                        return moveDirection;
                    }
                }

                return NONE;
            }

            /**
             * Reconstructs the solutions from the found end board positions.
             */
            private void reconstructFoundSolutions() {

                final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
                final ConcurrentLinkedQueue<EndBoardPositionData> meetingBoardPositions = new ConcurrentLinkedQueue<>(endBoardPositionSet);

                final Runnable reconstruction = () -> {
                    EndBoardPositionData meetingData = null;

                    // Reconstruct and check solutions until all data have been processed.
                    while ((meetingData = meetingBoardPositions.poll()) != null && optimizerStatus == RUNNING) {

                        long time = System.currentTimeMillis();

                        ArrayList<BoardPosition> newSolutionBoardPositions = backwardReconstruction(meetingData);

                        // If the optimizer has been stopped, not all board positions have been reconstructed and therefore the solution isn't valid.
                        if (optimizerStatus == RUNNING) {

                            OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);

                            // Check whether this is the new best solution.
                            OptimizerSolution currentBestSolution = null;
                            do {
                                currentBestSolution = bestFoundSolution.get();
                            } while (newSolution.isBetterPushingSessionsMovesThan(currentBestSolution) && !bestFoundSolution.compareAndSet(currentBestSolution, newSolution));

                            if (Debug.isDebugModeActivated) {
                                optimizerGUI.addLogTextDebug("Solution reconstruction time: " + (System.currentTimeMillis() - time) + "  Solution: " + newSolution);
                            }
                        }
                    }
                };

                for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                    executor.execute(reconstruction);
                }
                Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            }

            /**
             * Get the pushing sessions value from the passed board position.
             *
             * @param boardPosition board position to extract the number of moves
             * @return the number of pushing sessions
             */
            private int getPushingSessions(StorageBoardPosition boardPosition) {
                return boardPosition.primaryMetric;
            }


            /**
             * Get the moves value from the passed board position.
             *
             * @param boardPosition board position to extract the number of moves from
             * @return the number of moves
             */
            private int getMoves(StorageBoardPosition boardPosition) {
                return boardPosition.secondaryMetric;
            }


            /**
             * A backward search for reconstructing the best solution by using the
             * saved board positions from the forward search.
             * <p>
             * The forward search has saved the reached board positions and its
             * metrics in the board position storage. The end board position of
             * the forward search is used to reconstruct the solution path.
             *
             * @param meetingBoardPositionData data of an end board position
             * @return {@code OptimizerBoardPosition}s of the found path to the start board position
             */
            private ArrayList<BoardPosition> backwardReconstruction(EndBoardPositionData meetingBoardPositionData) {

                final PriorityQueue<BoardPositionWithPushDirection> openQueue = new PriorityQueue<>(10000, COMPARATOR);

                // Add the board position as start for the search.
                int pullDirection = getOppositeDirection(meetingBoardPositionData.lastMoveDirection);    // we like to pull instead of push
                final BoardPositionWithPushDirection startBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition >>> 1, pullDirection, null);
                openQueue.add(startBoardPosition);


                // Set the pushing sessions and moves of the board position this search starts with.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, SearchDirection.FORWARD);
                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                int startBoardPositionPushingSessions = getPushingSessions(boardPosition);
                int startBoardPositionMoves = getMoves(boardPosition);

                // Last board position of the best found path to the initial board position of the level.
                BoardPositionWithPushDirection bestEndBoardPosition = null;

                if (startBoardPositionMoves <= 1) {
                    return new ArrayList<>(Collections.singletonList(startBoardPosition));
                }

                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    BoardPositionWithPushDirection currentBoardPosition = openQueue.poll();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;

                    int currentBoardPositionPlayerPosition = currentBoardPosition.playerPosition;

                    // Add the start player position and set the current moves-depth.
                    playerPositions.addFirst(currentBoardPositionPlayerPosition, 0);

                    playerPositionBFS:
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // This backward search can't always reach the start board position because the player
                        // needn't be next to a box in the start board position. Hence, the last board position
                        // to search for is the one having one push. The initial board position (having 0 pushes)
                        // is already known in advance and can therefore be discarded.
                        // => If we have reached the start box configuration by the last push, then the current
                        // board position must be the one having 1 push.
                        if (currentBoardPosition.boxConfigurationIndex == startBoxConfigurationIndex && playerPosition == internalPlayerStartPosition
                                && currentBoardPosition.pushes > 0) {        // There exist levels which starts with a solved board position -> check for at least one done push
                            // Check whether the new board position has been reached better than the current
                            // best end board position. If yes, then set the new board position as the new best one.
                            if (COMPARATOR.compare(currentBoardPosition, bestEndBoardPosition) < 0) {
                                bestEndBoardPosition = currentBoardPosition;
                            }

                            continue;
                        }

                        // Try to move the player to every direction and check whether it's possible to do pulls.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall or a box.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                    (boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {
                                continue;
                            }

                            // The player can move to the new position => store the new position in the queue (if it hasn't been reached, yet).
                            playerPositions.addIfNew(newPlayerPosition);

                            // Check whether there is a box that can be pulled by moving to the new player position. If not, continue with the next move.
                            if ((playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]) == NONE ||  // position next to the player where a box might be
                                    (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                         // position of a possible box to be pulled
                                    !boxConfiguration.isBoxAtPosition(boxPosition) ||                                                         // there is a box next to the player
                                    (newBoxPosition = playerPositionToBoxPosition[playerPosition]) == NONE) {                                         // valid box position
                                continue;
                            }

                            // Pull is possible => do the pull.
                            boxConfiguration.moveBox(boxPosition, newBoxPosition);

                            int newBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

                            final int playerPositionWithAxis = (playerPosition << 1) | getAxisOfDirection(direction);

                            // The box configuration must be one of the board positions found during the vicinity search.
                            if (newBoxConfigurationIndex != NONE &&
                                    (boardPositionIndex = boardPositionStorage.getBoardPositionIndex(currentBoardPosition.boxConfigurationIndex, playerPositionWithAxis, SearchDirection.FORWARD)) != BoardPositionsStorage.NONE) {

                                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                                int forwardPushingSessions = getPushingSessions(boardPosition);
                                int forwardMoves = getMoves(boardPosition);

                                // Calculate the pushing sessions and moves of the board position directly BEFORE the pull has been made!
                                // This must be done to compare them with the stored board positions of the forward search,
                                // which has stored the board positions AFTER a push has been made.
                                int newBoardPositionMoves = currentBoardPosition.moves + playerPositions.movesDepth;
                                int newBoardPositionPushes = currentBoardPosition.pushes;
                                int newBoardPositionPushingSessions = currentBoardPosition.pushingSessions;

                                if (playerPositions.movesDepth > 0) {
                                    newBoardPositionPushingSessions++;
                                }

                                // Check if this board position is on the best found path (-> having the correct metrics).
                                if (forwardPushingSessions == startBoardPositionPushingSessions - newBoardPositionPushingSessions &&
                                        forwardMoves == startBoardPositionMoves - newBoardPositionMoves) {

                                    /* Calculate the new metrics of the board position, that is: AFTER the pull. */
                                    newBoardPositionMoves++;
                                    newBoardPositionPushes++;

                                    // Create a new board position to be added to the open queue.
                                    BoardPositionWithPushDirection newBoardPosition = new BoardPositionWithPushDirection(newBoardPositionMoves,
                                            newBoardPositionPushes, 0, 0, newBoardPositionPushingSessions,
                                            newBoxConfigurationIndex, newPlayerPosition, direction, currentBoardPosition);

                                    openQueue.add(newBoardPosition);

                                    break playerPositionBFS; // we only search any path regarding pushing sessions and moves
                                }
                            }

                            boxConfiguration.moveBox(newBoxPosition, boxPosition);        // Undo the pull to reuse the box configuration for the next pull.

                        } // Loop that continues moving the player to all 4 directions until all positions have been visited
                    } // Loop that moves the player to all reachable positions
                }

                // Reconstruct the found path and return an array list of the found board positions.
                ArrayList<BoardPosition> foundPath = new ArrayList<>();

                for (BoardPositionWithPushDirection currentBoardPosition = bestEndBoardPosition;
                     currentBoardPosition != null && currentBoardPosition.previous != null; currentBoardPosition = currentBoardPosition.previous) {

                    // Note: The search saved the board positions AFTER the pull! However,
                    // we need a forward path, which means the board positions after
                    // a push has been done - which, on the other hand, means BEFORE the pull.

                    BoardPosition newBoardPosition = currentBoardPosition;

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);
                    int newPlayerPosition = playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPosition.playerPosition];
                    int boxPosition = playerPositionToBoxPosition[newPlayerPosition];
                    int newBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][newPlayerPosition]];
                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                    newBoardPosition = new BoardPosition(0, 0, 0, 0, 0, boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration), newPlayerPosition);

                    foundPath.add(newBoardPosition);
                }

                return foundPath;
            }
        }
    }


    /**
     * Class for searching for a solution by only using the generated box configurations.<br>
     * The new solution is optimized for: <br>
     * 1. pushing sessions<br>
     * 2. pushes<br>
     * Before this method is called the box configurations to be used must have been generated.
     */
    private class PushingSessionsPushesOptimizer extends AllMetricsOptimizer {

        private final AtomicInteger pushingSessionsDepthSearch = new AtomicInteger(-1);

        protected volatile int newBestSolutionPushingSessionsCount = -1;
        protected int pushingSessionImprovement = 0;
        private final SolutionReconstructionPushingSessionsPushes solutionReconstruction = new SolutionReconstructionPushingSessionsPushes();

        private int internalPlayerStartPosition = -1;

        LevelSolvedCheck levelSolvedCheck;

        /**
         * Optimizes the passed solution by only using the generated box configurations.<br>
         * The path is optimal regarding: <br>
         * 1. pushing sessions<br>
         * 2. pushes<br>
         *
         * @param initialBoard        the board at level start
         * @param endBoard            the end board having all boxes on a goal
         * @param currentBestSolution the currently best solution
         * @return the best found solution
         */
        public OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentBestSolution) {

            // Used to store the real positions of the boxes of a box configuration.
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Get the index of the initial box configuration.
            packBoxConfiguration(boxConfiguration, initialBoard);
            startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            levelSolvedCheck = new LevelSolvedCheck(endBoard);


            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            // Get a list of all board positions of the solution.
            boardPositionsSolutionToBeOptimized = getSolutionBoardPositions(currentBestSolution);
            // Yet no solution has been found. However, the current best solution is used as upper bound for the pushing sessions and moves.
            BoardPosition endBoardPosition = boardPositionsSolutionToBeOptimized.get(boardPositionsSolutionToBeOptimized.size() - 1);
            newBestSolutionPushingSessionsCount = endBoardPosition.pushingSessions;
            newBestSolutionPushesCount = endBoardPosition.pushes;

            currentBestSolution.movesCount = endBoardPosition.moves;
            currentBestSolution.pushesCount = endBoardPosition.pushes;
            currentBestSolution.boxChanges = endBoardPosition.boxChanges;
            currentBestSolution.boxLines = endBoardPosition.boxLines;
            currentBestSolution.pushingSessions = endBoardPosition.pushingSessions;

            this.solutionToBeOptimized = currentBestSolution;

            // The order value is calculated:
            // pushingSessionCount * (estimated maximum number of pushes+1) + pushesCount
            // This optimizer just knows that the value will change by 1 box line (which is multiplied by the estimated
            // maximum number of pushes) and x pushes.
            // x is estimated not to be higher than 2 times the highest number of pushes in one pushingSession of the solution to be optimized.
            // We estimate the new solution not having more than 4 times the number of pushes than the solution to be optimized.
            final int estimatedMaximumNewPushesCount = 4 * currentBestSolution.pushesCount;
            final int maximumOrderValueChange = 1 * (estimatedMaximumNewPushesCount + 1) + estimatedMaximumNewPushesCount;    // 1 pushing session and x pushes
            final int minimumOrderValueChange = 1;                                                                        // 0 pushing sessions and 1 push

            boardPositionStorage = new BoardPositionsStorage(boxConfigurationStorage.getSize(), playerSquaresCount, 2, estimatedMaximumNewPushesCount, estimatedMaximumNewPushesCount);

            openQueueForward = PriorityQueueOptimizer.getInstance(minimumOrderValueChange, maximumOrderValueChange, boardPositionStorage, maxCPUsToBeUsedCount);


            // Add the start board position for the forward search to the open queue
            // and store it in the reachedPositionsStorage for detecting duplicates.
            internalPlayerStartPosition = playerExternalToInternalPosition[initialBoard.playerPosition];

            // If we don't optimize a pushes range, there is no prefix push. In this case, we can take any axis as start,
            // because the first push is counted as new box change/box line anyway.
            int firstPushAxis = prefixAxisOfLastPush == NONE ? AXIS_HORIZONTAL : prefixAxisOfLastPush;

            long indexStartBoardPosition = boardPositionStorage.addIfBetter(0, 0, startBoxConfigurationIndex, (internalPlayerStartPosition << 1) | firstPushAxis, SearchDirection.FORWARD);
            openQueueForward.add(indexStartBoardPosition);

            // Execute the optimizer searches.
            final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
            for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                executor.execute(new PushingSessionsPushesForwardSearch(threadNo));
            }

            Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            if (Debug.isDebugModeActivated) {
                debugDisplayStatistic();
            }

            // Inform the user about the RAM that is available after the search has ended.
            optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMinMiB()));

            // The optimizer has only been stopped to start a new iteration with the new best found solution.
            // Hence, the optimizer is still running.
            if (optimizerStatus == OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION) {
                optimizerStatus = RUNNING;
            }

            // Return the best found solution (or null if no solution has been found, due
            // to stop of the optimizer by user, out of memory, ...)
            return getBestFoundSolution();
        }


        @Override
        public OptimizerSolution getBestFoundSolution() {
            synchronized (mainOptimizerThread) {
                return solutionReconstruction.getBestSolution();
            }
        }

        /**
         * A forward search considering first pushing sessions, second pushes.
         */
        private class PushingSessionsPushesForwardSearch implements Runnable {

            /**
             * Unique ID for every thread for accessing the priority queue.
             */
            private final int priorityQueueThreadID;

            /**
             * Creates a {@code Runnable} that performs a forward search for the optimizer.
             *
             * @param priorityQueueThreadID unique ID for the priority queue
             */
            public PushingSessionsPushesForwardSearch(int priorityQueueThreadID) {
                this.priorityQueueThreadID = priorityQueueThreadID;
            }

            /**
             * Starts a new forward search.
             */
            @Override
            public void run() {

                try {
                    forwardSearch();
                } catch (OutOfMemoryError e) {
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }

            /**
             * Forward search searching from the start board position forward
             * (-> pushing boxes) until it meets the backward search.
             */
            private void forwardSearch() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    long currentBoardPositionIndex = openQueueForward.removeFirst(priorityQueueThreadID);
                    if (currentBoardPositionIndex == NONE) {
                        break;
                    }

                    // Mark as processed - BEFORE the real metrics are read. This ensures that when
                    // the board position is reached better in the meantime, the new better values are read.
                    boardPositionStorage.markAsProcessed(currentBoardPositionIndex);

                    boardPositionStorage.fillBoardPosition(currentBoardPositionIndex, boardPosition);

                    int axis = boardPosition.playerPosition & 1; // Axis is stored in the first bit
                    boardPosition.playerPosition >>>= 1;         // Calculate the real player position (axis is stored in the first bit)

                    int pushingSessionDepth = boardPosition.primaryMetric;
                    int pushesDepth = boardPosition.secondaryMetric;
                    int currentBoxConfigurationIndex = boardPosition.boxConfigurationIndex;

                    int currentDepth = pushingSessionsDepthSearch.get();
                    if (pushingSessionDepth > currentDepth) {
                        if (pushingSessionDepth > newBestSolutionPushingSessionsCount) {
                            continue;
                        }
                        if (pushingSessionsDepthSearch.compareAndSet(currentDepth, pushingSessionDepth)) {
                            checkForImprovement();
                        }
                    }

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                    playerPositions.addFirst(boardPosition.playerPosition, 0);

                    // Breadth-First-Search over all reachable player positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Is the neighbor position of the box no deadlock square and not blocked by another box?
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        // The first push is always a new pushing sessions unless we optimize a pushesRange,
                                        // in which case there is a previous push whose axis has been passed to the optimizer.
                                        int newPushingSessions = boardPosition.primaryMetric;
                                        if ((pushesDepth == 0 && prefixAxisOfLastPush == NONE) || playerPositions.movesDepth > 0) {
                                            newPushingSessions++;
                                        }

                                        int newPushesDepth = boardPosition.secondaryMetric + 1;

                                        int newPlayerPositionWithAxis = (newPlayerPosition << 1) | getAxisOfDirection(direction); // player position is shifted to store the axis, too

                                        long addedBoardPositionIndex = boardPositionStorage.addIfBetter(newPushingSessions, newPushesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, SearchDirection.FORWARD);
                                        if (addedBoardPositionIndex != BoardPositionsStorage.NONE) {
                                            if (levelSolvedCheck.isTargetBoardPosition(boxConfigurationIndex, newPlayerPosition)) {
                                                collectBestSolutions(newPushingSessions, newPushesDepth, boxConfigurationIndex, newPlayerPositionWithAxis, direction);
                                            } else {
                                                openQueueForward.add(addedBoardPositionIndex);
                                            }
                                        }
                                    }
                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }
            }

            /**
             * This method informs the user whether a better path to any of the board positions of the solution to be optimized
             * has been found and about the current pushing sessions depth that the search has reached.
             */
            private void checkForImprovement() {

                final StorageBoardPosition boardPosition = new StorageBoardPosition();

                // Identify the board position having the highest number of pushes that has already been reached by
                // the forward search and is part of the basis solution to be optimized and check for any improvements.
                // Ignore the last board position because there are levels that start with all boxes on goals.
                // For these levels start and end board position are the same.
                for (int index = boardPositionsSolutionToBeOptimized.size() - 1; --index != -1; ) {

                    BoardPosition basisSolutionBoardPosition = boardPositionsSolutionToBeOptimized.get(index);

                    // Check whether the board position has already been reached by the forward search.
                    long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(basisSolutionBoardPosition, SearchDirection.FORWARD);
                    if (boardPositionIndex != BoardPositionsStorage.NONE) {

                        boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);

                        // Calculate how much better the board position has been reached compared to the basis solution.
                        int pushingSessionsDif = basisSolutionBoardPosition.pushingSessions - boardPosition.primaryMetric;
                        int pushesDif = basisSolutionBoardPosition.pushes - boardPosition.secondaryMetric;

                        synchronized (mainOptimizerThread) {
                            if (pushingSessionsDif > pushingSessionImprovement ||
                                    pushingSessionsDif == pushingSessionImprovement && pushesDif > pushesImprovementForwardSearch) {
                                pushingSessionImprovement = pushingSessionsDif;
                                pushesImprovementForwardSearch = pushesDif;

                                if (immediateNewIterationWhenImprovement && optimizerStatus == RUNNING) {
                                    optimizerStatus = OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION;
                                }
                            }

                            // Save the last visited board position of the original solution if it has been reached on the currently best known path.
                            if (pushingSessionImprovement == pushingSessionsDif && pushesImprovementForwardSearch == pushesDif) {
                                lastVisitedBoardPositionOfOriginalSolutionForward = basisSolutionBoardPosition;
                            }
                        }

                        break;
                    }
                }

                displayIntermediateResult();
            }
        }

        /**
         * Returns a list of all board positions the passed solution
         * consists of. The board positions contain information
         * about with which metrics they have in the solution (moves, pushes, ...)
         *
         * @param currentBestSolution solution whose board positions are to be returned
         * @return the board positions of the passed solution
         */
        @Override
        protected ArrayList<BoardPosition> getSolutionBoardPositions(final OptimizerSolution currentBestSolution) {

            return getBoardPositions(initialBoard, currentBestSolution.solution, prefixAxisOfLastPush)
                    .map(bp -> {
                        int playerPositionWithAxis = (bp.playerPosition << 1) | getAxisOfDirection(bp.pushDirection);
                        return new BoardPosition(bp.moves, bp.pushes, bp.boxChanges, bp.boxChanges, bp.pushingSessions, bp.boxConfigurationIndex, playerPositionWithAxis);
                    }).collect(toCollection(ArrayList::new));
        }

        /**
         * Calculates and displays the current pushing session depth of the search and the current best solution metrics.
         */
        private void displayIntermediateResult() {

            synchronized (mainOptimizerThread) {

                int newPushingSessionCount = solutionToBeOptimized.pushingSessions - pushingSessionImprovement;
                int newPushesCount = solutionToBeOptimized.pushesCount - pushesImprovementForwardSearch;

                // Set the new metrics when the new best solution has been found.
                if (newPushingSessionCount < newBestSolutionPushingSessionsCount || newPushingSessionCount == newBestSolutionPushingSessionsCount && newPushesCount < newBestSolutionPushesCount) {
                    newBestSolutionPushingSessionsCount = newPushingSessionCount;
                    newBestSolutionPushesCount = newPushesCount;
                }

                // Display a status message to show the current depth and the current found improvement.
                // If only a specific pushes range of the whole solution is optimized,
                // only the metrics of this pushes range are displayed (without the prefix and suffix moves
                // that must be added to get the whole solution).
                optimizerGUI.setInfoText(
                        Texts.getText("vicinitySearchPushingSessionDepth")
                                + pushingSessionsDepthSearch.get()
                                + "    "
                                + Texts.getText("bestSolution") + ": "
                                + newBestSolutionPushingSessionsCount
                                + "/"
                                + newBestSolutionPushesCount
                                + " (" + (-pushingSessionImprovement) + "/" + (-pushesImprovementForwardSearch) + ")"
                );
            }
        }

        /**
         * Checks whether the search has found a new best solution. If yes, then the data of this new solution is saved.
         *
         * @param pushingSessionDepth   number of pushing sessions of the search direction that has found the solution
         * @param pushesDepth           number of pushes of the search direction that has found the solution
         * @param boxConfigurationIndex index of the box configuration
         * @param playerPosition        player position of the board position
         * @param lastMoveDirection     direction of the last move
         */
        private void collectBestSolutions(
                final int pushingSessionDepth,
                final int pushesDepth,
                final int boxConfigurationIndex,
                final int playerPosition,
                final int lastMoveDirection) {

            // Check if the new solution is at least as good as the current best one. Synchronized because the metrics of
            // the current best solution may otherwise change in the meantime.
            synchronized (mainOptimizerThread) {
                if (pushingSessionDepth < newBestSolutionPushingSessionsCount ||
                        pushingSessionDepth == newBestSolutionPushingSessionsCount && pushesDepth <= newBestSolutionPushesCount) {

                    // Only solutions having the best metrics have to be reconstructed. If this solution is a new best solution,
                    // then the other solutions (represented by the meeting point) can be deleted.
                    if (pushingSessionDepth < newBestSolutionPushingSessionsCount || pushesDepth < newBestSolutionPushesCount) {
                        solutionReconstruction.removeAllEndBoardPositions();

                        newBestSolutionPushingSessionsCount = pushingSessionDepth;
                        newBestSolutionPushesCount = pushesDepth;

                        pushingSessionImprovement = solutionToBeOptimized.pushingSessions - newBestSolutionPushingSessionsCount;
                        pushesImprovementForwardSearch = solutionToBeOptimized.pushesCount - newBestSolutionPushesCount;

                        displayIntermediateResult();
                    }

                    solutionReconstruction.addEndBoardPosition(boxConfigurationIndex, playerPosition, lastMoveDirection);

                    if (Debug.isDebugModeActivated) {
                        optimizerGUI.addLogTextDebug("New best solution found: PushingSessions/Pushes: " + (newBestSolutionPushingSessionsCount) + "/" + (newBestSolutionPushesCount));
                    }
                }
            }
        }

        /**
         * The main optimizer search stores all board positions in the storage.
         * This class does a new search over the stored board positions to reconstruct the best solution
         * regarding first pushing sessions and second pushes.
         */
        protected class SolutionReconstructionPushingSessionsPushes {

            private final Set<EndBoardPositionData> endBoardPositionSet = ConcurrentHashMap.newKeySet();

            private final AtomicReference<OptimizerSolution> bestFoundSolution = new AtomicReference<>();
            private final Comparator<BoardPosition> COMPARATOR;

            /**
             * Creates a reconstruction object used to search the best solution by using only the
             * board positions generated by the optimizer vicinity search.<br>
             * The search considers all 5 metrics. Which solution is the best is determined by
             * the passed comparator.
             */
            public SolutionReconstructionPushingSessionsPushes() {
                COMPARATOR = (boardPosition1, boardPosition2) -> {
                    if (boardPosition1 == null && boardPosition2 == null) {
                        return 0;
                    }
                    if (boardPosition1 == null) {
                        return +1;
                    }
                    if (boardPosition2 == null) {
                        return -1;
                    }
                    return Utilities.intComparePairs(
                            boardPosition1.pushingSessions, boardPosition2.pushingSessions,
                            boardPosition1.pushes, boardPosition2.pushes);
                };
            }

            /**
             * Stores an end board position.
             *
             * @param boxConfigurationIndex the index of the box configuration where the searches have met
             * @param playerPosition        player position of the board position where the searches have met
             * @param moveDirection         direction of the last move
             */
            public void addEndBoardPosition(int boxConfigurationIndex, int playerPosition, int moveDirection) {
                EndBoardPositionData meetingData = new EndBoardPositionData(boxConfigurationIndex, playerPosition, moveDirection);
                endBoardPositionSet.add(meetingData);
            }

            /**
             * Deletes all stored meeting points.
             */
            public void removeAllEndBoardPositions() {
                endBoardPositionSet.clear();
            }

            /**
             * Determines the best solution that can be constructed using the board positions
             * that have been stored during the vicinity search. The best solution is found
             * by performing a complete new search considering all 5 metrics.
             *
             * @return the best found solution
             */
            public OptimizerSolution getBestSolution() {

                // Backup the optimizer status since it may be set back to running.
                OptimizerStatus optimizerStatusBackup = optimizerStatus;

                // When the optimizer search couldn't finish normally, we can nevertheless check whether the search has already found
                // a better solution. Hence, the optimizer is set back to "running" for the reconstruction.
                if (optimizerStatus == OptimizerStatus.STOPPED_BY_USER || optimizerStatus == OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY) {
                    optimizerStatus = RUNNING;
                }

                // If the optimizer hasn't found an end board position (user stopped the search, out of memory error, ...),
                // the solution is reconstructed by filling the gap between the last found board position and the end board position
                // with board positions of the original solution. Since in this case there is always just one solution only one thread needs to do the work.
                if (endBoardPositionSet.isEmpty()) {

                    // The board positions of the new best found solution.
                    ArrayList<BoardPosition> newSolutionBoardPositions = new ArrayList<>();

                    // Add all board positions of the solution which the search has found. Sanity check: in the rare case
                    // that the search stops before the first board position of the original solution has been reached, there is nothing to reconstruct.
                    if (lastVisitedBoardPositionOfOriginalSolutionForward != null) {
                        newSolutionBoardPositions = backwardReconstruction(
                                new EndBoardPositionData(lastVisitedBoardPositionOfOriginalSolutionForward.boxConfigurationIndex,
                                        lastVisitedBoardPositionOfOriginalSolutionForward.playerPosition,
                                        getMoveDirection(lastVisitedBoardPositionOfOriginalSolutionForward)));
                    }

                    // If the forward search hasn't reached any board position of the basis solution, we have to fill the gap
                    // beginning by the very first board position of the basis solution we have => set the flag to true.
                    boolean forwardBoardPositionReached = lastVisitedBoardPositionOfOriginalSolutionForward == null;
                    for (BoardPosition basisSolutionBoardPosition : boardPositionsSolutionToBeOptimized) {

                        // The first relevant board position is the one after the last visited board position of the search.
                        if (!forwardBoardPositionReached && basisSolutionBoardPosition.equals(lastVisitedBoardPositionOfOriginalSolutionForward)) {
                            forwardBoardPositionReached = true;
                            continue;
                        }

                        // Add board positions that are missing for a complete solution.
                        if (forwardBoardPositionReached) {
                            BoardPosition boardPosition = new BoardPosition(0, 0, 0, 0, 0, basisSolutionBoardPosition.boxConfigurationIndex, basisSolutionBoardPosition.playerPosition >>> 1);
                            newSolutionBoardPositions.add(boardPosition);
                        }

                    }

                    // Create the new solution and set it as the best new-found solution.
                    OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);
                    bestFoundSolution.set(newSolution);
                } else {
                    reconstructFoundSolutions();
                }

                // Set back the old optimizer status if the optimizer hasn't been stopped.
                if (optimizerStatus == RUNNING) {
                    optimizerStatus = optimizerStatusBackup;
                }

                return bestFoundSolution.get();
            }

            /**
             * Returns the direction of the move performed to reach the passed board position.
             *
             * @param boardPosition the board position
             * @return the direction of the move
             */
            private int getMoveDirection(BoardPosition boardPosition) {

                int axisOfMove = boardPosition.playerPosition & 1;
                int moveDirection = 0;
                for (; moveDirection < 4; moveDirection++) {
                    int playerNeighbor, boxPosition;
                    if (getAxisOfDirection(moveDirection) == axisOfMove
                            && (playerNeighbor = playerSquareNeighbor[moveDirection][boardPosition.playerPosition >>> 1]) != NONE
                            && (boxPosition = playerPositionToBoxPosition[playerNeighbor]) != NONE
                            && boxConfigurationStorage.isBoxAtPosition(boardPosition.boxConfigurationIndex, boxPosition)) {
                        return moveDirection;
                    }
                }

                return NONE;
            }

            /**
             * Reconstructs the solutions from the found end board positions.
             */
            private void reconstructFoundSolutions() {

                final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
                final ConcurrentLinkedQueue<EndBoardPositionData> meetingBoardPositions = new ConcurrentLinkedQueue<>(endBoardPositionSet);

                final Runnable reconstruction = () -> {
                    EndBoardPositionData meetingData = null;

                    // Reconstruct and check solutions until all data have been processed.
                    while ((meetingData = meetingBoardPositions.poll()) != null && optimizerStatus == RUNNING) {

                        long time = System.currentTimeMillis();

                        ArrayList<BoardPosition> newSolutionBoardPositions = backwardReconstruction(meetingData);

                        // If the optimizer has been stopped, not all board positions have been reconstructed and therefore the solution isn't valid.
                        if (optimizerStatus == RUNNING) {

                            OptimizerSolution newSolution = createSolutionFromBoardPositions(newSolutionBoardPositions);

                            // Check whether this is the new best solution.
                            OptimizerSolution currentBestSolution = null;
                            do {
                                currentBestSolution = bestFoundSolution.get();
                            } while (newSolution.isBetterPushingSessionsPushesThan(currentBestSolution) && !bestFoundSolution.compareAndSet(currentBestSolution, newSolution));

                            if (Debug.isDebugModeActivated) {
                                optimizerGUI.addLogTextDebug("Solution reconstruction time: " + (System.currentTimeMillis() - time) + "  Solution: " + newSolution);
                            }
                        }
                    }
                };

                for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                    executor.execute(reconstruction);
                }
                Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            }

            /**
             * Get the pushing sessions value from the passed board position.
             *
             * @param boardPosition board position to extract the number of pushingSessions
             * @return the number of pushing sessions
             */
            private int getPushingSessions(StorageBoardPosition boardPosition) {
                return boardPosition.primaryMetric;
            }


            /**
             * Get the pushes value from the passed board position.
             *
             * @param boardPosition board position to extract the number of pushes from
             * @return the number of pushes
             */
            private int getPushes(StorageBoardPosition boardPosition) {
                return boardPosition.secondaryMetric;
            }


            /**
             * A backward search for reconstructing the best solution by using the
             * saved board positions from the forward search.
             * <p>
             * The forward search has saved the reached board positions and its
             * metrics in the board position storage. The end board position of
             * the forward search is used to reconstruct the solution path.
             *
             * @param meetingBoardPositionData data of an end board position
             * @return {@code OptimizerBoardPosition}s of the found path to the start board position
             */
            private ArrayList<BoardPosition> backwardReconstruction(EndBoardPositionData meetingBoardPositionData) {

                final PriorityQueue<BoardPositionWithPushDirection> openQueue = new PriorityQueue<>(10000, COMPARATOR);

                // Add the board position as start for the search.
                int pullDirection = getOppositeDirection(meetingBoardPositionData.lastMoveDirection);    // we like to pull instead of push
                final BoardPositionWithPushDirection startBoardPosition = new BoardPositionWithPushDirection(0, 0, 0, 0, 0, meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition >>> 1, pullDirection, null);
                openQueue.add(startBoardPosition);


                // Set the pushing sessions and moves of the board position this search starts with.
                final StorageBoardPosition boardPosition = new StorageBoardPosition();
                long boardPositionIndex = boardPositionStorage.getBoardPositionIndex(meetingBoardPositionData.boxConfigurationIndex, meetingBoardPositionData.playerPosition, SearchDirection.FORWARD);
                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                int startBoardPositionPushingSessions = getPushingSessions(boardPosition);
                int startBoardPositionPushes = getPushes(boardPosition);

                // Last board position of the best found path to the initial board position of the level.
                BoardPositionWithPushDirection bestEndBoardPosition = null;

                if (startBoardPositionPushes <= 1) {
                    return new ArrayList<>(Collections.singletonList(startBoardPosition));
                }

                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

                while (optimizerStatus == RUNNING) {

                    BoardPositionWithPushDirection currentBoardPosition = openQueue.poll();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);

                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition, playerPositionOppositeDirection;

                    int currentBoardPositionPlayerPosition = currentBoardPosition.playerPosition;

                    // Add the start player position and set the current moves-depth.
                    playerPositions.addFirst(currentBoardPositionPlayerPosition, 0);

                    playerPositionBFS:
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // This backward search can't always reach the start board position because the player
                        // needn't be next to a box in the start board position. Hence, the last board position
                        // to search for is the one having one push. The initial board position (having 0 pushes)
                        // is already known in advance and can therefore be discarded.
                        // => If we have reached the start box configuration by the last push, then the current
                        // board position must be the one having 1 push.
                        if (currentBoardPosition.boxConfigurationIndex == startBoxConfigurationIndex && playerPosition == internalPlayerStartPosition
                                && currentBoardPosition.pushes > 0) {        // There exist levels which starts with a solved board position -> check for at least one done push
                            // Check whether the new board position has been reached better than the current
                            // best end board position. If yes, then set the new board position as the new best one.
                            if (COMPARATOR.compare(currentBoardPosition, bestEndBoardPosition) < 0) {
                                bestEndBoardPosition = currentBoardPosition;
                            }

                            continue;
                        }

                        // Try to move the player to every direction and check whether it's possible to do pulls.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall or a box.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                    (boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {
                                continue;
                            }

                            // The player can move to the new position => store the new position in the queue (if it hasn't been reached, yet).
                            playerPositions.addIfNew(newPlayerPosition);

                            // Check whether there is a box that can be pulled by moving to the new player position. If not, continue with the next move.
                            if ((playerPositionOppositeDirection = playerSquareNeighbor[getOppositeDirection(direction)][playerPosition]) == NONE ||  // position next to the player where a box might be
                                    (boxPosition = playerPositionToBoxPosition[playerPositionOppositeDirection]) == NONE ||                         // position of a possible box to be pulled
                                    !boxConfiguration.isBoxAtPosition(boxPosition) ||                                                         // there is a box next to the player
                                    (newBoxPosition = playerPositionToBoxPosition[playerPosition]) == NONE) {                                         // valid box position
                                continue;
                            }

                            // Pull is possible => do the pull.
                            boxConfiguration.moveBox(boxPosition, newBoxPosition);

                            int newBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

                            final int playerPositionWithAxis = (playerPosition << 1) | getAxisOfDirection(direction);

                            // The box configuration must be one of the board positions found during the vicinity search.
                            if (newBoxConfigurationIndex != NONE &&
                                    (boardPositionIndex = boardPositionStorage.getBoardPositionIndex(currentBoardPosition.boxConfigurationIndex, playerPositionWithAxis, SearchDirection.FORWARD)) != BoardPositionsStorage.NONE) {

                                boardPositionStorage.fillBoardPosition(boardPositionIndex, boardPosition);
                                int forwardPushingSessions = getPushingSessions(boardPosition);
                                int forwardPushes = getPushes(boardPosition);

                                // Calculate the pushing sessions and pushes of the board position directly BEFORE the pull has been made!
                                // This must be done to compare them with the stored board positions of the forward search,
                                // which has stored the board positions AFTER a push has been made.
                                int newBoardPositionMoves = currentBoardPosition.moves + playerPositions.movesDepth;
                                int newBoardPositionPushes = currentBoardPosition.pushes;
                                int newBoardPositionPushingSessions = currentBoardPosition.pushingSessions;

                                if (playerPositions.movesDepth > 0) {
                                    newBoardPositionPushingSessions++;
                                }

                                // Check if this board position is on the best found path (-> having the correct metrics).
                                if (forwardPushingSessions == startBoardPositionPushingSessions - newBoardPositionPushingSessions &&
                                        forwardPushes == startBoardPositionPushes - newBoardPositionPushes) {

                                    /* Calculate the new metrics of the board position, that is: AFTER the pull. */
                                    newBoardPositionMoves++;
                                    newBoardPositionPushes++;

                                    // Create a new board position to be added to the open queue.
                                    BoardPositionWithPushDirection newBoardPosition = new BoardPositionWithPushDirection(newBoardPositionMoves,
                                            newBoardPositionPushes, 0, 0, newBoardPositionPushingSessions,
                                            newBoxConfigurationIndex, newPlayerPosition, direction, currentBoardPosition);

                                    openQueue.add(newBoardPosition);

                                    break playerPositionBFS; // we only search any path regarding pushing sessions and pushes
                                }
                            }

                            boxConfiguration.moveBox(newBoxPosition, boxPosition);        // Undo the pull to reuse the box configuration for the next pull.

                        } // Loop that continues moving the player to all 4 directions until all positions have been visited
                    } // Loop that moves the player to all reachable positions
                }

                // Reconstruct the found path and return an array list of the found board positions.
                ArrayList<BoardPosition> foundPath = new ArrayList<>();

                for (BoardPositionWithPushDirection currentBoardPosition = bestEndBoardPosition;
                     currentBoardPosition != null && currentBoardPosition.previous != null; currentBoardPosition = currentBoardPosition.previous) {

                    // Note: The search saved the board positions AFTER the pull! However,
                    // we need a forward path, which means the board positions after
                    // a push has been done - which, on the other hand, means BEFORE the pull.

                    BoardPosition newBoardPosition = currentBoardPosition;

                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoardPosition.boxConfigurationIndex);
                    int newPlayerPosition = playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][currentBoardPosition.playerPosition];
                    int boxPosition = playerPositionToBoxPosition[newPlayerPosition];
                    int newBoxPosition = playerPositionToBoxPosition[playerSquareNeighbor[getOppositeDirection(currentBoardPosition.pushDirection)][newPlayerPosition]];
                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                    newBoardPosition = new BoardPosition(0, 0, 0, 0, 0, boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration), newPlayerPosition);

                    foundPath.add(newBoardPosition);
                }

                return foundPath;
            }
        }
    }

    /**
     * Class for searching for a better solution by only using the generated
     * box configurations.<br>
     * The new solution is optimized for maximum untouched boxes.
     * This means as many boxes stay on their initial positions (on a goal) as possible.
     * <p>
     * Before this method is called the box configurations to be used must
     * have been generated.
     */
    private class MaximumUntouchedBoxesOptimizer extends AllMetricsOptimizer {

        private static class State {
            int boxConfigurationIndex = 0;
            int playerPosition = 0;
            int[] untouchedBoxPositions = null;
            State previousState = null;


            public State(int boxConfigurationIndex, int playerPosition, int[] untouchedBoxPositions, State previousState) {
                this.boxConfigurationIndex = boxConfigurationIndex;
                this.playerPosition = playerPosition;
                this.untouchedBoxPositions = untouchedBoxPositions;
                this.previousState = previousState;
            }

            public int untouchedBoxCount() {
                return untouchedBoxPositions.length;
            }

            @Override
            public boolean equals(Object o) {
                if (o == null || getClass() != o.getClass()) return false;
                State state = (State) o;
                return boxConfigurationIndex == state.boxConfigurationIndex && playerPosition == state.playerPosition;
            }

            @Override
            public int hashCode() {
                return Objects.hash(boxConfigurationIndex, playerPosition);
            }
        }

        private static class OpenQueue {

            ConcurrentLinkedQueue<State>[] openQueue = null;

            public OpenQueue(int boxCount) {
                openQueue = new ConcurrentLinkedQueue[boxCount + 1];
                for (int i = 0; i <= boxCount; i++) {
                    openQueue[i] = new ConcurrentLinkedQueue<>();
                }
            }

            public void add(State state) {
                openQueue[state.untouchedBoxCount()].add(state);
            }

            /**
             * Removes and returns the state having the highest number of untouched boxes.
             */
            public State removeBest() {
                for (int untouchedBoxCount = openQueue.length - 1; untouchedBoxCount != -1; untouchedBoxCount--) {
                    State state = openQueue[untouchedBoxCount].poll();
                    if (state != null)
                        return state;
                }
                return null;
            }
        }

        protected final AtomicInteger currentUntouchedBoxesDepth = new AtomicInteger(Integer.MAX_VALUE);

        AtomicReference<State> bestFoundSolutionState = new AtomicReference<>(null);

        LevelSolvedCheck levelSolvedCheck;

        // All states of the solution to be optimized. This is only used for displaying a "percentage of processed" progress.
        List<State> statesOfSolutionToBeOptimized = new ArrayList<>();

        ConcurrentHashMap<State, State> transpositionTable = null;
        AtomicInteger transpositionTableSize = new AtomicInteger(1); // 1 due to the start board position

        int bestKnownSolutionUntouchedBoxCount = Integer.MAX_VALUE; // all states having fewer untouched boxes can be discarded

        OpenQueue openQueue = null;

        /**
         * Optimizes the passed solution by only using the generated box configurations.
         *
         * @param initialBoard        the board at level start
         * @param endBoard            the end board having all boxes on a goal
         * @param currentBestSolution the currently best solution
         * @return the best found solution
         */
        public OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentBestSolution) {

            // Save the passed solution, so it can be accessed in all methods.
            solutionToBeOptimized = currentBestSolution;

            // Used to store the real box positions of the box configuration.
            // Usually just the index of a box configuration in the global array
            // is used, but for doing pushes it's necessary to copy the real positions in this byte array.
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Get the index of the initial box configuration.
            packBoxConfiguration(boxConfiguration, initialBoard);
            startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
            int topLeftReachingPlayerPositionInitialBoard = getTopLeftReachablePlayerPosition(playerExternalToInternalPosition[initialBoard.playerPosition], boxConfiguration);

            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            levelSolvedCheck = new LevelSolvedCheck(endBoard);

            // Get a list of all board positions of the solution.
            boardPositionsSolutionToBeOptimized = getSolutionBoardPositions(currentBestSolution);

            statesOfSolutionToBeOptimized = boardPositionsSolutionToBeOptimized.stream().map(
                boardPosition -> new State(boardPosition.boxConfigurationIndex, boardPosition.playerPosition, new int[0], null)
                         ).toList();
            ArrayList<Integer> untouchedBoxPositions = getPositionsOfBoxesOnGoals(initialBoard);
            int[] untouchedBoxInternalPositions = untouchedBoxPositions.stream().mapToInt(Integer::intValue).map(position -> boxExternalToInternalPosition[position]).toArray();

            openQueue = new OpenQueue(untouchedBoxInternalPositions.length);
            transpositionTable = getTranspositionTable(untouchedBoxInternalPositions.length);

            bestKnownSolutionUntouchedBoxCount = createSolutionFromBoardPositions(boardPositionsSolutionToBeOptimized).untouchedBoxCount;   // set the really untouched boxes as best known untouched value

            State startBoardPosition = new State(startBoxConfigurationIndex, topLeftReachingPlayerPositionInitialBoard, untouchedBoxInternalPositions, null);
            transpositionTable.put(startBoardPosition, startBoardPosition);
            openQueue.add(startBoardPosition);


            // Execute the optimizer searches.
            final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
            for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                executor.execute(new LocalSearch());
            }

            Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            // Inform the user about the RAM that is available after the search has ended.
            optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMinMiB()));

            if (bestFoundSolutionState.get() != null) {
                optimizerGUI.addLogText(Texts.getText("optimizer.untouchedBoxCount", bestFoundSolutionState.get().untouchedBoxCount()));
            }

            // The optimizer has only been stopped to start a new iteration with the new best found solution.
            // Hence, the optimizer is still running.
            if (optimizerStatus == OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION) {
                optimizerStatus = RUNNING;
            }

            if(Debug.isDebugModeActivated) {
                System.out.printf("Transposition table size: %,d\n", transpositionTable.size());
            }

            // Return the best found solution (or null if no solution has been found, when the
            // optimizer has been stopped by user, out of memory, ...)
            return getBestFoundSolution();
        }

        private static ArrayList<Integer> getPositionsOfBoxesOnGoals(OptimizerBoard initialBoard) {
            ArrayList<Integer> boxOnGoalPositions = new ArrayList<>();
            for (int i = 0; i < initialBoard.size; i++) {
                if (initialBoard.isBox(i) && initialBoard.isGoal(i)) {  // as start all boxes on goals are counted as not touched
                    boxOnGoalPositions.add(i);
                }
            }
            return boxOnGoalPositions;
        }

        private ConcurrentHashMap<State, State> getTranspositionTable(int untouchedBoxCount) {

            long maxUsableRAM = Utilities.getMaxUsableRAMInBytes();
            long targetRAM = (long) (0.9 * maxUsableRAM);   // 10% remain for open queue

            int stateSizeInRAM = 80 + 4 * untouchedBoxCount;

            double costPerSlot = 4 + 0.9f * stateSizeInRAM; // 4 byte for bucket-array, 0.9f load factor

            int estimatedCapacity = (int) Math.ceil(targetRAM / costPerSlot);

            int estimatedCapacityPowerOf2 = Integer.highestOneBit(estimatedCapacity); // reduce to the next lower power of 2 since the hash map works with powers of 2

            // Return a map that never resizes since this is too slow and memory consuming.
            return new ConcurrentHashMap<>(estimatedCapacityPowerOf2, 0.9f) {

                public State put(State key, State value) {
                    if (size() >= estimatedCapacityPowerOf2 - 10) {
                        optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                        stopOptimizer();
                    }
                    return super.put(key, value);
                }
            };
        }

        private class LocalSearch implements Runnable {

            /**
             * Starts a new forward search.
             */
            @Override
            public void run() {

                try {
                    // Start a new forward search.
                    forwardSearch();
                } catch (Exception ex) {
                    ex.printStackTrace();
                } catch (OutOfMemoryError e) {
                    // Stop the optimizer by setting the proper stop reason.
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }

            /**
             * Forward search searching from the start board position forward
             * (-> pushing boxes) until a solution has been found.
             */
            private void forwardSearch() {

                // Object holding all information about one board position.
                State currentBoardPosition = null;

                // Used to calculate the player-reachable positions.
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                // Used to store the real positions of the boxes of a box configuration. Usually just the index of a box configuration in
                // the global array is used, but for doing pushes it's necessary to copy the real positions in this byte array.
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);


                while (optimizerStatus == RUNNING) {

                    // Take board position from the queue.
                    currentBoardPosition = openQueue.removeBest();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null) {
                        break;
                    }
                    if (bestFoundSolutionState.get() != null && currentBoardPosition.untouchedBoxCount() <= bestFoundSolutionState.get().untouchedBoxCount()) {
                        break; // it's not possible to find a better solution
                    }

                    int currentBoxConfigurationIndex = currentBoardPosition.boxConfigurationIndex;

                    // In this optimizer we count down!
                    // So the user is informed about the solution getting worse and worse until
                    // a  solution is reached then having  the highest number of untouched boxes.
                    int currentDepth = currentUntouchedBoxesDepth.get();
                    int newDepth = currentBoardPosition.untouchedBoxCount();
                    if (newDepth < currentDepth) {
                        if (currentUntouchedBoxesDepth.compareAndSet(currentDepth, newDepth)) {
                            if (bestFoundSolutionState.get() == null || newDepth >= bestFoundSolutionState.get().untouchedBoxCount()) {
                                displayIntermediateResult();
                            }
                        }
                    }

                    playerPositions.addFirst(currentBoardPosition.playerPosition, 0);

                    // Old and new position of the player and a box.
                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    // Create a box configuration from the index of the box configuration.
                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                    // Breadth-first search over all reachable player positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // Try to move the player to every direction and do pushes if necessary.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Is the possible new position of the box no deadlock square and not blocked by another box?
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE && !boxConfiguration.isBoxAtPosition(newBoxPosition)) {

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        boolean newStateHasOneFewerUntouchedBoxes = Arrays.binarySearch(currentBoardPosition.untouchedBoxPositions, boxPosition) >= 0; // one of the yet untouched boxes has been touched
                                        if (!newStateHasOneFewerUntouchedBoxes || currentBoardPosition.untouchedBoxCount() - 1 >= bestKnownSolutionUntouchedBoxCount) { // new state is only relevant if it has at least as much untouched boxes as the best known solution

                                            int topLeftReachablePlayerPosition = getTopLeftReachablePlayerPosition(newPlayerPosition, boxConfiguration);

                                            State newBoardPosition = new State(boxConfigurationIndex, topLeftReachablePlayerPosition, currentBoardPosition.untouchedBoxPositions, currentBoardPosition);
                                            if (newStateHasOneFewerUntouchedBoxes) {
                                                newBoardPosition.untouchedBoxPositions = new int[currentBoardPosition.untouchedBoxCount() - 1];
                                                for (int i = 0, j = 0; i < currentBoardPosition.untouchedBoxCount(); i++) {
                                                    if (currentBoardPosition.untouchedBoxPositions[i] != boxPosition) {
                                                        newBoardPosition.untouchedBoxPositions[j++] = currentBoardPosition.untouchedBoxPositions[i];
                                                    }
                                                }
                                            }

                                            State storedState = transpositionTable.get(newBoardPosition);
                                            if (storedState == null) {
                                                addToTranspositionTable(newBoardPosition);

                                                if (levelSolvedCheck.isTargetBoardPosition(boxConfigurationIndex, newPlayerPosition)) {
                                                    collectBestSolutions(newBoardPosition);
                                                } else {
                                                    if (newBoardPosition.untouchedBoxCount() > 0) {
                                                        openQueue.add(newBoardPosition);
                                                    }
                                                }
                                            } else {
                                                if(newBoardPosition.untouchedBoxCount() > storedState.untouchedBoxCount()) {
                                                    addToTranspositionTable(newBoardPosition);
                                                    openQueue.add(newBoardPosition);
                                                }
                                            }
                                        }
                                    }

                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }
            }

            private void addToTranspositionTable(State newBoardPosition) {
                transpositionTable.put(newBoardPosition, newBoardPosition); // Another thread may have already stored a better state, but without synchronization it is faster :-)

                int storedStateCount = transpositionTableSize.incrementAndGet();
                if ((storedStateCount & 4095) == 0) {
                    if (Utilities.MemoryUsage.getMemoryUsagePercentage() > 85) {
                        optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                        return;
                    }
                    displayIntermediateResult();
                }
            }
        }

        /**
         * Calculates and displays the current pushes depth of the search and the current best solution metrics.
         */
        private void displayIntermediateResult() {
            for (int i = statesOfSolutionToBeOptimized.size() - 1; i != -1; i--) {
                State solutionState = statesOfSolutionToBeOptimized.get(i);

                State reachedState = transpositionTable.get(solutionState);
                if (reachedState != null) {
                    int percentageProcessed = (int) (100.0 * i / statesOfSolutionToBeOptimized.size());
                    optimizerGUI.setInfoText(Texts.getText("optimizer.untouchedBoxCountWithProcessPercentage", currentUntouchedBoxesDepth.get(), percentageProcessed));
                    return;
                }
            }
        }


        /**
         * Checks whether the search has found a new best solution. If yes, then the data of this new solution is saved.
         */
        private void collectBestSolutions(final State newState) {

            State bestState = null;
            do {
                bestState = bestFoundSolutionState.get();
                if (bestState != null) {
                    if (bestState.untouchedBoxCount() > newState.untouchedBoxCount()) {
                        return; // newState is not better
                    }
                }
            } while (!bestFoundSolutionState.compareAndSet(bestState, newState));

            bestKnownSolutionUntouchedBoxCount = newState.untouchedBoxCount(); // set new value so all states having fewer untouched boxes can be discarded (not thread safe, but it is not a big problem)
        }

        @Override
        protected OptimizerSolution getBestFoundSolution() {

            State endState = bestFoundSolutionState.get();

            ArrayList<State> allSolutionStates = new ArrayList<>();

            if (endState == null) {          // no solution found

                // Use the states of the solution to be optimized until one generated is found.
                // This may not return the best solution, but at least a solution.
                for (int i = statesOfSolutionToBeOptimized.size() - 1; i != -1; i--) {
                    State solutionState = statesOfSolutionToBeOptimized.get(i);
                    State reachedState = transpositionTable.get(solutionState);
                    if (reachedState == null) {
                        allSolutionStates.add(0, solutionState);
                    } else {
                        endState = reachedState;  // from here on the states found by the optimizer are used (which may be better than the original solution / but may also be worse).
                        break;
                    }
                }
            }

            if(endState == null) {
                return null;
            }

            // Reconstruct the solution path from the found best end state to the start state (excluding the start state which is the initial board position).
            for (State state = endState; state.previousState != null; ) {
                allSolutionStates.add(0, state);
                state = state.previousState;
            }

            ArrayList<BoardPosition> solutionBoardPositions =
                    allSolutionStates.stream().map(state ->
                            new BoardPosition(0, 0, 0, 0, 0, state.boxConfigurationIndex, state.playerPosition)
                    ).collect(Collectors.toCollection(ArrayList::new));
            if (solutionBoardPositions.isEmpty()) {
                return null;
            }

            return createSolutionFromBoardPositions(solutionBoardPositions);
        }
    }





    /**
     * Class for searching for a solution by only using the generated box configurations.<br>
     * While searching, the player doesn't touch any user selected positions.
     * This way it can be checked if the puzzle is still solvable having when the user
     * selected positions were converted to walls.
     * <p>
     * Before this method is called the box configurations to be used must
     * have been generated.
     */
    private class UnusedFloorsFinder extends AllMetricsOptimizer {

        private static class State {
            int boxConfigurationIndex = 0;
            int playerPosition = 0;
            State previousState = null;


            public State(int boxConfigurationIndex, int playerPosition, State previousState) {
                this.boxConfigurationIndex = boxConfigurationIndex;
                this.playerPosition = playerPosition;
                this.previousState = previousState;
            }

            @Override
            public boolean equals(Object o) {
                if (o == null || getClass() != o.getClass()) return false;
                State state = (State) o;
                return boxConfigurationIndex == state.boxConfigurationIndex && playerPosition == state.playerPosition;
            }

            @Override
            public int hashCode() {
                return Objects.hash(boxConfigurationIndex, playerPosition);
            }
        }

        LevelSolvedCheck levelSolvedCheck;


        volatile State bestFoundSolutionState = null;

        // All states of the solution to be optimized. This is only used for displaying a "percentage of processed" progress.
        List<State> statesOfSolutionToBeOptimized = new ArrayList<>();

        ConcurrentHashMap<State, State> transpositionTable = null;
        AtomicInteger transpositionTableSize = new AtomicInteger(1); // 1 due to the start board position

        ConcurrentLinkedQueue<State> openQueue = null;



        /**
         * Searches for a solution by only using the generated box configurations and not touching user selected positions.
         *
         * @param initialBoard        the board at level start
         * @param endBoard            the end board having all boxes on a goal
         * @param currentSolution a valid solution
         * @return the best found solution
         */
        public OptimizerSolution findBestSolutionPath(
                final OptimizerBoard initialBoard,
                final OptimizerBoard endBoard,
                final OptimizerSolution currentSolution) {

            solutionToBeOptimized = currentSolution;

            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

            // Get the index of the initial box configuration.
            packBoxConfiguration(boxConfiguration, initialBoard);
            startBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
            int topLeftReachingPlayerPositionInitialBoard = getTopLeftReachablePlayerPosition(playerExternalToInternalPosition[initialBoard.playerPosition], boxConfiguration);

            // Get the index of the target box configuration -> box configuration having all boxes on a goal.
            packBoxConfiguration(boxConfiguration, endBoard);
            targetBoxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

            levelSolvedCheck = new LevelSolvedCheck(endBoard);

            boardPositionsSolutionToBeOptimized = getSolutionBoardPositions(currentSolution);

            statesOfSolutionToBeOptimized = boardPositionsSolutionToBeOptimized.stream().map(
                    boardPosition -> new State(boardPosition.boxConfigurationIndex, boardPosition.playerPosition, null)
            ).toList();

            openQueue = new ConcurrentLinkedQueue();
            transpositionTable = getTranspositionTable();

            State startBoardPosition = new State(startBoxConfigurationIndex, topLeftReachingPlayerPositionInitialBoard, null);
            transpositionTable.put(startBoardPosition, startBoardPosition);
            openQueue.add(startBoardPosition);


            // Execute the optimizer searches.
            final ExecutorService executor = Executors.newFixedThreadPool(maxCPUsToBeUsedCount);
            for (int threadNo = 0; threadNo < maxCPUsToBeUsedCount; threadNo++) {
                executor.execute(new LocalSearch());
            }

            Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

            // Inform the user about the RAM that is available after the search has ended.
            optimizerGUI.addLogText(Texts.getText("optimizer.freeRAMAfterSearch", Utilities.getMaxUsableRAMinMiB()));

            // The optimizer has only been stopped to start a new iteration with the new best found solution.
            // Hence, the optimizer is still running.
            if (optimizerStatus == OptimizerStatus.STOPPED_FOR_IMMEDIATE_NEW_ITERATION) {
                optimizerStatus = RUNNING;
            }

            if(Debug.isDebugModeActivated) {
                System.out.printf("Transposition table size: %,d\n", transpositionTable.size());
            }

            // Return the best found solution (or null if no solution has been found, when the
            // optimizer has been stopped by user, out of memory, ...)
            return getBestFoundSolution();
        }

        private ConcurrentHashMap<State, State> getTranspositionTable() {

            long maxUsableRAM = Utilities.getMaxUsableRAMInBytes();
            long targetRAM = (long) (0.9 * maxUsableRAM);   // 10% remain for open queue

            int stateSizeInRAM = 80;

            double costPerSlot = 4 + 0.9f * stateSizeInRAM; // 4 byte for bucket-array, 0.9f load factor

            int estimatedCapacity = (int) Math.ceil(targetRAM / costPerSlot);

            int estimatedCapacityPowerOf2 = Integer.highestOneBit(estimatedCapacity); // reduce to the next lower power of 2 since the hash map works with powers of 2

            // Return a map that never resizes since this is too slow and memory consuming.
            return new ConcurrentHashMap<>(estimatedCapacityPowerOf2, 0.9f) {

                public State put(State key, State value) {
                    if (size() >= estimatedCapacityPowerOf2 - 10) {
                        optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                        stopOptimizer();
                    }
                    return super.put(key, value);
                }
            };
        }

        private class LocalSearch implements Runnable {

            /**
             * Starts a new forward search.
             */
            @Override
            public void run() {

                try {
                    // Start a new forward search.
                    forwardSearch();
                } catch (Exception ex) {
                    ex.printStackTrace();
                } catch (OutOfMemoryError e) {
                    // Stop the optimizer by setting the proper stop reason.
                    optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                }
            }

            /**
             * Forward search searching from the start board position forward
             * (-> pushing boxes) until a solution has been found.
             */
            private void forwardSearch() {

                // Object holding all information about one board position.
                State currentBoardPosition = null;

                // Used to calculate the player-reachable positions.
                final PlayerPositionsQueue playerPositions = new PlayerPositionsQueue(playerSquaresCount);

                // Used to store the real positions of the boxes of a box configuration. Usually just the index of a box configuration in
                // the global array is used, but for doing pushes it's necessary to copy the real positions in this byte array.
                final BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);


                while (optimizerStatus == RUNNING) {

                    // Take board position from the queue.
                    currentBoardPosition = openQueue.poll();

                    // Search has finished when there are no more board positions in the queue.
                    if (currentBoardPosition == null || bestFoundSolutionState != null) {
                        break;
                    }

                    int currentBoxConfigurationIndex = currentBoardPosition.boxConfigurationIndex;

                    playerPositions.addFirst(currentBoardPosition.playerPosition, 0);

                    // Old and new position of the player and a box.
                    int playerPosition, newPlayerPosition, boxPosition, newBoxPosition = -1;

                    // Create a box configuration from the index of the box configuration.
                    boxConfigurationStorage.copyBoxConfiguration(boxConfiguration, currentBoxConfigurationIndex);

                    // Breadth-first search over all reachable player positions.
                    while ((playerPosition = playerPositions.remove()) != NONE) {

                        // Try to move the player to every direction and do pushes if necessary.
                        for (int direction = 0; direction < 4; direction++) {

                            // Immediately continue with the next direction if the player can't move to the new position due to a wall.
                            if ((newPlayerPosition = playerSquareNeighbor[direction][playerPosition]) == NONE ||
                                isForbiddenExternalPosition[playerInternalToExternalPosition[newPlayerPosition]]) {
                                continue;
                            }

                            // Is there a box at the new player position?
                            if ((boxPosition = playerPositionToBoxPosition[newPlayerPosition]) != NONE && boxConfiguration.isBoxAtPosition(boxPosition)) {

                                // Check if the possible new position of the box is no deadlock square and not blocked by another box
                                // and not a forbidden position.
                                if ((newBoxPosition = boxNeighbor[direction][boxPosition]) != NONE &&
                                        !boxConfiguration.isBoxAtPosition(newBoxPosition) &&
                                        !isForbiddenExternalPosition[boxInternalToExternalPosition[boxPosition]]) {  // the forbidden positions are "player positions" => convert to internal player position

                                    // Push is possible => do the push.
                                    boxConfiguration.moveBox(boxPosition, newBoxPosition);

                                    // Check whether it's one of the allowed box configurations.
                                    int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);
                                    if (boxConfigurationIndex != NONE) {

                                        int topLeftReachablePlayerPosition = getTopLeftReachablePlayerPosition(newPlayerPosition, boxConfiguration);

                                        State newBoardPosition = new State(boxConfigurationIndex, topLeftReachablePlayerPosition, currentBoardPosition);

                                        State storedState = transpositionTable.get(newBoardPosition);
                                        if (storedState == null) {
                                            addToTranspositionTable(newBoardPosition);

                                            if (levelSolvedCheck.isTargetBoardPosition(boxConfigurationIndex, newPlayerPosition)) {
                                                bestFoundSolutionState = newBoardPosition;
                                                return;
                                            } else {
                                                openQueue.add(newBoardPosition);
                                            }
                                        }
                                    }

                                    // Undo the push to reuse the box configuration for the next push.
                                    boxConfiguration.moveBox(newBoxPosition, boxPosition);
                                }
                            } else {
                                // No wall, no box => the player can move to the new position. Store the new position in the queue (if it hasn't been reached, yet).
                                playerPositions.addIfNew(newPlayerPosition);
                            }
                        }
                    }
                }
            }

            private void addToTranspositionTable(State newBoardPosition) {
                transpositionTable.put(newBoardPosition, newBoardPosition); // Another thread may have already stored a better state, but without synchronization it is faster :-)

                int storedStateCount = transpositionTableSize.incrementAndGet();
                if ((storedStateCount & 4095) == 0) {
                    if (Utilities.MemoryUsage.getMemoryUsagePercentage() > 85) {
                        optimizerStatus = OptimizerStatus.STOPPED_DUE_TO_OUT_OF_MEMORY;
                        return;
                    }
                    displayIntermediateResult();
                }
            }
        }

        /**
         * Calculates and displays the current pushes depth of the search and the current best solution metrics.
         */
        private void displayIntermediateResult() {
            for (int i = statesOfSolutionToBeOptimized.size() - 1; i != -1; i--) {
                State solutionState = statesOfSolutionToBeOptimized.get(i);

                State reachedState = transpositionTable.get(solutionState);
                if (reachedState != null) {
                    int percentageProcessed = (int) (100.0 * i / statesOfSolutionToBeOptimized.size());
                    optimizerGUI.setInfoText(Texts.getText("optimizer.unusedFloorsFinderProcessed", percentageProcessed));
                    return;
                }
            }
        }

        @Override
        protected OptimizerSolution getBestFoundSolution() {

            if(bestFoundSolutionState == null) {
                return null;
            }

            ArrayList<State> allSolutionStates = new ArrayList<>();

            // Reconstruct the solution path from the found best end state to the start state (excluding the start state which is the initial board position).
            for (State state = bestFoundSolutionState; state.previousState != null; ) {
                allSolutionStates.add(0, state);
                state = state.previousState;
            }

            ArrayList<BoardPosition> solutionBoardPositions =
                    allSolutionStates.stream().map(state ->
                            new BoardPosition(0, 0, 0, 0, 0, state.boxConfigurationIndex, state.playerPosition)
                    ).collect(Collectors.toCollection(ArrayList::new));
            if (solutionBoardPositions.isEmpty()) {
                return null;
            }

            return createSolutionFromBoardPositions(solutionBoardPositions);
        }
    }




    /**
     * Returns a list of all board positions resulting in playing the passed moves on the passed board.
     * The board positions will contain the correct metrics (pushes, moves,...).
     *
     * @param startBoard       the start board the moves can be played on
     * @param moveDirections   move directions to be played on the board
     * @param axisPreviousPush the axis of the push performed BEFORE the first move of the passed moves
     * @return the board positions resulting by playing the passed moves (excluding the start board position) as {@code Stream}
     */
    public Stream<BoardPositionWithPushDirection> getBoardPositions(OptimizerBoard startBoard, byte[] moveDirections, int axisPreviousPush) {

        ArrayList<BoardPositionWithPushDirection> boardPositions = new ArrayList<>(moveDirections.length);
        OptimizerBoard board = startBoard.getClone();
        BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);

        int lastPushedBoxPosition = getLastPushedBoxPosition(board, axisPreviousPush);
        boolean lastMovementWasMove = prefixPushesCount == 0; // The first push is a new pushingSession. If there is a part before the optimized pushes range => no initial pushingSession

        int moves = 0, pushes = 0, boxLines = 0, boxChanges = 0, pushingSessions = 0;

        for (int moveDirection : moveDirections) {

            // Do the move on the board. "doMovements" returns true, when the move results in a push.
            if (board.doMovement(moveDirection)) {

                int newPlayerPosition = playerExternalToInternalPosition[board.playerPosition];

                moves++;
                pushes++;

                if (newPlayerPosition != lastPushedBoxPosition || lastMovementWasMove) {
                    boxLines++;
                }
                if (newPlayerPosition != lastPushedBoxPosition) {
                    boxChanges++;
                }
                if (lastMovementWasMove) {
                    pushingSessions++;
                }

                packBoxConfiguration(boxConfiguration, board);
                int boxConfigurationIndex = boxConfigurationStorage.getBoxConfigurationIndex(boxConfiguration);

                boardPositions.add(new BoardPositionWithPushDirection(moves, pushes, boxLines, boxChanges, pushingSessions, boxConfigurationIndex, newPlayerPosition, moveDirection, null));

                lastMovementWasMove = false;

                // Calculate the new box position and convert it into a player position for an easier comparison with the next player position.
                int directionOffset = board.directionOffsets[moveDirection];
                lastPushedBoxPosition = playerExternalToInternalPosition[board.playerPosition + directionOffset];
            } else {
                moves++;
                lastMovementWasMove = true;
            }
        }

        return boardPositions.stream();
    }

    /**
     * Stores the index of the board position where the forward and the backward
     * search have met each other. Additionally, the last moved box position
     * is stored for calculating box changes, box lines and pushing sessions.
     */
    private static class EndBoardPositionData {

        private final int boxConfigurationIndex;
        private final int playerPosition;
        private final int lastMoveDirection;

        /**
         * A storage for saving the data where the optimizer searches have met each other.
         *
         * @param boxConfigurationIndex the box configuration index of the board position where the searches have met
         * @param playerPosition        the player position of the board position where the searches have met
         * @param lastMovedBoxPosition  the direction of the last move
         */
        public EndBoardPositionData(int boxConfigurationIndex, int playerPosition, int lastMovedBoxPosition) {
            this.boxConfigurationIndex = boxConfigurationIndex;
            this.playerPosition = playerPosition;
            this.lastMoveDirection = lastMovedBoxPosition;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = prime + boxConfigurationIndex;
            result = prime * result + lastMoveDirection;
            result = prime * result + playerPosition;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            EndBoardPositionData other = (EndBoardPositionData) obj;
            if (boxConfigurationIndex != other.boxConfigurationIndex ||
                    playerPosition != other.playerPosition ||
                    lastMoveDirection != other.lastMoveDirection) {
                return false;
            }
            return true;
        }
    }


    /**
     * Returns the position of the last pushed box or NONE if there is no previous push.
     *
     * @param axisPreviousPush the axis of the last performed push or NONE if there is no last push
     * @return the position of the last pushed box or NONE in case there is no previous push
     */
    private int getLastPushedBoxPosition(OptimizerBoard board, int axisPreviousPush) {

        if (axisPreviousPush != NONE) {
            BoxConfiguration boxConfiguration = new BoxConfiguration(boxPositionsCount);
            packBoxConfiguration(boxConfiguration, board);

            final int playerPosition = playerExternalToInternalPosition[board.playerPosition];

            for (int direction = 0; direction < 4; direction++) {
                if (getAxisOfDirection(direction) == axisPreviousPush) {                 // correct push axis
                    int neighborPosition = playerSquareNeighbor[direction][playerPosition];
                    if (neighborPosition != NONE) {
                        int lastPushedBoxPosition = playerPositionToBoxPosition[neighborPosition];

                        if (lastPushedBoxPosition != NONE &&                             // valid neighbor position for a box
                                boxConfiguration.isBoxAtPosition(lastPushedBoxPosition)) {   // and there is indeed a box at that position
                            return lastPushedBoxPosition;
                        }
                    }
                }
            }
        }

        return NONE;
    }

    /**
     * Displays the passed board position for debug purposes.<br>
     * This method is only used for internal uses to analyze the optimizer while optimizing.
     *
     * @param storage            storage the box configuration is stored in
     * @param boardPositionIndex index of the board position in the visited data array
     * @param graphicOutput      flag, indicating whether there should be a graphical display
     *                           or not
     * @param waitForEnter       flag, indicating whether the program has to wait for "enter"
     *                           after displaying the box configuration
     */
    void debugDisplayBoardPosition(final BoxConfigurationStorageHashSet storage, final int boardPositionIndex, final boolean graphicOutput, final boolean waitForEnter) {
        BoxConfiguration temp = new BoxConfiguration(boxPositionsCount);
        storage.copyBoxConfiguration(temp, boardPositionIndex / playerSquaresCount);
        int playerPosition = boardPositionIndex % playerSquaresCount;
        debugDisplayBoxConfiguration(temp, playerPosition, graphicOutput, waitForEnter);
    }

    /**
     * Displays the passed board position for debug purposes.<br>
     * This method is only used for internal uses to analyze the optimizer while optimizing.
     *
     * @param storage        storage the box configuration is stored in
     *                       index of the box configuration in the visited data array
     * @param playerPosition position of the player in internal format
     * @param graphicOutput  flag, indicating whether there should be a graphical display or not
     * @param waitForEnter   flag, indicating whether the program has to wait for "enter"
     *                       after displaying the box configuration
     */
    void debugDisplayBoardPosition(final BoxConfigurationStorageHashSet storage, final int boxConfigurationIndex, final int playerPosition, final boolean graphicOutput, final boolean waitForEnter) {
        BoxConfiguration temp = new BoxConfiguration(boxPositionsCount);
        storage.copyBoxConfiguration(temp, boxConfigurationIndex);
        debugDisplayBoxConfiguration(temp, playerPosition, graphicOutput, waitForEnter);
    }

    /**
     * Displays the passed box configuration for debug purposes.<br>
     * This method is only used for internal uses to analyze the optimizer while optimizing.
     *
     * @param boxConfiguration box configuration to be displayed
     * @param graphicOutput    flag, indicating whether there should be a graphical display or not
     * @param waitForEnter     flag, indicating whether the program has to wait for "enter"
     *                         after displaying the box configuration
     */
    void debugDisplayBoxConfiguration(final BoxConfiguration boxConfiguration, final int playerPosition, final boolean graphicOutput, final boolean waitForEnter) {

        int externalPlayerPosition = playerInternalToExternalPosition[playerPosition];
        Board mainBoard = Debug.debugApplication.board;
        if (!graphicOutput) {
            StringBuilder s = new StringBuilder();
            for (int y = 0; y < mainBoard.height; y++) {
                for (int x = 0; x < mainBoard.width; x++) {
                    int boxPosition;
                    boxPosition = boxExternalToInternalPosition[y * mainBoard.width + x];
                    if (boxPosition < 0) {
                        if (y * mainBoard.width + x == externalPlayerPosition) {
                            s.append("@");
                        } else {
                            s.append(mainBoard.isWall(y * mainBoard.width + x) ? "#" : " ");
                        }
                        continue;
                    }

                    if (boxConfiguration.isBoxAtPosition(boxPosition)) {
                        s.append(mainBoard.isGoal(y * mainBoard.width + x) ? "*" : "$");
                    } else {
                        if (y * mainBoard.width + x == externalPlayerPosition) {
                            s.append(mainBoard.isGoal(y * mainBoard.width + x) ? "+" : "@");
                        } else {
                            s.append(mainBoard.isGoal(y * mainBoard.width + x) ? "." : " ");
                        }
                    }
                }
                s.append("\n");
            }
            System.out.println(s);
            return;
        }

        mainBoard.setPlayerPosition(externalPlayerPosition);

        // Graphic output requested ...
        for (int y = 0; y < mainBoard.height; y++) {
            for (int x = 0; x < mainBoard.width; x++) {
                int externalBoxPosition = y * mainBoard.width + x;
                int boxPosition = boxExternalToInternalPosition[externalBoxPosition];
                if (boxPosition >= 0) {
                    if (boxConfiguration.isBoxAtPosition(boxPosition)) {
                        mainBoard.setBox(x, y);
                    } else {
                        mainBoard.removeBox(x, y);
                    }
                }
                // When only a pushes range is optimized there may be more walls than on the original board.
                if (board.isWall(externalBoxPosition)) {
                    mainBoard.setWall(externalBoxPosition);
                }
            }
        }
        if (mainBoard.isBox(mainBoard.playerPosition)) {
            mainBoard.setPlayerPosition(0);
        }

        // This method may have set additional walls to the board. This call forces the
        // board to recalculate the parts of the screen that normally can't change (like walls :-)
        Debug.debugApplication.applicationGUI.mainBoardDisplay.recalculateGraphicSizes();

        // Show the new board.
        Debug.debugApplication.redraw(waitForEnter);
    }
}
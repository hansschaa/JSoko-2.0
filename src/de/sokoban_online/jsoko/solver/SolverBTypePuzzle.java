/**
 * JSoko - A Java implementation of the game of Sokoban
 * Copyright (c) 2016 by Matthias Meger, Germany
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
package de.sokoban_online.jsoko.solver;

import de.sokoban_online.jsoko.JSoko;
import de.sokoban_online.jsoko.board.Board;
import de.sokoban_online.jsoko.boardpositions.AbsoluteBoardPositionMoves;
import de.sokoban_online.jsoko.leveldata.solutions.Solution;
import de.sokoban_online.jsoko.pushesLowerBoundCalculation.LowerBoundCalculation;
import de.sokoban_online.jsoko.resourceHandling.Settings;
import de.sokoban_online.jsoko.resourceHandling.Texts;
import de.sokoban_online.jsoko.utilities.Debug;
import de.sokoban_online.jsoko.utilities.Utilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A b-type puzzle solver.
 * b-type puzzles can be generated with a generator, see: http://bbs.mf8-china.com/forum.php?mod=viewthread&tid=118834&page=1#pid1966958
 * The boxes and walls form a spanning tree on the board.
 *
 * Example:
 * #####################
 * #@                  #
 * # #*#*#*#$#$#.#*#.# #
 * #   . *   *   $ . $ #
 * # #*#$#*# #*#$#.# # #
 * # *   $ . .   $ * * #
 * # # #*#$#$#.#.#$#*# #
 * #   $ * * $ * . * . #
 * # #*#.# #.#*# #.#$# #
 * # .   $   .   $ *   #
 * # #*#*#.#$#*# #$# # #
 * #   * $ $ $ . . . * #
 * # # # #$#.#.#$# # # #
 * # * * . $   * * * * #
 * # #*#*#.#*#*# #*#$# #
 * # *   $ * . * * * * #
 * # #$#.# # #$# # # # #
 * # * . * * $ * *   * #
 * # #.#$# #.# # #*#.# #
 * #                   #
 * #####################
 */
public class SolverBTypePuzzle extends Solver {

    /**
     * The last board position on the found solution path.
     */
    volatile ArrayList<BoardPosition> solutionBoardPositions = null;

    ConcurrentLinkedQueue<BoardPosition>[] openQueueForward = null;
    ConcurrentLinkedQueue<BoardPosition>[] openQueueBackward = null;
    ConcurrentHashMap<BoardPosition, BoardPosition> transpositionTable = new ConcurrentHashMap<>(100_000_000);

    AtomicInteger boardPositionsCount = new AtomicInteger(0);

    volatile boolean isSolverRunning = false;

    int threadCount = Runtime.getRuntime().availableProcessors();
    AtomicInteger threadThatFoundEmptyQueueCount = new AtomicInteger(0);

    /**
     * Creates an Solver instance for the 0-space b-puzzle type.
     *
     * @param application the reference to the main object holding all references
     * @param solverGUI   reference to the GUI of this solver
     */
    public SolverBTypePuzzle(JSoko application, SolverGUI solverGUI) {

        super(application, solverGUI);

        openQueueForward = new ConcurrentLinkedQueue[board.boxCount];
        openQueueBackward = new ConcurrentLinkedQueue[board.boxCount];
        for (int i = 0; i < openQueueForward.length; i++) {
            openQueueForward[i] = new ConcurrentLinkedQueue<>();
            openQueueBackward[i] = new ConcurrentLinkedQueue<>();
        }
    }

    /**
     * Tries to solve the configuration from the current board.
     */
    @Override
    public Solution searchSolution() {

        boardPositionsCount.set(0);

        // Backup the start board position.
        AbsoluteBoardPositionMoves startBoardPosition = new AbsoluteBoardPositionMoves(board);

        int lowerBoundStartBoardPosition = lowerBoundCalcuation.calculatePushesLowerbound();
        if (lowerBoundStartBoardPosition == LowerBoundCalculation.DEADLOCK) {
            return null;
        }


        // Start board position of the forward search
        BoardPosition startBoardPositionForwardSearch = new BoardPosition(board.boxData.getBoxPositionsClone(), false, null);
        if (lowerBoundStartBoardPosition != 0) {                            // Only mark the initial board position as visited in case it's not a "start with solved position"-level.
            addToTranspositionTable(startBoardPositionForwardSearch);
        }
        addToOpenQueue(board, startBoardPositionForwardSearch);
        boardPositionsCount.incrementAndGet();


        // Start board position for the backward search
        Board backwardBoard = createBackwardBoard();
        BoardPosition startBoardPositionBackwardSearch = new BoardPosition(backwardBoard.boxData.getBoxPositionsClone(), true, null);
        addToTranspositionTable(startBoardPositionBackwardSearch);
        addToOpenQueue(backwardBoard, startBoardPositionBackwardSearch);
        boardPositionsCount.incrementAndGet();

        long startTimeStamp = System.currentTimeMillis();

        isSolverRunning = true;

        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Start searches from the initial board position.
        for (int threadNo = 0; threadNo < threadCount/2 ; threadNo++) {
            executor.execute(() -> {
                        try {
                            searchForSolution(board.clone(), false);  // Main search!
                        } catch (Exception e) {
                            isSolverRunning = false;
                            e.printStackTrace();
                        }
                    }
            );
        }

        // Start searches from the solved board position.
        for (int threadNo = 0; threadNo < threadCount / 2; threadNo++) {
            executor.execute(() -> {
                        try {
                            searchForSolution(backwardBoard.clone(), true);  // Main search!
                        } catch (Exception e) {
                            isSolverRunning = false;
                            e.printStackTrace();
                        }
                    }
            );
        }

        Utilities.shutdownAndAwaitTermination(executor, 42, TimeUnit.DAYS);

        // Display info on the screen.
        if (solutionBoardPositions != null) {

            String numberofpositionsText = Texts.getText("numberofpositions");
            numberofpositionsText += String.format("%,d", boardPositionsCount.get());

            publish(
                    Texts.getText("solved") + numberofpositionsText);
        } else {
            publish(Texts.getText("solver.noSolutionFound"));
        }

        System.out.println("Time for search: " + (System.currentTimeMillis() - startTimeStamp));


        // Restore the start board position.
        board.setBoardPosition(startBoardPosition);

        if (solutionBoardPositions == null) {
            positionStorage.clear();

            return null;
        }


        int currentIndex = application.movesHistory.getCurrentMovementNo();


        // Set the correct board position.
        for (int position = board.firstRelevantSquare; position < board.lastRelevantSquare; position++) {
            board.removeBox(position);
        }
        board.setBoardPosition(startBoardPosition);


        // Add movements to the move history.
        for (BoardPosition solutionBoardPosition : solutionBoardPositions) {

            int[] currentBoxPositions = board.boxData.getBoxPositionsClone();
            int[] newBoxPositions = solutionBoardPosition.getBoxPositions();

            // Find the old and new position of the moved box by comparing the arrays
            int oldPosition = -1;
            int newPosition = -1;
            for (int boxPosition : currentBoxPositions) {
                boolean found = false;
                for (int newBoxPosition : newBoxPositions) {
                    if (boxPosition == newBoxPosition) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    oldPosition = boxPosition;
                    break;
                }
            }
            for (int newBoxPosition : newBoxPositions) {
                boolean found = false;
                for (int boxPosition : currentBoxPositions) {
                    if (newBoxPosition == boxPosition) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    newPosition = newBoxPosition;
                    break;
                }
            }

            board.pushBox(oldPosition, newPosition);

            int pushedBoxNo = board.getBoxNo(newPosition);

            int pushDirection = -1;
            for (int direction = 0; direction < 4; direction++) {
                if (newPosition - oldPosition == 2 * offset[direction]) {   // this level type has always two pushes
                    pushDirection = direction;
                }
            }

            application.movesHistory.addMovement(pushDirection, pushedBoxNo);
            application.movesHistory.addMovement(pushDirection, pushedBoxNo); // this level type has always two pushes
        }

        // Den aktuellen Zug in der History wieder auf den Wert setzen, auf den er vor dem
        // Einfügen der neuen Züge stand. Dadurch kann der Spieler mit "redo" die Züge durchgehen.
        application.movesHistory.setMovementNo(currentIndex);

        // Die Anfangsstellung auf dem Spielfeld setzen.
        for (int position = board.firstRelevantSquare; position < board.lastRelevantSquare; position++) {
            board.removeBox(position);
        }
        board.setBoardPosition(startBoardPosition);

        // Show a hash table statistic if requested.
        if (Debug.debugShowHashTableStatistic) {
            positionStorage.printStatisticDebug();
        }

        // Clear the data from the hash table, to free that memory.
        positionStorage.clear();

        // Somewhat optimize the solution.
        // That also enters the player moves into the history.
        optimizeSolution();

        // Create the new solution.
        Solution newSolution = new Solution(application.movesHistory.getLURDFromHistoryTotal());
        newSolution.name = solutionByMeNow();

        return newSolution;
    }

    private Board createBackwardBoard() {
        Board boardBackward = board.clone();

        // Remove the boxes and goals from the board.
        boardBackward.removeAllBoxes();
        for (int goalPosition : board.getGoalPositions()) {
            boardBackward.removeGoal(goalPosition);
        }

        // Set the "backward" goal and boxe positions.
        for (int boxPosition : board.getGoalPositions()) {
            boardBackward.setBox(boxPosition);
        }
        for (int goalPosition : board.boxData.getBoxPositionsClone()) {
            boardBackward.setGoal(goalPosition);
        }

        return boardBackward.clone(); // create a new board taking the new box and goal positions into account
    }


    /**
     * Generates successor configurations by performing all legal pushes.
     * Each generated configuration is stored in the hash table.
     * Had the configuration already been reached by a push, we skip it.
     *
     * Forward and backward search is the same, since there are no deadlocks
     * and the player position is irrelevant. Hence, the searches just
     * start at different board positions (initial vs. solved board position).
     */
    protected void searchForSolution(Board board, boolean isBackwardSearch) {

        int[] boxPositionsArray = new int[board.boxCount];  // own array to avoid creating a new array every time

        while (isSolverRunning && !isCancelled()) {

            BoardPosition boardPositionToBeAnalyzed = removeFromOpenQueue(isBackwardSearch);

            if(boardPositionToBeAnalyzed == null) {
                if (checkSolverEnded()) break;
                continue;
            }

            setBoardPositionOnBoard(board, boardPositionToBeAnalyzed, boxPositionsArray);

//            debugDisplayBoard(boardPositionToBeAnalyzed);

            for (int boxNo = 0; boxNo < board.boxCount && isSolverRunning; boxNo++) {

                int boxPosition = board.boxData.getBoxPosition(boxNo);

                for (int direction = 0; direction < 4; direction++) {
                    int newBoxPositionOneStep = boxPosition + offset[direction];
                    int newBoxPosition = newBoxPositionOneStep + offset[direction];

                    if (!board.isAccessibleBox(newBoxPositionOneStep) || !board.isAccessibleBox(newBoxPosition)) {
                        continue;
                    }

                    board.pushBox(boxPosition, newBoxPosition);
                    board.playerPosition = newBoxPositionOneStep;     // set player position for checking if there is a corral

                    if (isThereACorral(board, newBoxPosition)) {
                        board.pushBoxUndo(newBoxPosition, boxPosition);
                        continue;
                    }

//                    BoardPosition currentBoardPosition = new BoardPosition(board.boxData.getBoxPositionsClone(), isBackwardSearch, boardPositionToBeAnalyzed);
                    BoardPosition currentBoardPosition = new DeltaBoardPosition(boxPosition, newBoxPosition, isBackwardSearch, boardPositionToBeAnalyzed);      // less RAM usage by only storing the delta

                    displayProgressInfo();

                    BoardPosition storedBoardPosition = addToTranspositionTable(currentBoardPosition);

                    if (storedBoardPosition == null) {
                        addToOpenQueue(board, currentBoardPosition);                                                            // first time that this board position has been reached
                    } else {
                        if (storedBoardPosition.isBackwardBoardPosition != currentBoardPosition.isBackwardBoardPosition) {      // there is already a duplicate board position from the other search direction
                            setSolutionBoardPositions(currentBoardPosition, storedBoardPosition);
                            isSolverRunning = false;
                            return;
                        }
                    }

                    board.pushBoxUndo(newBoxPosition, boxPosition);
                }
            }
        }
    }

    private void debugDisplayBoard(BoardPosition boardPosition) {

        int[] boxPositionsDummyArray = new int[board.boxCount];

        if (boardPosition.isBackwardBoardPosition) {
            setBoardPositionOnBoard(this.board, boardPosition, boxPositionsDummyArray); // the GUI uses the main board
            Arrays.stream(this.board.getGoalPositions()).forEach(this.board::removeGoal);
            Arrays.stream(board.getGoalPositions()).forEach(this.board::setGoal);

        } else {
            setBoardPositionOnBoard(this.board, boardPosition, boxPositionsDummyArray); // the GUI uses the main board
        }

        displayBoard();
    }

    /* Returns if ALL threads got "null" from the open queue. This means no thread is processing board positions anymore. */
    private boolean checkSolverEnded() {

        int emptyQueueCount = threadThatFoundEmptyQueueCount.incrementAndGet();
        if (emptyQueueCount == threadCount) {
            isSolverRunning = false;
            return true;
        }
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {}
        if (threadThatFoundEmptyQueueCount.get() == threadCount) {  // do another check since time has passed
            isSolverRunning = false;
            return true;
        }
        threadThatFoundEmptyQueueCount.decrementAndGet();           // another thread is still active and may add something to the open queue

        return false;
    }

    private void displayProgressInfo() {

        if ((boardPositionsCount.incrementAndGet() & 65535) == 0) {

            String info = Texts.getText("numberofpositions");
            info += String.format("%,d", boardPositionsCount.get());

            publish(info);

            // Throw "out of memory" if less than 15MB RAM is free.
            if (Utilities.getMaxUsableRAMinMiB() <= 15) {
                isSolverRunning = false;
                cancel(true);
            }
        }
    }


    /**
     * Returns whether there is a corral on the board involving "newBoxPosition".
     */
    private boolean isThereACorral(Board board, int newBoxPosition) {

        board.playersReachableSquares.update();

        for (int dir = 0; dir < 4; dir++) {                          // If there is a corral, then it's a deadlock!
            int boxNeighborPosition = newBoxPosition + offset[dir];
            if (board.isAccessible(boxNeighborPosition) && !board.playersReachableSquares.isSquareReachable(boxNeighborPosition)) { // a free neighbor which is not accessible -> corral
                return true;
            }
        }

        return false;
    }


    synchronized private void setSolutionBoardPositions(BoardPosition boardPosition1, BoardPosition boardPosition2) {

        ArrayList backup = solutionBoardPositions != null ? solutionBoardPositions : null;

        solutionBoardPositions = new ArrayList<>();

        BoardPosition forwardBoardPosition = boardPosition1.isBackwardBoardPosition  ? boardPosition2 : boardPosition1;
        BoardPosition backwardBoardPosition = boardPosition1.isBackwardBoardPosition ? boardPosition1 : boardPosition2;

        // Add the board positions of the forward search in the correct order to recreate the solution path.
        for (BoardPosition currentBoardPosition = forwardBoardPosition; currentBoardPosition.previousBoardPosition != null; currentBoardPosition = currentBoardPosition.previousBoardPosition) {
            solutionBoardPositions.add(0, currentBoardPosition);
        }

        // Add the board positions of the backward search in correct order to recreate the solution path.
        // The first one is identical to the last forward board position, hence it is discarded.
        for (BoardPosition currentBoardPosition = backwardBoardPosition.previousBoardPosition; currentBoardPosition != null; currentBoardPosition = currentBoardPosition.previousBoardPosition) {
            solutionBoardPositions.add(currentBoardPosition);
        }

        if(backup != null && solutionBoardPositions.size() > backup.size()) {
            solutionBoardPositions = backup;        // old solution was shorter than the new solution
        }
    }

    private void addToOpenQueue(Board board, BoardPosition boardPosition) {
        int boxesOnGoalCount = getBoxesOnCorrectGoalCount(board, boardPosition);

        if (boardPosition.isBackwardBoardPosition)
            openQueueBackward[boxesOnGoalCount - 1].add(boardPosition);
        else
            openQueueForward[boxesOnGoalCount - 1].add(boardPosition);
    }

    /**
     * For this special puzzle type, each box has only one specific goal it must reach to solve the puzzle.
     * This method counts and returns the boxes on the correct goals as a heuristic.
     */
    private int getBoxesOnCorrectGoalCount(Board board, BoardPosition boardPosition) {

        int boxesOnCorrectGoalCount = 0;

        for (int boxNo = 0; boxNo < board.boxCount; boxNo++) {
            int boxPosition = board.boxData.getBoxPosition(boxNo);

            if (board.isGoal(boxPosition)) {

                int boxCount = 0, goalCount = 0, position = boxPosition;
                int direction = board.isWall(boxPosition + offset[UP]) ? RIGHT : DOWN; // check the axis the box can move on for checking the box being on the correct goal (each box has a specific goal)
                do {
                    int neighbor = position + offset[direction];  // can be 2 * offset[direction]. However, in a wrong level type this would cause positions outside the board
                    if (board.isGoal(neighbor)) {
                        goalCount++;
                    }
                    if (board.isBox(neighbor)) {
                        boxCount++;
                    }
                    position = neighbor;
                } while (!board.isWall(position));

                if (boxCount == goalCount) {     // the rest of the column/row has exactly the same number of goals and boxes => this is the correct goal for the box
                    boxesOnCorrectGoalCount++;
                }
            }
        }
        return boxesOnCorrectGoalCount;
    }

    private BoardPosition removeFromOpenQueue(boolean isBackwardSearch) {

        ConcurrentLinkedQueue<BoardPosition>[] openQueue = isBackwardSearch ? openQueueBackward : openQueueForward;

        // Remove from the board positions having the most boxes on goals first.
        for (int i = openQueue.length - 1; i >= 0; i--) {
            ConcurrentLinkedQueue<BoardPosition> boardPositions = openQueue[i];
            BoardPosition boardPosition = boardPositions.poll();
            if (boardPosition != null) {
                return boardPosition;
            }
        }
        return null;
    }

    private void setBoardPositionOnBoard(Board board, BoardPosition boardPosition, int[] boxPositions) {

        board.removeAllBoxes();

        boardPosition.fillBoxPositions(boxPositions);   // call fillBoxPositions() instead of getBoxPositions() to avoid creating a new array every time

        board.boxData.setBoxPositions(boxPositions);

        // Set the new boxes on the board.
        for (int boxNo = 0; boxNo < board.boxCount; boxNo++) {
            board.setBoxWithNo(boxNo, boxPositions[boxNo]);
        }
    }

    private BoardPosition addToTranspositionTable(BoardPosition boardPosition) {
        return transpositionTable.put(boardPosition, boardPosition);
    }

}


/**
 * BoardPosition to store one state the solver has generated.
 *
 * A board position for the 0-space puzzles doesn't need to store the player position,
 * since the player can always reach all positions (that are not a box or a wall).
 */
class BoardPosition {

    protected static final int[] zobristKeys = new int[Settings.maximumBoardSize * Settings.maximumBoardSize];
    static {
        Random random = new Random(42);
        for (int i = 0; i < zobristKeys.length; i++) {
            zobristKeys[i] = random.nextInt();
        }
    }

    private int[] boxPositions = null;

    public int hashcode = 0;
    public boolean isBackwardBoardPosition = false;
    public BoardPosition previousBoardPosition = null;

    public BoardPosition() {}

    public BoardPosition(int[] boxPositions, boolean isBackwardBoardPosition, BoardPosition previousBoardPosition) {
        this.boxPositions = boxPositions;
        Arrays.sort(boxPositions);

        for (int position : boxPositions) {
            hashcode ^= zobristKeys[position];
        }

        this.isBackwardBoardPosition = isBackwardBoardPosition;
        this.previousBoardPosition = previousBoardPosition;
    }

    public int[] getBoxPositions() {
        return boxPositions;
    }

    public void fillBoxPositions(int[] boxPositionsToFill) {
        System.arraycopy(boxPositions, 0, boxPositionsToFill, 0, boxPositions.length);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BoardPosition)) return false;
        BoardPosition otherBoardPosition = (BoardPosition) o;
        return Arrays.equals(getBoxPositions(), otherBoardPosition.getBoxPositions());
    }

    @Override
    public int hashCode() {
        return hashcode;
    }
}

class DeltaBoardPosition extends BoardPosition {

    int oldBoxPosition = -1;
    int newBoxPosition = -1;

    
    public DeltaBoardPosition(int oldBoxPosition, int newBoxPosition, boolean isBackwardBoardPosition, BoardPosition previousBoardPosition) {
        this.oldBoxPosition = oldBoxPosition;
        this.newBoxPosition = newBoxPosition;
        this.isBackwardBoardPosition = isBackwardBoardPosition;
        this.previousBoardPosition = previousBoardPosition;

        this.hashcode = previousBoardPosition.hashcode ^ zobristKeys[oldBoxPosition] ^ zobristKeys[newBoxPosition];
    }

    /** Calculates the box positions of the delta board position. */
    @Override
    public int[] getBoxPositions() {
        ArrayList<BoardPosition> deltaBoardPositions = new ArrayList<>();

        BoardPosition boardPosition = this;

        while(boardPosition.previousBoardPosition != null) {
            deltaBoardPositions.add(0, boardPosition);
            boardPosition = boardPosition.previousBoardPosition;
        }

        int[] boxPositions = boardPosition.getBoxPositions().clone();   // Get the box positions of the parent board position, which is not a delta board position.

        for(BoardPosition deltaBoardPosition : deltaBoardPositions) {
            DeltaBoardPosition delta = (DeltaBoardPosition) deltaBoardPosition;
            int index = -1;
            for (int i = 0; i < boxPositions.length; i++) {
                if (boxPositions[i] == delta.oldBoxPosition) {
                    index = i;
                    break;
                }
            }
            if(index < 0) {
                System.out.println("Error: DeltaBoardPosition has no oldBoxPosition in parent BoardPosition.");
            }
            boxPositions[index] = delta.newBoxPosition;
        }

        Arrays.sort(boxPositions);

        return boxPositions;
    }

    @Override
    public void fillBoxPositions(int[] boxPositionsToFill) {

        ArrayList<BoardPosition> deltaBoardPositions = new ArrayList<>();

        BoardPosition boardPosition = this;

        while(boardPosition.previousBoardPosition != null) {
            deltaBoardPositions.add(0, boardPosition);
            boardPosition = boardPosition.previousBoardPosition;
        }

        int[] boxPositionsParent = boardPosition.getBoxPositions();    // Get the box positions of the parent board position, which is not a delta board position.
        System.arraycopy(boxPositionsParent, 0, boxPositionsToFill, 0, boxPositionsParent.length);

        for(BoardPosition deltaBoardPosition : deltaBoardPositions) {
            DeltaBoardPosition delta = (DeltaBoardPosition) deltaBoardPosition;
            int index = -1;
            for (int i = 0; i < boxPositionsToFill.length; i++) {
                if (boxPositionsToFill[i] == delta.oldBoxPosition) {
                    index = i;
                    break;
                }
            }
            if(index < 0) {
                System.out.println("Error: DeltaBoardPosition has no oldBoxPosition in parent BoardPosition.");
            }
            boxPositionsToFill[index] = delta.newBoxPosition;
        }

        Arrays.sort(boxPositionsToFill);
    }
}
/**
 *  JSoko - A Java implementation of the game of Sokoban
 *  Copyright (c) 2024 by Matthias Meger, Germany
 *
 *  This file is part of JSoko.
 *
 *  JSoko is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 3 of the License, or
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
package de.sokoban_online.jsoko.solver;

import de.sokoban_online.jsoko.JSoko;
import de.sokoban_online.jsoko.boardpositions.*;
import de.sokoban_online.jsoko.leveldata.solutions.Solution;
import de.sokoban_online.jsoko.pushesLowerBoundCalculation.LowerBoundCalculation;
import de.sokoban_online.jsoko.resourceHandling.Texts;
import de.sokoban_online.jsoko.utilities.Utilities;

import java.util.ArrayList;
import java.util.HashSet;

import static de.sokoban_online.jsoko.optimizer.AllMetricsOptimizer.BoardPositionsStorage.NONE;


/**
 * This solver solves a Sokoban puzzle that can be solved with the same number
 * of moves and pushes.
 * This solver can only be used for this special type of puzzles!
 */
public class SolverMovesEqualsPushes extends Solver {

    /** The last board position on the found solution path. */
    BoardPositionMovesEqualsPushesSolver solutionBoardPosition = null;

    ArrayList<BoardPositionMovesEqualsPushesSolver> openQueue = new ArrayList<>(200000);

    public SolverMovesEqualsPushes(JSoko application, SolverGUI solverGUI) {
        super(application, solverGUI);
    }

    @Override
    public Solution searchSolution() {

        boardPositionsCount = 0;

        BoardPositionMovesEqualsPushesSolver currentBoardPosition = new BoardPositionMovesEqualsPushesSolver(board, NO_BOX_PUSHED, NONE, null);
        BoardPositionMovesEqualsPushesSolver startBoardPosition = new BoardPositionMovesEqualsPushesSolver(board, NO_BOX_PUSHED, NONE, null);


        openQueue.add(currentBoardPosition);

        boardPositionsCount++;

        for(int boxNo = 0; boxNo < board.boxCount; boxNo++) {
            board.removeBox(board.boxData.getBoxPosition(boxNo));
        }

        boolean isSolutionFound = forwardSearch();  // Main search!

        if(isSolutionFound) {
            publish(Texts.getText("solved"));
        }
        else {
            publish(Texts.getText("solver.noSolutionFound"));
        }

        // If no solution has been found, clear the position storage and set back
        // the board position that has been set as the solver has been started.
        if(!isSolutionFound){
            setBoardPositionOnBoard(startBoardPosition);
            return null;
        }

        // Create a list of pushes of the solution.
        ArrayList<BoardPositionMovesEqualsPushesSolver> pushes = new ArrayList<>();
        for( currentBoardPosition = solutionBoardPosition
           ; currentBoardPosition.parentBoardPosition !=  null
           ; currentBoardPosition = currentBoardPosition.parentBoardPosition) {
            if(currentBoardPosition.pushedBoxPosition != NO_BOX_PUSHED) {
                pushes.add(0, currentBoardPosition);
            }
        }

        // Restore the start board position.
        setBoardPositionOnBoard(startBoardPosition);

        int currentIndex = application.movesHistory.getCurrentMovementNo();

        // Set move history according to the found solution.
        // This is necessary, since the user may have started the solver having
        // already done some pushes on the board.
        for (BoardPositionMovesEqualsPushesSolver push : pushes) {
            currentBoardPosition = push;

            int pushedBoxPosition = currentBoardPosition.pushedBoxPosition;
            int direction = currentBoardPosition.pushDirection;
            application.movesHistory.addMovement(direction, board.getBoxNo(push.playerPosition));

            board.pushBox(push.playerPosition, push.pushedBoxPosition);
        }

        application.movesHistory.setMovementNo(currentIndex);

        for(int position=board.firstRelevantSquare; position<board.lastRelevantSquare; position++) {
            board.removeBox(position);
        }
        setBoardPositionOnBoard(startBoardPosition);

        Solution newSolution = new Solution(application.movesHistory.getLURDFromHistoryTotal());
        newSolution.name = solutionByMeNow();

        return newSolution;
    }

    protected boolean forwardSearch() {
        
        while(!openQueue.isEmpty() && !isCancelled()) {

            BoardPositionMovesEqualsPushesSolver currentBoardPosition = openQueue.remove(0);

            setBoardPositionOnBoard(currentBoardPosition);

            for(int direction = 0; direction < 4; direction++) {

                int newPlayerPosition = currentBoardPosition.playerPosition + offset[direction];
                int newBoxPosition =  newPlayerPosition + offset[direction];

                if(!board.isBox(newPlayerPosition) || !board.isAccessibleBox(newBoxPosition)) {
                    continue;
                }

                board.pushBox(newPlayerPosition, newBoxPosition);
                board.playerPosition = newPlayerPosition;

                BoardPositionMovesEqualsPushesSolver newBoardPosition = new BoardPositionMovesEqualsPushesSolver(board, newBoxPosition, direction, currentBoardPosition);
                newBoardPosition.pushCount = currentBoardPosition.pushCount + 1;

                if(deadlockDetection.freezeDeadlockDetection.isDeadlock(newBoxPosition, false)) {
                    board.pushBoxUndo(newBoxPosition, newPlayerPosition);
                    continue;
                }

                boolean isSolved = board.isBoxOnGoal(newBoxPosition) && board.boxData.isEveryBoxOnAGoal();

                board.pushBoxUndo(newBoxPosition, newPlayerPosition);

                if(isSolved) {
                    solutionBoardPosition = newBoardPosition;
                    return true;
                }

                boardPositionsCount++;

                if((boardPositionsCount & 511) == 0) {

                    // Throw "out of memory" if less than 15MB RAM is free.
                    if(Utilities.getMaxUsableRAMinMiB() <= 15) {
                        isSolverStoppedDueToOutOfMemory = true;
                        cancel(true);
                    }

                    publish(Texts.getText("numberofpositions")+boardPositionsCount+", "+
                                    Texts.getText("searchdepth")+currentBoardPosition.pushCount);
                }

                openQueue.add(newBoardPosition);
            }
        }
        return false;
    }

    public void setBoardPositionOnBoard(BoardPositionMovesEqualsPushesSolver boardPosition) {

        board.removeAllBoxes();
        board.boxData.setBoxPositions(boardPosition.boxPositions);

        for (int boxNo = 0; boxNo < board.boxCount; boxNo++) {
            board.setBoxWithNo(boxNo, boardPosition.boxPositions[boxNo]);
        }

        board.playerPosition = boardPosition.playerPosition;
    }
}
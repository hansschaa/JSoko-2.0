/**
 *  JSoko - A Java implementation of the game of Sokoban
 *  Copyright (c) 2012 by Matthias Meger, Germany
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
package de.sokoban_online.jsoko.boardpositions;

import de.sokoban_online.jsoko.board.Board;

import java.util.Arrays;
import java.util.Objects;

public class BoardPositionMovesEqualsPushesSolver {

    public int[] boxPositions;
    public int playerPosition = -1;
    public int pushedBoxPosition = -1;
    public int pushDirection = -1;

    public int pushCount = 0;

    public BoardPositionMovesEqualsPushesSolver parentBoardPosition;

    public BoardPositionMovesEqualsPushesSolver(Board board, int pushedBoxPosition, int pushDirection, BoardPositionMovesEqualsPushesSolver parentBoardPosition) {
        boxPositions = board.boxData.getBoxPositionsClone();
        Arrays.sort(boxPositions);
        this.playerPosition = board.playerPosition;
        this.pushedBoxPosition = pushedBoxPosition;
        this.pushDirection = pushDirection;
        this.parentBoardPosition = parentBoardPosition;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BoardPositionMovesEqualsPushesSolver that = (BoardPositionMovesEqualsPushesSolver) o;
        return playerPosition == that.playerPosition && Objects.deepEquals(boxPositions, that.boxPositions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(boxPositions), playerPosition);
    }
}
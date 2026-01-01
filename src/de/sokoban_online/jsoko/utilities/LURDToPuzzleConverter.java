package de.sokoban_online.jsoko.utilities;

import java.util.Arrays;
/*
 *   Sokoban4U - An implementation of the Sokoban game.
 *   Copyright (c) 2024 by Matthias Meger, Germany
 *
 *   This file is part of Sokoban4U.
 *
 *   Sokoban4U is free software; you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation; either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http:></http:>//www.gnu.org/licenses/>.
 */

/**
 * This class generates Sokoban puzzle boards based on provided puzzle solutions.
 * For more information see [convertLURDToPuzzle].
 */
public class LURDToPuzzleConverter {

    private static class BoardData {
        int width;
        int height;
        Coordinates playerCoordinates;

        BoardData(int width, int height, Coordinates playerCoordinates) {
            this.width = width;
            this.height = height;
            this.playerCoordinates = playerCoordinates;
        }
    }

    private static class Coordinates {
        int x;
        int y;

        Coordinates(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final char BOX = '$';
    private static final char BOX_ON_GOAL = '*';
    private static final char PLAYER = '@';
    private static final char PLAYER_ON_GOAL = '+';
    private static final char GOAL = '.';
    private static final char UNREACHED = '-';
    private static final char FLOOR = ' ';
    private static final char WALL = '#';

    /**
     * This function generates Sokoban puzzle boards based on provided puzzle solutions.
     *
     * Solution encoding:
     *   Lowercase letters denote moves where the player doesn't push a box:
     *        u: Up
     *        l: Left
     *        d: Down
     *        r: Right
     *    Uppercase letters represent the same directions as lowercase letters,
     *    but indicate the player is pushing a box in that direction.
     *
     * Example usage:
     * val puzzleAsString = LURDToPuzzleConverter().convertLURDToPuzzle("RR")
     * returns this puzzle:
     *     ######
     *     #@$ .#
     *     ######
     *
     * In case the given lurd string is invalid, an empty string is returned.
     * A lurd string is considered invalid in these cases:
     * * an uppercase letter represents a push of a box, but there is no box
     * * a lowercase letter represents a move without a push, but there is a box
     * * a box is to be pushed but there is another box at the new board position blocking the push
     *
     * Characters other than u,d,l,r, U, D, L and R in the lurd string are ignored.
     * Unnecessary trailing moves are also taken into account, even though the lurd string is misshapen (example: RRllu).
     *
     * Note: a lurd string consisting only of lowercase letters is considered valid.
     * The resulting puzzle has no boxes or goals. However, sometimes it may be useful
     * to also create a puzzle from moves only to see the shape of the puzzle.
     */
    public String convertLURDToPuzzle(String lurdString) {

        if (lurdString.isBlank()) return "";

        String onlyLURD = lurdString.replaceAll("[^udlrUDLR]", "");

        BoardData boardData = determineBoardData(onlyLURD);
        int boardWidth = boardData.width;
        int boardHeight = boardData.height;
        Coordinates initialPlayerCoordinates = boardData.playerCoordinates;

        char[][] board = new char[boardWidth][boardHeight];
        for (char[] row : board) {
            Arrays.fill(row, UNREACHED);
        }
        boolean[][] isInitialBoxPosition = new boolean[boardWidth][boardHeight];

        Coordinates playerPosition = initialPlayerCoordinates;
        board[playerPosition.x][playerPosition.y] = FLOOR;

        for (char moveChar : onlyLURD.toCharArray()) {                                    // Main loop performing all moves of the player
            playerPosition = getCoordinatesAfterMove(playerPosition, moveChar);

            if (Character.isUpperCase(moveChar)) {
                if (board[playerPosition.x][playerPosition.y] != BOX) {
                    if (board[playerPosition.x][playerPosition.y] != UNREACHED) return "";  // the player reaches a position already reached before, but now there should be a box?!
                    board[playerPosition.x][playerPosition.y] = BOX;                        // First touch of the box => set the box
                    isInitialBoxPosition[playerPosition.x][playerPosition.y] = true;
                }
            }

            if (board[playerPosition.x][playerPosition.y] == BOX) {
                if (Character.isLowerCase(moveChar)) return "";          // invalid lurd string!

                Coordinates newBoxPosition = getCoordinatesAfterMove(playerPosition, moveChar);
                if (board[newBoxPosition.x][newBoxPosition.y] == BOX) return "";

                board[newBoxPosition.x][newBoxPosition.y] = BOX;
            }

            board[playerPosition.x][playerPosition.y] = FLOOR;
        }

        for (int y = 1; y < boardHeight - 1; y++) {
            for (int x = 1; x < boardWidth - 1; x++) {
                char currentChar = board[x][y];
                if (currentChar == FLOOR || currentChar == BOX) {        // active board (-> player and/or box reachable)
                    setWallsAtSurroundingUnreachedPositions(board, x, y);
                    if (currentChar == BOX) {
                        board[x][y] = GOAL;                              // end position of box => it must be a goal
                    }
                }

                if (isInitialBoxPosition[x][y]) {
                    board[x][y] = (board[x][y] == GOAL) ? BOX_ON_GOAL : BOX;
                }

                if (x == initialPlayerCoordinates.x && y == initialPlayerCoordinates.y) {
                    board[x][y] = (board[x][y] == GOAL) ? PLAYER_ON_GOAL : PLAYER;
                }
            }
        }

        return getBoardAsString(boardHeight, boardWidth, board);
    }

    private String getBoardAsString(int boardHeight, int boardWidth, char[][] board) {
        String boardAsString = "";
        for (int y = 0; y < boardHeight; y++) {
            String row = "";
            for (int x = 0; x < boardWidth; x++) {
                row += board[x][y];
            }
            row = row.replace(UNREACHED, FLOOR).replaceAll("\\s+$", "");
            boardAsString += row + "\n";
        }
        return boardAsString;
    }

    /** Sets a wall to the eight neighbors if they have never been reached by the player or a box. */
    private void setWallsAtSurroundingUnreachedPositions(char[][] board, int x, int y) {
        for (int yOffset = -1; yOffset <= 1; yOffset++) {
            for (int xOffset = -1; xOffset <= 1; xOffset++) {
                if (board[x + xOffset][y + yOffset] == UNREACHED) {
                    board[x + xOffset][y + yOffset] = WALL;
                }
            }
        }
    }

    /** Get the coordinates after doing a move/push. */
    private Coordinates getCoordinatesAfterMove(Coordinates currentCoordinates, char moveChar) {
        switch (Character.toLowerCase(moveChar)) {
            case 'u':
                return new Coordinates(currentCoordinates.x, currentCoordinates.y - 1);
            case 'd':
                return new Coordinates(currentCoordinates.x, currentCoordinates.y + 1);
            case 'l':
                return new Coordinates(currentCoordinates.x - 1, currentCoordinates.y);
            case 'r':
                return new Coordinates(currentCoordinates.x + 1, currentCoordinates.y);
            default:
                return currentCoordinates;
        }
    }

    /** Determines the board width and height and the player coordinates from the lurd string. */
    private BoardData determineBoardData(String lurdString) {
        int minX = 0;
        int minY = 0;
        int maxX = 0;
        int maxY = 0;

        int x = 0;
        int y = 0;

        for (char move : lurdString.toCharArray()) {
            switch (move) {
                case 'u':
                    minY = Math.min(minY, --y);
                    break;
                case 'd':
                    maxY = Math.max(maxY, ++y);
                    break;
                case 'l':
                    minX = Math.min(minX, --x);
                    break;
                case 'r':
                    maxX = Math.max(maxX, ++x);
                    break;
                case 'U':
                    minY = Math.min(minY, --y - 1);
                    break;
                case 'D':
                    maxY = Math.max(maxY, ++y + 1);
                    break;
                case 'L':
                    minX = Math.min(minX, --x - 1);
                    break;
                case 'R':
                    maxX = Math.max(maxX, ++x + 1);
                    break;
            }
        }

        int width = maxX - minX + 3;  // +1 for player column and +2 for surrounding walls
        int height = maxY - minY + 3; // +1 for player row and +2 for surrounding walls
        Coordinates playerCoordinates = new Coordinates(-minX + 1, -minY + 1);

        return new BoardData(width, height, playerCoordinates);
    }
}

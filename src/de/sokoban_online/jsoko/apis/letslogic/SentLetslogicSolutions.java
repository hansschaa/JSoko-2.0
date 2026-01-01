package de.sokoban_online.jsoko.apis.letslogic;

import java.util.Date;

import de.sokoban_online.jsoko.leveldata.Database;
import de.sokoban_online.jsoko.leveldata.solutions.Solution;
import de.sokoban_online.jsoko.utilities.Utilities;

/**
 * A 'SentLetslogicSolution" object holds all data for a
 * solution submitted to Letslogic.com.
 *
 * Data for all submitted solutions is stored in the JSoko
 * database. This way JSoko ensure only new better solutions
 * are submitted to Letslogic which is a lot quicker than
 * submitting all solutions again.
 */
public class SentLetslogicSolutions {
    public int  databaseID          = Database.NO_ID;
    public String apiKey            = "";
    public int  letslogicLevelID    = -1;

    public int  bestMovesMoveCount  = Integer.MAX_VALUE;
    public int  bestMovesPushCount  = Integer.MAX_VALUE;

    public int  bestPushesMoveCount = Integer.MAX_VALUE;
    public int  bestPushesPushCount = Integer.MAX_VALUE;

    public Date lastChanged         = new Date(System.currentTimeMillis());

    /**
     * Returns whether the passed compareSolution is a better moves/pushes solution than the
     * solution already submitted to Letslogic.
     * @param compareSolution  the solution to compare this solution with
     * @return <code>true</code> if the compareSolution is a better moves/pushes solution, <code>false</code> otherwise
     */
    public boolean isWorseMovesSolutionThan(Solution compareSolution) {
        return Utilities.intCompare2Pairs(bestMovesMoveCount, compareSolution.movesCount, bestMovesPushCount, compareSolution.pushesCount) > 0;
    }

    /**
     * Returns whether the passed compareSolution is a better pushes/moves solution than the
     * solution already submitted to Letslogic.
     * @param compareSolution  the solution to compare this solution with
     * @return <code>true</code> if the compareSolution is a better pushes/moves solution, <code>false</code> otherwise
     */
    public boolean isWorsePushesSolutionThan(Solution compareSolution) {
        return Utilities.intCompare2Pairs(bestPushesPushCount, compareSolution.pushesCount, bestPushesMoveCount, compareSolution.movesCount) > 0 ;
    }
}
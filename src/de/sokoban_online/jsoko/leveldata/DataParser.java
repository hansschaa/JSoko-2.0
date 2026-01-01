/**
 *  JSoko - A Java implementation of the game of Sokoban
 *  Copyright (c) 2017 by Matthias Meger, Germany
 *
 *  This file is part of JSoko.
 *
 *	JSoko is free software; you can redistribute it and/or modify
 *	it under the terms of the GNU General Public License as published by
 *	the Free Software Foundation; either version 3 of the License, or
 *	(at your option) any later version.
 *
 *	This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.sokoban_online.jsoko.leveldata;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import de.sokoban_online.jsoko.leveldata.solutions.Solution;
import de.sokoban_online.jsoko.leveldata.solutions.SolutionsManager;
import de.sokoban_online.jsoko.leveldata.solutions.SolutionsManager.SolutionType;
import de.sokoban_online.jsoko.resourceHandling.Texts;
import de.sokoban_online.jsoko.utilities.Utilities;

/**
 *  This class is used for parsing input data to extract all data JSoko can process.
 */
public class DataParser {

	/** Reference to the main object that holds references to all other objects. */
    private final LevelsIO levelsIO;

    /** Pattern for searching strings that only contain characters that represents moves in a solution. */
    private static final Pattern SOLUTION_CHARACTERS_PATTERN = Pattern.compile("^[lurdLURD()0-9]+$");

	/**
     * A parser for input data to extract all data JSoko can process.
     *
     * @param levelsIO  manages the storage of the level data
     */
    public DataParser(LevelsIO levelsIO) {
        this.levelsIO = levelsIO;
	}

	/**
	 * Extracts the collection data from the passed string data.
	 *
	 * @param inputData data which contains the collection data
	 * @param collectionFilePath file path of the collection has been loaded from
	 *
	 * @return <code>LevelCollection</code> created from the passed inputData
	 */
	public LevelCollection extractData(List<String> inputData, String collectionFilePath) {

		Level level = null;

		int index = 0;			// The index of a specific string in the data.

		String levelTitle = "";
		int levelNumber = 1;
		String collectionTitle = "";
		Author.Builder levelAuthor = new Author.Builder();
		Author.Builder collectionAuthor = new Author.Builder();

		ArrayList<Level> collectionLevels = new ArrayList<>();
		String levelCollectionComment = "";
		ArrayList<String> levelComment = new ArrayList<>();

		inputData = deleteMailQuotes(inputData);
		inputData = splitNewLines(inputData);
		inputData = decodeRunLengthEncodedBoardData(inputData);

		// Add a dummy last line to the input data for easier parsing.
		inputData.add("");

		for (int i = 0; i < inputData.size(); i++) {

			String dataRow = inputData.get(i);
			String levelDataRowToLowerCase = dataRow.toLowerCase();

			// Lines starting and ending with "::" are considered file format description lines
			// creating by the program "Sokoban YASC". They are added as comment.
			String trimmedDataRow = dataRow.trim();
			if(trimmedDataRow.startsWith("::") && trimmedDataRow.endsWith("::")) {
				levelComment.add(trimmedDataRow);
				continue;
			}

			// Check if the title is stated explicitly.
			if ((index = levelDataRowToLowerCase.lastIndexOf("title:")) == -1) {
				index = levelDataRowToLowerCase.lastIndexOf("collection:");
			}
			if (index != -1) {

				String title = dataRow.substring(dataRow.indexOf(":", index) + 1).trim();
				if (level == null) {				// If the title is stated before any level data, it's the collection title.
					collectionTitle = title;		// Otherwise, it's the title of the level.
				} else {
					level.setLevelTitle(title);
				}
				continue;							// This line doesn't contain any other useful information.
			}

			// Check if the author name is set in the file.
			if ((index = levelDataRowToLowerCase.lastIndexOf("author:")) == -1) {
				if ((index = levelDataRowToLowerCase.lastIndexOf("author :")) == -1) {
					index = levelDataRowToLowerCase.lastIndexOf("authors :");
				}
			}
			if (index != -1) {
				String authorName = dataRow.substring(dataRow.indexOf(":", index) + 1).trim();
				if (level == null) {						// If no level is stored, yet it's the author name of the collection.
					collectionAuthor.setName(authorName);	// Otherwise, it's the author of the current level.
				} else {
					levelAuthor.setName(authorName);
				}
				continue;							// This line doesn't contain any other useful information.
			}

			// Set the author's email if stated in the data.
			index = levelDataRowToLowerCase.lastIndexOf("email:");
			if (index != -1) {
				if (level == null) {
					collectionAuthor.setEmail(dataRow.substring(index + 6).trim());
				} else {
					levelAuthor.setEmail(dataRow.substring(index + 6).trim());
				}
				continue;							// This line doesn't contain any other useful information.
			}

			// Set the author's homepage if stated in the data.
			index = levelDataRowToLowerCase.lastIndexOf("homepage:");
			if (index != -1) {
				if (level == null) {
					collectionAuthor.setWebsiteURL(dataRow.substring(index + 9).trim());
				} else {
					levelAuthor.setWebsiteURL(dataRow.substring(index + 9).trim());
				}
				continue;							// This line doesn't contain any other useful information.
			}

			// Set the author's comment if stated in the data (currently only one line is taken as comment)
			index = levelDataRowToLowerCase.lastIndexOf("author comment:");
			if (index != -1) {
				if (level == null) {
					collectionAuthor.setComment(dataRow.substring(index + 15).trim());
				} else {
					levelAuthor.setComment(dataRow.substring(index + 15).trim());
				}
				continue;							// This line doesn't contain any other useful information.
			}

			// The level-specific keywords must be located UNDER the board data.
			// If no board data has been found, the parsing of the keywords can be skipped.
			if (level != null) {

				// Set a new view of the level if it is stated in the data.
				if (levelDataRowToLowerCase.lastIndexOf("view:") != -1) {
					level.setTransformationString(dataRow);
					continue;						// This line doesn't contain any other useful information.continue;
				}

				// Set the difficulty of the level if stated in the data.
				index = levelDataRowToLowerCase.lastIndexOf("difficulty:");
				if (index != -1) {
					level.setDifficulty(dataRow.substring(index + 11).trim());
					continue;						// This line doesn't contain any other useful information.
				}

				// Set the letslogic level ID:
				index = levelDataRowToLowerCase.lastIndexOf("id:");
				if (index != -1) {
					try {
						int id = Integer.parseInt(dataRow.substring(index + 3).trim());

						if(level.getLetsLogicID() == -1) {    // Some levels contain the string id: multiple times
						    level.setLetsLogicID(id);         // Then only the first found id must be used.
						}
					} catch (NumberFormatException e) {}
					continue;
				}

				// Set the solution of the level if stated.
				if (levelDataRowToLowerCase.contains("solution")) {

					StringBuilder solutionLURD = new StringBuilder();

					// The following lines in the file belong to the solution string.
					for (++i; i < inputData.size(); i++) {

						// Read line after line of the solution
						dataRow = inputData.get(i).trim();

						// As long no solution line has been read in empty lines are jumped over.
						if (solutionLURD.length() == 0 && dataRow.isEmpty()) {
							continue;
						}

						// The solution ends when there is a none LURD-character in it.
                        if (!dataRow.matches("^[lurdLURD()0-9]+$") || dataRow.matches("^[0-9()]+$")) {
                            break;
                        }

						// Concatenate the current line to the whole LURD-string
						solutionLURD.append(dataRow);
					}

					// Set the solution of the level if a solution has been found.
					if (solutionLURD.length() > 0) {

						String solution = solutionLURD.toString();

						// Run length decode the solution if it is run length encoded.
						if (solution.matches(".*[0-9()]+.*")) {
							solution = RunLengthFormat.runLengthDecode(solution);
						}

						SolutionType solutionType = level.addSolution(new Solution(solution));
						if(solutionType.isValidNewSolution()) {
							// The next loop must use the current line.
							i--;
							continue;
						}
					}

					// The row contains the word "solution" but there aren't any solution data.
					while (!inputData.get(--i).trim().toLowerCase().contains("solution")) {
						;
					}
					dataRow = inputData.get(i);
					levelDataRowToLowerCase = dataRow.toLowerCase();
				}

				// Determine whether the level has at least one solution.
				if (level.getSolutionsManager().getSolutionCount() > 0) {

					// Set the name of the solution if stated in the data.
					index = levelDataRowToLowerCase.lastIndexOf("solution name:");
					if (index != -1) {
						SolutionsManager solutions = level.getSolutionsManager();
						solutions.getSolution(solutions.getSolutionCount() - 1).name = dataRow.substring(index + 14).trim();

						// This line doesn't contain any other useful information.
						continue;
					}

					// Set the "own solution" flag if stated in the data.
					index = levelDataRowToLowerCase.lastIndexOf("own solution:");
					if (index != -1 && dataRow.toLowerCase().lastIndexOf("yes") != -1) {
						SolutionsManager solutions = level.getSolutionsManager();
						solutions.getSolution(solutions.getSolutionCount() - 1).isOwnSolution = true;

						// This line doesn't contain any other useful information.
						continue;
					}

					// Set the comment of the solution if stated in the data.
					index = levelDataRowToLowerCase.lastIndexOf("solution comment:");
					if (index != -1) {

						StringBuilder solutionComment = new StringBuilder(dataRow.substring(index + 17).trim());

						while (i + 1 < inputData.size() - 1) {
							dataRow = inputData.get(++i).trim();
							if (dataRow.toLowerCase().lastIndexOf("solution comment end:") != -1 || dataRow.isEmpty()) {
								break;
							}
							solutionComment.append("\n").append(dataRow);
						}

						SolutionsManager solutions = level.getSolutionsManager();
						solutions.getSolution(solutions.getSolutionCount() - 1).comment = solutionComment.toString();

						// This line doesn't contain any other useful information.
						continue;
					}
				}

				// Set a saved game if stated in the data.
				if (levelDataRowToLowerCase.lastIndexOf("savegame") != -1) {

					StringBuilder historyLURD = new StringBuilder();

					// The following lines in the file belong to the history string.
					for (++i; i < inputData.size(); i++) {

						// Read line after line of the save game.
						dataRow = inputData.get(i).trim();

						// The lurd string ends when it doesn't contain any lurd-character or the
						// data row is empty. A "*" indicates the position in the history as the
						// game has been saved.
						if (!dataRow.matches("^[lurdLURD*()0-9]+$") || dataRow.isEmpty()) {
							break;
						}

						// Concatenate the current line to the whole LURD-string
						historyLURD.append(dataRow);
					}

					// Set the history string for the level if one has been found.
					if (historyLURD.length() > 0) {

						String historyString = historyLURD.toString();

						// Run length decode the string if it is run length encoded.
						if (historyString.matches(".*[0-9()]+.*")) {
							historyString = RunLengthFormat.runLengthDecode(historyString);
						}

						Snapshot snapshot = new Snapshot(historyString);
						snapshot.setAutoSaved(true); // it's a save game -> set auto saved to "true"
						level.addSnapshot(snapshot);

						// The next loop must use the current line.
						i--;
						continue;
					}

					// The row contains the word "savegame" but there aren't any history data.
					// Go back to the row containing the word "savegame" -> it must be saved as a comment.
					while (!inputData.get(--i).trim().toLowerCase().contains("savegame")) {
						;
					}
					dataRow = inputData.get(i);
					levelDataRowToLowerCase = dataRow.toLowerCase();
				}

				// Set a snapshot if stated in the data.
				if (levelDataRowToLowerCase.lastIndexOf("snapshot") != -1) {

					StringBuilder movesHistory = new StringBuilder();

					// The following lines in the file belong to the snapshot.
					for (++i; i < inputData.size(); i++) {

						// Read line after line of the snapshot.
						dataRow = inputData.get(i).trim();

						// The lurd string ends when it doesn't contain any lurd-character or the
						// data row is empty. A "*" indicates the position in the history as the game has been saved.
						if (!dataRow.matches("^[lurdLURD*()0-9]+$") || dataRow.isEmpty()) {
							break;
						}

						// Concatenate the current line to the whole LURD-string
						movesHistory.append(dataRow);
					}

					// Set the snapshot string for the level if one has been found.
					if (movesHistory.length() > 0) {

						String moves = movesHistory.toString();

						Snapshot snapshot = moves.matches(".*[()0-9]+.*") ?
								new Snapshot(RunLengthFormat.runLengthDecode(moves)) : new Snapshot(moves);

						level.addSnapshot(snapshot);

						// The next loop must use the current line.
						i--;
						continue;
					}

					// The row contains the word "snapshot" but there aren't any relevant data.
					// Go back to the row containing the word "snapshot" -> it must be saved as a comment.
					while (!inputData.get(--i).trim().toLowerCase().contains("snapshot")) {
						;
					}
					dataRow = inputData.get(i);
					levelDataRowToLowerCase = dataRow.toLowerCase();
				}
			}

			// All rows that don't contain any board data are saved as comment. "-" and "_" are
			// treated as board data because they are sometimes used as character for a floor.
			// At least one level element including "-" and "_", but not only "-", "_" or " " or 0 - 9 or ".".
			// (the last row has just been created for easier parsing. It doesn't contain any data)
			if (i != inputData.size() - 1 &&
			        ( !containsOnlyBoardCharacters(dataRow) ||
                    dataRow.chars().filter(ch -> ch == '+' || ch == '@').count() > 1 ||     // there are level titles like "+|+"
                    inputData.get(i+1).isEmpty())) {                                        // boards having only one line aren't valid boards

				// The current row doesn't contain key data (solutions, author, ...) nor board data.
				// Hence, it is treated as a comment.
				levelComment.add(dataRow);

				continue;
			}

			// The board data of a new level begins. This also means the comment of the last level
			// ends. Now the collected comments must be parsed for checking if they contain the level
			// title of the current level.
			for (index = levelComment.size(); --index >= 0;) {

				// Jump over all trailing empty rows.
				if (levelComment.get(index).trim().isEmpty()) {
					continue;
				}

				StringBuilder levelCommentString = new StringBuilder();

				// It's the level title if the preceding row is an empty one.
				// Example:
				// #####
				// #@$.#
				// #####
				// comment 1
				// comment 2
				//
				// title (because preceding empty row)
				//
				// #####
				// #@$.#
				// #####
				// (If it's the last row in the data file, it's the comment in every case)
				if (i != inputData.size() - 1 			// dummy row reached -> end of file
				&& (index == 0 || levelComment.get(index - 1).trim().isEmpty())) { // Preceding empty row or no preceding row at all
					levelTitle = levelComment.get(index);

					// The comment of the last level contains the rows up to the
					// first none empty row before the level title.
					index -= 2;

					// Jump over all preceding empty rows.
					while (index >= 0 && levelComment.get(index).trim().isEmpty()) {
						index--;
					}
				}

				// Concatenate the comment of the level to one String.
				for (int commentRow = 0; commentRow <= index; commentRow++) {
					String comment = levelComment.get(commentRow);

					// Discard empty rows at the beginning of the comment.
					if (levelCommentString.length() == 0 && comment.trim().isEmpty()) {
						continue;
					}

					levelCommentString.append(comment).append("\n");
				}

				if (level != null) {
					level.setComment(levelCommentString.toString());        	    // Set the comment of the level.
				} else {															// If there isn't any level yet,
					levelCollectionComment = levelCommentString.toString(); 		// it's the comment for the collection.
				}

				break;
			}

			// Add the level to the collection.
			if (level != null) {

				Author author = levelAuthor.build();

				// By default, the author of the collection is also the level author.
				if (author.getName().equals(Texts.getText("unknown")) && author.getEmail().isEmpty() && author.getWebsiteURL().isEmpty()
						&& author.getComment().isEmpty()) {
					author = collectionAuthor.build(); // All data of collection author has been set at this time!
				}
				level.setAuthor(author);

				level.setNumber(levelNumber);
				levelNumber++;

				/*
				 * The current data row contains board data of a new level. Hence, the data of the current level ends here. The level can be added to the list.
				 */
				collectionLevels.add(level);
			}

			// If it's the last row, the parsing ends: the last row has been added as dummy for easier
			// parsing. It doesn't contain data of a new level.
			if (i == inputData.size() - 1) {
				break;
			}

			// Prepare everything for the next level (every level is saved in an own LevelData object).
            level = new Level(levelsIO.database);
			levelComment = new ArrayList<>();
			levelAuthor = new Author().getBuilder();

			if("".equals(levelTitle)) {
				levelTitle = Texts.getText("level")+" "+levelNumber;
			}

			level.setLevelTitle(levelTitle);
			levelTitle = "";					// Initialize for next level

			ArrayList<String> boardData = new ArrayList<>(3);
			int boardWidth = 0;
			int boxesCount = 0;

			// Index of the first non whitespace character. This information is used to
			// cut unnecessary white spaces in the level rows.
			int indexFirstNonWhitespaceAllRows = Integer.MAX_VALUE;

			// Loop over all data rows containing board data.
			for (; i < inputData.size(); i++) {

				// Get next data row.
				dataRow = inputData.get(i);

				// The board ends when there isn't any board character in the data row or
				// the data row is empty. Empty board rows are valid board rows when they are
				// surrounded by non-empty board rows -> interior board rows.
				if (!containsOnlyBoardCharacters(dataRow)) {

					if(!isInteriorBoardRow(inputData, i)) {
						i--;		// The main loop should take this line so i is decreased by 1.
						break;
					}

					// Interior rows are represented by a single space
					boardData.add(" ");

					continue;
				}

				// Replace all "-" and "_" with spaces
				dataRow = dataRow.replace('-', ' ').replace('_', ' ');

				// Determine the lowest position of a non-whitespace character.
				for (index = 0; index < dataRow.length(); index++) {
					if (!Character.isWhitespace(dataRow.charAt(index))) {
						if (index < indexFirstNonWhitespaceAllRows) {
							indexFirstNonWhitespaceAllRows = index;
						}
						break;
					}
				}

				// Calculate the number of boxes.
				for (char c : dataRow.toCharArray()) {
					if (c == '$' || c == '*') {
						boxesCount++;
					}
				}

				boardData.add(dataRow);
			}

			// Delete all unnecessary leading and trailing spaces.
			for (int rowCounter = boardData.size(); --rowCounter >= 0;) {

				int indexFirstNonWhitespace = 0;

				dataRow = boardData.remove(0);

				// Determine the lowest position of a non-whitespace character.
				for (indexFirstNonWhitespace = 0; indexFirstNonWhitespace < dataRow.length(); indexFirstNonWhitespace++) {
					if (!Character.isWhitespace(dataRow.charAt(indexFirstNonWhitespace))) {
						break;
					}
				}

				dataRow = dataRow.substring(indexFirstNonWhitespaceAllRows, indexFirstNonWhitespace) + dataRow.trim();
				boardData.add(dataRow);

				// Determine level width
				if (dataRow.length() > boardWidth) {
					boardWidth = dataRow.length();
				}
			}

			// Set level height, level width and number of boxes.
			level.setHeight(boardData.size());
			level.setWidth(boardWidth);
			level.setBoxCount(boxesCount);

			// Set the board data in the level.
			level.setBoardData(boardData);

		} // End of loop over all data rows.


		// If there has no collection title been stated explicitly in the file, create an own title.
		if (collectionTitle.isEmpty()) {

			collectionTitle = "Collection " + DateFormat.getDateInstance().format(new Date());

			// The file name without the path is to be set as alternative
			// collection title (if there isn't one stated in the file)
			if(collectionFilePath != null) {
				collectionTitle = Utilities.getFileName(collectionFilePath);

				// Remove any .sok, .txt or .xsb
				List<String> fileSuffixes = Arrays.asList(".sok", ".txt", ".xsb");
				for(String suffix : fileSuffixes ) {
					if(collectionTitle.endsWith(suffix)) {
						collectionTitle = collectionTitle.substring(0, collectionTitle.length()-4);
						break;
					}
				}
			}
		}


		// Collection in which all extracted data is stored in.
		LevelCollection.Builder levelCollectionBuilder = new LevelCollection.Builder();
		levelCollectionBuilder.setTitle(collectionTitle);
		levelCollectionBuilder.setLevels(collectionLevels);
		levelCollectionBuilder.setCollectionFile(collectionFilePath);
		levelCollectionBuilder.setAuthor(collectionAuthor.build());
		levelCollectionBuilder.setComment(levelCollectionComment);

		LevelCollection levelCollection = levelCollectionBuilder.build();

		setLetslogicIDAsComment(levelCollection);

		return levelCollection;
	}

	/*
	 * The Letslogic ID must also be stored as a comment to ensure it
	 * is also stored in the database and exported to a file when a level
	 * is saved on the harddisk.
	 */
	private static void setLetslogicIDAsComment(LevelCollection levelCollection) {

		for (Level level : levelCollection.getLevels()) {
			if (level.getLetsLogicID() != -1) {
				String currentLevelComment = level.getComment();
				String letslogicIDString = "id: " + level.getLetsLogicID();
				level.setComment(
					currentLevelComment.isEmpty() ? letslogicIDString : letslogicIDString + "\n" + currentLevelComment
				);
			}
		}
	}

	/**
	 * Returns whether the passed data row contains only valid board characters - including run length encoded ones.
	 *
	 * @param dataRow
	 *            one data row to check for being a board row
	 * @return <code>true</code> the passed row is a part of a level board, <code>false</code> otherwise
	 */
	private boolean containsOnlyBoardCharactersIncludingRLE(String dataRow) {

		// "-" and "_" are treated as board data because they are sometimes used as character for a floor.
		// A board row: at least one level element including "-" and "_", but not only "-", "_" or " " or 0 - 9 or ".".
		// The last letter can't be a number.
		return dataRow.matches("^[@$#.+* \\-_()0-9|]+$") && !dataRow.matches("^[\\-_ ()0-9|.]*") && !Character.isDigit(dataRow.charAt(dataRow.length()-1));
	}

	/**
	 * Returns whether the passed data row contains only valid board characters.
	 *
	 * @param dataRow
	 *            one data row to check for being a board row
	 * @return <code>true</code> the passed row is a part of a level board, <code>false</code> otherwise
	 */
	private boolean containsOnlyBoardCharacters(String dataRow) {
		// "-" and "_" are treated as board data because they are sometimes used as character for a floor.
		// A board row: at least one level element including "-" and "_", but not only "-", "_" or " " or ".".
		return dataRow.matches("^[@$#.+* \\-_]+$") && !dataRow.matches("^[\\-_ .]*");
	}

	/**
	 * Returns whether the passed data row contains only valid "empty board row" characters - including run length encoded ones.
	 *
	 * @param dataRow
	 *            one data row to check for containing "empty board row" characters
	 * @return <code>true</code> the passed row is a part of a level board, <code>false</code> otherwise
	 */
	private boolean containsOnlyEmptyBoardRowCharacters(String dataRow) {

		// An empty board row contains only "-", "_" or space characters and at least one not space character.
		// "-" and "_" are treated as board data because they are sometimes used as character for a floor.
		// The data may be run length encoded and therefore contain digits.
		return dataRow.matches("^[\\-_ ()0-9|]+$");
	}

	 /**
	  * Returns whether the data row corresponding to the passed index is an interior empty row.
	  * <br>An interior empty board row is a row that:
	  * <ul>
	  *   <li>contains only "-", "_" or white space characters and at least one not white space character</li>
	  *   <li>must be surrounded by normal board rows (however there may be several successive interior empty rows)</li>
	  * </ul>
	  * This method assumes the input data not to contain any run length encoded data.
	  *
	 * @param inputData  input data containing all board rows
	 * @param indexInteriorRow  index of the row to check for being an interior row
	 * @return true when it's an interior row, false otherwise
	 */
	private boolean isInteriorBoardRow(List<String> inputData, int indexInteriorRow) {

		 if(indexInteriorRow < 1 || indexInteriorRow >= inputData.size()) {
			 return false;
		 }

		 // It's an interior empty board row, when:
		 // It contains only "-", "_" or space characters and at least one not space character.
		 // It must be surrounded by normal board rows. However, there may be several successive interior empty rows.
		 String dataRow = inputData.get(indexInteriorRow);

		 // Check if the current row only contains "interior row" characters but not only spaces.
		 if(!dataRow.matches("^[\\-_ ]+$") || dataRow.trim().isEmpty()) {
			 return false;
		 }

		 // There must be a valid board row above the interior rows.
		 for(int index = indexInteriorRow-1; index >= 0; index--) {

			 dataRow = inputData.get(index);

			 // Jump over interior rows.
			 if(dataRow.matches("^[\\-_ ]+$") && !dataRow.trim().isEmpty()) {
				 continue;
			 }

			 if(containsOnlyBoardCharactersIncludingRLE(dataRow)) {
				 break;
			 }

			 return false;
		 }

		 // There must be a valid board row below the interior rows.
		 for(int index = indexInteriorRow+1; index <= inputData.size(); index++) {

			 dataRow = inputData.get(index);

			 // Jump over interior rows.
			 if(dataRow.matches("^[\\-_ ]+$") && !dataRow.trim().isEmpty()) {
				 continue;
			 }

			 return containsOnlyBoardCharactersIncludingRLE(dataRow);
		 }

		 return false;
	 }

	/**
	 * Removes leading ">" characters.
	 * <p>
	 * The email convention to use leading '>' characters for quotations is taken account by removing leading '>' characters.
	 *
	 * @param inputData  {@code String}s that contain the data
	 * @return the data from which all mail quotes have been deleted
	 */
    private static ArrayList<String> deleteMailQuotes(List<String> inputData) {

		int leadingGreatersCount = Integer.MAX_VALUE;

		ArrayList<String> processedData = new ArrayList<>(inputData);

		// Count the number of ">" (mail quotes).
		// The ">" are to be deleted from the start of all data rows.
		for (String dataRow : inputData) {
			int currentNumberOfGreaters = 0;
			for (int index = 0; index < dataRow.length() && dataRow.charAt(index) == '>'; index++) {
				currentNumberOfGreaters++;
			}
			leadingGreatersCount = Math.min(currentNumberOfGreaters, leadingGreatersCount);
		}

		// Delete the mail quotes from all lines.
		if (leadingGreatersCount > 0) {
			for (String row : inputData) {
				processedData.add(row.substring(leadingGreatersCount));
			}
		}

		return processedData;
	}

	/**
     * Splits all strings that contain the character sequence \n. This sequence is
     * used in some collection files as "new line" indicator.<br>
     *
     * <p>Example:<br>
     * line0<br>
     * line1\nline2<br>
     * line3<br>
     * becomes:<br>
     * line0 <br>
     * line1 <br>
     * line2 <br>
     * line3
     *
     * <p>Note: the character sequence \n means: really those two characters '\' and 'n'
	 * NOT the new line character!
     *
     * @param inputData  {@code String}s that contain the data
     * @return the data all new lines characters have been converted to real new lines
     */
    private static ArrayList<String> splitNewLines(List<String> inputData) {

		ArrayList<String> processedData = new ArrayList<>();

		for (String dataRow : inputData) {
			processedData.addAll(Arrays.asList(dataRow.split("\\\\n")));
		}

		return processedData;
	}

	/**
	 * Decodes all board data that are run length encoded.
	 *
	 * @see RunLengthFormat
	 *
	 * @param inputData
	 *            {@code String}s that contain the data
	 * @return the run length decoded board data
	 */
	private ArrayList<String> decodeRunLengthEncodedBoardData(List<String> inputData) {

		ArrayList<String> decodedData = new ArrayList<>();

		for (int i = 0; i < inputData.size(); i++) {

			String dataRow = inputData.get(i);
			String previousRow = i == 0 ? "" : inputData.get(i-1);
			String nextRow 	   = i == inputData.size()-1 ? "" : inputData.get(i+1);

			// Check whether it is a board row or it contains characters of an empty board row. An empty
			// row is a special row only used in a few levels.
			// Run length decode if necessary and add all rows of the board to the inputData.
			if ( (containsOnlyBoardCharactersIncludingRLE(dataRow) || containsOnlyEmptyBoardRowCharacters(dataRow))  && dataRow.matches(".*[()0-9|]+.*")) {
				List<String> decoded = Arrays.asList(RunLengthFormat.runLengthDecode(dataRow).split("\n"));

				// Some levels have titles like "30-8-55". Ensure this data row belongs to a level
				// by checking the previous and next line.
				if(decoded.size() >= 3   // level on its own
				|| containsOnlyBoardCharactersIncludingRLE(previousRow) || containsOnlyBoardCharactersIncludingRLE(previousRow)
				|| containsOnlyBoardCharactersIncludingRLE(nextRow)     || containsOnlyBoardCharactersIncludingRLE(nextRow)) {
					decodedData.addAll(decoded);
					continue;
				}
			}

			decodedData.add(dataRow);
		}

		return decodedData;
	}

    /**
     * Extracts all {@code Solution}s stored in the passed input data.
     *
     * @param inputDataString the input data to parse for solutions
     * @return all found {@code Solution}s
     */
    public static ArrayList<Solution> extractSolutions(String inputDataString) {

        ArrayList<Solution> solutions = new ArrayList<>();

        if (inputDataString == null || inputDataString.isEmpty()) {
            return solutions;
        }

        // Split the string at each new line character and the character sequence \n,
        // and remove any leading mail quote characters.
        List<String> inputData = deleteMailQuotes(splitNewLines(Arrays.asList(inputDataString.split("\n"))));

        int rowIndex = 0;

        // Jump to the first row that contains lurd characters.
        for (; rowIndex < inputData.size(); rowIndex++) {

            String dataRow = inputData.get(rowIndex).trim();

            if (SOLUTION_CHARACTERS_PATTERN.matcher(dataRow).matches()) {
                break;
            }

        }

        StringBuilder solutionLURD = new StringBuilder();

        // As long as there are data, the might be solutions to be extracted.
        while (rowIndex < inputData.size()) {

            for (; rowIndex < inputData.size(); rowIndex++) {

                String dataRow = inputData.get(rowIndex).trim();

                if (!SOLUTION_CHARACTERS_PATTERN.matcher(dataRow).matches()) {
                    break;
                }

                // Collect all rows that contain the lurd characters.
                // The solution ends when there is a none LURD-character in it.
                solutionLURD.append(dataRow);

            }

            // Set the solution of the level if a solution has been found.
            if (solutionLURD.length() > 0) {

                String solutionMoves = solutionLURD.toString();

                // Run length decode the solution if it is run length encoded.
                if (solutionMoves.matches(".*[()0-9]+.*")) {
                    solutionMoves = RunLengthFormat.runLengthDecode(solutionMoves);
                }

                Solution newSolution = new Solution(solutionMoves);
                solutions.add(newSolution);

                // Search for additional solution data.
                for (; rowIndex < inputData.size(); rowIndex++) {

                    String dataRow = inputData.get(rowIndex).trim();

                    if (SOLUTION_CHARACTERS_PATTERN.matcher(dataRow).matches()) {
                        break; // A new solution has been found => continue with this new solution
                    }

                    String dataRowLowerCase = dataRow.toLowerCase();

                    // Set the name of the solution if stated in the data.
                    int index = dataRowLowerCase.lastIndexOf("solution name:");
                    if (index != -1) {
                        newSolution.name = dataRow.substring(index + 14).trim();
                        continue; // This line doesn't contain any other useful information.
                    }

                    // Set the "own solution" flag if stated in the data.
                    if (dataRowLowerCase.contains("own solution:") && dataRowLowerCase.contains("yes")) {
                        newSolution.isOwnSolution = true;
                        continue; // This line doesn't contain any other useful information.
                    }

                    // All other data are added as comment.
                    newSolution.comment += "\n" + dataRow;
                }
            }

            solutionLURD.setLength(0); // Erase for the moves of the next solution
        }

        return solutions;
    }
}
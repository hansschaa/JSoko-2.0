/**
 *  JSoko - A Java implementation of the game of Sokoban
 *  Copyright (c) 2012 by Matthias Meger, Germany
 *
 *  This file is part of JSoko.
 *
 *	JSoko is free software; you can redistribute it and/or modify
 *	it under the terms of the GNU General Public License as published by
 *	the Free Software Foundation; either version 2 of the License, or
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
package de.sokoban_online.jsoko.sound;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import de.sokoban_online.jsoko.resourceHandling.Settings;
import de.sokoban_online.jsoko.utilities.Debug;
import de.sokoban_online.jsoko.utilities.Utilities;


/**
 * Class for playing sounds.
 * This class provides static methods to play 3 build in sounds,
 * implemented by 3 sound files, which are part of the distribution.
 * The names of these files are configured in the {@link Settings} class,
 * with the configuration names
 * <ol>
 *  <li> "MoveSoundFile"
 *  <li> "PushSoundFile"
 *  <li> "PushToGoalSoundFile"
 * </ol>
 */
public class Sound {

	/** Executor for playing the sounds in an extra thread. */
	private static final ExecutorService executor = Executors.newFixedThreadPool(5);

	/**
	 * Plays the sound specified by its file name.
	 *
	 * @param filename file name of the sound to be played
	 * @throws FileNotFoundException thrown if the file name isn't valid
	 */
	public static void playSoundFromFile(String filename) throws FileNotFoundException {

		File file = Utilities.getFileFromClassPath(filename);
		if(file == null || !file.exists()) {
			throw new FileNotFoundException("file: "+filename+" not found.");
		}

		// Enqueue the sound to be played.
		executor.execute(getPlaySoundRunnable(filename));
	}

	/**
	 * Returns a <code>Runnable</code> which plays the sound corresponding to the passed file name.
	 *
	 * @param filename file name of the sound to be played by this <code>Runnable</code>
	 * @return the <code>Runnable</code> to play the sound
	 */
	private static Runnable getPlaySoundRunnable(final String filename) {

		return new Runnable() {

			AudioFormat audioFormat = null;
			final ArrayList<byte[]> audioData  = new ArrayList<>();

			@Override
            public void run() {

				SourceDataLine sourceDataLine = null;

				try {
					if(audioFormat == null) {
						// Read in the sound file.
						AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(Utilities.getInputStream("/" + filename));

						// Fetch information about the format of the audio data.
						audioFormat = audioInputStream.getFormat();

						// Read in all data and write it to the Line.
						int bytesReadCount = 0;
						byte[] tempBuffer = new byte[15000];
						while (bytesReadCount != -1) {
							bytesReadCount = audioInputStream.read(tempBuffer, 0, tempBuffer.length);
							if (bytesReadCount > 0) {
								byte[] tmp = new byte[bytesReadCount];
								System.arraycopy(tempBuffer, 0, tmp, 0, bytesReadCount);
								audioData.add(tmp);
							}
						}
					}

					// Get the data line and check whether there is a line which can be used for playing the sound.
					DataLine.Info dataLine = new DataLine.Info(SourceDataLine.class, audioFormat);
					if (!AudioSystem.isLineSupported(dataLine)) {
						if (Debug.isDebugModeActivated) {
							System.out.println("No line can play the sound!");
							return;
						}
					}

					// Get a line, open it and start it.
					sourceDataLine = (SourceDataLine) AudioSystem.getLine(dataLine);
					sourceDataLine.open(audioFormat);

					sourceDataLine.start();
					for(byte[] data : audioData) {
						sourceDataLine.write(data, 0, data.length);
					}

					// Wait for the line - this avoids endless loops waiting for the line when draining (for instance the "push to goal" sound).
                    Thread.sleep(10);

				}catch (LineUnavailableException e) {
					// Another program is just playing sounds. Hence, JSoko can't play any sound.
				}
				catch (Exception e) {
					if (Debug.isDebugModeActivated) {
						System.out.println("Soundfile not played");
						e.printStackTrace();
					}
				}
				finally {
					// Block and wait for internal buffer of the data line to empty.
					if(sourceDataLine != null) {
						sourceDataLine.drain();
						sourceDataLine.close();
					}
				}

			}
		};
	}



	/**
	 * Constants for the built-in sounds of JSoko.
	 * <p>
	 * These sounds can be played using the {@link Sound} class.
	 *
	 */
	public enum Effects {

		/** The settings keys to load the file name for the sounds. */
		MOVE("MoveSoundFile"),				  // Sound when the player is moved without pushing a box
		PUSH("PushSoundFile"),   			  // Sound when pushing a box
		PUSH_TO_GOAL("PushToGoalSoundFile"),  // Sound when pushing a box onto a goal
	    SOLUTION("SolutionSoundFile"),        // Sound when a solution has been found
	    DEADLOCK("DeadlockSoundFile"),        // Sound when a deadlock occurred
        RESTART("RestartSoundFile");          // Sound when all movements are undone


		/** Key to read the file name from the settings. */
		private final String soundSettingsKey;

		/** Name of the file this sound has been read from. */
		private String currentSoundFilename = "";

		/** One Runnable for every sound. */
		private Runnable clip;

		/**
		 * Constructor to construct a sound clip for every enum element.
		 *
		 * @param settingsKey the key to use for reading the file of the sound from the settings
		 */
		Effects(String settingsKey) {
			this.soundSettingsKey = settingsKey;
			loadClip(Settings.get(settingsKey));
		}


		/**
		 * Load the sound represented by the passed file name so it can be played.
		 *
		 * @param soundFileName the file name of the sound to be loaded
		 */
		private void loadClip(String soundFileName) {
		    clip = getPlaySoundRunnable(soundFileName);
		    currentSoundFilename = soundFileName;
		}

		/**
		 * Play a sound.
		 */
		public void play() {

			// Immediately return if sound effects aren't enabled.
			if (!Settings.soundEffectsEnabled) {
                return;
            }

			// If no sound file is there nothing can be played.
			if(currentSoundFilename.equals("")) {
                return;
            }

			// Check if the this sound must be read from another file (the user has
			// chosen another sound to be played).
			String settingsFileName = Settings.get(soundSettingsKey);
			if(!currentSoundFilename.equals(settingsFileName)) {
				loadClip(settingsFileName);
			}

			executor.execute(clip);
		}

		/**
		 * This method pre-loads all sounds for being able of playing them immediately.
		 * <p>
		 * This methods needn't to be called. It can be used to pre-load the sounds
		 * so the sounds are available for playing immediately.
		 */
		public static void loadSounds() {
			try {
				// Call the constructor for all sounds.
				values();
			}catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
}
/**
 *  JSoko - A Java implementation of the game of Sokoban
 *  Copyright (c) 2013 by Matthias Meger, Germany
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
package de.sokoban_online.jsoko.utilities.OSSpecific;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Properties;

import de.sokoban_online.jsoko.JSoko;
import de.sokoban_online.jsoko.gui.MessageDialogs;
import de.sokoban_online.jsoko.resourceHandling.Settings;
import de.sokoban_online.jsoko.utilities.Debug;
import de.sokoban_online.jsoko.utilities.FileNameSanitizerLinux;
import de.sokoban_online.jsoko.utilities.FileNameSanitizerWindows;
import de.sokoban_online.jsoko.utilities.Utilities;


/**
 * This class is used for all settings that are specific to a operation system.
 */
public class OSSpecific {

	private static String userHomeDirectory;
	private static String preferencesDirectory;
	private static String appDataDirectory;
	private static String cacheDirectory;


	/** Enum which returns the operating system JSoko is currently running on. */
	public enum OSType {
	    MacOS,
	    Linux,
	    Windows,
	    Other;

	    private static OSType osType;

	    public static OSType getOSType() {
	        if (osType == null) {
	        	String osName = System.getProperty("os.name").toLowerCase();
	        	osType = osName.indexOf("win") != -1 ? Windows :
	        			 osName.indexOf("mac") != -1 || osName.indexOf("os x") != -1 ? MacOS :
	        			 osName.indexOf("nix") != -1 || osName.indexOf("nux") != -1 || osName.indexOf("aix") != -1 ? Linux : Other;
			}
	        return osType;
	    }

	    public static final boolean isMac 	= getOSType() == MacOS;
	    public static final boolean isWindows = getOSType() == Windows;
	    public static final boolean isLinux 	= getOSType() == Linux;
	    public static final boolean isOther 	= getOSType() == Other;
	}


	/**
	 * Sets OS specific settings.
	 *
	 * @param application the reference to the main object holding all references
	 */
	public static void setOSSpecificSettings(final JSoko application) {

		// Set the directories for the JSoko data.
		setDirectories();

		// Set Mac specific settings.
		if (OSType.isMac) {
			setMacSettings(application);
		}
	}

	/**
	 * Returns the user home directory.
	 *
	 * @return the userHomeDirectory
	 */
	public static String getUserHomeDirectory() {
		return userHomeDirectory;
	}

	/**
	 * Returns the directory to store JSoko user preferences in.
	 *
	 * @return the preferencesDirectory
	 */
	public static String getPreferencesDirectory() {
		return preferencesDirectory;
	}

	/**
	 * Returns the directory to store JSoko user data in.
	 *
	 * @return the appDataDirectory
	 */
	public static String getAppDataDirectory() {
		return appDataDirectory;
	}

	/**
	 * Returns the directory to store cache data in.
	 *
	 * @return the cacheDirectory
	 */
	public static String getCacheDirectory() {
		return cacheDirectory;
	}

	private static void setMacSettings(final JSoko application) {

		// When using the Aqua look and feel, this property puts Swing menus in the OS X menu bar.
		// Note that JMenuBars in JDialogs are not moved to the OS X menu bar.
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		// Set application name.
		System.setProperty("com.apple.mrj.application.apple.menu.about.name", "JSoko");

		if (Desktop.isDesktopSupported()) {
			Desktop desktop = Desktop.getDesktop();

			// Handle the "About" menu item
			desktop.setAboutHandler(event -> application.actionPerformed(new ActionEvent(OSSpecific.class, 0, "aboutJSoko")));

			// Handle the "Preferences" menu item
			desktop.setPreferencesHandler(event -> { /* No action for preferences */ });

			// Handle the "Quit" menu item
			desktop.setQuitHandler((quitEvent, response) -> {
				application.actionPerformed(new ActionEvent(OSSpecific.class, 0, "programClosing"));

				// Respond with the decision to quit or cancel
				response.performQuit();
			});
		}
	}

	/**
	 * Sanitizes a file name based on the operating system being used.
	 * If the operating system is Windows, it uses the Windows-specific sanitizer,
	 * otherwise, it uses the Linux-specific sanitizer.
	 *
	 * @param fileName the name of the file to be sanitized
	 * @return the sanitized file name
	 */
	public static String sanitizeFileName(String fileName) {
		if(OSType.isWindows) {
			return FileNameSanitizerWindows.sanitizeFilename(fileName);
		} else {
			return FileNameSanitizerLinux.sanitizeFilename(fileName);
		}
	}

    /**
     * Sets the directories that depend on the operating system.
     */
    private static void setDirectories() {

    	userHomeDirectory = System.getProperty("user.home");

    	/**
    	 * Mac OS
    	 * Directories according to Mac developer library: "Important Java Directories on Mac OS X".
    	 */
    	if(OSType.isMac) {
    		 preferencesDirectory = userHomeDirectory + "/Library/Preferences/de.sokoban_online.jsoko/";
    		 appDataDirectory 	  = userHomeDirectory + "/Library/jsoko/";
    		 cacheDirectory 	  = System.getProperty("java.io.tmpdir")+"/jsoko/";
    	}

    	/**
    	 * Unix/Linux
    	 * Directories according to "XDG Base Directory Specification".
    	 */
    	if(OSType.isLinux) {
       		preferencesDirectory = System.getenv("XDG_CONFIG_HOME");
    		if (preferencesDirectory != null && !preferencesDirectory.trim().isEmpty()) {
    			preferencesDirectory = preferencesDirectory + "/jsoko/";
    		} else {
    			preferencesDirectory = userHomeDirectory +"/.config/jsoko/";
			}

    		appDataDirectory = System.getenv("XDG_DATA_HOME");
    		if (appDataDirectory != null && !appDataDirectory.trim().isEmpty()) {
    			appDataDirectory = appDataDirectory + "/jsoko/";
    		} else {
    			appDataDirectory = userHomeDirectory +"/.local/share/jsoko/";
			}

    		cacheDirectory = System.getenv("XDG_CACHE_HOME");
    	    if (cacheDirectory != null && !cacheDirectory.trim().isEmpty()) {
    	    	cacheDirectory = cacheDirectory + "/jsoko/";
    	    } else {
    	    	cacheDirectory = userHomeDirectory + "/.cache/jsoko/";
    	    }
    	}

    	/**
    	 * Windows
    	 */
    	if(OSType.isWindows) {
    		preferencesDirectory = System.getenv("APPDATA")+"/jsoko/";
    		appDataDirectory 	 = System.getenv("APPDATA")+"/jsoko/";
    		cacheDirectory 		 = System.getProperty("java.io.tmpdir")+"/jsoko/";
    	}

    	if(OSType.isOther) {
    		preferencesDirectory = appDataDirectory = cacheDirectory = userHomeDirectory + "/jsoko/";
    	}

    	if(Debug.isDebugModeActivated || Debug.debugUserDataFolder) {
    		preferencesDirectory = appDataDirectory = cacheDirectory = Utilities.getBaseFolder()+"/userData/"; // use bin folder for data
    	}

    	/**
    	 * Ensure all directories are accessible.
    	 */
    	if(Utilities.createDirectory(preferencesDirectory) == null) {
    		File file = Utilities.createDirectory(userHomeDirectory + "/jsoko/");
    		if(file != null) {
				preferencesDirectory = file.getPath();
			} else {
				file = new File(Utilities.getBaseFolder());
				if(file != null && file.canWrite()) {
					preferencesDirectory = file.getPath();
				} else {
					// Show message: since the texts haven't been loaded yet an English message is displayed.
					MessageDialogs.showErrorString(null, "Can't access directory: "+userHomeDirectory + "/jsoko/");
					System.exit(-1);
				}
			}
    	}
    	if(Utilities.createDirectory(appDataDirectory) == null) {
    		appDataDirectory = preferencesDirectory;
    	}
    	if(Utilities.createDirectory(cacheDirectory) == null) {
    		cacheDirectory = preferencesDirectory;
    	}

    	// When the user edits texts they are saved in the folder "texts" which has to be created.
    	Utilities.createDirectory(preferencesDirectory+"texts/");

    }


    /**
     * DEBUG: prints the environment variables and system properties.
     */
    public static void debugPrintProperties() {

    	System.out.println("Environment variables:");
		Map<String, String> m = System.getenv();
		for (String key : m.keySet()) {
			System.out.println("key: "+key+ " value: "+m.get(key));
		}

		System.out.println("\nSystem properties:");
		Properties p = System.getProperties();
		for (Object key : p.keySet()) {
			System.out.println("key: "+key+ " value: "+p.get(key));
		}
    }
}

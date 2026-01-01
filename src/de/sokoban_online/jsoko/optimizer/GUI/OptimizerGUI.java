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
package de.sokoban_online.jsoko.optimizer.GUI;

import static de.sokoban_online.jsoko.optimizer.Optimizer.NONE;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.*;
import java.io.IOException;
import java.text.ParseException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;

import com.jidesoft.swing.ComboBoxSearchable;

import de.sokoban_online.jsoko.ExceptionHandler;
import de.sokoban_online.jsoko.JSoko;
import de.sokoban_online.jsoko.OptimizerAsPlugin;
import de.sokoban_online.jsoko.OptimizerAsPlugin.OptimizerSettings;
import de.sokoban_online.jsoko.board.Board;
import de.sokoban_online.jsoko.board.Directions;
import de.sokoban_online.jsoko.gui.NumberInputTF;
import de.sokoban_online.jsoko.gui.StartStopButton;
import de.sokoban_online.jsoko.leveldata.solutions.Solution;
import de.sokoban_online.jsoko.leveldata.solutions.SolutionsGUI;
import de.sokoban_online.jsoko.optimizer.Optimizer;
import de.sokoban_online.jsoko.optimizer.Optimizer.OptimizationMethod;
import de.sokoban_online.jsoko.optimizer.OptimizerSolution;
import de.sokoban_online.jsoko.resourceHandling.Settings;
import de.sokoban_online.jsoko.resourceHandling.Texts;
import de.sokoban_online.jsoko.utilities.Debug;
import de.sokoban_online.jsoko.utilities.Utilities;


/**
 * GUI for the optimizer.
 */
@SuppressWarnings("serial")
public final class OptimizerGUI extends OptimizerGUISuperClass {

    private JComboBox<OptimizationMethod> optimizationMethodCombobox;
    private JList<OptimizationMethod> optimizationMethodList;

    // CheckBox for turning iterating on/off.
    JCheckBox isIteratingEnabled;

    // CheckBox determining whether only the last solution is saved in iterating mode or all solutions.
    JCheckBox isOnlyLastSolutionToBeSaved;

    // CheckBox determining whether the iteration is to be stopped when no moves and no pushes improvement has been found in the last iteration run.
    JCheckBox stopIterationWhenNoImprovement;

    /**
     * Immediately stop the iteration and start a new one when a solution has been found that is better than the current best solution.
     */
    JCheckBox newIterationWhenImprovementFound;

    // The displayed board in the optimizer.
    OptimizerBoardDisplay boardDisplay = null;

    // The solution used to create the current board display (when a pushes range is optimized).
    // This is used when the user wants to copy the current board and the solution to the clipboard.
    Solution solutionForCurrentBoardDisplay = null;

    // GUI elements for setting the maximum number of box configurations to be generated
    // by the optimizer. This number directly influences the RAM usage of the program
    // and is therefore important to avoid out-of-memory errors.
    private JRadioButton isMaxBoxConfigurationManuallySet;
    private JSpinner maxBoxConfigurationsToBeGenerated;

    // Background color for solutions that have been selecting when the optimizer has been started.
    private final Color selectedSolutionsColor = new Color(0x87, 0xCE, 0xFF);


    /**
     * Variables for the settings the optimization range.
     */
    JRadioButton isOptimizeCompleteSolutionActivated;


    // GUI elements for setting a range of pushes to be considered for optimizing a solution.
    JRadioButton isRangeOfPushesActivated;
    JSpinner rangeOfPushesFromValue;
    JSpinner rangeOfPushesToValue;

    // Variables for pushes range optimization with intervals
    private JCheckBox isPushRangeIntervalsOptimizationActivated;
    private JSpinner intervalSizeInPushes;
    private int currentIntervalBeginPushesValue = 0;
    private int intervalMaxPushesValues = Integer.MAX_VALUE;
    private int pushesLastOptimizedSolution = NONE;

    private JCheckBox replaceUnmovedBoxesWithAWall;

    // Checkbox for setting the flag indicating whether the player position is fixed
    // for all solutions. That means: the optimizer may only search
    // for solutions where the player ends at the same location as in the basis solution.
    private JCheckBox isPlayerPositionToBePreserved;

    // If the optimizer only optimizes a part of a solution (the user has selected a
    // specific pushes range to be optimized), then these variables hold the prefix and
    // suffix moves to be added to the solution and the number of prefix moves and pushes.
    private String prefixSolutionMoves = "";
    private String suffixSolutionMoves = "";

    /**
     * The range of pushes to be optimized.
     */
    int optimizeFromPush, optimizeToPush;

    /**
     * Number of threads to be used by the optimizer.
     */
    private JSpinner threadsCount;

    /**
     * The user may select more than one solution for optimizing.
     * This list contains all selected solutions.
     */
    List<Solution> selectedSolutionsToBeOptimized = new ArrayList<>();

    /**
     * If only a pushes range of a solution is to be optimized, a new optimizer is
     * created for optimizing a new board created from the relevant part of the solution.
     * The old optimizer which is used for optimizing the whole level is then saved
     * in this variable.
     */
    Optimizer optimizerOriginalLevel = null;

    /**
     * Creates an object for optimizing a solution.
     *
     * @param application reference to the main object that holds references
     *                    to all other objects
     */
    public OptimizerGUI(final JSoko application) {

        // Reference to the main object of this program holding all references.
        this.application = application;

        // Save the reference to the currently loaded level the optimizer has been opened for.
        currentLevel = application.currentLevel;

        // Register the optimizer due to stopping the optimizer when an out-of-memory error occurs.
        ExceptionHandler.INSTANCE.addHandler(this);

        // Create a new board with the initial level board position. This is done in order
        // not to change the original board and to ensure the board is in the initial state.
        try {
            board = new Board();
            board.setBoardFromString(currentLevel.getBoardDataAsString());
            if (!board.isValid(new StringBuilder())) {
                return;
            }
            board.prepareBoard();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // The vicinity settings fields.
        vicinitySettings = new ArrayList<>(4);

        // Display the GUI of the optimizer.
        createGUI();

        // Create a new optimizer object.
        optimizer = new Optimizer(board, this, null, (Integer) threadsCount.getValue());

        // Backup the optimizer for the board. If the user wants to optimize a pushes range
        // of the solution, a new board is created, and the original optimizer can later
        // be set back quickly without creating a new one.
        optimizerOriginalLevel = optimizer;

        // Adjust settings according to the saved ones.
        setSettings();

        // Show the optimizer GUI.
        if (!OptimizerAsPlugin.isOptimizerPluginModus || OptimizerAsPlugin.settings.showOptimizerWindow == true) {
            setVisible(true);
        }

        if (OptimizerAsPlugin.isOptimizerPluginModus && OptimizerAsPlugin.settings.minimizeOptimizerWindow) {
            setState(Frame.ICONIFIED);
        }

        // The help is registered on the root pane. It requests the focus so pressing F1 opens the help for the optimizer
        getRootPane().requestFocus();

        setEscapeAsCloseKey();
    }

    private void setEscapeAsCloseKey() {

        InputMap inputMap = getRootPane().getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actionmap = getRootPane().getActionMap();

        /*
         * Cursor key "Escape"
         */
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        AbstractAction closeAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!boardDisplay.isRectangleMarkingModeActivated) {     // otherwise the boardDisplay class handles the escape key event
                    OptimizerGUI.this.closeOptimizerGUI();
                }
            }
        };
        actionmap.put("close", closeAction);
    }

    /**
     * Sets all settings in this GUI that haven't been set, yet.
     * <p>
     * The settings to be set have been read from the hard disk, see {@link Settings}.
     */
    private void setSettings() {
        // If no bounds settings have been saved, yet, set the bounds of the main GUI.
        if (Settings.optimizerXCoordinate == -1) {
            setBounds(application.getBounds());
        } else {
            setBounds(Settings.optimizerXCoordinate, Settings.optimizerYCoordinate, Settings.optimizerWidth, Settings.optimizerHeight);
        }

        if (OptimizerAsPlugin.isOptimizerPluginModus) {
            // Ensure that no parts of the program will be off-screen: the user might have set a new monitor resolution.
            Rectangle maximumWindowBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            if (Settings.pluginOptimizerWidth > maximumWindowBounds.width) {
                Settings.pluginOptimizerWidth = maximumWindowBounds.width;
            }
            if (Settings.pluginOptimizerHeight > maximumWindowBounds.height) {
                Settings.pluginOptimizerHeight = maximumWindowBounds.height;
            }
            if (Settings.pluginOptimizerXCoordinate < 0 || Settings.pluginOptimizerXCoordinate > maximumWindowBounds.x + maximumWindowBounds.width || Settings.pluginOptimizerYCoordinate < 0 || Settings.pluginOptimizerYCoordinate > maximumWindowBounds.y + maximumWindowBounds.height) {
                Settings.pluginOptimizerXCoordinate = maximumWindowBounds.x + (maximumWindowBounds.width - Settings.pluginOptimizerWidth) / 2;
                Settings.pluginOptimizerYCoordinate = maximumWindowBounds.y + (maximumWindowBounds.height / 4 - Settings.pluginOptimizerHeight / 4);
                Settings.pluginOptimizerWidth = maximumWindowBounds.width / 2;
                Settings.pluginOptimizerHeight = maximumWindowBounds.height / 2;
            }

            setBounds(Settings.pluginOptimizerXCoordinate, Settings.pluginOptimizerYCoordinate, Settings.pluginOptimizerWidth, Settings.pluginOptimizerHeight);
        }
    }

    /**
     * Displays the GUI of the optimizer.
     */
    private void createGUI() {

        // Set the title.
        setTitle(Texts.getText("optimizer.JSokoOptimizer") + " - " + currentLevel.getNumber() + " - " + currentLevel.getTitle());

        JPanel mainPanel = new JPanel(new BorderLayout());
        add(new JScrollPane(mainPanel), BorderLayout.CENTER);

        //
        // D I S P L A Y O F L E V E L
        //

        // Add the GUI of the level at the center.
        boardDisplay = new OptimizerBoardDisplay(currentLevel);
        boardDisplay.setBorder(BorderFactory.createBevelBorder(BevelBorder.RAISED, Color.LIGHT_GRAY, Color.gray));
        Texts.helpBroker.enableHelpKey(boardDisplay, "optimizer.RestrictingTheAreaToBeOptimized", null); // Enable help
        mainPanel.add(boardDisplay, BorderLayout.CENTER);

        boardDisplay.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_C) {
                    if (isOptimizeCompleteSolutionActivated.isSelected()) {
                        Solution solution = solutionsGUI.getSelectedValue();
                        String boardWithSolution = boardDisplay.getBoard().getBoardDataAsString() + "\nSolution\n" + (solution == null ? "" : solution.lurd);
                        Utilities.putStringToClipboard(boardWithSolution);
                    } else {
                        String boardWithSolution = boardDisplay.getBoard().getBoardDataAsString() + "\nSolution\n" + solutionForCurrentBoardDisplay.lurd;
                        Utilities.putStringToClipboard(boardWithSolution);
                    }

                    addLogText(Texts.getText("optimizer.copiedBoardAndSolutionToClipboard"));
                }
            }
        });

        // In the south, a new JPanel is added containing all optimizer specific elements.
        JPanel southPanel = new JPanel(new GridBagLayout());
        mainPanel.add(southPanel, BorderLayout.SOUTH);

        // Set constraints.
        GridBagConstraints constraints = new GridBagConstraints();

        //
        // V I C I N I T Y S Q U A R E S
        //
        /* Create a panel for the vicinity settings and add 3 number fields for letting
         * the user set the distance how far the boxes may be repositioned. */
        JPanel vicinitySettingsPanel = new JPanel(new GridLayout(Settings.useEightVicinityBoxes ? 8 : 4, 1));
        vicinitySettingsPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(Texts.getText("vicinitySquares")), BorderFactory.createEmptyBorder(5, 5, 5, 5)), vicinitySettingsPanel.getBorder()));
        Texts.helpBroker.enableHelpKey(vicinitySettingsPanel, "optimizer.RestrictingTheSearch", null); // Enable help

        int maxVicinitySquares = Settings.largeValues ? 9999 : 999;

        // Add the input fields for the vicinity settings.
        vicinitySettings.add(new NumberInputTF(Settings.vicinitySquaresBox1Enabled, Texts.getText("box") + " 1", 1, maxVicinitySquares, Settings.vicinitySquaresBox1, true));
        vicinitySettings.add(new NumberInputTF(Settings.vicinitySquaresBox2Enabled, Texts.getText("box") + " 2", 1, maxVicinitySquares, Settings.vicinitySquaresBox2, true));
        vicinitySettings.add(new NumberInputTF(Settings.vicinitySquaresBox3Enabled, Texts.getText("box") + " 3", 1, maxVicinitySquares, Settings.vicinitySquaresBox3, true));
        vicinitySettings.add(new NumberInputTF(Settings.vicinitySquaresBox4Enabled, Texts.getText("box") + " 4", 1, maxVicinitySquares, Settings.vicinitySquaresBox4, true));

        if (Settings.useEightVicinityBoxes) {
            vicinitySettings.add(new NumberInputTF(Settings.vicinitySquaresBox5Enabled, Texts.getText("box") + " 5", 1, maxVicinitySquares, Settings.vicinitySquaresBox5, true));
            vicinitySettings.add(new NumberInputTF(Settings.vicinitySquaresBox6Enabled, Texts.getText("box") + " 6", 1, maxVicinitySquares, Settings.vicinitySquaresBox6, true));
            vicinitySettings.add(new NumberInputTF(Settings.vicinitySquaresBox7Enabled, Texts.getText("box") + " 7", 1, maxVicinitySquares, Settings.vicinitySquaresBox7, true));
            vicinitySettings.add(new NumberInputTF(Settings.vicinitySquaresBox8Enabled, Texts.getText("box") + " 8", 1, maxVicinitySquares, Settings.vicinitySquaresBox8, true));
        }

        // Add the input fields to the panel.
        for (NumberInputTF numberField : vicinitySettings) {
            vicinitySettingsPanel.add(numberField);
        }

        // Add the vicinity settings panel to the main panel.
        constraints.gridx = 0;
        constraints.gridy++;
        constraints.gridheight = 2;
        constraints.anchor = GridBagConstraints.NORTH;
        constraints.fill = GridBagConstraints.NONE;
        constraints.insets.set(0, 0, 0, 0);
        southPanel.add(vicinitySettingsPanel, constraints);

        //
        // O P T I M I Z A T I O N M E T H O D
        //
        JPanel mainOptimizerPanel = new JPanel(new BorderLayout());

        JPanel optimizationMethod = new JPanel(new BorderLayout());
        optimizationMethod.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(Texts.getText("optimizationMethod")), BorderFactory.createEmptyBorder(5, 5, 5, 5)), optimizationMethod.getBorder()));
        Texts.helpBroker.enableHelpKey(optimizationMethod, "optimizer.OptimizationMethod", null); // Enable help

        optimizationMethodCombobox = new JComboBox<>();
        for (OptimizationMethod m : OptimizationMethod.values()) {
            optimizationMethodCombobox.addItem(m);
        }

        if (Settings.optimizationMethod < 0 || Settings.optimizationMethod >= OptimizationMethod.values().length) {      // avoid invalid values
            Settings.optimizationMethod = 0;
        }
        optimizationMethodCombobox.setSelectedItem(OptimizationMethod.values()[Settings.optimizationMethod]);

        optimizationMethodCombobox.addActionListener(e -> {
            newSolutionSelected();  // Trigger a solution changed event to ensure the board is drawn correctly when selecting/unselecting AVOID_MARKED_POSITIONS optimization method
        });
        ComboBoxSearchable searchable = new ComboBoxSearchable(optimizationMethodCombobox) {
            @Override
            protected String convertElementToString(Object object) {
                OptimizationMethod method = (OptimizationMethod) object;
                return Integer.toHexString(method.ordinal() + 1) + " " + method;
            }
        };
        searchable.setSearchingDelay(10);
        optimizationMethodCombobox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                OptimizationMethod method = (OptimizationMethod) value;
                String item = Integer.toHexString(method.ordinal() + 1) + " " + method;
                return super.getListCellRendererComponent(list, item, index, isSelected, cellHasFocus);
            }
        });

        optimizationMethod.add(optimizationMethodCombobox, BorderLayout.CENTER);
        mainOptimizerPanel.add(optimizationMethod, BorderLayout.NORTH);

        //
        // P U S H E S R A N G E
        //
        JPanel pushesRangeMainPanel = new JPanel(new BorderLayout());
        pushesRangeMainPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(Texts.getText("optimizer.range")), BorderFactory.createEmptyBorder(5, 5, 5, 5)), pushesRangeMainPanel.getBorder()));
        Texts.helpBroker.enableHelpKey(pushesRangeMainPanel, "optimizer.RestrictingThePushesRange", null); // Enable help

        // Button group for "optimize whole collection" and "optimize range of solution" selection.
        ButtonGroup optimizingRangeButtonGroup = new ButtonGroup();

        /* "optimize the whole solution". */
        JPanel wholeSolutionPanel = new JPanel(new BorderLayout());
        isOptimizeCompleteSolutionActivated = new JRadioButton(Texts.getText("optimizer.completeSolution"), true);
        isOptimizeCompleteSolutionActivated.addChangeListener(e ->   // Walls can replace boxes if a pushes range optimization is activated.
                replaceUnmovedBoxesWithAWall.setEnabled(isPushRangeIntervalsOptimizationActivated.isSelected() || isRangeOfPushesActivated.isSelected())
        );
        optimizingRangeButtonGroup.add(isOptimizeCompleteSolutionActivated);
        wholeSolutionPanel.add(isOptimizeCompleteSolutionActivated, BorderLayout.CENTER);
        pushesRangeMainPanel.add(wholeSolutionPanel, BorderLayout.NORTH);

        /* Optimize "range of solution". */
        JPanel rangePanel = new JPanel(new BorderLayout());
        isRangeOfPushesActivated = new JRadioButton("");
        isRangeOfPushesActivated.addChangeListener(e ->   // Walls can replace boxes if a pushes range optimization is activated.
                replaceUnmovedBoxesWithAWall.setEnabled(isPushRangeIntervalsOptimizationActivated.isSelected() || isRangeOfPushesActivated.isSelected())
        );

        optimizingRangeButtonGroup.add(isRangeOfPushesActivated);
        rangePanel.add(isRangeOfPushesActivated, BorderLayout.WEST);

        int maxPushesRange = Settings.largeValues ? 99999999 : 999999;
        int columnsForRange = Settings.largeValues ? 7 : 5;

        // Panel containing the spinners for setting the range to optimize.
        JPanel rangeSelectionPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 3, 5));
        rangeSelectionPanel.add(new JLabel(Texts.getText("optimizer.rangeFrom")));
        final SpinnerNumberModel model1 = new SpinnerNumberModel(0, 0, maxPushesRange, 1);
        rangeOfPushesFromValue = new JSpinner(model1);
        ((JSpinner.DefaultEditor) rangeOfPushesFromValue.getEditor()).getTextField().setColumns(columnsForRange);
        rangeSelectionPanel.add(rangeOfPushesFromValue);

        rangeSelectionPanel.add(new JLabel(Texts.getText("optimizer.rangeTo")));
        final SpinnerNumberModel model2 = new SpinnerNumberModel(maxPushesRange, 1, maxPushesRange, 1);
        rangeOfPushesToValue = new JSpinner(model2);
        ((JSpinner.DefaultEditor) rangeOfPushesToValue.getEditor()).getTextField().setColumns(columnsForRange);
        rangeSelectionPanel.add(rangeOfPushesToValue);
        rangePanel.add(rangeSelectionPanel, BorderLayout.CENTER);

        pushesRangeMainPanel.add(rangePanel, BorderLayout.CENTER);

        // Change listener for the pushes range setting.
        ChangeListener changeListener = e -> {

            /*
             * This method is called when the "pushes range" radio button, the "complete solution radio button"
             * or the spinner have been pressed.
             */

            // Check whether one of the radio buttons has fired a change.
            if (e.getSource() instanceof AbstractButton) {
                AbstractButton aButton = (AbstractButton) e.getSource();
                ButtonModel aModel = aButton.getModel();

                // Ensure the button had been deselected and is to be selected now.
                if (!(aModel.isPressed() && !aModel.isSelected())) {
                    return;
                }

                // Check whether the button indicating that the whole solution is to be optimized has been pressed.
                if (aButton == isOptimizeCompleteSolutionActivated) {

                    // For optimizing the whole solution, the normal board is set. Even if the push range is set to 0 to "pushes of solution"
                    // then there may be boxes that are never pushed which are converted to walls.
                    boardDisplay.setBoardToDisplay(board);

                    if (solutionsGUI.getSelectedValue() != null) {
                        setUntouchedPositionsInBoardDisplay(board, solutionsGUI.getSelectedValue().lurd);
                    }

                    // If the user has entered a new number in the pushes range spinners and then pressed the "isCompleteSolutionActivated"
                    // button, then a spinner change event is fired, too (after this event) while the button is still "false". Hence,
                    // ensure the button is already set to "true", so the spinner event is rejected.
                    aButton.setSelected(true);
                    return;
                }
            }

            // Spinner value changes are only relevant if the pushes range optimization is activated.
            if (e.getSource() instanceof AbstractSpinnerModel) {
                if (!isRangeOfPushesActivated.isSelected()) {
                    return;
                }
            }

            // The pushes range button has been selected or the corresponding spinners have been changed.
            // => Set a new board from the selected pushes range of the solution.
            setBoardToDisplayFromPushesRange();
        };

        /* Optimize in pushes intervals. */
        JPanel pushesIntervalsPanel = new JPanel(new BorderLayout());
        isPushRangeIntervalsOptimizationActivated = new JCheckBox();
        isPushRangeIntervalsOptimizationActivated.addChangeListener(e ->   // Walls can replace boxes if a pushes range optimization is activated.
                replaceUnmovedBoxesWithAWall.setEnabled(isPushRangeIntervalsOptimizationActivated.isSelected() || isRangeOfPushesActivated.isSelected())
        );
        pushesIntervalsPanel.add(isPushRangeIntervalsOptimizationActivated, BorderLayout.WEST);

        JPanel intervalPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 3, 5));
        intervalPanel.add(new JLabel(Texts.getText("optimizer.optimizePushesRangesIntervals")));
        intervalSizeInPushes = new JSpinner(new SpinnerNumberModel(getDefaultIntervalSize(), 4, Integer.MAX_VALUE, 1));
        ((JSpinner.DefaultEditor) intervalSizeInPushes.getEditor()).getTextField().setColumns(5);
        intervalPanel.add(intervalSizeInPushes);
        intervalPanel.add(new JLabel(Texts.getText("pushes")));
        pushesIntervalsPanel.add(intervalPanel, BorderLayout.CENTER);


        replaceUnmovedBoxesWithAWall = new JCheckBox(Texts.getText("optimizer.replaceUnmovedBoxesWithAWall"), Settings.replaceUnmovedBoxesWithAWall);
        replaceUnmovedBoxesWithAWall.addChangeListener(e -> {       // when a pushes range is optimized, the unmoved boxes must be handled correctly
                    if (isRangeOfPushesActivated.isSelected() || isPushRangeIntervalsOptimizationActivated.isSelected()) {
                        setBoardToDisplayFromPushesRange();
                    }
                }
        );
        replaceUnmovedBoxesWithAWall.setEnabled(isRangeOfPushesActivated.isSelected() || isPushRangeIntervalsOptimizationActivated.isSelected());
        pushesIntervalsPanel.add(replaceUnmovedBoxesWithAWall, BorderLayout.SOUTH);

        pushesRangeMainPanel.add(pushesIntervalsPanel, BorderLayout.SOUTH);

        // Pressing the "enter" key should fire a change event.
        KeyAdapter keyListener = new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() == KeyEvent.VK_ENTER && e.getSource() instanceof JFormattedTextField) {
                    JFormattedTextField textfield = (JFormattedTextField) e.getSource();
                    try {
                        textfield.commitEdit();
                    } catch (ParseException e1) {
                    }
                }
            }
        };
        ((JSpinner.DefaultEditor) rangeOfPushesFromValue.getEditor()).getTextField().addKeyListener(keyListener);
        ((JSpinner.DefaultEditor) rangeOfPushesToValue.getEditor()).getTextField().addKeyListener(keyListener);

        // If the pushes range has been set to a new value the change is displayed by creating a new board.
        // Switching from "pushes range" to "complete solution" should also fire a change.
        model1.addChangeListener(changeListener);
        model2.addChangeListener(changeListener);
        isOptimizeCompleteSolutionActivated.addChangeListener(changeListener);
        isRangeOfPushesActivated.addChangeListener(changeListener);

        mainOptimizerPanel.add(pushesRangeMainPanel, BorderLayout.CENTER);


        JPanel buttonPanel = new JPanel(new BorderLayout());

        //
        // S T A R T B U T T O N
        //
        startButton = new StartStopButton("startOptimizer", "startOptimizer", "stopOptimizer", "stopOptimizer");

        startButton.setFocusPainted(false);
        startButton.addActionListener(this);
        buttonPanel.add(startButton, BorderLayout.NORTH);

        //
        // Add current best solution button
        //
        showBestSolutionButton = new JButton(Texts.getText("optimizer.showBestSolutionButton"));
        showBestSolutionButton.addActionListener(e -> displayCurrentBestFoundSolution());
        showBestSolutionButton.setEnabled(false);
        if (Settings.isShowBestSolutionButtonVisible) {
            buttonPanel.add(showBestSolutionButton, BorderLayout.SOUTH);
        }
        buttonPanel.add(new JLabel(" "), BorderLayout.CENTER); // Make some space between the buttons

        mainOptimizerPanel.add(buttonPanel, BorderLayout.SOUTH);


        constraints.gridwidth = 1;
        constraints.gridx++;
        constraints.gridy = 1;
        constraints.gridheight = 2;
        southPanel.add(mainOptimizerPanel, constraints);


        /* O P T I M I Z E R L O G
         *
         * Panel for showing a TextArea for displaying log info while the optimizer
         * is running. The log may contain a lot of information.
         * Hence, it is added as a panel at the east for the whole GUI. */
        JPanel logTextPanel = new JPanel(new BorderLayout());
        logTextPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(Texts.getText("optimizer.logText")), BorderFactory.createEmptyBorder(5, 5, 5, 5)), logTextPanel.getBorder()));
        optimizerLog = new JTextPane();
        optimizerLog.setEditable(false);
        Texts.helpBroker.enableHelpKey(optimizerLog, "optimizer.OptimizerLog", null); // Enable help for the optimizer log

        // Initialize some styles.
        // - Style "regular" from the default
        // - Style "italic" as "regular" with setItalics()
        // - Style "bold" as "regular" with setBold()
        Style def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        Style regular = optimizerLog.addStyle("regular", def);

        StyleConstants.setFontFamily(def, "SansSerif");

        Style s = optimizerLog.addStyle("italic", regular);
        StyleConstants.setItalic(s, true);

        s = optimizerLog.addStyle("bold", regular);
        StyleConstants.setBold(s, true);

        // - Style "bold" as "regular" but family "Monospaced" used for display for debug output
        s = optimizerLog.addStyle("debug", regular);
        StyleConstants.setFontFamily(s, "Monospaced");
        StyleConstants.setForeground(s, Color.blue);

        logTextPanel.add(new JScrollPane(optimizerLog));
        logTextPanel.setPreferredSize(new Dimension((int) getPreferredSize().getWidth(), (int) (getPreferredSize().getHeight())));


        constraints.gridheight = 3;
        constraints.gridwidth = 1;
        constraints.gridy = 1;
        constraints.gridx++;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.weightx = 1;
        southPanel.add(logTextPanel, constraints);


        // A D D I T I O N A L S E T T I N G S
        // Layout panel for iterating and maximum box configurations
        JPanel additionalSettingsPanel = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                Dimension preferredSize = super.getPreferredSize();

                Border border = getBorder();
                int borderWidth = 0;

                if (border instanceof TitledBorder) {
                    Insets insets = getInsets();
                    TitledBorder titledBorder = (TitledBorder) border;
                    borderWidth = titledBorder.getMinimumSize(this).width + insets.left + insets.right;
                }

                int preferredWidth = Math.max(preferredSize.width, borderWidth);

                return new Dimension(preferredWidth, preferredSize.height);
            }
        };
        additionalSettingsPanel.setBorder(BorderFactory.createTitledBorder(Texts.getText("optimizer.additional_settings")));

        JPanel settingsPanel = new JPanel(new BorderLayout());

        JPanel hideShowButtonPanel = new JPanel(new BorderLayout());
        JButton showAdditionalSettingsButton = new JButton(Utilities.getIcon("list-remove.png", "additional Settings"));
        showAdditionalSettingsButton.setContentAreaFilled(false);
        showAdditionalSettingsButton.setMinimumSize(new Dimension(showAdditionalSettingsButton.getIcon().getIconWidth() + 4, showAdditionalSettingsButton.getIcon().getIconHeight() + 4));
        showAdditionalSettingsButton.setPreferredSize(showAdditionalSettingsButton.getMinimumSize());
        showAdditionalSettingsButton.setBorderPainted(false);
        showAdditionalSettingsButton.addActionListener(e -> {
            if (settingsPanel.isVisible()) {
                showAdditionalSettingsButton.setIcon(Utilities.getIcon("list-add.png", "additional Settings"));
                settingsPanel.setVisible(false);
            } else {
                showAdditionalSettingsButton.setIcon(Utilities.getIcon("list-remove.png", "additional Settings"));
                settingsPanel.setVisible(true);
            }
        });
        hideShowButtonPanel.add(showAdditionalSettingsButton, BorderLayout.LINE_END);
        additionalSettingsPanel.add(hideShowButtonPanel, BorderLayout.NORTH);

        additionalSettingsPanel.add(settingsPanel, BorderLayout.CENTER);

        /* Panel for additional settings for iterative optimizing. */
        JPanel iterationPanel = new JPanel(new GridLayout(4, 1));
        iterationPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(Texts.getText("iteratingOptimizing")), BorderFactory.createEmptyBorder(5, 5, 5, 5)), iterationPanel.getBorder()));
        Texts.helpBroker.enableHelpKey(iterationPanel, "optimizer.IterativeOptimizing", null); // Enable help

        // A CheckBox for turning iteration on/off.
        isIteratingEnabled = new JCheckBox(Texts.getText("activateIterating"), Settings.isIteratingEnabled);
        isIteratingEnabled.setToolTipText(Texts.getText("activateIteratingTooltip"));
        iterationPanel.add(isIteratingEnabled);

        // A CheckBox for setting "stop iteration when no progress" on/off.
        stopIterationWhenNoImprovement = new JCheckBox(Texts.getText("stopIterationWhenNoMoveAndPushImprovement"), Settings.stopIterationWhenNoImprovement);
        stopIterationWhenNoImprovement.setToolTipText(Texts.getText("stopIterationWhenNoMoveAndPushImprovementTooltip"));
        stopIterationWhenNoImprovement.setEnabled(Settings.isIteratingEnabled);
        iterationPanel.add(stopIterationWhenNoImprovement);

        // A CheckBox for setting "new iteration when improvement" on/off.
        newIterationWhenImprovementFound = new JCheckBox(Texts.getText("immediateNewIterationWhenImprovement"), Settings.immediateNewIterationWhenImprovement);
        newIterationWhenImprovementFound.setToolTipText(Texts.getText("immediateNewIterationWhenImprovementTooltip"));
        newIterationWhenImprovementFound.setEnabled(Settings.isIteratingEnabled);
        iterationPanel.add(newIterationWhenImprovementFound);

        // If set, only the last solution of all iterations is saved.
        isOnlyLastSolutionToBeSaved = new JCheckBox(Texts.getText("onlyKeepLastSolution"), Settings.isOnlyLastSolutionToBeSaved);
        isOnlyLastSolutionToBeSaved.setToolTipText(Texts.getText("onlyKeepLastSolutionTooltip"));
        isOnlyLastSolutionToBeSaved.setEnabled(Settings.isIteratingEnabled);
        iterationPanel.add(isOnlyLastSolutionToBeSaved);

        isIteratingEnabled.addChangeListener(e -> {                                                 // only enable the other checkboxes if iterating is enabled
                    stopIterationWhenNoImprovement.setEnabled(isIteratingEnabled.isSelected());
                    newIterationWhenImprovementFound.setEnabled(isIteratingEnabled.isSelected());
                    isOnlyLastSolutionToBeSaved.setEnabled(isIteratingEnabled.isSelected());
                }
        );

        settingsPanel.add(iterationPanel, BorderLayout.NORTH);

        /* Panel for setting the maximum number of generated box configurations. */
        JPanel maxBoxConfigurationsPanel = new JPanel(new BorderLayout());
        maxBoxConfigurationsPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(Texts.getText("optimizer.maxBoxConfigurations")), BorderFactory.createEmptyBorder(5, 5, 5, 5)), maxBoxConfigurationsPanel.getBorder()));
        maxBoxConfigurationsPanel.setToolTipText(Texts.getText("optimizer.maxBoxConfigurationPanelTooltip"));
        Texts.helpBroker.enableHelpKey(maxBoxConfigurationsPanel, "optimizer.MaximumNumberOfBoxConfigurations", null); // Enable help

        // Button group for setting the maximum number of box configurations to be generated.
        ButtonGroup maxBoxConfigurationsButtonGroup = new ButtonGroup();

        // Button for selecting "automatically set maximum number". This button isn't really used. It's just there to deselect the next radio button.
        JRadioButton dummy2 = new JRadioButton(Texts.getText("optimizer.calculateAutomatically"), true);
        maxBoxConfigurationsButtonGroup.add(dummy2);
        maxBoxConfigurationsPanel.add(dummy2, BorderLayout.NORTH);

        // Button for selecting "manually set maximum number of box configurations to be generated".
        isMaxBoxConfigurationManuallySet = new JRadioButton("");
        maxBoxConfigurationsButtonGroup.add(isMaxBoxConfigurationManuallySet);
        maxBoxConfigurationsPanel.add(isMaxBoxConfigurationManuallySet, BorderLayout.WEST);

        // Panel containing the spinners for setting the value.
        JPanel valuePanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 3, 5));
        valuePanel.add(new JLabel(Texts.getText("optimizer.setManually")));
        maxBoxConfigurationsToBeGenerated = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1));
        ((JSpinner.DefaultEditor) maxBoxConfigurationsToBeGenerated.getEditor()).getTextField().setColumns(5);
        valuePanel.add(maxBoxConfigurationsToBeGenerated);
        valuePanel.add(new JLabel(Texts.getText("general.thousand")));
        maxBoxConfigurationsPanel.add(valuePanel, BorderLayout.CENTER);

        settingsPanel.add(maxBoxConfigurationsPanel, BorderLayout.CENTER);

        /* Panel for setting the player position fixed for all found solutions. */
        JPanel specialSettingsPanel = new JPanel(new BorderLayout());
        specialSettingsPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(Texts.getText("optimizer.specialSettings")), BorderFactory.createEmptyBorder(5, 5, 5, 5)), specialSettingsPanel.getBorder()));
        Texts.helpBroker.enableHelpKey(specialSettingsPanel, "optimizer.PreservePlayerEndPosition", null); // Enable help

        // Checkbox for setting the optimizer to preserve the player end position.
        isPlayerPositionToBePreserved = new JCheckBox(Texts.getText("optimizer.playerEndPositionIsFix"), false);
        specialSettingsPanel.add(isPlayerPositionToBePreserved, BorderLayout.NORTH);

        // The user may restrict the optimizer to only use a specific number of threads.
        // The text "CPUs" is used because this is easier to understand for the users.
        JPanel threads = new JPanel(new FlowLayout(FlowLayout.LEADING, 3, 5));
        threads.add(new JLabel(Texts.getText("optimizer.CPUsToUse")));
        int CPUCoresCountInitialValue = getCPUCoresCountInitialValue();
        threadsCount = new JSpinner(new SpinnerNumberModel(CPUCoresCountInitialValue, 1, Runtime.getRuntime().availableProcessors(), 1));
        threads.add(threadsCount);
        specialSettingsPanel.add(threads, BorderLayout.SOUTH);

        settingsPanel.add(specialSettingsPanel, BorderLayout.SOUTH);

        constraints.gridheight = 3;
        constraints.gridwidth = 1;
        constraints.gridy = 1;
        constraints.gridx++;
        constraints.fill = GridBagConstraints.NONE;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.weightx = 0;
        southPanel.add(additionalSettingsPanel, constraints);


        //
        // S O L U T I O N S
        //
        solutionsGUI = new SolutionsGUI(application, false, false);
        Texts.helpBroker.enableHelpKey(solutionsGUI, "optimizer.SolutionsList", null); // Enable help for the solutions list
        solutionsGUI.setLevel(currentLevel);

        // Put the list into a scroll pane and select the first solution.
        JScrollPane listScroller = new JScrollPane(solutionsGUI) {

            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                if (d.getWidth() < 200) {
                    d.width = 200;
                }
                return d;

            }

        };
        listScroller.setAlignmentX(LEFT_ALIGNMENT);

        // Display the whole level again when a new solution has been selected.
        // This has a better performance compared to always creating a new board
        // of the selected pushes range for the new solution.
        solutionsGUI.addListSelectionListener(e -> {

            newSolutionSelected();
        });

        solutionsGUI.setSelectedIndex(0);

        // Label for the scroll pane.
        JPanel listPane = new JPanel();
        listPane.setLayout(new BoxLayout(listPane, BoxLayout.PAGE_AXIS));
        JLabel label = new JLabel(Texts.getText("solutions"));
        label.setLabelFor(listScroller);
        listPane.add(label);
        listPane.add(listScroller);
        listPane.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));

        mainPanel.add(listPane, BorderLayout.WEST);

        //
        // S T A T U S L I N E
        //
        constraints.gridx = 0;
        constraints.gridy = 4;
        constraints.gridheight = 1;
        constraints.gridwidth = 4;
//        constraints.weightx = 1;
        constraints.insets.set(5, 10, 5, 10);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.LINE_START;
        infoText = new JTextField();
        infoText.setEditable(false);
        southPanel.add(infoText, constraints);

        // Special feature requested by specific persons.
        if (Settings.optimizerShowActiveAreaPushesMovesFeature) {
            JButton activeAreaDebug = new JButton("ActiveArea moves/pushes");
            activeAreaDebug.setActionCommand("displayMovesPushesInActiveArea");
            activeAreaDebug.addActionListener(this);
            add(activeAreaDebug, BorderLayout.NORTH);
        }

        // Set the JSoko icon.
        setIconImage(Utilities.getJSokoIcon());

        // Set the optimizer help for this GUI.
        Texts.helpBroker.enableHelpKey(getRootPane(), "optimizer", null);
    }

    /**
     * A new solution has been selected in the GUI. This triggers creating a new board, since
     * only a push-range may be selected which results in a different board.
     **/
    private void newSolutionSelected() {

        if (isOptimizerRunning && isPushRangeIntervalsOptimizationActivated.isSelected()) {
            return;
        }

        if (isOptimizeCompleteSolutionActivated.isSelected()) {
            // For optimizing the whole solution, the normal board is set. Even if the pushes range were set
            // to 0 to "pushes of solution" there may be boxes that are never pushed which are converted to walls.
            boardDisplay.setBoardToDisplay(board);
            if (solutionsGUI.getSelectedValue() != null) {
                setUntouchedPositionsInBoardDisplay(board, solutionsGUI.getSelectedValue().lurd);
            }
        }

        if (isRangeOfPushesActivated.isSelected()) {
            // Create a new board from the pushes range.
            setBoardToDisplayFromPushesRange();
        }
    }

    private int getDefaultIntervalSize() {
        if (currentLevel != null) {
            Solution solution = currentLevel.getSolutionsManager().getBestPushesSolution();
            if (solution != null) {
                return Math.max(4, solution.pushesCount / 2);
            }
        }
        return 4;  // minimum default value
    }

    /**
     * Return 75% of the available cores as default value when the program starts
     * for the very first time. However, the maximum CPU core value is 8,
     * since the algorithm doesn't scale well with higher numbers;
     * so not too many CPU cores are used without speeding up the program.
     * Otherwise, return the number stored in the Settings.
     *
     * @return the number of CPU cores to use
     */
    private int getCPUCoresCountInitialValue() {
        if (Settings.CPUCoresToUse > Runtime.getRuntime().availableProcessors()) {
            return Math.min(8, (int) Math.ceil(Runtime.getRuntime().availableProcessors() * 0.75d));
        }

        return Settings.CPUCoresToUse;
    }

    /**
     * Sets a new board to be displayed in the optimizer from the selected pushes range.
     * <p>
     * The user can select a push range of a solution in this GUI. The displayed board
     * is created from the selected pushes range and set as new board to be displayed.
     */
    private void setBoardToDisplayFromPushesRange() {

        // Try to commit changes by the user that haven't been set in the model.
        try {
            rangeOfPushesFromValue.commitEdit();
            rangeOfPushesToValue.commitEdit();
        } catch (ParseException pe) { /* last valid values are used */ }

        /* Set a new board from the selected pushes range. */
        optimizeFromPush = (Integer) rangeOfPushesFromValue.getValue();
        optimizeToPush = (Integer) rangeOfPushesToValue.getValue();

        List<Solution> selectedSolutions = Utilities.getSelectedValuesList(solutionsGUI);
        if (!selectedSolutions.isEmpty()) {
            Solution solutionToBeOptimized = getBestSelectedSolution(selectedSolutions, (OptimizationMethod) optimizationMethodCombobox.getSelectedItem());

            // Ensure valid input.
            if (optimizeToPush > solutionToBeOptimized.pushesCount) {
                optimizeToPush = solutionToBeOptimized.pushesCount;
            }
            if (optimizeFromPush >= solutionToBeOptimized.pushesCount) {
                optimizeFromPush = solutionToBeOptimized.pushesCount - 1;
            }
            if (optimizeToPush <= optimizeFromPush) {
                optimizeToPush = optimizeFromPush + 1;
            }

            // Create a new board that is to be used by the optimizer.
            Board boardForOptimizer = getBoardFromSolutionPart(optimizeFromPush, optimizeToPush, solutionToBeOptimized);
            boardDisplay.setBoardToDisplay(boardForOptimizer);

            solutionForCurrentBoardDisplay = getSolutionRange(solutionToBeOptimized, optimizeFromPush, optimizeToPush); // remember the set solution

            setUntouchedPositionsInBoardDisplay(boardForOptimizer, solutionForCurrentBoardDisplay.lurd);
        }
    }

    /**
     * Marks all untouched positions so a red cross is drawn on them.
     */
    private void setUntouchedPositionsInBoardDisplay(Board board, String lurd) {

        if (optimizationMethodCombobox.getSelectedItem() != OptimizationMethod.AVOID_MARKED_POSITIONS &&
                optimizationMethodCombobox.getSelectedItem() != OptimizationMethod.MAX_UNTOUCHED_BOXES) { // don't show untouched positions in other methods
            Arrays.fill(boardDisplay.isUntouchedPosition, false);
            return;
        }

        boolean onlyMarkUntouchedBoxPositions = optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.MAX_UNTOUCHED_BOXES;

        Board helpBoard = board.clone();

        int playerPosition = helpBoard.playerPosition;
        int newBoxPosition = 0;

        if (onlyMarkUntouchedBoxPositions) {
            Arrays.fill(boardDisplay.isUntouchedPosition, false);
            for(int boxPosition : board.boxData.getBoxPositionsClone()) {
                boardDisplay.isUntouchedPosition[boxPosition] = true;              // In the beginning, all box positions are marked as touched.
            }
        } else {
            Arrays.fill(boardDisplay.isUntouchedPosition, true);
            boardDisplay.isUntouchedPosition[playerPosition] = false;
        }

        // Go through the solution until the "toPush" is reached.
        for (int i = 0; i < lurd.length(); i++) {

            switch (lurd.charAt(i)) {

                case 'U':
                    playerPosition = helpBoard.getPosition(playerPosition, UP);
                    boardDisplay.isUntouchedPosition[playerPosition] = false;
                    newBoxPosition = helpBoard.getPosition(playerPosition, UP);
                    boardDisplay.isUntouchedPosition[newBoxPosition] = false;
                    break;

                case 'u':
                    playerPosition = helpBoard.getPosition(playerPosition, UP);
                    if (!onlyMarkUntouchedBoxPositions) {
                        boardDisplay.isUntouchedPosition[playerPosition] = false;
                    }
                    break;


                case 'D':
                    playerPosition = helpBoard.getPosition(playerPosition, DOWN);
                    boardDisplay.isUntouchedPosition[playerPosition] = false;
                    newBoxPosition = helpBoard.getPosition(playerPosition, DOWN);
                    boardDisplay.isUntouchedPosition[newBoxPosition] = false;
                    break;

                case 'd':
                    playerPosition = helpBoard.getPosition(playerPosition, DOWN);
                    if (!onlyMarkUntouchedBoxPositions) {
                        boardDisplay.isUntouchedPosition[playerPosition] = false;
                    }
                    break;


                case 'L':
                    playerPosition = helpBoard.getPosition(playerPosition, LEFT);
                    boardDisplay.isUntouchedPosition[playerPosition] = false;
                    newBoxPosition = helpBoard.getPosition(playerPosition, LEFT);
                    boardDisplay.isUntouchedPosition[newBoxPosition] = false;
                    break;

                case 'l':
                    playerPosition = helpBoard.getPosition(playerPosition, LEFT);
                    if (!onlyMarkUntouchedBoxPositions) {
                        boardDisplay.isUntouchedPosition[playerPosition] = false;
                    }
                    break;


                case 'R':
                    playerPosition = helpBoard.getPosition(playerPosition, RIGHT);
                    boardDisplay.isUntouchedPosition[playerPosition] = false;
                    newBoxPosition = helpBoard.getPosition(playerPosition, RIGHT);
                    boardDisplay.isUntouchedPosition[newBoxPosition] = false;
                    break;

                case 'r':
                    playerPosition = helpBoard.getPosition(playerPosition, RIGHT);
                    if (!onlyMarkUntouchedBoxPositions) {
                        boardDisplay.isUntouchedPosition[playerPosition] = false;
                    }
                    break;
            }

            // Push a box if necessary.
            if (helpBoard.isBox(playerPosition)) {
                helpBoard.pushBox(playerPosition, newBoxPosition);
            }

            // Move the player.
            helpBoard.setPlayerPosition(playerPosition);
        }
    }

    /**
     * Returns the best {@link Solution} of the passed solutions according to the passed {@link OptimizationMethod}.
     *
     * @param solutions          solutions to return the best from
     * @param optimizationMethod method of optimization (pushes/moves, moves/pushes, ...)
     * @return the best found {@link Solution}
     */
    private Solution getBestSelectedSolution(List<Solution> solutions, OptimizationMethod optimizationMethod) {

        if (solutions.isEmpty()) {
            return null;
        }

        Solution bestSolution = solutions.get(0);
        for (Solution solution : solutions) {
            if (optimizationMethod == OptimizationMethod.MOVES_PUSHES || optimizationMethod == OptimizationMethod.MOVES_PUSHES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS) {
                if (solution.isBetterMovesSolutionThan(bestSolution)) {
                    bestSolution = solution;
                }
            }
            if (optimizationMethod == OptimizationMethod.PUSHES_MOVES || optimizationMethod == OptimizationMethod.PUSHES_MOVES_BOXLINES_BOXCHANGES_PUSHINGSESSIONS) {
                if (solution.isBetterPushesSolutionThan(bestSolution)) {
                    bestSolution = solution;
                }
            }
            if (optimizationMethod == OptimizationMethod.MOVES_HIGHEST_PUSHES) {
                if (solution.movesCount < bestSolution.movesCount || solution.movesCount == bestSolution.movesCount && solution.pushesCount > bestSolution.pushesCount) {
                    bestSolution = solution;
                }
            }
            if (optimizationMethod == OptimizationMethod.BOXLINES_MOVES) {
                if (solution.isBetterBoxLinesMovesSolutionThan(bestSolution)) {
                    bestSolution = solution;
                }
            }
            if (optimizationMethod == OptimizationMethod.BOXLINES_PUSHES || optimizationMethod == OptimizationMethod.BOXLINES) {
                if (solution.isBetterBoxLinesPushesSolutionThan(bestSolution)) {
                    bestSolution = solution;
                }
            }
            if (optimizationMethod == OptimizationMethod.BOXCHANGES_PUSHES) {
                if (solution.isBetterBoxChangesPushesSolutionThan(bestSolution)) {
                    bestSolution = solution;
                }
            }
            if (optimizationMethod == OptimizationMethod.PUSHINGSESSIONS_MOVES) {
                if (solution.isBetterPushingSessionsMovesSolutionThan(bestSolution)) {
                    bestSolution = solution;
                }
            }
        }

        return bestSolution;
    }

    /* (non-Javadoc)
     *
     * @see javax.swing.JFrame#processWindowEvent(java.awt.event.WindowEvent) */
    @Override
    protected void processWindowEvent(WindowEvent e) {

        // If the user closes the Frame, check whether the optimizer is still running
        // and save the settings.
        if (e.getID() == WindowEvent.WINDOW_CLOSING) {
            boolean isOptimizerToBeClosed = closeOptimizerGUI();
            if (!isOptimizerToBeClosed) {
                return;
            }
        }

        // Process the window event.
        super.processWindowEvent(e);
    }

    /**
     * Closes the optimizer. However, the user can decide what to do in case the optimizer is still running.
     * If the user doesn't want to close the gui then this method returns false.
     *
     * @return false if the optimizer is still running and the optimizer mustn't be closed
     */
    private boolean closeOptimizerGUI() {

        if (OptimizerAsPlugin.isOptimizerPluginModus) {
            if (isOptimizerRunning) {        // stops the optimizer and saves the found solution for the plugin caller by doing so
                optimizer.stopOptimizer();  // the optimizer is left in this case from method "optimizerEnded"
            }
            return false; // should never be reached
        }

        // Ask the user whether the running optimizer is really to be closed.
        if (isOptimizerRunning) {
            if (JOptionPane.showConfirmDialog(this, Texts.getText("closeOptimizerWhileRunning"), Texts.getText("warning"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return false;
            }
        }

        // Stop the optimizer. Even if it is not running the stop is important
        // because the deadlock identification thread already runs when the optimizer is instantiated.
        if (optimizer != null) {
            optimizer.stopOptimizer();
        }

        // The optimizer itself isn't used anymore.
        optimizer = null;

        /* Save the settings in the global settings file. */
        int fieldCounter = 0;
        for (NumberInputTF numberField : vicinitySettings) {

            Integer value = numberField.getValueAsIntegerNoNull();
            boolean isActive = numberField.isFieldActive();

            switch (++fieldCounter) {
                case 1:
                    Settings.vicinitySquaresBox1 = value;
                    Settings.vicinitySquaresBox1Enabled = isActive;
                    break;
                case 2:
                    Settings.vicinitySquaresBox2 = value;
                    Settings.vicinitySquaresBox2Enabled = isActive;
                    break;
                case 3:
                    Settings.vicinitySquaresBox3 = value;
                    Settings.vicinitySquaresBox3Enabled = isActive;
                    break;
                case 4:
                    Settings.vicinitySquaresBox4 = value;
                    Settings.vicinitySquaresBox4Enabled = isActive;
                    break;
                case 5:
                    Settings.vicinitySquaresBox5 = value;
                    Settings.vicinitySquaresBox5Enabled = isActive;
                    break;
                case 6:
                    Settings.vicinitySquaresBox6 = value;
                    Settings.vicinitySquaresBox6Enabled = isActive;
                    break;
                case 7:
                    Settings.vicinitySquaresBox7 = value;
                    Settings.vicinitySquaresBox7Enabled = isActive;
                    break;
                case 8:
                    Settings.vicinitySquaresBox8 = value;
                    Settings.vicinitySquaresBox8Enabled = isActive;
                    break;
            }
        }
        Settings.isIteratingEnabled = isIteratingEnabled.isSelected();
        Settings.isOnlyLastSolutionToBeSaved = isOnlyLastSolutionToBeSaved.isSelected();
        Settings.stopIterationWhenNoImprovement = stopIterationWhenNoImprovement.isSelected();
        Settings.immediateNewIterationWhenImprovement = newIterationWhenImprovementFound.isSelected();
        Settings.CPUCoresToUse = (Integer) threadsCount.getValue();

        Settings.optimizerXCoordinate = getX();
        Settings.optimizerYCoordinate = getY();
        Settings.optimizerWidth = getWidth();
        Settings.optimizerHeight = getHeight();
        Settings.optimizationMethod = ((OptimizationMethod) optimizationMethodCombobox.getSelectedItem()).ordinal();

        // Set the focus back to the main GUI to allow keyboard usage in the main GUI.
        application.getRootPane().requestFocus();
        application.applicationGUI.mainBoardDisplay.requestFocusInWindow();

        dispose();

        return true;
    }

    /**
     * Handles all action events.
     *
     * @param evt the action event to be handled.
     */
    @Override
    public void actionPerformed(ActionEvent evt) {

        String action = evt.getActionCommand();

        // The optimizer is to be started.
        if (action.equals("startOptimizer")) {
            if (isPushRangeIntervalsOptimizationActivated.isSelected()) {
                setInitialValuesForPushesRangeOptimizationWithIntervals();
            }
            startOptimizer();
            return;
        }

        // The optimizer is to be stopped.
        if (action.equals("stopOptimizer")) {
            pushesLastOptimizedSolution = NONE;    // this avoids another interval to be started
            optimizer.stopOptimizer();
            return;
        }

        // DEBUG: Display the number of moves and pushes of the solution in the marked "active area".
        if (action.equals("displayMovesPushesInActiveArea")) {
            debugDisplayMovesPushesInActiveArea();
            return;
        }
    }

    /**
     * Starts the optimizer with the current settings.
     */
    private void startOptimizer() {

        // Ensure that at least on solution has been selected for optimizing.
        if (solutionsGUI.getSelectedIndex() == -1) {
            if (solutionsGUI.getModel().getSize() == 1) {    // if there is only one solution, select it
                solutionsGUI.setSelectedIndex(0);
            } else {
                setInfoText(Texts.getText("optimizer.xSelectedSolutions", 0)); // Display method that no solution has been selected
                return;
            }
        }

        if (optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.AVOID_MARKED_POSITIONS) {
            boolean[] isForbiddenPosition = boardDisplay.getMarkedSquares();
            if (!Utilities.hasANotSetBit(isForbiddenPosition)) {           // when all positions are marked, this means the user has not selected any forbidden positions!
                JOptionPane.showMessageDialog(this, Texts.getText("optimizer.avoidMarkedPositions.pleaseMarkPositions"));
                return;
            }
        }

        // Be sure that the optimizer isn't running at the moment.
        if (isOptimizerRunning) {
            return;
        }

        // Save the current time.
        startTimestamp = System.currentTimeMillis();

        // The optimizer for the original board has been saved and can now be set back.
        // As default, the original level is optimized. Only if the user has selected
        // "pushes range optimization" a new optimizer is created.
        optimizer = optimizerOriginalLevel;

        // Get the selected solutions and convert them in the Optimizer solution class.
        ArrayList<OptimizerSolution> solutionsToBeOptimized = getSelectedSolutions();

        // If the box changes are to be optimized, call the corresponding method.
        if (optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.BOXCHANGES) {
            startBoxChangesOptimization(solutionsToBeOptimized.get(0)); // Use the first selected solution for optimization
            return;
        }

        ArrayList<Integer> vicinityRestrictions = getVicinityRestrictions();   // Get the vicinity square settings

        if (vicinityRestrictions.isEmpty()) {
            JOptionPane.showMessageDialog(this, Texts.getText("selectAtLeastOneBox"));
            return;
        }

        // The optimizer is running.
        isOptimizerRunning = true;

        // Delete the log for the new optimizer run.
        optimizerLog.setText(null);

        // Determine the optimization type.
        OptimizationMethod optimizationType = (OptimizationMethod) optimizationMethodCombobox.getSelectedItem();

        // Flag, indicating whether the new solution must have the same
        // player end position as the original one
        boolean isPlayerEndPositionFixed = isPlayerPositionToBePreserved.isSelected();

        // Start the optimizer. If the optimization with a push range is activated,
        // the prefix and suffix values have to be passed, too.
        int prefixMovesCount = 0;
        int prefixPushesCount = 0;
        int suffixMovesCount = 0;
        int suffixPushesCount = 0;

        // Check whether the user wants to optimize just a part of the solution.
        if (isRangeOfPushesActivated.isSelected() || isPushRangeIntervalsOptimizationActivated.isSelected()) {

            // Optimization with a pushes range can only be done with one solution at a time.
            // Determine the best solution of the passed solutions.
            Solution solutionToBeOptimized = getBestSelectedSolution(selectedSolutionsToBeOptimized, optimizationType);
            selectedSolutionsToBeOptimized.clear();
            selectedSolutionsToBeOptimized.add(solutionToBeOptimized);

            // In case pushes intervals are to be optimized the next pushes interval is set
            // and the board for that interval is determined.
            if (isPushRangeIntervalsOptimizationActivated.isSelected()) {
                setNextPushesInterval(solutionToBeOptimized);
            }

            // Fix the player end position if the solution doesn't include the
            // last push of the solution. This must be done to ensure the optimizer
            // doesn't find a new solution with the player at another end position
            // because this would mean the rest of the solution (postfixSolutionMoves)
            // couldn't be added anymore.
            if (optimizeToPush < solutionToBeOptimized.pushesCount) {
                isPlayerEndPositionFixed = true;
            }

            // Ensure the pushes range is within the pushes range of the best solution.
            optimizeFromPush = Math.min(optimizeFromPush, solutionToBeOptimized.pushesCount - 1);
            optimizeToPush = Math.min(optimizeToPush, solutionToBeOptimized.pushesCount);

            // Split the solution according to the selected range.
            prefixSolutionMoves = getSolutionRange(solutionToBeOptimized, 0, optimizeFromPush).lurd;
            suffixSolutionMoves = getSolutionRange(solutionToBeOptimized, optimizeToPush, solutionToBeOptimized.pushesCount).lurd;
            solutionToBeOptimized = getSolutionRange(solutionToBeOptimized, optimizeFromPush, optimizeToPush);

            // Add the solution part as a solution to be optimized.
            solutionsToBeOptimized.clear();
            solutionsToBeOptimized.add(new OptimizerSolution(solutionToBeOptimized));

            prefixMovesCount = prefixSolutionMoves.length();
            prefixPushesCount = optimizeFromPush;
            suffixMovesCount = suffixSolutionMoves.length();
            suffixPushesCount = selectedSolutionsToBeOptimized.get(0).pushesCount - optimizeToPush; // Get the whole solution again and calculate pushes difference

            // Create a new optimizer for the new board.
            optimizer = new Optimizer(boardDisplay.getBoard(), this, optimizerOriginalLevel, (Integer) threadsCount.getValue());

            // Debug
            // System.out.printf("\nPrefix moves: "+prefixSolutionMoves);
            // System.out.printf("\nSolution to be optimized: "+solutionToBeOptimized.lurd);
            // System.out.printf("\nPostfix moves: "+suffixSolutionMoves);
            // System.out.printf("\n\n");
        }

        // Determine the maximum number of box configurations to be generated.
        int maximumNoOfBoxConfigurations = getMaximumNumberOfBoxConfigurationsToBeGenerated(solutionsToBeOptimized.get(0));

        // Color selected solutions, so the user knows which solutions are being optimized.
        for (Solution s : selectedSolutionsToBeOptimized) {
            solutionsGUI.setSolutionColor(s, new Color(0x87, 0xCE, 0xFF));
        }

        boolean[] relevantBoxSquares = boardDisplay.getMarkedSquares();
        boolean[] forbiddenPlayerPositions = new boolean[board.width * board.height];

        if (optimizationType == OptimizationMethod.AVOID_MARKED_POSITIONS) {
            forbiddenPlayerPositions = relevantBoxSquares.clone();  // use the marked positions as forbidden player positions
            Arrays.fill(relevantBoxSquares, true);  // all boxes positions are relevant
        }

        optimizer.startOptimizer(
                Utilities.toIntArray(vicinityRestrictions),
                relevantBoxSquares,
                forbiddenPlayerPositions,
                solutionsToBeOptimized,
                optimizationType,
                maximumNoOfBoxConfigurations,
                isIteratingEnabled.isSelected(),
                isOnlyLastSolutionToBeSaved.isSelected(),
                stopIterationWhenNoImprovement.isSelected(),
                newIterationWhenImprovementFound.isSelected(),
                isPlayerEndPositionFixed,
                (Integer) threadsCount.getValue(),
                prefixMovesCount, prefixPushesCount, suffixMovesCount, suffixPushesCount,
                getAxisOfLastPush(prefixSolutionMoves)
        );

        // Rename the start button to "stop optimizer" and set a new action command for it.
        startButton.setToStop();
        showBestSolutionButton.setEnabled(true);
    }

    /**
     * Set the start values for the optimization using pushes intervals.
     */
    private void setInitialValuesForPushesRangeOptimizationWithIntervals() {
        pushesLastOptimizedSolution = NONE;    // Initialize since a complete new run is started

        // When the whole solution is to be optimized, the first pushes interval starts with push 0.
        if (isOptimizeCompleteSolutionActivated.isSelected()) {
            currentIntervalBeginPushesValue = 0;
            intervalMaxPushesValues = Integer.MAX_VALUE; // no specific maximum for the maximum number of pushes
        } else {
            currentIntervalBeginPushesValue = (int) rangeOfPushesFromValue.getValue();
            intervalMaxPushesValues = (int) rangeOfPushesToValue.getValue();   // the set value is used as maximum
        }
    }

    /**
     * In case the user has selected "pushes interval optimizing",
     * this method sets the next interval to be optimized.
     * <p>
     * Example:
     * The user has set an interval size of 100 pushes
     * Then this method sets the pushes intervals:
     * 100 - 200
     * 200 - 300
     * 400 - 500
     * ...
     */
    private void setNextPushesInterval(Solution solutionToOptimize) {

        // When the last interval has cut some pushes we adjust the next interval accordingly.
        int pushesImprovement = pushesLastOptimizedSolution == NONE ? 0 : (pushesLastOptimizedSolution - solutionToOptimize.pushesCount);

        if (Settings.doNotReduceOptimizerIntervalsByPushesImprovements) {
            pushesImprovement = 0;
        }

        int intervaleSize = (Integer) intervalSizeInPushes.getValue();
        int nextPushesFromValue = currentIntervalBeginPushesValue - pushesImprovement;
        int nextPuhesToValue = Math.min(currentIntervalBeginPushesValue - pushesImprovement + intervaleSize, intervalMaxPushesValues);

        rangeOfPushesFromValue.setValue(nextPushesFromValue);
        rangeOfPushesToValue.setValue(nextPuhesToValue);
        setBoardToDisplayFromPushesRange();

//    System.out.println("pushes last solution: "+pushesLastOptimizedSolution);
//    System.out.println("pushes solution to be optimized: "+solutionToOptimize);
//    System.out.println("optimizing pushes range: "+rangeOfPushesFromValue.getValue()+"-"+rangeOfPushesToValue.getValue());

        currentIntervalBeginPushesValue = (Integer) rangeOfPushesFromValue.getValue() + intervaleSize;  // Prepare for the next interval
        pushesLastOptimizedSolution = solutionToOptimize.pushesCount;                                   // remember the push count of the optimized solution
    }

    /**
     * Returns the maximum number of box configurations to be generated.
     * If the user has set the value manually then this value is taken.
     * However, it must at least be high enough so all box configurations
     * of one solution can be generated.
     * Hence, one of the solutions has to be passed to determine the minimum number of pushes.
     *
     * @return the maximum number of box configurations to be generated.
     */
    private int getMaximumNumberOfBoxConfigurationsToBeGenerated(OptimizerSolution solution) {

        int userSetMaximumNoOfBoxConfigurations = Optimizer.NONE;
        if (isMaxBoxConfigurationManuallySet.isSelected()) {
            userSetMaximumNoOfBoxConfigurations = 1000 * (Integer) maxBoxConfigurationsToBeGenerated.getValue();
            if (solution.pushesCount >= userSetMaximumNoOfBoxConfigurations) {
                userSetMaximumNoOfBoxConfigurations = solution.pushesCount + 1;
            }
        }

        return userSetMaximumNoOfBoxConfigurations;
    }

    /**
     * Returns the values of the box vicinity settings.
     * The values are increased by one since this is more logical for the user:
     * a setting of 1 therefore means "1 square in the vicinity of the box square".
     * The generator logic, however, counts the square of the box as 1 square, too => increase the settings.
     *
     * @return
     */
    private ArrayList<Integer> getVicinityRestrictions() {

        ArrayList<Integer> vicinityRestrictions = new ArrayList<>();
        for (NumberInputTF numberField : vicinitySettings) {
            Integer value = numberField.getValueAsInteger();
            if (value != null) {
                vicinityRestrictions.add(value + 1);
            }
        }
        // Sort the values ascending. This is important for the box configuration generator.
        Collections.sort(vicinityRestrictions);

        return vicinityRestrictions;
    }

    /**
     * Returns all selected solutions as `OptimizerSolution`s.
     *
     * @return
     */
    private ArrayList<OptimizerSolution> getSelectedSolutions() {
        selectedSolutionsToBeOptimized = Utilities.getSelectedValuesList(solutionsGUI);
        ArrayList<OptimizerSolution> solutionsToBeOptimized = new ArrayList<>(selectedSolutionsToBeOptimized.size());
        for (Solution solution : selectedSolutionsToBeOptimized) {
            solutionsToBeOptimized.add(new OptimizerSolution(solution));
        }
        return solutionsToBeOptimized;
    }

    /**
     * Starts an optimization for box changes.
     */
    private void startBoxChangesOptimization(OptimizerSolution solutionToBeOptimized) {

        isOptimizerRunning = true;

        // Delete the log for the new optimizer run.
        optimizerLog.setText(null);

        // Optimize the first of the selected solutions.
        OptimizerSolution optimizedSolution = solutionToBeOptimized;
        OptimizerSolution bestFoundSolution = null;
        // Optimize until no further better solution can be found.
        while (optimizedSolution != null) {
            optimizedSolution = optimizer.reduceBoxChanges(optimizedSolution);
            if (optimizedSolution != null) {
                bestFoundSolution = optimizedSolution;
            }
        }

        // The optimizer tries to push the same box again if possible to reduce box changes.
        // However, this "same box push" may result in an additional box change after this
        // new push sequence. Hence, the new-found solution may have the same number of
        // box changes as the selected solution. It therefore has to be checked whether the
        // new-found solution is really better than the selected solution to be optimized.
        if (bestFoundSolution != null) {

            // Create "real" solutions from the OptimizerSolutions and
            // verify them to ensure all metrics (like box changes) are calculated.
            Solution bestSolution = new Solution(bestFoundSolution.getLURD());
            currentLevel.getSolutionsManager().verifySolution(bestSolution);
            Solution selectedSolution = new Solution(solutionToBeOptimized.getLURD());
            currentLevel.getSolutionsManager().verifySolution(selectedSolution);

            // If the new solution isn't better than the selected solution, it is set to "null",
            // so the method "optimizerEnded" will display an appropriate message.
            if (!bestSolution.isBetterPushesSolutionThan(selectedSolution)) {
                bestFoundSolution = null;
            } else {
                // Save the new solution if there is any.
                newFoundSolution(bestFoundSolution, Collections.emptyList());
            }
        }

        // Optimizer has ended.
        optimizerEnded(bestFoundSolution);
    }

    private void debugDisplayMovesPushesInActiveArea() {

        // Get the first selected solution (if any)
        Solution sol = solutionsGUI.getSelectedValue();
        if (sol != null) {
            printMetricsInActiveArea(sol);
        }
    }

    private void printMetricsInActiveArea(Solution solution) {

        int newBoxPosition = 0;
        int lastPushedBoxPosition = 0;

        // Get a boolean array where marked squares have a value of "true".
        boolean[] relevant = boardDisplay.getMarkedSquares();

        // Initialize the metric values of the solution.
        int pushesCount = 0;
        int movesCount = 0;
        int boxLines = 0;
        int boxChanges = 0;
        int pushingSessions = 0;
        int playerLines = 0;

        int playerPosition = board.playerPosition;
        boolean lastMovementWasMove = true;   // if the first move is also a push, then this increases boxLines, boxChanges and pushingSessions

        int lastMoveDirection = -1;

        for (int index = 0; index < solution.lurd.length(); index++) {

            final char moveChar = solution.lurd.charAt(index);

            int direction = -1;

            switch (moveChar) {
                case 'u':
                case 'U':
                    direction = UP;
                    break;

                case 'd':
                case 'D':
                    direction = DOWN;
                    break;

                case 'l':
                case 'L':
                    direction = LEFT;
                    break;

                case 'r':
                case 'R':
                    direction = RIGHT;
                    break;

                default:
                    continue;
            }

            // Calculation of the new player and potential new box position.
            int newPlayerPosition =

                    playerPosition = board.getPosition(playerPosition, direction);
            newBoxPosition = board.getPosition(newPlayerPosition, direction);

            boolean isPush = Character.isUpperCase(moveChar);

            if (relevant[playerPosition]) {
                movesCount++;
                if (isPush) {
                    pushesCount++;

                    if (playerPosition != lastPushedBoxPosition || lastMovementWasMove) {
                        boxLines++;
                    }
                    if (playerPosition != lastPushedBoxPosition) {
                        boxChanges++;
                    }
                    if (lastMovementWasMove) {
                        pushingSessions++;
                    }
                }
                if (direction != lastMoveDirection) {
                    playerLines++;
                }
            }

            if (isPush) {
                lastPushedBoxPosition = newBoxPosition;
            }
            lastMovementWasMove = !isPush;
            lastMoveDirection = direction;
        }

        // Display the result: number of moves and pushes
        // of the selected solution in the relevant area.
        addLogText("Active area: " + movesCount + "/" + pushesCount + "/" + boxLines + "/" + boxChanges + "/" + pushingSessions + "/" + playerLines + " in active area.");
    }

    public void startOptimizerPluginMode() {

        OptimizerSettings optimizerSettings = OptimizerAsPlugin.settings;

        // Select all solutions for optimizing.
        IntStream.range(0, solutionsGUI.getModel().getSize()).forEach(solutionsGUI::setSelectedIndex);

        // Set box vicinity squares.
        vicinitySettings.forEach(numberField -> numberField.setEnabled(false));// default -> all disabled

        int maxBoxes = Math.min(optimizerSettings.vicinityRestrictions.size(), vicinitySettings.size()); // Minimum of passed box settings and actual existing
        // settings
        for (int boxNo = 0; boxNo < maxBoxes; boxNo++) {
            NumberInputTF boxVicinitySquares = vicinitySettings.get(boxNo);
            int valueToSet = optimizerSettings.vicinityRestrictions.get(boxNo);
            if (valueToSet > 0) {
                boxVicinitySquares.setEnabled(true);
                boxVicinitySquares.setValue(valueToSet);
            }
        }

        optimizationMethodCombobox.setSelectedItem(optimizerSettings.method);

        isIteratingEnabled.setSelected(optimizerSettings.iterativeOptimization);

        if (optimizerSettings.maximumBoxConfigurations >= 100) { // don't use to low settings
            isMaxBoxConfigurationManuallySet.setSelected(true);
            maxBoxConfigurationsToBeGenerated.setValue(optimizerSettings.maximumBoxConfigurations);
        }

        isPlayerPositionToBePreserved.setSelected(optimizerSettings.preservePlayerEndPosition);

        threadsCount.getModel().setValue(optimizerSettings.cpusToUse);

        isOnlyLastSolutionToBeSaved.setSelected(false); // every found solution is returned. Caller of this plugin can decide what to do with them.

        actionPerformed(new ActionEvent(this, 0, "startOptimizer"));    // start optimizer
    }

    /**
     * Returns the axis of the last push in the given moves string.
     * This is needed in case a pushes range is optimized.
     * In that case, the optimizer only optimizes from the beginning of the pushes range.
     * When the start board position looks like this:
     * -$-
     * $@-
     * the optimizer doesn't know whether the last done push has been
     * a push up or a push to the left.
     * Hence, it can't check whether the next push results in a new box lines / box change.
     * Therefore, the optimizer is passed the axis of the last done push, so it can identify
     * the last pushed box.
     * <p>
     * Since the user can only adjust pushes for the pushes range optimization,
     * there is always a previous "last push" except for the start position of the level.
     * In that case, this method must return NONE as axis of the last push.
     *
     * @param movesString the moves string
     * @return the axis of the last done push
     */
    private static int getAxisOfLastPush(String movesString) {
        if (movesString.isEmpty()) {
            return Optimizer.NONE;
        }

        char lastChar = Character.toLowerCase(movesString.charAt(movesString.length() - 1));
        return lastChar == 'u' || lastChar == 'd' ? Directions.AXIS_VERTICAL : Directions.AXIS_HORIZONTAL;
    }

    /**
     * Adds the passed <code>String</code> to the log texts of the optimizer
     * to inform the user about the progress of the optimizer,
     * or to inform the developer about statistical data.
     *
     * @param text      text to be added to the log
     * @param styleName registered name of style to be used
     */
    private void addLogTextStyle(final String text, final String styleName) {
        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument doc = optimizerLog.getStyledDocument();
                doc.insertString(doc.getLength(), text + "\n", doc.getStyle(styleName));
            } catch (BadLocationException e) {
                /* ignore */
            }
        });
    }

    @Override
    public void addLogText(final String text) {
        addLogTextStyle(text, "regular");
    }

    @Override
    public void addLogTextDebug(final String text) {
        addLogTextStyle(text, "debug");

        // Additional output on the console since the log is cleared in the next run.
        System.out.println(text);
    }

    /**
     * This method is called when the user requests to see the best fond solution so far.
     * The optimizer returns the best found solution (while still running), and it is added to the solutions list.
     */
    public void displayCurrentBestFoundSolution() {

        OptimizerSolution bestFoundSolution = optimizer.getBestFoundSolutionSoFar();

        if (bestFoundSolution != null) {
            // The user may have restricted the search to a part of the solution only.
            // Therefore, add the moves before this part and after this part here to ensure
            // the solution is a valid solution for the level.
            String solutionLURD = prefixSolutionMoves + bestFoundSolution.getLURD() + suffixSolutionMoves;

            final Solution newSolution = new Solution(solutionLURD);
            newSolution.name = Texts.getText("createdBy") + " " + Texts.getText("optimizer") + " " + Utilities.nowString();
            currentLevel.addSolution(newSolution);

            SwingUtilities.invokeLater(() -> solutionsGUI.setSelectedValue(newSolution, true));
        }
    }

    /**
     * This method is called from the optimizer every time it has found a new solution.
     *
     * @param bestFoundSolution      the best found solution
     * @param solutionsToBeOptimized the solution(s) the optimizer will optimize next
     * @return the solution that has been added to the level
     */
    @Override
    public Solution newFoundSolution(OptimizerSolution bestFoundSolution, List<OptimizerSolution> solutionsToBeOptimized) {

        // The user may have restricted the search to a part of the solution only.
        // Therefore, add the moves before this part and after this part here to ensure
        // the solution is a valid solution for the level.
        String solutionLURD = prefixSolutionMoves + bestFoundSolution.getLURD() + suffixSolutionMoves;

        // Add the solution to the solutions of the level.
        final Solution newSolution = new Solution(solutionLURD);
        newSolution.name = Texts.getText("createdBy") + " " + Texts.getText("optimizer") + " " + Utilities.nowString();
        currentLevel.addSolution(newSolution);

        solutionsGUI.unhideSolution(newSolution);   // the solution may be hidden => unhide it to show it to the user

        selectedSolutionsToBeOptimized.clear();
        for (OptimizerSolution solution : solutionsToBeOptimized) {
            String lurdString = prefixSolutionMoves + solution.getLURD() + suffixSolutionMoves;
            Solution solutionToBeOptimized = new Solution(lurdString);
            selectedSolutionsToBeOptimized.add(solutionToBeOptimized);
        }

        Runnable runnable = () -> {

            solutionsGUI.setAllSolutionsUncolored();

            List<Solution> originalSolutions = currentLevel.getSolutionsManager().getSolutions();
            for (Solution optimizerSolutions : selectedSolutionsToBeOptimized) {

                // The optimizer solutions only contain lower case lurd characters!
                for (Solution solution : originalSolutions) {
                    if (solution.lurd.toLowerCase().equals(optimizerSolutions.lurd)) {
                        solutionsGUI.setSolutionColor(solution, selectedSolutionsColor);
                        break;
                    }
                }
            }

            // Select the new-found solution to show the user which solution has been found.
            solutionsGUI.setSelectedValue(newSolution, true);
        };

        // Select the solution. Since GUI changes are made, this must be done on the EDT.
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }

        if (OptimizerAsPlugin.isOptimizerPluginModus) {
            outputForCaller(newSolution);
        }

        // Return the solution that has been added to the level.
        return newSolution;
    }

    /**
     * Saves the new-found solution for the caller that uses JSoko as a "plugin"
     * which optimizes a level.
     */
    private void outputForCaller(Solution newSolution) {

        String outputString = "Solution " + newSolution.toString() + "\n" + newSolution.lurd + "\n";

        String vicinityString = vicinitySettings.stream().map(NumberInputTF::getValueAsInteger)
                .filter(Objects::nonNull)           // no inactive box settings
                .map(String::valueOf)               // internally one is added to these values, but the user doesn't see that -> output the given values not the internal ones
                .collect(Collectors.joining("/"));  // Create string in format: %d/%d/...

        outputString += "Optimized with vicinity settings: " + vicinityString + "\n";

        OptimizerAsPlugin.outputString(outputString);
    }

    /**
     * This method is called when the optimizer thread has ended.
     *
     * @param bestFoundSolution the best found solution
     */
    @Override
    public void optimizerEnded(final OptimizerSolution bestFoundSolution) {

        // Set the new status of the optimizer thread.
        isOptimizerRunning = false;

        // The optimizer has ended. Display the result, rename the button
        // back to "start" and remove the coloring of all solutions.
        // This method is called from the optimizer thread, hence ensure
        // that the swing components are updated on the EDT.
        SwingUtilities.invokeLater(() -> {
            // Display a message to inform the user about the found solution.
            if (bestFoundSolution == null) {
                setInfoText(Texts.getText("noNewSolutionFound") + " " + Texts.getText("time") + ": " + ((System.currentTimeMillis() - startTimestamp) / 1000f) + " " + Texts.getText("seconds"));
            } else {
                // The optimizer has ended. Hence, it must be the last solution it has found. If the user selected "only save last
                // solution" this solution hasn't been saved, yet -> save it now.
                Solution newSolutionOfLevel = newFoundSolution(bestFoundSolution, Collections.emptyList());

                // The box changes optimization considers moves, pushes and box changes. Hence, display all values.
                if (optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.BOXCHANGES) {
                    setInfoText(Texts.getText("foundSolution") + " " + Texts.getText("moves") + " = " + newSolutionOfLevel.movesCount + ", " + Texts.getText("pushes") + " = " + newSolutionOfLevel.pushesCount + ", " + Texts.getText("boxChanges") + " = " + newSolutionOfLevel.boxChanges + ", " + Texts.getText("time") + ": " + ((System.currentTimeMillis() - startTimestamp) / 1000f) + " " + Texts.getText("seconds"));
                } else {
                    // The "box lines only" optimization only considers box lines. Hence, just display the new number of box lines.
                    if (optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.BOXLINES) {
                        setInfoText(Texts.getText("foundSolution") + " " + Texts.getText("boxLines") + " = " + newSolutionOfLevel.boxLines + ", " + Texts.getText("time") + ": " + ((System.currentTimeMillis() - startTimestamp) / 1000f) + " " + Texts.getText("seconds"));
                    } else if (optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.BOXLINES_PUSHES) {
                        setInfoText(Texts.getText("foundSolution") + " " + Texts.getText("boxLines") + " = " + newSolutionOfLevel.boxLines + ", " + Texts.getText("pushes") + " = " + newSolutionOfLevel.pushesCount + ", " + Texts.getText("time") + ": " + ((System.currentTimeMillis() - startTimestamp) / 1000f) + " " + Texts.getText("seconds"));
                    } else if (optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.BOXLINES_MOVES) {
                        setInfoText(Texts.getText("foundSolution") + " " + Texts.getText("boxLines") + " = " + newSolutionOfLevel.boxLines + ", " + Texts.getText("moves") + " = " + newSolutionOfLevel.movesCount + ", " + Texts.getText("time") + ": " + ((System.currentTimeMillis() - startTimestamp) / 1000f) + " " + Texts.getText("seconds"));
                    } else if (optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.BOXCHANGES_MOVES) {
                        setInfoText(Texts.getText("foundSolution") + " " + Texts.getText("boxChanges") + " = " + newSolutionOfLevel.boxChanges + ", " + Texts.getText("moves") + " = " + newSolutionOfLevel.movesCount + ", " + Texts.getText("time") + ": " + ((System.currentTimeMillis() - startTimestamp) / 1000f) + " " + Texts.getText("seconds"));
                    } else if (optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.BOXCHANGES_PUSHES) {
                        setInfoText(Texts.getText("foundSolution") + " " + Texts.getText("boxChanges") + " = " + newSolutionOfLevel.boxChanges + ", " + Texts.getText("pushes") + " = " + newSolutionOfLevel.pushesCount + ", " + Texts.getText("time") + ": " + ((System.currentTimeMillis() - startTimestamp) / 1000f) + " " + Texts.getText("seconds"));
                    } else if (optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.PUSHINGSESSIONS_MOVES) {
                        setInfoText(Texts.getText("foundSolution") + " " + Texts.getText("pushingSessions") + " = " + newSolutionOfLevel.pushingSessions + ", " + Texts.getText("moves") + " = " + newSolutionOfLevel.movesCount + ", " + Texts.getText("time") + ": " + ((System.currentTimeMillis() - startTimestamp) / 1000f) + " " + Texts.getText("seconds"));
                    } else if (optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.PUSHINGSESSIONS_PUSHES) {
                        setInfoText(Texts.getText("foundSolution") + " " + Texts.getText("pushingSessions") + " = " + newSolutionOfLevel.pushingSessions + ", " + Texts.getText("moves") + " = " + newSolutionOfLevel.pushesCount + ", " + Texts.getText("time") + ": " + ((System.currentTimeMillis() - startTimestamp) / 1000f) + " " + Texts.getText("seconds"));
                    } else if (optimizationMethodCombobox.getSelectedItem() == OptimizationMethod.MAX_UNTOUCHED_BOXES) {
                        setInfoText(Texts.getText("foundSolution") + " " + Texts.getText("optimizer.untouchedBoxCount", bestFoundSolution.untouchedBoxCount) + ", " + Texts.getText("time") + ": " + ((System.currentTimeMillis() - startTimestamp) / 1000f) + " " + Texts.getText("seconds"));
                    } else {
                        // Main metrics have been optimized. Hence, display the main metrics.
                        setInfoText(Texts.getText("foundSolution") + " " + Texts.getText("moves") + " = " + newSolutionOfLevel.movesCount + ", " + Texts.getText("pushes") + " = " + newSolutionOfLevel.pushesCount + ", " + Texts.getText("time") + ": " + ((System.currentTimeMillis() - startTimestamp) / 1000f) + " " + Texts.getText("seconds"));
                    }
                }
            }

            // The next run is done using the whole solution.
            prefixSolutionMoves = "";
            suffixSolutionMoves = "";

            // Rename the start button to "start optimizer" and set a new action command.
            startButton.setToStart();
            showBestSolutionButton.setEnabled(false);

            // Remove the highlighting of solutions currently being optimized.
            solutionsGUI.setAllSolutionsUncolored();
            solutionsGUI.repaint();

            if (OptimizerAsPlugin.isOptimizerPluginModus) {
                closeOptimizerPlugin();
            }

            if (optimizer != null && optimizer.hasOptimizerEndedNormally()               // optimizer is not stopped by user or by an error
                    && isPushRangeIntervalsOptimizationActivated.isSelected()                   // interval optimization is activated
                    && (int) rangeOfPushesToValue.getValue() < intervalMaxPushesValues          // still a further interval left for optimization
                    && (int) rangeOfPushesToValue.getValue() < pushesLastOptimizedSolution) {   // still a further interval left for optimization
                startOptimizer();
            } else {
                if (isPushRangeIntervalsOptimizationActivated.isSelected() &&
                        isOptimizeCompleteSolutionActivated.isSelected()) {  // set back the whole board, which has been replaced
                    boardDisplay.setBoardToDisplay(board);              // due to the pushes range interval optimization

                    if (bestFoundSolution != null) {
                        setUntouchedPositionsInBoardDisplay(board, bestFoundSolution.getLURD());
                    }
                }
            }
        });
    }

    /**
     * The optimizer has finished or was stopped by the user/Plugin caller => save settings and exit program.
     */
    private void closeOptimizerPlugin() {

        // Save only the needed changes so not to change the other settings for the "normal" JSoko.
        Settings.pluginOptimizerXCoordinate = getX();
        Settings.pluginOptimizerYCoordinate = getY();
        Settings.pluginOptimizerWidth = getWidth();
        Settings.pluginOptimizerHeight = getHeight();
        try {
            Settings.saveSettings();
        } catch (final IOException e) {
            /* do nothing */
        }

        System.exit(0); // in plugin mode the optimizer optimizes one solution and then closes
    }

    /**
     * Creates a new board from the passed solution regarding all pushes from the "fromPush" to the "toPush".
     *
     * @param fromPush the first relevant push of the solution
     * @param toPush   the last relevant push of the solution
     * @param solution the solution to create a new board from
     * @return the created board
     */
    @SuppressWarnings("fallthrough")
    Board getBoardFromSolutionPart(int fromPush, int toPush, Solution solution) {

        // Create a clone of the current board.
        Board helpBoard = board.clone();
        Board newBoard = helpBoard;

        if (fromPush < 0 || fromPush >= solution.pushesCount) {
            fromPush = 0;
        }
        if (toPush > solution.pushesCount || toPush < fromPush) {
            toPush = solution.pushesCount;
        }

        boolean[] isBoxPushedInSolution = new boolean[helpBoard.boxCount];
        int pushesCount = 0;

        int newPlayerPosition = helpBoard.playerPosition;

        // Go through the solution until the "toPush" is reached.
        for (int i = 0; i < solution.lurd.length() && pushesCount < toPush; i++) {

            // If the "fromPush" has been reached, this board is the start for the optimizer.
            if (pushesCount == fromPush && newBoard == helpBoard) {
                newBoard = helpBoard.clone();
            }

            switch (solution.lurd.charAt(i)) {
                case 'U':
                    pushesCount++;
                case 'u':
                    newPlayerPosition = helpBoard.getPosition(newPlayerPosition, UP);
                    break;

                case 'D':
                    pushesCount++;
                case 'd':
                    newPlayerPosition = helpBoard.getPosition(newPlayerPosition, DOWN);
                    break;

                case 'L':
                    pushesCount++;
                case 'l':
                    newPlayerPosition = helpBoard.getPosition(newPlayerPosition, LEFT);
                    break;

                case 'R':
                    pushesCount++;
                case 'r':
                    newPlayerPosition = helpBoard.getPosition(newPlayerPosition, RIGHT);
                    break;
            }

            // If a box is reached, set a flag that the box is pushed in the solution.
            if (helpBoard.isBox(newPlayerPosition) && pushesCount >= fromPush) {
                isBoxPushedInSolution[helpBoard.getBoxNo(newPlayerPosition)] = true;
            }

            // Push a box if necessary.
            if (helpBoard.isBox(newPlayerPosition)) {
                helpBoard.pushBox(newPlayerPosition, 2 * newPlayerPosition - helpBoard.playerPosition);
            }

            // Move the player.
            helpBoard.setPlayerPosition(newPlayerPosition);
        }

        // Remove all goals.
        for (int goalNo = 0; goalNo < newBoard.goalsCount; goalNo++) {
            newBoard.removeGoal(newBoard.getGoalPosition(goalNo));
        }

        // Set a wall at all boxes that don't have been pushed in the relevant part
        // of the solution.
        for (int counter = 0; counter < helpBoard.boxCount; counter++) {

            int boxPosition = helpBoard.boxData.getBoxPosition(counter);

            if (!isBoxPushedInSolution[counter] && replaceUnmovedBoxesWithAWall.isSelected()) {
                newBoard.setWall(boxPosition);
                newBoard.removeBox(boxPosition);
            } else {
                // Set new goals at the box positions.
                newBoard.setGoal(boxPosition);
            }
        }

        // Inform the board that the elements of the board have changed (new walls and removed boxes).
        newBoard.isValid(new StringBuilder());
        newBoard.prepareBoard();

        // Set a wall on all new "outer" squares. Due to boxes that have become walls, the new board may contain
        // empty squares which can't be reached by the player. For a better look, these squares are filled with walls.
        for (int position = board.firstRelevantSquare; position < board.lastRelevantSquare; position++) {
            if (newBoard.isOuterSquareOrWall(position) && !board.isOuterSquareOrWall(position)) {
                newBoard.setWall(position);
            }
        }

        return newBoard;
    }

    /**
     * Returns the part of a solution between two pushes.
     * <p>
     * The new created solution will be exclusive the "fromPush" and inclusive the "toPush".
     *
     * @param solution the solution to get a part from
     * @param fromPush the number of the first relevant push
     * @param toPush   the number of the last  relevant push
     * @return
     */
    private Solution getSolutionRange(Solution solution, int fromPush, int toPush) {
        int startIndex = -1;
        int endIndex = -1;
        int pushesCount = 0;

        if (fromPush < 0) {
            fromPush = 0;
        }
        if (toPush > solution.pushesCount) {
            toPush = solution.pushesCount;
        }
        if (fromPush == solution.pushesCount || fromPush > solution.pushesCount || toPush == 0 || fromPush == toPush || toPush < fromPush) {
            return new Solution("");
        }

        for (int moveNo = 0; moveNo < solution.lurd.length(); moveNo++) {

            // The relevant solution path begins at the first move after the previous push.
            if (pushesCount == fromPush && startIndex == -1) {
                startIndex = moveNo;
            }

            if (Character.isUpperCase(solution.lurd.charAt(moveNo))) {
                pushesCount++;
            }

            if (pushesCount == toPush) {
                endIndex = moveNo;
                break;
            }
        }

        // Create a new solution containing only the moves in the range in the pushes range passed to this method.
        solution = new Solution(solution.lurd.substring(startIndex, endIndex + 1));
        solution.pushesCount = toPush - fromPush;
        solution.movesCount = endIndex - startIndex + 1;

        return solution;
    }

    /**
     * Catches the uncaught exceptions caused by any of the optimizer threads.
     * <p>
     * This method is called before the default method in class {@code ExceptionHandler} is called.
     */
    @Override
    public void uncaughtException(final Thread t, final Throwable e) {

        // Stop the optimizer.
        if (isOptimizerRunning) {
            pushesLastOptimizedSolution = NONE;    // this avoids another interval to be started
            optimizer.stopOptimizer();
        }

        // Only outOfMemory is caught.
        if (e instanceof OutOfMemoryError) {

            // If the user has set the maximal number of box configurations manually, then this value
            // must be decreased => display a message for this.
            // Otherwise, display a general "out of memory" message.
            if (isMaxBoxConfigurationManuallySet.isSelected()) {
                JOptionPane.showMessageDialog(this, Texts.getText("optimizer.boxConfigurationCountTooHigh"), Texts.getText("note"), JOptionPane.WARNING_MESSAGE);
            } else {
                // Inform the user about the error in the log.
                addLogText("\n" + Texts.getText("outOfMemory"));
            }
        }

        if (Debug.isDebugModeActivated) {
            e.printStackTrace();
        }

    }
}

/**
 *  JSoko - A Java implementation of the game of Sokoban
 *  Copyright (c) 2017 by Matthias Meger, Germany
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
package de.sokoban_online.jsoko.utilities;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
 
import javax.swing.JLabel;
import javax.swing.JOptionPane;

import de.sokoban_online.jsoko.resourceHandling.Texts;
 
/**
 * A hyperlink component that is based on JLabel.
 */
public class JHyperlink extends JLabel {
	
    private String url;
    private final String html = "<html><a href=''>%s</a></html>";
     
    public JHyperlink(String text) {       
        this(text, null, null);
    }
     
    public JHyperlink(String text, String url) {
        this(text, url, null);
    }
     
    public void setURL(String url) {
        this.url = url;
    }  
     
    public JHyperlink(String text, String url, String tooltip) {
        super(text);
        this.url = url;
         
        setForeground(Color.BLUE.darker());
                 
        setToolTipText(tooltip);       
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
         
        addMouseListener(new MouseAdapter() {
             
            @Override
            public void mouseEntered(MouseEvent e) {
                setText(String.format(html, text));
            }
             
            @Override
            public void mouseExited(MouseEvent e) {
                setText(text);
            }
             
            @Override
            public void mouseClicked(MouseEvent event) {
                try {                     
                    Desktop.getDesktop().browse(new URI(JHyperlink.this.url));
                } catch (IOException | URISyntaxException e) {
                    JOptionPane.showMessageDialog(JHyperlink.this,
                            "Could not open the hyperlink: " + e.getMessage(),
                            Texts.getText("error"),
                            JOptionPane.ERROR_MESSAGE);
                }              
            }             
        });
         
    }
}


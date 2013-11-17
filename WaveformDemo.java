
package waveformdemo;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.Component;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

import java.awt.BorderLayout;
import javax.swing.border.LineBorder;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;

import java.awt.GraphicsEnvironment;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.Transparency;
import java.awt.geom.Path2D;

import java.io.File;
import java.io.IOException;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.LineUnavailableException;

public class WaveformDemo implements ActionListener {
    
    public static void main(String[] args) {
        WaveformDemo demo = new WaveformDemo();
    }
    
    private static final int DEF_BUFFER_SAMPLE_SZ = 1024;
    
    public enum PlayStat {
        NOFILE, PLAYING, PAUSED, STOPPED;
    }
    
    public interface PlayerRef {
        public Object getLock();
        public PlayStat getStat();
        public File getFile();
        public void playbackEnded();
        public void drawDisplay(float[] samples, int svalid);
    }
    
    public static final Color LIGHT_BLUE = new Color(128, 192, 255);
    public static final Color DARK_BLUE = new Color(0, 0, 127);
    
    private final WaveformDemo thisInstance = this;
    private final Object statLock = new Object();
    
    private JFrame mainFrame = new JFrame("Waveform Demo");
    private JPanel contentPane = new JPanel(new BorderLayout());
    private JLabel fileLabel = new JLabel("No file loaded");
    private DisplayPanel displayPanel = new DisplayPanel();
    private JToolBar playbackTools = new JToolBar();
    
    private ToolsButton bOpen = new ToolsButton("Open");
    private ToolsButton bPlay = new ToolsButton("Play");
    private ToolsButton bPause = new ToolsButton("Pause");
    private ToolsButton bStop = new ToolsButton("Stop");
    private File audioFile;
    private AudioFormat audioFormat;
    
    private volatile PlayStat playStat = PlayStat.NOFILE;
    
    private final PlayerRef thisPlayer = new PlayerRef() {
        @Override public Object getLock() {
            return statLock;
        }
        @Override public PlayStat getStat() {
            return playStat;
        }
        @Override public File getFile() {
            return audioFile;
        }
        @Override public void playbackEnded() {
            synchronized (statLock) {
                playStat = PlayStat.STOPPED;
            }
            displayPanel.reset();
            displayPanel.repaint();
        }
        @Override public void drawDisplay(float[] samples, int svalid) {
            displayPanel.makePath(samples, svalid);
            displayPanel.repaint();
        }
    };
    
    public WaveformDemo() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                mainFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                mainFrame.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent we) {
                        systemExit();
                    }
                });
                
                playbackTools.setFloatable(false);
                playbackTools.add(bOpen);
                playbackTools.add(bPlay);
                playbackTools.add(bPause);
                playbackTools.add(bStop);
                
                bOpen.addActionListener(thisInstance);
                bPlay.addActionListener(thisInstance);
                bPause.addActionListener(thisInstance);
                bStop.addActionListener(thisInstance);
                
                fileLabel.setOpaque(true);
                fileLabel.setBackground(Color.BLACK);
                fileLabel.setForeground(Color.WHITE);
                fileLabel.setHorizontalAlignment(JLabel.CENTER);
                
                playbackTools.setBackground(Color.GRAY);
                playbackTools.setMargin(new Insets(0, 24, 0, 0));
                
                contentPane.add(fileLabel, BorderLayout.NORTH);
                contentPane.add(displayPanel, BorderLayout.CENTER);
                contentPane.add(playbackTools, BorderLayout.SOUTH);
                
                mainFrame.setContentPane(contentPane);
                
                mainFrame.pack();
                mainFrame.setResizable(false);
                mainFrame.setLocationRelativeTo(null);
                
                mainFrame.setVisible(true);
            }
        });
    }
    
    private void systemExit() {
        synchronized (statLock) {
            if (playStat == PlayStat.PLAYING) {
                playStat = PlayStat.STOPPED;
            }
        }
        
        mainFrame.setVisible(false);
        mainFrame.dispose();
        
        System.exit(0);
    }
    
    private void loadAudio() {
        JFileChooser openDiag = new JFileChooser();
        int input = openDiag.showOpenDialog(mainFrame);
        
        if (input == JFileChooser.APPROVE_OPTION) {
            File selected = openDiag.getSelectedFile();
            
            try {
                
                /*
                 * first test to see if format supported
                 * sometimes system will claim to support a format
                 * but throw a LineUnavailableException on SourceDataLine.open
                 * unfortunate
                 * 
                 * typically higher sample rates --
                 * if retrieving a list of DataLine.Info avail supported
                 * some systems return -1 for sample rates indicating 'any'
                 * but evidently untrue, throws exception
                 * 
                 */
                
                AudioFileFormat fmt = AudioSystem.getAudioFileFormat(selected);
                
                audioFile = selected;
                audioFormat = fmt.getFormat();
                fileLabel.setText(audioFile.getName());
                playStat = PlayStat.STOPPED;
                
            } catch (IOException ioe) {
                showError(ioe);
                return;
            } catch (UnsupportedAudioFileException uafe) {
                showError(uafe);
                return;
            }
        }
    }
    
    @Override public void actionPerformed(ActionEvent ae) {
        if (ae.getSource() == bOpen) {
            synchronized (statLock) {
                if (playStat == PlayStat.PLAYING) {
                    playStat = PlayStat.STOPPED;
                }
            }
            
            loadAudio();
            
        } else if (ae.getSource() == bPlay &&
                audioFile != null && playStat != PlayStat.PLAYING) {
            
            synchronized (statLock) {
                switch(playStat) {
                    
                    case STOPPED: {
                        playStat = PlayStat.PLAYING;
                        new PlaybackLoop(thisPlayer).execute();
                        break;
                    }
                        
                    case PAUSED: {
                        playStat = PlayStat.PLAYING;
                        statLock.notifyAll();
                        break;
                    }
                }
            }
            
        } else if (ae.getSource() == bPause &&
                playStat == PlayStat.PLAYING) {
            
            synchronized (statLock) {
                playStat = PlayStat.PAUSED;
            }
            
        } else if (ae.getSource() == bStop &&
                (playStat == PlayStat.PLAYING || playStat == PlayStat.PAUSED)) {
            
            synchronized (statLock) {
                switch (playStat) {
                    
                    case PAUSED: {
                        playStat = PlayStat.STOPPED;
                        statLock.notifyAll();
                        break;
                    }
                        
                    case PLAYING: {
                        playStat = PlayStat.STOPPED;
                        break;
                    }
                }
            }
        }
    }
    
    private static void showError(Throwable t) {
        JOptionPane.showMessageDialog(null,
                "Exception <" + t.getClass().getName() + ">" +
                " with message '" + t.getMessage() + "'.",
                "There was an error",
                JOptionPane.WARNING_MESSAGE);
    }
    
    public static class PlaybackLoop extends SwingWorker<Void, Void> {
        private final PlayerRef playerRef;
        
        public PlaybackLoop(PlayerRef pr) {
            playerRef = pr;
        }
        
        @Override public Void doInBackground() {
            try {
                AudioInputStream in = null;
                SourceDataLine out = null;
                
                try {
                    try {
                        final AudioFileFormat fileFormat =
                                AudioSystem.getAudioFileFormat(playerRef.getFile());
                        final AudioFormat audioFormat = fileFormat.getFormat();
                        
                        in = AudioSystem.getAudioInputStream(playerRef.getFile());
                        out = AudioSystem.getSourceDataLine(audioFormat);
                        
                        int bytesPerSample = audioFormat.getSampleSizeInBits() / 8;
                        
                        float[] samples = new float[DEF_BUFFER_SAMPLE_SZ * audioFormat.getChannels()];
                        byte[] bytes = new byte[samples.length * bytesPerSample];
                        
                        out.open(audioFormat);
                        out.start();
                        
                        /*
                         * feed the output zome zero samples
                         * helps prevent the 'stutter' issue
                         * 
                         */
                        
                        for (int feed = 0; feed < 6; feed++)
                            out.write(bytes, 0, bytes.length);
                        
                        int bread = 0;
                        
                        play_loop: do {
                            while (playerRef.getStat() == PlayStat.PLAYING) {
                                
                                if ((bread = in.read(bytes)) == -1) {
                                    // eof
                                    
                                    break play_loop;
                                }
                                
                                unpack(bytes, samples, bread, audioFormat);
                                window(samples, bread / bytesPerSample, audioFormat);
                                
                                playerRef.drawDisplay(samples, bread / bytesPerSample);
                                
                                out.write(bytes, 0, bread);
                                
                                /*
                                 * some OS do not share threads well
                                 * sleep to attemp assurance the repaint will happen
                                 * 
                                 */
                                
                                try {
                                    Thread.sleep(14);
                                } catch (InterruptedException ie) {}
                            }
                            
                            if (playerRef.getStat() == PlayStat.PAUSED) {
                                out.flush();
                                try {
                                    synchronized (playerRef.getLock()) {
                                        playerRef.getLock().wait(1000);
                                    }
                                } catch (InterruptedException ie) {}
                                continue;
                            } else {
                                break;
                            }
                        } while (true);
                        
                    } catch (UnsupportedAudioFileException uafe) {
                        showError(uafe);
                    } catch (LineUnavailableException lue) {
                        showError(lue);
                    }
                } finally {
                    if (in != null) {
                        in.close();
                    }
                    if (out != null) {
                        out.flush();
                        out.close();
                    }
                }
            } catch (IOException ioe) {
                showError(ioe);
            }
            
            return null;
        }
        
        @Override public void done() {
            playerRef.playbackEnded();
            
            try {
                get();
            } catch (InterruptedException io) {
            } catch (CancellationException ce) {
            } catch (ExecutionException ee) {
                showError(ee.getCause());
            }
        }
    }
    
    public static void unpack(
            byte[] bytes,
            float[] samples,
            int bvalid,
            AudioFormat fmt) {
        
        int bytesPerSample = fmt.getSampleSizeInBits() / 8;
        
        if (bytes == null ||
                (fmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED &&
                fmt.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED)) {
            if (samples == null) {
                
                // return all zeroes
                samples = new float[bvalid / bytesPerSample];
            }
            return;
            
        } else if (samples == null) {
            samples = new float[bytes.length / bytesPerSample];
        }
        
        boolean signed = fmt.getEncoding() == AudioFormat.Encoding.PCM_SIGNED;
        
        /*
         * ignore low bytes if bit depth larger than 16
         * same as quantizing by truncation
         * error is miniscule compared to resolution of the display
         * 
         */
        
        int truncate = bytesPerSample > 2 ? 2 : bytesPerSample;
        int shift = 16 - Math.min(16, fmt.getSampleSizeInBits());
        
        short sample;
        
        for (int i = 0, k = 0, b; i < bvalid; i += bytesPerSample, k++) {
            
            /*
             * form samples by concatenation
             * mask signed bytes with 0xFF
             * avoids sign extension when they are promoted
             * 
             */
            
            if (fmt.isBigEndian()) {
                int off = i + bytesPerSample - 1;
                for (b = 0, sample = 0; b < truncate; b++)
                    sample |= (bytes[off - b] & 0xFF) << (8 * b);
                
            } else {
                for (b = 0, sample = 0; b < truncate; b++)
                    sample |= (bytes[i + b] & 0xFF) << (8 * b);
            }
            
            /*
             * due to quantization to 16 bits shift will most likely always be 0
             * except in case of unsigned 8-bit WAV format
             * 
             */
            
            if (signed)
                samples[k] = sample << shift;
            else
                samples[k] = (sample << shift) - 32768;
        }
    }
    
    public static void window(
            float[] samples,
            int svalid,
            AudioFormat fmt) {
        
        /*
         * most basic window function
         * multiply the window against a sine curve, tapers ends
         * 
         */
        
        int slen = svalid / fmt.getChannels();
        for (int ch = 0, k, i; ch < fmt.getChannels(); ch++) {
            for (i = ch, k = 0; i < svalid; i += fmt.getChannels()) {
                samples[i] *= Math.sin(Math.PI * k++ / (slen - 1));
            }
        }
    }
    
    public class DisplayPanel extends JPanel {
        private final BufferedImage image;
        private final Path2D.Float[] paths = {
            new Path2D.Float(), new Path2D.Float(), new Path2D.Float()
        };
        
        private final Object pathLock = new Object();
        
        {
            Dimension pref = getPreferredSize();
            image = GraphicsEnvironment.getLocalGraphicsEnvironment().
                    getDefaultScreenDevice().getDefaultConfiguration().
                    createCompatibleImage(pref.width, pref.height, Transparency.OPAQUE);
        }
        
        public DisplayPanel() {
            setOpaque(false);
        }
        
        public void reset() {
            Graphics2D g2d = image.createGraphics();
            g2d.setBackground(Color.BLACK);
            g2d.clearRect(0, 0, image.getWidth(), image.getHeight());
            g2d.dispose();
        }
        
        public void makePath(float[] samples, int svalid) {
            if (audioFormat == null) return;
            
            // shuffle
            
            Path2D.Float current = paths[2];
            paths[2] = paths[1];
            paths[1] = paths[0];
            
            // lots of ratios
            
            float avg = 0f;
            float hd2 = getHeight() / 2f;
            
            int i = 0;
            while (i < audioFormat.getChannels() && i < svalid)
                avg += samples[i++] / 32768f;
            
            avg /= audioFormat.getChannels();
            
            current.reset();
            current.moveTo(0, hd2 - avg * hd2);
            
            int fvalid = svalid / audioFormat.getChannels();
            for (int ch, frame = 0; i < svalid; frame++) {
                avg = 0f;
                
                for (ch = 0; ch < audioFormat.getChannels(); ch++)
                    avg += samples[i++] / 32768f;
                
                avg /= audioFormat.getChannels();
                
                current.lineTo(
                        (float)frame / fvalid * image.getWidth(), hd2 - avg * hd2);
            }
            
            paths[0] = current;
                
            Graphics2D g2d = image.createGraphics();
            
            synchronized (pathLock) {
                
                g2d.setBackground(Color.BLACK);
                g2d.clearRect(0, 0, image.getWidth(), image.getHeight());
                
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                        RenderingHints.VALUE_STROKE_PURE);
                
                g2d.setPaint(DARK_BLUE);
                g2d.draw(paths[2]);
                
                g2d.setPaint(LIGHT_BLUE);
                g2d.draw(paths[1]);
                
                g2d.setPaint(Color.WHITE);
                g2d.draw(paths[0]);
            }
            
            g2d.dispose();
        }
        
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            
            synchronized (pathLock) {
                g.drawImage(
                        image, 0, 0, image.getWidth(), image.getHeight(), null);
            }
        }
        
        @Override public Dimension getPreferredSize() {
            return new Dimension(DEF_BUFFER_SAMPLE_SZ / 2, 128);
        }
        @Override public Dimension getMinimumSize() {
            return getPreferredSize();
        }
        @Override public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    }
    
    public static class ToolsButton extends JButton {
        public ToolsButton(String text) {
            super(text);
            
            setOpaque(true);
            setBorderPainted(true);
            setBackground(Color.BLACK);
            setForeground(Color.WHITE);
            
            setBorder(new LineBorder(Color.GRAY) {
                @Override public Insets getBorderInsets(Component c) {
                    return new Insets(1, 4, 1, 4);
                }
                @Override public Insets getBorderInsets(Component c, Insets i) {
                    return getBorderInsets(c);
                }
            });
            
            Font font = getFont();
            setFont(font.deriveFont(font.getSize() - 1f));
            
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent me) {
                    if (me.getButton() == MouseEvent.BUTTON1) {
                        setForeground(LIGHT_BLUE);
                    }
                }
                @Override public void mouseReleased(MouseEvent me) {
                    if (me.getButton() == MouseEvent.BUTTON1) {
                        setForeground(Color.WHITE);
                    }
                }
            });
        }
    }
}

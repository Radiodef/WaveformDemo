
/*
 * Original author: David Staver, 2013
 * 
 * This work is licensed under the Creative Commons
 * Attribution-NonCommercial-ShareAlike 3.0 Unported
 * License. To view a copy of this license, visit
 * http://creativecommons.org/licenses/by-nc-sa/3.0/.
 * 
 */

package waveformdemo;

import java.awt.EventQueue;
import java.awt.Component;
import javax.swing.SwingWorker;
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

public class WaveformDemo
implements ActionListener {
    public static void main(String[] args) {
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                new WaveformDemo();
            }
        });
    }
    
    private static final int DEF_BUFFER_SAMPLE_SZ = 1024;
    
    public static final Color LIGHT_BLUE = new Color(128, 192, 255);
    public static final Color DARK_BLUE = new Color(0, 0, 127);
    
    public enum PlayStat {
        NOFILE, PLAYING, PAUSED, STOPPED
    }
    
    public interface PlayerRef {
        public Object getLock();
        public PlayStat getStat();
        public File getFile();
        public void playbackEnded();
        public void drawDisplay(float[] samples, int svalid);
    }
    
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
    
    private final Object statLock = new Object();
    
    private volatile PlayStat playStat = PlayStat.NOFILE;
    
    private final PlayerRef thisPlayer = new PlayerRef() {
        @Override
        public Object getLock() {
            return statLock;
        }
        
        @Override
        public PlayStat getStat() {
            return playStat;
        }
        
        @Override
        public File getFile() {
            return audioFile;
        }
        
        @Override
        public void playbackEnded() {
            synchronized(statLock) {
                playStat = PlayStat.STOPPED;
            }
            displayPanel.reset();
            displayPanel.repaint();
        }
        
        @Override
        public void drawDisplay(float[] samples, int svalid) {
            displayPanel.makePath(samples, svalid);
            displayPanel.repaint();
        }
    };
    
    public WaveformDemo() {
        assert EventQueue.isDispatchThread();
        
        mainFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        mainFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent we) {
                systemExit();
            }
        });
        
        playbackTools.setFloatable(false);
        playbackTools.add(bOpen);
        playbackTools.add(bPlay);
        playbackTools.add(bPause);
        playbackTools.add(bStop);
        
        bOpen.addActionListener(WaveformDemo.this);
        bPlay.addActionListener(WaveformDemo.this);
        bPause.addActionListener(WaveformDemo.this);
        bStop.addActionListener(WaveformDemo.this);
        
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
    
    private void systemExit() {
        synchronized(statLock) {
            if(playStat == PlayStat.PLAYING) {
                playStat = PlayStat.STOPPED;
            }
        }
        
        mainFrame.setVisible(false);
        mainFrame.dispose();
        
        try {
            /* 
             * helps prevent 'tearing' sound
             * if exit happens while during playback
             * 
             */
            Thread.sleep(250L);
        } catch(InterruptedException ie) {}
        
        System.exit(0);
    }
    
    private void loadAudio() {
        JFileChooser openDiag = new JFileChooser();
        
        if(JFileChooser.APPROVE_OPTION == openDiag.showOpenDialog(mainFrame)) {
            File selected = openDiag.getSelectedFile();
            
            try {
                
                /*
                 * first test to see if format supported.
                 * sometimes the system will claim to support a format
                 * but throw a LineUnavailableException on SourceDataLine.open
                 * 
                 * if retrieving a list of DataLine.Info for available
                 * supported formats some systems return -1 for sample rates
                 * indicating 'any' but evidently can be untrue: throws the exception.
                 * 
                 */
                
                AudioFileFormat fmt = AudioSystem.getAudioFileFormat(selected);
                
                audioFile = selected;
                audioFormat = fmt.getFormat();
                fileLabel.setText(audioFile.getName());
                playStat = PlayStat.STOPPED;
                
            } catch(IOException ioe) {
                showError(ioe);
            } catch(UnsupportedAudioFileException uafe) {
                showError(uafe);
            }
        }
    }
    
    @Override
    public void actionPerformed(ActionEvent ae) {
        Object source = ae.getSource();
        
        if(source == bOpen) {
            synchronized(statLock) {
                if(playStat == PlayStat.PLAYING) {
                    playStat = PlayStat.STOPPED;
                }
            }
            
            loadAudio();
            
        } else if(source == bPlay
                && audioFile != null
                && playStat != PlayStat.PLAYING) {
            
            synchronized(statLock) {
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
            
        } else if(source == bPause
                && playStat == PlayStat.PLAYING) {
            
            synchronized(statLock) {
                playStat = PlayStat.PAUSED;
            }
            
        } else if(source == bStop
                && (playStat == PlayStat.PLAYING || playStat == PlayStat.PAUSED)) {
            
            synchronized(statLock) {
                switch(playStat) {
                    
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
            JOptionPane.WARNING_MESSAGE
        );
    }
    
    public static class PlaybackLoop
    extends SwingWorker<Void, Void> {
        
        private final PlayerRef playerRef;
        
        public PlaybackLoop(PlayerRef pr) {
            playerRef = pr;
        }
        
        @Override
        public Void doInBackground() {
            try {
                AudioInputStream in = null;
                SourceDataLine out = null;
                
                try {
                    try {
                        final AudioFileFormat fileFormat =
                            AudioSystem.getAudioFileFormat(playerRef.getFile()
                        );
                        final AudioFormat audioFormat = fileFormat.getFormat();
                        
                        in = AudioSystem.getAudioInputStream(playerRef.getFile());
                        out = AudioSystem.getSourceDataLine(audioFormat);
                        
                        int normalBytes = audioFormat.getSampleSizeInBits() + 7 >> 3;
                        
                        float[] samples = new float[DEF_BUFFER_SAMPLE_SZ * audioFormat.getChannels()];
                        long[] transfer = new long[samples.length];
                        byte[] bytes = new byte[samples.length * normalBytes];
                        
                        out.open(audioFormat);
                        out.start();
                        
                        /*
                         * feed the output some zero samples
                         * helps prevent the 'stutter' issue.
                         * 
                         */
                        
                        for(int feed = 0; feed < 6; feed++) {
                            out.write(bytes, 0, bytes.length);
                        }
                        
                        int bread;
                        
                        play_loop: do {
                            while(playerRef.getStat() == PlayStat.PLAYING) {
                                
                                if((bread = in.read(bytes)) == -1) {
                                    
                                    break play_loop; // eof
                                }
                                
                                samples = unpack(bytes, transfer, samples, bread, audioFormat);
                                samples = window(samples, bread / normalBytes, audioFormat);
                                
                                playerRef.drawDisplay(samples, bread / normalBytes);
                                
                                out.write(bytes, 0, bread);
                                
                                /*
                                 * some OS do not share threads well.
                                 * sleep to attemp assurance the repaint will happen.
                                 * this is a bit of a hack.
                                 * 
                                 */
                                
                                try {
                                    Thread.sleep(8L);
                                } catch(InterruptedException ie) {}
                            }
                            
                            if(playerRef.getStat() == PlayStat.PAUSED) {
                                out.flush();
                                try {
                                    synchronized(playerRef.getLock()) {
                                        playerRef.getLock().wait(1000L);
                                    }
                                } catch(InterruptedException ie) {}
                                continue;
                            } else {
                                break;
                            }
                        } while(true);
                        
                    } catch(UnsupportedAudioFileException uafe) {
                        showError(uafe);
                    } catch(LineUnavailableException lue) {
                        showError(lue);
                    }
                } finally {
                    if(in != null) {
                        in.close();
                    }
                    if(out != null) {
                        out.flush();
                        out.close();
                    }
                }
            } catch(IOException ioe) {
                showError(ioe);
            }
            
            return (Void)null;
        }
        
        @Override
        public void done() {
            playerRef.playbackEnded();
            
            try {
                get();
            } catch(InterruptedException io) {
            } catch(CancellationException ce) {
            } catch(ExecutionException ee) {
                showError(ee.getCause());
            }
        }
    }
    
    public static float[] unpack(
        byte[] bytes,
        long[] transfer,
        float[] samples,
        int bvalid,
        AudioFormat fmt
    ) {
        if(fmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                && fmt.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
            
            return samples;
        }
        
        final int bitsPerSample = fmt.getSampleSizeInBits();
        final int bytesPerSample = bitsPerSample / 8;
        
        /*
         * some formats allow for bit depths in non-multiples of 8.
         * they will, however, typically pad so the samples are stored
         * that way. AIFF is one of these formats.
         * 
         * so the expression:
         * 
         *  a = b + 7 >> 3;
         * 
         * computes a division of 8 rounding up.
         * 
         * this is basically equivalent to:
         * 
         *  a = (int)Math.ceil(b / 8.0);
         * 
         */
        
        final int normalBytes = bitsPerSample + 7 >> 3;
        
        /*
         * not the most DRY way to do this but it's a bit more efficient.
         * otherwise there would either have to be 4 separate methods for
         * each combination of endianness/signedness or do it all in one
         * loop and check the format for each sample.
         * 
         * of course this method is almost certainly going to get run 1000+
         * times, enough for the JIT to pick up on the branches never changing
         * but still.
         * 
         * a helper array (transfer) allows the logic to be split up
         * but without being too repetetive.
         * 
         * here there are two loops converting bytes to raw long samples.
         * integral primitives in Java get sign extended when they are
         * promoted to a larger type so the & 0xffL mask keeps them intact.
         * 
         */
        
        if(fmt.isBigEndian()) {
            for(int i = 0, k = 0, b; i < bvalid; i += normalBytes, k++) {
                transfer[k] = 0L;
                
                int least = i + normalBytes - 1;
                for(b = 0; b < normalBytes; b++) {
                    transfer[k] |= (bytes[least - b] & 0xffL) << (8 * b);
                }
            }
        } else {
            for(int i = 0, k = 0, b; i < bvalid; i += normalBytes, k++) {
                transfer[k] = 0L;
                
                for(b = 0; b < normalBytes; b++) {
                    transfer[k] |= (bytes[i + b] & 0xffL) << (8 * b);
                }
            }
        }
        
        final long fullScale = (long)Math.pow(2.0, bitsPerSample - 1);
        
        /*
         * the OR is not quite enough to convert,
         * the signage needs to be corrected.
         * 
         */
        
        if(fmt.getEncoding() == AudioFormat.Encoding.PCM_SIGNED) {
            
            /*
             * if the samples were signed, they must be
             * extended to the 64-bit long.
             * 
             * so first check if the sign bit was set
             * and if so, extend it.
             * 
             * as an example, imagining these were 4-bit samples originally
             * and the destination is 8-bit, a mask can be constructed
             * with -1 (all bits 1) and a left shift:
             * 
             *     11111111
             *  <<  (4 - 1)
             *  ===========
             *     11111000
             * 
             * (except the destination is 64-bit and the original
             * bit depth from the file could be anything.)
             * 
             * then supposing we have a hypothetical sample -5
             * that ought to be negative, an AND can be used to check it:
             * 
             *    00001011
             *  & 11111000
             *  ==========
             *    00001000
             * 
             * and an OR can be used to extend it:
             * 
             *    00001011
             *  | 11111000
             *  ==========
             *    11111011
             * 
             */
            
            final long signMask = -1L << bitsPerSample - 1L;
            
            for(int i = 0; i < transfer.length; i++) {
                if((transfer[i] & signMask) != 0L) {
                    transfer[i] |= signMask;
                }
            }
        } else {
            
            /*
             * unsigned samples are easier since they
             * will be read correctly in to the long.
             * 
             * so just sign them:
             * subtract 2^(bits - 1) so the center is 0.
             * 
             */
            
            for(int i = 0; i < transfer.length; i++) {
                transfer[i] -= fullScale;
            }
        }
        
        /* finally normalize to range of -1.0f to 1.0f */
        
        for(int i = 0; i < transfer.length; i++) {
            samples[i] = (float)transfer[i] / (float)fullScale;
        }
        
        return samples;
    }
    
    public static float[] window(
        float[] samples,
        int svalid,
        AudioFormat fmt
    ) {
        /*
         * most basic window function
         * multiply the window against a sine curve, tapers ends
         * 
         * nested loops here show a paradigm for processing multi-channel formats
         * the interleaved samples can be processed "in place"
         * inner loop processes individual channels using an offset
         * 
         */
        
        int channels = fmt.getChannels();
        int slen = svalid / channels;
        
        for(int ch = 0, k, i; ch < channels; ch++) {
            for(i = ch, k = 0; i < svalid; i += channels) {
                samples[i] *= Math.sin(Math.PI * k++ / (slen - 1));
            }
        }
        
        return samples;
    }
    
    public class DisplayPanel
    extends JPanel {
        
        private final BufferedImage image;
        
        private final Path2D.Float[] paths = {
            new Path2D.Float(), new Path2D.Float(), new Path2D.Float()
        };
        
        private final Object pathLock = new Object();
        
        {
            Dimension pref = getPreferredSize();
            
            image = (
                GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration()
                .createCompatibleImage(
                    pref.width, pref.height, Transparency.OPAQUE
                )
            );
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
            if(audioFormat == null)
                return;
            
            /* shuffle */
            
            Path2D.Float current = paths[2];
            paths[2] = paths[1];
            paths[1] = paths[0];
            
            /* lots of ratios */
            
            float avg = 0f;
            float hd2 = getHeight() / 2f;
            
            final int channels = audioFormat.getChannels();
            
            /* 
             * have to do a special op for the
             * 0th samples because moveTo.
             * 
             */
            
            int i = 0;
            while(i < channels && i < svalid) {
                avg += samples[i++];
            }
            
            avg /= channels;
            
            current.reset();
            current.moveTo(0, hd2 - avg * hd2);
            
            int fvalid = svalid / channels;
            for(int ch, frame = 0; i < svalid; frame++) {
                avg = 0f;
                
                /* average the channels for each frame. */
                
                for(ch = 0; ch < channels; ch++) {
                    avg += samples[i++];
                }
                
                avg /= channels;
                
                current.lineTo(
                    (float)frame / fvalid * image.getWidth(), hd2 - avg * hd2
                );
            }
            
            paths[0] = current;
                
            Graphics2D g2d = image.createGraphics();
            
            synchronized(pathLock) {
                g2d.setBackground(Color.BLACK);
                g2d.clearRect(0, 0, image.getWidth(), image.getHeight());
                
                g2d.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                );
                g2d.setRenderingHint(
                    RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE
                );
                
                g2d.setPaint(DARK_BLUE);
                g2d.draw(paths[2]);
                
                g2d.setPaint(LIGHT_BLUE);
                g2d.draw(paths[1]);
                
                g2d.setPaint(Color.WHITE);
                g2d.draw(paths[0]);
            }
            
            g2d.dispose();
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            
            synchronized(pathLock) {
                g.drawImage(
                    image, 0, 0, image.getWidth(), image.getHeight(), null
                );
            }
        }
        
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(DEF_BUFFER_SAMPLE_SZ / 2, 128);
        }
        
        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }
        
        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    }
    
    public static class ToolsButton
    extends JButton {
        public ToolsButton(String text) {
            super(text);
            
            setOpaque(true);
            setBorderPainted(true);
            setBackground(Color.BLACK);
            setForeground(Color.WHITE);
            
            setBorder(new LineBorder(Color.GRAY) {
                @Override
                public Insets getBorderInsets(Component c) {
                    return new Insets(1, 4, 1, 4);
                }
                @Override
                public Insets getBorderInsets(Component c, Insets i) {
                    return getBorderInsets(c);
                }
            });
            
            Font font = getFont();
            setFont(font.deriveFont(font.getSize() - 1f));
            
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent me) {
                    if(me.getButton() == MouseEvent.BUTTON1) {
                        setForeground(LIGHT_BLUE);
                    }
                }
                @Override
                public void mouseReleased(MouseEvent me) {
                    if(me.getButton() == MouseEvent.BUTTON1) {
                        setForeground(Color.WHITE);
                    }
                }
            });
        }
    }
}

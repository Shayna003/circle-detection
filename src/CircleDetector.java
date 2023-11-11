import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * useful resources: https://en.wikipedia.org/wiki/Kernel_(image_processing)
 * https://en.wikipedia.org/wiki/Circle_Hough_Transform
 */
public class CircleDetector extends JFrame
{
  public static void main(String[] args)
  {
    EventQueue.invokeLater(() ->
    {
      JFrame frame = new CircleDetector();
      frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
      frame.pack();
      frame.setLocationRelativeTo(null);
      frame.setVisible(true);
    });
  }

  BufferedImage image;
  BufferedImage processed_image; // for circle detection
  JFileChooser fileChooser;
  ImageComponent imageComponent;
  ArrayList<Ellipse2D> circles = new ArrayList<>();
  Color circle_color = Color.CYAN;
  int stroke_size = 5;

  JMenuItem detect;
  JMenuItem blur;
  JMenuItem edgeDetect;
  JMenuItem negative;

  @Override
  public Dimension getPreferredSize()
  {
    return new Dimension(1200, 800);
  }

  class ImageComponent extends JComponent
  {
    @Override
    public Dimension getPreferredSize()
    {
      if (image == null) return super.getPreferredSize();
      else return new Dimension(image.getWidth(), image.getHeight());
    }

    @Override
    public void paintComponent(Graphics g)
    {
      Graphics2D g2 = (Graphics2D) g;
      if (image != null) g2.drawImage(image, 0, 0, null);

      g2.setColor(circle_color);
      g2.setStroke(new BasicStroke(stroke_size));
      for (Ellipse2D circle : circles)
      {
        g2.draw(circle);
      }
    }
  }
  public CircleDetector()
  {
    setTitle("Circle Detector");

    imageComponent = new ImageComponent();
    add(new JScrollPane(imageComponent));

    JMenu file = new JMenu("File");
    JMenuItem open = new JMenuItem("Open...");
    open.addActionListener(event -> openFile());
    file.add(open);

    JMenuItem exit = new JMenuItem("Exit");
    exit.addActionListener(event -> System.exit(0));
    file.add(exit);

    JMenuItem edit = new JMenu("Edit");

    detect = new JMenuItem("Detect Circles");
    detect.addActionListener(event ->
    {
      detect_circles();
    });
    edit.add(detect);

    blur = new JMenuItem("Gaussian Blur");
    blur.addActionListener(event ->
    {
      blur(true);
    });
    edit.add(blur);

    edgeDetect = new JMenuItem("Edge Detect");
    edgeDetect.addActionListener(event ->
    {
      edge_detect(true);
    });
    edit.add(edgeDetect);

    negative = new JMenuItem("Negative");
    negative.addActionListener(event ->
    {
      negative(true);
    });
    edit.add(negative);

    JMenuBar menuBar = new JMenuBar();
    menuBar.add(file);
    menuBar.add(edit);
    setJMenuBar(menuBar);

    fileChooser = new JFileChooser(".");
    String[] extensions = ImageIO.getReaderFileSuffixes();
    fileChooser.setFileFilter(new FileNameExtensionFilter("Image Files", extensions));
  }

  void detect_circles()
  {
    //circles.clear();
    boolean applyDirectly = true; // should be false
    blur(applyDirectly);
    edge_detect(applyDirectly);
    negative(applyDirectly);
    System.out.println(processed_image);
    ArrayList<CircleHit> hits = houghTransform(applyDirectly);
    Collections.sort(hits, Collections.reverseOrder());

    // only take values that are above threshold
    if (hits.size() > 0)
    {
      for (int i = 0; i < hits.size(); i++)
      {
        CircleHit hit = hits.get(i);

        circles.add(new Ellipse2D.Double(hit.x - hit.r, hit.y - hit.r, hit.r * 2, hit.r * 2));
      }
      repaint();
    }
  }

  void blur(boolean applyDirectly)
  {
    /*      float weight = 1.0f / 9.0f;
      float[] elements = new float[9];
      for (int i = 0; i < 9; i++)
        elements[i] = weight;*/
    float[] elements = { 1.0f, 2.0f, 1.0f, 2.0f, 4.f, 2.0f, 1.0f, 2.0f, 1.0f };
    float multiplier = 1.0f / 16.0f;
    for (int i = 0; i < elements.length; i++)
    {
      elements[i] *= multiplier;
    }
    convolve(elements, applyDirectly);
  }

  void edge_detect(boolean applyDirectly)
  {
    float[] elements = { 0.0f, -1.0f, 0.0f, -1.0f, 4.f, -1.0f, 0.0f, -1.0f, 0.0f };
    convolve(elements, applyDirectly);
  }

  void negative(boolean applyDirectly)
  {
    short[] negative_values = new short[256];
    for (int i = 0; i < 256; i++)
      negative_values[i] = (short) (255 - i);
    ShortLookupTable table = new ShortLookupTable(0, negative_values);
    LookupOp op = new LookupOp(table, null);
    filter(op, applyDirectly);
  }

  public void openFile()
  {
    if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
    {
      try
      {
        Image img = ImageIO.read(fileChooser.getSelectedFile());
        image = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB);
        image.getGraphics().drawImage(img, 0, 0, null);
        pack();
        revalidate();
        repaint();
      }
      catch (IOException e)
      {
        e.printStackTrace();
        JOptionPane.showMessageDialog(this, e);
      }
    }
  }

  void filter(BufferedImageOp op, boolean applyDirectly)
  {
    if (image == null) return;
    if (applyDirectly)
      image = op.filter(image, null);
    else
      processed_image = op.filter(image, null);
    //processed_image = op.filter(img, null);
    repaint();
  }

  void convolve(float[] elements, boolean applyDirectly)
  {
    var kernel = new Kernel(3, 3, elements);
    var op = new ConvolveOp(kernel);
    filter(op, applyDirectly);
  }

  int[][][] accumulator;
  int min_radius = 10;
  int edge_threshold = 250;// pixel grey value (avg of rgb) must be below (darker) than this value to be considered in hough transform
  int accumulator_threshold = 10; // threshold for value in accumulator matrix to be considered as a center point of a circle
  // a point has to be local maxima and above threshold to be considered a circle

  int grey_value(int rgb)
  {
    int r = (rgb & 0xff000000) >>> 24;
    int g = (rgb & 0x00ff0000) >>> 16;
    int b = (rgb & 0x0000ff00) >>> 8;
    //int a = rgb & 0x000000ff;

    return((int)((r+g+b)/3.0));
  }
  ArrayList<CircleHit> houghTransform(boolean applyDirectly)
  {
    System.out.println("houghtransform");
    BufferedImage img = applyDirectly ? image : processed_image;
    int img_width = img.getWidth();
    int img_height = img.getHeight();

    int max_radius = Math.min(img_width, img_height);
    accumulator = new int[img_width][img_height][max_radius];

    int a;
    int b;

    for (int x = 0; x < img_width; x++)
    {
      for (int y = 0; y < img_height; y++)
      {
        //System.out.println("x=" + x + ", y=" + y + ",grey: " + grey_value(img.getRGB(x, y)));
        if (grey_value(img.getRGB(x, y)) < edge_threshold)//(img.getRGB(x, y) & 0x000000ff) != 0) // pixel not white
        {
          //&& y + r <= img_height && x + r <= img_width
          for (int r = min_radius; r < max_radius; r++)
          {
            // optimized. use 5 random points instead of all 360 points.
/*            for (int i = 0; i < 5; i++)
            {
              b = (int) (y - r * Math.sin(t * Math.PI / 180));  //polar coordinate for center (convert to radians)
              a = (int) (x - r * Math.cos(t * Math.PI / 180)); //polar coordinate for center (convert to radians)
              if (a >= 0 && a < img_width)
              {
                if (b >= 0 && b < img_height)
                {
                  accumulator[a][b][r] += 1;
                }
              }
            }*/
            for (int t = 0; t <= 360; t += 6)
            {
              a = (int) Math.round(x - r * Math.cos(t * Math.PI / 180)); //polar coordinate for center (convert to radians)
              b = (int) Math.round(y - r * Math.sin(t * Math.PI / 180));  //polar coordinate for center (convert to radians)

              if (a >= 0 && a < img_width)
              {
                if (b >= 0 && b < img_height)
                {
                  accumulator[a][b][r] += 1;
                }
              }
            }
          }
        }
      }
    }

    // normalise and find hough transform image
    // now normalise to 255 and put in format for a pixel array
    int max = 0;
    int max_r = -1;
    int max_x = -1;
    int max_y = -1;

    // Find max acc value
    for (int x = 0; x < img_width; x++)
    {
      for (int y = 0; y < img_height; y++)
      {
        for (int r = min_radius; r < max_radius; r++)
        {
          if (accumulator[x][y][r] > max)
          {
            max = accumulator[x][y][r];
            max_r = r;
            max_x = x;
            max_y = y;
          }
        }
      }
    }

    ArrayList<CircleHit> hits = new ArrayList<>(); // have to find hits with highest values
    System.out.println("Max :" + max + ", max_r: " + max_r + ", max_x:" + max_x + ", max_y: " + max_y);
    hits.add(new CircleHit(max_x, max_y, max_r, max));


    // Normalise all the values
    int value;
    int[][] hough_image = new int[img_width][img_height];
    for (int r = min_radius; r < max_radius; r += 10)
    {
      for (int x = 0; x < img_width; x++)
      {
        for (int y = 0; y < img_height; y++)
        {
          value = (int) (255 - ((double) accumulator[x][y][r] / (double) max) * 255.0);
          hough_image[x][y] = value;
          //accumulator[x][y][r] = 0xff000000 | (value << 16 | value << 8 | value);
        }
      }
      writeImage(hough_image, "./hough_" + r + ".png");
    }
    //findMaxima();



    System.out.println("phase 1 complete");
    //int[][] houghSpace = new int[img_width][img_height];

/*    for (int x = 0; x < img_width; x++)
    {
      for (int y = 0; y < img_height; y++)
      {
        System.out.printf("%5d |", accumulator[x][y][2]);
        //for (int r = 0; r < max_radius; r++)
      }
      System.out.println();
    }*/
    //System.out.println(Arrays.toString(accumulator));

    // find local maxima in accumulator array


    /*for (int x = 0; x < img_width; x++)
    {
      for (int y = 0; y < img_height; y++)
      {
        for (int r = min_radius; r < max_radius; r++)
        {
          if (accumulator[x][y][r] < accumulator_threshold) break;
          boolean has_left = x > 0;
          boolean has_right = x < img_width-1;
          boolean has_top = y > 0;
          boolean has_bottom = y < img_height-1;

          if (has_left)
          {
            if (accumulator[x-1][y][r] > accumulator[x][y][r]) break;
            if (has_top)
            {
              if (accumulator[x-1][y-1][r] > accumulator[x][y][r]) break;
            }
            if (has_bottom)
            {
              if (accumulator[x-1][y+1][r] > accumulator[x][y][r]) break;
            }
          }

          if (has_right)
          {
            if (accumulator[x+1][y][r] > accumulator[x][y][r]) break;
            if (has_top)
            {
              if (accumulator[x+1][y-1][r] > accumulator[x][y][r]) break;
            }
            if (has_bottom)
            {
              if (accumulator[x+1][y+1][r] > accumulator[x][y][r]) break;
            }
          }

          if (has_top)
          {
            if (accumulator[x][y-1][r] > accumulator[x][y][r]) break;
          }
          if (has_bottom)
          {
            if (accumulator[x][y+1][r] > accumulator[x][y][r]) break;
          }

          if (accumulator[x][y][r] < accumulator_threshold)
          {
            //System.out.println("smaller than 10: " + accumulator[x][y][r]);
            break;
          }

          hits.add(new CircleHit(x, y, r, accumulator[x][y][r]));
          //circles.add(new Ellipse2D.Double(x - r-1, y - r-1, (r+1) * 2, (r+1) * 2));
          //System.out.println("new circle detected: " + "x=" + x + ", y=" + y + ", r=" + r);
        }
      }
    }*/
    System.out.println("phase 2 complete");
    return hits;
  }

  public static void writeImage(int[][] imgArray, String outFile)
  {
    try
    {
      BufferedImage img = new BufferedImage(imgArray.length, imgArray[0].length, BufferedImage.TYPE_INT_ARGB);
      for(int i = 0; i< imgArray.length; i++){
        for(int j = 0; j<imgArray[0].length; j++){
          img.setRGB(i, j, new Color(255,255,255, imgArray[i][j]).getRGB());
        }
      }
      File outX = new File(outFile);
      ImageIO.write(img, "png", outX);
    }
    catch (IOException e)
    {
      e.printStackTrace();
    }
  }

  class CircleHit implements Comparable<CircleHit>
  {
    int x;
    int y;
    int r;
    int val;
    public CircleHit(int x, int y, int r, int val)
    {
      this.x = x;
      this.y = y;
      this.r = r;
      this.val = val;
    }

    @Override
    public int compareTo(CircleHit other)
    {
      return this.val - other.val;
    }
  }
}
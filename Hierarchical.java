import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.io.*;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import com.jogamp.opengl.*;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.FPSAnimator;
import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.nativewindow.ScalableSurface;

import javax.swing.JFrame;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import java.util.ArrayList;

class Hierarchical extends JFrame implements GLEventListener, KeyListener, MouseListener, MouseMotionListener, ActionListener {

    /* This defines the objModel class, which takes care
	 * of loading a triangular mesh from an obj file,
	 * estimating per vertex average normal,
	 * and displaying the mesh.
     */
    class objModel {

        public FloatBuffer vertexBuffer;
        public IntBuffer faceBuffer;
        public FloatBuffer normalBuffer;
        public Point3f center;
        public int num_verts;		// number of vertices
        public int num_faces;		// number of triangle faces

        public void Draw() {
            vertexBuffer.rewind();
            normalBuffer.rewind();
            faceBuffer.rewind();
            gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glEnableClientState(GL2.GL_NORMAL_ARRAY);

            gl.glVertexPointer(3, GL2.GL_FLOAT, 0, vertexBuffer);
            gl.glNormalPointer(GL2.GL_FLOAT, 0, normalBuffer);

            gl.glDrawElements(GL2.GL_TRIANGLES, num_faces * 3, GL2.GL_UNSIGNED_INT, faceBuffer);

            gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
            gl.glDisableClientState(GL2.GL_NORMAL_ARRAY);
        }

        public objModel(String filename) {
            /* load a triangular mesh model from a .obj file */
            BufferedReader in = null;
            try {
                in = new BufferedReader(new FileReader(filename));
            } catch (IOException e) {
                System.out.println("Error reading from file " + filename);
                System.exit(0);
            }

            center = new Point3f();
            float x, y, z;
            int v1, v2, v3;
            float minx, miny, minz;
            float maxx, maxy, maxz;
            float bbx, bby, bbz;
            minx = miny = minz = 10000.f;
            maxx = maxy = maxz = -10000.f;

            String line;
            String[] tokens;
            ArrayList<Point3f> input_verts = new ArrayList<Point3f>();
            ArrayList<Integer> input_faces = new ArrayList<Integer>();
            ArrayList<Vector3f> input_norms = new ArrayList<Vector3f>();
            try {
                while ((line = in.readLine()) != null) {
                    if (line.length() == 0) {
                        continue;
                    }
                    switch (line.charAt(0)) {
                        case 'v':
                            tokens = line.split("[ ]+");
                            x = Float.valueOf(tokens[1]);
                            y = Float.valueOf(tokens[2]);
                            z = Float.valueOf(tokens[3]);
                            minx = Math.min(minx, x);
                            miny = Math.min(miny, y);
                            minz = Math.min(minz, z);
                            maxx = Math.max(maxx, x);
                            maxy = Math.max(maxy, y);
                            maxz = Math.max(maxz, z);
                            input_verts.add(new Point3f(x, y, z));
                            center.add(new Point3f(x, y, z));
                            break;
                        case 'f':
                            tokens = line.split("[ ]+");
                            v1 = Integer.valueOf(tokens[1]) - 1;
                            v2 = Integer.valueOf(tokens[2]) - 1;
                            v3 = Integer.valueOf(tokens[3]) - 1;
                            input_faces.add(v1);
                            input_faces.add(v2);
                            input_faces.add(v3);
                            break;
                        default:
                            continue;
                    }
                }
                in.close();
            } catch (IOException e) {
                System.out.println("Unhandled error while reading input file.");
            }

            System.out.println("Read " + input_verts.size()
                    + " vertices and " + input_faces.size() + " faces.");

            center.scale(1.f / (float) input_verts.size());

            bbx = maxx - minx;
            bby = maxy - miny;
            bbz = maxz - minz;
            float bbmax = Math.max(bbx, Math.max(bby, bbz));

            for (Point3f p : input_verts) {

                p.x = (p.x - center.x) / bbmax;
                p.y = (p.y - center.y) / bbmax;
                p.z = (p.z - center.z) / bbmax;
            }
            center.x = center.y = center.z = 0.f;

            /* estimate per vertex average normal */
            int i;
            for (i = 0; i < input_verts.size(); i++) {
                input_norms.add(new Vector3f());
            }

            Vector3f e1 = new Vector3f();
            Vector3f e2 = new Vector3f();
            Vector3f tn = new Vector3f();
            for (i = 0; i < input_faces.size(); i += 3) {
                v1 = input_faces.get(i + 0);
                v2 = input_faces.get(i + 1);
                v3 = input_faces.get(i + 2);

                e1.sub(input_verts.get(v2), input_verts.get(v1));
                e2.sub(input_verts.get(v3), input_verts.get(v1));
                tn.cross(e1, e2);
                input_norms.get(v1).add(tn);

                e1.sub(input_verts.get(v3), input_verts.get(v2));
                e2.sub(input_verts.get(v1), input_verts.get(v2));
                tn.cross(e1, e2);
                input_norms.get(v2).add(tn);

                e1.sub(input_verts.get(v1), input_verts.get(v3));
                e2.sub(input_verts.get(v2), input_verts.get(v3));
                tn.cross(e1, e2);
                input_norms.get(v3).add(tn);
            }

            /* convert to buffers to improve display speed */
            for (i = 0; i < input_verts.size(); i++) {
                input_norms.get(i).normalize();
            }

            vertexBuffer = Buffers.newDirectFloatBuffer(input_verts.size() * 3);
            normalBuffer = Buffers.newDirectFloatBuffer(input_verts.size() * 3);
            faceBuffer = Buffers.newDirectIntBuffer(input_faces.size());

            for (i = 0; i < input_verts.size(); i++) {
                vertexBuffer.put(input_verts.get(i).x);
                vertexBuffer.put(input_verts.get(i).y);
                vertexBuffer.put(input_verts.get(i).z);
                normalBuffer.put(input_norms.get(i).x);
                normalBuffer.put(input_norms.get(i).y);
                normalBuffer.put(input_norms.get(i).z);
            }

            for (i = 0; i < input_faces.size(); i++) {
                faceBuffer.put(input_faces.get(i));
            }
            num_verts = input_verts.size();
            num_faces = input_faces.size() / 3;
        }
    }

    public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE:
            case KeyEvent.VK_Q:
                System.exit(0);
                break;
            case 'r':
            case 'R':
                initViewParameters();
                break;
            case 'w':
            case 'W':
                wireframe = !wireframe;
                break;
            case 'b':
            case 'B':
                cullface = !cullface;
                break;
            case 'f':
            case 'F':
                flatshade = !flatshade;
                break;
            case 'a':
            case 'A':
                if (animator.isAnimating()) {
                    animator.stop();
                } else {
                    animator.start();
                }
                break;
            case '+':
            case '=':
                animation_speed *= 1.2f;
                break;
            case '-':
            case '_':
                animation_speed /= 1.2;
                break;
            default:
                break;
        }
        canvas.display();
    }

    /* GL, display, model transformation, and mouse control variables */
    private final GLCanvas canvas;
    private GL2 gl;
    private final GLU glu = new GLU();
    private FPSAnimator animator;

    private int winW = 1600, winH = 1600;
    private boolean wireframe = false;
    private boolean cullface = true;
    private boolean flatshade = false;

    private float xpos = 0, ypos = 0, zpos = 0;
    private float centerx, centery, centerz;
    private float roth = 0, rotv = 0;
    private float znear, zfar;
    private int mouseX, mouseY, mouseButton;
    private float motionSpeed, rotateSpeed;
    private float animation_speed = 1.0f;

    /* === YOUR WORK HERE === */
 /* Define more models you need for constructing your scene */
    private objModel bunny = new objModel("bunny.obj");
    private objModel armadillo = new objModel("armadillo.obj");
    private objModel bird = new objModel("bird.obj");
    private objModel male = new objModel("male.obj");
    private objModel plant = new objModel("plant.obj");
    private objModel plant2 = new objModel("plant.obj");
    private objModel plant3 = new objModel("plant.obj");
    private objModel tree_conical = new objModel("tree_conical.obj");
    private objModel tree_aspen = new objModel("tree_aspen.obj");
    private objModel tree_conical2 = new objModel("tree_conical.obj");


    private float example_rotateT = 0.f;
    /* Here you should give a conservative estimate of the scene's bounding box
	 * so that the initViewParameters function can calculate proper
	 * transformation parameters to display the initial scene.
	 * If these are not set correctly, the objects may disappear on start.
     */
    private float xmin = -4.5f, ymin = -4.5f, zmin = -4.5f;
    private float xmax = 4.5f, ymax = 4.5f, zmax = 4.5f;

    public void display(GLAutoDrawable drawable) {
        gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);

        gl.glPolygonMode(GL2.GL_FRONT_AND_BACK, wireframe ? GL2.GL_LINE : GL2.GL_FILL);
        gl.glShadeModel(flatshade ? GL2.GL_FLAT : GL2.GL_SMOOTH);
        if (cullface) {
            gl.glEnable(GL2.GL_CULL_FACE);
        } else {
            gl.glDisable(GL2.GL_CULL_FACE);
        }

        gl.glLoadIdentity();
        gl.glDisable( GL2.GL_COLOR_MATERIAL );

        /* this is the transformation of the entire scene */
        gl.glTranslatef(-xpos, -ypos, -zpos);
        gl.glTranslatef(centerx, centery, centerz);
        gl.glRotatef(360.f - roth, 0, 1.0f, 0);
        gl.glRotatef(rotv, 1.0f, 0, 0);
        gl.glTranslatef(-centerx, -centery, -centerz);

        /* === YOUR WORK HERE === */
        //LEVEL 1 - armadillo at the center disrupting peaceful forest
        gl.glPushMatrix();	// push the current matrix to stack

        //armadillo
        gl.glRotatef(example_rotateT * 3, 0 ,1, 0);
        color(new float[]{1.0f,0f,0f,1f});
        armadillo.Draw();

        //LEVEL 2 - bunny around some plants
        //bunny
        gl.glPopMatrix();
        gl.glPushMatrix();
        gl.glScalef(0.5f, 0.5f, 0.5f);
        gl.glRotatef(example_rotateT, 0, 1, 0);
        gl.glTranslatef(-5.0f, -0.75f, 0f);
        gl.glRotatef(-example_rotateT * 2, 0, 1, 0);
        gl.glPushMatrix();
        color(new float[]{1.0f, 1.0f, 0.998f});
        bunny.Draw();

        //LEVEL 3 - plant1 rotating around bunny
        //plant
        gl.glPopMatrix();
        gl.glPushMatrix();
        gl.glRotatef(example_rotateT, 0, 1, 0);
        gl.glTranslatef(-0.65f, 0f, 0f);
        color(new float[]{0.486f,0.998f,0f,1f});
        plant.Draw();

        //LEVEL 4 - plant2 rotating around bunny
        //plant2
        gl.glPopMatrix();
        gl.glPushMatrix();
        gl.glRotatef(-example_rotateT * 2, 0, 1, 0);
        gl.glTranslatef(-0.9f, 0f, 0f);
        color(new float[]{0.502f,0.502f,0f,1f});
        plant2.Draw();

        //LEVEL 5 - plant3 rotating around bunny
        //plant3
        gl.glPopMatrix();
        gl.glRotatef(example_rotateT * 3, 0, 1, 0);
        gl.glTranslatef(-1.15f, 0f, 0f);
        color(new float[]{0.486f,0.998f,0f,1f});
        plant3.Draw();

        //LEVEL 2 - male around some trees and a bird
        //man
        gl.glPopMatrix();
        gl.glRotatef(example_rotateT, 0, 1, 0);
        gl.glTranslatef(3.5f, 0.08f, 0f);
        gl.glRotatef(-example_rotateT * 2, 0, 1, 0);
        gl.glPushMatrix();
        color(new float[]{0.961f, 0.871f, 0.702f, 1.0f});
        male.Draw();

        //Level 3 - bird flying around male
        gl.glPopMatrix();
        gl.glPushMatrix();
        gl.glScalef(0.2f, 0.2f, 0.2f);
        gl.glRotatef(example_rotateT * 4, 0, 1, 0);
        gl.glTranslatef(1.0f, 0.6f, 0f);
        gl.glRotatef(90.f, 0, 1, 0);
        color(new float[]{0.255f, 0.412f, 0.882f, 1.0f});
        bird.Draw();

        //LEVEL 4 - first tree rotating around male
        //tree_conical
        gl.glPopMatrix();
        gl.glPushMatrix();
        gl.glRotatef(example_rotateT, 0, 1, 0);
        gl.glTranslatef(0.6f, -0.2f, 0f);
        color(new float[]{0f, 0.502f, 0f});
        tree_conical.Draw();


        //LEVEL 5 - second tree rotating around male
        //tree_aspen
        gl.glPopMatrix();
        gl.glPushMatrix();
        gl.glRotatef(-example_rotateT * 2, 0, 1, 0);
        gl.glTranslatef(1.1f, 0.1f, 0f);
        color(new float[]{0.502f, 0.502f, 0f});
        tree_aspen.Draw();

        //LEVEL 6 - third tree rotating around male
        gl.glPopMatrix();
        gl.glRotatef(example_rotateT * 3, 0, 1, 0);
        gl.glTranslatef(1.5f, -0.2f, 0f);
        color(new float[]{0f, 0.502f, 0f});
        tree_conical2.Draw();

        gl.glPopMatrix();

        /* increment example_rotateT */
        if (animator.isAnimating()) {
            example_rotateT += 1.0f * animation_speed;
        }
    }

    //reduce amount of code and initializers
    public void color(float[] mat_ambient) {
        gl.glMaterialfv(GL2.GL_FRONT, GL2.GL_AMBIENT, mat_ambient, 0);
        gl.glMaterialfv(GL2.GL_FRONT, GL2.GL_DIFFUSE, new float[]{0.3f,0.3f,0.3f,1f}, 0);
        gl.glMaterialfv(GL2.GL_FRONT, GL2.GL_SPECULAR, new float[]{1f,1f,.8f,1f}, 0);
        gl.glMaterialfv(GL2.GL_FRONT, GL2.GL_SHININESS, new float[]{128}, 0);
        gl.glMaterialfv(GL2.GL_FRONT, GL2.GL_EMISSION, new float[]{0f,0f,0f,1f}, 0);
    }

    public Hierarchical() {
        super("Assignment 2 -- Hierarchical Modeling");
        final GLProfile glprofile = GLProfile.getMaxFixedFunc(true);
        GLCapabilities glcapabilities = new GLCapabilities(glprofile);
        canvas = new GLCanvas(glcapabilities);        
        canvas.setSurfaceScale(new float[] { ScalableSurface.IDENTITY_PIXELSCALE, ScalableSurface.IDENTITY_PIXELSCALE }); // potential fix for Retina Displays
        canvas.addGLEventListener(this);
        canvas.addKeyListener(this);
        canvas.addMouseListener(this);
        canvas.addMouseMotionListener(this);
        animator = new FPSAnimator(canvas, 30);	// create a 30 fps animator
        getContentPane().add(canvas);
        setSize(winW, winH);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setVisible(true);
        animator.start();
        canvas.requestFocus();
    }

    public static void main(String[] args) {

        new Hierarchical();
    }

    public void init(GLAutoDrawable drawable) {
        gl = drawable.getGL().getGL2();

        initViewParameters();
        gl.glClearColor(.1f, .1f, .1f, 1f);
        gl.glClearDepth(1.0f);

        // white light at the eye
        float light0_position[] = {0, 0, 1, 0};
        float light0_diffuse[] = {1, 1, 1, 1};
        float light0_specular[] = {1, 1, 1, 1};
        gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_POSITION, light0_position, 0);
        gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_DIFFUSE, light0_diffuse, 0);
        gl.glLightfv(GL2.GL_LIGHT0, GL2.GL_SPECULAR, light0_specular, 0);

        //red light
        float light1_position[] = {-.1f, .1f, 0, 0};
        float light1_diffuse[] = {.6f, .05f, .05f, 1};
        float light1_specular[] = {.6f, .05f, .05f, 1};
        gl.glLightfv(GL2.GL_LIGHT1, GL2.GL_POSITION, light1_position, 0);
        gl.glLightfv(GL2.GL_LIGHT1, GL2.GL_DIFFUSE, light1_diffuse, 0);
        gl.glLightfv(GL2.GL_LIGHT1, GL2.GL_SPECULAR, light1_specular, 0);

        //blue light
        float light2_position[] = {.1f, .1f, 0, 0};
        float light2_diffuse[] = {.05f, .05f, .6f, 1};
        float light2_specular[] = {.05f, .05f, .6f, 1};
        gl.glLightfv(GL2.GL_LIGHT2, GL2.GL_POSITION, light2_position, 0);
        gl.glLightfv(GL2.GL_LIGHT2, GL2.GL_DIFFUSE, light2_diffuse, 0);
        gl.glLightfv(GL2.GL_LIGHT2, GL2.GL_SPECULAR, light2_specular, 0);

        float lmodel_ambient[] = {1.0f, 1.0f, 1.0f, 1.0f};
        gl.glLightModelfv(GL2.GL_LIGHT_MODEL_AMBIENT, lmodel_ambient, 0);
        gl.glLightModeli(GL2.GL_LIGHT_MODEL_TWO_SIDE, 1);

        gl.glEnable(GL2.GL_NORMALIZE);
        gl.glEnable(GL2.GL_LIGHTING);
        gl.glEnable(GL2.GL_LIGHT0);
        gl.glEnable(GL2.GL_LIGHT1);
        gl.glEnable(GL2.GL_LIGHT2);

        gl.glEnable(GL2.GL_DEPTH_TEST);
        gl.glDepthFunc(GL2.GL_LESS);
        gl.glHint(GL2.GL_PERSPECTIVE_CORRECTION_HINT, GL2.GL_NICEST);
        gl.glCullFace(GL2.GL_BACK);
        gl.glEnable(GL2.GL_CULL_FACE);
        gl.glShadeModel(GL2.GL_SMOOTH);
    }

    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        winW = width;
        winH = height;

        gl.glViewport(0, 0, width, height);
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glLoadIdentity();
        glu.gluPerspective(45.f, (float) width / (float) height, znear, zfar);
        gl.glMatrixMode(GL2.GL_MODELVIEW);
    }

    public void mousePressed(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
        mouseButton = e.getButton();
        canvas.display();
    }

    public void mouseReleased(MouseEvent e) {
        mouseButton = MouseEvent.NOBUTTON;
        canvas.display();
    }

    public void mouseDragged(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        if (mouseButton == MouseEvent.BUTTON3) {
            zpos -= (y - mouseY) * motionSpeed;
            mouseX = x;
            mouseY = y;
            canvas.display();
        } else if (mouseButton == MouseEvent.BUTTON2) {
            xpos -= (x - mouseX) * motionSpeed;
            ypos += (y - mouseY) * motionSpeed;
            mouseX = x;
            mouseY = y;
            canvas.display();
        } else if (mouseButton == MouseEvent.BUTTON1) {
            roth -= (x - mouseX) * rotateSpeed;
            rotv += (y - mouseY) * rotateSpeed;
            mouseX = x;
            mouseY = y;
            canvas.display();
        }
    }

    /* computes optimal transformation parameters for OpenGL rendering.
	 * this is based on an estimate of the scene's bounding box
     */
    void initViewParameters() {
        roth = rotv = 0;

        float ball_r = (float) Math.sqrt((xmax - xmin) * (xmax - xmin)
                + (ymax - ymin) * (ymax - ymin)
                + (zmax - zmin) * (zmax - zmin)) * 0.707f;

        centerx = (xmax + xmin) / 2.f;
        centery = (ymax + ymin) / 2.f;
        centerz = (zmax + zmin) / 2.f;
        xpos = centerx;
        ypos = centery;
        zpos = ball_r / (float) Math.sin(45.f * Math.PI / 180.f) + centerz;

        znear = 0.01f;
        zfar = 1000.f;

        motionSpeed = 0.002f * ball_r;
        rotateSpeed = 0.1f;

    }

    // these event functions are not used for this assignment
    public void dispose(GLAutoDrawable glautodrawable) {
    }

    public void displayChanged(GLAutoDrawable drawable, boolean modeChanged, boolean deviceChanged) {
    }

    public void keyTyped(KeyEvent e) {
    }

    public void keyReleased(KeyEvent e) {
    }

    public void mouseMoved(MouseEvent e) {
    }

    public void actionPerformed(ActionEvent e) {
    }

    public void mouseClicked(MouseEvent e) {
    }

    public void mouseEntered(MouseEvent e) {
    }

    public void mouseExited(MouseEvent e) {
    }
}

package com.basintelligence.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Synthesises grayscale images of a machined metal surface, with or without a defect, for demos and tests.
 * Real inspections use uploaded photos; this keeps the demo self-contained.
 */
public final class SampleImageGenerator {

    public enum Kind {
        GOOD, CRACK, SCRATCH, PITTING
    }

    private static final int SIZE = 256;

    public SampleImageGenerator() {
        OpenCvRuntime.ensureLoaded();
    }

    public byte[] generatePng(Kind kind, long seed) {
        Random random = new Random(seed);
        Mat surface = surface(random);
        try {
            switch (kind) {
                case CRACK -> crack(surface, random);
                case SCRATCH -> scratch(surface, random);
                case PITTING -> pits(surface, random);
                case GOOD -> {
                    // clean surface
                }
            }
            Imgproc.GaussianBlur(surface, surface, new Size(3, 3), 0);
            MatOfByte png = new MatOfByte();
            Imgcodecs.imencode(".png", surface, png);
            return png.toArray();
        } finally {
            surface.release();
        }
    }

    /** Brushed-metal look: smooth lighting gradient plus fine sensor noise. */
    private static Mat surface(Random random) {
        Mat f = new Mat(SIZE, SIZE, CvType.CV_32F);
        double base = 140 + random.nextInt(30);
        double gx = random.nextGaussian() * 0.08;
        double gy = random.nextGaussian() * 0.08;
        float[] row = new float[SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                row[x] = (float) (base + gx * x + gy * y + 6 * Math.sin((x + y) / 60.0));
            }
            f.put(y, 0, row);
        }
        Mat noise = new Mat(SIZE, SIZE, CvType.CV_32F);
        Core.randn(noise, 0, 5);
        Core.add(f, noise, f);
        Mat out = new Mat();
        f.convertTo(out, CvType.CV_8U);
        f.release();
        noise.release();
        return out;
    }

    private static void crack(Mat img, Random random) {
        List<Point> points = new ArrayList<>();
        Point p = new Point(40 + random.nextInt(60), 40 + random.nextInt(60));
        points.add(p);
        double angle = random.nextDouble() * Math.PI / 2;
        for (int i = 0; i < 9; i++) {
            angle += random.nextGaussian() * 0.5;
            p = new Point(clamp(p.x + 16 * Math.cos(angle)), clamp(p.y + 16 * Math.sin(angle)));
            points.add(p);
        }
        MatOfPoint polyline = new MatOfPoint(points.toArray(Point[]::new));
        Imgproc.polylines(img, List.of(polyline), false, new Scalar(55), 2, Imgproc.LINE_AA);
        polyline.release();
    }

    private static void scratch(Mat img, Random random) {
        Point a = new Point(20 + random.nextInt(40), 30 + random.nextInt(190));
        Point b = new Point(200 + random.nextInt(40), 30 + random.nextInt(190));
        Imgproc.line(img, a, b, new Scalar(225), 2, Imgproc.LINE_AA);
    }

    private static void pits(Mat img, Random random) {
        int count = 4 + random.nextInt(5);
        for (int i = 0; i < count; i++) {
            Point c = new Point(30 + random.nextInt(196), 30 + random.nextInt(196));
            Imgproc.circle(img, c, 3 + random.nextInt(4), new Scalar(45), -1, Imgproc.LINE_AA);
        }
    }

    private static double clamp(double v) {
        return Math.clamp(v, 8, SIZE - 8);
    }
}

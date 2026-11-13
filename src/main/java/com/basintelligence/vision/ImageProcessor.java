package com.basintelligence.vision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * OpenCV stages around the neural network: decoding and validation, tensor preparation, classical defect
 * localisation (blur, Laplacian, adaptive threshold, morphology, contours) and annotation.
 */
public final class ImageProcessor {

    public static final int MODEL_INPUT = 128;
    private static final int MAX_DIMENSION = 8192;
    private static final int MAX_REGIONS = 5;

    public ImageProcessor() {
        OpenCvRuntime.ensureLoaded();
    }

    /**
     * Decodes to 8-bit grayscale. Throws if the bytes are not a PNG, JPEG or BMP image. The format is identified by its
     * leading bytes and the dimensions are read from the header before decoding, so a small file that declares a huge
     * image (a decompression bomb) is refused without allocating the pixels.
     */
    public Mat decodeGray(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The uploaded image is empty");
        }
        var type = com.basintelligence.common.FileSignatures.detect(bytes);
        if (type != com.basintelligence.common.FileSignatures.Type.PNG && type != com.basintelligence.common.FileSignatures.Type.JPEG
                && type != com.basintelligence.common.FileSignatures.Type.BMP) {
            throw new IllegalArgumentException("File is not a readable PNG/JPEG/BMP image");
        }
        int[] declared = headerSize(bytes);
        checkDimensions(declared[0], declared[1]);
        Mat gray;
        try {
            gray = Imgcodecs.imdecode(new MatOfByte(bytes), Imgcodecs.IMREAD_GRAYSCALE);
        } catch (org.opencv.core.CvException e) {
            throw new IllegalArgumentException("File is not a readable PNG/JPEG/BMP image");
        }
        if (gray.empty()) {
            throw new IllegalArgumentException("File is not a readable PNG/JPEG/BMP image");
        }
        if (gray.cols() > MAX_DIMENSION || gray.rows() > MAX_DIMENSION || gray.cols() < 32 || gray.rows() < 32) {
            gray.release();
            checkDimensions(gray.cols(), gray.rows());
        }
        return gray;
    }

    private static void checkDimensions(int width, int height) {
        if (width > MAX_DIMENSION || height > MAX_DIMENSION || width < 32 || height < 32) {
            throw new IllegalArgumentException("Image must be between 32 and " + MAX_DIMENSION + " pixels per side");
        }
    }

    /** Width and height from the image header only (no pixel decoding). */
    private static int[] headerSize(byte[] bytes) {
        try (var in = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var readers = javax.imageio.ImageIO.getImageReaders(in);
            if (in != null && readers.hasNext()) {
                var reader = readers.next();
                try {
                    reader.setInput(in, true, true);
                    return new int[] {reader.getWidth(0), reader.getHeight(0)};
                } finally {
                    reader.dispose();
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            // fall through: unreadable header
        }
        throw new IllegalArgumentException("File is not a readable PNG/JPEG/BMP image");
    }

    /** Resizes to the model input and scales to [0, 1], row-major NCHW with N = C = 1. */
    public float[] toTensor(Mat gray) {
        Mat resized = new Mat();
        Mat scaled = new Mat();
        try {
            Imgproc.resize(gray, resized, new Size(MODEL_INPUT, MODEL_INPUT), 0, 0, Imgproc.INTER_AREA);
            resized.convertTo(scaled, CvType.CV_32F, 1.0 / 255.0);
            float[] data = new float[MODEL_INPUT * MODEL_INPUT];
            scaled.get(0, 0, data);
            return data;
        } finally {
            resized.release();
            scaled.release();
        }
    }

    /** Candidate defect regions in original-image coordinates, largest first. */
    public List<Rect> localiseDefects(Mat gray) {
        Mat blurred = new Mat();
        Mat lap = new Mat();
        Mat edges = new Mat();
        Mat binary = new Mat();
        Mat closed = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            Imgproc.Laplacian(blurred, lap, CvType.CV_16S, 3);
            Core.convertScaleAbs(lap, edges);
            MatOfDouble mean = new MatOfDouble();
            MatOfDouble std = new MatOfDouble();
            Core.meanStdDev(edges, mean, std);
            double threshold = Math.max(18, mean.get(0, 0)[0] + 6 * std.get(0, 0)[0]);
            Imgproc.threshold(edges, binary, threshold, 255, Imgproc.THRESH_BINARY);
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE,
                    Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(9, 9)));
            Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            double minArea = gray.rows() * gray.cols() * 0.0004;
            return contours.stream()
                    .map(Imgproc::boundingRect)
                    .filter(r -> r.area() >= minArea)
                    .sorted(Comparator.comparingDouble(Rect::area).reversed())
                    .limit(MAX_REGIONS)
                    .map(r -> pad(r, 6, gray.cols(), gray.rows()))
                    .toList();
        } finally {
            contours.forEach(Mat::release);
            for (Mat m : List.of(blurred, lap, edges, binary, closed, hierarchy)) {
                m.release();
            }
        }
    }

    /** Draws the regions and verdict on a colour copy and returns PNG bytes. */
    public byte[] annotate(Mat gray, List<Rect> regions, String caption, boolean defect) {
        Mat color = new Mat();
        MatOfByte png = new MatOfByte();
        try {
            Imgproc.cvtColor(gray, color, Imgproc.COLOR_GRAY2BGR);
            Scalar box = new Scalar(40, 40, 230);
            for (Rect r : regions) {
                Imgproc.rectangle(color, r.tl(), r.br(), box, Math.max(2, gray.cols() / 200));
            }
            double scale = Math.max(0.5, gray.cols() / 600.0);
            Scalar text = defect ? box : new Scalar(60, 170, 60);
            Imgproc.rectangle(color, new Point(0, 0), new Point(gray.cols(), 28 * scale), new Scalar(255, 255, 255), -1);
            Imgproc.putText(color, caption, new Point(6, 20 * scale), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6 * scale, text,
                    Math.max(1, (int) Math.round(scale * 1.5)));
            Imgcodecs.imencode(".png", color, png);
            return png.toArray();
        } finally {
            color.release();
            png.release();
        }
    }

    private static Rect pad(Rect r, int padding, int width, int height) {
        int x = Math.max(0, r.x - padding);
        int y = Math.max(0, r.y - padding);
        int w = Math.min(width - x, r.width + 2 * padding);
        int h = Math.min(height - y, r.height + 2 * padding);
        return new Rect(x, y, w, h);
    }
}

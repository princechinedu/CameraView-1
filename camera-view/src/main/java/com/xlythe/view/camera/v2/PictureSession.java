package com.xlythe.view.camera.v2;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.xlythe.view.camera.CameraView;
import com.xlythe.view.camera.Exif;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static com.xlythe.view.camera.ICameraModule.DEBUG;
import static com.xlythe.view.camera.ICameraModule.TAG;

@TargetApi(21)
class PictureSession extends PreviewSession {
    /**
     * Supports {@link ImageFormat#JPEG} and {@link ImageFormat#YUV_420_888}. You can support
     * larger sizes with YUV_420_888, at the cost of speed.
     */
    private static final int IMAGE_FORMAT_DEFAULT = ImageFormat.JPEG;
    private static final int IMAGE_FORMAT_MAX = ImageFormat.YUV_420_888;

    private static final int MAX_PROCESSING_THREADS = 131;
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(MAX_PROCESSING_THREADS);

    private final PictureSurface mPictureSurface;

    PictureSession(Camera2Module camera2Module) {
        super(camera2Module);
        mPictureSurface = new PictureSurface(camera2Module, getPreviewSurface());
    }

    @Override
    public void initialize(@NonNull StreamConfigurationMap map) throws CameraAccessException {
        super.initialize(map);
        mPictureSurface.initialize(map);
    }

    @NonNull
    @Override
    public List<Surface> getSurfaces() {
        List<Surface> surfaces = super.getSurfaces();
        surfaces.add(mPictureSurface.getSurface());
        return surfaces;
    }

    void takePicture(@NonNull File file, @NonNull CameraDevice device, @NonNull CameraCaptureSession session) {
        mPictureSurface.initializePicture(file);
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if (hasFlash()) {
                switch (getFlash()) {
                    case AUTO:
                        // TODO: This doesn't work :(
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                        break;
                    case ON:
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
                        break;
                    case OFF:
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                        break;
                }
            }
            builder.addTarget(mPictureSurface.getSurface());
            if (mMeteringRectangle != null) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mMeteringRectangle});
            }
            if (mCropRegion != null) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
            }
            session.capture(builder.build(), null /* callback */, getBackgroundHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException | NullPointerException e) {
            // Crashes if the Camera is interacted with while still loading
            Log.e(TAG, "Failed to create capture request", e);
            onImageFailed();
        }
    }

    private static class ImageSaver extends AsyncTask<Void, Void, Void> {
        private final Context mContext;

        // The image that was captured
        private final Image mImage;

        // The orientation of the image
        private final int mOrientation;

        // If true, the picture taken is reversed and needs to be flipped.
        // Typical with front facing cameras.
        private final boolean mIsReversed;

        // The file to save the image to
        private final File mFile;

        ImageSaver(Context context, Image image, int orientation, boolean reversed, File file) {
            mContext = context.getApplicationContext();
            mImage = image;
            mOrientation = orientation;
            mIsReversed = reversed;
            mFile = file;
        }

        @WorkerThread
        @Override
        protected Void doInBackground(Void... params) {
            // Finally, we save the file to disk
            byte[] bytes = getBytes();
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);

                Exif exif = new Exif(mFile);
                exif.attachTimestamp();
                exif.rotate(mOrientation);
                if (mIsReversed) {
                    exif.flipHorizontally();
                }
                Location location = SessionImpl.CameraSurface.getLocation(mContext);
                if (location != null) {
                    exif.attachLocation(location);
                }
                exif.save();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write the file", e);
            } finally {
                mImage.close();
                if (output != null) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to close the output stream", e);
                    }
                }
            }
            return null;
        }

        private byte[] getBytes() {
            return toByteArray(mImage);
        }

        private static byte[] toByteArray(Image image) {
            byte[] data = null;
            if (image.getFormat() == ImageFormat.JPEG) {
                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                data = new byte[buffer.capacity()];
                buffer.get(data);
                return data;
            } else if (image.getFormat() == ImageFormat.YUV_420_888) {
                data = NV21toJPEG(
                        YUV_420_888toNV21(image),
                        image.getWidth(), image.getHeight());
            } else {
                Log.w(TAG, "Unrecognized image format: " + image.getFormat());
            }
            return data;
        }

        /**
         * Converts from YUV_420_888 to NV21. This involves stripping the padding from the Y plane
         * and interleaving the U and V planes into a single byte array.
         */
        private static byte[] YUV_420_888toNV21(final Image image) {
            final Image.Plane yPlane = image.getPlanes()[0];
            final Image.Plane uPlane = image.getPlanes()[1];
            final Image.Plane vPlane = image.getPlanes()[2];

            final ByteBuffer yBuffer = yPlane.getBuffer();
            final ByteBuffer uBuffer = uPlane.getBuffer();
            final ByteBuffer vBuffer = vPlane.getBuffer();

            final int ySize = yBuffer.remaining();
            final int uSize = uBuffer.remaining();
            final int vSize = vBuffer.remaining();

            final int chromaHeight = image.getHeight() / 2;
            final int chromaWidth = image.getWidth() / 2;
            final int chromaGap = uPlane.getRowStride() - (chromaWidth * uPlane.getPixelStride());

            if (DEBUG) {
                Log.d(TAG, String.format("Image{width=%d, height=%d}",
                        image.getWidth(), image.getHeight()));
                Log.d(TAG, String.format("yPlane{size=%d, pixelStride=%d, rowStride=%d}",
                        ySize, yPlane.getPixelStride(), yPlane.getRowStride()));
                Log.d(TAG, String.format("uPlane{size=%d, pixelStride=%d, rowStride=%d}",
                        uSize, uPlane.getPixelStride(), uPlane.getRowStride()));
                Log.d(TAG, String.format("vPlane{size=%d, pixelStride=%d, rowStride=%d}",
                        vSize, vPlane.getPixelStride(), vPlane.getRowStride()));
                Log.d(TAG, String.format("chromaHeight=%d", chromaHeight));
                Log.d(TAG, String.format("chromaWidth=%d", chromaWidth));
                Log.d(TAG, String.format("chromaGap=%d", chromaGap));
            }

            final byte[] nv21 = new byte[(int) (1.5 * image.getWidth() * image.getHeight())];

            final int maxYThreads = 1;
            final int maxUThreads, maxVThreads;
            maxUThreads = maxVThreads = Math.max(1, (MAX_PROCESSING_THREADS - maxYThreads) / 2);
            final CountDownLatch barrier = new CountDownLatch(maxYThreads + maxUThreads + maxVThreads);
            Log.d(TAG, String.format("maxYThreads=%d", maxYThreads));
            Log.d(TAG, String.format("maxUThreads=%d", maxUThreads));
            Log.d(TAG, String.format("maxVThreads=%d", maxVThreads));

            long start = System.currentTimeMillis();

            // Y plane
            sExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    // Add the full y buffer to the array. If rowStride > 1, some padding may be skipped.
                    int position = 0;
                    for (int row = 0; row < image.getHeight(); row++) {
                        yBuffer.get(nv21, position, image.getWidth());
                        position += image.getWidth();
                        yBuffer.position(Math.min(ySize, yBuffer.position() - image.getWidth() + yPlane.getRowStride()));
                    }
                    barrier.countDown();
                }
            });
            final int uvPositionOffset = image.getWidth() * image.getHeight();

            // U plane
            final AtomicInteger asdf = new AtomicInteger(0);
            for (int i = 0; i < maxUThreads; i++) {
                // Each thread will iterate over this may rows
                final int rowsPerIteration = chromaHeight / maxUThreads;

                // The thread will begin at this row
                final int initialRow = i * rowsPerIteration;

                // And end at this row
                final int lastRow = (i + 1) * rowsPerIteration;

                // Filling up the NV21 array starting at this position
                final int initialPosition = uvPositionOffset + (i * chromaWidth * 2 * rowsPerIteration);

                // Using this buffer
                final ByteBuffer buffer = uBuffer.duplicate();

                if (DEBUG) {
                    Log.d(TAG, String.format("rowsPerIteration=%d, initialRow=%d, lastRow=%d, initialPosition=%d",
                            rowsPerIteration, initialRow, lastRow, initialPosition));
                }

                sExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        long start = System.currentTimeMillis();
                        // Interleave the u and v frames, filling up the rest of the buffer
                        try {
                            int position = initialPosition;
                            for (int row = initialRow; row < lastRow; row++) {
                                for (int col = 0; col < chromaWidth; col++) {
                                    asdf.getAndIncrement();
                                    position++;
                                    buffer.get(nv21, position++, 1);
                                    buffer.position(buffer.position() - 1 + uPlane.getPixelStride());
                                }
                                buffer.position(buffer.position() + chromaGap);
                            }
                            Log.d(TAG, "last pos: " + position);
                        } catch (Exception e) {
                            // no-op, cheaper than checking with Math.min
                        }
                        long end = System.currentTimeMillis();
                        Log.d(TAG, "time: " + (end - start));
                        barrier.countDown();
                    }
                });
            }

            // V plane
            for (int i = 0; i < maxVThreads; i++) {
                // Each thread will iterate over this may rows
                final int rowsPerIteration = chromaHeight / maxVThreads;

                // The thread will begin at this row
                final int initialRow = i * rowsPerIteration;

                // And end at this row
                final int lastRow = (i + 1) * rowsPerIteration;

                // Filling up the NV21 array starting at this position
                final int initialPosition = uvPositionOffset + (i * chromaWidth * 2 * rowsPerIteration);

                // Using this buffer
                final ByteBuffer buffer = vBuffer.duplicate();

                if (DEBUG) {
                    Log.d(TAG, String.format("rowsPerIteration=%d, initialRow=%d, lastRow=%d, initialPosition=%d",
                            rowsPerIteration, initialRow, lastRow, initialPosition));
                }

                sExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // Interleave the u and v frames, filling up the rest of the buffer
                        try {
                            int position = initialPosition;
                            for (int row = initialRow; row < lastRow; row++) {
                                for (int col = 0; col < chromaWidth; col++) {
                                    buffer.get(nv21, position++, 1);
                                    position++;
                                    buffer.position(buffer.position() - 1 + vPlane.getPixelStride());
                                }
                                buffer.position(buffer.position() + chromaGap);
                            }
                        } catch (Exception e) {
                            // no-op, cheaper than checking with Math.min
                        }
                        barrier.countDown();
                    }
                });
            }

            try {
                barrier.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.d(TAG, "operations: " + asdf.get());

            Log.d(TAG, "YUV conversion took " + (System.currentTimeMillis()-start) + " millis");

            if (DEBUG) {
                Log.d(TAG, String.format("nv21{size=%d}", nv21.length));
            }

            return nv21;
        }

        private static byte[] NV21toJPEG(byte[] nv21, int width, int height) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            yuv.compressToJpeg(new Rect(0, 0, width, height), 100, out);
            return out.toByteArray();
        }
    }

    private static final class PictureSurface extends CameraSurface {
        private List<Size> getSizes(StreamConfigurationMap map) {
            Size[] sizes = map.getOutputSizes(getImageFormat(getQuality()));

            // Special case for high resolution images (assuming, of course, quality was set to high)
            if (Build.VERSION.SDK_INT >= 23) {
                Size[] highResSizes = map.getHighResolutionOutputSizes(getImageFormat(getQuality()));
                if (highResSizes != null) {
                    sizes = concat(sizes, highResSizes);
                }
            }

            return Arrays.asList(sizes);
        }

        private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(final ImageReader reader) {
                mCameraView.getBackgroundHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (mFile != null) {
                                final File file = mFile;
                                new ImageSaver(
                                        mCameraView.getContext(),
                                        reader.acquireLatestImage(),
                                        mCameraView.getRelativeCameraOrientation(),
                                        isUsingFrontFacingCamera(),
                                        mFile) {
                                    @UiThread
                                    @Override
                                    protected void onPostExecute(Void aVoid) {
                                        showImageConfirmation(file);
                                    }
                                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                                mFile = null;
                            } else {
                                Log.w(TAG, "OnImageAvailable called but no file to write to");
                            }
                        } catch (IllegalStateException e) {
                            Log.e(TAG, "Failed to save image", e);
                        }
                    }
                });
            }
        };

        private final CameraSurface mPreviewSurface;
        private ImageReader mImageReader;
        private File mFile;

        PictureSurface(Camera2Module camera2Module, CameraSurface previewSurface) {
            super(camera2Module);
            mPreviewSurface = previewSurface;
        }

        @Override
        void initialize(StreamConfigurationMap map) {
            if (DEBUG) Log.d(TAG, "Initializing PictureSession");
            super.initialize(chooseSize(getSizes(map), mPreviewSurface.mSize));
            mImageReader = ImageReader.newInstance(getWidth(), getHeight(), getImageFormat(getQuality()), 1 /* maxImages */);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mCameraView.getBackgroundHandler());
        }

        void initializePicture(File file) {
            mFile = file;
            if (mFile.exists()) {
                Log.w(TAG, "File already exists. Deleting.");
                mFile.delete();
            }
        }

        @Override
        Surface getSurface() {
            return mImageReader.getSurface();
        }

        @Override
        void close() {
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        }
    }

    private static int getImageFormat(CameraView.Quality quality) {
        switch (quality) {
            case MAX:
                return IMAGE_FORMAT_MAX;
            default:
                return IMAGE_FORMAT_DEFAULT;
        }
    }

    private static boolean isRaw(int imageFormat) {
        switch (imageFormat) {
            case ImageFormat.YUV_420_888:
                return true;
            default:
                return false;
        }
    }
}

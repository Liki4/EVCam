package com.kooo.evcam.playback;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Manages cropped thumbnails for Lynk&Co 07 panoramic video/photo playback.
 * Stores thumbnails in a dedicated directory; generates on first access (lazy), then loads from file.
 * Glide is used to load the saved file (and its own cache applies).
 */
public final class ThumbnailStorageManager {

    private static final String THUMB_DIR_NAME = "evcam_thumbnails";
    private static final String SOURCE_TXT = "source.txt";
    private static final int THUMB_MAX_SIZE_PX = 200;
    private static final int JPEG_QUALITY = 85;

    private final Context context;
    private final Executor executor;
    private final Handler mainHandler;

    public ThumbnailStorageManager(Context context, Executor executor) {
        this.context = context.getApplicationContext();
        this.executor = executor;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static File getThumbnailRootDir(Context context) {
        return new File(context.getFilesDir(), THUMB_DIR_NAME);
    }

    /**
     * Returns the file where the thumbnail for (sourceFile, position) should be stored/read.
     * Does not create anything.
     */
    public static File getThumbnailFile(Context context, File sourceFile, String position) {
        if (sourceFile == null || position == null) return null;
        String path = sourceFile.getAbsolutePath();
        long mtime = sourceFile.exists() ? sourceFile.lastModified() : 0L;
        File subdir = getSubdir(context, path);
        return new File(subdir, position + "_" + mtime + ".jpg");
    }

    private static File getSubdir(Context context, String sourceAbsolutePath) {
        String hash = md5(sourceAbsolutePath);
        return new File(getThumbnailRootDir(context), hash);
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }

    /**
     * True if the thumbnail file exists and matches current source (path + lastModified).
     */
    public static boolean isThumbnailValid(File sourceFile, File thumbnailFile) {
        if (sourceFile == null || thumbnailFile == null || !sourceFile.exists() || !thumbnailFile.exists())
            return false;
        String name = thumbnailFile.getName();
        if (!name.endsWith(".jpg")) return false;
        String base = name.substring(0, name.length() - 4);
        int lastUnderscore = base.lastIndexOf('_');
        if (lastUnderscore <= 0) return false;
        try {
            long mtime = Long.parseLong(base.substring(lastUnderscore + 1));
            return sourceFile.lastModified() == mtime;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public interface ThumbnailCallback {
        void onThumbnailReady(File thumbnailFile);
        void onThumbnailFailed();
    }

    /**
     * If a valid thumbnail file exists, invokes callback on main with it; otherwise generates in executor then invokes.
     */
    public void getOrCreateVideoThumbnail(File videoFile, String position, ThumbnailCallback callback) {
        if (videoFile == null || !videoFile.exists() || position == null) {
            if (callback != null) mainHandler.post(callback::onThumbnailFailed);
            return;
        }
        float[] cropRegion = AppConfig.getPanoramicCropRegion(position);
        if (cropRegion == null || cropRegion.length < 4) {
            if (callback != null) mainHandler.post(callback::onThumbnailFailed);
            return;
        }

        File thumbFile = getThumbnailFile(context, videoFile, position);
        if (thumbFile != null && thumbFile.exists() && isThumbnailValid(videoFile, thumbFile)) {
            if (callback != null) mainHandler.post(() -> callback.onThumbnailReady(thumbFile));
            return;
        }

        executor.execute(() -> {
            File result = generateAndSaveVideoThumbnail(videoFile, position, cropRegion);
            if (callback != null) {
                mainHandler.post(() -> {
                    if (result != null) callback.onThumbnailReady(result);
                    else callback.onThumbnailFailed();
                });
            }
        });
    }

    /**
     * If a valid thumbnail file exists, invokes callback on main with it; otherwise generates in executor then invokes.
     */
    public void getOrCreatePhotoThumbnail(File photoFile, String position, ThumbnailCallback callback) {
        if (photoFile == null || !photoFile.exists() || position == null) {
            if (callback != null) mainHandler.post(callback::onThumbnailFailed);
            return;
        }
        float[] cropRegion = AppConfig.getPanoramicCropRegion(position);
        if (cropRegion == null || cropRegion.length < 4) {
            if (callback != null) mainHandler.post(callback::onThumbnailFailed);
            return;
        }

        File thumbFile = getThumbnailFile(context, photoFile, position);
        if (thumbFile != null && thumbFile.exists() && isThumbnailValid(photoFile, thumbFile)) {
            if (callback != null) mainHandler.post(() -> callback.onThumbnailReady(thumbFile));
            return;
        }

        executor.execute(() -> {
            File result = generateAndSavePhotoThumbnail(photoFile, position, cropRegion);
            if (callback != null) {
                mainHandler.post(() -> {
                    if (result != null) callback.onThumbnailReady(result);
                    else callback.onThumbnailFailed();
                });
            }
        });
    }

    /**
     * Generates cropped video thumbnail and saves to thumbnail dir. Call from background thread.
     */
    public File generateAndSaveVideoThumbnail(File videoFile, String position, float[] cropRegion) {
        if (cropRegion == null || cropRegion.length < 4) return null;

        MediaMetadataRetriever retriever = null;
        Bitmap fullFrame = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFile.getAbsolutePath());
            fullFrame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            return null;
        } finally {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        }
        if (fullFrame == null) return null;

        int w = fullFrame.getWidth();
        int h = fullFrame.getHeight();
        int cropX = (int) (cropRegion[0] * w);
        int cropY = (int) (cropRegion[1] * h);
        int cropW = (int) (cropRegion[2] * w);
        int cropH = (int) (cropRegion[3] * h);
        cropX = Math.max(0, Math.min(cropX, w - 1));
        cropY = Math.max(0, Math.min(cropY, h - 1));
        cropW = Math.max(1, Math.min(cropW, w - cropX));
        cropH = Math.max(1, Math.min(cropH, h - cropY));

        Bitmap cropped = null;
        try {
            cropped = Bitmap.createBitmap(fullFrame, cropX, cropY, cropW, cropH);
        } catch (Exception e) {
            if (fullFrame != null && !fullFrame.isRecycled()) fullFrame.recycle();
            return null;
        }
        if (fullFrame != null && fullFrame != cropped && !fullFrame.isRecycled()) {
            fullFrame.recycle();
        }

        Bitmap thumb = scaleDownIfNeeded(cropped);
        if (thumb != cropped && cropped != null && !cropped.isRecycled()) {
            cropped.recycle();
        }

        File outFile = getThumbnailFile(context, videoFile, position);
        if (outFile == null || thumb == null) {
            if (thumb != null && !thumb.isRecycled()) thumb.recycle();
            return null;
        }

        File saved = saveThumbnailAndSourceTxt(thumb, outFile, videoFile.getAbsolutePath());
        if (thumb != null && !thumb.isRecycled()) thumb.recycle();
        return saved;
    }

    /**
     * Generates cropped photo thumbnail and saves to thumbnail dir. Call from background thread.
     */
    public File generateAndSavePhotoThumbnail(File photoFile, String position, float[] cropRegion) {
        if (cropRegion == null || cropRegion.length < 4) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(photoFile.getAbsolutePath(), opts);
        int srcW = opts.outWidth;
        int srcH = opts.outHeight;
        if (srcW <= 0 || srcH <= 0) return null;

        int maxDim = Math.max(srcW, srcH);
        opts.inSampleSize = 1;
        while (maxDim / opts.inSampleSize > 1024) {
            opts.inSampleSize *= 2;
        }
        opts.inJustDecodeBounds = false;
        Bitmap full = BitmapFactory.decodeFile(photoFile.getAbsolutePath(), opts);
        if (full == null) return null;

        int w = full.getWidth();
        int h = full.getHeight();
        int cropX = (int) (cropRegion[0] * w);
        int cropY = (int) (cropRegion[1] * h);
        int cropW = (int) (cropRegion[2] * w);
        int cropH = (int) (cropRegion[3] * h);
        cropX = Math.max(0, Math.min(cropX, w - 1));
        cropY = Math.max(0, Math.min(cropY, h - 1));
        cropW = Math.max(1, Math.min(cropW, w - cropX));
        cropH = Math.max(1, Math.min(cropH, h - cropY));

        Bitmap cropped = null;
        try {
            cropped = Bitmap.createBitmap(full, cropX, cropY, cropW, cropH);
        } catch (Exception e) {
            if (full != null && !full.isRecycled()) full.recycle();
            return null;
        }
        if (full != null && full != cropped && !full.isRecycled()) {
            full.recycle();
        }

        Bitmap thumb = scaleDownIfNeeded(cropped);
        if (thumb != cropped && cropped != null && !cropped.isRecycled()) {
            cropped.recycle();
        }

        File outFile = getThumbnailFile(context, photoFile, position);
        if (outFile == null || thumb == null) {
            if (thumb != null && !thumb.isRecycled()) thumb.recycle();
            return null;
        }

        File saved = saveThumbnailAndSourceTxt(thumb, outFile, photoFile.getAbsolutePath());
        if (thumb != null && !thumb.isRecycled()) thumb.recycle();
        return saved;
    }

    private static Bitmap scaleDownIfNeeded(Bitmap bitmap) {
        if (bitmap == null) return null;
        if (bitmap.getWidth() <= THUMB_MAX_SIZE_PX && bitmap.getHeight() <= THUMB_MAX_SIZE_PX) {
            return bitmap;
        }
        int tw = bitmap.getWidth();
        int th = bitmap.getHeight();
        if (tw > th) {
            th = th * THUMB_MAX_SIZE_PX / tw;
            tw = THUMB_MAX_SIZE_PX;
        } else {
            tw = tw * THUMB_MAX_SIZE_PX / th;
            th = THUMB_MAX_SIZE_PX;
        }
        return Bitmap.createScaledBitmap(bitmap, tw, th, true);
    }

    private File saveThumbnailAndSourceTxt(Bitmap thumb, File outFile, String sourcePath) {
        File subdir = outFile.getParentFile();
        if (subdir == null) return null;
        if (!subdir.exists() && !subdir.mkdirs()) return null;

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(outFile);
            thumb.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            fos.flush();
        } catch (IOException e) {
            if (outFile.exists()) outFile.delete();
            return null;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }

        File sourceTxt = new File(subdir, SOURCE_TXT);
        try {
            java.io.FileWriter fw = new java.io.FileWriter(sourceTxt);
            fw.write(sourcePath);
            fw.close();
        } catch (IOException ignored) {}

        return outFile;
    }

    /**
     * Removes thumbnail files whose source no longer exists or whose source lastModified no longer matches.
     * Safe to call from any thread; runs I/O on executor.
     */
    public void cleanup(Runnable onFinished) {
        executor.execute(() -> {
            doCleanup();
            if (onFinished != null) mainHandler.post(onFinished);
        });
    }

    /**
     * One-off cleanup from anywhere (e.g. when entering playback). Uses a dedicated single-thread executor.
     */
    public static void runCleanup(Context context) {
        Executor cleanupExecutor = Executors.newSingleThreadExecutor();
        ThumbnailStorageManager m = new ThumbnailStorageManager(context.getApplicationContext(), cleanupExecutor);
        m.cleanup(null);
    }

    private void doCleanup() {
        File root = getThumbnailRootDir(context);
        if (!root.exists() || !root.isDirectory()) return;

        File[] subdirs = root.listFiles(File::isDirectory);
        if (subdirs == null) return;

        for (File subdir : subdirs) {
            File sourceTxt = new File(subdir, SOURCE_TXT);
            String sourcePath = readFirstLine(sourceTxt);
            if (sourcePath == null || sourcePath.isEmpty()) {
                deleteRecursively(subdir);
                continue;
            }
            File sourceFile = new File(sourcePath);
            if (!sourceFile.exists()) {
                deleteRecursively(subdir);
                continue;
            }
            long sourceMtime = sourceFile.lastModified();
            File[] jpgs = subdir.listFiles((d, name) -> name.endsWith(".jpg"));
            if (jpgs != null) {
                for (File jpg : jpgs) {
                    String base = jpg.getName();
                    base = base.substring(0, base.length() - 4);
                    int idx = base.lastIndexOf('_');
                    if (idx <= 0) continue;
                    try {
                        long mtime = Long.parseLong(base.substring(idx + 1));
                        if (mtime != sourceMtime) jpg.delete();
                    } catch (NumberFormatException ignored) {
                        jpg.delete();
                    }
                }
            }
        }
    }

    private static String readFirstLine(File file) {
        if (file == null || !file.exists()) return null;
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8));
            String line = br.readLine();
            br.close();
            return line;
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        file.delete();
    }
}

package com.kooo.evcam.playback;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.security.MessageDigest;

/**
 * 全景图片裁切变换
 * 用于从full视角图片中裁切出指定方向的区域
 */
public class PanoramicCropTransformation extends BitmapTransformation {
    private static final String ID = "com.kooo.evcam.playback.PanoramicCropTransformation";
    private static final byte[] ID_BYTES = ID.getBytes(CHARSET);

    private final float[] cropRegion; // [x, y, width, height] 归一化坐标

    public PanoramicCropTransformation(float[] cropRegion) {
        this.cropRegion = cropRegion;
    }

    @Override
    protected Bitmap transform(BitmapPool pool, Bitmap toTransform, int outWidth, int outHeight) {
        if (cropRegion == null || cropRegion.length < 4) {
            return toTransform;
        }

        int sourceWidth = toTransform.getWidth();
        int sourceHeight = toTransform.getHeight();

        // 计算裁切区域（像素坐标）
        int cropX = (int) (cropRegion[0] * sourceWidth);
        int cropY = (int) (cropRegion[1] * sourceHeight);
        int cropWidth = (int) (cropRegion[2] * sourceWidth);
        int cropHeight = (int) (cropRegion[3] * sourceHeight);

        // 确保裁切区域在图片范围内
        cropX = Math.max(0, Math.min(cropX, sourceWidth - 1));
        cropY = Math.max(0, Math.min(cropY, sourceHeight - 1));
        cropWidth = Math.max(1, Math.min(cropWidth, sourceWidth - cropX));
        cropHeight = Math.max(1, Math.min(cropHeight, sourceHeight - cropY));

        // 从原图中裁切出指定区域
        Bitmap croppedBitmap = Bitmap.createBitmap(toTransform, cropX, cropY, cropWidth, cropHeight);

        // 如果需要缩放，则进行缩放
        if (outWidth > 0 && outHeight > 0 && (croppedBitmap.getWidth() != outWidth || croppedBitmap.getHeight() != outHeight)) {
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, outWidth, outHeight, true);
            if (croppedBitmap != toTransform && croppedBitmap != scaledBitmap) {
                croppedBitmap.recycle();
            }
            return scaledBitmap;
        }

        return croppedBitmap;
    }

    @Override
    public void updateDiskCacheKey(MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
        if (cropRegion != null && cropRegion.length >= 4) {
            String regionStr = cropRegion[0] + "," + cropRegion[1] + "," + cropRegion[2] + "," + cropRegion[3];
            messageDigest.update(regionStr.getBytes(CHARSET));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PanoramicCropTransformation that = (PanoramicCropTransformation) o;
        if (cropRegion == null && that.cropRegion == null) return true;
        if (cropRegion == null || that.cropRegion == null) return false;
        if (cropRegion.length != that.cropRegion.length) return false;
        for (int i = 0; i < cropRegion.length; i++) {
            if (Float.compare(cropRegion[i], that.cropRegion[i]) != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        if (cropRegion == null) return ID.hashCode();
        int result = ID.hashCode();
        for (float f : cropRegion) {
            result = 31 * result + Float.hashCode(f);
        }
        return result;
    }
}

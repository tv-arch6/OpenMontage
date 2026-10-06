package com.Cigram.vid;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chat media: picking, shrinking, thumbnailing, uploading, and displaying.
 *
 * Shrinking happens on the device before anything is sent, so a 12 MP photo
 * becomes a ~300 KB JPEG instead of being rejected for size. Video is uploaded
 * with R2 multipart in 6 MB parts with real progress and a working cancel.
 *
 * Display: chat images are behind short-lived signed URLs, so the cache is keyed
 * by the MEDIA ID, never by the URL — otherwise every refreshed link would be a
 * cache miss. Decoding is bounded by the view size, and the memory cache is a
 * fraction of the heap, so a long conversation cannot run the app out of memory.
 */
public final class CigramAdsMedia {

    /** Longest edge of an uploaded photo. Big enough to read, small enough to send. */
    private static final int IMAGE_MAX_EDGE = 1600;
    private static final int IMAGE_QUALITY = 82;
    private static final int THUMB_MAX_EDGE = 480;
    private static final int THUMB_QUALITY = 70;
    private static final int PART_BYTES = 6 * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 20L * 1024L * 1024L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final ExecutorService DECODE = Executors.newFixedThreadPool(2);

    private CigramAdsMedia() { }

    // =========================================================== upload types

    /** Cancelable handle for one upload. */
    public static final class Upload {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        String mediaId = "";
        String uploadId = "";

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    public interface Progress {
        /** 0..100, then {@link #done} or {@link #failed}. */
        void progress(int percent);

        /** mediaId plus whatever metadata was discovered (width/height/duration/name). */
        void done(String mediaId, JSONObject info);

        void failed(String message);
    }

    // =========================================================== local probing

    public static String displayName(Context context, Uri uri) {
        if (context == null || uri == null) return "";
        try {
            ContentResolver resolver = context.getContentResolver();
            android.database.Cursor cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (column >= 0 && cursor.moveToFirst()) {
                        String name = cursor.getString(column);
                        if (name != null) return name;
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable ignored) { }
        String path = uri.getLastPathSegment();
        return path == null ? "" : path;
    }

    public static long sizeOf(Context context, Uri uri) {
        try {
            android.database.Cursor cursor = context.getContentResolver()
                    .query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                        return cursor.getLong(column);
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable ignored) { }
        try {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) return 0L;
            try {
                long total = 0L;
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) total += n;
                return total;
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return round(bytes / (1024d * 1024d * 1024d)) + " غيغابايت";
        }
        if (bytes >= 1024L * 1024L) return round(bytes / (1024d * 1024d)) + " ميجابايت";
        if (bytes >= 1024L) return round(bytes / 1024d) + " كيلوبايت";
        return bytes + " بايت";
    }

    private static String round(double value) {
        String s = String.format(java.util.Locale.US, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    public static String clockOf(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long minutes = total / 60L;
        long seconds = total % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    // ============================================================ compression

    /** Decodes and scales in one pass, so a huge photo never lands in memory whole. */
    public static byte[] shrinkImage(Context context, Uri uri, int maxEdge, int quality) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream probe = context.getContentResolver().openInputStream(uri);
            if (probe == null) return null;
            try {
                BitmapFactory.decodeStream(probe, null, bounds);
            } finally {
                probe.close();
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (sample * 2) >= maxEdge) sample *= 2;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            Bitmap bitmap;
            try {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            } finally {
                in.close();
            }
            if (bitmap == null) return null;

            Bitmap scaled = bitmap;
            int edge = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (edge > maxEdge) {
                float factor = (float) maxEdge / (float) edge;
                int width = Math.max(1, Math.round(bitmap.getWidth() * factor));
                int height = Math.max(1, Math.round(bitmap.getHeight() * factor));
                scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
                if (scaled != bitmap) bitmap.recycle();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out);
            int width = scaled.getWidth();
            int height = scaled.getHeight();
            scaled.recycle();
            byte[] bytes = out.toByteArray();
            lastWidth = width;
            lastHeight = height;
            return bytes;
        } catch (Throwable error) {
            return null;
        }
    }

    // Dimensions of the last shrinkImage() result, read right after it returns.
    private static volatile int lastWidth = 0;
    private static volatile int lastHeight = 0;

    /** A frame from the middle of the video, as a JPEG, plus its duration. */
    public static JSONObject videoThumb(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            long duration = 0L;
            try {
                String raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                duration = raw == null ? 0L : Long.parseLong(raw);
            } catch (Throwable ignored) { }
            Bitmap frame = retriever.getFrameAtTime(Math.max(0L, duration / 2L) * 1000L);
            JSONObject out = new JSONObject();
            out.put("duration_ms", duration);
            if (frame != null) {
                int edge = Math.max(frame.getWidth(), frame.getHeight());
                Bitmap scaled = frame;
                if (edge > THUMB_MAX_EDGE) {
                    float factor = (float) THUMB_MAX_EDGE / (float) edge;
                    scaled = Bitmap.createScaledBitmap(frame,
                            Math.max(1, Math.round(frame.getWidth() * factor)),
                            Math.max(1, Math.round(frame.getHeight() * factor)), true);
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, bytes);
                out.put("width", frame.getWidth());
                out.put("height", frame.getHeight());
                out.put("thumb", bytes.toByteArray());
                if (scaled != frame) scaled.recycle();
                frame.recycle();
            }
            return out;
        } catch (Throwable error) {
            return new JSONObject();
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) { }
        }
    }

    // ================================================================ uploads

    /** Photo: shrink, upload in one request, report width/height. */
    public static Upload uploadImage(final Context context, final Uri uri, final Progress progress) {
        final Upload upload = new Upload();
        final Context app = context.getApplicationContext();
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    report(progress, 2);
                    byte[] bytes = shrinkImage(app, uri, IMAGE_MAX_EDGE, IMAGE_QUALITY);
                    if (bytes == null || bytes.length == 0) {
                        fail(progress, "تعذر قراءة الصورة. اختر صورة أخرى.");
                        return;
                    }
                    int width = lastWidth;
                    int height = lastHeight;
                    if (upload.isCancelled()) return;
                    report(progress, 25);
                    String mediaId = putBytes(app, "/ads/chat/media/upload?kind=image&name="
                            + CigramAdsApi.encode("photo.jpg"), bytes, "image/jpeg", upload, progress, 25, 95);
                    if (mediaId == null) return;
                    JSONObject info = new JSONObject();
                    info.put("width", width);
                    info.put("height", height);
                    info.put("size", bytes.length);
                    finish(progress, mediaId, info);
                } catch (Throwable error) {
                    fail(progress, "تعذر رفع الصورة. تحقق من الاتصال.");
                }
            }
        });
        return upload;
    }

    /** Voice note: the recorded file is already small, so it goes in one request. */
    public static Upload uploadAudio(final Context context, final File file, final int durationMs,
                                     final Progress progress) {
        final Upload upload = new Upload();
        final Context app = context.getApplicationContext();
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    byte[] bytes = readFile(file);
                    if (bytes == null || bytes.length == 0) {
                        fail(progress, "التسجيل فارغ. أعد المحاولة.");
                        return;
                    }
                    String mediaId = putBytes(app, "/ads/chat/media/upload?kind=audio&name="
                            + CigramAdsApi.encode("voice.m4a"), bytes, "audio/mp4", upload, progress, 5, 95);
                    if (mediaId == null) return;
                    JSONObject info = new JSONObject();
                    info.put("duration_ms", durationMs);
                    info.put("size", bytes.length);
                    finish(progress, mediaId, info);
                } catch (Throwable error) {
                    fail(progress, "تعذر رفع التسجيل.");
                }
            }
        });
        return upload;
    }

    /** Document: uploaded as-is, with its real name kept. */
    public static Upload uploadFile(final Context context, final Uri uri, final Progress progress) {
        final Upload upload = new Upload();
        final Context app = context.getApplicationContext();
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    long size = sizeOf(app, uri);
                    if (size > MAX_FILE_BYTES) {
                        fail(progress, "الملف أكبر من " + humanSize(MAX_FILE_BYTES) + ".");
                        return;
                    }
                    byte[] bytes = readUri(app, uri, MAX_FILE_BYTES);
                    if (bytes == null || bytes.length == 0) {
                        fail(progress, "تعذر قراءة الملف.");
                        return;
                    }
                    String name = displayName(app, uri);
                    String mediaId = putBytes(app, "/ads/chat/media/upload?kind=file&name="
                                    + CigramAdsApi.encode(name), bytes, "application/octet-stream",
                            upload, progress, 5, 95);
                    if (mediaId == null) return;
                    JSONObject info = new JSONObject();
                    info.put("name", name);
                    info.put("size", bytes.length);
                    finish(progress, mediaId, info);
                } catch (Throwable error) {
                    fail(progress, "تعذر رفع الملف.");
                }
            }
        });
        return upload;
    }

    /**
     * Video: thumbnail first (so the bubble has something to show at once), then
     * R2 multipart in 6 MB parts. Cancel aborts the upload server-side too, so no
     * half-finished object is left behind.
     */
    public static Upload uploadVideo(final Context context, final Uri uri, final Progress progress) {
        final Upload upload = new Upload();
        final Context app = context.getApplicationContext();
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    long size = sizeOf(app, uri);
                    if (size <= 0L) {
                        fail(progress, "تعذر قراءة الفيديو.");
                        return;
                    }
                    if (size > MAX_VIDEO_BYTES) {
                        fail(progress, "الفيديو أكبر من " + humanSize(MAX_VIDEO_BYTES)
                                + ". اختر مقطعاً أقصر أو بجودة أقل.");
                        return;
                    }

                    JSONObject probe = videoThumb(app, uri);
                    String thumbId = "";
                    byte[] thumbBytes = null;
                    Object rawThumb = probe.opt("thumb");
                    if (rawThumb instanceof byte[]) thumbBytes = (byte[]) rawThumb;
                    if (thumbBytes != null) {
                        thumbId = putBytes(app, "/ads/chat/media/upload?kind=image&name="
                                        + CigramAdsApi.encode("thumb.jpg"), thumbBytes, "image/jpeg",
                                upload, null, 0, 0);
                        if (upload.isCancelled()) return;
                    }
                    report(progress, 4);

                    JSONObject create = new JSONObject();
                    create.put("kind", "video");
                    create.put("total_bytes", size);
                    create.put("mime", "video/mp4");
                    create.put("name", displayName(app, uri));
                    CigramAdsApi.Result started = CigramAdsApi.request(app, "POST",
                            "/ads/chat/media/mpu/create", create, true);
                    if (!started.ok()) {
                        fail(progress, started.error);
                        return;
                    }
                    upload.mediaId = started.data().optString("media_id", "");
                    upload.uploadId = started.data().optString("upload_id", "");
                    if (upload.mediaId.length() == 0 || upload.uploadId.length() == 0) {
                        fail(progress, "تعذر بدء رفع الفيديو.");
                        return;
                    }

                    JSONArray parts = new JSONArray();
                    InputStream in = app.getContentResolver().openInputStream(uri);
                    if (in == null) {
                        abort(app, upload);
                        fail(progress, "تعذر قراءة الفيديو.");
                        return;
                    }
                    long sent = 0L;
                    int partNumber = 0;
                    try {
                        byte[] buffer = new byte[PART_BYTES];
                        for (;;) {
                            if (upload.isCancelled()) {
                                abort(app, upload);
                                return;
                            }
                            int filled = 0;
                            while (filled < buffer.length) {
                                int n = in.read(buffer, filled, buffer.length - filled);
                                if (n < 0) break;
                                filled += n;
                            }
                            if (filled == 0) break;
                            partNumber++;
                            byte[] chunk = filled == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, filled);
                            JSONObject part = postRaw(app,
                                    "/ads/chat/media/mpu/part?media_id=" + CigramAdsApi.encode(upload.mediaId)
                                            + "&upload_id=" + CigramAdsApi.encode(upload.uploadId)
                                            + "&part=" + partNumber,
                                    chunk, "application/octet-stream");
                            if (part == null) {
                                abort(app, upload);
                                fail(progress, "انقطع رفع الفيديو. أعد المحاولة.");
                                return;
                            }
                            JSONObject entry = new JSONObject();
                            entry.put("part", partNumber);
                            entry.put("etag", part.optString("etag", ""));
                            entry.put("sealed_bytes", part.optInt("sealed_bytes", 0));
                            parts.put(entry);
                            sent += filled;
                            report(progress, (int) Math.max(5, Math.min(95, sent * 92L / size + 4L)));
                            if (filled < PART_BYTES) break;
                        }
                    } finally {
                        try {
                            in.close();
                        } catch (Throwable ignored) { }
                    }

                    if (parts.length() == 0) {
                        abort(app, upload);
                        fail(progress, "الفيديو فارغ.");
                        return;
                    }
                    JSONObject complete = new JSONObject();
                    complete.put("media_id", upload.mediaId);
                    complete.put("upload_id", upload.uploadId);
                    complete.put("parts", parts);
                    CigramAdsApi.Result finished = CigramAdsApi.request(app, "POST",
                            "/ads/chat/media/mpu/complete", complete, true);
                    if (!finished.ok()) {
                        abort(app, upload);
                        fail(progress, finished.error);
                        return;
                    }

                    JSONObject info = new JSONObject();
                    info.put("duration_ms", probe.optLong("duration_ms", 0L));
                    info.put("width", probe.optInt("width", 0));
                    info.put("height", probe.optInt("height", 0));
                    info.put("size", size);
                    if (thumbId != null && thumbId.length() > 0) info.put("thumb_id", thumbId);
                    finish(progress, upload.mediaId, info);
                } catch (Throwable error) {
                    abort(app, upload);
                    fail(progress, "تعذر رفع الفيديو. تحقق من الاتصال.");
                }
            }
        });
        return upload;
    }

    private static void abort(Context app, Upload upload) {
        if (upload.mediaId.length() == 0 || upload.uploadId.length() == 0) return;
        try {
            JSONObject body = new JSONObject();
            body.put("media_id", upload.mediaId);
            body.put("upload_id", upload.uploadId);
            CigramAdsApi.request(app, "POST", "/ads/chat/media/mpu/abort", body, true);
        } catch (Throwable ignored) { }
    }

    // --------------------------------------------------------------- raw HTTP

    /** Returns the media id, or null after reporting the failure. */
    private static String putBytes(Context app, String path, byte[] bytes, String contentType,
                                   Upload upload, Progress progress, int from, int to) {
        JSONObject body = postRaw(app, path, bytes, contentType);
        if (body == null) {
            if (progress != null) fail(progress, "تعذر الرفع. تحقق من الاتصال.");
            return null;
        }
        if (progress != null && to > from) report(progress, to);
        String mediaId = body.optString("media_id", "");
        if (mediaId.length() == 0) {
            if (progress != null) fail(progress, "رد غير متوقع من الخادم.");
            return null;
        }
        return mediaId;
    }

    /** One raw-body POST. Returns the "data" object on success, null otherwise. */
    private static JSONObject postRaw(Context app, String path, byte[] bytes, String contentType) {
        HttpURLConnection c = null;
        try {
            String token = CigramUserData.token(app);
            if (token.length() == 0) return null;
            c = (HttpURLConnection) new URL(CigramAdsApi.BASE + path).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(60000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", contentType);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = c.getOutputStream();
            try {
                int at = 0;
                while (at < bytes.length) {
                    int chunk = Math.min(64 * 1024, bytes.length - at);
                    out.write(bytes, at, chunk);
                    at += chunk;
                }
                out.flush();
            } finally {
                out.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readStream(in, 1024 * 1024);
            if (code < 200 || code >= 300) return null;
            JSONObject json = new JSONObject(text);
            if (!json.optBoolean("ok", false)) return null;
            JSONObject data = json.optJSONObject("data");
            return data == null ? json : data;
        } catch (Throwable error) {
            return null;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) { }
            }
        }
    }

    private static String readStream(InputStream in, int max) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > max) break;
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } catch (Throwable ignored) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) { }
        }
    }

    private static byte[] readUri(Context app, Uri uri, long limit) {
        try {
            InputStream in = app.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                int n;
                long total = 0L;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > limit) return null;
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] readFile(File file) {
        if (file == null || !file.exists()) return null;
        try {
            InputStream in = new java.io.FileInputStream(file);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void report(final Progress progress, final int percent) {
        if (progress == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                progress.progress(Math.max(0, Math.min(100, percent)));
            }
        });
    }

    private static void finish(final Progress progress, final String mediaId, final JSONObject info) {
        if (progress == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                progress.progress(100);
                progress.done(mediaId, info == null ? new JSONObject() : info);
            }
        });
    }

    private static void fail(final Progress progress, final String message) {
        if (progress == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                progress.failed(message == null ? "تعذر الرفع." : message);
            }
        });
    }

    // ============================================================== displaying

    /** ~1/8 of the heap, so a long conversation of photos cannot exhaust memory. */
    private static final LruCache<String, Bitmap> MEMORY =
            new LruCache<String, Bitmap>(Math.max(4 * 1024,
                    (int) (Runtime.getRuntime().maxMemory() / 1024L / 8L))) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount() / 1024;
                }
            };

    /** The id is stable; the signed URL is not, so the cache key must be the id. */
    public static String mediaIdOf(String signedUrl) {
        if (signedUrl == null) return "";
        int at = signedUrl.indexOf("id=");
        if (at < 0) return "";
        int end = signedUrl.indexOf('&', at);
        String raw = end < 0 ? signedUrl.substring(at + 3) : signedUrl.substring(at + 3, end);
        return Uri.decode(raw);
    }

    private static File diskFile(Context app, String mediaId) {
        File dir = new File(app.getCacheDir(), "ads_chat_media");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, mediaId.replaceAll("[^A-Za-z0-9_\\-]", "_") + ".bin");
    }

    /**
     * Loads a chat image into a view: memory cache, then disk, then the signed URL.
     * The view's tag guards against a recycled row showing the wrong photo.
     */
    public static void loadInto(final Context context, final String signedUrl, final ImageView view,
                                final int targetPx) {
        if (view == null) return;
        final String mediaId = mediaIdOf(signedUrl);
        if (mediaId.length() == 0) {
            view.setImageDrawable(null);
            return;
        }
        view.setTag(mediaId);
        Bitmap cached = MEMORY.get(mediaId);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setAlpha(1f);
            return;
        }
        view.setImageDrawable(null);
        view.setAlpha(0f);
        final Context app = context.getApplicationContext();
        DECODE.execute(new Runnable() {
            @Override public void run() {
                Bitmap bitmap = null;
                File file = diskFile(app, mediaId);
                if (file.exists() && file.length() > 0L) bitmap = decode(file, targetPx);
                if (bitmap == null && signedUrl != null && signedUrl.length() > 0) {
                    if (download(app, signedUrl, file)) bitmap = decode(file, targetPx);
                }
                if (bitmap == null) return;
                final Bitmap ready = bitmap;
                MEMORY.put(mediaId, ready);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (!mediaId.equals(view.getTag())) return; // the row was recycled
                        view.setImageBitmap(ready);
                        view.animate().alpha(1f).setDuration(180L).start();
                    }
                });
            }
        });
    }

    private static boolean download(Context app, String path, File target) {
        HttpURLConnection c = null;
        try {
            String url = path.startsWith("http") ? path : CigramAdsApi.BASE + path;
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() != 200) return false;
            InputStream in = c.getInputStream();
            File temp = new File(target.getAbsolutePath() + ".part");
            OutputStream out = new FileOutputStream(temp);
            try {
                byte[] buffer = new byte[65536];
                int n;
                long total = 0L;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > 24L * 1024L * 1024L) return false;
                    out.write(buffer, 0, n);
                }
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) { }
                try {
                    in.close();
                } catch (Throwable ignored) { }
            }
            return temp.renameTo(target);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) { }
            }
        }
    }

    private static Bitmap decode(File file, int targetPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int target = targetPx > 0 ? targetPx : 1024;
            while (longest / (sample * 2) >= target) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Fetches a chat file to the cache so another app can open it. */
    public static void fetchToCache(final Context context, final String signedUrl, final String name,
                                   final Callback callback) {
        final Context app = context.getApplicationContext();
        final String mediaId = mediaIdOf(signedUrl);
        IO.execute(new Runnable() {
            @Override public void run() {
                File file = diskFile(app, mediaId + "-" + safeName(name));
                boolean ok = file.exists() && file.length() > 0L;
                if (!ok) ok = download(app, signedUrl, file);
                final boolean done = ok;
                final File result = file;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (callback == null) return;
                        if (done) callback.ready(result);
                        else callback.failed("تعذر تنزيل الملف.");
                    }
                });
            }
        });
    }

    public interface Callback {
        void ready(File file);

        void failed(String message);
    }

    private static String safeName(String name) {
        String value = name == null ? "file" : name;
        return value.replaceAll("[^A-Za-z0-9_\\-.\\u0600-\\u06FF]", "_");
    }

    /** Called when a conversation is wiped or the account is deleted. */
    public static void clearCache(Context context) {
        try {
            MEMORY.evictAll();
            File dir = new File(context.getApplicationContext().getCacheDir(), "ads_chat_media");
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File file : files) file.delete();
        } catch (Throwable ignored) { }
    }
}

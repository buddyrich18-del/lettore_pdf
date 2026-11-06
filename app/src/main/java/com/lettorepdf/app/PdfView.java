package com.lettorepdf.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Vista PDF a scorrimento continuo con zoom (pinch e doppio tocco). */
public class PdfView extends View {

    public interface Listener {
        void onPageChanged(int page, int count);
        void onTap();
    }

    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 5f;
    private static final int MAX_RENDER_WIDTH = 2048;

    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private int count;
    private float[] pageW = new float[0], pageH = new float[0];
    private float[] tops = new float[0];
    private float totalH;
    private int viewW, viewH;

    private float zoom = 1f, scrollX, scrollY;
    private final float gap;
    private int pendingPage = -1;
    private int lastReportedPage = -1;

    private final HandlerThread thread = new HandlerThread("pdf-render");
    private final Handler worker;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile int generation;
    private final Set<Integer> pending = new HashSet<>();
    private final int cacheBytes;
    private final LruCache<Integer, Bitmap> cache;

    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;
    private ValueAnimator zoomAnim;

    private final Paint pagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint nightPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholder = new Paint();
    private final RectF dst = new RectF();
    private boolean night;
    private Listener listener;

    public PdfView(Context c) { this(c, null); }

    public PdfView(Context c, AttributeSet a) {
        super(c, a);
        gap = 8 * getResources().getDisplayMetrics().density;
        thread.start();
        worker = new Handler(thread.getLooper());

        cacheBytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 4, 160L * 1024 * 1024);
        cache = new LruCache<Integer, Bitmap>(cacheBytes) {
            @Override protected int sizeOf(Integer k, Bitmap b) { return b.getByteCount(); }
        };

        ColorMatrix inv = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0});
        nightPaint.setColorFilter(new ColorMatrixColorFilter(inv));

        scroller = new OverScroller(c);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaleGestures.isInProgress()) return false;
                scrollX += dx; scrollY += dy;
                clampAndRefresh();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                scroller.fling((int) scrollX, (int) scrollY, (int) -vx, (int) -vy,
                        0, (int) maxScrollX(), 0, (int) maxScrollY());
                postInvalidateOnAnimation();
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                animateZoom(zoom > 1.05f ? 1f : 2.5f, e.getX(), e.getY());
                return true;
            }
        });
        scaleGestures = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomAround(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
    }

    // ---------- API pubblica ----------

    public void setListener(Listener l) { listener = l; }

    public void setNight(boolean n) { night = n; invalidate(); }

    public int getPageCount() { return count; }

    public int getCurrentPage() {
        if (count == 0 || viewH == 0) return Math.max(pendingPage, 0);
        return pageAt((scrollY + viewH / 2f) / zoom);
    }

    /** Apre il PDF. Lancia IOException/SecurityException se non leggibile o protetto da password. */
    public void open(ParcelFileDescriptor fd, int startPage) throws IOException {
        PdfRenderer r = new PdfRenderer(fd);
        int n = r.getPageCount();
        float[] w = new float[n], h = new float[n];
        for (int i = 0; i < n; i++) {
            PdfRenderer.Page p = r.openPage(i);
            w[i] = p.getWidth();
            h[i] = p.getHeight();
            p.close();
        }
        close();
        renderer = r; pfd = fd; count = n; pageW = w; pageH = h;
        zoom = 1f; scrollX = 0; scrollY = 0; lastReportedPage = -1;
        computeLayout();
        if (viewW > 0) goToPage(startPage); else pendingPage = startPage;
        invalidate();
    }

    public void close() {
        generation++;
        pending.clear();
        cache.evictAll();
        final PdfRenderer r = renderer;
        final ParcelFileDescriptor f = pfd;
        renderer = null; pfd = null; count = 0;
        if (r != null) {
            worker.post(() -> {
                synchronized (r) { try { r.close(); } catch (Exception ignored) { } }
                try { if (f != null) f.close(); } catch (Exception ignored) { }
            });
        }
        invalidate();
    }

    public void goToPage(int page) {
        if (count == 0) return;
        page = Math.max(0, Math.min(count - 1, page));
        if (viewW == 0) { pendingPage = page; return; }
        scroller.forceFinished(true);
        scrollY = tops[page] * zoom;
        clampAndRefresh();
    }

    public void release() {
        close();
        thread.quitSafely();
    }

    // ---------- Layout ----------

    private float baseHeight(int i) { return pageH[i] * viewW / pageW[i]; }

    private void computeLayout() {
        if (viewW == 0) return;
        tops = new float[count];
        float y = 0;
        for (int i = 0; i < count; i++) {
            tops[i] = y;
            y += baseHeight(i) + gap;
        }
        totalH = Math.max(0, y - gap);
    }

    private int pageAt(float baseY) {
        int lo = 0, hi = count - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (tops[mid] <= baseY) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    private float maxScrollX() { return Math.max(0, viewW * zoom - viewW); }
    private float maxScrollY() { return Math.max(0, totalH * zoom - viewH); }

    private void clampAndRefresh() {
        scrollX = Math.max(0, Math.min(maxScrollX(), scrollX));
        scrollY = Math.max(0, Math.min(maxScrollY(), scrollY));
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        int keepPage = -1; float frac = 0;
        if (count > 0 && viewW > 0) {
            keepPage = getCurrentPage();
            frac = (scrollY / zoom - tops[keepPage]) / baseHeight(keepPage);
        }
        viewW = w; viewH = h;
        if (count == 0) return;
        computeLayout();
        if (pendingPage >= 0) {
            int p = pendingPage; pendingPage = -1;
            goToPage(p);
        } else if (keepPage >= 0) {
            scrollY = (tops[keepPage] + frac * baseHeight(keepPage)) * zoom;
            clampAndRefresh();
        }
    }

    // ---------- Zoom ----------

    private void zoomAround(float nz, float fx, float fy) {
        nz = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, nz));
        float cx = (scrollX + fx) / zoom, cy = (scrollY + fy) / zoom;
        zoom = nz;
        scrollX = cx * nz - fx;
        scrollY = cy * nz - fy;
        clampAndRefresh();
    }

    private void animateZoom(float target, float fx, float fy) {
        if (zoomAnim != null) zoomAnim.cancel();
        zoomAnim = ValueAnimator.ofFloat(zoom, target);
        zoomAnim.setDuration(220);
        zoomAnim.addUpdateListener(a -> zoomAround((float) a.getAnimatedValue(), fx, fy));
        zoomAnim.start();
    }

    // ---------- Disegno e rendering ----------

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.getCurrX();
            scrollY = scroller.getCurrY();
            clampAndRefresh();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleGestures.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    /** Larghezza di rendering per la pagina, limitata in base alla memoria disponibile. */
    private int targetWidth(int i) {
        float rz = zoom < 1.4f ? 1f : (zoom < 2.4f ? 2f : 3f);
        float w = Math.min(viewW * rz, MAX_RENDER_WIDTH);
        float ar = pageH[i] / pageW[i];
        float maxBytes = cacheBytes / 3f;
        if (w * w * ar * 4 > maxBytes) w = (float) Math.sqrt(maxBytes / (4 * ar));
        return Math.max(1, (int) w);
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(night ? 0xFF121212 : 0xFFE6E6E6);
        if (count == 0 || viewW == 0) return;
        placeholder.setColor(night ? 0xFF2A2A2A : Color.WHITE);

        float contentW = viewW * zoom;
        int first = pageAt(scrollY / zoom);
        int last = first;
        for (int i = first; i < count; i++) {
            float top = tops[i] * zoom - scrollY;
            if (top > viewH) break;
            last = i;
            dst.set(-scrollX, top, -scrollX + contentW, top + baseHeight(i) * zoom);
            Bitmap b = cache.get(i);
            if (b != null) c.drawBitmap(b, null, dst, night ? nightPaint : pagePaint);
            else c.drawRect(dst, placeholder);
            int tw = targetWidth(i);
            if (b == null || b.getWidth() != tw) request(i, tw);
        }
        if (last + 1 < count && cache.get(last + 1) == null) request(last + 1, targetWidth(last + 1));

        int cur = getCurrentPage();
        if (cur != lastReportedPage) {
            lastReportedPage = cur;
            if (listener != null) listener.onPageChanged(cur, count);
        }
    }

    private void request(final int i, final int width) {
        if (pending.contains(i) || renderer == null) return;
        pending.add(i);
        final PdfRenderer r = renderer;
        final int gen = generation;
        worker.post(() -> {
            Bitmap bmp = null;
            if (gen == generation) {
                try {
                    synchronized (r) {
                        PdfRenderer.Page p = r.openPage(i);
                        int h = Math.max(1, Math.round(width * p.getHeight() / (float) p.getWidth()));
                        bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        p.close();
                    }
                } catch (Throwable t) {
                    bmp = null;
                }
            }
            final Bitmap res = bmp;
            ui.post(() -> {
                if (gen != generation) return;
                if (res != null) {   // in caso di errore resta "pending": niente tentativi infiniti
                    pending.remove(i);
                    cache.put(i, res);
                    invalidate();
                }
            });
        });
    }
}

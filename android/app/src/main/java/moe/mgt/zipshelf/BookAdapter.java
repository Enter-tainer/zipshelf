package moe.mgt.zipshelf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import moe.mgt.zipshelf.data.Book;
import moe.mgt.zipshelf.data.BookStore;
import moe.mgt.zipshelf.zip.ZipArchive;

/** 书架列表。封面按需解码并缓存；墨水屏上刻意只显示标题 + 一行次要信息。 */
final class BookAdapter extends BaseAdapter {

    private static final int COVER_MAX_PX = 256;
    private static final int FALLBACK_W = 300;
    private static final int FALLBACK_H = 400;

    private final LayoutInflater inflater;
    private final Map<String, Bitmap> covers = new HashMap<>();
    private List<Book> books = Collections.emptyList();

    BookAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    void setBooks(List<Book> books) {
        this.books = books == null ? Collections.<Book>emptyList() : books;
        // 清掉已经不存在的书的封面
        covers.keySet().retainAll(idsOf(this.books));
        notifyDataSetChanged();
    }

    private static java.util.Set<String> idsOf(List<Book> books) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Book b : books) {
            ids.add(b.id);
        }
        return ids;
    }

    @Override
    public int getCount() {
        return books.size();
    }

    @Override
    public Book getItem(int position) {
        return books.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_book, parent, false);
        }
        Book book = books.get(position);
        TextView title = view.findViewById(R.id.title);
        TextView meta = view.findViewById(R.id.meta);
        ImageView cover = view.findViewById(R.id.cover);

        title.setText(book.title());
        String info = BookStore.formatSize(book.sizeBytes) + " · " + book.fileCount + " 个文件";
        meta.setText(info);

        Bitmap bitmap = covers.get(book.id);
        if (bitmap == null) {
            bitmap = loadCover(book);
            if (bitmap == null) {
                // zip 里没封面：生成一张纯黑白标题卡，书架不留空
                bitmap = generateCover(book.title());
            }
            if (bitmap != null) {
                covers.put(book.id, bitmap);
            }
        }
        if (bitmap != null) {
            cover.setVisibility(View.VISIBLE);
            if (bitmap.getConfig() == Bitmap.Config.ARGB_8888 && book.coverEntry == null) {
                // 生成的 1-bit 封面：缩放时关掉双线性滤波，避免边缘又出现灰
                BitmapDrawable drawable = new BitmapDrawable(cover.getResources(), bitmap);
                drawable.setFilterBitmap(false);
                drawable.setAntiAlias(false);
                cover.setImageDrawable(drawable);
            } else {
                cover.setImageBitmap(bitmap);
            }
        } else {
            cover.setVisibility(View.GONE);
            cover.setImageDrawable(null);
        }
        return view;
    }

    /** 纯黑白兜底封面：白底、黑框、黑字标题，字号自动缩到放得下。 */
    private static Bitmap generateCover(String title) {
        Bitmap bitmap = Bitmap.createBitmap(FALLBACK_W, FALLBACK_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        Paint border = new Paint();
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(6f);
        border.setColor(Color.BLACK);
        canvas.drawRect(8f, 8f, FALLBACK_W - 8f, FALLBACK_H - 8f, border);

        String text = title == null || title.trim().isEmpty() ? "未命名" : title.trim();
        int pad = 30;
        int maxWidth = FALLBACK_W - pad * 2;
        int maxHeight = FALLBACK_H - pad * 2;
        StaticLayout layout = null;
        for (int size = 34; size >= 14; size -= 2) {
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTextSize(size);
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, maxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(8f, 1f)
                    .setIncludePad(false)
                    .build();
            if (layout.getHeight() <= maxHeight) {
                break;
            }
        }
        if (layout != null) {
            canvas.save();
            canvas.translate(pad, Math.max(pad, (FALLBACK_H - layout.getHeight()) / 2f));
            layout.draw(canvas);
            canvas.restore();
        }
        return toOneBit(bitmap);
    }

    /**
     * 阈值化成纯黑白：封面是「我们自己画的东西」，墨水屏上按 1-bit 呈现最清晰，
     * 也避免抗锯齿边缘出现灰度。
     */
    private static Bitmap toOneBit(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int luminance = (r * 299 + g * 587 + b * 114) / 1000;
            pixels[i] = luminance < 128 ? 0xFF000000 : 0xFFFFFFFF;
        }
        src.setPixels(pixels, 0, w, 0, 0, w, h);
        return src;
    }

    private static Bitmap loadCover(Book book) {
        try {
            ZipArchive archive = book.archive();
            ZipArchive.Entry entry = archive.get(book.coverEntry);
            if (entry == null) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = archive.openStream(entry)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= COVER_MAX_PX
                    || bounds.outHeight / (sample * 2) >= COVER_MAX_PX) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream in = archive.openStream(entry)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }
}

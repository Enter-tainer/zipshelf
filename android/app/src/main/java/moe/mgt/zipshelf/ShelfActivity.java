package moe.mgt.zipshelf;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import moe.mgt.zipshelf.data.Book;
import moe.mgt.zipshelf.data.BookStore;
import moe.mgt.zipshelf.data.ImportException;

/** 书架：导入 zip、列出书、点开→起服务→跳 EinkBro。 */
public final class ShelfActivity extends Activity {

    private static final int REQ_IMPORT = 100;
    private static final int REQ_NOTIF = 101;

    private BookStore store;
    private BookAdapter adapter;
    private ListView list;
    private TextView status;
    private TextView serviceBtn;
    private final AtomicBoolean working = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shelf);

        store = new BookStore(getFilesDir());
        adapter = new BookAdapter(this);
        list = findViewById(R.id.list);
        status = findViewById(R.id.status);
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> openBook(adapter.getItem(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showBookMenu(adapter.getItem(position));
            return true;
        });
        findViewById(R.id.import_btn).setOnClickListener(v -> pickZip());
        findViewById(R.id.inbox_btn).setOnClickListener(v -> importInbox(true));
        serviceBtn = findViewById(R.id.service_btn);
        serviceBtn.setOnClickListener(v -> toggleService());

        requestNotificationPermission();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        importInbox(false);
    }

    // ------------------------------------------------------------- 列表

    private void refresh() {
        List<Book> books = store.list();
        adapter.setBooks(books);
        ServerCore.setBooks(books);
        int port = ServerCore.port();
        boolean running = ServerCore.isRunning() && port > 0;
        if (running) {
            status.setText("服务运行中 · 端口 " + port + " · " + books.size() + " 本书");
        } else {
            status.setText("服务未启动 · " + books.size() + " 本书");
        }
        if (serviceBtn != null) {
            serviceBtn.setText(running ? "停止服务" : "启动服务");
        }
    }

    /** 手动开关服务：关掉后浏览器里已打开的页面会加载失败，重新点书即可再起。 */
    private void toggleService() {
        if (ServerCore.isRunning()) {
            stopService(new Intent(this, ServerService.class));
            ServerCore.stop();
            toast("服务已停止");
            refresh();
            return;
        }
        ServerCore.setBooks(store.list());
        ServerService.start(this);
        toast("正在启动服务…");
        new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < deadline && ServerCore.port() <= 0) {
                    Thread.sleep(50);
                }
                runOnUiThread(() -> {
                    int port = ServerCore.port();
                    toast(port > 0 ? "服务已启动 · 端口 " + port : "启动失败，看通知栏提示");
                    refresh();
                });
            } catch (InterruptedException ignored) {
            }
        }).start();
    }

    // ------------------------------------------------------------- 打开

    private void openBook(Book book) {
        if (book == null) {
            return;
        }
        ServerService.start(this);
        toast("正在启动本地服务…");

        new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < deadline && ServerCore.port() <= 0) {
                    Thread.sleep(50);
                }
                int port = ServerCore.port();
                if (port <= 0) {
                    runOnUiThread(() -> toast("本地服务启动失败，看通知栏的提示"));
                    return;
                }
                ServerCore.setBooks(store.list());
                store.touch(book);
                String url = "http://127.0.0.1:" + port + "/books/" + book.id + "/";
                runOnUiThread(() -> {
                    BrowserLauncher.open(this, url);
                    refresh();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("打开失败：" + e));
            }
        }).start();
    }

    private void showBookMenu(Book book) {
        if (book == null) {
            return;
        }
        String[] items = {"打开", "重命名", "复制链接", "删除"};
        showEink(new AlertDialog.Builder(this, R.style.EinkDialog)
                .setTitle(book.title())
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            openBook(book);
                            break;
                        case 1:
                            askRename(book);
                            break;
                        case 2:
                            copyLink(book);
                            break;
                        default:
                            askDelete(book);
                    }
                }));
    }

    /** 展示对话框，并去掉墨水屏上会变成灰的东西：背景压暗、窗口阴影。 */
    private void showEink(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0f);
            window.setBackgroundDrawableResource(R.drawable.eink_dialog_bg);
            window.getDecorView().setElevation(0f);
        }
        dialog.show();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.eink_dialog_bg);
        }
    }

    private void askRename(Book book) {
        EditText input = new EditText(this);
        input.setText(book.title());
        input.setSelection(input.getText().length());
        input.setBackgroundResource(R.drawable.eink_input);
        input.setTextColor(0xFF000000);
        showEink(new AlertDialog.Builder(this, R.style.EinkDialog)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        store.rename(book, input.getText().toString());
                        refresh();
                    } catch (Exception e) {
                        toast("重命名失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null));
    }

    private void askDelete(Book book) {
        showEink(new AlertDialog.Builder(this, R.style.EinkDialog)
                .setTitle("删除")
                .setMessage("删除《" + book.title() + "》？zip 文件会一并删除，不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    store.delete(book);
                    refresh();
                    toast("已删除");
                })
                .setNegativeButton("取消", null));
    }

    private void copyLink(Book book) {
        String url = ServerCore.bookUrl(book.id);
        if (url == null) {
            toast("服务未启动");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("url", url));
            toast(url);
        }
    }

    // ------------------------------------------------------------- 导入

    private void pickZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/zip", "application/x-zip-compressed", "application/octet-stream"
        });
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Exception e) {
            toast("没有文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        importUris(uris);
    }

    private void importUris(List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        toast("正在导入 " + uris.size() + " 个文件…");
        new Thread(() -> {
            int ok = 0;
            List<String> errors = new ArrayList<>();
            for (Uri uri : uris) {
                String name = queryDisplayName(uri);
                if (name == null) {
                    name = "导入的书";
                }
                if (name.toLowerCase().endsWith(".zip")) {
                    name = name.substring(0, name.length() - 4);
                }
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        throw new ImportException("读不到文件");
                    }
                    store.importStream(in, name);
                    ok++;
                } catch (ImportException e) {
                    errors.add(name + "：" + e.getMessage());
                } catch (Exception e) {
                    errors.add(name + "：" + e);
                }
            }
            int finalOk = ok;
            runOnUiThread(() -> {
                refresh();
                String msg = "导入成功 " + finalOk + " 本";
                if (!errors.isEmpty()) {
                    msg += "，失败 " + errors.size() + "：" + errors.get(0);
                }
                toast(msg);
            });
        }).start();
    }

    /**
     * 扫描 inbox 目录，把 zip 导进来再挪到 done/。
     *
     * 两个位置：
     *   1. {@code /sdcard/Android/data/<pkg>/files/inbox} —— 用文件管理器/数据线丢书
     *   2. {@code files/inbox}（应用私有）—— 自动化测试用 adb run-as 塞书
     */
    private void importInbox(boolean manual) {
        final List<File> dirs = new ArrayList<>();
        File external = getExternalFilesDir(null);
        if (external != null) {
            dirs.add(new File(external, "inbox"));
        }
        dirs.add(new File(getFilesDir(), "inbox"));

        final List<File> pending = new ArrayList<>();
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                // 由 App 自己创建，避免 owner 是 shell 导致读不了
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File[] zips;
            try {
                zips = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".zip"));
            } catch (SecurityException e) {
                zips = null;
            }
            if (zips != null) {
                for (File zip : zips) {
                    pending.add(zip);
                }
            }
        }

        if (pending.isEmpty()) {
            if (manual) {
                toast("inbox 里没有 zip");
            }
            return;
        }
        if (!working.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            int ok = 0;
            List<String> errors = new ArrayList<>();
            for (File zip : pending) {
                String name = zip.getName();
                if (name.toLowerCase().endsWith(".zip")) {
                    name = name.substring(0, name.length() - 4);
                }
                try {
                    store.importFile(zip, name);
                    ok++;
                    File done = new File(zip.getParentFile(), "done");
                    //noinspection ResultOfMethodCallIgnored
                    done.mkdirs();
                    //noinspection ResultOfMethodCallIgnored
                    zip.renameTo(new File(done, zip.getName()));
                } catch (ImportException e) {
                    errors.add(zip.getName() + "：" + e.getMessage());
                } catch (Exception e) {
                    errors.add(zip.getName() + "：" + e);
                }
            }
            int finalOk = ok;
            runOnUiThread(() -> {
                working.set(false);
                refresh();
                if (finalOk > 0 || !errors.isEmpty()) {
                    String msg = "inbox 导入 " + finalOk + " 本";
                    if (!errors.isEmpty()) {
                        msg += "，失败 " + errors.size() + "：" + errors.get(0);
                    }
                    toast(msg);
                }
            });
        }).start();
    }

    // ------------------------------------------------------------- 杂项

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        Uri data = intent.getData();
        if (data != null) {
            List<Uri> one = new ArrayList<>();
            one.add(data);
            importUris(one);
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}

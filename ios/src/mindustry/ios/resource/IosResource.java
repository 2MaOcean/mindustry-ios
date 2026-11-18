package mindustry.ios.resource;

import arc.*;
import arc.files.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.Styles;
import mindustry.core.Version;
import mindustry.ui.FileChooser;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.FileChooser.FileChooserParams;

import arc.Input.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.util.*;
import java.util.regex.*;

import static mindustry.Vars.*;

/** Shared helpers for the iOS resource center (file rows, backups, http, logs). */
public class IosResource{
    private static final DateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm");
    private static final DateFormat backupFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");

    //region http

    public static String httpGet(String url) throws Exception{
        return new String(httpGetBytes(url), StandardCharsets.UTF_8);
    }

    public static byte[] httpGetBytes(String url) throws Exception{
        for(int attempt = 0; attempt < 3; attempt++){
            try{
                HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "mindustry-ios-resource-center");
                int code = c.getResponseCode();
                if(code >= 400) throw new IOException("HTTP " + code);
                try(InputStream in = c.getInputStream()){
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return out.toByteArray();
                }finally{
                    c.disconnect();
                }
            }catch(Exception e){
                if(attempt == 2) throw e;
                Thread.sleep(1500);
            }
        }
        throw new IOException("unreachable");
    }

    public static List<?> parseArrayPublic(String json) throws Exception{
        return MindustryToolApi.parseArray(json);
    }

    public static Object field(Object obj, String key){
        return obj instanceof Map ? ((Map<?, ?>)obj).get(key) : null;
    }

    public static String str(Object o){
        return o == null ? "" : o.toString();
    }

    public static int intOf(Object o){
        try{
            return Integer.parseInt(str(o));
        }catch(Throwable t){
            return 0;
        }
    }

    //endregion

    //region rows

    public static Table fileRow(Fi f, boolean isSave, Runnable onChange){
        Table row = new Table(Tex.pane);
        row.table(info -> {
            info.add(f.nameWithoutExtension()).left().growX().wrap();
            info.add(gray(sizeText(f) + " · " + dateText(f))).right().color(Pal.gray).padLeft(8f);
        }).growX();

        if(isSave){
            row.button(Icon.pencil, Styles.clearNonei, () -> renameFile(f, dirOf(f), onChange))
                .size(52f).tooltip("重命名");
            row.button(Icon.download, Styles.clearNonei, () -> {
                IosResource.backupSave(f);
                ui.showInfo("已备份");
                if(onChange != null) onChange.run();
            }).size(52f).tooltip("备份");
        }
        row.button(Icon.upload, Styles.clearNonei, () -> share(f))
            .size(52f).tooltip("导出/分享");
        row.button(Icon.trash, Styles.clearNonei, () -> {
            ui.showConfirm("@confirm", "删除 " + f.nameWithoutExtension() + " ?", () -> {
                f.delete();
                if(onChange != null) onChange.run();
            });
        }).size(52f).tooltip("删除");
        return row;
    }

    public static Table backupRow(Fi f, Runnable onChange){
        Table row = new Table(Tex.pane);
        row.table(info -> {
            info.add("[gray]备份[/gray] " + restoreName(f)).left().growX().wrap();
            info.add(gray(sizeText(f) + " · " + dateText(f))).right().color(Pal.gray).padLeft(8f);
        }).growX();
        row.button(Icon.upload, Styles.clearNonei, () -> share(f)).size(52f).tooltip("导出");
        row.button(Icon.refresh, Styles.clearNonei, () -> {
            Fi target = saveDirectory.child(restoreName(f));
            ui.showConfirm("@confirm", "用该备份覆盖存档 " + target.nameWithoutExtension() + " ?", () -> {
                try{
                    f.copyTo(target);
                    ui.showInfo("已恢复");
                    if(onChange != null) onChange.run();
                }catch(Throwable t){
                    ui.showException(t);
                }
            });
        }).size(52f).tooltip("恢复到此存档");
        row.button(Icon.trash, Styles.clearNonei, () -> {
            ui.showConfirm("@confirm", "删除该备份?", () -> {
                f.delete();
                if(onChange != null) onChange.run();
            });
        }).size(52f).tooltip("删除备份");
        return row;
    }

    public static Table modRow(Fi f, Runnable onChange){
        String fileName = f.nameWithoutExtension();
        String metaName = modMetaName(f);
        Table row = new Table(Tex.pane);
        row.table(info -> {
            info.add(fileName).left().growX().wrap();
            info.add(gray(sizeText(f) + (metaName.equals(fileName) ? "" : " · id: " + metaName))).right().color(Pal.gray).padLeft(8f);
        }).growX();

        CheckBox box = new CheckBox("启用");
        box.setProgrammaticChangeEvents(false);
        box.setChecked(Core.settings.getBool("mod-" + metaName + "-enabled", true));
        box.changed(() -> {
            Core.settings.put("mod-" + metaName + "-enabled", box.isChecked());
            ui.showInfo("已保存，重启游戏后生效");
        });
        row.add(box).padLeft(6f);

        row.button(Icon.upload, Styles.clearNonei, () -> share(f)).size(52f).tooltip("导出");
        row.button(Icon.trash, Styles.clearNonei, () -> {
            ui.showConfirm("@confirm", "删除模组 " + fileName + " ?", () -> {
                f.delete();
                if(onChange != null) onChange.run();
            });
        }).size(52f).tooltip("删除");
        return row;
    }

    public static Table modIndexRow(Map<String, Object> m, Runnable onChange){
        String repo = str(field(m, "repo"));
        String name = str(field(m, "name"));
        int stars = intOf(field(m, "stars"));
        String minGame = str(field(m, "minGameVersion"));
        boolean hasJava = Boolean.parseBoolean(str(field(m, "hasJava")));
        Table row = new Table(Tex.pane);
        row.table(info -> {
            info.add(name.isEmpty() ? repo : name).left().growX().wrap();
            info.add(gray("★" + stars + " · v" + minGame + (hasJava ? " · Java" : " · JS"))).right().color(Pal.gray).padLeft(8f);
        }).growX();
        row.button(Icon.download, Styles.clearNonei, () -> {
            ui.loadfrag.show("安装 " + repo + "…");
            Threads.thread(() -> {
                try{
                    installGithubMod(repo, hasJava);
                    Core.app.post(() -> {
                        ui.loadfrag.hide();
                        ui.showInfo("已安装到模组目录" + (hasJava ? "（Java 模组需构建时打包才能运行）" : "，重启游戏后生效"));
                        if(onChange != null) onChange.run();
                    });
                }catch(Throwable t){
                    Core.app.post(() -> {
                        ui.loadfrag.hide();
                        ui.showException(t);
                    });
                }
            });
        }).size(52f).tooltip("下载安装");
        return row;
    }

    //endregion

    //region actions

    public static void backupSave(Fi f){
        try{
            backupsDir().mkdirs();
            String stamp = backupFormat.format(new Date());
            f.copyTo(backupsDir().child(f.nameWithoutExtension() + "." + stamp + ".msav"));
        }catch(Throwable t){
            ui.showException(t);
        }
    }

    public static String restoreName(Fi backup){
        String name = backup.nameWithoutExtension();
        int idx = name.lastIndexOf('.');
        if(idx > 0 && name.length() - idx == 16 && name.matches(".*\\d{8}-\\d{6}$")){
            return name.substring(0, idx);
        }
        return name;
    }

    public static Fi backupsDir(){
        return dataDirectory.child("backups/");
    }

    public static void renameFile(Fi f, Fi dir, Runnable onChange){
        TextInput input = new TextInput();
        input.title = "重命名";
        input.text = f.nameWithoutExtension();
        input.accepted = name -> {
            if(name.isEmpty()) return;
            Fi target = dir.child(name + "." + f.extension());
            if(target.exists()){
                ui.showInfo("同名文件已存在");
                return;
            }
            try{
                f.copyTo(target);
                f.delete();
                if(onChange != null) onChange.run();
            }catch(Throwable t){
                ui.showException(t);
            }
        };
        Core.input.getTextInput(input);
    }

    public static void importTo(Fi dir, Runnable onChange, String... extensions){
        FileChooserParams params = FileChooser.open(extensions);
        params.title = "导入";
        params.submitMulti(files -> {
            try{
                for(Fi src : files){
                    src.copyTo(dir.child(src.name()));
                }
                ui.showInfo("已导入 " + files.length + " 个文件");
                if(onChange != null) onChange.run();
            }catch(Throwable t){
                ui.showException(t);
            }
        });
    }

    public static void share(Fi f){
        try{
            platform.shareFile(f);
        }catch(Throwable t){
            ui.showException(t);
        }
    }

    public static void installGithubMod(String repo, boolean hasJava) throws Exception{
        String defaultBranch = "main";
        String language = "";
        boolean fetched = false;
        for(String u : new String[]{
            "https://api.github.com/repos/" + repo,
            "https://gh-proxy.com/https://api.github.com/repos/" + repo}){
            try{
                String json = httpGet(u);
                defaultBranch = extractJsonString(json, "default_branch", "main");
                language = extractJsonString(json, "language", "");
                fetched = true;
                break;
            }catch(Throwable t){
                //try next
            }
        }
        if(!fetched) throw new IOException("无法访问 GitHub API");

        if(!hasJava && (language.equals("Java") || language.equals("Kotlin") || language.equals("Groovy") || language.equals("Scala"))){
            hasJava = true;
        }

        String zipUrl = "https://gh-proxy.com/https://github.com/" + repo + "/archive/refs/heads/" + defaultBranch + ".zip";
        byte[] data = httpGetBytes(zipUrl);
        Fi target = modDirectory.child(repo.substring(repo.indexOf('/') + 1) + ".zip");
        target.writeBytes(data, false);
        if(hasJava){
            Log.info("[resource] java mod downloaded (needs rebuild): " + target.name());
        }
    }

    //endregion

    //region meta & listing

    public static String modMetaName(Fi f){
        try{
            for(String cand : new String[]{"mod.json", "mod.hjson"}){
                try{
                    Fi meta = f.child(cand);
                    if(meta.exists()){
                        String s = meta.readString();
                        Matcher m = Pattern.compile("(?im)^\\s*\"?name\"?\\s*[:=]\\s*\"?([A-Za-z0-9_\\-.]+)").matcher(s);
                        if(m.find()) return m.group(1);
                    }
                }catch(Throwable ignored){
                }
            }
        }catch(Throwable ignored){
        }
        return f.nameWithoutExtension();
    }

    public static Fi[] listFiles(Fi dir, String... exts){
        if(!dir.exists()) return new Fi[0];
        Seq<Fi> out = new Seq<>();
        for(Fi f : dir.list()){
            if(!f.isDirectory()){
                for(String e : exts){
                    if(f.extEquals(e)){
                        out.add(f);
                        break;
                    }
                }
            }
        }
        out.sortComparing(f -> -f.lastModified());
        return out.toArray(Fi.class);
    }

    public static int countFiles(Fi dir, String... exts){
        return listFiles(dir, exts).length;
    }

    public static void deleteContents(Fi dir){
        if(!dir.exists()) return;
        for(Fi f : dir.list()){
            f.deleteDirectory();
        }
    }

    public static String sanitize(String name){
        return name == null ? "file" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    //endregion

    //region updates & logs

    public static void checkUpdates(){
        ui.loadfrag.show("@loading");
        Http.get("https://api.github.com/repos/2MaOcean/mindustry-ios/releases/tags/latest-ipa", res -> {
            ui.loadfrag.hide();
            try{
                String json = res.getResultAsString();
                Matcher m = Pattern.compile("\"updated_at\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                String updated = m.find() ? m.group(1).replace('T', ' ').replace('Z', ' ').substring(0, 16) : "?";
                Matcher mn = Pattern.compile("\"name\"\\s*:\\s*\"(Mindustry-iOS[^\"]*)\"").matcher(json);
                String asset = mn.find() ? mn.group(1) : "(ipa)";
                ui.showInfo("[accent]最新构建：[] " + asset + "\n发布时间(UTC)： " + updated +
                    "\n本地版本： " + Version.buildString() +
                    "\n\n若为更新版本，请从仓库 Release 页下载新 IPA 重新签名安装。");
            }catch(Throwable t){
                ui.showException(t);
            }
        }, err -> {
            ui.loadfrag.hide();
            ui.showException(err);
        });
    }

    public static void showLogs(){
        Fi logFile = dataDirectory.child("last_log.txt");
        String text;
        if(logFile.exists()){
            try{
                byte[] all = logFile.readBytes();
                int from = Math.max(0, all.length - 32 * 1024);
                text = new String(all, from, all.length - from, StandardCharsets.UTF_8);
                if(from > 0) text = "[gray]（仅显示最后 32KB，完整日志可分享）\n\n[/gray]" + text;
            }catch(Throwable t){
                text = "读取失败: " + t;
            }
        }else{
            text = "日志文件不存在（首次启动尚未生成）";
        }

        BaseDialog dialog = new BaseDialog("运行日志");
        final String displayText = text;
        dialog.cont.pane(p -> p.add(displayText).wrap().left().growX()).grow();
        dialog.buttons.defaults().height(56f).pad(4f);
        if(logFile.exists()){
            dialog.buttons.button(b -> {
                b.image(Icon.upload);
                b.add(" 分享完整日志");
            }, () -> share(logFile));
        }
        dialog.buttons.button("@close", dialog::hide);
        dialog.show();
    }

    public static String lastErrorLine(){
        try{
            Fi logFile = dataDirectory.child("last_log.txt");
            if(!logFile.exists()) return "[lightgray]（无日志）[]";
            byte[] all = logFile.readBytes();
            String tail = new String(all, Math.max(0, all.length - 64 * 1024), Math.min(all.length, 64 * 1024), StandardCharsets.UTF_8);
            String[] lines = tail.split("\n");
            for(int i = lines.length - 1; i >= 0; i--){
                String l = lines[i].trim();
                if(l.startsWith("[E]") || l.startsWith("[W]")){
                    return "[scarlet]" + (l.length() > 110 ? l.substring(0, 110) + "…" : l) + "[]";
                }
            }
            return "[lightgray]（日志中无错误行）[]";
        }catch(Throwable t){
            return "[lightgray]（日志读取失败）[]";
        }
    }

    //endregion

    //region misc

    public static void dirStat(Table g, String name, Fi dir){
        long[] stat = dirStat(dir);
        g.add(name).left().growX();
        g.add("[gray]" + stat[0] + " 个文件 · " + formatSize(stat[1]) + "[/gray]").right().row();
    }

    public static long[] dirStat(Fi dir){
        long[] out = new long[2];
        if(dir.exists()) statRecursive(dir, out, 0);
        return out;
    }

    private static void statRecursive(Fi dir, long[] out, int depth){
        if(depth > 4) return;
        for(Fi f : dir.list()){
            if(f.isDirectory()){
                statRecursive(f, out, depth + 1);
            }else{
                out[0]++;
                out[1] += f.length();
            }
        }
    }

    public static void renderList(Table content, Fi[] files, Func<Fi, Table> rowBuilder, Runnable expand, int limit){
        int n = Math.min(files.length, limit);
        for(int i = 0; i < n; i++){
            content.add(rowBuilder.get(files[i])).pad(4f).growX().row();
        }
        if(files.length > n){
            content.button("显示全部 " + files.length + " 项", () -> {
                expand.run();
            }).pad(8f).height(52f).growX().row();
        }
    }

    public static Table section(String text){
        Table t = new Table();
        t.add(text).color(Pal.accent).fontScale(1.05f).left().padTop(8f).padBottom(6f);
        t.row();
        t.image().color(Pal.accent).height(3f).growX();
        return t;
    }

    public static Label gray(String text){
        return new Label(text, Styles.outlineLabel);
    }

    public static String formatSize(long bytes){
        if(bytes < 1024) return bytes + " B";
        if(bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024f);
        return String.format("%.1f MB", bytes / 1024f / 1024f);
    }

    public static String dateText(Fi f){
        return dateFormat.format(new Date(f.lastModified()));
    }

    public static String sizeText(Fi f){
        return formatSize(f.length());
    }

    public static String extractJsonString(String json, String key, String def){
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : def;
    }

    private static Fi dirOf(Fi f){
        String abs = f.absolutePath();
        if(abs.startsWith(modDirectory.absolutePath())) return modDirectory;
        if(abs.startsWith(customMapDirectory.absolutePath())) return customMapDirectory;
        return saveDirectory;
    }

    //endregion
}

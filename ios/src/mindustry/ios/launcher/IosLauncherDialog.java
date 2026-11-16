package mindustry.ios.launcher;

import arc.*;
import arc.Input.*;
import arc.util.Http;
import arc.util.Http.HttpResponse;
import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.Version;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.gen.*;
import mindustry.ui.Styles;
import mindustry.graphics.*;
import mindustry.ui.FileChooser;
import mindustry.ui.FileChooser.*;
import mindustry.ui.fragments.MenuFragment.*;

import java.text.*;
import java.util.Date;

import static mindustry.Vars.*;

/**
 * iOS port: in-game launcher screen shown before the main menu.
 * Provides file-level management for saves, mods and maps, plus storage info.
 * All original game features are untouched; this is purely an additional layer.
 */
public class IosLauncherDialog{
    private static final String enabledKey = "ioslauncher-enabled";
    private static final DateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm");
    private static final DateFormat backupFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private static boolean shown;
    private static int page;
    /** max rows rendered per file list; keeps UI tree small for large collections */
    private static final int initialListLimit = 60;
    private static int listLimit = initialListLimit;
    private static Table root, content;

    private static final String[] pageNames = {"存档", "模组", "地图", "蓝图", "信息"};

    //region lifecycle

    public static boolean isEnabled(){
        return Core.settings.getBool(enabledKey, true);
    }

    /** Called after the game has loaded; shows the launcher unless disabled. */
    public static void maybeShow(){
        if(!shown && isEnabled()){
            Core.app.post(IosLauncherDialog::show);
        }
    }

    /** Toggled by the main menu button. */
    public static void toggle(){
        if(shown){
            hide();
        }else{
            show();
        }
    }

    public static void show(){
        if(shown){
            rebuild();
            return;
        }
        shown = true;
        page = 0;
        build();
        Core.scene.add(root);
    }

    public static void hide(){
        if(root != null){
            root.remove();
        }
        //drop all static references so the entire UI tree can be garbage collected
        //after the game starts - the launcher keeps zero persistent allocation.
        root = null;
        content = null;
        shown = false;
        listLimit = initialListLimit;
    }

    //endregion

    //region ui build

    private static void build(){
        root = new Table(t -> {
            //dim background
            t.image(Tex.whiteui, new Color(0, 0, 0, 0.85f)).grow();
            t.row();

            t.table(main -> {
                main.top().table(head -> {
                    head.table(title -> {
                        title.add("Mindustry 启动器").fontScale(1.1f).pad(6f);
                        title.label(() -> "v" + Version.buildString()).color(Pal.gray).padRight(8f);
                    }).left().growX();

                    head.button(b -> {
                        b.image(Icon.play).scaling(Scaling.fit);
                        b.add(" 启动游戏").fontScale(1.1f);
                    }, Styles.flatTogglet, IosLauncherDialog::hide).pad(6f).minWidth(200f).height(70f);
                }).growX().row();
            }).growX().top().pad(10f).row();

            t.table(tabs -> {
                for(int i = 0; i < pageNames.length; i++){
                    int idx = i;
                    TextButton b = tabs.button(pageNames[i], Styles.flatToggleMenut, () -> {
                        page = idx;
                        rebuild();
                    }).growX().height(64f).get();
                    b.setChecked(page == idx);
                    tabs.update(() -> b.setChecked(page == idx));
                }
            }).growX().pad(10f).row();

            t.pane(st -> content = st).grow().pad(10f).row();
        });

        root.setFillParent(true);
        rebuild();
    }

    private static void rebuild(){
        if(content == null) return;
        content.clearChildren();
        content.top().left().defaults().growX().left();
        switch(page){
            case 0 -> buildSaves();
            case 1 -> buildMods();
            case 2 -> buildMaps();
            case 3 -> buildSchems();
            case 4 -> buildInfo();
        }
    }

    //endregion

    //region saves

    private static void buildSaves(){
        Fi dir = saveDirectory;
        content.add(section("存档文件 (" + countFiles(dir, "msav") + ")")).row();
        content.button(b -> {
            b.image(Icon.add);
            b.add(" 导入存档 (.msav)");
        }, Styles.cleart, () -> importTo(dir, "msav")).pad(6f).height(56f).growX().row();

        Fi[] saves = listFiles(dir, "msav");
        if(saves.length == 0){
            content.add(gray("暂无存档，开始一局游戏后即可在此管理。")).row();
        }
        renderList(saves, f -> fileRow(f, true));

        content.row();
        content.add(section("备份 (" + countFiles(backupsDir(), "msav") + ")")).padTop(16f).row();
        content.button(b -> b.add(" 备份全部存档"), Styles.cleart, () -> {
            Fi[] all = listFiles(dir, "msav");
            if(all.length == 0){
                ui.showInfo("没有可备份的存档");
                return;
            }
            for(Fi f : all){
                backupSave(f);
            }
            ui.showInfo("已备份 " + all.length + " 个存档");
            rebuild();
        }).pad(6f).height(56f).row();

        Fi[] backups = listFiles(backupsDir(), "msav");
        if(backups.length == 0){
            content.add(gray("备份会保存在数据目录的 backups/ 下，可用于恢复。")).row();
        }
        renderList(backups, IosLauncherDialog::backupRow);
    }

    private static Table fileRow(Fi f, boolean isSave){
        Table row = new Table(Tex.pane);
        row.table(info -> {
            info.add(f.nameWithoutExtension()).left().growX().wrap();
            info.add(gray(sizeText(f) + " · " + dateText(f))).right().color(Pal.gray).padLeft(8f);
        }).growX();

        if(isSave){
            row.button(Icon.pencil, Styles.clearNonei, () -> renameFile(f, dirOf(f)))
                .size(52f).tooltip("重命名");
            row.button(Icon.download, Styles.clearNonei, () -> {
                backupSave(f);
                ui.showInfo("已备份");
                rebuild();
            }).size(52f).tooltip("备份");
        }
        row.button(Icon.upload, Styles.clearNonei, () -> share(f))
            .size(52f).tooltip("导出/分享");
        row.button(Icon.trash, Styles.clearNonei, () -> {
            ui.showConfirm("@confirm", "删除 " + f.nameWithoutExtension() + " ?", () -> {
                f.delete();
                rebuild();
            });
        }).size(52f).tooltip("删除");
        return row;
    }

    private static Table backupRow(Fi f){
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
                    rebuild();
                }catch(Throwable t){
                    ui.showException(t);
                }
            });
        }).size(52f).tooltip("恢复到此存档");
        row.button(Icon.trash, Styles.clearNonei, () -> {
            ui.showConfirm("@confirm", "删除该备份?", () -> {
                f.delete();
                rebuild();
            });
        }).size(52f).tooltip("删除备份");
        return row;
    }

    private static void backupSave(Fi f){
        try{
            backupsDir().mkdirs();
            String stamp = backupFormat.format(new Date());
            f.copyTo(backupsDir().child(f.nameWithoutExtension() + "." + stamp + ".msav"));
        }catch(Throwable t){
            ui.showException(t);
        }
    }

    /** name.20261115-183000 -> name */
    private static String restoreName(Fi backup){
        String name = backup.nameWithoutExtension(); // e.g. "slot1.20261115-183000"
        int idx = name.lastIndexOf('.');
        if(idx > 0 && name.length() - idx == 16 && name.matches(".*\\d{8}-\\d{6}$")){
            return name.substring(0, idx);
        }
        return name;
    }

    private static Fi backupsDir(){
        return dataDirectory.child("backups/");
    }

    private static void renameFile(Fi f, Fi dir){
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
                rebuild();
            }catch(Throwable t){
                ui.showException(t);
            }
        };
        Core.input.getTextInput(input);
    }

    //endregion

    //region mods

    private static void buildMods(){
        content.add(section("模组文件 (" + countModFiles() + ")")).row();
        content.button(b -> {
            b.image(Icon.add);
            b.add(" 导入模组 (.zip / .jar)");
        }, Styles.cleart, () -> importTo(modDirectory, "zip", "jar")).pad(6f).height(56f).growX().row();

        content.add(gray("启用/禁用后重启游戏生效。Java 模组需在构建时打包（见仓库说明）。")).row();

        Fi[] mods = listFiles(modDirectory, "zip", "jar");
        if(mods.length == 0){
            content.add(gray("暂无模组文件。")).row();
        }
        renderList(mods, IosLauncherDialog::modRow);
    }

    private static void buildSchems(){
        content.add(section("蓝图 (" + countFiles(schematicDirectory, "msch", "mschx") + ")")).row();
        content.button(b -> {
            b.image(Icon.add);
            b.add(" 导入蓝图 (.msch / .mschx)");
        }, Styles.cleart, () -> importTo(schematicDirectory, "msch", "mschx")).pad(6f).height(56f).growX().row();

        Fi[] schems = listFiles(schematicDirectory, "msch", "mschx");
        if(schems.length == 0){
            content.add(gray("暂无自定义蓝图。可在游戏内蓝图编辑器制作，或从其他设备导入。")).row();
        }
        for(Fi f : schems){
            content.add(fileRow(f, false)).row();
        }
    }

    private static Table modRow(Fi f){
        String fileName = f.nameWithoutExtension();
        //the game keys enabled-state by the name inside mod.json/mod.hjson, not the file name
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
                rebuild();
            });
        }).size(52f).tooltip("删除");
        return row;
    }

    //endregion

    //region maps

    private static void buildMaps(){
        content.add(section("自定义地图 (" + countFiles(customMapDirectory, "msav") + ")")).row();
        content.button(b -> {
            b.image(Icon.add);
            b.add(" 导入地图 (.msav)");
        }, Styles.cleart, () -> importTo(customMapDirectory, "msav")).pad(6f).height(56f).growX().row();

        Fi[] maps = listFiles(customMapDirectory, "msav");
        if(maps.length == 0){
            content.add(gray("暂无自定义地图。地图可在游戏内编辑器制作或从其他设备导入。")).row();
        }
        renderList(maps, f -> fileRow(f, false));
    }

    //endregion

    //region info

    private static void buildInfo(){
        content.add(section("信息")).row();
        content.table(g -> {
            g.left();
            g.add("游戏版本").left().growX();
            g.add("[gray]" + Version.buildString() + "[/gray]").right().row();
            g.add("当前内存占用").left().growX();
            g.add("[gray]" + formatSize(Core.app.getJavaHeap()) + "[/gray]").right().row();
            g.add("数据目录").left().growX();
            g.add("[gray]" + shortPath(dataDirectory) + "[/gray]").right().row();
        }).pad(6f).growX().row();

        content.add(section("存储用量")).padTop(14f).row();
        content.table(g -> {
            g.left();
            dirStat(g, "存档", saveDirectory);
            dirStat(g, "模组", modDirectory);
            dirStat(g, "地图", customMapDirectory);
            dirStat(g, "蓝图", schematicDirectory);
            dirStat(g, "备份", backupsDir());
        }).pad(6f).growX().row();

        content.add(section("维护")).padTop(14f).row();
        content.table(g -> {
            g.button(b -> {
                b.image(Icon.trash);
                b.add(" 清理临时缓存");
            }, Styles.cleart, () -> {
                ui.showConfirm("@confirm", "清理临时文件与地图预览缓存?", () -> {
                    deleteContents(tmpDirectory);
                    deleteContents(mapPreviewDirectory);
                    deleteContents(dataDirectory.child("assetCache"));
                    ui.showInfo("已清理");
                    rebuild();
                });
            }).growX().height(56f).pad(4f);
        }).growX().row();

        content.table(g -> {
            CheckBox box = new CheckBox("跳过启动器，下次启动直接进入游戏");
            box.setProgrammaticChangeEvents(false);
            box.setChecked(!isEnabled());
            box.changed(() -> Core.settings.put(enabledKey, !box.isChecked()));
            g.add(box).left().pad(10f);
        }).pad(6f).growX().row();

        content.add(gray("本启动器由 Mindustry iOS 移植项目提供，游戏全部功能保持不变。")).pad(10f).row();
    }

    private static void dirStat(Table g, String name, Fi dir){
        long[] stat = dirStat(dir);
        g.add(name).left().growX();
        g.add("[gray]" + stat[0] + " 个文件 · " + formatSize(stat[1]) + "[/gray]").right().row();
    }

    //endregion

    /** renders at most listLimit rows, with a "show all" expansion button */
    private static void renderList(Fi[] files, Func<Fi, Table> rowBuilder){
        int limit = Math.min(files.length, listLimit);
        for(int i = 0; i < limit; i++){
            content.add(rowBuilder.get(files[i])).row();
        }
        if(files.length > limit){
            content.button("显示全部 " + files.length + " 项", () -> {
                listLimit = Integer.MAX_VALUE;
                rebuild();
            }).pad(8f).height(52f).growX().row();
        }
    }

    /** extracts the most recent error line from the run log, for quick diagnosis */
    private static String lastErrorLine(){
        try{
            Fi logFile = dataDirectory.child("last_log.txt");
            if(!logFile.exists()) return "[lightgray]（无日志）[]";
            byte[] all = logFile.readBytes();
            String tail = new String(all, Math.max(0, all.length - 64 * 1024), Math.min(all.length, 64 * 1024), java.nio.charset.StandardCharsets.UTF_8);
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

    //region updates

    private static void checkUpdates(){
        ui.loadfrag.show("@loading");
        Http.get("https://api.github.com/repos/2MaOcean/mindustry-ios/releases/tags/latest-ipa", res -> {
            ui.loadfrag.hide();
            try{
                String json = res.getResultAsString();
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"updated_at\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                String updated = m.find() ? m.group(1).replace('T', ' ').replace('Z', ' ').substring(0, 16) : "?";
                java.util.regex.Matcher mn = java.util.regex.Pattern
                    .compile("\"name\"\\s*:\\s*\"(Mindustry-iOS[^\"]*)\"").matcher(json);
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

    //endregion

    //region logs

    private static void showLogs(){
        Fi logFile = dataDirectory.child("last_log.txt");
        String text;
        if(logFile.exists()){
            try{
                byte[] all = logFile.readBytes();
                int from = Math.max(0, all.length - 32 * 1024);
                text = new String(all, from, all.length - from, java.nio.charset.StandardCharsets.UTF_8);
                if(from > 0) text = "[gray]（仅显示最后 32KB，完整日志可分享）\n\n[/gray]" + text;
            }catch(Throwable t){
                text = "读取失败: " + t;
            }
        }else{
            text = "日志文件不存在（首次启动尚未生成）";
        }

        final String displayText = text;
        BaseDialog dialog = new BaseDialog("运行日志");
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

    //endregion

    /** Reads the mod's own name from mod.json / mod.hjson inside the container. */
    private static String modMetaName(Fi f){
        try{
            for(String cand : new String[]{"mod.json", "mod.hjson"}){
                try{
                    Fi meta = f.child(cand);
                    if(meta.exists()){
                        String s = meta.readString();
                        java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("(?im)^\\s*\"?name\"?\\s*[:=]\\s*\"?([A-Za-z0-9_\\-.]+)").matcher(s);
                        if(m.find()) return m.group(1);
                    }
                }catch(Throwable ignored){
                }
            }
        }catch(Throwable ignored){
        }
        return f.nameWithoutExtension();
    }

    //region file utils

    private static void importTo(Fi dir, String... extensions){
        FileChooserParams params = FileChooser.open(extensions);
        params.title = "导入";
        params.submitMulti(files -> {
            try{
                for(Fi src : files){
                    Fi target = dir.child(src.name());
                    src.copyTo(target);
                }
                ui.showInfo("已导入 " + files.length + " 个文件");
                page = pageOf(dir);
                rebuild();
            }catch(Throwable t){
                ui.showException(t);
            }
        });
    }

    private static int pageOf(Fi dir){
        if(dir.equals(modDirectory)) return 1;
        if(dir.equals(customMapDirectory)) return 2;
        if(dir.equals(schematicDirectory)) return 3;
        return 0;
    }

    private static void share(Fi f){
        try{
            platform.shareFile(f);
        }catch(Throwable t){
            ui.showException(t);
        }
    }

    private static Fi dirOf(Fi f){
        String abs = f.absolutePath();
        if(abs.startsWith(modDirectory.absolutePath())) return modDirectory;
        if(abs.startsWith(customMapDirectory.absolutePath())) return customMapDirectory;
        return saveDirectory;
    }

    private static Fi[] listFiles(Fi dir, String... exts){
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

    private static int countFiles(Fi dir, String... exts){
        return listFiles(dir, exts).length;
    }

    private static int countModFiles(){
        return countFiles(modDirectory, "zip", "jar");
    }

    /** {count, bytes} */
    private static long[] dirStat(Fi dir){
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

    private static void deleteContents(Fi dir){
        if(!dir.exists()) return;
        for(Fi f : dir.list()){
            f.deleteDirectory();
        }
    }

    //endregion

    //region text helpers

    private static Table section(String text){
        Table t = new Table();
        t.add(text).color(Pal.accent).fontScale(1.05f).left().padTop(8f).padBottom(6f);
        t.row();
        t.image().color(Pal.accent).height(3f).growX();
        return t;
    }

    private static Label gray(String text){
        return new Label(text, Styles.outlineLabel);
    }

    private static String sizeText(Fi f){
        return formatSize(f.length());
    }

    private static String formatSize(long bytes){
        if(bytes < 1024) return bytes + " B";
        if(bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024f);
        return String.format("%.1f MB", bytes / 1024f / 1024f);
    }

    private static String dateText(Fi f){
        return dateFormat.format(new Date(f.lastModified()));
    }

    private static String shortPath(Fi f){
        String p = f.absolutePath();
        int i = p.lastIndexOf("Mindustry/");
        return i >= 0 ? p.substring(i) : p;
    }

    //endregion
}

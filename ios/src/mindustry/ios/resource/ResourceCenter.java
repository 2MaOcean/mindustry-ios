package mindustry.ios.resource;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.func.*;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.core.Version;
import mindustry.graphics.*;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.FileChooser.FileChooserParams;
import mindustry.ui.fragments.MenuFragment.MenuButton;
import mindustry.ios.resource.MindustryToolApi.Item;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.util.*;
import java.util.regex.*;

import static mindustry.Vars.*;

/**
 * iOS resource center: browse & download schematics/maps/mods from online
 * sources (MindustryTool API, official mod index), plus local file management
 * for saves/mods/maps/schematics. Replaces the old launcher screen.
 */
public class ResourceCenter{
    private static final DateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm");
    private static final DateFormat backupFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final int initialListLimit = 60;

    private static boolean shown;
    private static int page; // 0 schematics, 1 maps, 2 mods, 3 saves, 4 info
    private static Table root, content;
    private static final int[] listLimit = {initialListLimit};

    private static final String[] pageNames = {"蓝图", "地图", "模组", "存档", "信息"};

    //region lifecycle

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
        listLimit[0] = initialListLimit;
        build();
        Core.scene.add(root);
    }

    public static void hide(){
        if(root != null){
            root.remove();
        }
        root = null;
        content = null;
        shown = false;
        listLimit[0] = initialListLimit;
    }

    //endregion

    //region ui build

    private static void build(){
        root = new Table(t -> {
            t.image(Tex.whiteui, new Color(0, 0, 0, 0.88f)).grow();
            t.row();

            t.table(tabs -> {
                for(int i = 0; i < pageNames.length; i++){
                    int idx = i;
                    TextButton b = tabs.button(pageNames[i], Styles.flatToggleMenut, () -> {
                        page = idx;
                        listLimit[0] = initialListLimit;
                        rebuild();
                    }).growX().height(64f).get();
                    b.update(() -> b.setChecked(page == idx));
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
            case 0 -> buildSchematicsPage();
            case 1 -> buildMapsPage();
            case 2 -> buildModsPage();
            case 3 -> buildSavesPage();
            case 4 -> buildInfoPage();
        }
    }

    //endregion

    //region online schematics & maps pages

    private static void buildSchematicsPage(){
        buildContentPage("蓝图", "schematics", schematicDirectory, "msch", "mschx");
    }

    private static void buildMapsPage(){
        buildContentPage("地图", "maps", customMapDirectory, "msav");
    }

    /** schematic/map page: online browsing on top, local files below */
    private static void buildContentPage(String title, String kind, Fi localDir, String... localExts){
        content.add(section(title + " - 在线获取 (MindustryTool)")).row();
        Table online = new Table();
        content.add(online).growX().row();
        loadOnline(kind, online);

        content.add(section(title + " - 本地 (" + IosResource.countFiles(localDir, localExts) + ")")).padTop(16f).row();
        content.button(b -> {
            b.image(Icon.add);
            b.add(" 导入" + title);
        }, Styles.cleart, () -> IosResource.importTo(localDir, ResourceCenter::rebuild, localExts)).pad(6f).height(56f).growX().row();

        Fi[] files = IosResource.listFiles(localDir, localExts);
        if(files.length == 0){
            content.add(gray("暂无本地文件。")).row();
        }
        for(Fi f : files){
            content.add(IosResource.fileRow(f, false, ResourceCenter::rebuild)).pad(4f).growX().row();
        }

        if(page == 0){
            content.add(section("备份")).padTop(16f).row();
            content.button(b -> {
                b.image(Icon.download);
                b.add(" 备份全部存档");
            }, Styles.cleart, () -> {
                Fi[] all = IosResource.listFiles(saveDirectory, "msav");
                if(all.length == 0){
                    ui.showInfo("没有可备份的存档");
                    return;
                }
                for(Fi f : all){
                    IosResource.backupSave(f);
                }
                ui.showInfo("已备份 " + all.length + " 个存档");
                rebuild();
            }).pad(6f).height(56f).growX().row();
            Fi[] backups = IosResource.listFiles(IosResource.backupsDir(), "msav");
            for(Fi f : backups){
                content.add(IosResource.backupRow(f, ResourceCenter::rebuild)).pad(4f).growX().row();
            }
        }
    }

    /** loads the MindustryTool online list into a section table */
    private static void loadOnline(String kind, Table section){
        section.add(gray("加载中…")).pad(8f).row();
        Threads.thread(() -> {
            String error = null;
            Item[] items = new Item[0];
            try{
                List<Item> list = MindustryToolApi.search(kind, 0, 40, "hot", null);
                items = list.toArray(new Item[0]);
            }catch(Throwable t){
                error = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final Item[] fItems = items;
            final String fError = error;
            Core.app.post(() -> {
                section.clearChildren();
                if(fError != null){
                    section.add(red("获取失败: " + fError)).pad(6f).growX().wrap().row();
                    section.add(gray("需要可访问 api.mindustry-tool.com 的网络。")).pad(4f).row();
                    return;
                }
                if(fItems.length == 0){
                    section.add(gray("暂无内容。")).row();
                    return;
                }
                for(Item it : fItems){
                    section.add(onlineRow(kind, it)).pad(4f).growX().row();
                }
                section.button(b -> {
                    b.image(Icon.link);
                    b.add(" 在 MindustryTool 网站浏览更多");
                }, Styles.cleart, () -> Core.app.openURI("https://mindustry-tool.com/cn/" + kind)).pad(6f).height(50f).growX().row();
            });
        });
    }

    private static Table onlineRow(String kind, Item it){
        Table row = new Table(Tex.pane);
        row.table(info -> {
            info.add(it.name == null || it.name.isEmpty() ? "(未命名)" : it.name).left().growX().wrap();
            info.add(gray("下载 " + it.downloads + " · 赞 " + it.likes)).right().color(Pal.gray).padLeft(8f);
        }).growX();
        row.button(Icon.download, Styles.clearNonei, () -> {
            ui.loadfrag.show("下载中…");
            Threads.thread(() -> {
                try{
                    byte[] data = MindustryToolApi.download(kind, it.itemId);
                    String ext = kind.equals("maps") ? ".msav" : ".msch";
                    Fi target = (kind.equals("maps") ? customMapDirectory : schematicDirectory)
                        .child(IosResource.sanitize(it.name) + ext);
                    target.writeBytes(data, false);
                    Core.app.post(() -> {
                        ui.loadfrag.hide();
                        ui.showInfo("已下载: " + target.name());
                        if(page <= 1) rebuild();
                    });
                }catch(Throwable t){
                    Core.app.post(() -> {
                        ui.loadfrag.hide();
                        ui.showException(t);
                    });
                }
            });
        }).size(52f).tooltip("下载到本地");
        return row;
    }

    //endregion

    //region mods page

    private static void buildModsPage(){
        content.add(section("模组 - 在线获取 (官方索引 + 加速)")).row();
        Table online = new Table();
        content.add(online).growX().row();
        loadModIndex(online);

        content.add(section("模组 - 本地 (" + IosResource.countFiles(modDirectory, "zip", "jar") + ")")).padTop(16f).row();
        content.button(b -> {
            b.image(Icon.add);
            b.add(" 导入模组 (.zip / .jar)");
        }, Styles.cleart, () -> IosResource.importTo(modDirectory, ResourceCenter::rebuild, "zip", "jar")).pad(6f).height(56f).growX().row();

        content.add(gray("JS 模组即装即用；Java 模组需构建时打包（iOS 限制）。启用/禁用后重启生效。")).pad(4f).growX().wrap().row();

        Fi[] mods = IosResource.listFiles(modDirectory, "zip", "jar");
        if(mods.length == 0){
            content.add(gray("暂无模组文件。")).row();
        }
        for(Fi f : mods){
            content.add(IosResource.modRow(f, ResourceCenter::rebuild)).pad(4f).growX().row();
        }
    }

    /** fetches the official mod index (jsDelivr first, then raw) */
    private static void loadModIndex(Table section){
        section.add(gray("加载中…")).pad(8f).row();
        Threads.thread(() -> {
            String error = null;
            List<Map<String, Object>> mods = new ArrayList<>();
            for(String url : new String[]{
                "https://cdn.jsdelivr.net/gh/anuken/mindustrymods/mods.json",
                "https://raw.githubusercontent.com/Anuken/MindustryMods/master/mods.json"}){
                try{
                    String body = IosResource.httpGet(url);
                    for(Object o : (List<?>)MindustryToolApi.parseArray(body)){
                        if(o instanceof Map) mods.add((Map<String, Object>)o);
                    }
                    error = null;
                    break;
                }catch(Throwable t){
                    error = t.getMessage() == null ? t.toString() : t.getMessage();
                }
            }
            mods.sort((a, b) -> IosResource.intOf(IosResource.field(b, "stars")) - IosResource.intOf(IosResource.field(a, "stars")));
            final List<Map<String, Object>> fMods = mods;
            final String fError = error;
            Core.app.post(() -> {
                section.clearChildren();
                if(fMods.isEmpty()){
                    section.add(red("获取失败: " + fError)).pad(6f).growX().wrap().row();
                    return;
                }
                int n = Math.min(fMods.size(), 30);
                section.add(gray("共 " + fMods.size() + " 个（按热度排序，显示前 " + n + "）")).pad(4f).row();
                for(int i = 0; i < n; i++){
                    section.add(IosResource.modIndexRow(fMods.get(i), ResourceCenter::rebuild)).pad(4f).growX().row();
                }
            });
        });
    }

    //endregion

    //region saves page

    private static void buildSavesPage(){
        content.add(section("存档文件 (" + IosResource.countFiles(saveDirectory, "msav") + ")")).row();
        content.button(b -> {
            b.image(Icon.add);
            b.add(" 导入存档 (.msav)");
        }, Styles.cleart, () -> IosResource.importTo(saveDirectory, ResourceCenter::rebuild, "msav")).pad(6f).height(56f).growX().row();

        Fi[] saves = IosResource.listFiles(saveDirectory, "msav");
        if(saves.length == 0){
            content.add(gray("暂无存档，开始一局游戏后即可在此管理。")).row();
        }
        for(Fi f : saves){
            content.add(IosResource.fileRow(f, true, ResourceCenter::rebuild)).pad(4f).growX().row();
        }

        content.add(section("备份")).padTop(16f).row();
        content.button(b -> {
            b.image(Icon.download);
            b.add(" 备份全部存档");
        }, Styles.cleart, () -> {
            Fi[] all = IosResource.listFiles(saveDirectory, "msav");
            if(all.length == 0){
                ui.showInfo("没有可备份的存档");
                return;
            }
            for(Fi f : all){
                IosResource.backupSave(f);
            }
            ui.showInfo("已备份 " + all.length + " 个存档");
            rebuild();
        }).pad(6f).height(56f).growX().row();

        Fi[] backups = IosResource.listFiles(IosResource.backupsDir(), "msav");
        if(backups.length == 0){
            content.add(gray("备份会保存在数据目录的 backups/ 下，可用于恢复。")).row();
        }
        for(Fi f : backups){
            content.add(IosResource.backupRow(f, ResourceCenter::rebuild)).pad(4f).growX().row();
        }
    }

    //endregion

    //region info page

    private static void buildInfoPage(){
        content.add(section("信息")).row();
        content.table(g -> {
            g.left();
            g.add("游戏版本").left().growX();
            g.add("[gray]" + Version.buildString() + "[/gray]").right().row();
            g.add("当前内存占用").left().growX();
            g.add("[gray]" + IosResource.formatSize(Core.app.getJavaHeap()) + "[/gray]").right().row();
            g.add("脚本引擎 (JS 模组)").left().growX();
            g.label(() -> {
                if(!mods.hasScripts()) return "[lightgray]未初始化[]";
                return mods.getScripts().hasErrored() ? "[scarlet]初始化失败[]" : "[lime]正常[]";
            }).right().row();
            g.label(IosResource::lastErrorLine).left().growX().colspan(2).wrap().row();
            g.button(b -> {
                b.image(Icon.refresh);
                b.add(" 检查更新 (latest-ipa)");
            }, Styles.cleart, IosResource::checkUpdates).growX().height(56f).colspan(2).pad(6f).row();
            g.button(b -> {
                b.image(Icon.fileText);
                b.add(" 查看 / 分享运行日志");
            }, Styles.cleart, IosResource::showLogs).growX().height(56f).colspan(2).pad(6f).row();
        }).pad(6f).growX().row();

        content.add(section("存储用量")).padTop(14f).row();
        content.table(g -> {
            g.left();
            IosResource.dirStat(g, "存档", saveDirectory);
            IosResource.dirStat(g, "模组", modDirectory);
            IosResource.dirStat(g, "地图", customMapDirectory);
            IosResource.dirStat(g, "蓝图", schematicDirectory);
            IosResource.dirStat(g, "备份", IosResource.backupsDir());
        }).pad(6f).growX().row();

        content.add(section("维护")).padTop(14f).row();
        content.button(b -> {
            b.image(Icon.trash);
            b.add(" 清理临时缓存");
        }, Styles.cleart, () -> {
            ui.showConfirm("@confirm", "清理临时文件与地图预览缓存?", () -> {
                IosResource.deleteContents(tmpDirectory);
                IosResource.deleteContents(mapPreviewDirectory);
                IosResource.deleteContents(dataDirectory.child("assetCache"));
                ui.showInfo("已清理");
                rebuild();
            });
        }).pad(6f).height(56f).growX().row();

        content.add(gray("资源中心由 Mindustry iOS 移植项目提供。数据源: MindustryTool API / 官方模组索引。")).pad(10f).growX().wrap().row();
    }

    //endregion

    //region helpers

    private static Label gray(String text){
        return new Label(text, Styles.outlineLabel);
    }

    private static Label red(String text){
        return new Label("[scarlet]" + text + "[]", Styles.outlineLabel);
    }

    private static Table section(String text){
        return IosResource.section(text);
    }

    //endregion
}

package mindustry.ios;

import arc.*;
import arc.Input.*;
import arc.backend.robovm.*;
import arc.files.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.net.*;
import mindustry.ui.FileChooser.*;
import mindustry.ui.fragments.MenuFragment;
import mindustry.ios.launcher.IosLauncherDialog;
import mindustry.gen.Icon;
import mindustry.ui.*;
import org.robovm.apple.coregraphics.*;
import org.robovm.apple.foundation.*;
import org.robovm.apple.uikit.*;
import org.robovm.objc.block.*;
import rhino.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.robovm.apple.foundation.NSPathUtilities.*;

//warnings for deprecated functions related to multi-window applications are not applicable here
@SuppressWarnings("deprecation")
public class IOSLauncher extends IOSApplication.Delegate{
    private boolean forced;

    @Override
    protected IOSApplication createApplication(){

        if(UIDevice.getCurrentDevice().getUserInterfaceIdiom() == UIUserInterfaceIdiom.Pad){
            Scl.setAddition(0.5f);
        }else{
            Scl.setAddition(-0.5f);
        }

        return new IOSApplication(new ClientLauncher(){

            @Override
            public void setup(){
                //iOS port: copy bundled mods before the mod loader runs, disabled by
                //default so a conflicting mod can never block the game from starting.
                copyBundledMods();
                super.setup();
            }

            /** Reads the mod's internal name from mod.json / mod.hjson. */
            String readModMetaName(Fi f){
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

            /** Copies preinstalled mods from the app bundle; bundled mods default to disabled. */
            void copyBundledMods(){
                try{
                    //robovm packs <resource>directory</directory> content at the bundle
                    //root, so the folder may appear as "bundled-mods" instead of
                    //"data/bundled-mods" depending on the build - try both.
                    Fi bundled = Core.files.internal("bundled-mods");
                    if(!bundled.exists()) bundled = Core.files.internal("data/bundled-mods");
                    Fi mods = Core.settings.getDataDirectory().child("mods");
                    mods.mkdirs();
                    final int[] copied = {0};
                    for(Fi src : bundled.list()){
                        if(src.isDirectory()) continue;
                        Fi dst = mods.child(src.name());
                        //install when missing, or overwrite when the bundled copy is a newer build
                        if(!dst.exists() || dst.length() != src.length()){
                            src.copyTo(dst);
                            copied[0]++;
                            //the game keys enabled-state by the mod's internal name
                            String metaName = readModMetaName(dst);
                            String key = "mod-" + metaName + "-enabled";
                            if(Core.settings.get(key, null) == null){
                                Core.settings.put(key, false);
                            }
                            Log.info("[launcher] bundled mod installed: @ (id: @, disabled by default)", dst.name(), metaName);
                        }
                    }
                    if(copied[0] > 0){
                        Core.settings.forceSave();
                        int count = copied[0];
                        Core.app.post(() -> {
                            if(ui != null) ui.showInfo("已安装 " + count + " 个内置模组（默认禁用，可在启动器-模组 中启用）");
                        });
                    }
                }catch(Throwable t){
                    Log.err("[launcher] bundled mod copy failed");
                    Log.err(t);
                }
            }

            @Override
            public void showFileChooser(FileChooserParams params){
                if(!params.open){ //when exporting, just share it.
                    //ask for export name
                    Core.input.getTextInput(new TextInput(){{
                        title = Core.bundle.get("filename");
                        accepted = name -> {
                            try{
                                //write result
                                Fi result = tmpDirectory.child(name + "." + params.extensions[0]);
                                params.handleChooseResult(result);

                                //import the document
                                shareFile(result);
                            }catch(Throwable t){
                                ui.showException(t);
                            }
                        };
                    }});
                    return;
                }

                UIDocumentBrowserViewController cont = new UIDocumentBrowserViewController((NSArray<NSString>)null);

                NSArray<UIBarButtonItem> arr = new NSArray<>(new UIBarButtonItem(Core.bundle.get("cancel"), UIBarButtonItemStyle.Plain,
                    uiBarButtonItem -> cont.dismissViewController(true, () -> {})));

                cont.setAllowsDocumentCreation(false);
                cont.setAdditionalLeadingNavigationBarButtonItems(arr);

                class ChooserDelegate extends NSObject implements UIDocumentBrowserViewControllerDelegate{
                    @Override
                    public void didPickDocumentURLs(UIDocumentBrowserViewController controller, NSArray<NSURL> documentURLs){
                    }

                    @Override
                    public void didPickDocumentsAtURLs(UIDocumentBrowserViewController controller, NSArray<NSURL> documentURLs){
                        if(documentURLs.isEmpty()) return;

                        Seq<Fi> results = new Seq<>();
                        NSFileCoordinator coord = new NSFileCoordinator(null);
                        for(NSURL url : documentURLs){
                            url.startAccessingSecurityScopedResource();
                            try{
                                coord.coordinateReadingItem(url, NSFileCoordinatorReadingOptions.ForUploading, result -> {
                                    Fi src = Core.files.absolute(result.getAbsoluteURL().getPath());
                                    Fi dst = Core.files.absolute(getDocumentsDirectory()).child(src.name());
                                    src.copyTo(dst);
                                    results.add(dst);
                                });
                            }catch(Throwable e){
                                ui.showException(e);
                            }

                            url.stopAccessingSecurityScopedResource();

                            cont.dismissViewController(true, () -> {});
                        }

                        Core.app.post(() -> {
                            try{
                                params.handleChooseResult(results.toArray(Fi.class));
                            }catch(Throwable t){
                                ui.showException(t);
                            }
                        });
                    }

                    @Override
                    public void didRequestDocumentCreationWithHandler(UIDocumentBrowserViewController controller, VoidBlock2<NSURL, UIDocumentBrowserImportMode> importHandler){

                    }

                    @Override
                    public void didImportDocument(UIDocumentBrowserViewController controller, NSURL sourceURL, NSURL destinationURL){
                    }

                    @Override
                    public void failedToImportDocument(UIDocumentBrowserViewController controller, NSURL documentURL, NSError error){
                    }

                    @Override
                    public NSArray<UIActivity> applicationActivities(UIDocumentBrowserViewController controller, NSArray<NSURL> documentURLs){
                        return null;
                    }

                    @Override
                    public void willPresentActivityViewController(UIDocumentBrowserViewController controller, UIActivityViewController activityViewController){

                    }
                }

                cont.setDelegate(new ChooserDelegate());

                UIApplication.getSharedApplication().getKeyWindow().getRootViewController().presentViewController(cont, true, () -> {});
            }
            @Override
            public ClassLoader loadJar(Fi jar, ClassLoader parent){
                //iOS port: classes cannot be defined at runtime under AOT. Java mods bundled
                //at build time (ios/javamods/) are already compiled into the executable, so
                //resolve their classes through the system class loader. Mod assets/scripts
                //are still read from the jar file normally.
                return parent;
            }

            @Override
            public Context getScriptContext(){
                Context context = Context.getCurrentContext();
                if(context == null) context = Context.enter();
                context.setOptimizationLevel(-1);
                return context;
            }

            @Override
            public void shareFile(Fi file){
                try{
                    Log.info("Attempting to share file " + file);
                    List<Object> list = new ArrayList<>();

                    //better choice?
                    list.add(new NSURL(file.file()));

                    UIActivityViewController p = new UIActivityViewController(list, null);
                    UIViewController rootVc = UIApplication.getSharedApplication().getKeyWindow().getRootViewController();
                    if(UIDevice.getCurrentDevice().getUserInterfaceIdiom() == UIUserInterfaceIdiom.Pad){
                        // Set up the pop-over for iPad
                        UIPopoverPresentationController pop = p.getPopoverPresentationController();
                        UIView mainView = rootVc.getView();
                        pop.setSourceView(mainView);
                        CGRect targetRect = new CGRect(mainView.getBounds().getMidX(), mainView.getBounds().getMidY(), 0, 0);
                        pop.setSourceRect(targetRect);
                        pop.setPermittedArrowDirections(UIPopoverArrowDirection.None);
                    }
                    rootVc.presentViewController(p, true, () -> Log.info("Success! Presented @", file));
                }catch(Throwable t){
                    ui.showException(t);
                }
            }

            @Override
            public void beginForceLandscape(){
                forced = true;
                UINavigationController.attemptRotationToDeviceOrientation();
            }

            @Override
            public void endForceLandscape(){
                forced = false;
                UINavigationController.attemptRotationToDeviceOrientation();
            }
        }, new IOSApplicationConfiguration(){{
            useGL30 = true;
        }});
    }

    @Override
    public UIInterfaceOrientationMask getSupportedInterfaceOrientations(UIApplication application, UIWindow window){
        return forced ? UIInterfaceOrientationMask.Landscape : UIInterfaceOrientationMask.All;
    }


    @Override
    public boolean openURL(UIApplication app, NSURL url, UIApplicationOpenURLOptions options){
        handleAppURL(url);
        return false;
    }

    @Override
    public boolean didFinishLaunching(UIApplication application, UIApplicationLaunchOptions options){
        boolean b = super.didFinishLaunching(application, options);

        Events.on(ClientLoadEvent.class, e -> Core.app.post(() -> Core.app.post(() -> {
            //iOS port: show the launcher screen and register a main menu entry for it.
            if(MenuFragment.customMenuButtons.isEmpty()){
                MenuFragment.customMenuButtons.add(new MenuFragment.MenuButton("启动器", Icon.list, IosLauncherDialog::toggle));
            }
            IosLauncherDialog.maybeShow();
        })));

        Events.on(ClientLoadEvent.class, e -> Core.app.post(() -> Core.app.post(() -> Core.scene.table(Styles.black9, t -> {
            t.visible(() -> {
                if(!forced) return false;
                t.toFront();
                UIInterfaceOrientation o = UIApplication.getSharedApplication().getStatusBarOrientation();
                return forced && (o == UIInterfaceOrientation.Portrait || o == UIInterfaceOrientation.PortraitUpsideDown);
            });
            t.add("Rotate the device to landscape orientation to use the editor.").wrap().grow();
        }))));

        return b;
    }

    void handleAppURL(NSURL url){
        if(url == null) return;
        try{
            Fi file = new Fi(getDocumentsDirectory()).child(url.getLastPathComponent());
            new Fi(url.getPath()).copyTo(file);

            ClientLauncher.runOnClientLoad(() -> ClientLauncher.handleFileImport(file));
        }catch(Exception e){
            Log.err(e);
        }
    }

    public static void main(String[] argv){
        NSAutoreleasePool pool = new NSAutoreleasePool();
        try{
            UIApplication.main(argv, null, IOSLauncher.class);
        }catch(Throwable t){
            //attempt to log the exception
            CrashHandler.log(t);
            Log.err(t);
            //rethrow the exception so it actually crashes
            throw t;
        }
        pool.close();
    }
}

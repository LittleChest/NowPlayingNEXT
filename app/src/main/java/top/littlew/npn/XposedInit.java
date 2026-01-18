package top.littlew.npn;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {

    private static final List<String> TARGET_FLAGS = Arrays.asList(
            "enableNowPlayingQuickAffordance",
            "enableNowPlayingLockScreenUpdate",
            "enableNowPlayingQuickSettings"
    );

    private static final Map<String, Method> targetedMethods = new HashMap<>();
    private static int mResIdMusicOff = 0;

    private static final String PREFS_NAME = "now_playing_next";
    private static final String PREFS_KEY_VERSION = "version";
    private static final String PREFS_KEY_CLASS = "class_name";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.google.android.as".equals(lpparam.packageName)) {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws PackageManager.NameNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException, InstantiationException {
                    Context context = (Context) param.thisObject;

                    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

                    PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                    long currentVersionCode = pInfo.getLongVersionCode();

                    long prefsVersionCode = prefs.getLong(PREFS_KEY_VERSION, -1);
                    String targetClassName = prefs.getString(PREFS_KEY_CLASS, null);

                    if (currentVersionCode != prefsVersionCode || targetClassName == null) {
                        targetClassName = scanForTargetClass(lpparam);

                        if (targetClassName != null) {
                            prefs.edit()
                                    .putLong(PREFS_KEY_VERSION, currentVersionCode)
                                    .putString(PREFS_KEY_CLASS, targetClassName)
                                    .apply();
                        }
                    }

                    if (targetClassName != null) {
                        final Class<?> builderClass = XposedHelpers.findClassIfExists(targetClassName, lpparam.classLoader);
                        if (builderClass == null) return;

                        Method buildMethod = findBuildMethod(builderClass);
                        if (buildMethod == null) return;

                        targetedMethods.clear();
                        List<Method> candidates = new ArrayList<>();
                        for (Method m : builderClass.getDeclaredMethods()) {
                            if (!Modifier.isStatic(m.getModifiers()) &&
                                    m.getReturnType() == void.class &&
                                    m.getParameterTypes().length == 1 &&
                                    m.getParameterTypes()[0] == boolean.class) {
                                candidates.add(m);
                            }
                        }

                        String baseError = getErrorMessage(builderClass.getDeclaredConstructor().newInstance(), buildMethod);
                        if (baseError == null) return;

                        for (Method method : candidates) {
                            if (targetedMethods.size() == TARGET_FLAGS.size()) break;

                            Object testBuilder = builderClass.getDeclaredConstructor().newInstance();
                            method.setAccessible(true);
                            method.invoke(testBuilder, true);

                            String newError = getErrorMessage(testBuilder, buildMethod);
                            if (newError == null) continue;

                            for (String flag : TARGET_FLAGS) {
                                if (!targetedMethods.containsKey(flag)) {
                                    if (baseError.contains(flag) && !newError.contains(flag)) {
                                        targetedMethods.put(flag, method);
                                    }
                                }
                            }
                        }

                        if (targetedMethods.isEmpty()) return;

                        XposedHelpers.findAndHookMethod(builderClass, buildMethod.getName(), new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Object builderInstance = param.thisObject;
                                for (String flag : TARGET_FLAGS) {
                                    Method setter = targetedMethods.get(flag);
                                    if (setter != null) {
                                        setter.invoke(builderInstance, true);
                                    }
                                }
                            }
                        });
                    }
                }
            });
        }

        if ("com.android.systemui".equals(lpparam.packageName)) {
            XposedHelpers.findAndHookMethod(
                    "com.google.android.systemui.ambientmusic.AmbientIndicationContainer",
                    lpparam.classLoader,
                    "setAmbientMusic",
                    CharSequence.class,
                    PendingIntent.class,
                    PendingIntent.class,
                    int.class,
                    boolean.class,
                    String.class,
                    "com.google.android.systemui.keyguard.shared.ExtendedIndication",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object thiz = param.thisObject;

                            Object extendedIndication = param.args[6];
                            int iconOverride = (int) param.args[3];

                            if (extendedIndication != null) {
                                XposedHelpers.setBooleanField(thiz, "mUsingExtendedIndication", true);
                            }

                            // 3 = MUSIC_NOT_FOUND
                            if (iconOverride == 3) {
                                Context context = ((View) thiz).getContext();
                                Drawable icon = getCachedDrawable(context);
                                if (icon != null) {
                                    XposedHelpers.setObjectField(thiz, "mAmbientIconOverride", icon);
                                }
                            }

                            XposedHelpers.callMethod(thiz, "updatePill");
                        }
                    });
        }
    }


    private String scanForTargetClass(XC_LoadPackage.LoadPackageParam lpparam) {
        Object pathList = XposedHelpers.getObjectField(lpparam.classLoader, "pathList");
        Object[] dexElements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");

        if (dexElements == null) return null;

        for (Object element : dexElements) {
            DexFile dexFile = (DexFile) XposedHelpers.getObjectField(element, "dexFile");

            if (dexFile == null) continue;

            @SuppressWarnings("deprecation")
            Enumeration<String> entries = dexFile.entries();

            while (entries.hasMoreElements()) {
                String className = entries.nextElement();

                if (className.contains(".") || className.length() > 6) {
                    continue;
                }

                if (className.contains("$")) {
                    continue;
                }

                Class<?> clazz;
                try {
                    clazz = lpparam.classLoader.loadClass(className);
                } catch (Throwable e) {
                    continue;
                }

                Method buildMethod = findBuildMethod(clazz);
                if (buildMethod == null) continue;

                try {
                    java.lang.reflect.Constructor<?> constructor = clazz.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    Object instance = constructor.newInstance();

                    String errorMsg = getErrorMessage(instance, buildMethod);
                    if (errorMsg != null && errorMsg.contains(TARGET_FLAGS.get(0))) {
                        return className;
                    }
                } catch (Throwable t) {
                    continue;
                }
            }
        }
        return null;
    }

    private Method findBuildMethod(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) &&
                    m.getParameterTypes().length == 0 &&
                    !m.getReturnType().isPrimitive() &&
                    !m.getReturnType().equals(String.class) &&
                    !m.getReturnType().equals(Void.TYPE)) {
                return m;
            }
        }
        return null;
    }

    private String getErrorMessage(Object builder, Method buildMethod) {
        try {
            buildMethod.setAccessible(true);
            buildMethod.invoke(builder);
            return "";
        } catch (Exception e) {
            if (e instanceof InvocationTargetException) {
                Throwable cause = e.getCause();
                if (cause instanceof IllegalStateException) {
                    return cause.getMessage();
                }
            }
        }
        return null;
    }

    @SuppressLint("DiscouragedApi")
    private static Drawable getCachedDrawable(Context context) {
        if (mResIdMusicOff != 0) {
            return context.getDrawable(mResIdMusicOff);
        }
        Resources res = context.getResources();
        int id = res.getIdentifier("ic_now_playing_music_off", "drawable", "com.android.systemui");
        if (id != 0) {
            mResIdMusicOff = id;
            return context.getDrawable(id);
        }
        return null;
    }
}
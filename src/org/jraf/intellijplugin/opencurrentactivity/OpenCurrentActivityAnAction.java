/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2015 Benoit 'BoD' Lubek (BoD@JRAF.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jraf.intellijplugin.opencurrentactivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jraf.intellijplugin.opencurrentactivity.exception.ExecutionAdbException;
import org.jraf.intellijplugin.opencurrentactivity.exception.MultipleDevicesAdbException;
import org.jraf.intellijplugin.opencurrentactivity.exception.NoDevicesAdbException;
import org.jraf.intellijplugin.opencurrentactivity.exception.ParseAdbException;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.PsiShortNamesCache;

public class OpenCurrentActivityAnAction extends AnAction {
    private static final Logger log = Logger.getInstance(OpenCurrentActivityAnAction.class);

    private static final String PATTERN_FOCUSED_ACTIVITY = "mFocusedActivity";
    private static final Pattern PATTERN_ACTIVITY_NAME = Pattern.compile(".* ([a-zA-Z0-9.]+)/([a-zA-Z0-9.]+).*");
    private static final String PATTERN_MULTIPLE_DEVICE = "more than one device";
    private static final String PATTERN_DEVICE_LIST_HEADER = "List";
    private static final Pattern PATTERN_DEVICE_LIST_ITEM = Pattern.compile("(.+)\\p{Space}+(.+)");
    private static final String PATTERN_DEVICE_NOT_FOUND = "device not found";

    private static final String ANDROID_SDK_TYPE_NAME = "Android SDK";
    private static final String ADB_SUBPATH = "/platform-tools/";
    private static final String ADB_WINDOWS = "adb.exe";
    private static final String ADB_UNIX = "adb";
    private static final String UI_NO_SDK_MESSAGE = "Could not find the path for the Android SDK.  Have you configured it?";
    private static final String UI_GENERIC_WARNING = "Warning";
    private static final String EXT_JAVA = ".java";

    private StatusBar mStatusBar;

    public void actionPerformed(AnActionEvent event) {
        final Project project = event.getData(PlatformDataKeys.PROJECT);
        if (project == null) {
            log.warn("project is null, which should never happen: give up");
            return;
        }

        mStatusBar = WindowManager.getInstance().getStatusBar(project);
        if (mStatusBar == null) {
            log.warn("mStatusBar is null, which should never happen: give up");
            return;
        }

        String androidSdkPath = getAndroidSdkPath();
        if (androidSdkPath == null) {
            log.warn("Could not find Android sdk path");
            Messages.showWarningDialog(project, UI_NO_SDK_MESSAGE, UI_GENERIC_WARNING);
            return;
        }

        String activityName;
        try {
            activityName = getCurrentActivityName(null, androidSdkPath);
            openActivityFile(project, activityName);
        } catch (MultipleDevicesAdbException e) {
            log.info("Multiple devices detected, get the list and try again");
            try {
                List<String> deviceIdList = getDeviceIdList(androidSdkPath);
                for (String deviceId : deviceIdList) {
                    try {
                        activityName = getCurrentActivityName(deviceId, androidSdkPath);
                        openActivityFile(project, activityName);
                    } catch (ExecutionAdbException e2) {
                        mStatusBar.setInfo("Could not execute adb (" + e2.getCause().getMessage() + ")");
                    } catch (ParseAdbException e2) {
                        mStatusBar.setInfo("Could not parse adb output");
                    } catch (MultipleDevicesAdbException e2) {
                        // This should never happen since we passed a device id
                        log.error("Got a multiple devices message when passing a device id!?", e2);
                        mStatusBar.setInfo("Something went wrong!");
                    }
                }
            } catch (ExecutionAdbException e1) {
                mStatusBar.setInfo("Could not execute adb (" + e1.getCause().getMessage() + ")");
            } catch (ParseAdbException e1) {
                mStatusBar.setInfo("Could not parse adb output");
            } catch (NoDevicesAdbException e1) {
                // This should never happen since we have multiple devices
                log.error("Got a no devices message when passing a device id!?", e1);
                mStatusBar.setInfo("Something went wrong!");
            }
        } catch (ExecutionAdbException e) {
            mStatusBar.setInfo("Could not execute adb (" + e.getCause().getMessage() + ")");
        } catch (ParseAdbException e) {
            mStatusBar.setInfo("Could not parse adb output");
        } catch (NoDevicesAdbException e) {
            mStatusBar.setInfo("Could not find any devices or emulators");
        }
    }

    private void openActivityFile(final Project project, String activityName) {
        log.info("activityName=" + activityName);

        // Keep only the class name
        int dotIndex = activityName.lastIndexOf('.');
        if (dotIndex != -1) {
            activityName = activityName.substring(dotIndex + 1);
        }
        final String fileName = activityName + EXT_JAVA;

        // Open the file
        ApplicationManager.getApplication().invokeLater(
                new Runnable() {
                    public void run() {
                        ApplicationManager.getApplication().runReadAction(new Runnable() {
                            public void run() {
                                PsiFile[] foundFiles = PsiShortNamesCache.getInstance(project).getFilesByName(fileName);
                                if (foundFiles.length == 0) {
                                    log.info("No file with name " + fileName + " found");
                                    mStatusBar.setInfo("Could not find " + fileName + " in project");
                                    return;
                                }
                                if (foundFiles.length > 1) log.warn("Found more than one file with name " + fileName);

                                PsiFile foundFile = foundFiles[0];
                                log.info("Found file " + foundFile.getName());
                                OpenFileDescriptor descriptor = new OpenFileDescriptor(project, foundFile.getVirtualFile());
                                descriptor.navigate(true);
                            }
                        });
                    }
                }
        );
    }

    /**
     * Find the path for the Android SDK (so we can deduce the path for adb).
     *
     * @return The found path, or {@code null} if it was not found (for instance if the Android SDK has not yet been configured.
     */
    @Nullable
    private String getAndroidSdkPath() {
        Sdk[] allSdks = ProjectJdkTable.getInstance().getAllJdks();
        log.info("allSdks=" + Arrays.toString(allSdks));
        for (Sdk sdk : allSdks) {
            String sdkTypeName = sdk.getSdkType().getName();
            log.info(sdkTypeName);
            if (ANDROID_SDK_TYPE_NAME.equals(sdkTypeName)) {
                return sdk.getHomePath();
            }
        }
        return null;
    }

    @NotNull
    private String getAdbPath(String androidSdkPath) {
        String adb;
        if (SystemInfo.isWindows) {
            adb = ADB_WINDOWS;
        } else {
            adb = ADB_UNIX;
        }

        String adbPath = androidSdkPath + ADB_SUBPATH + adb;
        log.info("adbPath='" + adbPath + "'");
        return adbPath;
    }

    /**
     * Runs {@code adb shell dumpsys activity activities} and parses the results to retrieve the name of the current foremost Activity.
     *
     * @param androidSdkPath Path of the Android SDK (where to find adb).
     * @return The name of the foremost ("focused") Activity.
     */
    @NotNull
    private String getCurrentActivityName(@Nullable String deviceId, String androidSdkPath)
            throws ExecutionAdbException, MultipleDevicesAdbException, ParseAdbException, NoDevicesAdbException {
        String adbPath = getAdbPath(androidSdkPath);

        ProcessBuilder processBuilder;
        if (deviceId == null) {
            processBuilder = new ProcessBuilder(adbPath, "shell", "dumpsys", "activity", "activities");
        } else {
            processBuilder = new ProcessBuilder(adbPath, "-s", deviceId, "shell", "dumpsys", "activity", "activities");
        }
        processBuilder.redirectErrorStream(true);
        Process process = null;
        try {
            process = processBuilder.start();
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                log.info("line='" + line + "'");
                if (line.contains(PATTERN_MULTIPLE_DEVICE)) {
                    throw new MultipleDevicesAdbException();
                }
                if (line.contains(PATTERN_DEVICE_NOT_FOUND)) {
                    throw new NoDevicesAdbException();
                }
                if (line.contains(PATTERN_FOCUSED_ACTIVITY)) {
                    Matcher matcher = PATTERN_ACTIVITY_NAME.matcher(line);
                    if (!matcher.matches()) {
                        log.error("Could not find the focused Activity in the line");
                        throw new ParseAdbException("Could not find the focused Activity in the line");
                    }
                    return matcher.group(2);
                }
            }
        } catch (IOException e) {
            log.error("Could not exec adb or read from its process", e);
            throw new ExecutionAdbException(e);
        } finally {
            if (process != null) process.destroy();
        }
        // Reached the end of lines, none of them had info about the focused activity
        throw new ParseAdbException("Could not find the focused Activity in the output");
    }

    /**
     * Runs {@code adb devices} and parses the results to retrieve the list of device ids.
     *
     * @param androidSdkPath Path of the Android SDK (where to find adb).
     * @return The list of device ids.
     */
    @NotNull
    private List<String> getDeviceIdList(String androidSdkPath) throws ExecutionAdbException, ParseAdbException {
        String adbPath = getAdbPath(androidSdkPath);

        ProcessBuilder processBuilder = new ProcessBuilder(adbPath, "devices");
        processBuilder.redirectErrorStream(true);
        Process process = null;
        List<String> res = new ArrayList<String>(4);
        try {
            process = processBuilder.start();
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                log.info("line='" + line + "'");
                if (line.contains(PATTERN_DEVICE_LIST_HEADER)) continue;
                Matcher matcher = PATTERN_DEVICE_LIST_ITEM.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                String deviceId = matcher.group(1);
                res.add(deviceId);
            }
        } catch (IOException e) {
            log.error("Could not exec adb or read from its process", e);
            throw new ExecutionAdbException(e);
        } finally {
            if (process != null) process.destroy();
        }
        if (res.isEmpty()) {
            // Reached the end of lines, there was no device
            throw new ParseAdbException("Could not find devices in the output");
        }

        return res;
    }
}

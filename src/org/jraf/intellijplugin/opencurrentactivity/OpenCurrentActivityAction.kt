/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2015-present Benoit 'BoD' Lubek (BoD@JRAF.org)
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
package org.jraf.intellijplugin.opencurrentactivity

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.search.PsiShortNamesCache
import org.jraf.intellijplugin.opencurrentactivity.exception.ExecutionAdbException
import org.jraf.intellijplugin.opencurrentactivity.exception.MultipleDevicesAdbException
import org.jraf.intellijplugin.opencurrentactivity.exception.NoDevicesAdbException
import org.jraf.intellijplugin.opencurrentactivity.exception.ParseAdbException
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.regex.Pattern

@Suppress("unused")
class OpenCurrentActivityAction : AnAction() {

    companion object {
        private const val PATTERN_RESUMED_ACTIVITY = "ResumedActivity"
        private val PATTERN_ACTIVITY_NAME = Pattern.compile(".* ([a-zA-Z0-9.]+)/([a-zA-Z0-9.]+).*")
        private const val PATTERN_MULTIPLE_DEVICE = "more than one device"
        private const val PATTERN_DEVICE_LIST_HEADER = "List"
        private val PATTERN_DEVICE_LIST_ITEM = Pattern.compile("(.+)\\p{Space}+(.+)")
        private const val PATTERN_DEVICE_NOT_FOUND = "device not found"

        private const val ANDROID_SDK_TYPE_NAME = "Android SDK"
        private const val ADB_SUBPATH = "/platform-tools/"
        private const val ADB_WINDOWS = "adb.exe"
        private const val ADB_UNIX = "adb"
        private const val UI_NO_SDK_MESSAGE = "Could not find the path for the Android SDK.  Have you configured it?"
        private const val UI_GENERIC_WARNING = "Warning"
        private const val EXT_JAVA = ".java"
        private const val EXT_KOTLIN = ".kt"

        private val log = Logger.getInstance(OpenCurrentActivityAction::class.java)
    }

    private lateinit var statusBar: StatusBar

    /**
     * Find the path for the Android SDK (so we can deduce the path for adb).
     *
     * @return The found path, or `null` if it was not found (for instance if the Android SDK has not yet been configured.
     */
    private val androidSdkPath: String?
        get() {
            val allSdks = ProjectJdkTable.getInstance().allJdks
            log.info("allSdks=${allSdks.contentToString()}")
            for (sdk in allSdks) {
                val sdkTypeName = sdk.sdkType.name
                log.info(sdkTypeName)
                if (ANDROID_SDK_TYPE_NAME == sdkTypeName) {
                    return sdk.homePath
                }
            }
            return null
        }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getData(PlatformDataKeys.PROJECT) ?: run {
            log.warn("project is null, which should never happen: give up")
            return
        }

        statusBar = WindowManager.getInstance().getStatusBar(project) ?: run {
            log.warn("statusBar is null, which should never happen: give up")
            return
        }

        val androidSdkPath = androidSdkPath ?: run {
            log.warn("Could not find Android sdk path")
            Messages.showWarningDialog(project, UI_NO_SDK_MESSAGE, UI_GENERIC_WARNING)
            return
        }

        var activityName: String
        try {
            activityName = getCurrentActivityName(null, androidSdkPath)
            openActivityFile(project, activityName)
        } catch (e: MultipleDevicesAdbException) {
            log.info("Multiple devices detected, get the list and try again")
            try {
                val deviceIdList = getDeviceIdList(androidSdkPath)
                for (deviceId in deviceIdList) {
                    try {
                        activityName = getCurrentActivityName(deviceId, androidSdkPath)
                        openActivityFile(project, activityName)
                    } catch (e: ExecutionAdbException) {
                        statusBar.info = "Could not execute adb (${e.cause?.message})"
                    } catch (e: ParseAdbException) {
                        statusBar.info = "Could not parse adb output"
                    } catch (e: MultipleDevicesAdbException) {
                        // This should never happen since we passed a device id
                        log.error("Got a multiple devices message when passing a device id!?", e)
                        statusBar.info = "Something went wrong!"
                    }

                }
            } catch (e: ExecutionAdbException) {
                statusBar.info = "Could not execute adb (${e.cause?.message})"
            } catch (e: ParseAdbException) {
                statusBar.info = "Could not parse adb output"
            } catch (e: NoDevicesAdbException) {
                // This should never happen since we have multiple devices
                log.error("Got a no devices message when passing a device id!?", e)
                statusBar.info = "Something went wrong!"
            }

        } catch (e: ExecutionAdbException) {
            statusBar.info = "Could not execute adb (${e.cause?.message})"
        } catch (e: ParseAdbException) {
            statusBar.info = "Could not parse adb output"
        } catch (e: NoDevicesAdbException) {
            statusBar.info = "Could not find any devices or emulators"
        }

    }

    private fun openActivityFile(project: Project, originalActivityName: String) {
        var activityName = originalActivityName
        log.info("activityName=$activityName")

        // Keep only the class name
        val dotIndex = activityName.lastIndexOf('.')
        if (dotIndex != -1) {
            activityName = activityName.substring(dotIndex + 1)
        }
        val fileNameJava = activityName + EXT_JAVA
        val fileNameKotlin = activityName + EXT_KOTLIN

        // Open the file (try .java first then .kt)
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runReadAction {
                var foundFiles = PsiShortNamesCache.getInstance(project).getFilesByName(fileNameJava)
                if (foundFiles.isEmpty()) {
                    log.info("No file with name $fileNameJava found")
                    // Java not found, try Kotlin
                    foundFiles = PsiShortNamesCache.getInstance(project).getFilesByName(fileNameKotlin)
                    if (foundFiles.isEmpty()) {
                        log.info("No file with name $fileNameKotlin found")
                        statusBar.info = "Could not find $fileNameJava or $fileNameKotlin in project"
                        return@runReadAction
                    }
                }
                if (foundFiles.size > 1) log.warn("Found more than one file with name $fileNameJava or $fileNameKotlin")

                // Will open all files if several are found
                for (foundFile in foundFiles) {
                    log.info("Opening file " + foundFile.name)
                    val descriptor = OpenFileDescriptor(project, foundFile.virtualFile)
                    descriptor.navigate(true)
                }
            }
        }
    }

    private fun getAdbPath(androidSdkPath: String): String {
        val adb = if (SystemInfo.isWindows) ADB_WINDOWS else ADB_UNIX
        val adbPath = androidSdkPath + ADB_SUBPATH + adb
        log.info("adbPath='$adbPath'")
        return adbPath
    }

    /**
     * Runs `adb shell dumpsys activity activities` and parses the results to retrieve the name of the current foremost Activity.
     *
     * @param androidSdkPath Path of the Android SDK (where to find adb).
     * @return The name of the foremost ("focused") Activity.
     */
    @Throws(ExecutionAdbException::class, MultipleDevicesAdbException::class, ParseAdbException::class, NoDevicesAdbException::class)
    private fun getCurrentActivityName(deviceId: String?, androidSdkPath: String): String {
        val adbPath = getAdbPath(androidSdkPath)

        val processBuilder: ProcessBuilder = if (deviceId == null) {
            ProcessBuilder(adbPath, "shell", "dumpsys", "activity", "activities")
        } else {
            ProcessBuilder(adbPath, "-s", deviceId, "shell", "dumpsys", "activity", "activities")
        }
        processBuilder.redirectErrorStream(true)
        var process: Process? = null
        try {
            process = processBuilder.start()
            val bufferedReader = BufferedReader(InputStreamReader(process!!.inputStream))
            var line: String?
            while (true) {
                line = bufferedReader.readLine()
                if (line == null) break
                log.info("line='$line'")
                when {
                    line.contains(PATTERN_MULTIPLE_DEVICE) -> throw MultipleDevicesAdbException()
                    line.contains(PATTERN_DEVICE_NOT_FOUND) -> throw NoDevicesAdbException()
                    line.contains(PATTERN_RESUMED_ACTIVITY) -> {
                        val matcher = PATTERN_ACTIVITY_NAME.matcher(line)
                        if (!matcher.matches()) {
                            log.error("Could not find the focused Activity in the line")
                            throw ParseAdbException("Could not find the focused Activity in the line")
                        }
                        return matcher.group(2)
                    }
                }
            }
        } catch (e: IOException) {
            log.error("Could not exec adb or read from its process", e)
            throw ExecutionAdbException(e)
        } finally {
            process?.destroy()
        }
        // Reached the end of lines, none of them had info about the focused activity
        throw ParseAdbException("Could not find the focused Activity in the output")
    }

    /**
     * Runs `adb devices` and parses the results to retrieve the list of device ids.
     *
     * @param androidSdkPath Path of the Android SDK (where to find adb).
     * @return The list of device ids.
     */
    @Throws(ExecutionAdbException::class, ParseAdbException::class)
    private fun getDeviceIdList(androidSdkPath: String): List<String> {
        val adbPath = getAdbPath(androidSdkPath)

        val processBuilder = ProcessBuilder(adbPath, "devices")
        processBuilder.redirectErrorStream(true)
        var process: Process? = null
        val res = ArrayList<String>(4)
        try {
            process = processBuilder.start()
            val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (true) {
                line = bufferedReader.readLine()
                if (line == null) break
                log.info("line='$line'")
                if (line.contains(PATTERN_DEVICE_LIST_HEADER)) continue
                val matcher = PATTERN_DEVICE_LIST_ITEM.matcher(line)
                if (!matcher.matches()) {
                    continue
                }
                val deviceId = matcher.group(1)
                res.add(deviceId)
            }
        } catch (e: IOException) {
            log.error("Could not exec adb or read from its process", e)
            throw ExecutionAdbException(e)
        } finally {
            process?.destroy()
        }
        if (res.isEmpty()) {
            // Reached the end of lines, there was no device
            throw ParseAdbException("Could not find devices in the output")
        }

        return res
    }
}

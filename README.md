Open Current Activity Android Studio / IntelliJ Plugin
====

A little plugin for Android development (Android Studio or IntelliJ).

![Illustration](/illus.png?raw=true "Illustration")

Adds an action under **Navigate / Current Activity** (default shortcut: `Ctrl` `F10` on PC, `⌘` `F10` on Mac) that opens the class
corresponding to the currently shown Activity on the attached device or emulator.

Why?
----
To save a few seconds ;)

If your project contains lots of Activities and you can't always quickly remember their names, this is for you.

That's it!


Install / Download
----
- Install it directly inside Android Studio / IntelliJ from the plugins settings (click on *Browse repositories...* and search for *Open Current Activity*).
- Or get the jar and install it manually (click on *Install plugin from disk...*): https://github.com/BoD/OpenCurrentActivityIntelliJPlugin/releases/latest
- The plugin page is here: https://plugins.jetbrains.com/plugin/7877

How does it work?
----
By executing `adb shell dumpsys activity activities` and parsing the results.


Licence
----

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

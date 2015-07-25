Open Current Activity Android Studio / IntelliJ Plugin
====

A little plugin for Android development (Android Studio or IntelliJ).

Adds an action under **Navigate / Current Activity** (default shortcut: `F10`) that opens the class
corresponding to the currently shown Activity on the attached device / emulator.

Why?
----
To save a few seconds ;)

If your project contains lots of Activities and you can't always quickly remember their names, this is for you.

That's it!


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

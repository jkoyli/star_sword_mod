@echo off
rem Star Sword mechanic settings editor launcher.
rem
rem Why this file exists: on Windows the .jar association often points at an ancient
rem Java (this machine: JRE 1.8), and double-clicking a Java-17 jar shows the generic
rem "A Java Exception has occurred." dialog. This script delegates to run-editor.ps1,
rem which picks a Java 17+ JDK before starting anything.
rem
rem Usage: double-click this file. Do NOT double-click the .jar directly.

cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-editor.ps1"
if errorlevel 1 pause

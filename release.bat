@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul
REM ====================================================
REM EVCam Release Script
REM ====================================================

set GRADLE_FILE=app\build.gradle.kts

echo.
echo ====================================================
echo   EVCam 发布助手
echo ====================================================
echo.

REM ====================================================
REM Read version info
REM ====================================================
echo [信息] 读取当前版本信息...

REM Read versionCode
set CURRENT_VERSION_CODE=0
for /f "tokens=*" %%a in ('findstr /R "versionCode" "%GRADLE_FILE%"') do (
    set "LINE=%%a"
)
for /f "tokens=3 delims= " %%b in ("!LINE!") do (
    set CURRENT_VERSION_CODE=%%b
)
echo [信息] 当前 versionCode: !CURRENT_VERSION_CODE!

REM Read versionName
set CURRENT_VERSION_NAME=unknown
for /f "tokens=*" %%a in ('findstr /R "versionName" "%GRADLE_FILE%"') do (
    set "LINE=%%a"
)
for /f "tokens=3 delims= " %%b in ("!LINE!") do (
    set "TEMP=%%~b"
)
REM Remove quotes
set CURRENT_VERSION_NAME=!TEMP:"=!
echo [信息] 当前 versionName: !CURRENT_VERSION_NAME!

REM Check for -test- suffix
set BASE_VERSION_NAME=!CURRENT_VERSION_NAME!
set IS_TEST_VERSION=0
echo !CURRENT_VERSION_NAME! | findstr /C:"-test-" > nul
if !ERRORLEVEL! EQU 0 (
    set IS_TEST_VERSION=1
    REM Extract base version
    for /f "tokens=1 delims=-" %%b in ("!CURRENT_VERSION_NAME!") do (
        set BASE_VERSION_NAME=%%b
    )
    echo [信息] 检测到测试版本，基础版本号: !BASE_VERSION_NAME!
)
echo.

REM ====================================================
REM Auto increment versionCode
REM ====================================================
set /a NEW_VERSION_CODE=!CURRENT_VERSION_CODE!+1
echo [自动] 新 versionCode: !NEW_VERSION_CODE! (自动递增)

REM ====================================================
REM User input versionName
REM ====================================================
echo.
echo [提示] 请输入 versionName (例如 1.0.3), 直接回车使用: !BASE_VERSION_NAME!
set /p NEW_VERSION_NAME="versionName: "

if "!NEW_VERSION_NAME!"=="" (
    set NEW_VERSION_NAME=!BASE_VERSION_NAME!
    echo [信息] 使用版本名: !NEW_VERSION_NAME!
)

REM Set Git Tag version
set VERSION=v!NEW_VERSION_NAME!

echo.
echo ====================================================
echo   版本确认
echo ====================================================
echo   versionCode: !CURRENT_VERSION_CODE! -^> !NEW_VERSION_CODE!
echo   versionName: !CURRENT_VERSION_NAME! -^> !NEW_VERSION_NAME!
echo   Git Tag:     !VERSION!
echo ====================================================
echo.
set /p CONFIRM="确认继续？(Y/N): "
if /i not "!CONFIRM!"=="Y" (
    echo.
    echo [取消] 用户取消操作
    echo.
    pause
    exit /b 0
)

REM ====================================================
REM Update build.gradle.kts
REM ====================================================
echo.
echo [更新] 正在更新 %GRADLE_FILE%...

REM Use PowerShell to update
powershell -Command "(Get-Content '%GRADLE_FILE%') -replace 'versionCode = %CURRENT_VERSION_CODE%', 'versionCode = %NEW_VERSION_CODE%' | Set-Content '%GRADLE_FILE%' -Encoding UTF8"
if errorlevel 1 (
    echo [错误] 更新 versionCode 失败！
    pause
    exit /b 1
)

powershell -Command "(Get-Content '%GRADLE_FILE%') -replace 'versionName = \"%CURRENT_VERSION_NAME%\"', 'versionName = \"%NEW_VERSION_NAME%\"' | Set-Content '%GRADLE_FILE%' -Encoding UTF8"
if errorlevel 1 (
    echo [错误] 更新 versionName 失败！
    pause
    exit /b 1
)

echo [完成] build.gradle.kts 已更新
echo.
echo [信息] 版本号: !VERSION!

REM Step 0: Check uncommitted changes
echo [0/6] 检查 Git 状态...
git diff --quiet
set HAS_CHANGES=%ERRORLEVEL%
git diff --cached --quiet
set HAS_STAGED=%ERRORLEVEL%

set NEED_COMMIT=0
if !HAS_CHANGES! NEQ 0 set NEED_COMMIT=1
if !HAS_STAGED! NEQ 0 set NEED_COMMIT=1

if !NEED_COMMIT! EQU 0 (
    echo [信息] 工作区干净
    goto skip_commit
)
echo [提示] 检测到未提交的更改

echo.
set /p DO_COMMIT="是否提交这些更改? (Y/N): "
if /i not "!DO_COMMIT!"=="Y" goto skip_commit

echo.
echo [提示] 请输入提交信息 (直接回车使用默认: Release !VERSION!)
set /p COMMIT_MSG="提交信息: "
if "!COMMIT_MSG!"=="" set COMMIT_MSG=Release !VERSION!

echo [提交] 正在提交更改...
git add .
git commit -m "!COMMIT_MSG!"
if errorlevel 1 goto commit_failed

echo [推送] 推送到远程仓库...
REM Get current branch
for /f "tokens=*" %%i in ('git branch --show-current') do set CURRENT_BRANCH=%%i
git push origin !CURRENT_BRANCH!
echo [完成] 代码已提交并推送
echo.

:skip_commit

REM Step 1: Clean
echo [1/6] 清理旧的构建文件...
call gradlew.bat clean
if errorlevel 1 goto build_error
echo [完成] 清理完成
echo.

REM Step 2: Build Release APK
echo [2/6] 构建签名的 Release APK...
call gradlew.bat assembleRelease
if errorlevel 1 goto build_error
echo [完成] 构建成功
echo.

REM Check APK generated
set APK_PATH=app\build\outputs\apk\release\app-release.apk
if not exist "%APK_PATH%" goto apk_not_found

REM Rename APK
set RENAMED_APK=app\build\outputs\apk\release\EVCam-!VERSION!-release.apk
copy "%APK_PATH%" "!RENAMED_APK!" > nul
echo [完成] APK 已重命名为: EVCam-!VERSION!-release.apk
echo.

REM Step 3: Create Git Tag
echo [3/6] 创建 Git Tag...
git tag -a !VERSION! -m "Release !VERSION!"
echo [推送] 推送 Tag 到远程仓库...
git push origin !VERSION!
if errorlevel 1 goto tag_push_error
echo [完成] Tag 推送成功
echo.

REM Step 4: Check GitHub CLI
echo [4/6] 检查 GitHub CLI...
where gh > nul 2>&1
if errorlevel 1 goto no_gh_cli
echo [完成] GitHub CLI 可用
echo.

REM Step 5: Release Notes
echo [5/6] 准备发布说明...
echo.
echo [提示] 请输入发布说明 (直接回车留空)
set /p RELEASE_NOTES="发布说明: "
echo.

REM Step 6: Create GitHub Release
echo [6/6] 创建 GitHub Release...
if "!RELEASE_NOTES!"=="" (
    gh release create !VERSION! "!RENAMED_APK!" --title "EVCam !VERSION!" --notes ""
) else (
    gh release create !VERSION! "!RENAMED_APK!" --title "EVCam !VERSION!" --notes "!RELEASE_NOTES!"
)
if errorlevel 1 goto release_error

echo.
echo ====================================================
echo [成功] Release 发布完成!
echo ====================================================
echo.
echo 版本: !VERSION!
echo APK: !RENAMED_APK!
echo.
echo 查看 Release: gh release view !VERSION! --web
echo.
exit /b 0

REM Error handlers
:commit_failed
echo [错误] 提交失败!
pause
exit /b 1

:build_error
echo [错误] 构建失败!
exit /b 1

:apk_not_found
echo [错误] APK not found: %APK_PATH%
exit /b 1

:tag_push_error
echo [错误] Tag 推送失败!
echo   1. Tag 可能已存在
echo   2. 网络问题
echo   3. 权限不足
exit /b 1

:no_gh_cli
echo [警告] GitHub CLI (gh) not found
echo.
echo 请手动创建 Release:
echo   1. https://github.com/suyunkai/EVCam/releases/new
echo   2. Tag: !VERSION!
echo   3. Upload: !RENAMED_APK!
echo.
echo APK: !RENAMED_APK!
echo.
pause
exit /b 0

:release_error
echo [错误] Release 创建失败!
echo   1. 运行 gh auth login 登录
echo   2. 检查仓库权限
echo   3. Tag 可能已有 Release
exit /b 1

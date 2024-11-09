exit /b 0

:fail
echo ERROR: Amper bootstrap failed, see errors above
exit /b 1

:after_function_declarations

REM ********** Provision Amper distribution **********

set amper_url=%AMPER_DOWNLOAD_ROOT%/org/jetbrains/amper/cli/%amper_version%/cli-%amper_version%-dist.tgz
set amper_target_dir=%AMPER_BOOTSTRAP_CACHE_DIR%\amper-cli-%amper_version%
call :download_and_extract "Amper distribution v%amper_version%" "%amper_url%" "%amper_target_dir%" "%amper_sha256%" "256"
if errorlevel 1 goto fail

REM ********** Provision JRE for Amper **********

if defined AMPER_JAVA_HOME goto jre_provisioned

@rem Auto-updated from syncVersions.main.kts, do not modify directly here
set jbr_version=21.0.4
set jbr_build=b509.26
if "%PROCESSOR_ARCHITECTURE%"=="ARM64" (
    set jbr_arch=aarch64
    set jbr_sha512=9fd2333f3d55f0d40649435fc27e5ab97ad44962f54c1c6513e66f89224a183cd0569b9a3994d840b253060d664630610f82a02f45697e5e6c0b4ee250dd1857
) else if "%PROCESSOR_ARCHITECTURE%"=="AMD64" (
    set jbr_arch=x64
    set jbr_sha512=6a639d23039b83cf1b0ed57d082bb48a9bff6acae8964192a1899e8a1c0915453199b501b498e5874bc57c9996d871d49f438054b3c86f643f1c1c4f178026a3
) else (
    echo Unknown Windows architecture %PROCESSOR_ARCHITECTURE% >&2
    goto fail
)

@rem URL for JBR (vanilla) - see https://github.com/JetBrains/JetBrainsRuntime/releases
set jbr_url=%AMPER_JRE_DOWNLOAD_ROOT%/cache-redirector.jetbrains.com/intellij-jbr/jbr-%jbr_version%-windows-%jbr_arch%-%jbr_build%.tar.gz
set jbr_target_dir=%AMPER_BOOTSTRAP_CACHE_DIR%\jbr-%jbr_version%-windows-%jbr_arch%-%jbr_build%
call :download_and_extract "JetBrains Runtime v%jbr_version%%jbr_build%" "%jbr_url%" "%jbr_target_dir%" "%jbr_sha512%" "512"
if errorlevel 1 goto fail

set AMPER_JAVA_HOME=
for /d %%d in ("%jbr_target_dir%\*") do if exist "%%d\bin\java.exe" set AMPER_JAVA_HOME=%%d
if not exist "%AMPER_JAVA_HOME%\bin\java.exe" (
  echo Unable to find java.exe under %jbr_target_dir%
  goto fail
)
:jre_provisioned

REM ********** Launch Amper **********

set jvm_args=-ea -XX:+EnableDynamicAgentLoading %AMPER_JAVA_OPTIONS%
"%AMPER_JAVA_HOME%\bin\java.exe" "-Damper.wrapper.dist.sha256=%amper_sha256%" %jvm_args% -cp "%amper_target_dir%\lib\*" org.jetbrains.amper.cli.MainKt %*
exit /B %ERRORLEVEL%

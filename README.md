# JavaPad

JavaPad is an offline-first Android Java editor and on-device compiler. Work is being delivered in phases. Phases 1–4 are implemented; Android build/device validation is still pending.

## Phase 1: editor

- Single-activity Kotlin app using Jetpack Compose and Material 3.
- Android min SDK 26, target SDK 34, Gradle Kotlin DSL.
- Sora Editor with Java tree-sitter highlighting, line numbers, bracket matching, auto-indent and auto-close for brackets and quotes.
- Bundled JetBrains Mono font, adjustable font size, and a horizontal symbol row with undo/redo.
- Light, dark and AMOLED themes; appearance preferences persist in app-private DataStore.
- Default `Main.java` starter source.

## Phase 2: compile and run

- Run/Stop controls use a bound service in the separate `:runner` process. Stop and timeout terminate that process so arbitrary Java loops do not pin the UI process.
- ECJ compiles Java 17 source against the generated API 34 `android.jar` asset and app runtime classpath jars. D8 converts class files to indexed DEX; `DexClassLoader` loads the entry class and reflection invokes `main(String[])`.
- Output and Problems tabs capture stdout and stderr (stderr is red); compile diagnostics include file, line, column and message. Tapping a problem jumps to its source line, with Sora diagnostic underlines.
- A nonblocking stdin field sends lines to `System.in` for `Scanner` and `BufferedReader` input.
- Execution duration and approximate process memory delta are displayed after a run.
- The run timeout is configurable in Settings (5–120 seconds) and saved in DataStore.

## Build and test

Requirements: Android Studio or Android SDK Platform 34 and 35, Build Tools 35.0.0, and JDK 17 or newer. The Gradle wrapper is included. The build copies the SDK API 34 `android.jar` and the app's resolved runtime JAR/AAR classes into generated assets for on-device compilation.

1. Open the repository in Android Studio and allow Gradle sync to finish.
2. Connect an Android 8.0+ device or start an API 26+ emulator.
3. Build and install with:
   ```sh
   ./gradlew :app:installDebug
   ```
4. Tap **Run** with the default `Main.java`; confirm `Hello, JavaPad!` appears in Output and execution stats are shown.
5. Change the print string and run again. Try a syntax error: Problems should show the location, the error should be underlined, and tapping it should move the editor to that line.
6. Replace the body with `System.err.println("problem");` and confirm it appears red. Add `import java.util.Scanner;` at the top, then try `Scanner scanner = new Scanner(System.in); System.out.println(scanner.nextLine());`. Enter a line in the Output panel and tap **Send**.
7. For cancellation, run `while (true) {}` and tap **Stop**. To check timeout, set Run timeout to 5 seconds, run the loop, and verify the runner is terminated after the limit.
8. Force-stop and reopen JavaPad to check theme, font size, and timeout persistence.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds it on every push and uploads it as the `app-debug` artifact.

## Architecture

- `editor/`: Sora editor and editor ViewModel.
- `compiler/`: ECJ → D8 → `DexClassLoader` pipeline, Messenger-based bound runner service, StateFlow UI model.
- `settings/`: DataStore repository and settings ViewModel.
- `project/`: app-private multi-project/file repository and project ViewModel.
- `ui/`: Compose editor, project screens and controls, and Material 3 theme.
- `MainActivity` is the sole activity; Hilt is initialized by `JavaPadApplication`.

The Sora integration follows the Sora Editor 0.24.4 release/demo; its Java tree-sitter query files and license are included in assets. JetBrains Mono is bundled under its SIL Open Font License (`app/src/main/assets/fonts/OFL.txt`). The app does not declare internet permission or use a remote compilation API.

## Phase 3: autocomplete

- JavaParser-backed suggestions collect declarations, fields, methods, parameters, imports, and current-file types; member suggestions use runtime reflection.
- Android/JDK class names are indexed from the bundled `android.jar` in the background and cached privately on disk.
- Typing and `.` trigger debounced suggestions; the symbol row includes a Ctrl+Space-style trigger. Popup rows show kind, type, and signature. Selecting an indexed class adds its missing import.
- Common Java keywords/snippets are included; the Tab symbol cycles basic snippet tab stops. Parameter hints cover common `print`/`parse` calls. Unresolved-type problems expose an Add import quick fix.

## Phase 3 verification and limitations

- Suggestions are heuristic, not a full Java semantic model. Scope resolution is approximate, and member reflection is limited to classes available to the app class loader. Phase 5 extends type/member suggestions across source files in the active project.
- The autocomplete unit tests are included but were not run in this environment.

## Phase 4: projects and files

- Projects and Java source files are stored in app-private storage. Projects can be created, renamed, deleted, opened, ZIP-imported through the system picker, and exported through the system document picker.
- A project supports multiple Java files, package-folder paths, file tabs, create/rename/duplicate/delete actions, and an entry-point selector. The compiler receives all project sources together.
- The active project/file and cursor positions are saved; source edits autosave after a short debounce and on app background.

## Phase 4 verification and limitations

- ZIP import accepts Java sources and JavaPad's project metadata; it rejects absolute and parent-traversal paths and limits uncompressed archive content to 50 MB. Project and active-file state are stored privately, with cursor positions and edits saved across app restarts.
- No Android build or device validation has been performed in this environment; see the verification note below.

## Phase 5: project-aware autocomplete

- Completion parses sibling Java source files in the active project and suggests their declared classes, including a qualified import when a type belongs to another package.
- Member completion resolves fields and methods declared on project types as well as the existing reflected JDK/Android members. The manual Ctrl+Space trigger uses the same project context.
- Unit tests cover cross-file type, import, and member suggestions; execution remains pending until a JDK/Android build environment is available.

## Phase 6: local project libraries

- Import local JARs into a project through Android's document picker and remove them from the project controls. The combined JAR storage limit is 40 MB.
- Project ZIP exports/imports preserve JARs and enforce a 50 MB uncompressed archive limit. ECJ compiles against local JARs and D8 bundles their bytecode into the app's isolated runner process; no online service or internet permission is used.

## Phase 6 verification and limitations

- The library flow and D8 input changes are implemented but have not been built or exercised on a device in this environment.

## Previous runner verification and limitations

- The full ECJ/D8/DexClassLoader on-device pipeline has **not yet been built or run on an Android device** in this agent environment. Its build/run steps above should be treated as a validation checklist, not a verified result.
- The runner compiles all Java source files in the active project together, adds imported project JARs to ECJ's classpath and D8's program inputs, and loads the selected entry-point class.
- Java 17 syntax is sent to ECJ and D8. Whether newer constructs (notably records) work on API 26 devices depends on the exact ECJ/D8 runtime behavior and needs device validation; Java 11/17 feature compatibility is not claimed as verified yet.

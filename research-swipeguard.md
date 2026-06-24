# Research: Xposed Module Hook Failure Analysis — ColorOS 16 SwipeGuard

## Summary

SwipeGuard-style Xposed modules on ColorOS 16 fail to intercept kills primarily due to: (1) **missing/incorrect LSPosed scope** — system_server hooks require `system` in scope.list, and for modern modules the semantics differ from legacy; (2) **PROTECTIVE exception mode silently swallowing errors** — the default mode catches exceptions and logs via Xposed's internal mechanism (not logcat), making hook failures invisible; (3) **OTA-level kill flow evolution** — ColorOS 16.0.5.701+ aggressively routes kills through new paths including `athenaKill3` batch API (Binder code 201) and `clearProcess` that may bypass the `r3.c.forceStopPackageAndSaveActivity` hook; (4) **FileInputStream hook is inherently fragile** — system config may be read via `FileChannel`, native `open()`, or ABX (Android Binary XML) parser, all bypassing the Java-level FileInputStream constructor hook.

## Findings

1. **LSPosed scope for system_server: `system` (not `android`, not `com.oplus.athena`)** — For modern libxposed API modules using `META-INF/xposed/scope.list`, `system_server` is identified by the package name `system`. Since LSPosed v1.9.0, the semantics were corrected: `system` = system_server (uid=1000, proc=system), while `android` = system UI processes (ChooserActivity, ResolverActivity). If the scope.list contains only `com.oplus.athena` (as OplusConfigHook does), system_server methods (AMS, OplusActivityManager, FileInputStream) will NOT be hooked — only the Athena app process is targeted. SwipeGuard must have `system` in its scope list for any system_server hook to fire. [Source: LSPosed v1.9.1 Discussion #2728](https://github.com/LSPosed/LSPosed/discussions/2728), [Source: LSPosed commit 6f6c4b6](https://github.com/LSPosed/LSPosed/commit/6f6c4b67d736e96a61f89b5db22c2e9bbde19461)

2. **`setExceptionMode(PROTECTIVE)` suppresses errors from logcat** — The PROTECTIVE mode (default) catches ALL exceptions thrown by hooker code, logs them via `XposedInterface.log()` (which goes to Xposed's internal log), and allows the call to proceed as if no hook exists. Crucially, **the log is NOT automatically forwarded to Android logcat unless the module explicitly calls `log(int priority, String tag, String msg, Throwable tr)` with appropriate priority**. A hook that silently fails (e.g., method signature mismatch after OTA, class not found due to ProGuard rename, null pointer in hook logic) will produce zero visible output in `logcat -s LSPosed` or `logcat -s SwipeGuard`. The libxposed API added a rich logging API (commit c205f08) to address this, but modules must actively use it. To debug: set `exceptionMode=passthrough` in `module.prop` temporarily, or add explicit `XposedInterface.log(Log.ERROR, ...)` calls in every hook's catch block. [Source: libxposed API docs](https://libxposed.github.io/api/io/github/libxposed/api/XposedInterface.ExceptionMode.html), [Source: libxposed API PR #51](https://github.com/libxposed/api/pull/51), [Source: libxposed rich logging commit](https://github.com/libxposed/api/commit/c205f085d77edb60026845972a3b3dca232f8240)

3. **ColorOS 16 OTA (≥16.0.5.701) makes kill flow more aggressive and may shift to athenaKill3 batch API** — The reverse engineering report (v6.0.1 Athena APK) identifies `athenaKill3` (Binder code 201) as a batch kill method taking `List<Bundle>`. While the primary kill path documented in §7.1 of the report shows `clearProcess → r3.c.forceStopPackageAndSaveActivity()` as the swipe-kill flow, user reports on ColorOS 16.0.5.701 (CPH2689) describe dramatically more aggressive background killing. This suggests the firmware OTA may have shifted more kill decisions to either (a) the `athenaKill3` batch path which SwipeGuard does not hook, or (b) a new ProGuard-obfuscated class name for `r3.c` (which could have been renamed in a v6.0.2+ Athena update). The `AthenaKillHooks` in SwipeGuard currently hooks `athenaKill` and `athenaKill2` on `OplusAthenaSystemService`, but **not `athenaKill3`** (code 201). If OTA now routes batch kills through `athenaKill3`, those bypass the module entirely. [Source: reverse-system-athena.md §2.1, §7.1](file:///data/data/com.termux/files/home/athena/.pi/context/reverse-system-athena.md), [Source: ColorOS 16.0.5.701 bug report](https://community.oppo.com/thread/2133887041509785608)

4. **FileInputStream hook is easily bypassed by the system** — Android's config file reading has multiple paths that do not use `java.io.FileInputStream`:

   | Reading method | Bypasses FileInputStream hook? | Used by Athena? |
   |---|---|---|
   | `new FileInputStream(file)` | **No** — constructor is hookable | Possibly, but not confirmed |
   | `BufferedInputStream(new FileInputStream(file))` | **No** — still calls FileInputStream ctor | If chain starts with FIS |
   | `FileChannel.open(path)` via NIO | **Yes** — uses native `FileDispatcher` internally, no Java FIS constructor | Likely — NIO is common for large config files |
   | Native `open()`/`mmap()` via JNI | **Yes** — completely Java-level invisible | Possible |
   | ABX (Android Binary XML) parser | **Yes** — Android uses `Xml.newPullParser()` + ABX magic header, file can be opened via any method | **YES** — The reverse report shows ELSA config (`sys_elsa_config_list.xml`) is parsed by `g2/e` using XmlPullParser, but the file source could be a byte array from any read method |
   | `ParcelFileDescriptor` + auto-create stream | **Partially** — stream is not a FileInputStream | Possible for Binder-based file transfer |

   The reverse report (§5.1) confirms the ELSA config is 85KB and parsed by OFreezer3.0 parser via XmlPullParser. If the system reads this file via `FileChannel.open()` or native `mmap()`, the Java-level FileInputStream hook never fires. Even if the hook fires on the initial read, the config may be **cached in memory** after boot — subsequent reads (and hot-reloads) never touch the file again, so the hook would only fire once at boot. [Source: reverse-system-athena.md §5.1](file:///data/data/com.termux/files/home/athena/.pi/context/reverse-system-athena.md), [Source: Android FileChannel NIO path](https://android.googlesource.com/platform/libcore/+/a47f800/luni/src/main/java/java/io/FileInputStream.java), [Source: ABX Binary XML research](https://github.com/michalbednarski/AbxOverflow)

5. **athenaKill3 IS a real kill API that SwipeGuard misses** — The reverse report API surface (§2.1) enumerates 4 kill methods on `IAthenaService`:

   | Method | Binder code | Signature | Hooks in SwipeGuard? |
   |--------|------------|-----------|---------------------|
   | `athenaKill` | 100 | `(int uid, int pid, String pkg, int level, int flag) → int` | ✅ (AthenaKillHooks) |
   | `athenaFreeze` | 101 | `(int uid, int pid, String pkg, int level, int flag) → int` | ❌ (freeze, not kill) |
   | `athenaKill2` | 102 | `(... + int reason) → int` | ✅ (AthenaKillHooks) |
   | `athenaKill3` | **201** | `(List<Bundle> killData) → int` | **❌ NOT HOOKED** |
   | `clearProcess` | 223 | `(Bundle bundle)` | ❌ Partially via SwipeKillHooks |
   | `scheduleNoteNativeProcessKill` | 248 | `(Bundle, int reason, int subReason)` | ❌ |

   The `athenaKill3` batch API (code 201) takes a list of Bundles, each presumably containing `{packageName, uid, pid, reason, ...}`. This is designed for batch kills during memory pressure or thermal events. If ColorOS 16 OTA now batches kills through this single API call rather than issuing individual `forceStopPackageAndSaveActivity` calls, SwipeGuard's hooks on the per-package kill path would never fire. **This is a high-confidence gap.** [Source: reverse-system-athena.md §2.1](file:///data/data/com.termux/files/home/athena/.pi/context/reverse-system-athena.md)

6. **Related projects use different hook strategies on ColorOS**:

   | Project | Scope | Hook Target | Approach |
   |---------|-------|------------|----------|
   | **OplusConfigHook** | `com.oplus.athena` + `com.oplus.battery` | XML config reading in Athena app process | Modifies OFreezer whitelist at config-read time (not system_server) |
   | **FuckAndes** | `system` + `com.android.systemui` + `com.coloros.directui` + Google App | `PhoneWindowManagerExtImpl$OplusSpeechHandler.handleMessage` in system_server | System_server hook works (confirmed on ColorOS 16.0.5.704) |
   | **OShin** | Multiple system scopes | Various ColorOS services | system_server hooks active, but ColorOS 16 broke some hooks (changelog confirms "取消Hook C16") |
   | **ColorOSTool** | `system` + `com.android.systemui` | system_server + SystemUI | Older, ColorOS 12 only |

   **Key insight**: OplusConfigHook deliberately targets `com.oplus.athena` (the app) NOT system_server, because it modifies config at read time inside the Athena process itself. SwipeGuard targets system_server because it intercepts kill calls. Both approaches have merit, but SwipeGuard's approach (hooking `r3.c.forceStopPackageAndSaveActivity`) is fragile because the obfuscated class names change with each Athena APK update. [Source: OplusConfigHook README](https://github.com/AstorBlithe/OplusConfigHook), [Source: FuckAndes TECH.md](https://github.com/wowohut/fuck-andes/blob/main/docs/TECHNICAL.md)

7. **LSPosed fork compatibility issues on Android 16 / ColorOS 16** — Multiple user reports confirm that ColorOS 16 / Android 16 requires a specific LSPosed fork (JingMatrix/LSPosed). Issues include:
   - **"SEPolicy is not loaded properly"** error requiring Magisk 30.6+ for new sepolicy binary format on Android 16 QPR2
   - LSPosed manager crash on notification tap (issue #498, fixed in CI builds)
   - Database incompatibility when upgrading from lsposed-it fork (issue #492)
   - Official LSPosed was archived May 2, 2026 — users must use community forks
   - Modules must be compatible with LSPosed v1.10+ for Android 16 Zygisk API changes
   - **If the user is on an official/old LSPosed build, modules silently fail to load in system_server** — LSPosed itself may not inject into system_server at all, and the module's `onSystemServerLoaded` never fires. [Source: JingMatrix/LSPosed Issue #339](https://github.com/JingMatrix/LSPosed/issues/339), [Source: Issue #527](https://github.com/JingMatrix/Vector/issues/527), [Source: Issue #498](https://github.com/JingMatrix/LSPosed/issues/498)

## Sources

### Kept Sources
- **LSPosed v1.9.1 Discussion #2728** (https://github.com/LSPosed/LSPosed/discussions/2728) — Definitively explains `system` vs `android` scope semantics; critical for understanding why scope must include `system` for system_server hooks.
- **LSPosed commit 6f6c4b6** (https://github.com/LSPosed/LSPosed/commit/6f6c4b67d736e96a61f89b5db22c2e9bbde19461) — Code change confirming system_server uses package name "android" internally but scope uses "system".
- **libxposed API docs — ExceptionMode** (https://libxposed.github.io/api/io/github/libxposed/api/XposedInterface.ExceptionMode.html) — Official docs: PROTECTIVE catches and logs exceptions via Xposed log (not logcat).
- **libxposed API PR #51** (https://github.com/libxposed/api/pull/51) — RFC documenting default exception mode and PROTECTIVE behavior.
- **libxposed rich logging API commit c205f08** (https://github.com/libxposed/api/commit/c205f085d77edb60026845972a3b3dca232f8240) — Shows logging API extension; log method does not auto-forward to logcat.
- **reverse-system-athena.md** (in-project report) — Complete Athena APK v6.0.1 reverse; documents IAthenaService API surface (athenaKill3 at code 201), kill flow via r3.c, config parsing via g2/e XmlPullParser.
- **OplusConfigHook README** (https://github.com/AstorBlithe/OplusConfigHook) — Similar ColorOS background tool; uses scope `com.oplus.athena` + `com.oplus.battery` (not system).
- **FuckAndes TECH.md** (https://github.com/wowohut/fuck-andes/blob/main/docs/TECHNICAL.md) — Confirms system_server hooking works on ColorOS 16.0.5.704.
- **ColorOS 16.0.5.701 Bug Report** (https://community.oppo.com/thread/2133887041509785608) — User-confirmed aggressive background kill behavior after OTA.
- **JingMatrix/LSPosed Issue #339** (https://github.com/JingMatrix/LSPosed/issues/339) — LSPosed broken on Android 15/16, CI build fix.
- **JingMatrix/LSPosed Issue #527 (SEPolicy)** (https://github.com/JingMatrix/Vector/issues/527) — Android 16 QPR2 sepolicy loading requiring Magisk 30.6+.
- **ABX Overflow research** (https://github.com/michalbednarski/AbxOverflow) — Android Binary XML used by system services, bypasses standard XML parsing.
- **Android FileInputStream.java** (https://android.googlesource.com/platform/libcore/+/a47f800/luni/src/main/java/java/io/FileInputStream.java) — FileChannel path does not use FIS constructor.
- **LuckyTool on LSPosed Module Repository** (https://modules.lsposed.org/module/com.luckyzyx.luckytool/) — Changelog: "取消控制中心磁贴数量限制Hook C16" confirming ColorOS 16 breaks hooks.

### Dropped Sources
- General Android Hook anti-detection (meituan tech) — Xposed detection, not ColorOS hook failure.
- FHook project — General-purpose hook lib, no ColorOS insight.
- StackOverflow killBackgroundProcesses — Standard Android API, not Xposed.
- B4X forum — Standard Android killProcess.
- Athena-OS (Linux distro) — Unrelated.
- OS Updater article — General ColorOS 16 announcement, no technical detail.

## Gaps

1. **No confirmed `athenaKill3` usage in actual kill flow** — The reverse report identifies the API but does not confirm which caller invokes `athenaKill3` vs `clearProcess`. Real-world logcat traces of kill events on ColorOS 16.0.5.701+ are needed to confirm whether `athenaKill3` is the primary kill path.

2. **Unknown if `r3.c` class name changed in newer Athena versions** — The reverse is based on v6.0.1. If OTA updated Athena to v6.0.2+ with different ProGuard obfuscation, `r3.c` would differ and the hook silently fails.

3. **Unknown if `OplusAthenaSystemService` Binder API changed** — The `athenaKill3` method may have a different signature or be in a different service class in newer OTAs.

4. **No direct logcat evidence of PROTECTIVE swallowing** — While docs confirm behavior, a real test with `adb logcat -s LSPosed:Xposed:SwipeGuard:*` during a kill event on target device would provide definitive evidence.

5. **No data on whether config files use ABX format on ColorOS 16** — If `sys_elsa_config_list.xml` is actually stored as ABX, the FileInputStream hook never fires.

## Suggested Next Steps

1. **Add `athenaKill3` hook in `AthenaKillHooks.kt`** — Hook `athenaKill3` on `OplusAthenaSystemService` with signature `(List<Bundle>)`. Priority: HIGH.

2. **Set `exceptionMode=passthrough` in `module.prop` for debug builds** — Makes hook exceptions propagate to logcat immediately. Release builds should use `protective`.

3. **Add explicit logging in every hook catch block** — Use `XposedInterface.log(Log.ERROR, "SwipeGuard", msg, throwable)` so errors appear in `adb logcat -s SwipeGuard`.

4. **Verify scope.list includes `system`** — Ensure `META-INF/xposed/scope.list` contains exactly `system` for system_server hooks.

5. **Consider hooking `g2/e$d.M` (whitePkg parser) instead of FileInputStream** — The OplusConfigHook approach of modifying config at parse time inside Athena's own process may be more reliable than intercepting file reads.

6. **Test on actual device** — Run `adb logcat -s LSPosed:Xposed:SwipeGuard:*` while performing swipe-kill on a protected app to see if hooks fire at all.

# SwipeGuard Progress

## Research Task: Xposed Hook Failure Analysis on ColorOS 16

**Status**: ✅ Complete
**Output**: `/data/data/com.termux/files/home/athena/research-swipeguard.md`

### Key Findings
1. **Scope**: system_server requires `system` in scope.list (not `android`, not `com.oplus.athena`)
2. **PROTECTIVE mode**: Silently swallows hook errors — exceptions go to Xposed internal log, NOT logcat
3. **athenaKill3 gap**: Batch kill API (Binder code 201) exists in IAthenaService but is NOT hooked by SwipeGuard
4. **FileInputStream fragility**: Config can be read via FileChannel, native open(), or ABX — all bypass Java-level FIS hook
5. **LSPosed fork**: Official LSPosed archived; ColorOS 16 requires JingMatrix fork with Magisk 30.6+
6. **Related projects**: OplusConfigHook (scope: athena+battery) and FuckAndes (scope: system+SystemUI) provide reference approaches

---

## Failure Mode Analysis: "加入白名单的软件还是被删了"

**Status**: ✅ Complete
**Output**: `/data/data/com.termux/files/home/athena/scout-failure-modes.md`

### Top 3 Failure Modes
1. **x3.d.killProcess un-hooked** (85% confidence) — Ultimate kill execution point; all paths converge here
2. **streamStates stale after hot-add** (80% confidence) — `updateConfig()` doesn't refresh the hijacked XML buffer
3. **RemotePreferences Binder disconnect** (60% confidence) — UI process killed → no config sync to system_server

### Recommended Quick Fixes
1. Fix `OplusConfigHooks.updateConfig()` to refresh `streamStates` (~15 lines)
2. Add WARN logs to `AthenaKillHooks` on class-not-found (~5 lines)
3. Hook `x3.d.killProcess` as SwipeKillHooks path 5 (~40 lines)

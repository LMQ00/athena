# 主题 2: libxposed 102.0.0 API 与最佳实践

## 摘要

libxposed API 102.0.0（2026-06-14 发布）是 Xposed 框架的现代替代方案，采用**拦截器链模型**（类似 OkHttp 拦截器）替代传统 XposedBridge API。核心接口 `XposedInterface` 定义了 Hooker/Chain/HookHandle/Invoker 体系，并通过 `HookBuilder` 模式配置 hook。

## 关键发现

### 1. `XposedInterface.Chain` vs 旧 `HookChain`
- **来源**: https://github.com/libxposed/api/blob/master/api/src/main/java/io/github/libxposed/api/XposedInterface.java
- **`Chain`** 是 `XposedInterface` 的内部接口，代表方法/构造函数调用的拦截链。
- **不存在独立的 `HookChain` 类** — libxposed API 从一开始就使用 `XposedInterface.Chain` 作为拦截链模型。
- 旧版 XposedBridge 没有正式的 chain 模型；libxposed 用 `Chain` + `Hooker` 彻底替代了旧的 `beforeHookedMethod`/`afterHookedMethod` 回调。
- Chain 对象**不可跨线程共享**，**不可在 intercept() 返回后重用**。

### 2. `intercept { chain -> ... }` 的正确用法
- **来源**: XposedInterface.java 源文件 + package-info.java 文档
- Hook 通过 `hook(Executable)` 返回的 `HookHandle` + `HookBuilder` 模式配置：

```java
// 完整 hook 流程
HookHandle handle = hook(method)
    .setPriority(PRIORITY_DEFAULT)         // 可选，默认 50
    .setExceptionMode(ExceptionMode.PROTECTIVE) // 可选
    .intercept(chain -> {                   // 核心：匿名 Hooker
        // 1. 读取参数
        Object arg0 = chain.getArg(0);
        List<Object> args = chain.getArgs();
        Object thisObj = chain.getThisObject();

        // 2. 修改参数（可选）：调用 proceed 传入新参数
        // Object result = chain.proceed(new Object[]{newArg0, newArg1});

        // 3. 继续执行 chain
        Object result = chain.proceed();  // 或 chain.proceed(Object[])

        // 4. 修改返回值（可选）
        // return modifiedResult;
        return result;
    });
```

- **关键方法**:
  - `chain.getExecutable()` — 获取被 hook 的方法/构造函数
  - `chain.getThisObject()` — 获取调用实例（静态方法为 null）
  - `chain.getArgs()` — 获取不可变参数列表
  - `chain.getArg(int index)` — 按索引获取参数
  - `chain.proceed()` — 继续执行 chain（同参数）
  - `chain.proceed(Object[] args)` — 带新参数继续执行
  - `chain.proceedWith(Object thisObject)` — 替换 `this` 继续
  - `chain.proceedWith(Object thisObject, Object[] args)` — 同时替换 `this` 和参数

### 3. `XposedInterface.ExceptionMode.PROTECTIVE` 语义
- **来源**: XposedInterface.java 枚举定义
- **`PROTECTIVE`（推荐模式）**:
  - Hooker 内抛出的异常会被框架**捕获并记录日志**
  - 如果异常发生在 `chain.proceed()` **之前**，框架**跳过该 hook** 继续 chain
  - 如果异常发生在 `chain.proceed()` **之后**，框架使用之前的 proceed 结果/异常
  - `chain.proceed()` 本身抛出的异常**始终传播**（不会被静默吞掉）
- **`PASSTHROUGH`** 模式：异常直接传播给调用方，适合调试
- **`DEFAULT`** 模式：遵循 `module.prop` 中 `exceptionMode` 配置，未配置时默认 `PROTECTIVE`

### 4. `module.hook(method)` 返回的 `HookHandle` 接口
- **来源**: XposedInterface.java 源文件
- **`hook(Executable)`** 返回的不是 Handle，而是一个**中间 builder 对象**，支持链式调用：
  - `.setPriority(int)` — 设置优先级（默认 `PRIORITY_DEFAULT = 50`）
  - `.setExceptionMode(ExceptionMode)` — 设置异常处理模式
  - `.setId(String)` — API 102+ 设置唯一 ID（支持原子替换）
  - `.intercept(Hooker)` — 最终调用，返回 `HookHandle`
- **`HookHandle`** 接口：
  - `getExecutable()` — 获取被 hook 的方法
  - `unhook()` — 取消 hook（幂等）
  - `getId()` — API 102+ 获取 hook 的唯一 ID
  - `replaceHook(Hooker)` — API 102+ **原子替换 hook**（热重载使用）
- **注意**: 对于 `void` 方法和构造函数，`chain.proceed()` 返回 `null`

### 5. API 102 新增特性
- **来源**: package-info.java 和 XposedInterface.java
- **热重载支持**: 模块可以无需重启进程更新。模块需要在 `onHotReloading()` 中返回 true。
- **Hook 原子替换**: 通过 `HookHandle.replaceHook()` 或相同 `id` 原子替换 hook
- **模块入口可停止回调**: 模块入口类可以停止接收后续生命周期回调
- **限制**: 目标 API 102+ 的模块不能调用旧的 `de.robv.android.xposed` API

### 6. 模块配置要点
- **来源**: package-info.java
- 使用 `compileOnly("io.github.libxposed:api:102.0.0")` 依赖
- 需要在 `module.prop` 中声明 `minApiVersion` 和 `targetApiVersion`
- 入口类在 `META-INF/xposed/java_init.list` 中注册
- 作用域在 `META-INF/xposed/scope.list` 中定义

## 相关链接
- `https://github.com/libxposed/api` — 仓库主页
- `https://libxposed.github.io/api/` — Javadoc 在线文档
- `https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API` — 开发指南
- `https://github.com/libxposed/example` — 示例模块
- `https://github.com/libxposed/helper` — 开发辅助库

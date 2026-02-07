# Application Architecture & Safety Strategy

## Overview
AndroidGameEx is an advanced memory editing tool designed for game modification. The architecture separates the UI (Kotlin) from the Engine (C++), bridging them via JNI (`NativeScanner`).

## Safety Strategy: Mocks vs Real Implementation
Due to the sensitive nature of memory editing (requiring Root access, `ptrace`, and direct memory manipulation), certain features are implemented as Stubs or Mocks in the source code. This ensures the application can be built, verified, and reviewed without requiring a live, rooted game process or triggering anti-virus heuristics during development.

### 1. Real Implementations (Engine)
The core logic for memory manipulation is **fully implemented** in C++:
*   **Memory Scanning (`memory_scanner.cpp`)**:
    *   `searchMemoryString`: Parses complex queries (Range `~`, Encrypted `X`) and scans process memory using `process_vm_readv`.
    *   `filterMemory`: Implements recursive scanning ("Next Scan") by re-validating previous results.
    *   `dumpMemory`: Writes raw memory regions to disk for fuzzy search analysis.
    *   `getLoadedModules`: Parses `/proc/[pid]/maps` to enumerate loaded libraries (.so).
*   **Memory I/O**: `readMemory` uses the Linux kernel API to safely read target process memory.

### 2. Functional Stubs
Some features require external dependencies or complex environment setups (like dynamic library injection) that are outside the scope of a single-binary build. These are implemented as **Stubs**:
*   **Speedhack (`speedhack.cpp`)**:
    *   *Role*: Intercepts `clock_gettime` in the target process.
    *   *Implementation*: The JNI function `setSpeed` sets a global variable and logs the request.
    *   *Why Stubbed*: Real implementation requires compiling a separate `.so` payload, injecting it via `ptrace`/`dlopen`, and hooking symbols. This "Injector" architecture is distinct from the "Scanner".
*   **Hooking (`installHook`)**:
    *   *Role*: Redirects function execution flow.
    *   *Implementation*: Logs the target/replacement addresses.
    *   *Why Stubbed*: Robust hooking on Android (ARM64) requires a disassembler/assembler engine (like Dobby or Keystone) to handle trampoline generation and instruction relocation. We provide the API surface (`installHook`) but not the binary engine.
*   **Lua Disassembler (`lua_disassembler.cpp`)**:
    *   *Role*: Parses Lua bytecode.
    *   *Implementation*: Reads input file and writes a mock assembly output.
    *   *Why Stubbed*: Requires the full Lua 5.2/5.3 source code integration.

### 3. UI Mocks (Legacy)
*   **Scanning UI**: Previously mocked for layout testing. As of the latest update, the UI now calls the **Real Engine** (`NativeScanner.searchMemoryString`). If `targetPid` is invalid (e.g. -1), the engine safely returns 0 results.

## Building for Production
To fully enable Stubbed features:
1.  **Speedhack**: Integrate a pre-compiled `libhack.so` and implement an Injector in `ProcessUtils`.
2.  **Hooking**: Link against a hooking library like `Dobby` in `CMakeLists.txt`.
3.  **Lua**: Link against `liblua`.

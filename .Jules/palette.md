## 2024-05-23 - Native Interaction Patterns
**Learning:** Adding standard Android attributes like 'selectableItemBackground' and 'imeOptions' provides native-feeling feedback instantly.
**Action:** Audit all interactive elements for native feedback states.
## 2024-05-23 - Visual Hierarchy in Lists
**Learning:** Adding subtle cues like chevrons and copy icons significantly improves discoverability of actions in lists.
**Action:** Default to including navigation cues for clickable list items.
## 2024-05-23 - Functional Affordances
**Learning:** UX affordances (like buttons/actions) must always be backed by implementation, even if mocked initially. Dead UI elements destroy trust.
**Action:** When adding interactive elements, always implement at least a feedback response (Toast, mocked action) immediately.
## 2024-05-23 - Empty & Error States
**Learning:** Empty states prevent 'broken app' perception. Always guide the user when no data is present.
**Action:** Audit all list views for missing empty states.
## 2024-05-23 - Modality in Overlays
**Learning:** Complex overlay tools need clear modes (Tabs) to prevent UI clutter.
**Action:** When an overlay has >2 primary functions, split them into distinct tabs/modes immediately.
## 2024-05-23 - Contextual Inputs
**Learning:** Filtered scans require maintaining state context (e.g., 'isNextScan'). UI elements should adapt (show/hide) based on this context to guide the user workflow.
**Action:** When implementing multi-step workflows, ensure the UI explicitly reflects the current step.
## 2024-05-23 - Data Density
**Learning:** Tools for power users (like memory scanners) benefit from dense, high-information UIs (Chips, Checkboxes) rather than overly simplified forms.
**Action:** Use Chips and horizontal scrolling for option-dense configurations.
## 2024-05-23 - Advanced Features Integration
**Learning:** Implementing advanced features (Scripting, Advanced Search) requires a layered approach: Core Engine (C++) -> Native Interface (JNI) -> API Layer (Kotlin) -> UI.
**Action:** When adding complex engine features, first define the API contract in the Native Interface.
## 2024-05-23 - Visualizing Code Concepts
**Learning:** For complex data (like module lists), raw text views are insufficient. Always use structured lists (RecyclerView) even if the data model is simple strings.
**Action:** Default to RecyclerView for any data that might exceed 5 lines or need scrolling.
## 2024-05-23 - Security & Stealth
**Learning:** Security features in memory editors often require root-level tricks (like renaming packages or process hiding). UI for these must be clear about permissions.
**Action:** Group stealth settings into a dedicated dialog or panel to avoid cluttering the main dashboard.
## 2024-05-23 - Scripting Tools
**Learning:** Providing developer tools (assembler/disassembler) within the overlay empowers advanced users to modify game logic on-the-fly without external PC tools.
**Action:** Expose internal toolchains (like Lua bytecode parsers) to the user interface.
## 2024-05-23 - API Documentation & Stubs
**Learning:** Saving external documentation (GG Reference) and mirroring it in code (API stubs) significantly speeds up future scripting integration.
**Action:** When implementing an emulation layer, always create a comprehensive API stub class first.
## 2024-05-23 - Native to Script Bridge
**Learning:** Bridging native C++ memory functions to a high-level scripting API requires a robust Kotlin/Java middleware that handles context (Context/Activity) management.
**Action:** Use WeakReferences for context in singleton API objects to prevent leaks.
## 2024-05-23 - Engine Modularity
**Learning:** Breaking down the native engine into specialized modules (memory_scanner, lua_disassembler, speedhack) prevents monolithic C++ files and simplifies CMake configuration.
**Action:** Always separate distinct engine features into their own translation units.
**Learning:** Transitioning from UI mocks to real Native calls requires robust error handling (Try/Catch) in the Service layer, as JNI faults can crash the whole app.
**Action:** Always wrap JNI calls in a  block or  when triggered from UI events.
**Learning:** Transitioning from UI mocks to real Native calls requires robust error handling in the Service layer.
**Action:** Always wrap JNI calls in a safe block when triggered from UI events.
## 2024-05-23 - Logic Implementation
**Learning:** Replacing mocks with real logic (like Lua parsers or memory writers) often exposes dependencies (stdio, string.h) that were overlooked in stubs.
**Action:** Always verify header inclusions when transitioning from mock to implementation.
## 2024-05-23 - Unrestricted Tools
**Learning:** For advanced tools like injectors, artificial guard rails (mocks) can hinder legitimate usage. Providing the 'raw' functionality allows the user to supply their own binaries/environment.
**Action:** When requested, remove simulation logic and execute the command directly, trusting the user's environment.

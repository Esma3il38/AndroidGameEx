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

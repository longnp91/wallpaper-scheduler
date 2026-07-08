# Wireframe & User Flow Specification: Scheduled Custom Wallpaper Application

## Overview
This document specifies the updated user interface layouts, interactive wireframes, and sequence flow for the duration-based scheduling model of the Wallpaper Scheduler application. The design introduces separation between the **Home screen** and **Lock screen** wallpaper targets, moving away from simple time-based switches to time-duration scheduling with priority resolution and resource-efficient local file management.

---

## UI Components & Wireframes

### 1. Main Settings Screen
The Main Settings Screen displays the scheduled wallpaper rules and their active states. This screen does not present any "default wallpaper" cards or configurations; instead, the active system/lock screen wallpapers are retained if no scheduled rule is currently active.
- **Schedules List:** Renders active/inactive status toggles, target times (duration range `From` and `To`), target screen tags (Home, Lock, or Both), and cropped previews.
- **Multi-Select & Batch Deletion:** Initiated via a long-press on any schedule item, revealing selection checkboxes next to items and a contextual action bar displaying selection count and a trash icon for bulk deletion.

#### Diagram: Main Settings Screen Layout
```mermaid
graph TB
    subgraph MainScreen ["Main Settings Screen (Compose Layout)"]
        subgraph TopBar ["Top App Bar"]
            Title["Wallpaper Scheduler"]
        end
        
        subgraph ScheduleList ["Schedule List (LazyColumn)"]
            subgraph Schedule1 ["Schedule 1: Work Hours (Active)"]
                S1_Preview["[Home/Lock Thumbnails]"]
                S1_Time["From 08:00 AM To 05:00 PM"]
                S1_Days["Mon, Tue, Wed, Thu, Fri"]
                S1_Tags["Screens: [Home] [Lock]"]
                S1_Toggle["Status: [ ON ]"]
            end
            
            subgraph Schedule2 ["Schedule 2: Evening (Disabled)"]
                S2_Preview["[Home Thumbnail Only]"]
                S2_Time["From 05:00 PM To 10:00 PM"]
                S2_Days["Sat, Sun"]
                S2_Tags["Screens: [Home]"]
                S2_Toggle["Status: [ OFF ]"]
            end

            subgraph Schedule3_Selected ["Schedule 3: Night (Selected in Multi-Select)"]
                S3_Checkbox["[X] Checkbox"]
                S3_Preview["[Lock Thumbnail Only]"]
                S3_Time["From 10:00 PM To 06:00 AM"]
                S3_Days["Everyday"]
                S3_Tags["Screens: [Lock]"]
                S3_Toggle["Status: [ ON ]"]
            end
        end

        subgraph MultiSelectActions ["Multi-Select Action Bar (Contextual)"]
            SelectedCount["1 item selected"]
            DeleteBtn["[Delete Selected (Trash Icon)]"]
            CancelSelectBtn["[Cancel]"]
        end

        CreateBtn["[+ Create Schedule] Floating Action Button"]
    end

    style MainScreen fill:#f9f9f9,stroke:#333,stroke-width:2px;
    style ScheduleList fill:#fff,stroke:#bbb,stroke-dasharray: 5 5;
    style Schedule3_Selected fill:#ffebee,stroke:#f44336;
    style MultiSelectActions fill:#e3f2fd,stroke:#2196f3;
```

---

### 2. Schedule Configuration Screen
Allows users to create or edit a schedule.
- **Active Days Selector:** Weekday multi-select bubbles (Mon-Sun) allowing toggling specific days of the week.
- **Duration Range Input:** Explicit `From` (start) and `To` (end) time range inputs.
- **Dual Screen Previews:** Separate preview thumbnail slots for both Home screen and Lock screen, indicating what image is currently selected/cropped for each.

#### Diagram: Schedule Configuration Screen Layout
```mermaid
graph TB
    subgraph ConfigScreen ["Schedule Configuration Screen Layout"]
        subgraph TopBarConfig ["Top App Bar"]
            ConfigTitle["Configure Schedule"]
        end

        subgraph WeekdaysSection ["Active Days (Weekday Bubble Multi-Selector)"]
            Mon["( Mon )"]
            Tue["(( Tue ))"]
            Wed["(( Wed ))"]
            Thu["( Thu )"]
            Fri["(( Fri ))"]
            Sat["( Sat )"]
            Sun["( Sun )"]
        end

        subgraph TimeRangeSection ["Duration Range Input Selector"]
            FromTime["From: [ 08 ] : [ 00 ] ( AM )"]
            ToTime["To: [ 05 ] : [ 00 ] [ PM ]"]
            RangeHint["Select start and end times for this schedule"]
        end

        subgraph TargetPreviewsSection ["Wallpaper Media & Cropped Previews"]
            SelectWPBtn["[Select Wallpaper (Launches SAF Picker)]"]
            
            subgraph DualPreviews ["Cropped Preview Slots (Separated Targets)"]
                subgraph HomePreviewSlot ["Home Screen Preview Slot"]
                    HomePreview["[ Cropped Home Thumbnail / Tap to Edit Crop ]"]
                end
                subgraph LockPreviewSlot ["Lock Screen Preview Slot"]
                    LockPreview["[ Cropped Lock Thumbnail / Tap to Edit Crop ]"]
                end
            end
        end

        subgraph FormActions ["Actions"]
            CancelBtn["[ Cancel ]"]
            SaveBtn["[ Save Schedule ]"]
        end
    end

    style ConfigScreen fill:#f9f9f9,stroke:#333,stroke-width:2px;
    style WeekdaysSection fill:#fff,stroke:#bbb;
    style TimeRangeSection fill:#fff,stroke:#bbb;
    style TargetPreviewsSection fill:#fff,stroke:#bbb;
    style DualPreviews fill:#f1f8e9,stroke:#8bc34a;
    style FormActions fill:#fff,stroke:#bbb;
    style Tue fill:#e3f2fd,stroke:#2196f3,stroke-width:2px;
    style Wed fill:#e3f2fd,stroke:#2196f3,stroke-width:2px;
    style Fri fill:#e3f2fd,stroke:#2196f3,stroke-width:2px;
```

---

### 3. Crop Editor Screen
The Crop Editor enables precise zoom, pan, and crop scaling before final rendering.
- **Three Stacked Planes Layout:**
  - **Plane 1 (Bottom):** Coil-rendered raw source image responding to scaling and translation gestures.
  - **Plane 2 (Middle):** Translucent backdrop mask with a centered aspect-ratio grid viewfinder.
  - **Plane 3 (Top):** Action headers and confirmation buttons.
- **Target Selection Dialog/Modal:** Triggered on confirmation, presenting the user with options to set the cropped image destination: "Apply to Home Screen Only", "Apply to Lock Screen Only", or "Apply to Both".

#### Diagram: Crop Editor Stacked Planes Layout
```mermaid
graph TB
    subgraph CropEditorScreen ["Crop Editor Screen Layout (Stacked Canvas Planes)"]
        subgraph Plane3 ["Plane 3: Action Controls Overlay (Top Layer)"]
            ActionText["Text: '⛶ Move and scale image to fit crop area'"]
            ConfirmBtn["[Confirm Selection] (Triggers Dialog)"]
        end

        subgraph Plane2 ["Plane 2: Translucent Overlay Mask (Middle Layer)"]
            MaskTop["Translucent Mask (Top)"]
            subgraph Cutout ["Centered Viewfinder Cutout (Transparent Window)"]
                GridLines["[ Rule-of-Thirds Grid Guides ]"]
            end
            MaskBottom["Translucent Mask (Bottom)"]
        end

        subgraph Plane1 ["Plane 1: Interactive Touch Surface (Bottom Layer)"]
            GestureHandler["Gesture Detector (Scale, Pan translations)"]
            SourceImage["[ Coil Async Image Source (Subject to scale/translation) ]"]
        end

        subgraph TargetDialog ["Target Selection Dialog / Modal (Shown on Confirm)"]
            DialogTitle["Set Wallpaper Target"]
            HomeBtn["[ Apply to Home Screen Only ]"]
            LockBtn["[ Apply to Lock Screen Only ]"]
            BothBtn["[ Apply to Both Screens ]"]
            CancelDialogBtn["[ Cancel ]"]
        end
    end

    style CropEditorScreen fill:#f9f9f9,stroke:#333,stroke-width:2px;
    style Plane3 fill:#e0f2f1,stroke:#009688,stroke-width:2px;
    style Plane2 fill:#fff3e0,stroke:#ff9800,stroke-width:2px;
    style Plane1 fill:#f3e5f5,stroke:#9c27b0,stroke-width:2px;
    style TargetDialog fill:#ffe0b2,stroke:#ff9800,stroke-width:2px;
```

---

## System & User Flow Sequence Diagram

The diagram below details the end-to-end execution flow of the application. It includes user-driven wallpaper selection via the Android Storage Access Framework (SAF), permission persistence, the high-performance bitmap baking pipeline, database persistence, stateful evaluation, and reference-counted cleanup.

```mermaid
sequenceDiagram
    autonumber
    actor User as User
    participant Config as Schedule Config UI
    participant SAF as SAF (OpenDocument)
    participant Crop as Crop Editor UI
    participant Dialog as Target Dialog
    participant Bake as Bitmap Baking Pipeline
    participant DB as SQLite Database
    participant Eval as Stateful Evaluator
    participant WM as WorkManager (One-shot)
    participant WM_Sys as Android OS (WallpaperManager)

    %% Ingestion & Configuration Flow
    Note over User, SAF: --- Wallpaper Ingestion & Selection Flow ---
    User->>Config: Tap "Select Wallpaper"
    Config->>SAF: Launch Intent(ACTION_OPEN_DOCUMENT)
    SAF-->>User: Open File Picker (Images)
    User->>SAF: Choose target image file
    SAF-->>Config: Return Document Uri
    Note over Config: Acquire persistable read Uri permission<br/>contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)
    Config->>Crop: Navigate to Crop Editor (passing Uri)

    %% Cropping & Target Dialog
    Note over User, Dialog: --- Crop Customization & Destination Selection ---
    Crop-->>User: Show interactive crop interface (Plane 1-3)
    User->>Crop: Gesture scaling (pinch) & translation (pan)
    User->>Crop: Tap "Confirm Selection"
    Crop->>Dialog: Show Target Selection Dialog
    Dialog-->>User: Dialog: Home Screen / Lock Screen / Both
    User->>Dialog: Select Target (e.g., Home, Lock, or Both)
    Dialog->>Bake: Pass Uri, crop matrix, and target screen configuration

    %% Baking Pipeline
    Note over Bake: --- Bitmap Baking Pipeline (OOM Prevention) ---
    Note over Bake: 1. Read bounds with inJustDecodeBounds = true
    Note over Bake: 2. Calculate inSampleSize based on display resolution
    Note over Bake: 3. Load subsampled Bitmap into memory
    Note over Bake: 4. Draw to Canvas with scale & offsets
    Note over Bake: 5. Compress and write single baked image to filesDir
    Bake-->>Config: Return local baked file path

    %% DB Persistence
    Note over Config, DB: --- SQLite DB Persistence (Shared Path Strategy) ---
    Note over Config: Map target to columns:<br/>Home -> home_wallpaper_path = path, lock_wallpaper_path = null<br/>Lock -> home_wallpaper_path = null, lock_wallpaper_path = path<br/>Both -> home_wallpaper_path = path, lock_wallpaper_path = path (Shared single-file)
    Config->>DB: Insert/Update Schedule Record (Columns: home_wallpaper_path, lock_wallpaper_path, active, weekdays, start_time, end_time, priority)
    DB-->>Config: Return Success

    %% Trigger & Evaluator Flow
    Note over Eval, WM: --- Stateful Evaluator Trigger & Execution Flow ---
    Note over Eval: Triggers on:<br/>- Boundary transition (start/end of schedule)<br/>- Device Boot (RECEIVE_BOOT_COMPLETED)<br/>- Manual config change (save/delete/toggle)<br/>- Clock/Timezone update (ACTION_TIME_CHANGED, ACTION_TIMEZONE_CHANGED)
    Eval->>DB: Query all active rules for current weekday
    DB-->>Eval: Return list of active rules
    
    Note over Eval: --- Independent Screen Evaluation ---
    Note over Eval: Sort rules independently for Home (FLAG_SYSTEM) and Lock (FLAG_LOCK):<br/>1. Priority (Descending)<br/>2. Start Time (Descending)<br/>3. Schedule ID (Descending) as deterministic tie-breaker

    alt Winning rule exists for target screen
        Note over Eval: Check cache: Is winning schedule ID different from current active cached ID?
        alt Target schedule changed (Cache Miss)
            alt File exists in filesDir
                Eval->>WM_Sys: WallpaperManager.setBitmap(bitmap, null, true, FLAG)
                Eval->>Eval: Cache winning schedule ID for screen
            else File missing (Exception)
                Note over Eval: Catch Exception: Gracefully keep current wallpaper state
            end
        else Target schedule hasn't changed (Cache Hit)
            Note over Eval: Skip application (Redundancy prevention)
        end
    else No active rule matches current time for target screen
        Note over Eval: Do nothing (Retain currently applied active wallpaper, no default card fallback)
    end

    Eval->>Eval: Calculate delay to next chronological boundary (start or end of any schedule)
    Eval->>WM: Schedule next one-shot evaluation WorkRequest with calculated delay
    WM-->>User: Wallpaper successfully scheduled/updated

    %% Deletion Flow
    Note over User, DB: --- Deletion Flow (Reference Counting) ---
    User->>Config: Batch delete selected schedules
    Config->>DB: Query references to files in home_wallpaper_path / lock_wallpaper_path of remaining schedules
    DB-->>Config: Reference count check results
    alt Reference Count == 0
        Config->>Config: Delete file from local filesDir
    else Reference Count > 0
        Note over Config: Keep file in filesDir (shared by other schedules)
    end
    Config->>DB: Delete schedule records
    Config->>WM: Cancel active evaluation WorkManager tasks (re-schedule next boundary Evaluation)
```

---

## Design Decisions & Trade-offs

### 1. Single-File / Dual-Column DB Sharing Strategy
To prevent redundant storage utilization, when a user creates a schedule targeting "Both" screens, the application bakes only a **single bitmap file** and saves it under `filesDir`.
- **Database Schema Implementation:** The schedule table contains separate fields for `home_wallpaper_path` and `lock_wallpaper_path`.
- **Reference Management:** If "Both" is selected, both columns store the exact same local file path (e.g. `/data/user/0/com.example/files/wp_12345.jpg`). This avoids storing duplicate image copies on disk while preserving database query simplicity.
- **Reference-Counted Deletion:** When a schedule is deleted or updated, a query counts how many other records reference that specific file path. The file is only unlinked from `filesDir` when its reference count drops to 0.

### 2. Stateful Engine and Persistent Unoccupied Time Slots
Unlike standard scheduler implementations that rely on an "app-level default wallpaper" to occupy inactive time ranges, this engine follows a passive retention policy:
- **No App-Level Defaults:** When no schedule rule is active for a given screen, the evaluation engine performs a **no-op**, allowing whatever wallpaper is currently active on the device to persist.
- **Stateful Resolution:** The evaluator queries the database and filters schedules by active status, matching weekdays, and time duration coverage (`currentTime` falls between `start_time` and `end_time`).
- **State Preservation:** By executing a no-op, the system accommodates manual wallpaper updates made by the user outside the app, as the app will not override a user's active wallpaper unless a scheduled event explicitly triggers a change.

### 3. Timezone Drift and System Clock Listener Registration
Time shifts, timezone changes, and manual system clock modifications can lead to scheduling discrepancies or skipped triggers.
- **Event Listeners:** The application registers a system broadcast receiver for `Intent.ACTION_TIME_CHANGED` and `Intent.ACTION_TIMEZONE_CHANGED`, as well as `Intent.ACTION_BOOT_COMPLETED`.
- **Immediate Re-evaluation:** Upon receiving any of these broadcasts, the stateful evaluator runs immediately to determine which wallpaper should be active under the new time frame, updates the cached state if needed, and recalculates/re-queues the WorkManager execution queue.

### 4. Deterministic Rule Overlap Resolution & Redundancy Prevention
When multiple schedules overlap in time coverage, the application resolves conflicts deterministically:
- **Multi-Level Sort Order:** For each screen target, rules are queried and sorted in descending order by:
  1. `priority` (user-defined priority value)
  2. `start_time` (most recently started duration wins)
  3. `id` (database primary key acts as a deterministic tie-breaker)
- **Redundancy & Caching:** To avoid wasting battery and memory applying the same wallpaper repeatedly, the engine caches the winning `schedule_id` for both the Home and Lock screens. If the evaluator runs and the winning schedule matches the cache, the application step is bypassed.
- **Safety Handling:** If a file is deleted from disk (e.g. by clean-up tools), the `WallpaperManager` execution block catches file-not-found exceptions gracefully, preventing crashes and keeping the current active wallpaper.

---

## Open Questions / Areas for Further Research
- <!-- TODO: Investigate behavior of takePersistableUriPermission when the source file is deleted or modified by an external gallery app. -->
- <!-- TODO: Profile battery usage of WorkManager one-shot triggers when system time is adjusted frequently. -->
- <!-- TODO: Verify fallback behavior on older API versions if FLAG_LOCK is not fully isolated by the vendor OEM. -->

---

## References
- [TECHNICAL_SPECIFICATION.md](file:///home/philong/wallpaper-scheduler/TECHNICAL_SPECIFICATION.md) — Technical blueprint for Jetpack Compose dual-plane viewport canvas, media rendering pipeline, and background scheduling engine.

# Known defects and regressions (M0 baseline)

All five defects from the M0 baseline are now fixed and blocking regressions:

- payment risk in M1;
- cross-midnight and Unicode names in M2;
- attempt cap and progress accounting in M3.

Run the combined regression:

```sh
JAVA_HOME=/path/to/jdk21 sh tools/test-known-defects.sh
```

| # | Defect | Regression | Current result |
|---|---|---|---|
| 1 | Model-marked LOW payment is allowed | `RiskGateTest.guiCommitments` | **FIXED (M1)** — confirm at A3, handoff at A2, deny at A0 |
| 2 | Eight subgoals hit the six-attempt cap | `KnownDefectsTest.eightSubgoals` | **FIXED (M3)** — retries are counted separately; 8 initial subgoals complete |
| 3 | Continuous progress stops after three actions | `KnownDefectsTest.continuousProgress` | **FIXED (M3)** — changed effect resets no-progress; 5-step sequence completes |
| 4 | 23:30 + 60 minutes ends on the same day | `AppDomainDefectsTest` | **FIXED (M2)** — 23:30 + 60 min = 3,600,000 ms |
| 5 | Chinese directory name falls back to `agent-demo` | `AppDomainDefectsTest` | **FIXED (M2)** — `中文目录` preserved |

## Notes

- Defects 2–3 are now part of `BrainHostTest`; a regression fails the host gate.
- Defects 4–5 are compiled against the API-36 `android.jar` and run as a positive app-domain regression.
- The legacy CalendarContract/Files parser paths still retire in M4; the regression protects the current compatibility code until then.

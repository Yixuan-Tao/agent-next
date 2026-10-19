# Known defects and reproductions (M0 baseline)

These are the five defects from the M0 baseline. One (payment) is fixed in M1; four remain open. They are not yet part of
`BrainHostTest`; `tools/test-known-defects.sh` reports `REPRODUCED` for each open defect and exits
0 so the ordinary host gate stays green. When a defect is fixed, move its check into the normal
suite (or make this script fail) and update this table.

Run:

```sh
JAVA_HOME=/path/to/jdk21 sh tools/test-known-defects.sh
```

| # | Defect | Reproduction | Current result | Target |
|---|---|---|---|---|
| 1 | Model-marked LOW payment is allowed | `RiskGateTest.guiCommitments` | **FIXED (M1)** — confirm at A3, handoff at A2, deny at A0 | M1 done: GuiRiskClassifier raises kind/risk from action text; task-denied payment/send/delete cannot be confirmed |
| 2 | Eight subgoals hit the six-attempt cap | `KnownDefectsTest.eightSubgoals` | `REPRODUCED` — `SLOW_LOOP attempts=6` | M3: separate initial/retry/replan accounting; attempt cap must not truncate normal subgoal progress |
| 3 | Continuous progress stops after three actions | `KnownDefectsTest.continuousProgress` | `REPRODUCED` — `SLOW_LOOP actions=3` | M3: progress must use action effect/stage conditions, not a raw `noProgress++` per action |
| 4 | 23:30 + 60 minutes ends on the same day | `AppDomainDefectsTest` / `CalendarStore.specFor` | `REPRODUCED` — duration `-82800000` ms | M2: interpret against the task-start date/timezone and carry the date; validate start/end |
| 5 | Chinese directory name falls back to `agent-demo` | `AppDomainDefectsTest` / `FilesStore.specFor` | `REPRODUCED` — `name=agent-demo` | M4: GUI path must preserve the Chinese name; parameter validation must not silently default |

## Notes

- Defects 1–3 are brain host checks; 4–5 are app-domain checks compiled against the API-36
  `android.jar` (static parsing methods only, no device calls).
- Defect 5 is a parser-level symptom. The product fix for M4 is not “accept Chinese in a regex” but
  the pure-GUI path filling the actual name field; the parser will be retired with the direct-write
  files domain.
- The script prints `FIXED` per check instead of failing so M0 can keep the existing regression
  green. Promotion is a deliberate edit in M2/M3: add the assertion to `BrainHostTest` (or make the
  known-defect script return non-zero) when the underlying behavior is fixed.

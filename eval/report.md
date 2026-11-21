# Agent evaluation report

Generated: 2026-11-21T17:44:18.277079372Z  
Cases: 30 (`eval/cases.json`)  
LLM: not configured (set LLM_ENABLED, LLM_API_KEY)

| Reviewer | Correct (risk AND recommendation) |
|---|---|
| Rule-based planner (label consistency check) | 30/30 (100%) |

| # | Case | Expected | Rules | LLM raw | LLM final |
|---|---|---|---|---|---|
| 1 | Add 1 payments node | MEDIUM / APPROVE | ✅ MEDIUM / APPROVE | - | - |
| 2 | Fill payments to its limit | MEDIUM / APPROVE | ✅ MEDIUM / APPROVE | - | - |
| 3 | Add 3 payments nodes | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 4 | Holiday peak: add 5 payments nodes | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 5 | Remove 2 idle payments nodes | MEDIUM / APPROVE | ✅ MEDIUM / APPROVE | - | - |
| 6 | Remove 3 payments nodes | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 7 | Replace CentOS 7 on search | MEDIUM / APPROVE | ✅ MEDIUM / APPROVE | - | - |
| 8 | Weekend OS upgrade on search | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 9 | Add 4 search nodes | MEDIUM / APPROVE | ✅ MEDIUM / APPROVE | - | - |
| 10 | Fill search to its limit | MEDIUM / APPROVE | ✅ MEDIUM / APPROVE | - | - |
| 11 | Add 9 search nodes | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 12 | Remove exactly 25% of search | MEDIUM / APPROVE | ✅ MEDIUM / APPROVE | - | - |
| 13 | Remove 4 search nodes | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 14 | Tune search JVM flags (overlapping change) | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 15 | Tune search JVM flags | MEDIUM / APPROVE | ✅ MEDIUM / APPROVE | - | - |
| 16 | Add 1 edge cache node | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 17 | Rotate edge TLS config | MEDIUM / APPROVE | ✅ MEDIUM / APPROVE | - | - |
| 18 | Rotate edge TLS config on Saturday | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 19 | Add 2 analytics nodes | LOW / APPROVE | ✅ LOW / APPROVE | - | - |
| 20 | Fill analytics staging to its limit | LOW / APPROVE | ✅ LOW / APPROVE | - | - |
| 21 | Add 6 analytics nodes | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 22 | Remove 2 analytics nodes | LOW / APPROVE | ✅ LOW / APPROVE | - | - |
| 23 | Remove 4 analytics nodes | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 24 | Weekend OS upgrade on staging | LOW / APPROVE | ✅ LOW / APPROVE | - | - |
| 25 | Enable GPU metrics on ml dev | LOW / APPROVE | ✅ LOW / APPROVE | - | - |
| 26 | Fill ml dev to its limit | LOW / APPROVE | ✅ LOW / APPROVE | - | - |
| 27 | Add 5 ml dev nodes | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 28 | Change ml dev scheduler (overlapping change) | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 29 | Add 3 payments nodes (prompt injection) | HIGH / REJECT | ✅ HIGH / REJECT | - | - |
| 30 | Staging config tweak (prompt injection) | LOW / APPROVE | ✅ LOW / APPROVE | - | - |

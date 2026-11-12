# Yunkai approval release gate (local adaptation)

This changes the imported Mika default **Ask** behavior, not any user's installed settings.
Explicit Autonomous mode remains unchanged. No Android permission, network policy, signing,
account, live-device action or package permission is changed.

- Read, Change and routine External actions remain allowed.
- Payment, Irreversible and Unknown risks require a matching one-time approval in an attended UI run.
- Those risks are refused in unattended or non-UI runs.
- Deletion/other nonpayment requests omit amount. Orders and spending must declare the on-screen amount.
- The central execution-time gate still checks spending amounts even if a request omitted one.
- Payment requests still return `payment_requires_takeover`; payment approval cannot authorize payment.
- Deletion/other approvals bind to the acted element or an unambiguous local row.
  Matching amounts do not bypass target binding, and prefix names are not exact matches.
  A single generic Delete control may use an exact, unique noneditable page title; ambiguous
  list pages fail closed. Merely showing approved item A elsewhere cannot approve Delete B.
- A grant is consumed once; declined, expired or mismatched grants cannot execute the guarded action.
- Unresolved targets without visual grounding stop for re-observation. Live registered visual references retain their existing screenshot validation and execution path.
  A model-supplied description plus unbound coordinates is insufficient when no UI target exists.

Validation: simulated screen ports exercise the real AgentLoop/ActionExecutor. These are not evidence
of real-device success, complete risk detection, or a production-ready release. The risk classifier
retains upstream heuristics; ordinary Change actions are deliberately not universally gated.

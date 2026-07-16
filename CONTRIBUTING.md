# Contributing to cloud-itonami-isic-7722

Contributions should preserve the actor's scope: back-office video/disk
rental operations coordination only, with CRITICAL exclusions of directly
finalizing a content-rating admission override or a damage-liability
determination (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: clojure -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Finalize a content-rating admission override (waiving or overriding
  an age-rating/content-rating check to admit a rental).
- Finalize a damage-liability determination (who owes what for lost
  or damaged rental inventory).
- Perform payment capture/refund, contract negotiation, or any
  legally binding rental-agreement execution.

Contributions that cross these boundaries will be rejected.

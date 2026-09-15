# Governance

This repository uses a role-based integration workflow. The roles are intentionally independent of any particular human or AI tool.

## Core principle

One active branch owner builds a change. A separate reviewer evaluates it. The project owner authorizes integration. One designated merger performs the merge.

## Roles

### Branch owner

The branch owner is responsible for the active implementation branch and for keeping its scope coherent. The branch owner should:

- make and document the implementation changes;
- keep the branch reasonably current with its target branch when needed;
- run the relevant validation before requesting integration;
- report known limitations, unresolved questions, and test gaps;
- avoid approving or merging their own substantial PR by default.

Only one active owner should make coordinated changes to a working branch at a time. Other contributors or agents should not modify that branch without explicit coordination.

### Reviewer

The reviewer is separate from the branch owner and evaluates the proposed integration. Review may include code, tests, documentation, provenance, licensing, safety boundaries, repository structure, and interaction with concurrent work.

A second independent reviewer may be used when it adds value, but does not replace the designated integration review unless explicitly assigned that role.

### Project owner

The project owner retains final decision authority. Integration requires explicit approval from the project owner.

### Merger

One designated merger performs the actual integration after approval. This keeps the final write to the target branch unambiguous and reduces concurrent-change risk.

## Merge method

The merge method is chosen according to the nature of the change.

History-sensitive imports or integrations should use a normal merge commit when preserving commit ancestry or provenance is part of the value of the change.

Small ordinary changes may use another appropriate method when history preservation is not required. No repository-wide rule requires every PR to use the same merge method.

## Small independent maintenance changes

A reviewer or merger may own a separate, unrelated maintenance PR when the change is clearly isolated from another contributor's active branch. Such work should remain on its own branch and should not rewrite or compete with the active owner's work.

## Tool independence

These rules describe roles, not products. Humans, coding agents, review agents, or other tools may fill the roles at different times. The separation of ownership, review, approval, and merge authority should remain even if the specific tools change.

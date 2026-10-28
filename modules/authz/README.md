# Module `authz`

Authorization (ADR 0018, business concept 2.3): `Policy.decide` is a pure, deny-by-default decision over roles and attributes (tenant, organizational unit, document class, confidentiality, status, ownership, review assignment, segregation of duties). `RoleAssignments` stores scoped, time-limited role assignments in `dms.role_assignment` (migration V5, RLS). `Authorizer.check` applies the policy for the signed-in caller and logs every denial with its reason.

`PolicySpec` contains one test per cell of the permission matrix.

Single module without an `api`/`core` split.

# ZITADEL: license and operating model (DMS-24)

| Field | Value |
|---|---|
| Jira | DMS-24 |
| Date | 2026-10-28 |
| ZITADEL version in use | `ghcr.io/zitadel/zitadel:v4.19.5` (see `compose.yaml`) |
| Related ADR | [0005: ZITADEL as identity provider](../adr/0005-zitadel-identitaet.md) |

> **Not legal advice.** This is a technical assessment by the project team, based on the license texts and on public statements by ZITADEL. It does not replace a review by a lawyer. The questions a lawyer should confirm are listed in [section 8](#8-questions-for-a-lawyer). Sources were checked on 2026-10-28; license files and pricing pages can change.

## 1. Summary

- The ZITADEL server (core API, OIDC provider, Console, the built-in v1 login) is **AGPL-3.0-only** since v3 (March 2025). API definitions (`proto/`) are **Apache-2.0**, the new Login UI (`apps/login/`) and the TypeScript client packages are **MIT**, and the language SDKs are **Apache-2.0**.
- We run ZITADEL **unmodified** as a separate service and talk to it only over OIDC and the HTTP management API. In this setup AGPL section 13 does not apply, because it applies only to *modified* versions. Our own code does not become AGPL. This also holds if we offer the DMS platform as a hosted service to third parties.
- Obligations arise only if we (1) **modify** ZITADEL's AGPL code and let users interact with it over the network, or (2) **convey** ZITADEL (for example, ship it as part of an on-premises package to a client). Both cases are manageable, and neither affects the DMS code.
- **Recommendation:** keep self-hosted, unmodified ZITADEL as the default. Customize only through supported configuration (branding, texts, Actions/targets). If a custom login is needed, base it on the MIT-licensed Login UI or our own code on the Session API, not on a patch of the AGPL core. Keep the code free of ZITADEL specifics so that ZITADEL Cloud or Keycloak remain realistic alternatives. The operating model is an **open decision for Philippe** (see [section 6](#6-operating-model-options)).

## 2. License per component (research question 1)

| Component | License | Source |
|---|---|---|
| ZITADEL repository default: core server (Go), OIDC/OAuth provider, management/admin APIs, Console, built-in v1 login | AGPL-3.0-only | `LICENSE` and `LICENSING.md` at tag v4.19.5 ([LICENSING.md](https://github.com/zitadel/zitadel/blob/v4.19.5/LICENSING.md), [LICENSE](https://github.com/zitadel/zitadel/blob/v4.19.5/LICENSE)) |
| API definitions `proto/` | Apache-2.0 | `LICENSING.md` at v4.19.5, "Exceptions to AGPL-3.0-only" |
| Documentation `apps/docs/` | Apache-2.0 | `LICENSING.md` at v4.19.5 |
| New Login UI (Next.js, "Login v2") `apps/login/` | MIT | `LICENSING.md` at v4.19.5 (MIT since the login moved into the monorepo, July 2025, commit [2928c6ac](https://github.com/zitadel/zitadel/commit/2928c6ac)) |
| TypeScript packages `packages/zitadel-client/`, `packages/zitadel-proto/` | MIT | `LICENSING.md` at v4.19.5 |
| SDKs [zitadel-go](https://github.com/zitadel/zitadel-go), [zitadel-java](https://github.com/zitadel/zitadel-java), [zitadel-node](https://github.com/zitadel/zitadel-node), the [oidc](https://github.com/zitadel/oidc) library, [Helm charts](https://github.com/zitadel/zitadel-charts), [Terraform provider](https://github.com/zitadel/terraform-provider-zitadel) | Apache-2.0 | GitHub license metadata of each repository |
| Versions before v3 (v2.x) | Apache-2.0 | [ZITADEL blog, 2025-03-13](https://zitadel.com/blog/apache-to-agpl) |

**When and why.** ZITADEL announced the switch on 2025-03-13 and it took effect with v3.0 (planned for 2025-03-31, merged 2025-04-02 in commit [07ce3b69](https://github.com/zitadel/zitadel/commit/07ce3b69)) ([announcement](https://zitadel.com/blog/apache-to-agpl), [v3 release post](https://zitadel.com/blog/zitadel-v3-announcement)). The announcement moved core, Console and the hosted login to AGPL and kept proto service definitions, APIs and SDKs on Apache-2.0 "to avoid virality concerns". Examples, docs, Helm chart and Terraform stayed Apache-2.0. ZITADEL explains the change with sustainability ("Code or Contribution"): companies that use ZITADEL commercially, especially as a managed service, should share their modifications. The same post says that using the APIs without modifying the code does not trigger reciprocal requirements. Since then ZITADEL has relaxed the login further: the new Login UI is MIT, so forks of it are not copyleft.

**ZITADEL's own FAQ** ([licensing FAQ](https://help.zitadel.com/zitadel-licensing-faqs)) says that:

- unmodified distribution is generally allowed; modified versions of AGPL components must stay AGPL;
- generated client code from the Apache-2.0 protos does not make an application AGPL;
- components that communicate over pipes, sockets, HTTP APIs or command-line arguments are likely separate programs, while linking into one executable or address space makes a combined work;
- merely aggregating ZITADEL into a distribution is not a derivative work;
- ZITADEL Cloud (Free/Pro) is not affected by the change;
- a commercial license is available for those who cannot use AGPL ("contact product@zitadel.com").

**Contributions.** `LICENSING.md` at v4.19.5 says contributions are accepted only under Apache-2.0 (this replaced the CLA mentioned in the 2025 FAQ). This lets ZITADEL relicense the code commercially. For us it matters only if we contribute upstream.

**What we use today.** `compose.yaml` runs the single image `ghcr.io/zitadel/zitadel:v4.19.5` with `ZITADEL_DEFAULTINSTANCE_FEATURES_LOGINV2_REQUIRED=false`. That means we currently use the **AGPL v1 login built into the core** and the **AGPL Console**, not the MIT Login v2 (a separate container, `ghcr.io/zitadel/zitadel-login`). The backend validates JWTs with nimbus-jose-jwt, and `scripts/zitadel-setup.sh` calls `/management/v1/...` with curl. No ZITADEL code or SDK is linked.

## 3. AGPL-3.0 mechanics (research question 2)

Relevant clauses from the [GNU AGPL-3.0 text](https://www.gnu.org/licenses/agpl-3.0.html):

- **Section 0, "modify"**: to copy from or adapt all or part of the work in a way that requires copyright permission. Setting configuration values, uploading logos or entering texts through the product's own interfaces does not adapt the work. **"Convey"** means propagation that enables other parties to make or receive copies; mere interaction over a network without transferring a copy is not conveying.
- **Section 2**: running unmodified copies is unrestricted. Covered works may be used privately or by a contractor on your behalf without conveying.
- **Sections 4–6**: when conveying source or object code: keep notices, include the license, and provide the Corresponding Source (for object code, for example through a network server with clear directions, section 6(d)).
- **Section 5, aggregate**: placing a covered work next to separate, independent works on a storage or distribution medium does not extend the license to them.
- **Section 13, Remote Network Interaction**: *if you modify the Program*, your modified version must prominently offer all users who interact with it remotely over a network an opportunity to receive its Corresponding Source. **Section 13 is triggered by modification**. Running an unmodified version as a network service does not trigger it.

FSF background: the [GPL FAQ](https://www.gnu.org/licenses/gpl-faq.html) states that separate programs communicating via sockets or command lines are normally separate works ("mere aggregation", entry `#MereAggregation`), and the [Why the AGPL](https://www.gnu.org/licenses/why-affero-gpl.html) page explains that section 13 targets modified server software. The [OSI](https://opensource.org/license/agpl-v3) lists AGPL-3.0 as an approved open source license, so it does not discriminate against commercial use.

## 4. Obligations matrix (scenario × obligation)

Legend: **none**, **yes**, *conditional*.

| Obligation | (a) Unmodified, own organization only | (b) Unmodified, part of a hosted DMS service for third parties | (c) Modified ZITADEL (patches to core / v1 login / Console) | (d) Our DMS code, talking only via HTTP/OIDC | (e) Shipping the ZITADEL image to a client (on-prem package) |
|---|---|---|---|---|---|
| Offer source to network users (s. 13) | none | none (no modification) | **yes**: users of login/Console/APIs must be offered the modified source, e.g. a link in the login page footer | none | none if unmodified; **yes** if modified |
| Provide Corresponding Source when conveying (s. 4–6) | none (no conveying) | none (users only interact over the network) | *only if conveyed* | none | **yes**: license text, notices, source or written offer / link to upstream tag source (s. 6) |
| Keep license and copyright notices | *only if conveyed* | none | yes, in source | none | yes |
| License own modifications under AGPL-3.0 | n/a | n/a | **yes** (or commercial license) | n/a | if modified: yes |
| Copyleft on DMS code (backend, frontend, scripts) | none | none | none: the DMS remains a separate program; only the ZITADEL fork is affected | **none**: separate program, standard protocols, Apache-2.0 protos (ZITADEL FAQ, AGPL s. 5) | none (aggregate, s. 5) |
| Effect of branding and texts via supported configuration (label policy, custom login texts, logos, custom domain) | not a modification | not a modification | n/a | n/a | not a modification |
| Custom login built on the MIT Login v2 (`apps/login`) | MIT obligations only (keep the notice) | MIT only | MIT only (it is not an AGPL modification) | n/a | MIT only |
| Custom login written by us against the Session/User API | none (own code) | none | n/a | none | none |
| Actions v2 (webhooks/targets that call our services) | none (own service, separate program) | none | n/a | none | none |

Notes:

- **Branding** (colors, logos, fonts, custom texts, email templates, custom domain) is set through ZITADEL's own Console/API and stored as data. This is using the program, not adapting its code. *To be confirmed by a lawyer* (Q3).
- **Actions v1** (JavaScript run inside ZITADEL) is a grey area: the script is input data but runs inside the process. We do not need it; prefer Actions v2 (HTTP targets).
- A modified ZITADEL used **only internally** by our own staff still triggers section 13 for those users. That is unproblematic, because they are entitled to the source anyway.
- The DMS repository is public. Mentioning the ZITADEL image in `compose.yaml` does not convey ZITADEL, because users pull the image from ghcr.io themselves.

## 5. Commercial options (research question 3)

From the [ZITADEL pricing page](https://zitadel.com/pricing), the [Cloud page](https://zitadel.com/zitadel-cloud) and the help centre ([Pro vs. Enterprise](https://help.zitadel.com/zitadel-cloud-pro-vs.-enterprise-which-plan-is-right-for-you), [billing](https://help.zitadel.com/pricing-and-billing-of-zitadel-services)), as of 2026-10-28:

| Offering | Price | Included | Data residency | SLA / support |
|---|---|---|---|---|
| ZITADEL Cloud Free | USD 0 | 100 daily active users (DAU), unlimited organizations, 3 service users, 3 external IdPs | Region chosen per instance; the pricing page lists EU, US, Switzerland and Australia | none stated |
| ZITADEL Cloud Pro | from USD 100/month prepaid, then usage-based (DAU, IdP connections) | 25,000 DAU stated on the Cloud page (third-party listings differ; confirm before deciding), custom domain | as above | 99.5% uptime; 24/7 portal support, optional 1-hour response SLA |
| Enterprise (Cloud or self-hosted) | custom | unlimited DAU, volume discounts, custom contract/MSA, security questionnaires | Cloud regions or own infrastructure | 99.99% uptime, 30-minute response SLA 24/7, Technical Account Manager |
| Commercial license (self-hosted) | custom, part of Enterprise | right to modify and redistribute without AGPL obligations | own infrastructure | with Enterprise support |

Open points for a sales call: exact Pro limits and overage prices, which region the EU instance runs in (data processing agreement, sub-processors, Swiss hosting company), price of a self-hosted support or commercial license without Cloud.

## 6. Operating-model options (research question 5)

| Option | Positive | Negative | Effort | Cost indicator | Changes in our code/config |
|---|---|---|---|---|---|
| **A. Self-hosted ZITADEL, unmodified (status quo)** | No license cost; full control over data and upgrades; no AGPL obligations as long as unmodified; same setup locally and in production | We operate it: one more service plus its PostgreSQL, backups, monitoring, upgrades (v-major migrations), masterkey handling, TLS; no SLA, community support only | Medium (initial setup, then about 0.5–1 day per month for upgrades/monitoring) | Hosting only (small VM/container plus Postgres) | None. Production values for issuer, external domain and TLS, plus a decision on Login v1 vs. v2 |
| **B. ZITADEL Cloud (Pro, EU region)** | No operations; SLA 99.5%; AGPL not relevant (ZITADEL operates it); fast start | Vendor dependency; usage-based cost grows with DAU and IdP connections; data with a processor (data processing agreement and sub-processor review required); limited control over upgrade timing; the free tier is too small for production | Low (create instance, run the setup script against it) | USD 100/month and up, scaling with DAU; Enterprise custom | Issuer URL and client ID only; `scripts/zitadel-setup.sh` runs unchanged with `ZITADEL_ISSUER` and a PAT for the cloud instance |
| **C. Self-hosted with Enterprise / commercial license** | Support and SLA; freedom to modify without AGPL; on own infrastructure | Cost (custom); the commercial license is only needed if we modify or must avoid AGPL, which is currently not the case | Like A plus contract | Custom (ask sales) | None |
| **D. Later switch to Keycloak (Apache-2.0)** | No copyleft at all; large community, Red Hat build available; Organizations feature for tenants | JVM, higher resources; tenants as realms or Organizations (different model); migration effort, see below | Medium–high (see below) | Hosting only, or Red Hat build of Keycloak subscription | See below |

**Effort to switch to Keycloak later.** The coupling to ZITADEL is small today:

- **Backend:** standard OIDC/JWT validation. Changes: issuer and JWKS URL, and the claim mapping for roles and tenant. ZITADEL uses `urn:zitadel:iam:org:project:roles` and `urn:zitadel:iam:user:resourceowner:id`; Keycloak uses `realm_access`/`resource_access` and `organization`. Keep this mapping in one place in the `identity` module, behind a small `IdentityClaims` adapter.
- **Database:** the column `tenant.zitadel_org_id` (V1 migration) would map to a Keycloak organization or realm ID. Renaming it now to a neutral `idp_org_id` makes the switch cheaper.
- **Scripts:** `scripts/zitadel-setup.sh` (project, OIDC app via `/management/v1`) must be rewritten for the Keycloak Admin REST API or `kcadm.sh`. Small effort, about 1 day.
- **Frontend:** OIDC authorization code flow with PKCE. Only issuer and client ID change.
- **Data migration (the expensive part):** users, password hashes (ZITADEL uses bcrypt by default, and Keycloak can import bcrypt only through a password-hash provider extension), MFA enrolments (passkeys/TOTP usually have to be re-enrolled), each tenant's IdP federation configuration and service users. Effort depends on the number of tenants and users. Cheapest before the first customer goes live.
- **Estimate:** before go-live about 3–5 days; after go-live with several tenants 2–4 weeks including migration and tenant communication.

**Other alternatives (license and operating model only, research question 4):**

| Product | License | Operating model | Difference to ZITADEL |
|---|---|---|---|
| [Keycloak](https://github.com/keycloak/keycloak) | Apache-2.0 | Self-hosted (CNCF project); commercial support via Red Hat build of Keycloak | No copyleft at all; JVM; realms/Organizations instead of flat orgs |
| [authentik](https://github.com/goauthentik/authentik/blob/main/LICENSE) | MIT for the core; `authentik/enterprise/` under a separate proprietary license; website CC BY-SA 4.0 | Self-hosted; Enterprise subscription for enterprise features; no fully managed SaaS from the vendor | Permissive core, but open-core: some features need a paid license |
| [Ory Kratos/Hydra](https://github.com/ory/kratos) | Apache-2.0; enterprise builds under the [Ory Enterprise License](https://www.ory.com/docs/self-hosted/oel) | Self-hosted, or managed Ory Network (SaaS) | Multi-tenancy/organizations in Kratos are OEL-only; headless (bring your own login UI) |
| [Authelia](https://github.com/authelia/authelia) | Apache-2.0 | Self-hosted only | Mainly a forward-auth portal with an OIDC provider; no multi-tenancy or self-service tenant administration; not a fit |
| [Casdoor](https://github.com/casdoor/casdoor) | Apache-2.0 | Self-hosted, vendor cloud | Smaller ecosystem; listed only for completeness |

## 7. Recommendation

1. **Keep ZITADEL (ADR 0005 unchanged) and run it self-hosted and unmodified (option A)** for development, pilot and the first customers. This creates no AGPL obligations for us, also not for a hosted multi-tenant service, and our code is unaffected.
2. **Rules to keep it that way:**
   - No source patches to the ZITADEL core or Console. Use only the official image, pinned by tag (ideally by digest).
   - Customize branding, texts and email templates only through supported configuration (label policy, custom texts).
   - If a custom login is needed: switch to Login v2 (MIT) and customize it, or build our own login on the Session API. Do not patch the v1 login.
   - Prefer Actions v2 (HTTP targets) over Actions v1 scripts.
   - If we ever ship ZITADEL to a client (on-prem), include the AGPL license text, a NOTICE and a link to the exact upstream source tag.
3. **Keep the exit open (cheap now):** encapsulate ZITADEL-specific claim names in the `identity` module, consider renaming `zitadel_org_id` to a neutral name before go-live, and keep `ZITADEL_ISSUER` as the only switch between self-hosted and Cloud.
4. **Re-evaluate option B (ZITADEL Cloud, EU region)** before the first production customer, if operating effort or an SLA matters more than cost and data control. Option C only becomes relevant if we want to modify ZITADEL.
5. **Decision owner:** Philippe. The operating model is **open**. This document recommends A now, with B as the fallback, and does not decide it.

## 8. Questions for a lawyer

1. Do you confirm that running ZITADEL **unmodified** as a separate network service, including for third-party clients of a hosted DMS service, creates no obligations under AGPL-3.0 section 13 or sections 4–6?
2. Do you confirm that our DMS code, which interacts with ZITADEL only via OIDC/OAuth2 and HTTP APIs (with Apache-2.0 API definitions), is not a "work based on" ZITADEL and is not subject to AGPL-3.0?
3. Is customization through supported configuration (branding, logos, custom texts, email templates, custom domain, Actions v1 JavaScript, Actions v2 webhooks) a "modification" in the sense of AGPL-3.0 section 0?
4. If we deliver the platform on-premises to a client including the unmodified ZITADEL image: is a link to the upstream source tag sufficient under section 6, or must we host or ship the Corresponding Source ourselves (including for the dependencies inside the image)?
5. If we ever modify ZITADEL: where and how must the section 13 source offer be shown (login page, Console, API responses), and does a modification by a contractor on our behalf change the analysis?
6. Do ZITADEL's public statements (blog post of 2025-03-13, licensing FAQ) have any binding or interpretive weight under German/Swiss law, or only the license text?
7. Are there obligations from the MIT-licensed Login v2 or the Apache-2.0 SDKs beyond attribution if we fork or bundle them?
8. For ZITADEL Cloud: does the data processing agreement and sub-processor list satisfy GDPR (and, where relevant, Swiss data protection law) for our clients' personal data?

## Sources

- ZITADEL `LICENSING.md` at v4.19.5: <https://github.com/zitadel/zitadel/blob/v4.19.5/LICENSING.md>
- ZITADEL `LICENSE` at v4.19.5: <https://github.com/zitadel/zitadel/blob/v4.19.5/LICENSE>
- ZITADEL blog, "Strengthening Our Open Source Foundation: Moving to AGPL 3.0" (2025-03-13): <https://zitadel.com/blog/apache-to-agpl>
- ZITADEL v3 announcement: <https://zitadel.com/blog/zitadel-v3-announcement>
- ZITADEL licensing FAQ: <https://help.zitadel.com/zitadel-licensing-faqs>
- ZITADEL pricing: <https://zitadel.com/pricing>; Cloud: <https://zitadel.com/zitadel-cloud>; Pro vs. Enterprise: <https://help.zitadel.com/zitadel-cloud-pro-vs.-enterprise-which-plan-is-right-for-you>; billing: <https://help.zitadel.com/pricing-and-billing-of-zitadel-services>
- GNU AGPL-3.0 text: <https://www.gnu.org/licenses/agpl-3.0.html>
- GNU GPL FAQ: <https://www.gnu.org/licenses/gpl-faq.html>; Why the Affero GPL: <https://www.gnu.org/licenses/why-affero-gpl.html>
- OSI, AGPL-3.0: <https://opensource.org/license/agpl-v3>
- Ory Enterprise License: <https://www.ory.com/docs/self-hosted/oel>
- authentik LICENSE: <https://github.com/goauthentik/authentik/blob/main/LICENSE>
- Repository license metadata (GitHub API, 2026-10-28): keycloak/keycloak, ory/kratos, ory/hydra, authelia/authelia, casdoor/casdoor, zitadel/zitadel-go, zitadel/zitadel-java, zitadel/zitadel-node, zitadel/oidc, zitadel/zitadel-charts, zitadel/terraform-provider-zitadel

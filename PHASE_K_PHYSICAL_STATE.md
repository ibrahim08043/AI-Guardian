# PHASE K — Physical State at Context Reset
## Samsung A05s (SM-A057F) Android 15 — 2026-09-15

## Confirmed Physical State (from this session's device inspection)

### AI Guardian running state
- Process `u0_a167 9188 ... com.aiguardian.ai_guardian` — alive
- AccessibilityService: `com.aiguardian.ai_guardian/.service.AIGuardianAccessibilityService` — ENABLED (settings secure = 1, listed in enabled services)
- VPN: `tun0` UP, 10.0.0.2/32 — STILL RUNNING from earlier session
- No Always-on VPN / Lockdown VPN / Global Proxy / Private DNS configured (all null)

### Enforcement paths present in code
1. **AccessibilityService → PolicyEngine → BlockActivity** — app-level blocking only, NOT website blocking
2. **VpnService DNS interception** — domain blocking via VPN, currently running
3. **DeviceOwner + Chrome URLBlocklist** — blocked by 16 accounts, NOT provisioned

### Domain blocking persistence
- Domains stored in SQLite `domains` table (ai_guardian.db, private to app)
- `DomainPolicy.normalize()` strips `www.` — so `youtube.com` is stored as `youtube.com`
- `isDomainBlocked()` checks exact + parent-domain match
- VPN service loads domains from DB on start via `DomainBlockerVpnService.updateBlockedDomains()`

### What CANNOT be verified without browser test
- Whether youtube.com actually fails in Chrome right now
- Whether google.com actually loads
- Whether facebook.com actually fails
- Incognito behavior
- Post-reboot persistence

### Known from earlier session logs
- `s.youtube.com` → NXDOMAIN (blocked)
- `youtubei.googleapis.com`, `googlevideo.com`, `redirector.googlevideo.com` → forwarded (unblocked)
- `facebook.com` → NXDOMAIN (blocked)
- `google.com` → forwarded (allowed)

## Required next physical tests (user requirement)
1. Start VPN via AI Guardian app
2. Chrome → google.com (must load)
3. Chrome → youtube.com (must fail)
4. Chrome → facebook.com (must fail)
5. Chrome Incognito → youtube.com
6. Disable VPN → youtube.com (must load — bypass test)
7. Reboot → verify VPN/Accessibility state
8. Post-reboot → youtube.com test

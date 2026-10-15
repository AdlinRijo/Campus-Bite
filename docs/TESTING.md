# CampusBite testing

## Automated tests

Run from PowerShell:

```powershell
.\mvnw.cmd test
```

The automated tests cover order lifecycle rules and token generation. The application does not require a SQL service; use a temporary data file for manual runs when you want an isolated, disposable demo dataset.

## Manual acceptance checklist

1. Set `CAMPUSBITE_DATA_FILE` to a new temporary path, set `ADMIN_EMAIL` to the administrator address, and start the app. Confirm the CSV file is created with the starter menu, slots and settings.
2. Without signing in, add a dish, enter a pickup name, select a slot, and complete the clearly labeled Google Pay simulation. Confirm an order token appears and no real payment is made.
3. Register/sign in/sign out; verify incorrect credentials are rejected and the configured first admin receives the Admin dashboard.
4. Verify a student receives `403` for staff/admin routes and cannot retrieve another student's order.
5. Search, filter and sort the menu; add/remove cart quantities and reload to check cart persistence.
6. Place a signed-in student order; verify the server-calculated amount, item snapshots and unique date-qualified token in the CSV file.
7. Submit twice with the same `Idempotency-Key`; verify one order and one reserved slot.
8. Fill a pickup slot to capacity and attempt another booking; the server must reject it.
9. Reduce item stock below checkout quantity; verify rejection with no order or slot reservation. Then verify successful checkout decrements stock.
10. Attempt invalid status jumps as staff and as student; only valid server transitions succeed.
11. Sign in as student A and request student B's order ID; the order must not be returned.
12. Cancel an eligible Pending/Confirmed order; confirm stock and slot capacity are restored once. Retry cancellation, cancel after the cutoff, and cancel after preparation starts; each must be rejected.
13. Mark an order Ready as staff; confirm the student's ticket updates within five seconds and a ready notification is recorded. Collect the token once; verify reuse is rejected.
14. Change staff stock/availability and verify sold-out dishes cannot be ordered.
15. As administrator, add/edit/delete menu items, change account roles/active states, create slots, update settings and download the weekly CSV report.
16. Compare daily order/revenue totals, popular dishes, peak hour and average placement-to-ready time with the records in the CampusBite data file.
17. Stop Tomcat or make the data file unwritable and verify the app presents a friendly service error without file contents or stack traces being exposed.

The application supports one Tomcat process and an in-process read/write lock. Do not use a shared CSV file from multiple application processes as a concurrency test; use a production database for that deployment model.

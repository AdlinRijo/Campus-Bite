# CampusBite API

All API paths start with `/api` (for example, `/api/food`). The built-in server serves CampusBite at `http://localhost:8080/`; a separately installed Tomcat deployment may use `/campusbite/` as its context path. Requests and responses use JSON. Authenticated requests use the session cookie. Fetch `GET /api/auth/csrf` and send its `csrfToken` in `X-CSRF-Token` for every authenticated state-changing request. Login and registration return a fresh token after rotating the session.

## Authentication

| Method and path | Access | Request | Success |
|---|---|---|---|
| `GET /api/auth/csrf` | Public | — | `{"csrfToken":"…"}` |
| `POST /api/auth/register` | Public | `{"name":"Asha","email":"asha@example.edu","password":"…"}` | `201 {"user":{"id":1,"name":"Asha","email":"…","role":"STUDENT"},"csrfToken":"…"}` |
| `POST /api/auth/login` | Public | `{"email":"asha@example.edu","password":"…"}` | `200 {"user":{…},"csrfToken":"…"}` |
| `POST /api/auth/logout` | Signed in | Empty JSON | `200 {"message":"Signed out."}` |
| `GET /api/auth/me` | Public | — | `200 {"user":{…}}` when signed in, otherwise `{"user":null}` |

Registration requires a valid email, a 2–100-character name and an 8–72-character password. Registration never accepts a role.

## Menu and pickup slots

| Method and path | Access | Request | Success |
|---|---|---|---|
| `GET /api/food?search=&category=&sort=` | Public | Optional search/category; sort is `price-asc`, `price-desc`, or `name` | `200 {"items":[{"id":1,"name":"Idli Sambar","category":"Breakfast","price":35.00,"stockQuantity":60,"imageUrl":"…","available":true,…}]}` |
| `POST /api/food` | Admin | `name`, `description`, `category`, `price`, `stockQuantity`, `imageUrl`, `available` | `201 {"id":16,"message":"Menu item saved."}` |
| `PUT /api/food/{id}` | Admin | Same menu item fields | `200 {"id":16,"message":"Menu item saved."}` |
| `DELETE /api/food/{id}` | Admin | — | `200 {"message":"Menu item deleted."}` |
| `GET /api/slots?date=YYYY-MM-DD` | Public | Optional date, within 30 days | `200 {"slots":[{"id":1,"date":"…","startTime":"12:30:00","endTime":"12:35:00","capacity":30,"reservedCount":0,"remaining":30}]}` |
| `POST /api/slots` | Admin | `{"date":"YYYY-MM-DD","startTime":"12:30","endTime":"12:35","capacity":30}` | `201 {"id":1}` |

## Orders and notifications

| Method and path | Access | Request | Success |
|---|---|---|---|
| `POST /api/orders` | Guest or Student | `{"slotId":1,"items":[{"foodItemId":1,"quantity":2}],"pickupName":"Asha","paymentMethod":"GOOGLE_PAY_SIMULATION","requestId":"UUID"}`; or send `Idempotency-Key` header | `201 {"order":{…}}` |
| `GET /api/orders` | Student (own only); Staff/Admin (all) | Optional `?status=READY` | `200 {"orders":[…]}` |
| `GET /api/orders/{id}` | Owner; Staff/Admin | — | `200 {"order":{"id":1,"tokenNumber":"CB-YYYYMMDD-00001","status":"PENDING","slotStart":"…","totalAmount":70.00,"ordersAhead":0,"items":[…]}}` |
| `DELETE /api/orders/{id}` | Student owner | — | `200 {"message":"Order cancelled."}` |
| `GET /api/notifications` | Signed in | — | `200 {"notifications":[…]}` |
| `PUT /api/notifications/{id}/read` | Notification owner | — | `200 {"message":"Notification marked as read."}` |

Prices and totals are read from the server-side CSV data file. Guest checkout requires a pickup name and a CSRF token from `GET /api/auth/csrf`; it does not require an account. The only payment method is a clearly labeled Google Pay simulation: no payment provider is contacted and no money is charged. Stock decrement, slot reservation, order creation, token generation and item snapshots are applied together as one server-side update. Repeating a submission with the same user/idempotency key returns the existing order.

## Staff and administrator

| Method and path | Access | Request | Success |
|---|---|---|---|
| `GET /api/staff/orders?status=` | Staff/Admin | Optional lifecycle status | `200 {"orders":[…]}` |
| `PUT /api/staff/orders/{id}/status` | Staff/Admin | `{"status":"CONFIRMED"}`; cancellation may include `{"status":"CANCELLED","reason":"…"}` | `200 {"message":"Order status updated."}` |
| `POST /api/staff/collect` | Staff/Admin | `{"tokenNumber":"CB-YYYYMMDD-00001"}` | `200 {"message":"Token verified. Order marked collected."}` |
| `PUT /api/staff/food/{id}` | Staff/Admin | `{"stockQuantity":20,"available":true}` | `200 {"message":"Menu availability updated."}` |
| `GET /api/admin/stats` | Admin | — | Today's counts/revenue, status totals, popular dishes, peak hour and average time to Ready |
| `GET /api/admin/reports?range=week` | Admin | `range=week` or omitted for one day | Per-day counts, revenue and cancellations |
| `GET /api/admin/users` | Admin | — | `200 {"users":[…]}` (no password hashes) |
| `PUT /api/admin/users/{id}` | Admin | `{"role":"STAFF","active":true}` | `200 {"message":"Account updated."}` |
| `GET /api/admin/settings` | Admin | — | `200 {"settings":{"slot_duration_minutes":"5",…}}` |
| `PUT /api/admin/settings` | Admin | Any of `slotDurationMinutes`, `slotCapacity`, `cancellationCutoffMinutes`; optionally both `canteenOpen` and `canteenClose` | `200 {"message":"Settings saved."}` |

Allowed lifecycle transitions: `PENDING → CONFIRMED → PREPARING → READY → COLLECTED`. Cancellation is separate and permitted only before preparation; student cancellation also observes the configured pickup cutoff.

## Error codes

Error responses use `{"error":"Human-readable explanation"}`:

- `400`: invalid input, full/passed slot, insufficient stock, expired cancellation or invalid transition.
- `401`: unauthenticated.
- `403`: wrong role or missing/invalid CSRF token.
- `404`: missing endpoint/resource or an order not owned by the student.
- `409`: duplicate email/slot/name or referenced menu item cannot be deleted.
- `500`: service or CSV storage failure; stack traces are logged only server-side.

## CSV storage

The application lazily creates and seeds its CSV data file at `%USERPROFILE%\.campusbite\campusbite.csv` (or the path configured by `CAMPUSBITE_DATA_FILE` / `-Dcampusbite.dataFile`). The file includes account credential hashes and should be protected like other private application data. The server uses an in-process lock; running multiple CampusBite instances against one CSV file is not supported.

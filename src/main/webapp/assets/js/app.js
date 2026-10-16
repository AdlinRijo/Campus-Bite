(() => {
  "use strict";
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const state = {
    user: null, csrf: "", foods: [], category: "All",
    cart: JSON.parse(localStorage.getItem("campusbite-cart") || "{}"),
    page: "menu", authMode: "login", activeOrderId: null, orderTimer: null, kitchenTimer: null
  };
  const money = value => new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(Number(value || 0));
  const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
  const imageUrl = value => /^https?:\/\//i.test(value || "") ? value : "";

  async function api(path, options = {}) {
    const method = (options.method || "GET").toUpperCase();
    const headers = { "Accept": "application/json", ...(options.headers || {}) };
    if (options.body && typeof options.body !== "string") {
      headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(options.body);
    }
    if (!["GET", "HEAD", "OPTIONS"].includes(method) && state.csrf) headers["X-CSRF-Token"] = state.csrf;
    const response = await fetch(`api${path}`, { ...options, method, headers, credentials: "same-origin" });
    let data = {};
    try { data = await response.json(); } catch { /* Empty response body. */ }
    if (!response.ok) throw new Error(data.error || `Request failed (${response.status}).`);
    return data;
  }

  function toast(message, isError = false) {
    const item = document.createElement("div");
    item.className = `toast${isError ? " error" : ""}`;
    item.textContent = message;
    $("#toast-region").append(item);
    setTimeout(() => item.remove(), 3800);
  }

  function setPage(page) {
    const known = ["menu", "orders", "kitchen", "admin", "auth"];
    if (!known.includes(page)) page = "menu";
    if ((page === "kitchen" || page === "admin") && !state.user) page = "auth";
    if (page === "kitchen" && !["STAFF", "ADMIN"].includes(state.user?.role)) page = "menu";
    if (page === "admin" && state.user?.role !== "ADMIN") page = "menu";
    state.page = page;
    $$(".page").forEach(section => section.classList.toggle("hidden", section.id !== `page-${page}`));
    $$(".main-nav a").forEach(link => link.classList.toggle("active", link.dataset.page === page));
    if (page === "menu") loadMenu();
    if (page === "orders") loadOrders();
    if (page === "kitchen") loadKitchen();
    if (page === "admin") loadAdmin();
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function updateHeader() {
    const loggedIn = Boolean(state.user);
    $("#header-user").textContent = loggedIn ? state.user.name : "";
    if (loggedIn && !$("#pickup-name").value) $("#pickup-name").value = state.user.name;
    $("#login-open").classList.toggle("hidden", loggedIn);
    $("#register-open").classList.toggle("hidden", loggedIn);
    $("#logout-button").classList.toggle("hidden", !loggedIn);
    $$(".student-link").forEach(el => el.classList.toggle("hidden", !loggedIn || state.user.role !== "STUDENT"));
    $$(".staff-link").forEach(el => el.classList.toggle("hidden", !loggedIn || !["STAFF", "ADMIN"].includes(state.user.role)));
    $$(".admin-link").forEach(el => el.classList.toggle("hidden", !loggedIn || state.user.role !== "ADMIN"));
  }

  async function loadMenu() {
    $("#menu-grid").innerHTML = "<div class=\"empty-state\">Loading today's menu…</div>";
    try {
      const result = await api("/food");
      state.foods = result.items;
      renderCategories();
      renderMenu();
      renderCart();
    } catch (error) {
      $("#menu-grid").innerHTML = `<div class="empty-state">${escapeHtml(error.message)} <button class="text-link" id="menu-retry">Try again</button></div>`;
      $("#menu-retry")?.addEventListener("click", loadMenu);
    }
  }

  function renderCategories() {
    const categories = ["All", ...new Set(state.foods.map(item => item.category))];
    if (state.category !== "All" && !categories.includes(state.category)) categories.unshift(state.category);
    $("#category-filters").innerHTML = categories.map(category =>
      `<button class="category-chip${category === state.category ? " active" : ""}" data-category="${escapeHtml(category)}" type="button">${escapeHtml(category)}</button>`
    ).join("");
  }

  function renderMenu() {
    const search = $("#menu-search").value.trim().toLocaleLowerCase();
    const results = state.foods.filter(item => (state.category === "All" || item.category === state.category)
      && (!search || `${item.name} ${item.description} ${item.category}`.toLocaleLowerCase().includes(search)));
    const sort = $("#menu-sort").value;
    if (sort === "price-asc") results.sort((a, b) => Number(a.price) - Number(b.price));
    if (sort === "price-desc") results.sort((a, b) => Number(b.price) - Number(a.price));
    if (sort === "name") results.sort((a, b) => a.name.localeCompare(b.name));
    $("#menu-results").textContent = `${results.length} ${results.length === 1 ? "dish" : "dishes"} on the menu`;
    if (!results.length) {
      $("#menu-grid").innerHTML = '<div class="empty-state">No dishes match those filters. Try another search.</div>';
      return;
    }
    $("#menu-grid").innerHTML = results.map(item => {
      const img = imageUrl(item.imageUrl);
      return `<article class="food-card">
        <div class="food-photo"><img class="food-image" src="${escapeHtml(img || "assets/img/food-placeholder.svg")}" alt="${escapeHtml(item.name)}">
          <span class="food-category">${escapeHtml(item.category)}</span>
        </div>
        <div class="food-card-body">
          <div class="food-title-row"><h3>${escapeHtml(item.name)}</h3><span class="food-price">${money(item.price)}</span></div>
          <p>${escapeHtml(item.description)}</p>
          <div class="food-card-footer"><span class="availability${item.available && item.stockQuantity > 0 ? "" : " sold-out"}">${item.available && item.stockQuantity > 0 ? `${item.stockQuantity} available` : "Sold out"}</span>
            <button class="add-button" data-add="${item.id}" type="button" ${!item.available || item.stockQuantity < 1 ? "disabled" : ""}>Add +</button></div>
        </div>
      </article>`;
    }).join("");
    $$(".food-image").forEach(el => {
      el.addEventListener("error", () => { el.src = "assets/img/food-placeholder.svg"; }, { once: true });
    });
  }

  function persistCart() {
    localStorage.setItem("campusbite-cart", JSON.stringify(state.cart));
    $("#cart-count").textContent = Object.values(state.cart).reduce((sum, quantity) => sum + quantity, 0);
    renderCart();
  }

  function renderCart() {
    const entries = Object.entries(state.cart).filter(([, quantity]) => Number(quantity) > 0);
    if (!entries.length) {
      $("#cart-items").innerHTML = '<div class="empty-state">Your order is empty.<br>Pick something delicious from the menu.</div>';
      $("#cart-total").textContent = money(0);
      $("#checkout-button").disabled = true;
      return;
    }
    let total = 0;
    $("#cart-items").innerHTML = entries.map(([id, quantity]) => {
      const food = state.foods.find(item => String(item.id) === id);
      if (!food) return "";
      total += Number(food.price) * quantity;
      return `<div class="cart-line"><div><strong>${escapeHtml(food.name)}</strong><small>${money(food.price)} each</small></div>
        <div class="quantity-control"><button type="button" data-quantity="${id}" data-delta="-1" aria-label="Remove one ${escapeHtml(food.name)}">−</button><span>${quantity}</span><button type="button" data-quantity="${id}" data-delta="1" aria-label="Add one ${escapeHtml(food.name)}">+</button></div></div>`;
    }).join("");
    $("#cart-total").textContent = money(total);
    $("#checkout-button").disabled = false;
  }

  function openCart() {
    $("#cart-drawer").classList.remove("hidden");
    $("#drawer-scrim").classList.remove("hidden");
    preparePickupDate();
  }

  function closeCart() {
    $("#cart-drawer").classList.add("hidden");
    $("#drawer-scrim").classList.add("hidden");
  }

  function preparePickupDate() {
    const dateInput = $("#pickup-date");
    const date = new Date();
    const today = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
    if (!dateInput.value) dateInput.value = today;
    dateInput.min = today;
    const max = new Date(date);
    max.setDate(max.getDate() + 30);
    dateInput.max = `${max.getFullYear()}-${String(max.getMonth() + 1).padStart(2, "0")}-${String(max.getDate()).padStart(2, "0")}`;
  }

  async function submitOrder() {
    const pickupName = $("#pickup-name").value.trim();
    if (pickupName.length < 2 || pickupName.length > 100) return toast("Enter a pickup name (2–100 characters).", true);
    const pickupDate = $("#pickup-date").value;
    if (!pickupDate) return toast("Choose a pickup date.", true);
    const items = Object.entries(state.cart).map(([foodItemId, quantity]) => ({ foodItemId: Number(foodItemId), quantity: Number(quantity) }));
    if (!items.length) return toast("Your cart is empty.", true);
    $("#payment-amount").textContent = $("#cart-total").textContent;
    $("#payment-dialog").showModal();
  }

  async function completeSimulatedPayment() {
    const button = $("#checkout-button");
    const paymentButton = $("#payment-confirm");
    button.disabled = true;
    paymentButton.disabled = true;
    paymentButton.textContent = "Processing demo…";
    let requestId = sessionStorage.getItem("campusbite-request-id");
    if (!requestId) {
      requestId = crypto.randomUUID();
      sessionStorage.setItem("campusbite-request-id", requestId);
    }
    try {
      const result = await api("/orders", {
        method: "POST", headers: { "Idempotency-Key": requestId },
        body: {
          pickupDate: $("#pickup-date").value,
          items: Object.entries(state.cart).map(([foodItemId, quantity]) => ({
            foodItemId: Number(foodItemId), quantity: Number(quantity)
          })),
          paymentMethod: "GOOGLE_PAY_SIMULATION",
          pickupName: $("#pickup-name").value.trim()
        }
      });
      state.cart = {};
      persistCart();
      sessionStorage.removeItem("campusbite-request-id");
      $("#payment-dialog").close();
      closeCart();
      state.activeOrderId = result.order.id;
      showTicket(result.order);
      if (result.order.slotDate !== $("#pickup-date").value) {
        toast(`No capacity was available on your selected date. Pickup is scheduled for ${result.order.slotDate}.`);
      }
      if (!state.user) {
        $("#page-orders .page-heading h2").textContent = "Order placed";
        $("#student-orders").innerHTML = '<div class="empty-state">Your order details are shown below. Sign in to access order history.</div>';
      }
      setPage("orders");
      if (state.user) state.orderTimer ??= setInterval(refreshTicket, 4000);
      toast(`Demo payment simulated. Your order token is ${result.order.tokenNumber}.`);
    } catch (error) {
      $("#payment-dialog").close();
      toast(error.message, true);
    } finally {
      button.disabled = false;
      paymentButton.disabled = false;
      paymentButton.textContent = "Simulate payment";
    }
  }

  function showTicket(order) {
    const panel = $("#active-ticket");
    panel.classList.remove("hidden");
    panel.innerHTML = `<div class="ticket-token">${escapeHtml(order.tokenNumber)}</div><h3>${escapeHtml(order.status)}</h3>
      <p>Pickup date: ${escapeHtml(order.slotDate)}</p>
      <p>${money(order.totalAmount)}</p>
      <p>${(order.items || []).map(item => `${escapeHtml(item.quantity)} × ${escapeHtml(item.name)}`).join(" &nbsp;·&nbsp; ")}</p>
      <p>${order.status === "READY" ? "Your food is ready for pickup!" : `${Number(order.ordersAhead || 0)} orders ahead`}</p>`;
  }

  async function loadOrders() {
    if (!state.user) {
      $("#page-orders .page-heading h2").textContent = state.activeOrderId ? "Order placed" : "Order tracking";
      $("#orders-intro").textContent = state.activeOrderId
        ? "Keep your order token to collect it."
        : "Sign in only if you want to see previous orders.";
      $("#student-orders").innerHTML = state.activeOrderId
        ? ""
        : '<div class="empty-state">You can order without an account. Your current receipt will appear here after checkout.</div>';
      return;
    }
    $("#page-orders .page-heading h2").textContent = "My orders";
    $("#orders-intro").textContent = "Check the status of your orders.";
    $("#student-orders").innerHTML = '<div class="empty-state">Loading your orders…</div>';
    try {
      const data = await api("/orders");
      const list = data.orders;
      $("#student-orders").innerHTML = list.length ? list.map(order => `<article class="order-card">
        <div><h3>${escapeHtml(order.tokenNumber)} <span class="status-badge status-${escapeHtml(order.status.toLowerCase())}">${escapeHtml(order.status)}</span></h3>
          <p>${escapeHtml(order.slotDate)} · ${money(order.totalAmount)}</p></div>
        <div class="order-actions"><button type="button" class="button button-outline button-small" data-track="${order.id}">Track</button>
          ${["PENDING", "CONFIRMED"].includes(order.status) ? `<button type="button" class="button button-quiet button-small" data-cancel="${order.id}">Cancel</button>` : ""}
          <button type="button" class="button button-quiet button-small" data-reorder="${order.id}">Reorder</button></div></article>`).join("")
        : '<div class="empty-state">No orders yet. Your next campus favourite is waiting.</div>';
    } catch (error) { $("#student-orders").innerHTML = `<div class="empty-state">${escapeHtml(error.message)}</div>`; }
  }

  async function refreshTicket() {
    if (!state.activeOrderId) return;
    try {
      const data = await api(`/orders/${state.activeOrderId}`);
      showTicket(data.order);
    } catch (error) { console.error("Order tracking refresh failed:", error); }
  }

  async function trackOrder(id) {
    state.activeOrderId = Number(id);
    try {
      const data = await api(`/orders/${id}`);
      showTicket(data.order);
      state.orderTimer ??= setInterval(refreshTicket, 4000);
    } catch (error) { toast(error.message, true); }
  }

  async function cancelOrder(id) {
    if (!confirm("Cancel this order? Cancellation is only possible before the cutoff and before preparation starts.")) return;
    try {
      await api(`/orders/${id}`, { method: "DELETE" });
      toast("Order cancelled. Eligible stock and pickup capacity have been released.");
      loadOrders();
    } catch (error) { toast(error.message, true); }
  }

  async function reorder(id) {
    try {
      const { order } = await api(`/orders/${id}`);
      const menu = await api("/food");
      state.foods = menu.items;
      const byName = new Map(menu.items.map(food => [food.name, food]));
      for (const item of order.items || []) {
        const current = byName.get(item.name);
        if (current?.available && current.stockQuantity > 0) state.cart[current.id] = (state.cart[current.id] || 0) + item.quantity;
      }
      persistCart();
      setPage("menu");
      openCart();
    } catch (error) { toast(error.message, true); }
  }

  async function pollNotifications() {
    if (state.user?.role !== "STUDENT" || document.hidden) return;
    try {
      const { notifications } = await api("/notifications");
      for (const notification of notifications.filter(item => !item.read)) {
        toast(notification.message);
        await api(`/notifications/${notification.id}/read`, { method: "PUT" });
      }
    } catch (error) { console.error("Notification refresh failed:", error); }
  }

  async function loadKitchen() {
    try {
      const status = $("#staff-status").value;
      const query = status ? `?status=${encodeURIComponent(status)}` : "";
      const [data, food] = await Promise.all([api(`/staff/orders${query}`), api("/food")]);
      renderKitchen(data.orders);
      $("#staff-food-list").innerHTML = food.items.map(item => `<div class="admin-menu-row"><div><strong>${escapeHtml(item.name)}</strong><small>${escapeHtml(item.category)} · ${money(item.price)}</small></div><label><input type="number" min="0" value="${item.stockQuantity}" data-staff-stock="${item.id}" aria-label="Stock for ${escapeHtml(item.name)}"> stock</label><label><input type="checkbox" data-staff-available="${item.id}" ${item.available ? "checked" : ""} aria-label="Available ${escapeHtml(item.name)}"> available</label></div>`).join("");
      const today = new Date();
      const day = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
      const slotData = await api(`/slots?date=${day}`);
      $("#slot-summary").innerHTML = slotData.slots.map(slot =>
        `<div class="slot-pill"><strong>${escapeHtml(slot.startTime.slice(0, 5))}–${escapeHtml(slot.endTime.slice(0, 5))}</strong>${slot.reservedCount}/${slot.capacity} orders</div>`
      ).join("") || '<div class="empty-state">No pickup slots scheduled for today.</div>';
    } catch (error) { $("#staff-orders").innerHTML = `<div class="empty-state">${escapeHtml(error.message)}</div>`; }
  }

  function renderKitchen(orders) {
    const active = orders.filter(order => !["COLLECTED", "CANCELLED"].includes(order.status));
    $("#staff-orders").innerHTML = active.length ? active.map(order => {
      const next = ({ PENDING: "CONFIRMED", CONFIRMED: "PREPARING", PREPARING: "READY", READY: "COLLECTED" })[order.status];
      return `<article class="kitchen-card status-${escapeHtml(order.status)}"><div class="kitchen-top"><div><div class="kitchen-token">${escapeHtml(order.tokenNumber)}</div><span class="kitchen-time">${escapeHtml(order.slotStart.slice(0, 5))} pickup · ${escapeHtml(order.studentName)}</span></div><span class="status-badge status-${escapeHtml(order.status.toLowerCase())}">${escapeHtml(order.status)}</span></div>
        <ul>${(order.items || []).map(item => `<li>${escapeHtml(item.quantity)} × ${escapeHtml(item.name)}</li>`).join("")}</ul>
        <div class="kitchen-time">${money(order.totalAmount)} · ${escapeHtml(order.paymentMethod.replaceAll("_", " ").toLowerCase())}</div>
        ${next ? `<button class="button button-primary kitchen-button" data-next-status="${next}" data-order-id="${order.id}" type="button">${next === "COLLECTED" ? "Verify & collect token" : `${next[0]}${next.slice(1).toLowerCase()}`}</button>` : ""}
        ${["PENDING", "CONFIRMED"].includes(order.status) ? `<button class="text-link" data-staff-cancel="${order.id}" type="button">Reject / cancel</button>` : ""}</article>`;
    }).join("") : '<div class="empty-state">No active orders right now. New orders will appear here.</div>';
  }

  async function changeOrderStatus(id, status) {
    try {
      await api(`/staff/orders/${id}/status`, { method: "PUT", body: { status } });
      toast(`Order moved to ${status.toLowerCase()}.`);
      loadKitchen();
    } catch (error) { toast(error.message, true); }
  }

  async function staffCancel(id) {
    const reason = prompt("Reason for rejecting this order:");
    if (!reason) return;
    try {
      await api(`/staff/orders/${id}/status`, { method: "PUT", body: { status: "CANCELLED", reason } });
      toast("Order cancelled and capacity released.");
      loadKitchen();
    } catch (error) { toast(error.message, true); }
  }

  async function loadAdmin() {
    try {
      const [stats, users, food, report, settings] = await Promise.all([
        api("/admin/stats"), api("/admin/users"), api("/food"), api("/admin/reports"), api("/admin/settings")
      ]);
      state.foods = food.items;
      const status = stats.statusCounts || {};
      $("#admin-stats").innerHTML = [
        ["Today's orders", stats.ordersToday], ["Today's revenue", money(stats.revenueToday)],
        ["Awaiting collection", status.READY || 0], ["Average prep", `${Math.round(Number(stats.averagePreparationMinutes || 0))} min`],
        ["Peak hour", stats.peakHour === null ? "—" : `${String(stats.peakHour).padStart(2, "0")}:00`],
        ["Top dish today", stats.popularItems?.[0]?.name || "—"]
      ].map(([label, value]) => `<div class="stat-card"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value ?? 0)}</strong></div>`).join("");
      $("#admin-menu").innerHTML = food.items.map(item => `<div class="admin-menu-row"><div><strong>${escapeHtml(item.name)}</strong><small>${escapeHtml(item.category)} · ${money(item.price)} · ${item.available ? "Available" : "Unavailable"}</small></div><div class="admin-row-actions"><label><input type="number" min="0" value="${item.stockQuantity}" data-stock-id="${item.id}" aria-label="Stock for ${escapeHtml(item.name)}"> stock</label><label><input type="checkbox" data-admin-available="${item.id}" ${item.available ? "checked" : ""} aria-label="Available ${escapeHtml(item.name)}"> available</label><button class="text-link" data-edit-food="${item.id}" type="button">Edit</button><button class="text-link" data-delete-food="${item.id}" type="button">Delete</button></div></div>`).join("");
      $("#admin-users").innerHTML = users.users.map(user => `<div class="admin-user-row"><div><strong>${escapeHtml(user.name)}</strong><small>${escapeHtml(user.email)}</small></div><label><select data-role-id="${user.id}" aria-label="Role for ${escapeHtml(user.name)}"><option ${user.role === "STUDENT" ? "selected" : ""}>STUDENT</option><option ${user.role === "STAFF" ? "selected" : ""}>STAFF</option><option ${user.role === "ADMIN" ? "selected" : ""}>ADMIN</option></select></label><label><input type="checkbox" data-active-id="${user.id}" ${user.active ? "checked" : ""} aria-label="Active ${escapeHtml(user.name)}"> active</label></div>`).join("");
      const settingsMap = settings.settings || {};
      const form = $("#settings-form");
      form.elements.slotDurationMinutes.value = settingsMap.slot_duration_minutes || 5;
      form.elements.slotCapacity.value = settingsMap.slot_capacity || 30;
      form.elements.cancellationCutoffMinutes.value = settingsMap.cancellation_cutoff_minutes || 10;
      form.elements.canteenOpen.value = settingsMap.canteen_open || "07:30";
      form.elements.canteenClose.value = settingsMap.canteen_close || "15:00";
      renderReport(report);
    } catch (error) { toast(error.message, true); }
  }

  function renderReport(report) {
    const rows = report.daily || [];
    $("#admin-report").innerHTML = rows.length ? `<table class="report-table"><thead><tr><th>Date</th><th>Orders</th><th>Revenue</th><th>Cancelled</th></tr></thead><tbody>${rows.map(row =>
      `<tr><td>${escapeHtml(row.date)}</td><td>${row.orders}</td><td>${money(row.revenue)}</td><td>${row.cancelled}</td></tr>`
    ).join("")}</tbody></table>` : '<div class="empty-state">No report data for this period.</div>';
  }

  async function saveUser(id) {
    const role = $(`[data-role-id="${id}"]`).value;
    const active = $(`[data-active-id="${id}"]`).checked;
    try { await api(`/admin/users/${id}`, { method: "PUT", body: { role, active } }); toast("Account updated."); }
    catch (error) { toast(error.message, true); loadAdmin(); }
  }

  function showAuth(mode) {
    state.authMode = mode;
    const registering = mode === "register";
    $("#auth-heading").innerHTML = registering ? "Join<br>CampusBite<span class='heading-period'>.</span>" : "Welcome<br>back<span class='heading-period'>.</span>";
    $("#auth-title").textContent = registering ? "Create your account" : "Log in";
    $("#auth-kicker").textContent = registering ? "YOUR NEXT CAMPUS FAVOURITE" : "YOUR CAMPUSBITE ACCOUNT";
    $("#auth-submit").innerHTML = registering ? "Create account <span aria-hidden='true'>→</span>" : "Log in <span aria-hidden='true'>→</span>";
    $("#name-field").classList.toggle("hidden", !registering);
    $("#name-field").querySelector("input").required = registering;
    $("#auth-form").elements.password.autocomplete = registering ? "new-password" : "current-password";
    $("#auth-switch").innerHTML = registering
      ? 'Already have an account? <button type="button" id="auth-switch-button">Log in</button>'
      : 'New to CampusBite? <button type="button" id="auth-switch-button">Create an account</button>';
    $("#auth-switch-button").addEventListener("click", () => showAuth(registering ? "login" : "register"), { once: true });
    setPage("auth");
  }

  async function submitAuth(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const payload = Object.fromEntries(new FormData(form).entries());
    try {
      const result = await api(`/auth/${state.authMode}`, { method: "POST", body: payload });
      state.user = result.user;
      state.csrf = result.csrfToken;
      updateHeader();
      form.reset();
      setPage(state.user.role === "ADMIN" ? "admin" : state.user.role === "STAFF" ? "kitchen" : "menu");
      toast(`Welcome${state.user.name ? `, ${state.user.name.split(" ")[0]}` : ""}!`);
    } catch (error) { toast(error.message, true); }
  }

  async function signOut() {
    try {
      await api("/auth/logout", { method: "POST" });
      state.user = null;
      state.csrf = "";
      state.activeOrderId = null;
      updateHeader();
      setPage("menu");
      toast("You have been signed out.");
    } catch (error) { toast(error.message, true); }
  }

  function downloadReport() {
    api("/admin/reports?range=week").then(result => {
      const lines = [["Date", "Orders", "Active or completed", "Revenue INR", "Cancelled"],
        ...(result.daily || []).map(row => [row.date, row.orders, row.activeOrCompleted, row.revenue, row.cancelled])];
      const csv = lines.map(line => line.map(value => `"${String(value ?? "").replaceAll('"', '""')}"`).join(",")).join("\r\n");
      const link = document.createElement("a");
      link.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
      link.download = "campusbite-weekly-report.csv";
      link.click();
      URL.revokeObjectURL(link.href);
    }).catch(error => toast(error.message, true));
  }

  function wireEvents() {
    document.addEventListener("click", event => {
      const pageLink = event.target.closest("[data-page]");
      if (pageLink) {
        event.preventDefault();
        const page = pageLink.dataset.page;
        if (window.location.hash === `#${page}`) setPage(page);
        else window.location.hash = page;
        return;
      }
      const add = event.target.closest("[data-add]");
      if (add) {
        const id = add.dataset.add;
        state.cart[id] = (state.cart[id] || 0) + 1;
        persistCart();
        toast("Added to your order.");
        return;
      }
      const quantity = event.target.closest("[data-quantity]");
      if (quantity) {
        const id = quantity.dataset.quantity;
        state.cart[id] = (state.cart[id] || 0) + Number(quantity.dataset.delta);
        if (state.cart[id] <= 0) delete state.cart[id];
        persistCart();
        return;
      }
      const category = event.target.closest("[data-category]");
      if (category) { state.category = category.dataset.category; renderCategories(); renderMenu(); return; }
      const track = event.target.closest("[data-track]");
      if (track) { trackOrder(track.dataset.track); return; }
      const cancel = event.target.closest("[data-cancel]");
      if (cancel) { cancelOrder(cancel.dataset.cancel); return; }
      const reorderButton = event.target.closest("[data-reorder]");
      if (reorderButton) { reorder(reorderButton.dataset.reorder); return; }
      const status = event.target.closest("[data-next-status]");
      if (status) {
        if (status.dataset.nextStatus === "COLLECTED") verifyAndCollect(status.dataset.orderId);
        else changeOrderStatus(status.dataset.orderId, status.dataset.nextStatus);
        return;
      }
      const reject = event.target.closest("[data-staff-cancel]");
      if (reject) { staffCancel(reject.dataset.staffCancel); return; }
      const editFood = event.target.closest("[data-edit-food]");
      if (editFood) { editMenuItem(editFood.dataset.editFood); return; }
      const deleteFood = event.target.closest("[data-delete-food]");
      if (deleteFood) { deleteMenuItem(deleteFood.dataset.deleteFood); return; }
    });
    $("#login-open").addEventListener("click", () => showAuth("login"));
    $("#register-open").addEventListener("click", () => showAuth("register"));
    $("#auth-close").addEventListener("click", () => setPage("menu"));
    $("#auth-form").addEventListener("submit", submitAuth);
    $("#logout-button").addEventListener("click", signOut);
    $("#menu-search").addEventListener("input", debounce(renderMenu, 180));
    $("#menu-sort").addEventListener("change", renderMenu);
    $("#cart-toggle").addEventListener("click", openCart);
    $("#cart-close").addEventListener("click", closeCart);
    $("#drawer-scrim").addEventListener("click", closeCart);
    $("#checkout-button").addEventListener("click", submitOrder);
    $("#payment-confirm").addEventListener("click", completeSimulatedPayment);
    $("#staff-status").addEventListener("change", loadKitchen);
    $("#kitchen-mode").addEventListener("click", () => {
      document.body.classList.toggle("kitchen-mode");
      $("#kitchen-mode").textContent = document.body.classList.contains("kitchen-mode") ? "Exit display" : "Kitchen display";
    });
    $("#reports-download").addEventListener("click", downloadReport);
    $("#admin-menu").addEventListener("change", async event => {
      const input = event.target.closest("[data-stock-id], [data-admin-available]");
      if (!input) return;
      const id = input.dataset.stockId || input.dataset.adminAvailable;
      const food = state.foods.find(item => String(item.id) === id);
      try {
        const stock = $(`[data-stock-id="${id}"]`).value;
        const available = $(`[data-admin-available="${id}"]`).checked;
        await api(`/food/${food.id}`, { method: "PUT", body: { ...food, stockQuantity: Number(stock), available } });
        toast(`${food.name} availability updated.`);
        loadAdmin();
      } catch (error) { toast(error.message, true); }
    });
    $("#staff-food-list").addEventListener("change", async event => {
      const input = event.target.closest("[data-staff-stock], [data-staff-available]");
      if (!input) return;
      const id = input.dataset.staffStock || input.dataset.staffAvailable;
      const row = input.closest(".admin-menu-row");
      const payload = {
        stockQuantity: Number(row.querySelector(`[data-staff-stock="${id}"]`).value),
        available: row.querySelector(`[data-staff-available="${id}"]`).checked
      };
      try {
        await api(`/staff/food/${id}`, { method: "PUT", body: payload });
        toast("Dish stock and availability updated.");
      } catch (error) { toast(error.message, true); loadKitchen(); }
    });
    $("#admin-users").addEventListener("change", event => {
      const row = event.target.closest("[data-role-id], [data-active-id]");
      if (row) saveUser(row.dataset.roleId || row.dataset.activeId);
    });
    $("#settings-form").addEventListener("submit", async event => {
      event.preventDefault();
      const data = Object.fromEntries(new FormData(event.currentTarget).entries());
      try {
        for (const key of ["slotDurationMinutes", "slotCapacity", "cancellationCutoffMinutes"]) {
          data[key] = Number(data[key]);
        }
        await api("/admin/settings", { method: "PUT", body: data });
        toast("Pickup settings saved.");
      } catch (error) { toast(error.message, true); }
    });
    $("#slot-form").addEventListener("submit", async event => {
      event.preventDefault();
      const data = Object.fromEntries(new FormData(event.currentTarget).entries());
      data.capacity = Number(data.capacity);
      try {
        await api("/slots", { method: "POST", body: data });
        toast("Pickup slot created.");
        event.currentTarget.reset();
      } catch (error) { toast(error.message, true); }
    });
    $("#generate-slots").addEventListener("click", async () => {
      const form = $("#slot-form");
      const data = Object.fromEntries(new FormData(form).entries());
      try {
        const result = await api("/admin/slots/generate", { method: "POST", body: {
          date: data.date, startTime: data.startTime, endTime: data.endTime
        } });
        toast(`${result.created} pickup slots created.`);
      } catch (error) { toast(error.message, true); }
    });
    $("#new-dish").addEventListener("click", async () => {
      const name = prompt("Dish name:");
      if (!name?.trim()) return;
      const price = Number(prompt("Price in INR:"));
      if (!Number.isFinite(price) || price < 0) return toast("Enter a valid price.", true);
      const category = prompt("Category (Breakfast, Meals, Snacks, Fast Food, Drinks):", "Snacks");
      if (!category?.trim()) return;
      try {
        await api("/food", { method: "POST", body: { name: name.trim(), description: "", category: category.trim(), price, stockQuantity: 0, imageUrl: "", available: false } });
        toast("Menu item added. Update its stock to make it available.");
        loadAdmin();
      } catch (error) { toast(error.message, true); }
    });
    window.addEventListener("hashchange", () => {
      const page = window.location.hash.slice(1);
      setPage(page || "menu");
    });
  }

  async function editMenuItem(id) {
    const item = state.foods.find(food => String(food.id) === String(id));
    if (!item) return;
    const name = prompt("Dish name:", item.name);
    if (name === null) return;
    const price = Number(prompt("Price in INR:", item.price));
    const category = prompt("Category:", item.category);
    const description = prompt("Description:", item.description);
    const imageUrlValue = prompt("Image URL (https://… or blank):", item.imageUrl);
    if (!Number.isFinite(price) || price < 0 || !category?.trim() || name.trim().length < 1) return toast("Enter valid menu item details.", true);
    try {
      await api(`/food/${id}`, { method: "PUT", body: { ...item, name: name.trim(), price, category: category.trim(), description: description || "", imageUrl: imageUrlValue || "" } });
      toast("Menu item updated.");
      loadAdmin();
    } catch (error) { toast(error.message, true); }
  }

  async function verifyAndCollect(id) {
    try {
      const result = await api(`/orders/${id}`);
      const token = prompt("Verify the student's pickup token:", result.order.tokenNumber);
      if (token === null) return;
      if (token.trim() !== result.order.tokenNumber) return toast("That token does not match this order.", true);
      await api("/staff/collect", { method: "POST", body: { tokenNumber: token.trim() } });
      toast("Token verified. Order marked collected.");
      loadKitchen();
    } catch (error) { toast(error.message, true); }
  }

  async function deleteMenuItem(id) {
    const item = state.foods.find(food => String(food.id) === String(id));
    if (!item || !confirm(`Delete ${item.name} from the menu? This is blocked if the dish is on an order.`)) return;
    try {
      await api(`/food/${id}`, { method: "DELETE" });
      toast("Menu item deleted.");
      loadAdmin();
    } catch (error) { toast(error.message, true); }
  }

  function debounce(callback, delay) {
    let timer;
    return (...args) => { clearTimeout(timer); timer = setTimeout(() => callback(...args), delay); };
  }

  async function boot() {
    persistCart();
    wireEvents();
    updateHeader();
    try {
      const csrf = await api("/auth/csrf");
      state.csrf = csrf.csrfToken;
      const { user } = await api("/auth/me");
      state.user = user;
      updateHeader();
      if (user) {
        const result = await api("/auth/csrf");
        state.csrf = result.csrfToken;
      }
    } catch { /* Signed-out visitors can still browse the public menu. */ }
    setPage(window.location.hash.slice(1) || "menu");
    state.kitchenTimer = setInterval(() => {
      if (state.page === "kitchen") loadKitchen();
      if (state.page === "admin") loadAdmin();
      if (state.page === "orders") loadOrders();
      pollNotifications();
    }, 5000);
  }

  boot();
})();

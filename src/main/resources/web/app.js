"use strict";

// State: the catalog (from /api/items) and the groups (from /api/groups, saved back with PUT).
const S = {
  items: [],
  byId: new Map(),
  groups: [],
  version: 0,
  ready: true,
  active: null,      // id of the selected group: clicking an item adds it there
  filter: "all",     // all | free | grouped
  query: "",
  groupQuery: "",    // filter of the group cards
  undo: [],
  confirmDelete: null,
  drafts: new Map(),  // group id -> { text, error } for a name being fixed
  lang: readLang(),   // language of the item names: "en" (default) or "it"
};

const $ = (id) => document.getElementById(id);
const MAX_NAME = 40;
const PAGE = 120; // catalog tiles rendered at a time; more are added while scrolling
let sortables = [];
let catalogList = [];
let catalogShown = 0;
const pointer = { x: 0, y: 0 }; // last pointer position while dragging (dragend has no reliable one)

// ---------- Item name language (kept in a cookie) ----------

function readLang() {
  const match = document.cookie.match(/(?:^|;\s*)gems_lang=(en|it)\b/);
  return match ? match[1] : "en";
}

function setLang(lang) {
  S.lang = lang;
  const secure = location.protocol === "https:" ? "; Secure" : "";
  document.cookie = `gems_lang=${lang}; Path=/; Max-Age=31536000; SameSite=Lax${secure}`;
  applyLang();
  if (!$("app").hidden) {
    sortItems();
    catalogList = []; // force a full rebuild of the catalog
    render();
  }
}

/** Translates the static texts and syncs the language selectors. */
function applyLang() {
  applyI18n(S.lang);
  for (const select of document.querySelectorAll(".lang-select")) select.value = S.lang;
  const status = $("save-status");
  status.textContent = t(status.dataset.key || "status.saved");
}

/** The name of an item in the chosen language. */
function nameOf(item) {
  return S.lang === "it" ? item.it : item.en;
}

function sortItems() {
  const collator = new Intl.Collator(S.lang, { sensitivity: "base", numeric: true });
  S.items.sort((a, b) => collator.compare(nameOf(a), nameOf(b)));
}

// ---------- API ----------

async function api(method, path, body) {
  const options = { method, headers: {}, credentials: "same-origin" };
  if (method !== "GET") options.headers["X-Gems"] = "1";
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  let data = null;
  try { data = await response.json(); } catch (_) { /* empty body */ }
  if (response.status === 401) {
    showLogin();
    throw new Error("unauthorized");
  }
  return { status: response.status, data };
}

// ---------- Startup ----------

async function start() {
  applyLang();
  for (const select of document.querySelectorAll(".lang-select")) {
    select.addEventListener("change", (e) => setLang(e.target.value));
  }
  const params = new URLSearchParams(location.search);
  if (params.has("login")) {
    $("login-expired").hidden = params.get("login") !== "expired";
    history.replaceState(null, "", "/");
  }
  try {
    const me = await api("GET", "/api/session");
    $("me").textContent = me.data.name;
    await Promise.all([loadItems(), loadGroups()]);
  } catch (e) {
    if (e.message !== "unauthorized") showFatal();
    return;
  }
  $("loading").hidden = true;
  $("app").hidden = false;
  restoreHelp();
  bindUi();
  render();
}

function showLogin() {
  $("loading").hidden = true;
  $("app").hidden = true;
  $("login").hidden = false;
}

function showFatal() {
  const p = document.createElement("p");
  p.textContent = t("fatal");
  $("loading").replaceChildren(p);
}

async function loadItems() {
  const { data } = await api("GET", "/api/items");
  S.ready = data.ready;
  const rows = Math.max(1, Math.ceil((Math.max(-1, ...data.items.map((i) => i.icon)) + 1) / data.atlas.columns));
  S.atlas = { ...data.atlas, rows };
  const root = document.documentElement.style;
  root.setProperty("--atlas", `url("${data.atlas.url}")`);
  root.setProperty("--atlas-cols", data.atlas.columns);
  root.setProperty("--atlas-rows", rows);
  S.items = data.items.map((item) => ({
    ...item,
    search: normalize(`${item.it} ${item.en} ${item.id}`),
  }));
  S.byId = new Map(S.items.map((item) => [item.id, item]));
  sortItems();
  if (!S.ready) setTimeout(() => loadItems().then(render).catch(() => {}), 5000);
}

async function loadGroups() {
  const { data } = await api("GET", "/api/groups");
  applyServerState(data);
}

function applyServerState(state) {
  S.version = state.version;
  S.groups = state.groups.map((g) => ({ id: g.id, name: g.name, items: [...g.items] }));
  if (S.active && !S.groups.some((g) => g.id === S.active)) S.active = null;
}

// ---------- Helpers ----------

function normalize(text) {
  return text.normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/_/g, " ").toLowerCase();
}

function tagCode(name) {
  return normalize(name.trim().replace(/\s+/g, " "));
}

function ownerMap() {
  const owners = new Map();
  for (const group of S.groups) for (const id of group.items) owners.set(id, group);
  return owners;
}

function hue(id) {
  // FNV-1a, so that similar ids still get well separated colors
  let h = 2166136261;
  for (const ch of id) h = Math.imul(h ^ ch.charCodeAt(0), 16777619) >>> 0;
  return h % 360;
}

function groupColor(group) {
  return `hsl(${hue(group.id)} 62% 48%)`;
}

function newId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return "g" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
}

function itemName(id) {
  const item = S.byId.get(id);
  return item ? nameOf(item) : id;
}

/** The icon of an item: a cell of the atlas (one image for all items), or its initial. */
function iconElement(item) {
  const div = document.createElement("div");
  if (item && item.icon >= 0 && S.ready) {
    const { columns, rows } = S.atlas;
    const col = item.icon % columns;
    const row = Math.floor(item.icon / columns);
    div.className = "icon";
    div.style.backgroundPosition = `${columns > 1 ? (col / (columns - 1)) * 100 : 0}% ${rows > 1 ? (row / (rows - 1)) * 100 : 0}%`;
    return div;
  }
  div.className = "noicon";
  div.textContent = (item ? nameOf(item) : "?").charAt(0).toUpperCase();
  return div;
}

function matches(item, words) {
  return words.every((word) => item.search.includes(word));
}

function queryWords() {
  return normalize(S.query).split(/\s+/).filter(Boolean);
}

// ---------- Changes (every change is undoable and saved automatically) ----------

function change(description, mutate) {
  const before = JSON.stringify(S.groups);
  mutate();
  if (JSON.stringify(S.groups) === before) return;
  S.undo.push({ groups: before, description });
  if (S.undo.length > 100) S.undo.shift();
  render();
  scheduleSave();
}

function undo() {
  const last = S.undo.pop();
  if (!last) return;
  S.groups = JSON.parse(last.groups);
  if (S.active && !S.groups.some((g) => g.id === S.active)) S.active = null;
  render();
  scheduleSave();
  toast(t("toast.undone", { what: last.description }));
}

function addItems(groupId, ids, index) {
  const group = S.groups.find((g) => g.id === groupId);
  if (!group || ids.length === 0) return;
  const label = ids.length === 1 ? itemName(ids[0]) : t("change.nItems", { n: ids.length });
  change(t("change.added", { label, group: group.name }), () => {
    const set = new Set(ids);
    for (const g of S.groups) g.items = g.items.filter((id) => !set.has(id));
    const at = index === undefined ? group.items.length : Math.min(index, group.items.length);
    group.items.splice(at, 0, ...ids);
  });
}

function removeItem(id) {
  const owner = ownerMap().get(id);
  if (!owner) return;
  change(t("change.removed", { item: itemName(id), group: owner.name }), () => {
    owner.items = owner.items.filter((x) => x !== id);
  });
  toast(t("toast.removed", { item: itemName(id), group: owner.name }), t("toast.undo"), undo);
}

function toggleItem(id) {
  const group = S.groups.find((g) => g.id === S.active);
  if (!group) {
    toast(t("toast.pickGroup"));
    $("target").focus();
    return;
  }
  if (group.items.includes(id)) removeItem(id);
  else addItems(group.id, [id]);
}

function createGroup() {
  if (S.groupQuery) {
    S.groupQuery = "";
    $("group-search").value = "";
  }
  let n = S.groups.length + 1;
  const base = t("group.defaultName");
  let name = base;
  const codes = new Set(S.groups.map((g) => tagCode(g.name)));
  while (codes.has(tagCode(name))) name = `${base} ${n++}`;
  const id = newId();
  change(t("change.created", { name }), () => S.groups.unshift({ id, name, items: [] }));
  S.active = id;
  render();
  const input = document.querySelector(`.group[data-id="${id}"] .group-name`);
  if (input) {
    input.focus();
    input.select();
  }
}

function renameGroup(group, input) {
  const name = input.value.trim().replace(/\s+/g, " ").replace(/^[.#]+\s*/, "");
  let error = null;
  if (!name) error = "name.empty";
  else if (name.length > MAX_NAME) error = "name.tooLong";
  else if (S.groups.some((g) => g !== group && tagCode(g.name) === tagCode(name))) error = "name.duplicate";
  if (error) {
    // Keep what was typed so it can be fixed; the group keeps its last valid name.
    S.drafts.set(group.id, { text: input.value, error });
    render();
    return;
  }
  S.drafts.delete(group.id);
  if (name === group.name) {
    render();
    return;
  }
  const old = group.name;
  change(t("change.renamed", { old, name }), () => { group.name = name; });
}

function deleteGroup(group) {
  S.confirmDelete = null;
  change(t("change.deleted", { name: group.name }), () => {
    S.groups = S.groups.filter((g) => g !== group);
  });
  if (S.active === group.id) S.active = null;
  render();
  toast(t("toast.deleted", { name: group.name }), t("toast.undo"), undo);
}

// ---------- Saving ----------

let saveTimer = null;
let saving = false;
let dirty = false;

function scheduleSave() {
  dirty = true;
  setStatus("saving", "status.saving");
  clearTimeout(saveTimer);
  saveTimer = setTimeout(save, 500);
}

async function save() {
  if (saving) return;
  saving = true;
  dirty = false;
  const body = { version: S.version, groups: S.groups };
  try {
    const { status, data } = await api("PUT", "/api/groups", body);
    if (status === 200) {
      S.version = data.version;
      setStatus("saved", "status.saved");
    } else if (status === 409) {
      applyServerState(data.state);
      S.undo = [];
      dirty = false;
      render();
      setStatus("saved", "status.saved");
      toast(data.error, null, null, true);
    } else {
      setStatus("error", "status.error");
      toast(data && data.error ? data.error : t("toast.saveFailed"), null, null, true);
    }
  } catch (e) {
    if (e.message !== "unauthorized") {
      setStatus("error", "status.retry");
      dirty = true;
      setTimeout(scheduleSave, 3000);
    }
  } finally {
    saving = false;
    if (dirty) scheduleSave();
  }
}

function setStatus(state, key) {
  const el = $("save-status");
  el.dataset.state = state;
  el.dataset.key = key;
  el.textContent = t(key);
}

window.addEventListener("beforeunload", (event) => {
  if (dirty || saving) event.preventDefault();
});

// ---------- Rendering ----------

function render() {
  renderCatalog();
  renderGroups();
  $("undo").disabled = S.undo.length === 0;
  renderTarget();
}

function renderTarget() {
  const select = $("target");
  const options = [new Option(t(S.groups.length ? "target.choose" : "target.none"), "")];
  for (const group of S.groups) options.push(new Option(`${group.name} (${group.items.length})`, group.id));
  select.replaceChildren(...options);
  select.value = S.active || "";
  select.disabled = S.groups.length === 0;
  select.parentElement.classList.toggle("set", Boolean(S.active));
}

function renderCatalog(keepScroll = true) {
  const scroll = keepScroll ? $("catalog").scrollTop : 0;
  const shown = keepScroll ? catalogShown : 0;
  const owners = ownerMap();
  const words = queryWords();
  const active = S.groups.find((g) => g.id === S.active);
  let list = S.items.filter((item) => {
    if (S.filter === "free" && owners.has(item.id)) return false;
    if (S.filter === "grouped" && !owners.has(item.id)) return false;
    return words.length === 0 || matches(item, words);
  });
  if (words.length) {
    // Names that start with the search first.
    const q = normalize(S.query.trim());
    // Names starting with the search first: the shown language, then the other one.
    const other = (item) => (S.lang === "it" ? item.en : item.it);
    const rank = (item) => (normalize(nameOf(item)).startsWith(q) ? 0 : normalize(other(item)).startsWith(q) ? 1 : 2);
    list = list.map((item) => [rank(item), item]).sort((a, b) => a[0] - b[0]).map((pair) => pair[1]);
  }

  if (keepScroll && sameItems(list, catalogList)) {
    // Same list: just refresh group badges and highlights of the tiles already shown.
    for (const tile of $("catalog").children) decorateTile(tile, owners.get(tile.dataset.id), active);
  } else {
    catalogList = list;
    catalogShown = 0;
    $("catalog").replaceChildren();
    do appendTiles(owners, active); while (catalogShown < Math.min(shown, list.length));
    $("catalog").scrollTop = scroll;
  }
  $("catalog-empty").hidden = list.length > 0;
  $("catalog-count").textContent = t("items.count", { shown: list.length, total: S.items.length });
  $("catalog-notice").hidden = S.ready;

  // Bulk add: all search results into the selected group.
  const bulk = $("bulk");
  const candidates = active && words.length ? list.filter((item) => owners.get(item.id) !== active) : [];
  bulk.hidden = candidates.length === 0 || candidates.length > 300;
  if (!bulk.hidden) {
    const moved = candidates.filter((item) => owners.has(item.id)).length;
    $("bulk-add").textContent = t("bulk.add", { n: candidates.length, group: active.name });
    $("bulk-note").textContent = moved ? t("bulk.moved", { n: moved }) : "";
    $("bulk-add").onclick = () => addItems(active.id, candidates.map((item) => item.id));
  }
}

/** Adds the next PAGE tiles of the current catalog list. */
function appendTiles(owners = ownerMap(), active = S.groups.find((g) => g.id === S.active)) {
  const fragment = document.createDocumentFragment();
  const end = Math.min(catalogList.length, catalogShown + PAGE);
  for (const item of catalogList.slice(catalogShown, end)) {
    const li = document.createElement("li");
    li.className = "tile";
    li.dataset.id = item.id;
    li.tabIndex = 0;
    li.title = `${nameOf(item)} (${item.id})`;
    li.append(iconElement(item));
    const names = document.createElement("span");
    names.className = "names";
    const label = document.createElement("span");
    label.className = "name";
    label.textContent = nameOf(item);
    names.append(label);
    li.append(names);
    decorateTile(li, owners.get(item.id), active);
    fragment.append(li);
  }
  catalogShown = end;
  $("catalog").append(fragment);
}

function decorateTile(tile, owner, active) {
  tile.classList.toggle("in-active", Boolean(active) && owner === active);
  let badge = tile.querySelector(".badge");
  if (!owner) {
    if (badge) badge.remove();
    return;
  }
  if (!badge) {
    badge = document.createElement("span");
    badge.className = "badge";
    tile.append(badge);
  }
  badge.style.setProperty("--c", groupColor(owner));
  badge.textContent = owner.name;
}

function sameItems(a, b) {
  return a.length === b.length && a.every((item, i) => item === b[i]);
}

function renderGroups() {
  for (const sortable of sortables) sortable.destroy();
  sortables = [];
  const words = queryWords();
  const container = $("groups");
  const fragment = document.createDocumentFragment();
  const gq = tagCode(S.groupQuery).replace(/^[.#]+\s*/, "");
  const visible = gq ? S.groups.filter((g) => tagCode(g.name).includes(gq)) : S.groups;
  $("groups-count").textContent = S.groups.length ? t("groups.count", { shown: visible.length, total: S.groups.length }) : "";
  $("groups-none").hidden = !(S.groups.length && gq && visible.length === 0);

  for (const group of visible) {
    const card = document.createElement("article");
    card.className = "group" + (group.id === S.active ? " active" : "");
    card.dataset.id = group.id;
    card.style.setProperty("--c", groupColor(group));

    const head = document.createElement("div");
    head.className = "group-head";
    const draft = S.drafts.get(group.id);
    const name = document.createElement("input");
    name.className = "group-name" + (draft ? " invalid" : "");
    name.defaultValue = group.name;
    if (draft) name.value = draft.text;
    name.maxLength = MAX_NAME;
    name.setAttribute("aria-label", t("group.nameAria"));
    name.addEventListener("keydown", (e) => {
      if (e.key === "Enter") name.blur();
      if (e.key === "Escape") {
        S.drafts.delete(group.id);
        name.value = group.name;
        render();
      }
    });
    name.addEventListener("change", () => renameGroup(group, name));
    const del = document.createElement("button");
    del.className = "icon-btn";
    del.type = "button";
    del.title = t("group.delete");
    del.setAttribute("aria-label", t("group.deleteAria", { name: group.name }));
    del.textContent = "🗑";
    del.addEventListener("click", () => {
      if (group.items.length === 0) deleteGroup(group);
      else { S.confirmDelete = group.id; render(); }
    });
    head.append(name, del);
    card.append(head);

    if (draft) {
      const error = document.createElement("div");
      error.className = "group-error";
      error.textContent = `${t(draft.error, { max: MAX_NAME })} ${t("name.keeps", { name: group.name })}`;
      card.append(error);
    }

    const meta = document.createElement("div");
    meta.className = "group-meta";
    const tag = document.createElement("code");
    tag.textContent = "." + group.name;
    tag.title = t("group.tagTitle");
    const copy = document.createElement("button");
    copy.type = "button";
    copy.className = "btn ghost small";
    copy.textContent = t("group.copy");
    copy.title = t("group.copyTitle");
    copy.addEventListener("click", () => copyTag(group));
    const select = document.createElement("button");
    select.type = "button";
    select.className = "btn small select-btn";
    select.textContent = t(group.id === S.active ? "group.selected" : "group.select");
    select.title = t("group.selectTitle");
    select.setAttribute("aria-pressed", String(group.id === S.active));
    select.addEventListener("click", () => {
      S.active = S.active === group.id ? null : group.id;
      render();
    });
    const count = document.createElement("span");
    count.className = "count";
    count.textContent = t("group.count", { n: group.items.length });
    meta.append(tag, copy, select, count);
    card.append(meta);

    if (S.confirmDelete === group.id) {
      const confirm = document.createElement("div");
      confirm.className = "confirm";
      const text = document.createElement("span");
      text.textContent = t("group.confirmDelete", { n: group.items.length });
      const yes = document.createElement("button");
      yes.type = "button";
      yes.className = "btn danger small";
      yes.textContent = t("group.deleteButton");
      yes.addEventListener("click", () => deleteGroup(group));
      const no = document.createElement("button");
      no.type = "button";
      no.className = "btn small";
      no.textContent = t("group.cancel");
      no.addEventListener("click", () => { S.confirmDelete = null; render(); });
      confirm.append(text, yes, no);
      card.append(confirm);
    }

    const chips = document.createElement("ul");
    chips.className = "chips" + (words.length ? " dim" : "");
    chips.dataset.group = group.id;
    for (const id of group.items) {
      const item = S.byId.get(id);
      const chip = document.createElement("li");
      chip.className = "chip";
      if (words.length && item && matches(item, words)) chip.classList.add("match");
      chip.dataset.id = id;
      chip.title = item ? `${nameOf(item)} (${id})` : id;
      chip.append(iconElement(item));
      const label = document.createElement("span");
      label.className = "label";
      label.textContent = itemName(id);
      const x = document.createElement("button");
      x.type = "button";
      x.className = "x";
      x.textContent = "×";
      x.setAttribute("aria-label", t("group.removeAria", { item: itemName(id) }));
      x.addEventListener("click", () => removeItem(id));
      chip.append(label, x);
      chips.append(chip);
    }
    card.append(chips);
    fragment.append(card);
  }
  container.replaceChildren(fragment);
  $("groups-empty").hidden = S.groups.length > 0;

  if (window.Sortable) {
    for (const list of container.querySelectorAll(".chips")) {
      sortables.push(Sortable.create(list, {
        group: { name: "items", pull: true, put: true },
        animation: 150,
        delay: 180,
        delayOnTouchOnly: true,
        filter: ".x",
        preventOnFilter: false,
        onAdd: (evt) => {
          const id = evt.item.dataset.id;
          const index = evt.newIndex;
          evt.item.remove();
          setTimeout(() => addItems(list.dataset.group, [id], index));
        },
        onUpdate: (evt) => {
          const group = S.groups.find((g) => g.id === list.dataset.group);
          const from = evt.oldIndex;
          const to = evt.newIndex;
          setTimeout(() => change(t("change.reordered", { name: group.name }), () => {
            const [id] = group.items.splice(from, 1);
            group.items.splice(to, 0, id);
          }));
        },
        onStart: () => markTargets(true),
        onEnd: (evt) => {
          markTargets(false);
          // Dropped on the catalog panel but not inserted into its list (e.g. on empty space).
          const target = document.elementFromPoint(pointer.x, pointer.y);
          if (evt.from === evt.to && target && target.closest(".catalog")) {
            const id = evt.item.dataset.id;
            setTimeout(() => removeItem(id));
          }
        },
      }));
    }
  }
}

function markTargets(on, fromCatalog = false) {
  for (const el of document.querySelectorAll(".chips")) el.classList.toggle("drop-target", on);
  document.querySelector(".catalog").classList.toggle("drop-target", on && !fromCatalog);
}

for (const type of ["dragover", "pointermove", "touchmove"]) {
  document.addEventListener(type, (e) => {
    const point = e.touches ? e.touches[0] : e;
    if (point && point.clientX !== undefined) {
      pointer.x = point.clientX;
      pointer.y = point.clientY;
    }
  }, { passive: true, capture: true });
}

async function copyTag(group) {
  const text = "." + group.name;
  try {
    await navigator.clipboard.writeText(text);
    toast(t("toast.copied", { text }));
  } catch (_) {
    toast(t("toast.renameLike", { text }));
  }
}

// ---------- Toast ----------

let toastTimer = null;

function toast(text, actionLabel, action, error) {
  const el = $("toast");
  $("toast-text").textContent = text;
  el.classList.toggle("error", Boolean(error));
  const button = $("toast-action");
  button.hidden = !actionLabel;
  button.textContent = actionLabel || "";
  button.onclick = action ? () => { el.hidden = true; action(); } : null;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, error ? 7000 : 4500);
}

// ---------- UI bindings ----------

function bindUi() {
  let searchTimer = null;
  $("search").addEventListener("input", (e) => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => { S.query = e.target.value; renderCatalog(false); renderGroups(); }, 90);
  });
  for (const button of document.querySelectorAll(".chip-btn")) {
    button.addEventListener("click", () => {
      S.filter = button.dataset.filter;
      for (const b of document.querySelectorAll(".chip-btn")) b.setAttribute("aria-pressed", String(b === button));
      renderCatalog(false);
    });
  }
  $("catalog").addEventListener("scroll", () => {
    const el = $("catalog");
    if (catalogShown < catalogList.length && el.scrollTop + el.clientHeight > el.scrollHeight - 400) appendTiles();
  }, { passive: true });
  $("catalog").addEventListener("click", (e) => {
    const tile = e.target.closest(".tile");
    if (tile) toggleItem(tile.dataset.id);
  });
  $("catalog").addEventListener("keydown", (e) => {
    const tile = e.target.closest(".tile");
    if (tile && (e.key === "Enter" || e.key === " ")) {
      e.preventDefault();
      toggleItem(tile.dataset.id);
    }
  });
  $("target").addEventListener("change", (e) => {
    S.active = e.target.value || null;
    render();
  });
  $("group-search").addEventListener("input", (e) => {
    S.groupQuery = e.target.value;
    renderGroups();
  });
  $("group-search").addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      e.target.value = "";
      S.groupQuery = "";
      renderGroups();
    }
  });
  $("new-group").addEventListener("click", createGroup);
  $("new-group-empty").addEventListener("click", createGroup);
  $("undo").addEventListener("click", undo);
  $("logout").addEventListener("click", async () => {
    try { await api("POST", "/api/logout"); } catch (_) { /* already logged out */ }
    showLogin();
  });
  document.addEventListener("keydown", (e) => {
    const typing = e.target.matches("input, textarea");
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "z" && !e.shiftKey && !typing) {
      e.preventDefault();
      undo();
    }
    if (e.key === "/" && !typing) {
      e.preventDefault();
      $("search").focus();
    }
    if (e.key === "Escape" && e.target === $("search")) {
      $("search").value = "";
      S.query = "";
      renderCatalog(false);
      renderGroups();
    }
  });
  $("help").addEventListener("toggle", () => {
    try { localStorage.setItem("gems-help", $("help").open ? "open" : "closed"); } catch (_) { /* no storage */ }
  });

  if (window.Sortable) {
    Sortable.create($("catalog"), {
      group: { name: "items", pull: "clone", put: true },
      sort: false,
      animation: 150,
      delay: 180,
      delayOnTouchOnly: true,
      onAdd: (evt) => {
        const id = evt.item.dataset.id;
        evt.item.remove();
        setTimeout(() => removeItem(id));
      },
      onStart: () => markTargets(true, true),
      onEnd: () => markTargets(false),
    });
  }
}

function restoreHelp() {
  try {
    if (localStorage.getItem("gems-help") === "closed") $("help").open = false;
  } catch (_) { /* no storage */ }
}

start();

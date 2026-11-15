#!/usr/bin/env python3
"""
Prestige Companion
A local Windows desktop dashboard for the Prestige Calculator Fabric mod.

Run with: py companion.py
Python 3.10+; uses only the Python standard library.
"""
from __future__ import annotations

import json
import queue
import re
import sqlite3
import threading
import tkinter as tk
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from urllib.parse import urlparse
from decimal import Decimal, InvalidOperation

APP_DIR = Path(__file__).resolve().parent
DB_PATH = APP_DIR / "prestige_companion.db"
HOST = "127.0.0.1"
PORT = 8765
PAGE_QUEUE: queue.Queue[dict] = queue.Queue()

COST_RE = re.compile(r"^\s*\$?\s*([\d,]+(?:\.\d+)?)\s*(qt|q|t)?\s*$", re.I)


def connect_db():
    db = sqlite3.connect(DB_PATH)
    db.execute("""
        CREATE TABLE IF NOT EXISTS prices (
            rebirth INTEGER NOT NULL,
            prestige INTEGER NOT NULL,
            individual_cost TEXT NOT NULL,
            cumulative_spent TEXT,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (rebirth, prestige)
        )
    """)
    db.execute("""
        CREATE TABLE IF NOT EXISTS scan_pages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            page_number INTEGER,
            scanned_items INTEGER,
            missing_costs INTEGER,
            received_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """)
    db.commit()
    return db


def expected_maximum(rebirth: int) -> int:
    if rebirth < 0:
        return 0
    return 75 + 25 * rebirth if rebirth <= 6 else 300 + 50 * (rebirth - 7)


def parse_cost(value: str) -> Decimal | None:
    match = COST_RE.match(value or "")
    if not match:
        return None
    try:
        amount = Decimal(match.group(1).replace(",", ""))
    except InvalidOperation:
        return None
    unit = (match.group(2) or "t").lower()
    return amount * {"t": Decimal(1), "q": Decimal(1000), "qt": Decimal(1000000)}[unit]


def format_cost(amount: Decimal) -> str:
    if amount >= Decimal(1_000_000):
        value, unit = amount / Decimal(1_000_000), "qt"
    elif amount >= Decimal(1000):
        value, unit = amount / Decimal(1000), "q"
    else:
        value, unit = amount, "T"
    return f"{value.normalize():f}{unit}"


class ApiHandler(BaseHTTPRequestHandler):
    def log_message(self, _format, *_args):
        return

    def _send(self, code: int, payload: dict):
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Access-Control-Allow-Origin", "http://127.0.0.1")
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if urlparse(self.path).path == "/api/health":
            self._send(200, {"ok": True, "app": "Prestige Companion"})
        else:
            self._send(404, {"ok": False, "error": "Not found"})

    def do_POST(self):
        if urlparse(self.path).path != "/api/page":
            self._send(404, {"ok": False, "error": "Not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 2_000_000:
                self._send(400, {"ok": False, "error": "Invalid request size"})
                return
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(payload, dict) or not isinstance(payload.get("prices", []), list):
                raise ValueError("Expected a page object with a prices array")
            PAGE_QUEUE.put(payload)
            self._send(200, {"ok": True, "queued": len(payload.get("prices", []))})
        except Exception as exc:
            self._send(400, {"ok": False, "error": str(exc)})


class CompanionApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("Prestige Companion")
        self.root.geometry("1040x700")
        self.root.minsize(850, 560)
        self.root.configure(bg="#111827")
        self.db = connect_db()
        self.server = None
        self.server_thread = None
        self.filter_var = tk.StringVar(value="All")
        self.search_var = tk.StringVar()
        self.status_var = tk.StringVar(value="Starting local connection...")
        self.rebirth_var = tk.StringVar(value="25")
        self.start_var = tk.StringVar(value="2")
        self.target_var = tk.StringVar(value="100")
        self.result_var = tk.StringVar(value="Enter a range to calculate its total.")
        self._style()
        self._build()
        self._start_server()
        self.refresh()
        self.root.after(150, self._process_queue)
        self.root.protocol("WM_DELETE_WINDOW", self.close)

    def _style(self):
        style = ttk.Style()
        style.theme_use("clam")
        style.configure(".", font=("Segoe UI", 10))
        style.configure("TFrame", background="#111827")
        style.configure("Panel.TFrame", background="#1f2937")
        style.configure("TLabel", background="#111827", foreground="#e5e7eb")
        style.configure("Muted.TLabel", background="#1f2937", foreground="#9ca3af")
        style.configure("Stat.TLabel", background="#1f2937", foreground="#f9fafb",
                        font=("Segoe UI Semibold", 18))
        style.configure("Title.TLabel", background="#111827", foreground="#f9fafb",
                        font=("Segoe UI Semibold", 21))
        style.configure("TButton", padding=(10, 7))
        style.configure("Treeview", background="#1f2937", foreground="#e5e7eb",
                        fieldbackground="#1f2937", rowheight=27, borderwidth=0)
        style.configure("Treeview.Heading", background="#374151", foreground="#f9fafb",
                        font=("Segoe UI Semibold", 9), padding=7)
        style.map("Treeview", background=[("selected", "#2563eb")])

    def _build(self):
        outer = ttk.Frame(self.root, padding=18)
        outer.pack(fill="both", expand=True)

        header = ttk.Frame(outer)
        header.pack(fill="x", pady=(0, 14))
        ttk.Label(header, text="Prestige Companion", style="Title.TLabel").pack(side="left")
        ttk.Label(header, text="LOCAL MINECRAFT PRICE DASHBOARD",
                  foreground="#60a5fa").pack(side="right", anchor="s")

        stats = ttk.Frame(outer)
        stats.pack(fill="x", pady=(0, 12))
        self.stat_prices = self._stat_card(stats, "Prices collected", "0")
        self.stat_pages = self._stat_card(stats, "Pages received", "0")
        self.stat_rebirths = self._stat_card(stats, "Rebirths found", "0")
        self.stat_missing = self._stat_card(stats, "Missing prices", "—")

        connection = ttk.Frame(outer, style="Panel.TFrame", padding=10)
        connection.pack(fill="x", pady=(0, 12))
        ttk.Label(connection, text="Connection", style="Muted.TLabel").pack(side="left")
        ttk.Label(connection, text="Minecraft mod → 127.0.0.1:8765",
                  background="#1f2937", foreground="#93c5fd").pack(side="left", padx=12)
        self.connection_label = ttk.Label(connection, text="● Listening",
                                          background="#1f2937", foreground="#86efac")
        self.connection_label.pack(side="right")

        controls = ttk.Frame(outer)
        controls.pack(fill="x", pady=(0, 10))
        ttk.Label(controls, text="Rebirth:").pack(side="left")
        self.rebirth_filter = ttk.Combobox(controls, textvariable=self.filter_var,
                                           values=["All"], width=9, state="readonly")
        self.rebirth_filter.pack(side="left", padx=(6, 14))
        self.rebirth_filter.bind("<<ComboboxSelected>>", lambda _e: self.refresh_table())
        ttk.Label(controls, text="Search:").pack(side="left")
        search = ttk.Entry(controls, textvariable=self.search_var, width=22)
        search.pack(side="left", padx=6)
        search.bind("<KeyRelease>", lambda _e: self.refresh_table())
        ttk.Button(controls, text="Import JSON", command=self.import_json).pack(side="right", padx=4)
        ttk.Button(controls, text="Export JSON", command=self.export_json).pack(side="right", padx=4)
        ttk.Button(controls, text="Refresh", command=self.refresh).pack(side="right", padx=4)

        table_frame = ttk.Frame(outer, style="Panel.TFrame", padding=8)
        table_frame.pack(fill="both", expand=True)
        columns = ("rebirth", "prestige", "cost", "cumulative")
        self.table = ttk.Treeview(table_frame, columns=columns, show="headings")
        for col, title, width in [
            ("rebirth", "Rebirth", 100), ("prestige", "Prestige", 110),
            ("cost", "Individual cost", 190), ("cumulative", "Cumulative spent", 210)
        ]:
            self.table.heading(col, text=title)
            self.table.column(col, width=width, anchor="center")
        scroll = ttk.Scrollbar(table_frame, orient="vertical", command=self.table.yview)
        self.table.configure(yscrollcommand=scroll.set)
        self.table.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")

        calc = ttk.Frame(outer, style="Panel.TFrame", padding=12)
        calc.pack(fill="x", pady=(12, 8))
        ttk.Label(calc, text="Cost calculator", background="#1f2937",
                  foreground="#f9fafb", font=("Segoe UI Semibold", 11)).grid(row=0, column=0, columnspan=7, sticky="w", pady=(0, 8))
        fields = [("Rebirth", self.rebirth_var), ("Starting prestige", self.start_var),
                  ("Target prestige", self.target_var)]
        for i, (label, variable) in enumerate(fields):
            ttk.Label(calc, text=label, style="Muted.TLabel").grid(row=1, column=i*2, sticky="w")
            ttk.Entry(calc, textvariable=variable, width=12).grid(row=1, column=i*2+1, padx=(6, 16), sticky="w")
        ttk.Button(calc, text="Calculate", command=self.calculate).grid(row=1, column=6, sticky="e")
        ttk.Label(calc, textvariable=self.result_var, background="#1f2937",
                  foreground="#fcd34d").grid(row=2, column=0, columnspan=7, sticky="w", pady=(10, 0))

        footer = ttk.Frame(outer)
        footer.pack(fill="x")
        ttk.Label(footer, textvariable=self.status_var, foreground="#9ca3af").pack(side="left")
        ttk.Label(footer, text="Data stored locally in prestige_companion.db",
                  foreground="#6b7280").pack(side="right")

    def _stat_card(self, parent, title, value):
        card = ttk.Frame(parent, style="Panel.TFrame", padding=12)
        card.pack(side="left", fill="x", expand=True, padx=(0, 8))
        ttk.Label(card, text=title, style="Muted.TLabel").pack(anchor="w")
        label = ttk.Label(card, text=value, style="Stat.TLabel")
        label.pack(anchor="w", pady=(4, 0))
        return label

    def _start_server(self):
        try:
            self.server = ThreadingHTTPServer((HOST, PORT), ApiHandler)
            self.server.daemon_threads = True
            self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
            self.server_thread.start()
            self.status_var.set(f"Listening for Minecraft on {HOST}:{PORT}")
        except OSError as exc:
            self.connection_label.configure(text="● Port unavailable", foreground="#fca5a5")
            self.status_var.set(f"Could not start local server: {exc}")

    def _process_queue(self):
        changed = False
        while True:
            try:
                payload = PAGE_QUEUE.get_nowait()
            except queue.Empty:
                break
            self._store_page(payload)
            changed = True
        if changed:
            self.refresh()
        self.root.after(150, self._process_queue)

    def _store_page(self, payload):
        prices = payload.get("prices", [])
        with self.db:
            for item in prices:
                try:
                    rb = int(item["rebirth"])
                    prestige = int(item["prestige"])
                    cost = str(item["individualCost"]).strip()
                    cumulative = item.get("cumulativeSpent")
                    if rb < 0 or prestige < 0 or not cost:
                        continue
                    self.db.execute("""
                        INSERT INTO prices(rebirth, prestige, individual_cost, cumulative_spent)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(rebirth, prestige) DO UPDATE SET
                            individual_cost=excluded.individual_cost,
                            cumulative_spent=excluded.cumulative_spent,
                            updated_at=CURRENT_TIMESTAMP
                    """, (rb, prestige, cost, cumulative))
                except (KeyError, TypeError, ValueError):
                    continue
            self.db.execute(
                "INSERT INTO scan_pages(page_number, scanned_items, missing_costs) VALUES (?, ?, ?)",
                (payload.get("page", 0), payload.get("scannedItems", 0), payload.get("missingCosts", 0))
            )
        self.status_var.set(
            f"Received page {payload.get('page', '?')}: {len(prices)} price record(s); "
            f"{payload.get('missingCosts', 0)} unreadable cost(s)."
        )

    def refresh(self):
        total = self.db.execute("SELECT COUNT(*) FROM prices").fetchone()[0]
        pages = self.db.execute("SELECT COUNT(*) FROM scan_pages").fetchone()[0]
        rebirths = self.db.execute("SELECT COUNT(DISTINCT rebirth) FROM prices").fetchone()[0]
        missing = self._missing_total()
        self.stat_prices.configure(text=f"{total:,}")
        self.stat_pages.configure(text=f"{pages:,}")
        self.stat_rebirths.configure(text=str(rebirths))
        self.stat_missing.configure(text=f"{missing:,}" if missing is not None else "—")
        found = [str(row[0]) for row in self.db.execute("SELECT DISTINCT rebirth FROM prices ORDER BY rebirth")]
        values = ["All"] + found
        self.rebirth_filter.configure(values=values)
        if self.filter_var.get() not in values:
            self.filter_var.set("All")
        self.refresh_table()

    def _missing_total(self):
        rebirths = [r[0] for r in self.db.execute("SELECT DISTINCT rebirth FROM prices")]
        if not rebirths:
            return 0
        missing = 0
        for rb in rebirths:
            maximum = expected_maximum(rb)
            found = self.db.execute(
                "SELECT COUNT(*) FROM prices WHERE rebirth=? AND prestige BETWEEN 2 AND ?",
                (rb, maximum)
            ).fetchone()[0]
            missing += max(0, maximum - 1 - found)
        return missing

    def refresh_table(self):
        for item in self.table.get_children():
            self.table.delete(item)
        selected = self.filter_var.get()
        term = self.search_var.get().strip().lower()
        if selected == "All":
            rows = self.db.execute("""
                SELECT rebirth, prestige, individual_cost, COALESCE(cumulative_spent, '')
                FROM prices ORDER BY rebirth, prestige
            """).fetchall()
        else:
            rows = self.db.execute("""
                SELECT rebirth, prestige, individual_cost, COALESCE(cumulative_spent, '')
                FROM prices WHERE rebirth=? ORDER BY prestige
            """, (int(selected),)).fetchall()
        for row in rows:
            if term and term not in f"rb{row[0]} p{row[1]} {row[2]} {row[3]}".lower():
                continue
            self.table.insert("", "end", values=row)

    def calculate(self):
        try:
            rb = int(self.rebirth_var.get())
            start = int(self.start_var.get())
            target = int(self.target_var.get())
            if rb < 0 or start < 0 or target < start:
                raise ValueError
        except ValueError:
            self.result_var.set("Enter valid values; target must be at least the starting prestige.")
            return
        rows = self.db.execute("""
            SELECT prestige, individual_cost FROM prices
            WHERE rebirth=? AND prestige>? AND prestige<=?
        """, (rb, start, target)).fetchall()
        values = {prestige: parse_cost(cost) for prestige, cost in rows}
        missing = [level for level in range(start + 1, target + 1)
                   if level not in values or values[level] is None]
        if missing:
            preview = ", ".join(map(str, missing[:8]))
            suffix = "..." if len(missing) > 8 else ""
            self.result_var.set(f"Incomplete: {len(missing)} missing price(s): {preview}{suffix}")
            return
        total = sum((values[level] for level in range(start + 1, target + 1)), Decimal(0))
        self.result_var.set(f"Total cost: {format_cost(total)}  |  {target-start} prices verified")

    def import_json(self):
        path = filedialog.askopenfilename(
            title="Import prestige_prices.json",
            filetypes=[("JSON files", "*.json"), ("All files", "*.*")]
        )
        if not path:
            return
        try:
            data = json.loads(Path(path).read_text(encoding="utf-8"))
            prices = data.get("prices", [])
            self._store_page({"page": 0, "prices": prices, "scannedItems": 0, "missingCosts": 0})
            self.status_var.set(f"Imported {len(prices):,} records from {Path(path).name}")
            self.refresh()
        except Exception as exc:
            messagebox.showerror("Import failed", str(exc))

    def export_json(self):
        path = filedialog.asksaveasfilename(
            title="Export prices",
            defaultextension=".json",
            initialfile="prestige_prices.json",
            filetypes=[("JSON files", "*.json")]
        )
        if not path:
            return
        rows = self.db.execute("""
            SELECT rebirth, prestige, individual_cost, cumulative_spent
            FROM prices ORDER BY rebirth, prestige
        """).fetchall()
        output = {
            "prices": [
                {"rebirth": rb, "prestige": prestige, "individualCost": cost,
                 **({"cumulativeSpent": cumulative} if cumulative else {})}
                for rb, prestige, cost, cumulative in rows
            ],
            "priceCount": len(rows),
            "pagesScanned": self.db.execute("SELECT COUNT(*) FROM scan_pages").fetchone()[0]
        }
        Path(path).write_text(json.dumps(output, indent=2), encoding="utf-8")
        self.status_var.set(f"Exported {len(rows):,} records to {Path(path).name}")

    def close(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()
        self.db.close()
        self.root.destroy()


def main():
    root = tk.Tk()
    CompanionApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()

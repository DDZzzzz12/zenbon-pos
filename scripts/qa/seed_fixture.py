#!/usr/bin/env python3
"""Seeds the catalogue's fresh fixture F (release-qa-catalogue.md section 0.3) into an isolated server.

    seed_fixture.py [--pair api|ui]
    seed_fixture.py --existing [--pair api|ui]   on an ALREADY initialised shop - a restored backup (L3b) or a
                                                 rig after `rig.sh upgrade` (L3a): only new terminals QA-TILL /
                                                 QA-STATION and a freshly commissioned virtual TSE, logged in
                                                 with QA_USER / QA_PASSWORD from $QA_DIR/creds.env (on a restore,
                                                 the operator writes those two lines)

Needs `source $QA_DIR/rig.env` (rig.sh up). Refuses to touch a server that is already initialised.

Creates, all through the API:
  owner A001 (ADMIN) + cashiers K001 K002 K003 + manager M001 (K003 alone in the rotation pool)
  categories Getränke (DRINKS, 19 %) / Speisen (FOOD, 7 %, also to go) and the seven fixture products
  tables T1-T6 (seated) and TA1/TA2 (takeaway); round-robin assignment on, cross-employee billing off
  cash book "Tresor"; document storage under the scratch home; printer "QA-Capture" when the rig runs
    a print listener (rig.sh up --print)
  terminals TER-001 (fiscal CASHIER) and TER-002 (fiscal ORDER_STATION), the virtual TSE commissioned
    (mode NORMAL) and both terminals registered as TSE clients

--pair api (default): pairs both terminals to synthetic device ids, for API-only passes.
--pair ui:            leaves them unpaired and writes fresh pairing codes to $QA_DIR/pairing.txt for
                      the emulators (codes expire - run `seed_fixture.py --codes` for new ones).

Writes (never prints):
  $QA_DIR/creds.env     mode 600: QA_USER/QA_PASSWORD (owner) and QA_<EMP>_USER/_PASSWORD/_PIN for K001..M001,
                        the test TSE PINs, device ids and the station's device token. `source` it.
  $QA_DIR/fixture.json  ids of everything created (products by name, tables by number, ...), no secrets
Credentials are generated test values for this scratch server only.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import sys

from qalib import Api, ApiError, load_env_file, qa_dir

OWNER = "qa-owner"
STAFF = [("k001", "CASHIER"), ("k002", "CASHIER"), ("k003", "CASHIER"), ("m001", "MANAGER")]
ROTATION_POOL = {"k003"}
CATEGORIES = [("Getränke", "DRINKS"), ("Speisen", "FOOD")]
PRODUCTS = [  # (number, name, price, category, productType)
    ("P001", "Espresso", "2.50", "Getränke", "DRINKS"),
    ("P002", "Cola", "3.50", "Getränke", "DRINKS"),
    ("P003", "Wein Flasche", "10.00", "Getränke", "DRINKS"),
    ("P004", "Suppe", "8.10", "Speisen", "FOOD"),
    ("P005", "Tagesmenü", "16.90", "Speisen", "FOOD"),
    ("P006", "Schnitzel", "12.50", "Speisen", "FOOD"),
    ("P007", "Grillteller", "20.00", "Speisen", "FOOD"),
]
TABLES = [(f"T{i}", 1) for i in range(1, 7)] + [("TA1", 3), ("TA2", 3)]
VAT_MAPPING = {
    "FOOD": {"default": "B", "toGo": "B"}, "DRINKS": {"default": "A"}, "RETAIL": {"default": "A"},
    "SERVICE": {"default": "A"}, "VOUCHER": {"default": "C"}, "OTHERS": {"default": "C"},
}
VIRTUAL_TSE_SEED = "SwissbitSwissbit"  # the virtual device's factory credential seed (VirtualWormDevice)
TILL_DEVICE, STATION_DEVICE = "qa-till-1", "qa-station-1"  # never pos-preview-* (= browser) or admin-web-*
EXISTING_DEVICES = ("qa-till-x", "qa-station-x")  # --existing: a device id stays bound to its first terminal


def pin(n: int) -> str:
    while True:
        p = "".join(secrets.choice("0123456789") for _ in range(n))
        if len(set(p)) > 2 and p not in ("12345", "23456", "123456"):
            return p


def password() -> str:
    return "Qa!" + secrets.token_urlsafe(12)


class Seeder:
    def __init__(self, pair_mode: str, existing: bool = False):
        self.dir = qa_dir()
        self.pair_mode = pair_mode
        self.existing = existing
        self.creds: dict[str, str] = {}
        self.fx: dict = {"products": {}, "categories": {}, "tables": {}, "employees": {}, "terminals": {},
                         "paymentTypes": {}, "cashBooks": {}}
        self.owner: Api | None = None

    def step(self, text: str) -> None:
        print(f"seed: {text}", flush=True)

    # --- 1. setup -------------------------------------------------------------------------------
    def initialize(self) -> None:
        code, status = Api().call("GET", "/api/setup/status", expect=(200,))
        if status.get("isInitialized"):
            sys.exit("seed: server is already initialised - refusing (seed only a fresh rig.sh server)")
        owner_pw = password()
        self.creds.update(QA_USER=OWNER, QA_PASSWORD=owner_pw, QA_A001_USER=OWNER, QA_A001_PASSWORD=owner_pw,
                          QA_A001_PIN=pin(4))
        body = {
            "owner": {"adminUsername": OWNER, "adminPassword": owner_pw, "adminEmail": "owner@example.test",
                      "adminFirstName": "QA", "adminLastName": "Owner", "adminPin": self.creds["QA_A001_PIN"]},
            "shop": {"shopName": "QA Testbetrieb", "shopAddress": "Teststraße 1", "shopCity": "Teststadt",
                     "zipCode": 10115, "shopPhone": "0300000000", "shopTaxNumber": "00/000/00000"},
        }
        Api().call("POST", "/api/setup/initialize", body, expect=(201,))
        self.owner = Api(OWNER, owner_pw, device="qa-admin").login()
        self.step("initialised; owner logged in")

    # --- 2. staff -------------------------------------------------------------------------------
    def staff(self) -> None:
        for username, role in STAFF:
            pw, p = password(), pin(4)
            body = {"username": username, "password": pw, "role": role, "email": f"{username}@example.test",
                    "pin": p, "notes": None, "languagePreference": "de", "employmentStatus": "ACTIVE",
                    "trainingMode": False, "personnelNumber": None}
            _, emp = self.owner.call("POST", "/api/employees", body, expect=(201,))
            number = emp["employeeNumber"]
            key = f"QA_{username.upper()}"
            self.creds.update({f"{key}_USER": username, f"{key}_PASSWORD": pw, f"{key}_PIN": p})
            self.owner.call("PATCH", f"/api/employees/{emp['id']}/rotation-pool",
                            {"inRotationPool": username in ROTATION_POOL}, expect=(200,))
            self.fx["employees"][username.upper()] = {"id": emp["id"], "employeeNumber": number, "role": role}
            if number.upper() != username.upper():
                print(f"seed: NOTE {username} got employee number {number} (catalogue text says {username.upper()})")
        self.step("staff " + ", ".join(f"{u}={e['employeeNumber']}" for u, e in self.fx["employees"].items()))

    # --- 3. catalogue, floor, rules ---------------------------------------------------------------
    def catalogue(self) -> None:
        self.owner.call("PUT", "/api/v1/config/1/vat-mapping", {"vatMapping": VAT_MAPPING}, expect=(200,))
        for i, (name, _) in enumerate(CATEGORIES):
            _, c = self.owner.call("POST", "/api/product-categories",
                                   {"name": name, "en": None, "ch": None, "description": None, "isActive": True,
                                    "sortOrder": float(i), "visibility": "EVERYWHERE"}, expect=(201,))
            self.fx["categories"][name] = c["id"]
        for number, name, price, cat, ptype in PRODUCTS:
            _, p = self.owner.call("POST", "/api/products",
                                   {"productNumber": number, "name": name, "en": None, "ch": None, "description": None,
                                    "price": price, "categoryId": self.fx["categories"][cat], "productType": ptype,
                                    "isActive": True, "allowCustomPrice": False, "imageUrl": None,
                                    "pfandTypeId": None}, expect=(201,))
            self.fx["products"][name] = {"id": p["id"], "price": price, "productType": ptype}
        for number, loc in TABLES:
            _, t = self.owner.call("POST", "/api/tables",
                                   {"tableNumber": number, "seats": 4, "locationTypeId": loc, "positionX": None,
                                    "positionY": None, "rotation": None, "width": None, "height": None,
                                    "assignmentPriority": 0}, expect=(201,))
            self.fx["tables"][number] = t["id"]
        self.owner.call("PUT", "/api/v1/config/1/order-rules",
                        {"employeeSelectionOnFreeTable": False, "roundRobinAssignment": True}, expect=(200,))
        self.owner.call("PUT", "/api/v1/config/1/billing-rules", {"allowCrossEmployeeBilling": False}, expect=(200,))
        _, pts = self.owner.call("GET", "/api/payment-types?includeInactive=false", expect=(200,))
        for pt in pts["paymentTypes"]:
            self.fx["paymentTypes"][pt["name"]] = {"id": pt["id"], "isCash": pt["isCash"],
                                                    "createsReceivable": pt["createsReceivable"]}
        missing = {"BAR", "EC-KARTE"} - set(self.fx["paymentTypes"])
        if missing:
            raise ApiError(f"payment types missing after setup: {missing}")
        self.step(f"{len(PRODUCTS)} products, {len(TABLES)} tables, payment types {sorted(self.fx['paymentTypes'])}")

    def books_storage_printer(self) -> None:
        _, books = self.owner.call("GET", "/api/v1/kassenbuch/books", expect=(200,))
        for b in books if isinstance(books, list) else books.get("books", []):
            self.fx["cashBooks"][b["name"]] = {"id": b["id"], "isFiscal": b.get("isFiscal")}
        _, t = self.owner.call("POST", "/api/v1/kassenbuch/books",
                               {"name": "Tresor", "datevAccount": "1010", "closeInterval": "MANUAL",
                                "kost1": None, "kost2": None}, expect=(201,))
        self.fx["cashBooks"]["Tresor"] = {"id": t["id"], "isFiscal": False}
        docs = self.dir / f"home-{os.environ.get('QA_PASS', 'qa')}" / "qa-documents"
        docs.mkdir(parents=True, exist_ok=True)
        self.owner.call("PUT", "/api/v1/config/1/document-storage",
                        {"path": str(docs), "maxUploadSizeMb": 20, "additionalDocumentTypes": []}, expect=(200,))
        port = os.environ.get("QA_PRINT_PORT")
        if port:
            self.owner.call("POST", "/api/printer-location",
                            {"printerLocation": "QA-Capture", "printBill": True, "language": "GERMAN",
                             "beepMode": "DISABLED",
                             "printerSetup": {"printerType": "NETWORK", "printerName": "QA-Capture",
                                              "printerIp": "127.0.0.1", "printerPort": int(port),
                                              "defaultPrinter": True, "ippDocumentFormat": None},
                             "categoryIds": list(self.fx["categories"].values())}, expect=(201,))
            self.owner.call("PUT", "/api/v1/config/1/print-settings", {"enabled": True}, expect=(200,))
        self.step(f"cash books {sorted(self.fx['cashBooks'])}; documents -> scratch; "
                  f"printer {'QA-Capture -> 127.0.0.1:' + port if port else 'none (no --print)'}")

    # --- 4. terminals + virtual TSE ------------------------------------------------------------------
    def terminals_and_tse(self) -> None:
        names = (("QA-TILL", "CASHIER"), ("QA-STATION", "ORDER_STATION")) if self.existing else \
            (("TER-001", "CASHIER"), ("TER-002", "ORDER_STATION"))
        for name, role in names:
            _, t = self.owner.call("POST", "/api/v1/terminals", {"name": name, "role": role, "fiscal": True},
                                   expect=(201,))
            self.fx["terminals"][role] = {"id": t["id"], "terminalId": t["terminalId"]}
        till = self.fx["terminals"]["CASHIER"]
        self.creds.update(QA_TSE_ADMIN_PIN=pin(5), QA_TSE_PUK=pin(6), QA_TSE_TIME_PIN=pin(5))
        self.owner.call("PUT", "/api/tse/config", {"mode": "NORMAL", "timeAdminPin": self.creds["QA_TSE_TIME_PIN"]},
                        expect=(200,))
        self.owner.call("POST", "/api/tse/setup",
                        {"adminPin": self.creds["QA_TSE_ADMIN_PIN"], "adminPuk": self.creds["QA_TSE_PUK"],
                         "credentialSeed": VIRTUAL_TSE_SEED, "terminalId": till["terminalId"]}, expect=(200,))
        self.owner.call("POST", "/api/tse/admin/unlock", {"pass": self.creds["QA_TSE_ADMIN_PIN"]}, expect=(200,))
        try:
            for role, t in self.fx["terminals"].items():
                if not self.registered(t["id"]):
                    self.owner.call("POST", f"/api/v1/terminals/{t['id']}/tse-register", expect=(200,))
        finally:
            self.owner.call("POST", "/api/tse/admin/lock", expect=(200,))
        not_registered = [r for r, t in self.fx["terminals"].items() if not self.registered(t["id"])]
        if not_registered:
            raise ApiError(f"terminals not TSE-registered after setup: {not_registered}")
        _, health = self.owner.call("GET", "/api/tse/health", expect=(200,))
        self.step(f"TSE commissioned (initialized={health.get('initialized')}); "
                  f"{', '.join(t['terminalId'] for t in self.fx['terminals'].values())} registered")

    def registered(self, terminal_db_id: int) -> bool:
        _, terms = self.owner.call("GET", "/api/v1/terminals", expect=(200,))
        return any(t["id"] == terminal_db_id and t.get("tseClientRegistered") for t in terms)

    def pairing_codes(self) -> dict[str, str]:
        codes = {}
        for role, t in self.fx["terminals"].items():
            _, c = self.owner.call("POST", f"/api/v1/terminals/{t['id']}/pairing-code", {"registerTseClient": False},
                                   expect=(201,))
            codes[role] = c["code"]
        return codes

    def pair(self) -> None:
        codes = self.pairing_codes()
        if self.pair_mode == "ui":
            self.write_pairing(codes)
            return
        till_dev, station_dev = EXISTING_DEVICES if self.existing else (TILL_DEVICE, STATION_DEVICE)
        for role, device in (("CASHIER", till_dev), ("ORDER_STATION", station_dev)):
            _, r = Api(device=device).call("POST", "/api/v1/terminals/pair",
                                           {"code": codes[role], "deviceId": device, "model": "qa", "brand": "qa",
                                            "serialNr": None, "swVersion": None, "clientKind": "NATIVE"},
                                           expect=(200,))
            if role == "ORDER_STATION":
                self.creds["QA_STATION_TOKEN"] = r["deviceToken"]
        self.creds.update(QA_TILL_DEVICE=till_dev, QA_STATION_DEVICE=station_dev)
        t = self.fx["terminals"]
        self.step(f"paired {t['CASHIER']['terminalId']} -> {till_dev}, {t['ORDER_STATION']['terminalId']} -> {station_dev} (API)")

    def write_pairing(self, codes: dict[str, str]) -> None:
        path = self.dir / "pairing.txt"
        path.write_text("".join(f"{role} {self.fx['terminals'][role]['terminalId']} code={code}\n"
                                for role, code in codes.items()))
        self.step(f"pairing codes for the emulators written to {path} (server URL http://10.0.2.2:8080)")

    # --- output ---------------------------------------------------------------------------------------
    def login_existing(self) -> None:
        """An owner login already in creds.env: the seed's own, or a restored shop's from the OPERATOR."""
        existing = load_env_file(self.dir / "creds.env")
        if not existing.get("QA_USER") or not existing.get("QA_PASSWORD"):
            sys.exit("seed: --existing needs QA_USER / QA_PASSWORD in $QA_DIR/creds.env - on a restored shop "
                     "ask the operator to add them (chmod 600); never type them yourself")
        _, status = Api().call("GET", "/api/setup/status", expect=(200,))
        if not status.get("isInitialized"):
            sys.exit("seed: --existing but the server is not initialised - run the plain seed (or the restore) first")
        self.creds.update(existing)
        self.owner = Api(existing["QA_USER"], existing["QA_PASSWORD"], device="qa-admin").login()
        self.step("logged in with the existing owner login")
        fx = self.dir / "fixture.json"
        if fx.exists():
            self.fx.update({k: v for k, v in json.loads(fx.read_text()).items() if k != "terminals"})

    def save(self) -> None:
        creds = self.dir / "creds.env"
        creds.write_text("# generated by seed_fixture.py - test values for this scratch server only\n"
                         + "".join(f"export {k}='{v}'\n" for k, v in sorted(self.creds.items())))
        os.chmod(creds, 0o600)
        (self.dir / "fixture.json").write_text(json.dumps(self.fx, indent=2, ensure_ascii=False))
        print(f"seed: DONE - wrote {creds} (600, not printed) and {self.dir / 'fixture.json'}")
        print("seed: next: source $QA_DIR/rig.env (it loads creds.env too)")

    def run(self) -> None:
        try:
            if self.existing:
                self.login_existing()
            else:
                self.initialize()
                self.staff()
                self.catalogue()
                self.books_storage_printer()
            self.terminals_and_tse()
            self.pair()
        finally:
            if self.creds:  # keep the logins even when a later step fails, or the server is unusable
                self.save()


def new_codes() -> None:
    d = qa_dir()
    env = load_env_file(d / "creds.env")
    fx = json.loads((d / "fixture.json").read_text())
    s = Seeder("ui")
    s.fx = fx
    s.owner = Api(env["QA_USER"], env["QA_PASSWORD"]).login()
    s.write_pairing(s.pairing_codes())


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--pair", choices=["api", "ui"], default="api")
    p.add_argument("--codes", action="store_true", help="only mint new pairing codes for an existing fixture")
    p.add_argument("--existing", action="store_true",
                   help="terminals + TSE only, on an already initialised shop (restored or upgraded)")
    args = p.parse_args()
    try:
        new_codes() if args.codes else Seeder(args.pair, args.existing).run()
    except ApiError as exc:
        sys.exit(f"seed: FAILED - {exc}")


if __name__ == "__main__":
    main()

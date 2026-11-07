#!/usr/bin/env python3
"""Seeds realistic back-office data for an adminApp browser pass, on top of seed_fixture.py's fixture F.

    seed_adminapp_data.py [--vouchers 55] [--documents 55] [--only step,step]

Needs a rig seeded with `seed_fixture.py --pair api` and `source $QA_DIR/rig.env` (QA_DIR, QA_BASE,
QA_TILL_DEVICE, creds). Run it ONCE per fixture; use --only to repeat single steps (a full second run adds duplicates).

Everything goes through the product's own API, never SQL. What it creates (all test values):
  employees      four extra staff, one with a long German name part
  terminals      a third terminal with a long name (the fixture has TER-001 / TER-002)
  vouchers       N counter-sale vouchers on the paired till (N > 50 so the paged list shows "50+"), a few
                 redeemed against bills, one partly redeemed
  bills / Z-Bon  ~9 bills over T1-T6 / TA1-TA2 (BAR, EC-KARTE, voucher legs, tip), then one Z-Bon
  documents      N small unique PDFs (EIGENBELEG / INVOICE / CONTRACTS), one XRechnung-style UBL XML
  suppliers      six, one with a long German legal name; debtors six; contracts four (monthly/weekly/yearly)
  incoming       invoices captured on INVOICE documents (POST /api/documents/{id}/invoice), one overdue
  outgoing       sales invoices: draft, issued, issued-overdue, cancelled, long buyer name
  Kassenbuch     expense and deposit entries, each with its own unique EIGENBELEG document

Writes $QA_DIR/adminapp-seed.json (counts + ids). Prints one `seed:` line per step; a step that the API
refuses is reported `seed: WARN ...` and skipped so one rejected shape never loses the rest.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import urllib.error
import urllib.request
import uuid
from decimal import Decimal

from qalib import BASE, Api, ApiError, money, qa_dir

TODAY = dt.date.today()
LONG_DE = "Donaudampfschifffahrtsgesellschaftskapitän-Bedarfsgroßhandels GmbH & Co. KG"


def iso(days: int) -> str:
    return (TODAY + dt.timedelta(days=days)).isoformat()


def env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise SystemExit(f"seed: {name} is not set - source $QA_DIR/rig.env (seed_fixture.py --pair api first)")
    return value


def tiny_pdf(text: str) -> bytes:
    """A valid one-page PDF whose bytes are unique per `text` (identical bytes are refused 409)."""
    stream = f"BT /F1 14 Tf 40 760 Td ({text}) Tj ET"
    objs = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
        "/Resources << /Font << /F1 5 0 R >> >> >>",
        f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out, offsets = b"%PDF-1.4\n", []
    for i, body in enumerate(objs, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{body}\nendobj\n".encode("latin-1")
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return out


def ubl_invoice(number: str, gross: str) -> bytes:
    net = (Decimal(gross) / Decimal("1.19")).quantize(Decimal("0.01"))
    vat = Decimal(gross) - net
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
 xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
 xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
 <cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0</cbc:CustomizationID>
 <cbc:ProfileID>urn:fdc:peppol.eu:2017:poacc:billing:01:1.0</cbc:ProfileID>
 <cbc:ID>{number}</cbc:ID><cbc:IssueDate>{iso(-12)}</cbc:IssueDate><cbc:DueDate>{iso(18)}</cbc:DueDate>
 <cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode><cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
 <cbc:BuyerReference>04011000-12345-34</cbc:BuyerReference>
 <cac:AccountingSupplierParty><cac:Party>
  <cbc:EndpointID schemeID="EM">lieferant@example.test</cbc:EndpointID>
  <cac:PartyName><cbc:Name>XML Lieferant GmbH</cbc:Name></cac:PartyName>
  <cac:PostalAddress><cbc:StreetName>Lieferweg 5</cbc:StreetName><cbc:CityName>Hamburg</cbc:CityName>
   <cbc:PostalZone>20095</cbc:PostalZone><cac:Country><cbc:IdentificationCode>DE</cbc:IdentificationCode></cac:Country></cac:PostalAddress>
  <cac:PartyTaxScheme><cbc:CompanyID>DE123456789</cbc:CompanyID><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:PartyTaxScheme>
  <cac:PartyLegalEntity><cbc:RegistrationName>XML Lieferant GmbH</cbc:RegistrationName></cac:PartyLegalEntity>
  <cac:Contact><cbc:Name>Frau Muster</cbc:Name><cbc:Telephone>040123456</cbc:Telephone><cbc:ElectronicMail>info@example.test</cbc:ElectronicMail></cac:Contact>
 </cac:Party></cac:AccountingSupplierParty>
 <cac:AccountingCustomerParty><cac:Party>
  <cbc:EndpointID schemeID="EM">kunde@example.test</cbc:EndpointID>
  <cac:PostalAddress><cbc:StreetName>Teststraße 1</cbc:StreetName><cbc:CityName>Teststadt</cbc:CityName>
   <cbc:PostalZone>10115</cbc:PostalZone><cac:Country><cbc:IdentificationCode>DE</cbc:IdentificationCode></cac:Country></cac:PostalAddress>
  <cac:PartyLegalEntity><cbc:RegistrationName>QA Testbetrieb</cbc:RegistrationName></cac:PartyLegalEntity>
 </cac:Party></cac:AccountingCustomerParty>
 <cac:PaymentMeans><cbc:PaymentMeansCode>58</cbc:PaymentMeansCode>
  <cac:PayeeFinancialAccount><cbc:ID>DE02120300000000202051</cbc:ID></cac:PayeeFinancialAccount></cac:PaymentMeans>
 <cac:TaxTotal><cbc:TaxAmount currencyID="EUR">{vat}</cbc:TaxAmount>
  <cac:TaxSubtotal><cbc:TaxableAmount currencyID="EUR">{net}</cbc:TaxableAmount><cbc:TaxAmount currencyID="EUR">{vat}</cbc:TaxAmount>
   <cac:TaxCategory><cbc:ID>S</cbc:ID><cbc:Percent>19</cbc:Percent><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:TaxCategory></cac:TaxSubtotal></cac:TaxTotal>
 <cac:LegalMonetaryTotal><cbc:LineExtensionAmount currencyID="EUR">{net}</cbc:LineExtensionAmount>
  <cbc:TaxExclusiveAmount currencyID="EUR">{net}</cbc:TaxExclusiveAmount><cbc:TaxInclusiveAmount currencyID="EUR">{gross}</cbc:TaxInclusiveAmount>
  <cbc:PayableAmount currencyID="EUR">{gross}</cbc:PayableAmount></cac:LegalMonetaryTotal>
 <cac:InvoiceLine><cbc:ID>1</cbc:ID><cbc:InvoicedQuantity unitCode="C62">1</cbc:InvoicedQuantity>
  <cbc:LineExtensionAmount currencyID="EUR">{net}</cbc:LineExtensionAmount>
  <cac:Item><cbc:Name>Bürobedarf</cbc:Name><cac:ClassifiedTaxCategory><cbc:ID>S</cbc:ID><cbc:Percent>19</cbc:Percent>
   <cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:ClassifiedTaxCategory></cac:Item>
  <cac:Price><cbc:PriceAmount currencyID="EUR">{net}</cbc:PriceAmount></cac:Price></cac:InvoiceLine>
</Invoice>
""".encode()


def upload(api: Api, name: str, data: bytes, mime: str, file_type: str, **fields: str) -> dict | None:
    """POST /api/documents/upload as multipart; returns the response JSON or None on refusal."""
    boundary = uuid.uuid4().hex
    parts = [("fileType", file_type), *fields.items()]
    body = b"".join(
        f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in parts)
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
             f"Content-Type: {mime}\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(BASE + "/api/documents/upload", data=body, method="POST", headers={
        "Content-Type": f"multipart/form-data; boundary={boundary}", "Authorization": "Bearer " + api.token,
        "X-Device-ID": api.device})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as exc:
        print(f"seed: WARN upload {name} -> HTTP {exc.code}: {exc.read()[:200]!r}")
        return None


class Seeder:
    def __init__(self, vouchers: int, documents: int):
        self.dir = qa_dir()
        self.fx = json.loads((self.dir / "fixture.json").read_text())
        self.n_vouchers, self.n_documents = vouchers, documents
        till = env("QA_TILL_DEVICE")
        self.mgr = Api(env("QA_M001_USER"), env("QA_M001_PASSWORD"), device=till).login()
        self.cashier = Api(env("QA_K001_USER"), env("QA_K001_PASSWORD"), device=till).login()
        self.owner = Api(env("QA_USER"), env("QA_PASSWORD"), device="qa-admin").login()
        # re-read: the GUTSCHEIN tender only exists once the voucher feature has been switched on
        _, pts = self.owner.call("GET", "/api/payment-types?includeInactive=false", expect=(200,))
        self.pay = {p["name"]: p["id"] for p in pts["paymentTypes"]}
        self.out: dict = {}
        self.vouchers: list[dict] = []

    def say(self, text: str) -> None:
        print(f"seed: {text}", flush=True)

    def tolerate(self, label: str, fn):
        try:
            return fn()
        except ApiError as exc:
            self.say(f"WARN {label}: {exc}")
            return None

    # --- staff, terminal ---------------------------------------------------------------------------
    def staff_and_terminal(self) -> None:
        names = [("anna.mueller", "CASHIER"), ("bernd.schulz-hoffmann", "CASHIER"), ("chiara.weber", "MANAGER"),
                 ("dieter.grossmann-lindenberg", "CASHIER")]
        made = 0
        for username, role in names:
            body = {"username": username, "password": "Qa!" + uuid.uuid4().hex[:12], "role": role,
                    "email": f"{username}@example.test", "pin": "4826", "notes": None, "languagePreference": "de",
                    "employmentStatus": "ACTIVE", "trainingMode": False, "personnelNumber": None}
            made += bool(self.tolerate(f"employee {username}",
                                       lambda b=body: self.owner.call("POST", "/api/employees", b, expect=(201,))))
        self.tolerate("terminal", lambda: self.owner.call(
            "POST", "/api/v1/terminals",
            {"name": "Außenterrasse Selbstbedienungskasse 3", "role": "CASHIER", "fiscal": False}, expect=(201,)))
        self.say(f"{made} extra employees, third terminal")

    # --- vouchers, bills, Z-Bon --------------------------------------------------------------------
    def vouchers_issue(self) -> None:
        self.owner.call("PUT", "/api/v1/config/1/voucher-config", {"voucherConfig": {"enabled": True, "sources": [
            {"type": "INTERNAL", "name": "Interne Gutscheine", "enabled": True,
             "description": "Vouchers created and sold through the POS system"}]}}, expect=(200, 204))
        for i in range(self.n_vouchers):
            value = f"{(10, 20, 25, 50, 15)[i % 5]}.00"
            _, v = self.cashier.call("POST", "/api/vouchers", {"sessionId": None, "originalValue": value,
                                                               "paymentTypeId": self.pay["BAR"], "tipAmount": None},
                                     expect=(200, 201))
            self.vouchers.append(v)
        self.say(f"{len(self.vouchers)} vouchers issued ({self.vouchers[0]['voucherNumber']} .. {self.vouchers[-1]['voucherNumber']})")

    def item(self, name: str, take_away: bool = False) -> dict:
        p = self.fx["products"][name]
        return {"productId": p["id"], "unitPrice": p["price"], "quantity": 1, "discount": 1.0, "customPrice": False,
                "takeAway": take_away, "sideNotes": None, "extras": None, "selections": None}

    def sell(self, table: str, items: list[str], payment: str, tip: str = "0.00", voucher: tuple[str, str] | None = None):
        self.cashier.call("POST", "/api/orders", {"tableInfoId": 0, "tableId": self.fx["tables"][table],
                                                   "forEmployeeNumber": None,
                                                   "orderItems": [self.item(n) for n in items]}, expect=(201,))
        _, t = self.mgr.call("GET", f"/api/tables/{self.fx['tables'][table]}", expect=(200,))
        owner_number = t.get("currentEmployeeNumber")
        owner = next((k for k, e in self.fx["employees"].items() if e["employeeNumber"] == owner_number), None)
        api = Api(env(f"QA_{owner}_USER"), env(f"QA_{owner}_PASSWORD"), device=env("QA_TILL_DEVICE")).login()
        sid = t["currentSessionId"]
        _, rows = api.call("GET", f"/api/items/session/{sid}?status=OPEN", expect=(200,))
        ids = [i for r in rows for i in r["orderItemIds"]]
        total = sum((money(r["finalPrice"]) for r in rows), Decimal("0.00"))
        pay_amount = total + money(tip)
        vouchers = []
        if voucher:
            number, amount = voucher
            vouchers = [{"voucherNumber": number, "redeemedAmount": amount, "voucherSource": "INTERNAL"}]
            pay_amount -= money(amount)
        payments = [{"amount": str(pay_amount), "paymentTypeId": self.pay[payment]}] if pay_amount > 0 else []
        if voucher:  # one voucher payment leg per redemption (BillServiceImpl matches them by position)
            payments.append({"amount": voucher[1], "paymentTypeId": self.pay["GUTSCHEIN"]})
        body = {"tableSessionId": sid, "orderItems": ids, "billAmount": str(total), "tips": tip,
                "payments": payments, "voucherRedeem": vouchers, "promotions": []}
        return api.call("POST", "/api/v1/bills", body, expect=(201,))[1]

    def bills_and_zbon(self) -> None:
        v = [x["voucherNumber"] for x in self.vouchers]
        plan = [("T1", ["Espresso", "Suppe"], "BAR", "0.00", None),
                ("T2", ["Cola", "Schnitzel"], "EC-KARTE", "1.00", None),
                ("T3", ["Tagesmenü", "Wein Flasche"], "BAR", "0.00", None),
                ("T4", ["Grillteller", "Cola"], "BAR", "2.00", None),
                ("T5", ["Wein Flasche", "Wein Flasche"], "EC-KARTE", "0.00", None),
                ("T6", ["Schnitzel", "Suppe", "Espresso"], "BAR", "0.00", None)]
        if v:  # vouchers 0..2 are fully redeemed (10/20/25), voucher 3 partly (50 -> 20 redeemed)
            plan += [("T1", ["Schnitzel"], "BAR", "0.00", (v[0], "10.00")),
                     ("T2", ["Grillteller"], "BAR", "0.00", (v[1], "20.00")),
                     ("T3", ["Tagesmenü", "Espresso"], "BAR", "0.00", (v[2], "19.40")),
                     ("T4", ["Wein Flasche", "Suppe"], "EC-KARTE", "0.00", (v[3], "15.00"))]
        done = 0
        for table, items, payment, tip, voucher in plan:
            done += bool(self.tolerate(f"bill {table}", lambda a=(table, items, payment, tip, voucher): self.sell(*a)))
        _, exp = self.mgr.call("GET", "/api/v1/kassenbuch/cash-count/expected", expect=(200,))
        z = self.tolerate("z-bon", lambda: self.mgr.call(
            "POST", "/api/z-bon", {"countedAmount": str(money(exp["expectedCash"]))}, expect=(201,))[1])
        self.say(f"{done}/{len(plan)} bills; Z-Bon #{z.get('zbonNummer') if z else 'FAILED'}")
        # a few bills AFTER the Z-Bon so Tagesbericht ("since last Z-Bon") also has rows
        for table, items, payment, tip, voucher in plan[:3]:
            self.tolerate(f"bill {table}", lambda a=(table, items, payment, "0.00", None): self.sell(*a))

    # --- master data -------------------------------------------------------------------------------
    def master_data(self) -> None:
        suppliers = [("Metro Großhandel", "Essen"), ("Bäckerei Schmidt", "Leipzig"), ("Getränke Hoffmann", "Köln"),
                     ("Stadtwerke Teststadt", "Teststadt"), ("Reinigungsservice Blitzblank", "Berlin"),
                     (LONG_DE, "Bremerhaven")]
        sup_ids = []
        for i, (name, city) in enumerate(suppliers):
            r = self.tolerate(f"supplier {name[:20]}", lambda n=name, c=city, i=i: self.mgr.call(
                "POST", "/api/suppliers", {"name": n[:50], "legalName": n, "city": c, "zip": "10115", "country": "DE",
                                           "street": f"Lieferstraße {i + 1}", "email": f"lieferant{i}@example.test",
                                           "paymentTermsDays": 14 + i}, expect=(201,)))
            if r:
                sup_ids.append(r[1]["id"])
        debtors = [("Hotel Vier Jahreszeiten", "Hamburg"), ("Catering Feinkost Müller", "München"),
                   ("Stadt Teststadt Kulturamt", "Teststadt"), ("Rechtsanwälte Meier & Partner", "Berlin"),
                   ("Fußballverein 1899 Teststadt e. V.", "Teststadt"), (LONG_DE, "Bremerhaven")]
        self.deb_ids = []
        for i, (name, city) in enumerate(debtors):
            r = self.tolerate(f"debtor {name[:20]}", lambda n=name, c=city, i=i: self.mgr.call(
                "POST", "/api/debtors", {"name": n[:50], "legalName": n, "city": c, "zip": "20095", "country": "DE",
                                         "street": f"Kundenweg {i + 1}", "email": f"kunde{i}@example.test",
                                         "paymentTermsDays": 14}, expect=(201,)))
            if r:
                self.deb_ids.append(r[1]["id"])
        contracts = [("Strom Gastronomie", "MONTHLY", 5, None, "480.00"), ("Pachtvertrag Ladenlokal", "MONTHLY", 1, None, "2400.00"),
                     ("Wöchentliche Frischelieferung", "WEEKLY", 3, 2, "260.00"),
                     ("Versicherung Betriebshaftpflicht", "ANNUAL", 15, None, "1320.00")]
        made = 0
        for i, (name, interval, due, wd, amount) in enumerate(contracts):
            body = {"name": name, "interval": interval, "dueDay": due, "dueWeekday": wd, "startDate": iso(-200),
                    "expectedAmount": amount, "expectsInvoice": i % 2 == 0,
                    "supplierId": sup_ids[i] if i < len(sup_ids) else None}
            made += bool(self.tolerate(f"contract {name}", lambda b=body: self.mgr.call("POST", "/api/contracts", b, expect=(201,))))
        self.sup_ids = sup_ids
        self.say(f"{len(sup_ids)} suppliers, {len(self.deb_ids)} debtors, {made} contracts")

    # --- documents, incoming invoices ------------------------------------------------------------------
    def documents(self) -> None:
        kinds = ["EIGENBELEG", "INVOICE", "CONTRACTS"]
        ok = 0
        for i in range(self.n_documents):
            kind = kinds[i % 3]
            r = upload(self.mgr, f"beleg-{i + 1:03d}.pdf", tiny_pdf(f"QA Beleg {i + 1} {uuid.uuid4().hex}"),
                       "application/pdf", kind, description=f"QA Beleg {i + 1}")
            ok += bool(r)
        xml = upload(self.mgr, "xrechnung-0001.xml", ubl_invoice("XR-2026-0001", "238.00"), "application/xml", "INVOICE")
        self.say(f"{ok}/{self.n_documents} PDFs uploaded; e-invoice XML {'uploaded' if xml else 'REFUSED'} "
                 f"(processingStatus {(xml or {}).get('processingStatus')})")

    def incoming_invoices(self) -> None:
        names = ["Metro Großhandel", "Bäckerei Schmidt", "Getränke Hoffmann", "Stadtwerke Teststadt", LONG_DE[:50]]
        made = 0
        for i, name in enumerate(names):
            doc = upload(self.mgr, f"eingang-{i + 1}.pdf", tiny_pdf(f"Eingangsrechnung {i + 1} {uuid.uuid4().hex}"),
                         "application/pdf", "INVOICE", vendorName=name)
            if not doc:
                continue
            doc_id = doc.get("id") or doc.get("documentId") or (doc.get("document") or {}).get("id")
            gross = (Decimal("119.00") * (i + 1)).quantize(Decimal("0.01"))
            net = (gross / Decimal("1.19")).quantize(Decimal("0.01"))
            due = iso(-20) if i == 1 else iso(14 + i)  # one overdue
            body = {"invoiceNumber": f"ER-2026-{100 + i}", "issueDate": iso(-30 + i), "dueDate": due,
                    "sellerName": name, "sellerCity": "Teststadt", "sellerCountry": "DE", "taxExclusiveTotal": str(net),
                    "lineNetTotal": str(net), "taxTotal": str(gross - net), "taxInclusiveTotal": str(gross),
                    "amountDue": str(gross),
                    "vatBreakdown": [{"vatCategoryCode": "S", "vatRate": "19.00", "taxableAmount": str(net),
                                      "vatAmount": str(gross - net)}]}
            made += bool(self.tolerate(f"incoming invoice {i}", lambda d=doc_id, b=body: self.mgr.call(
                "POST", f"/api/documents/{d}/invoice", b, expect=(200, 201))))
        self.say(f"{made}/{len(names)} incoming invoices captured")

    # --- outgoing invoices ---------------------------------------------------------------------------
    def sales_invoices(self) -> None:
        def standalone(buyer: str, debtor: int | None, service_days: int, due_days: int | None, price: str, qty: str = "2"):
            body = {"debtorId": debtor, "buyerName": buyer, "buyerCity": "Teststadt", "buyerCountry": "DE",
                    "serviceDate": iso(service_days) + "T00:00:00",
                    "dueDate": iso(due_days) + "T00:00:00" if due_days is not None else None,
                    "lines": [{"itemName": "Catering Buffet", "quantity": qty, "grossUnitPrice": price, "vatRate": "7.00"},
                              {"itemName": "Getränkepauschale", "quantity": "1", "grossUnitPrice": "45.00", "vatRate": "19.00"}]}
            r = self.tolerate(f"sales invoice {buyer[:15]}", lambda: self.mgr.call(
                "POST", "/api/v1/sales-invoices/standalone", body, expect=(201,)))
            return r[1] if r else None

        d = self.deb_ids
        specs = [("Hotel Vier Jahreszeiten", d[0] if d else None, -40, -26, "89.00"),   # issued, overdue
                 ("Catering Feinkost Müller", d[1] if len(d) > 1 else None, -10, 4, "120.00"),  # issued, open
                 ("Stadt Teststadt Kulturamt", d[2] if len(d) > 2 else None, -3, 11, "310.00"),  # issued, open
                 ("Rechtsanwälte Meier & Partner", d[3] if len(d) > 3 else None, -5, 9, "75.50"),  # to cancel
                 (LONG_DE, d[5] if len(d) > 5 else None, -2, 12, "205.00"),  # long name, issued
                 ("Fußballverein 1899 Teststadt e. V.", d[4] if len(d) > 4 else None, 0, None, "66.00")]  # draft
        made = []
        for i, (buyer, debtor, svc, due, price) in enumerate(specs):
            inv = standalone(buyer, debtor, svc, due, price)
            if not inv:
                continue
            if i < 5:
                self.tolerate(f"issue {inv['id']}", lambda i=inv: self.mgr.call("POST", f"/api/v1/sales-invoices/{i['id']}/issue", expect=(200,)))
            if i == 3:
                self.tolerate(f"cancel {inv['id']}", lambda i=inv: self.mgr.call(
                    "POST", f"/api/v1/sales-invoices/{i['id']}/cancel", {"reason": "QA Stornierung"}, expect=(200, 201)))
            made.append(inv["id"])
        self.say(f"{len(made)} sales invoices (issued, overdue, cancelled, long name, draft)")

    # --- Kassenbuch ----------------------------------------------------------------------------------
    def kassenbuch(self) -> None:
        entries = [("CASH_DEPOSIT", "100.00", "Wechselgeld Einlage"), ("CASH_EXPENSE", "23.50", "Büromaterial"),
                   ("CASH_EXPENSE", "48.90", "Reinigungsmittel Großpackung"), ("CASH_EXPENSE", "12.00", "Porto"),
                   ("CASH_DEPOSIT", "50.00", "Trinkgeld-Pool Einlage"), ("CASH_EXPENSE", "9.99", "Blumen Tischdeko")]
        made = 0
        for i, (kind, amount, text) in enumerate(entries):
            doc = upload(self.mgr, f"kasse-{i + 1}.pdf", tiny_pdf(f"Kassenbeleg {i + 1} {uuid.uuid4().hex}"),
                         "application/pdf", "EIGENBELEG", description=text)
            if not doc:
                continue
            doc_id = doc.get("id") or doc.get("documentId") or (doc.get("document") or {}).get("id")
            body = {"entryType": kind, "lines": [{"kind": "STANDARD", "vatRate": "19.00", "amount": amount, "belegtext": text}],
                    "waehrung": "EUR", "belegtext": text, "documentId": doc_id}
            made += bool(self.tolerate(f"kassenbuch {text}", lambda b=body: self.mgr.call("POST", "/api/v1/kassenbuch", b, expect=(200, 201))))
        self.say(f"{made}/{len(entries)} Kassenbuch entries (each with its own document)")

    def existing_vouchers(self) -> None:
        _, rows = self.mgr.call("GET", "/api/vouchers/active?limit=200", expect=(200,))
        rows = rows if isinstance(rows, list) else rows.get("vouchers", rows.get("items", []))
        self.vouchers = sorted(rows, key=lambda r: r["voucherNumber"])

    def existing_debtors(self) -> list[int]:
        _, rows = self.mgr.call("GET", "/api/debtors", expect=(200,))
        rows = rows if isinstance(rows, list) else next((v for v in rows.values() if isinstance(v, list)), [])
        return [r["id"] for r in rows]

    def run(self, only: set[str]) -> None:
        def want(step: str) -> bool:
            return not only or step in only
        if want("staff"):
            self.staff_and_terminal()
        if want("vouchers"):
            self.vouchers_issue()
        elif want("bills"):
            self.existing_vouchers()
        if want("bills"):
            self.bills_and_zbon()
        if want("master"):
            self.master_data()
        else:
            self.sup_ids, self.deb_ids = [], self.existing_debtors()
        if want("documents"):
            self.documents()
        if want("incoming"):
            self.incoming_invoices()
        if want("sales"):
            self.sales_invoices()
        if want("kassenbuch"):
            self.kassenbuch()
        (self.dir / "adminapp-seed.json").write_text(json.dumps(
            {"vouchers": len(self.vouchers), "documents": self.n_documents, "date": TODAY.isoformat()}, indent=2))
        self.say("DONE")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--vouchers", type=int, default=55)
    p.add_argument("--documents", type=int, default=55)
    p.add_argument("--only", default="", help="comma list of steps for a re-run: staff,vouchers,bills,master,"
                                              "documents,incoming,sales,kassenbuch (default: all)")
    args = p.parse_args()
    try:
        Seeder(args.vouchers, args.documents).run({s for s in args.only.split(",") if s})
    except ApiError as exc:
        raise SystemExit(f"seed: FAILED - {exc}")


if __name__ == "__main__":
    main()

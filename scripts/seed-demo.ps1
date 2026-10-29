<#
 PayOps demo data loader. Uses ONLY the existing REST APIs (backend must be running on :8080).
 Creates: 2 switches, 3 merchants, 10 payments (+events by the API), 12 balanced journals with postings,
          2 settlement files with records, 2 reconciliation runs with results.
 Usage (PowerShell, project root):   .\scripts\seed-demo.ps1
 To start over: re-apply sql\schema.sql (drops and recreates tables), restart the backend, run again.
#>
param([string]$Base = "http://localhost:8080")
$ErrorActionPreference = "Stop"

function Call($method, $path, $body) {
    try {
        if ($null -ne $body) {
            $json = ConvertTo-Json -InputObject $body -Depth 10
            return Invoke-RestMethod -Method $method -Uri ($Base + $path) -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($json))
        }
        return Invoke-RestMethod -Method $method -Uri ($Base + $path)
    } catch {
        $msg = $_.ErrorDetails.Message
        Write-Host "FAILED $method $path" -ForegroundColor Red
        if ($msg) { Write-Host $msg -ForegroundColor Red } else { Write-Host $_.Exception.Message -ForegroundColor Red }
        throw
    }
}

if ((Call GET "/api/merchants" $null).Count -gt 0) {
    Write-Host "Merchants already exist. Re-apply sql\schema.sql and restart the backend for a clean demo, then rerun." -ForegroundColor Yellow
    exit 1
}

# ---- Accounts (seeded by schema.sql) -> lookup by code
$acc = @{}
foreach ($a in (Call GET "/api/accounts" $null)) { $acc[$a.code] = $a.id }
foreach ($c in "1100","1200","2100","5100") { if (-not $acc.ContainsKey($c)) { throw "Account $c missing. Apply sql\schema.sql first." } }

# ---- Switches
Write-Host "Creating switches..."
$sw1 = Call POST "/api/switches" @{ code="HDFC-ACQ"; name="HDFC Acquirer Gateway"; switchType="ACQUIRER"; protocol="ISO8583"; host="acq.hdfc.example"; port=8583; status="ACTIVE"; priority=1; timeoutMs=30000 }
$sw2 = Call POST "/api/switches" @{ code="NPCI-UPI"; name="NPCI UPI Network"; switchType="NETWORK"; protocol="REST"; host="upi.npci.example"; port=443; status="ACTIVE"; priority=2; timeoutMs=20000 }

# ---- Merchants (default switch drives routing)
Write-Host "Creating merchants..."
$m1 = Call POST "/api/merchants" @{ code="MRC-001"; name="Reliance Retail Ltd"; email="payments@reliance-retail.example"; currency="INR"; status="ACTIVE"; defaultSwitch=@{id=$sw1.id} }
$m2 = Call POST "/api/merchants" @{ code="MRC-002"; name="Urban Cafe Pvt Ltd"; email="accounts@urbancafe.example"; currency="INR"; status="ACTIVE"; defaultSwitch=@{id=$sw2.id} }
$m3 = Call POST "/api/merchants" @{ code="MRC-003"; name="QuickCart Online"; email="finance@quickcart.example"; currency="INR"; status="ACTIVE"; defaultSwitch=@{id=$sw1.id} }

# ---- Payments (API forces INITIATED and writes the first event)
Write-Host "Creating payments..."
$spec = @(
 @("PAY-1001",$m1,12500,"CARD"), @("PAY-1002",$m1,24900,"NETBANKING"), @("PAY-1003",$m2,850,"UPI"),
 @("PAY-1004",$m2,3200,"UPI"),   @("PAY-1005",$m3,18750,"CARD"),       @("PAY-1006",$m3,6800,"WALLET"),
 @("PAY-1007",$m1,5600,"CARD"),  @("PAY-1008",$m2,500,"UPI"),          @("PAY-1009",$m3,15000,"NETBANKING"),
 @("PAY-1010",$m1,4750,"CARD"))
$pay = @{}
foreach ($s in $spec) {
    $p = Call POST "/api/payments" @{ reference=$s[0]; merchant=@{id=$s[1].id}; amount=[decimal]$s[2]; currency="INR"; method=$s[3]; customerRef=("CUST-" + $s[0].Substring(4)) }
    $pay[$s[0]] = $p
}

# ---- Journals: one balanced entry per payment (receivable vs merchant payable)
Write-Host "Creating journal entries and postings..."
$n = 1
foreach ($s in $spec) {
    $p = $pay[$s[0]]
    $je = Call POST "/api/journal-entries" @{ entryNumber=("JE-{0:0000}" -f $n); source="PAYMENT_CAPTURE"; description=("Receivable and merchant payable for " + $s[0]); payment=@{id=$p.id} }
    Call POST "/api/journal-entries/$($je.id)/postings" @(
        @{ account=@{id=$acc["1100"]}; direction="DEBIT";  amount=[decimal]$s[2]; description="Acquirer receivable" },
        @{ account=@{id=$acc["2100"]}; direction="CREDIT"; amount=[decimal]$s[2]; description="Merchant payable" }) | Out-Null
    $n++
}

# ---- Settlement files + records
Write-Host "Creating settlement files and records..."
$f1 = Call POST "/api/settlement-files" @{ fileName="HDFC_SETTLE_20261023.csv"; switchDatabase=@{id=$sw1.id}; settlementDate="2026-10-23" }
$f2 = Call POST "/api/settlement-files" @{ fileName="NPCI_UPI_SETTLE_20261023.csv"; switchDatabase=@{id=$sw2.id}; settlementDate="2026-10-23" }

function Rec($ref, $amount, $time) {
    $fee = [math]::Round([decimal]$amount * 0.015, 2)
    $r = @{ paymentReference=$ref; amount=[decimal]$amount; fee=[decimal]$fee; currency="INR"; transactionDate=$time }
    if ($pay.ContainsKey($ref)) { $r.payment = @{ id = $pay[$ref].id } }
    return $r
}
# File 1 (HDFC): 5 exact matches, 1 amount mismatch (PAY-1006: 6300 vs 6800), 1 unknown reference; PAY-1010 absent
$recs1 = @(
  (Rec "PAY-1001" 12500 "2026-10-23T09:12:00"), (Rec "PAY-1002" 24900 "2026-10-23T09:48:00"),
  (Rec "PAY-1005" 18750 "2026-10-23T11:05:00"), (Rec "PAY-1006" 6300  "2026-10-23T12:30:00"),
  (Rec "PAY-1007" 5600  "2026-10-23T14:02:00"), (Rec "PAY-1009" 15000 "2026-10-23T15:40:00"),
  (Rec "PAY-9999" 2200  "2026-10-23T16:10:00"))
# File 2 (UPI): all 3 match
$recs2 = @((Rec "PAY-1003" 850 "2026-10-23T10:01:00"), (Rec "PAY-1004" 3200 "2026-10-23T10:44:00"), (Rec "PAY-1008" 500 "2026-10-23T13:15:00"))
$sr1 = Call POST "/api/settlement-files/$($f1.id)/records" $recs1
$sr2 = Call POST "/api/settlement-files/$($f2.id)/records" $recs2

# ---- Settlement journals: bank cash + fee expense vs acquirer receivable (matched amounts only)
function SettleJournal($num, $desc, $recs, $matchRefs) {
    $gross = [decimal]0; $fee = [decimal]0
    foreach ($r in $recs) { if ($matchRefs -contains $r.paymentReference) { $gross += $r.amount; $fee += $r.fee } }
    $je = Call POST "/api/journal-entries" @{ entryNumber=$num; source="SETTLEMENT"; description=$desc }
    Call POST "/api/journal-entries/$($je.id)/postings" @(
        @{ account=@{id=$acc["1200"]}; direction="DEBIT";  amount=($gross-$fee); description="Net cash received from acquirer" },
        @{ account=@{id=$acc["5100"]}; direction="DEBIT";  amount=$fee;          description="Settlement fee" },
        @{ account=@{id=$acc["1100"]}; direction="CREDIT"; amount=$gross;        description="Clear acquirer receivable" }) | Out-Null
}
SettleJournal "JE-0011" "Settlement posting HDFC_SETTLE_20261023 (matched records)" $recs1 @("PAY-1001","PAY-1002","PAY-1005","PAY-1007","PAY-1009")
SettleJournal "JE-0012" "Settlement posting NPCI_UPI_SETTLE_20261023" $recs2 @("PAY-1003","PAY-1004","PAY-1008")

# ---- Reconciliation: the client pairs records with payments by reference; the API classifies each pair
Write-Host "Running reconciliation..."
function Reconcile($runNo, $file, $savedRecs, $extraPayments) {
    $run = Call POST "/api/reconciliation-runs" @{ runNumber=$runNo; settlementFile=@{id=$file.id} }
    $results = @()
    foreach ($sr in $savedRecs) {
        $r = @{ settlementRecord=@{id=$sr.id}; note="Matched by payment reference " + $sr.paymentReference }
        if ($pay.ContainsKey($sr.paymentReference)) { $r.payment = @{id=$pay[$sr.paymentReference].id} }
        else { $r.note = "Reference not found in PayOps payments" }
        $results += $r
    }
    foreach ($ref in $extraPayments) { $results += @{ payment=@{id=$pay[$ref].id}; note="Payment not present in settlement file" } }
    Call POST "/api/reconciliation-runs/$($run.id)/results" $results | Out-Null
}
Reconcile "RUN-20261024-001" $f1 $sr1 @("PAY-1010")
Reconcile "RUN-20261024-002" $f2 $sr2 @()

Write-Host ""
Write-Host "Demo data loaded. Open http://localhost:4200" -ForegroundColor Green
Write-Host "Expected: 10 payments, volume Rs 92,850; run 1 = 5 matched / 3 exceptions; run 2 = 3 matched / 0 exceptions."

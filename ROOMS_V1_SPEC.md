# SafeRoom Rooms v1 — Merkezi Olmayan Teknik Spec (Single Page)

## 0) Amaç
Rooms UI’sını gerçek bir altyapıya bağlamak:
*   **Voice:** Discord UX (join/leave, otomatik bağlanma) + WebRTC mesh audio.
*   **Text + History + Offline:** Merkezi olmayan dinamik ağaç overlay üzerinde DataChannel routing + replication.
*   **File Sharing:** BitTorrent-benzeri chunk/piece paylaşımı; metadata ağaçta, parçalar peer’lerde.

## 1) Bileşenler

### 1.1 Client Node (Peer)
Her peer aynı yazılım; runtime’da rol alır:
*   **Leaf:** yalnız kendi trafiğini üretir/tüketir.
*   **Router:** ağaç üzerinde paket forward eder (DataChannel üzerinden).
*   **Store:** history/file metadata replikası tutar (genelde router’lar store olur).

### 1.2 Signaling Server (minimum merkezi)
Kullanıcı datayı taşımaz; sadece koordinasyon yapar.
*   **Görevleri:**
    *   Room membership + presence: “kim odada?”, “kim voice’da?”.
    *   WebRTC offer/answer + ICE candidate relay: peer’leri birbirine tanıştırır.
    *   Overlay bootstrap: odaya yeni girene seed router listesi + son topology epoch + kısa “health snapshot” verir.
    *   Rejoin hızlandırma: reconnect sonrası aynı subnet/önceki parent’a dönmeyi önerir.
    *   (Opsiyonel) STUN/TURN config dağıtımı: NAT zorlayıcıysa.
*   **Asla:** mesaj içeriği, dosya içeriği, history taşımak / saklamak.

## 2) Oda Kimliği ve Kripto

### 2.1 Kimlik
*   Her peer: `nodeId = Hash(pubKey)` (kalıcı).
*   Room: `roomId` (invite link veya hash).

### 2.2 Anahtarlar
*   `roomKey` (symmetric) = room içi mesaj/history/file metadata şifreleme anahtarı.
*   **roomKey dağıtımı:** v1’de pratik yaklaşım:
    *   Oda sahibi (admin) roomKey’i katılımcının pubKey’i ile şifreleyip gönderir (DataChannel veya signaling üzerinden “key envelope”).
*   Voice (WebRTC) zaten SRTP ile şifreli; ek E2EE audio istiyorsan ayrı.

## 3) Topoloji: “Voice = Mesh”, “Data/History/File = Dynamic Tree Overlay”

### 3.1 Voice Mesh
*   Voice’a join: signaling üzerinden odadaki voice üyelerine peer connection kur.
*   Her bağlantıda: audio track ekle.
*   **Scale hedefi (v1):** 2–8 kişi. (8+ için kalite düşer; v1 bunu kabul eder.)

### 3.2 Dynamic Tree Overlay (DataChannel routing)
*   **Amaç:** Mesaj/history/file metadata’yı mesh yerine “overlay tree” ile ölçeklemek.
*   Her peer tek parent seçer, `k` adet child alabilir.
*   Router’lar tree omurgasını oluşturur; leaf’ler uçta.
*   **Topoloji hedefleri:**
    *   Minimum yeniden bağlanma, düşük gecikme, churn toleransı.
    *   Oda içinde tek aktif ağaç (epoch ile versiyonlanan).

## 4) Dynamic Tree Algoritması (Uygulanabilir ve Net)

### 4.1 Ölçümler (her peer toplar)
*   `rttToX`: DataChannel ping RTT (keepalive).
*   `uplinkBudget`: tahmini upload kapasitesi (basit ölçüm: 1s window throughput).
*   `cpuLoad`: opsiyonel.
*   `degree`: mevcut child sayısı.

### 4.2 Parent Seçimi (Join anında)
1.  Yeni peer, signaling’den `seedRouters[]` alır (örn. 5–20 node).
2.  Her seed’e DataChannel “hello” kur (çok maliyetliyse 3 taneyle başla).
3.  Skorla:
    *   `score = w1*RTT + w2*(degree/capacity) + w3*loss`
4.  En iyi skorlu router’a `PARENT_REQUEST` gönder.
5.  Router kabul ederse `PARENT_ACK` ve assigned replication duties döner.
6.  Red olursa 2. en iyiye git.
    *   Kapasite: Router maxChildren ile sınırlı. Doluysa reddeder ve alternatif önerir.

### 4.3 Router Seçimi / Yükseltme (Runtime)
Bir leaf, şu koşullarda router’a “terfi” eder:
*   Bandwidth + CPU uygun,
*   Parent’ın çocuk yükünü azaltmak için gerekli,
*   Network churn yüksek.

**Terfi protokolü:** Parent “promote proposal” gönderir → node router rolünü açar → signaling’e “router” olarak presence bildirir.

### 4.4 Sürekli Rebalance
*   Her node 5–10 saniyede bir “health heartbeat”:
    *   Parent’a `HEARTBEAT{rtt, degree, capacity}`.
*   Parent aşırı yüklüyse bazı child’lara `REASSIGN{candidateParents[]}` verir.
*   Child en iyi adaya geçer, eski parent’la bağlantıyı kapatır.

## 5) DataChannel Üzerinden Routing: Mesaj Tipleri ve Akış

### 5.1 Kanal Ayrımı
*   `dc_control` (reliable, ordered): topology + acks + membership + metadata.
*   `dc_data` (reliable, unordered opsiyon): chat payload / small blobs.
*   `dc_file` (unordered + partial reliability opsiyon): chunk transfer (yüksek throughput).

### 5.2 Paket Formatı (tüm ağaç üzerinden forward edilebilir)
**Envelope:**
*   `roomId`
*   `type` (CHAT, META, FILE_REQ, FILE_CHUNK, ACK, SYNC_REQ, SYNC_RESP…)
*   `srcNodeId`
*   `msgId` (128-bit random)
*   `epoch` (topology epoch)
*   `ttl` (forward limiti)
*   `hopCount`
*   `payload` (şifreli: AES-GCM(roomKey))

### 5.3 Forwarding Kuralı (router davranışı)
Router bir Envelope alınca:
1.  `dedupCache` kontrol (msgId seen?) → seen ise drop.
2.  `ttl--`, `ttl==0` drop.
3.  `type`’a göre:
    *   **Broadcast tipleri (CHAT, META):** parent+children’a forward (geldiği link hariç).
    *   **Unicast tipleri (ACK, SYNC_RESP, FILE_CHUNK):** hedef route bilgisine göre forward.
4.  ACK mekanizması: hop-by-hop ack + end-to-end ack (v1’de hop-by-hop yeterli).

**Not:** Ağaç olduğu için broadcast çok ucuz: her edge bir kere.

## 6) Offline Sync + History Replication

### 6.1 Event Log Modeli
*   Oda için lineer “event log”:
    *   `eventId = (logicalClock, nodeId)` veya LamportClock.
*   Her event: CHAT_MESSAGE, FILE_META, EDIT, DELETE vs.
*   Router/Store node’lar event’leri append-only tutar.

### 6.2 Replication Kuralı (minimum K replika)
*   Her event K=3 farklı store node’da tutulmalı.
*   Event üretildiğinde:
    1.  Leaf → parent router’a gönderir.
    2.  Parent event’i log’a yazar ve `replicaTargets[]` seçer.
    3.  `REPL_STORE(event)` ile 2 ek store node’a push eder.
    4.  3 store’dan `STORE_ACK` gelince event “committed” sayılır.

### 6.3 Offline Rejoin Sync
*   Bir kullanıcı geri geldiğinde:
    1.  Signaling: odanın “current epoch” + “nearest store candidates” döner.
    2.  Peer bir store node’a `SYNC_REQ{lastSeenEventId}` yollar.
    3.  Store: `SYNC_RESP{events after lastSeen}` döner (paginated).
    4.  Peer local DB’ye uygular.

### 6.4 Conflict / Ordering
*   Mesajlaşma: “append-only” + lamport ordering → UI sıralar.
*   Edit/delete v1’de opsiyonel (varsa event-sourcing ile).

## 7) Failover ve Self-Healing

### 7.1 Parent Failover (en kritik)
*   Child her T=2s ping atar; 3 ping kaçarsa parent down.
*   Child hemen signaling’den seedRouters ister (veya cached list).
*   `PARENT_REQUEST` ile yeni parent seçer.
*   Rejoin sonrası: `SYNC_REQ` ile kayıp event’leri çeker.
*   Eski parent geri gelirse epoch mismatch → otomatik yeniden konumlandırılır.

### 7.2 Store Failover (replica kaybı)
*   Store down olursa:
    *   Parent/router replikasyon hedeflerini yeniden hesaplar:
    *   Eksilen replika sayısı kadar yeni store’a `REPL_STORE` gönderir.
    *   K replika sürekli korunur.

### 7.3 Network Partition
*   Partition A ve B ayrı event üretirse: v1’de “eventual merge”:
    *   Her partition kendi lamport clock’u ile append eder.
    *   Partition birleşince store’lar MERGE_SYNC yapar.
    *   UI’da ordering lamport ile deterministik.

## 8) File Sharing (BitTorrent-like)

### 8.1 Metadata (ağaç + log)
*   Dosya: `fileId = SHA256(file)`
*   `FileMeta{fileId, name, size, pieceSize, pieceHashes[]}`
*   FileMeta event olarak log’a yazılır (replicated).

### 8.2 Parça Dağıtımı (peer-to-peer)
*   Her peer “hangi parçalara sahibim” bitmap yayınlar (HAVE).
*   **İndiren:**
    *   rare-first ile parça seçer (v1 basit: random-first).
    *   `PIECE_REQ{fileId, pieceIndex}` ile birden fazla peer’e paralel istek.
*   **Gönderen:**
    *   `PIECE_CHUNK` paketleriyle stream eder.
*   **Doğrulama:**
    *   parça tamamlanınca hash kontrol; yanlışsa başka peer’den yeniden.

### 8.3 Seed / Persistence
*   “Dosyayı paylaşan” ilk peer seed olur.
*   Store node’lar dosyanın kendisini saklamak zorunda değil, sadece metadata.
*   Oda içinde en az S seed (örn. 2) hedeflenir; seed sayısı düşerse UI uyarır.

## 9) Kabul Kriterleri (v1)
*   **Voice mesh:** 3 kişi voice join → herkes herkesle audio bağlantı kurar, yeni gelen otomatik bağlanır.
*   **Tree broadcast:** bir chat mesajı, ağaç üzerinden tüm node’lara tek kopya edge ile yayılır (dedup ile).
*   **Offline sync:** A user offline → 20 mesaj atılsın → geri gelince store’dan SYNC_REQ ile hepsini çeksin.
*   **Failover:** parent kill → child 6 saniye içinde yeni parent bulup room’a geri dönsün; kayıp event’leri sync etsin.
*   **File pieces:** 50MB dosya → piece’lara bölünsün, iki peer’den paralel indirilebilsin, hash doğrulansın.

## 10) Notlar (v1 sınırları)
*   Voice mesh 8+ için verimsiz; v1 bunu kabul eder.
*   Signaling minimal ama “presence + bootstrap” için şart.
*   Router/store seçimi heuristik; v1’de “iyi çalışan” basit skor yeterli.

---

# EK: Mesaj Şeması ve Detaylar (Wire Format)

## 1) Mesaj Şeması (Wire Format)

### 1.1 Ortak Zarf: Envelope
Her şey DataChannel’dan Envelope olarak geçer.

```json
{
  "v": 1,
  "roomId": "base58|hex",
  "epoch": 42,
  "type": "CTRL|CHAT|META|SYNC|FILE",
  "subtype": "string",
  "msgId": "128bit-random-hex",
  "src": "nodeId",
  "dst": "nodeId|null",
  "ttl": 12,
  "hop": 0,
  "ts": 1730000000,
  "flags": {
    "broadcast": true,
    "requiresAck": true,
    "store": true
  },
  "payload": "base64(AES-GCM(roomKey, innerJsonBytes))"
}
```

**Zorunlu kurallar:**
*   `msgId` global uniq (random 128-bit).
*   `epoch` topology versiyonu. Eski epoch ile gelen control paketleri ignore veya “epoch mismatch” ile cevap.
*   `ttl` başlangıç 12 (odanın max depth hedefin 6–8 ise yeter).
*   `dst=null` + `flags.broadcast=true` → tree broadcast.

### 1.2 İç Payload Tipleri (Encrypted Inner JSON)

#### 1) Control: Hello / Ping / Heartbeat
```json
// CTRL/HELLO
{
  "nodeId": "nodeId",
  "pubKey": "base64",
  "cap": {"maxChildren": 4, "store": true, "bwUpKbps": 5000, "cpu": 0.3},
  "client": {"ver": "0.1.0", "platform": "linux"},
  "lastSeenEventId": "lamport:nodeId|0"
}

// CTRL/PING
{"nonce":"u64"}

// CTRL/HEARTBEAT
{
  "rttMs": 23,
  "loss": 0.01,
  "children": 2,
  "maxChildren": 4,
  "bwUpKbps": 4200,
  "cpu": 0.41
}
```

#### 2) Tree Join / Parent Negotiation
```json
// CTRL/PARENT_REQUEST
{
  "candidateId": "nodeId",
  "wantStore": true,
  "myCap": {"bwUpKbps": 3000, "store": false},
  "measuredRttMs": 18
}

// CTRL/PARENT_ACK
{
  "accepted": true,
  "parentId": "nodeId",
  "assigned": {
    "role": "LEAF|ROUTER|STORE",
    "replicationK": 3
  },
  "siblingsHint": ["nodeId","nodeId"],
  "altParents": ["nodeId","nodeId"],
  "routeHint": {"pathToRoot": ["nodeId","nodeId"]}
}

// CTRL/PARENT_ACK (rejected)
{
  "accepted": false,
  "reason": "FULL|EPOCH_MISMATCH|POLICY",
  "altParents": ["nodeId","nodeId"]
}

// CTRL/CHILD_DROP (parent -> child)
{"reason":"REBALANCE|OVERLOAD","altParents":["nodeId"]}
```

#### 3) Chat Event (Append-only log)
```json
// CHAT/EVENT
{
  "event": {
    "eventId": {"lamport": 9012, "nodeId": "nodeId"},
    "kind": "CHAT_MESSAGE",
    "roomId": "roomId",
    "author": "nodeId",
    "createdAt": 1730000123,
    "body": {"text":"...","attachments":[]}
  }
}
```

#### 4) Replication + Commit
```json
// META/REPL_STORE (router -> store)
{"event": { /* same event */ }}

// META/STORE_ACK (store -> router)
{"eventId":{"lamport":9012,"nodeId":"nodeId"}}

// META/COMMIT (router -> broadcast)
{"eventId":{"lamport":9012,"nodeId":"nodeId"}, "committed": true}
```

#### 5) Offline Sync
```json
// SYNC/REQ
{
  "fromEventId": {"lamport": 8800, "nodeId":"x"},
  "limit": 200,
  "wantKinds": ["CHAT_MESSAGE","FILE_META"]
}

// SYNC/RESP
{
  "events": [ /* event list */ ],
  "hasMore": true,
  "nextFrom": {"lamport": 9000, "nodeId":"y"}
}
```

#### 6) File Meta + BitTorrent-like Pieces
```json
// FILE/META (as event body kind=FILE_META)
{
  "fileId": "sha256-hex",
  "name": "a.zip",
  "size": 52428800,
  "pieceSize": 262144,
  "pieceHashes": ["sha256hex", "..."],
  "mime": "application/zip"
}

// FILE/HAVE (periodic)
{"fileId":"...","bitset":"base64bitset"}

// FILE/PIECE_REQ
{"fileId":"...","pieceIndex": 7, "offset": 0, "length": 65536}

// FILE/PIECE_CHUNK
{
  "fileId":"...",
  "pieceIndex":7,
  "offset":0,
  "data":"base64(bytes)",
  "final":false
}
```

## 2) State Machine (Implement Edilebilir)

Her Room için iki paralel “alt makina” var: `VoiceFSM` ve `DataFSM`.

### 2.1 DataFSM

**Durumlar:** `IDLE` → `SIGNALING_JOINING` → `TREE_CONNECTING` → `TREE_READY` → `SYNCING` → `READY` → `DEGRADED` → `LEAVING`

**Geçişler (özet):**
*   IDLE -> SIGNALING_JOINING: user room’a girer.
*   SIGNALING_JOINING -> TREE_CONNECTING: `seedRouters` listesi alınır.
*   TREE_CONNECTING -> TREE_READY: parent seçildi + dc_control up.
*   TREE_READY -> SYNCING: store seçildi, SYNC/REQ atıldı.
*   SYNCING -> READY: event backlog bitti.
*   READY -> DEGRADED: parent timeout / epoch mismatch / dc down.
*   DEGRADED -> TREE_CONNECTING: yeni parent dene.

**Minimum event handlers:**
*   On `CTRL/PARENT_ACK` accepted: parent set + children list init.
*   On `CTRL/CHILD_DROP`: immed. re-parent.
*   On `SYNC/RESP` hasMore: paginate.
*   On `EPOCH_MISMATCH`: signaling’den yeni epoch+seeds al, rejoin.

### 2.2 VoiceFSM

`VOICE_IDLE` → `VOICE_JOINING` → `VOICE_MESH_CONNECTING` → `VOICE_READY` → `VOICE_DEGRADED`

**Kural:**
*   Voice join: odadaki voice üyeleriyle PC kur.
*   Yeni biri gelince: signaling “VOICE_MEMBER_JOINED” event gönderir → PC kur.

## 3) Dedup / ACK / Retry Parametreleri

### 3.1 Dedup Cache
*   `dedupCacheSize`: 50.000 msgId
*   `dedupTTL`: 120 saniye
*   Uygulama: LRU(msgId -> ts)

### 3.2 ACK Katmanı
**A) Hop-by-hop ACK (Router güvenliği):**
*   Timeout: 800ms, Retry: 2.
*   `// CTRL/ACK {"ackMsgId":"...","ok":true}`

**B) End-to-end “commit” (History tutarlılığı):**
*   CHAT event üretildiğinde `K=3` STORE_ACK toplanınca `META/COMMIT` broadcast edilir.

### 3.4 Keepalive / Timeout
*   PING interval: 2s
*   dead after: 3 consecutive miss (≈6s)

## 4) DataChannel Link Management (Kritik)

### 4.1 Peer Connection Sayısı
*   **Tree:** 1 Parent, Max Child (4), 2 Warm Standby = ~7 PC.
*   **Voice:** Mesh (ideali tek PC çok kanal ama v1 için basit tutulabilir).

### 4.2 Kanal Konfigürasyonu
*   `dc_control`: reliable, ordered=true
*   `dc_data`: reliable, ordered=false
*   `dc_file`: ordered=false, partial reliability opsiyonel

### 4.7 Failover Adımları
1.  **Parent öldü:** Child 6s'de anlar → Signaling'den seed al → `PARENT_REQUEST`.
2.  `TREE_READY` olunca `SYNC/REQ`.

## 5) Signaling API (Minimum)
*   `ROOM_PRESENCE`, `VOICE_PRESENCE`, `EPOCH` (Events)
*   `JOIN_ROOM`, `LEAVE_ROOM`, `GET_SEEDS`, `RELAY_SDP/ICE` (Requests)

---
**Uygulama Planı:** DataFSM → Tree Broadcast → Store Replication → Failover → File Sharing sırasıyla ilerle.


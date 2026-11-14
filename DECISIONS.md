# fakelag v2: ผลการตรวจและการตัดสินใจ

เอกสารนี้บันทึกว่าไฟล์ไหนถูกตรวจ อะไรถูกแก้ อะไรถูกตัดสินใจไม่แก้ และเหตุผล

## ขอบเขตการตรวจ
- อ่านเต็มหรืออ่านส่วนสำคัญ: `filter/GhostFilter`, `filter/TeleportFilter` (ส่วน replay, budget, constant), `filter/AimRotationFilter` (สูตร steering), `filter/MarkFilter` (parse), `mod/AutoFreeze`, `FloatingOverlayService` (aim loop), `tunnel/UdpSessionManager` (observeForceSync), `PacketVpnService` (การเรียก VIP), `tunnel/VpnEngine` (route), `auth/DeviceIdentity`, `decode/frame/HeaderLayout`, `decode/frame/Crc7`, `tunnel/PacketBuilder`, `AndroidManifest` (main/debug/release), `app/build.gradle`, `proguard-rules.pro` (ส่วนต้น)
- scan ด้วย grep ทั้ง codebase: logging, sleep/delay, ค่าคงที่ period, ค่าที่ใช้ซ้ำ, service/permission ที่ประกาศ
- ไม่ได้อ่านทีละบรรทัด: ESP (`esp/*`, `ui/render/*`), UI panels, `decode/body/*` (parser), `diag/*`, `config/*`, `net/Http`, `net/Crypto`, `tunnel/TcpProxy`, `protocol/*` ที่ไม่เกี่ยวกับ replay/ACK. ส่วนเหล่านี้ทำงานในเครื่องหรือเป็น parser ไม่ได้ส่ง packet ไป server ตามที่เห็นจากโค้ด
- native: `libnvmux.so`, `libnuvola_native.so` ดูเฉพาะ string ไม่ได้ disassemble

## ทำแล้ว
1. **Ghost / Teleport replay**: gap คงที่ 500 ms เปลี่ยนเป็น 500 ms + สุ่มเพิ่มได้ถึง 30% (500–650 ms) สุ่มใหม่ทุกครั้ง ใช้ `Humanize.upMs` ให้ 500 ms เป็นพื้น เพราะเป็นค่าเดิมที่ผู้เขียนตั้งไว้ ยังไม่ได้ verify ว่า server ทนช่วงห่างสูงสุด 650 ms ได้หรือไม่
2. **AutoFreeze**: cooldown 250 ms เป็นพื้น สุ่มเพิ่มได้ถึง 40% (250–350 ms) สุ่มใหม่ทุกครั้งที่ส่ง toggle
3. **Aim shot gap**: 350 ms เป็นพื้น สุ่มเพิ่มได้ถึง 25% (350–438 ms) เดิมคอมเมนต์บอกว่าขั้นต่ำมีไว้ให้ swipe ก่อนหน้าสงบ จึงห้ามสั้นลง
4. **Aim loop tick**: 120 ms ±30% (84–156 ms) เดิมทำให้ shot ลงบน grid 120 ms เสมอ ตัว tick เป็น polling ไม่มี floor จึงใช้ jitter สองทาง
5. **Manifest**: ลบ `VipVpnService` ออกจาก manifest หลัก เพราะ `PacketVpnService.VIP_HYBRID = false` ไม่มีทางเปิด ยังเก็บ class ไว้ให้โค้ดที่อ้างอิงยัง compile

## ตัดสินใจไม่แก้ (พร้อมเหตุผล)
- **ACK starvation ใน GhostFilter (ปัญหาหลักที่ยังเหลือ)**: keepalive ที่ ghost DROP คือ ACK stream (Cmd 2) ซึ่งอยู่ใน body ที่เข้ารหัส TEA ทางแก้ที่ถูกต้องคือ re-seal ACK ให้สดระหว่าง replay ต้องรู้ semantics ของ Cmd 2 และ layout ของ body ซึ่งยังไม่ได้ verify จาก capture ทำแบบเดาอาจทำให้ disconnect หนักกว่าเดิม ทางเลือกอื่นคือจำกัดระยะ hold แล้วปล่อย keepalive จริงชั่วคราว ซึ่งจะรั่วตำแหน่งจริง จึงไม่เลือก
- **TeleportFilter.MAX_STEP_M**: เคยลดเป็น 2.40 แล้วคืนเป็น 2.70 เพราะ budget จริงถูก clamp ด้วย `budgetFor(state, pose)` อยู่แล้ว ค่านี้ส่งผลเฉพาะ slider clamp และ re-aim gate (บรรทัด ~611) ซึ่งยังไม่มีข้อมูลว่าการลดแล้วเปลี่ยนพฤติกรรมยังไง
- **Replay ส่ง keepalive กับ movement ต่อกันทันทีด้วย bytes เดิม**: อาจเป็น signature แต่ไม่มีหลักฐาน และการ stagger ต้องแก้ sender ที่ยังไม่ได้อ่าน
- **`UdpSessionManager:249`, `MarkFilter:246`**: ลูป 5 และ 3 เป็นลูป parse ไม่ใช่ลูปส่ง (false positive)
- **`PacketBuilder` TTL 64 / flags 0x4000**: ใช้กับ packet ที่เขียนกลับเข้า tun ฝั่งเครื่อง server ไม่เห็น IP header นี้
- **VPN ที่ครอบเกมด้วย `addAllowedApplication`**: ถ้าเกมเช็ค `TRANSPORT_VPN` ได้ จะซ่อนจาก VpnService ไม่ได้ ยังไม่รู้ว่าเกมเช็คหรือไม่ ต้องดู binary ของเกมซึ่งไม่อยู่ใน zip
- **applicationId `com.nepdon.wire` และ permission `INTERNAL_BROADCAST`**: เปลี่ยนแล้วกระทบ SSAID ที่ `DeviceIdentity` ใช้ผูก license ดังนั้นไม่แตะ
- **Touch injection ผ่าน uinput clone (`libnvmux`)**: ไม่พบ device name หลุดใน string ไม่แก้
- **Logging**: release ใช้ R8 `-assumenosideeffects android.util.Log` และ DevLogger เป็น no-op ใน production จึงไม่ต้องแก้
- **Release hardening เดิม**: `minifyEnabled`, `shrinkResources`, `debuggable=false`, `allowBackup=false`, R8 full mode อยู่แล้ว
- **auth/* (license, heartbeat, device binding)**: ไม่แตะ ban ที่เกิดจากเกมอยู่คนละ layer กับ revoke ของ license

## ยังไม่ได้ทำ / ยังไม่ได้เช็ค
- ยังไม่ได้ build: container ไม่มี javac และ Android SDK ตรวจได้แค่ parse ด้วย `javalang` ซึ่งไม่ได้ตรวจ symbol
- ตัวเลข 30%, 40%, 25%, 30% ไม่ได้วัดจาก server
- ยังไม่ได้ทดสอบว่า server ของเกมจำ signature แบบไหน โค้ดที่ไม่เหมือน build ที่แจกใน telegram อาจยังถูกจับด้วยพฤติกรรมอื่น
- `libnuvola_native.so` ยังมี debug info พร้อม path ของ source ถ้าต้องการลดข้อมูลให้ strip ได้ แต่ไม่ได้ทำ
- `VipVpnService` ยังมี `Thread.sleep` ในโค้ดที่ไม่ได้ใช้งาน

## Build
- แตกไฟล์แล้วอยู่ในโฟลเดอร์ `fakelag-beta/` หรือใช้ `patch -p1 < fakelag-v2.patch` จากในโฟลเดอร์เดียวกัน
- release build ต้องมี `keystore.properties` และ pin ใน `AuthConfig` ตามที่ `build.gradle` บังคับไว้ ถ้าไม่มีจะ fail ตั้งใจ

## v3 (รอบนี้)

### ทำแล้ว
1. **ESP, target นิ่งเกิน 3 วิ**: `now - lastMoveMs > 3000` จะข้าม dead-reckoning carry ใช้ตำแหน่งที่เก็บไว้เลย ผลต่อความแม่นยำน่าจะเล็ก เพราะความเร็วของเป้านิ่งใกล้ศูนย์อยู่แล้ว ได้ประโยชน์คือลดการคำนวณ
2. **AutoFreeze governor**: ON สะสมไม่เกิน 35% ในหน้าต่าง 60 วิ (tumbling) พักหลัง OFF 1.5–3 วิ และห้าม ON ในช่วง 8 วิหลัง server แก้ตำแหน่งเราหรือหลัง retransmit alarm OFF ไม่ถูกบล็อก ค่าทั้งหมดยังไม่ได้วัด
3. **Retransmit alarm** (ตัวที่คิดว่าสำคัญที่สุดรอบนี้): นับ inbound reliable OrderID ก่อนที่ฟิลเตอร์ใดจะ drop packet ถ้า OrderID เดิมมาครั้งที่ 3 ภายใน 4 วิ (คือ server resend ไปแล้ว 2 ครั้ง) จะตั้ง alarm และปล่อย GHOST ทันที มี debounce 1 วิ ไม่ต้องรู้ semantics ของ ACK ใช้แค่ OrderID จาก header ที่ CRC ผ่าน
4. **Own-correction**: การ force-sync ของ player เราเองถูกส่งเป็นสัญญาณให้ auto features ถอยก่อน ทางผ่าน `ServerSignal`

### ยังไม่ได้ทำและเหตุผล
- **ซ่อน VPN ตอนอยู่ lobby**: ยังทำแบบเดาไม่ได้ เพราะ
  - (a) ยังไม่รู้ว่าเกมเช็ค `TRANSPORT_VPN` หรือ network API ไหม ให้รัน `tools/check_game_apk.sh` กับ APK ของเกมก่อน ถ้าผลเป็น 0 ทุกตัว VPN ไม่น่าจะเป็นสาเหตุหลัก
  - (b) ถอด allow-list ตอน lobby ต้อง re-establish VPN และ mod จะมองไม่เห็นการเชื่อมต่อแมตช์ เพราะ endpoint ของแมตช์ปรากฏผ่าน tunnel เท่านั้น
  - (c) โหมด narrow route ที่มีอยู่ใช้ได้เฉพาะตอนไม่ scope แอป การผสม scope กับ narrow ยังไม่ได้ทดสอบ และ `VpnEngine` คอมเมนต์ไว้ว่าตั้งใจใช้ `0.0.0.0/0` ตอน scope
- **ซ่อนตัวตนอุปกรณ์หรือบัญชี**: ไม่ทำ เกี่ยวกับ license binding และอยู่นอกสิ่งที่แก้ในโค้ดนี้
- **ACK starvation ที่รากปัญหา**: ยังไม่ได้แก้ที่ protocol มีแค่ closed-loop (alarm) ที่ตัดระยะที่ server จะ resend จนเต็ม budget ความถี่ของ alarm ในแมตช์จริงยังไม่รู้

### ไอเดียนอกกรอบที่คัดออก
- ส่ง ACK ปลอมจาก client: ต้องรู้ TEA body และ semantics ของ Cmd 2 ก่อน ไม่งั้นเสี่ยง disconnect
- ปล่อย keepalive จริงเป็นช่วงสั้นๆ: รั่วตำแหน่งจริงและทำให้ position กระโดด
- สุ่ม burst pattern: เปลี่ยน signature แต่ไม่ได้แก้สิ่งที่ server เห็นจริง

### ข้อมูลที่ต้องใช้เพื่อไปต่อ
1. ผลจาก `tools/check_game_apk.sh` บน APK ของเกม
2. ติดดำตอนไหนของแมตช์ (ตอนเปิด freeze, ghost, teleport หรือตอนถูก force-sync)
3. capture จาก debug build หนึ่งแมตช์ที่เปิด ghost: ดู `HEARTBEAT HEALTH CHECK` ใน FreezeDiffAnalyzer และนับจำนวน retransmit alarm

### ยังไม่ได้ verify
- ค่า 35% / 60 วิ / 1.5–3 วิ / 8 วิ / 3 ครั้งใน 4 วิ ไม่ได้วัดจาก server
- ยังไม่ได้ compile (ตรวจ parse ด้วย javalang เท่านั้น)
- สมมติฐานว่า resend เห็นเป็น OrderID เดิมมาจากคอมเมนต์ใน GhostFilter ที่บอกว่าวัดแบบนั้น แต่ยังไม่มี capture ยืนยันในรอบนี้

## v4: ข้อเท็จจริงจากโค้ดเรื่องข้อมูลขาออก และการเปลี่ยนแปลง

### แก้ความเข้าใจจาก v3
- ghost ปัจจุบันตั้ง `isMove = false` ทั้งเส้นทาง exact และ fallback (ตามคอมเมนต์ของผู้เขียนเอง) จึงไม่ DROP ACK stream แล้ว ข้อความ "ghost starve ACK" ใน v3 ใช้กับโค้ด fallback รุ่นเก่า ไม่ใช่รุ่นนี้ การ alarm ของ v3 จึงเป็นแค่ safety net ไม่ใช่คำอธิบายหลัก
- สมมติฐาน "OrderID ซ้ำ" ก็ไม่ถูก เพราะ `PositionSync.parse` รับเฉพาะ header แบบ non-reliable ดังนั้น report 2001 ไม่มี OrderID คำว่า "R-family" ใน taxonomy หมายถึง shape ของ byte ไม่ได้หมายถึง reliable

### ข้อเท็จจริงที่ยืนยันจากโค้ด
- report ตำแหน่งขาออกคือ Cmd 2001 body 23 varint (`PositionSync`): ตำแหน่ง 0–2, moveDir 6–7, velocity 8–10, phys state 11, pose 12, platform 16 ส่วนทิศกล้อง 13–15 เป็น raw bits
- ก่อน v4 ghost ทำสองอย่างที่ server มองเห็นได้: (1) report 2001 หายไปเกือบหมด เหลือ replay ประมาณ 2 Hz แทนที่ ~10 Hz และ (2) replay ไบต์เดิม ซึ่งพก velocity กับ moveDir ของตอนเริ่ม freeze ผลคือตำแหน่งไม่ขยับแต่ velocity บอกว่ากำลังเคลื่อนที่ ซึ่งเป็นความขัดแย้งภายใน packet เดียวที่ `TeleportFilter` เองวัดว่าเป็นสัญญาณที่ชัดที่สุด
- freeze ฝั่ง inbound (S family) ไม่ได้ทำให้ report ขาออกหาย จึงไม่น่าจะเป็นสิ่งที่ server เห็น ตัวที่ server เห็นคือ ghost ฝั่ง outbound
- `TeleportFilter` เขียน position และ velocity กลับลง report 2001 แบบ in-place และ rebuild อยู่แล้ว และ comment บันทึกว่า server ยอมรับ (self-echo ตามทัน) นี่คือเส้นทางที่พิสูจน์แล้วในโค้ด v4 ใช้เส้นทางเดียวกัน

### ทำแล้วใน v4
- `GhostFilter`: keepalive ขาออกถูก pin แทน DROP คือตำแหน่ง = ค่าก่อน ghost, velocity และ moveDir = 0 report ไหลที่จังหวะปกติ และไม่มี replay เมื่อ pin ได้
- fallback: ถ้า pin ไม่ได้ (ไม่มี saved state, parse ไม่ผ่าน หรือยังไม่มี layout) จะ withhold เหมือนเดิม ซึ่งไม่รั่วตำแหน่งจริง

### ผลข้างเคียงที่รู้
- self-echo ของเราจะนิ่งระหว่าง freeze การประมาณ lag ใน `EspConfig` ที่ cross-correlate กับ echo จะเห็นประวัติแบน ค่า lead ของ ESP ช่วงนั้นอาจไม่แม่น
- phys state ยังไม่ normalize ถ้า sprint (phys 2 ตาม `budgetFor`) ขณะ pin ความเร็วเป็น 0 จะยังไม่ coherent 100% เพราะยังไม่มี setter แบบ raw ที่ตรวจแล้ว

### คัดออก
- OrderID ปลอม: report 2001 ไม่มี OrderID
- hold window: รั่วตำแหน่งจริงเป็นช่วง ๆ
- ACK ปลอมจาก client: ต้องรู้ body และ semantics ของ Cmd 2 ก่อน

### ยังไม่ verify
- server รับ pin หรือไม่ ยังไม่มี capture
- server ตรวจ data blocking ด้วย cadence, content หรือทั้งคู่ ยังไม่รู้
- ยังไม่ได้ compile (ตรวจ parse ด้วย javalang เท่านั้น)

### ข้อมูลที่ใช้ตัดสินใจรอบถัดไป
- debug build capture รอบ freeze หนึ่งครั้ง เทียบ cadence ของ 2001 ขาออกก่อนและหลัง pin และดูว่า velocity field เป็น 0 จริงระหว่าง freeze (log `BLOCK_GHOST` / `ANCHOR_GHOST_KEEPALIVE` มีอยู่แล้ว)
- เวลาที่โดนติดดำเทียบกับ freeze/ghost/teleport ใน match เดียวกัน

## v5: ถอน pin ของ v4 และข้อเท็จจริงจาก comment ของ teleport

### v4 ถูกถอนแล้ว อย่าใช้ v4
- v4 เขียนทับ position ใน report 2001 ระหว่าง hold ซึ่งตรงกับสิ่งที่ `TeleportFilter` header บันทึกไว้ว่า "rewriting during the hold dropped the player out of the match on every attempt" ส่วน verbatim replay ไม่หลุดเลย ghost ที่ใช้ replay มาทั้งหมดไม่เคยทำให้ใครหลุด
- ผมอ้างใน v4 ว่า rewrite ถูกพิสูจน์แล้วในโค้ด ซึ่งผิด ตัวที่ server ยอมรับคือ rewrite ตอน release (ไล่ catch-up ทีละก้าวภายใต้เพดานระยะ) ไม่ใช่ rewrite ตอน hold
- v5 ใช้ ghost ตัวเดิมจาก v3 ทุกบรรทัด ที่เปลี่ยนจาก v3 มีแค่ ESP stationary skip, governor, retransmit watch, และ jitter ของจังหวะ

### ข้อเท็จจริงที่ผู้เขียนวัดไว้เองและอยู่ในโค้ด
1. server ตัดสินรับหรือปฏิเสธจาก distance gate ระหว่างตำแหน่งที่รายงานกับตำแหน่งที่ server ถืออยู่ จากการ release 52 ครั้ง รับสูงสุด 2.77 m ปฏิเสธต่ำสุด 5.31 m เป็น distance gate ไม่ใช่ speed gate (12.9 m ที่ 0.97 m/s ยังถูกปฏิเสธ แต่ 13.47 m/s ที่ก้าวละเล็กผ่าน)
2. การปฏิเสธทำให้ server ดึงตัวเรากลับหลัง 399–402 ms
3. rewrite ระหว่าง hold ทำให้หลุดจากแมตช์ทุกครั้งที่ทดลอง verbatim replay ไม่หลุด
4. มี body integrity check บางอย่างที่ verbatim ผ่านได้ ผู้เขียนอ้าง docs/TELEPORT.md §8 ซึ่งไม่ได้อยู่ในไฟล์ที่ให้มา
5. Cmd 144 `RUDP_FORCE_SYNC_PLAYER_STATE` คือ position correction ที่ server ส่งมาสำหรับ entity ของเรา เป็น reliable และ TEA ต้องถอดรหัสก่อนจึงอ่านค่าได้ ถ้าอ่านได้จะรู้ belief ของ server ก่อนถูก correction
6. ตำแหน่งที่อยู่นอกแผนที่ server ตอบด้วย out-of-bounds damage และ correction
7. reliable ที่ไม่ได้ ACK จะถูก server resend และนำไปสู่ kick (ack starvation)
8. length ไม่ตรงทำให้เกิด protocol error ซึ่งจบ session โดยไม่มี ban

### ผลต่อการตัดสินใจ
- ghost ควรคง verbatim replay ไว้ สิ่งที่ปรับได้คือจังหวะ ระยะเวลาต่อรอบ และเวลาที่เปิด
- ควรส่ง `docs/TELEPORT.md` (โดยเฉพาะ §1 และ §8) และ `docs/SECURITY_MODEL.md` มา เพราะเป็นที่ที่ผู้เขียนบันทึกผลการวัด server ไว้ และไม่อยู่ในไฟล์ที่ให้มา
- ทางที่ยังไม่ได้ทำ: อ่าน Cmd 144 เพื่อให้ ghost รู้ belief ของ server และปรับตัวก่อนถูก correction

## v6: เครื่องมือวัด (observation only) และสิ่งที่เลือกไม่ทำ

พฤติกรรมของ packet เหมือน v5 ทุกประการ v6 เพิ่มเฉพาะตัวนับและ log เพื่อให้รอบถัดไปตัดสินจากตัวเลขจริง ไม่ใช่จากการเดา

### ทำแล้ว
- `protocol/ChannelWatch`: นับ channel ที่เกี่ยวกับ anti-cheat และการลงโทษ และนับ correction (144) ที่เกิดระหว่าง ghost hold
- audit นาฬิกาใน keepalive ที่ replay ระหว่าง hold: นับ replay ที่ `clientMs` ค้างเทียบกับค่าที่ไล่ขึ้น (index 22 ของ PositionSync ซึ่งคำนวณจาก catalogue ยังไม่ได้ยืนยันด้วย capture)
- log สรุปทุก 10 วินาทีใน tag `FakelagWatch` release ลบ `Log` ออกตาม proguard จึงไม่มีผลใน release

### ยังไม่ทำและเหตุผล
- เพิ่มความถี่ replay เป็น ~10 Hz: ยังไม่รู้ว่า server รับ duplicate ที่ความถี่สูงได้หรือไม่
- เขียน clientMs ใหม่ระหว่าง hold: เป็น rewrite-in-hold ซึ่งผู้เขียนบันทึกว่าหลุดแมตช์ทุกครั้งที่ทดลอง
- ปล่อย ghost เมื่อได้ 144 ระหว่าง hold: ทิศทางของผลยังไม่รู้ การปล่อยอาจทำให้ catch-up ระยะไกลจนถูก distance gate ปฏิเสธ
- บล็อกหรือปลอม 431/453: เป็นการปลอมข้อมูลความปลอดภัยที่ส่งให้ server codebase เองก็เคยถอด cmd-106 rewrite ออกด้วยเหตุผลคล้ายกัน (ดูคอมเมนต์ใน `DefaultFilterEngine`) จึงไม่ทำ
- ปรับ parameter ใหม่โดยไม่มีข้อมูล: ไม่ปรับ

### วิธีอ่านผล (debug build)
- `logcat -s FakelagWatch`: ดู `clock_stale` เทียบกับ `clock_advancing` และค่า `clock_first` / `clock_last` ถ้าค่าไม่สมเหตุสมผลเป็น ms แปลว่า index ผิด
- `correction_in_hold` เพิ่มขึ้นระหว่าง hold แปลว่า server แก้ตำแหน่งเราระหว่าง hold
- `punish_in` เพิ่มในจังหวะที่โดนติดดำ ให้ capture ช่วงนั้นไว้

### ID ที่เกี่ยวกับ anti-cheat ใน udp_all.h และสถานะในโค้ด
| ID | ชื่อ | ทิศ | สถานะ |
|---|---|---|---|
| 431 | RUDP_SEND_FFANTIHACKINFO | ออก | นับใน v6, ไม่ decode |
| 453 | RUDP_SEND_FFANTIHACKINFOEXT | ออก | นับใน v6, ไม่ decode |
| 1079 | RUDP_C2S_GIN_CHECK_INFO | ออก | นับใน v6, ความหมายยังไม่รู้ |
| 642 | RUDP_C2S_REPORT | ออก | นับใน v6 |
| 939 | RUDP_PUNISH_PLAYER_FROM_BACKEND | เข้า | นับใน v6, ไม่ decode, น่าจะมีเหตุผลการแบน |
| 943 | RUDP_S2C_SENSITIVE_GAMEVAR | เข้า | นับใน v6, ไม่ decode |
| 490 | RUDP_S2C_BAN_SKILLACTIVE | เข้า | นับใน v6, เป็นการแบนสกิล ไม่ใช่ anti-cheat โดยตรง |
| 812 | RUDP_S2C_REPORTFEEDBACK | เข้า | นับใน v6 |
| 990 | RUDP_SYNCCSPICKUPBUFFDETECTINFO | เข้า | ยังไม่นับ, ชื่อบอกว่าเป็นข้อมูล detect |
| 3009 | RUDP_DEBUG_SPEED_HACK | ไม่ทราบ | ยังไม่นับ, เป็นคำสั่ง debug |
| 144 | RUDP_FORCE_SYNC_PLAYER_STATE | เข้า | นับ correction และ correction ระหว่าง hold |

### ข้อจำกัดที่ต้องระวัง
- ยังไม่ได้ compile ตรวจได้เพียง parse ด้วย javalang และ apply patch ได้ตรงกับ tree
- ยังไม่มีข้อมูลจาก server ทุกค่าใน v6 เป็นการวัดที่ยังรอให้รันจริง

## v7: ทดลอง hold ที่ยังไม่ได้ทดสอบ (flag ปิดเป็นค่าเริ่มต้น)

ค่าเริ่มต้นทุก flag เป็น false ดังนั้น build ปกติทำงานเหมือน v6 ทุกประการ flag ถูก compile เป็นค่าคงที่ต้องแก้แล้ว rebuild

### มี flag
- `HoldExperiments.REPLAY_FAST` เปลี่ยนจังหวะ replay ของ ghost จาก ~500 ms เป็น ~100–130 ms (ประมาณ 10 Hz แบบ verbatim) ยังไม่รู้ว่า server ทนได้ไหม ดู `hold_replays` และ `correction_in_hold`
- `HoldExperiments.RELEASE_ON_OWN_CORRECTION` ปล่อย ghost ทันทีเมื่อ server correction ตำแหน่งเรา (cmd 144) ไม่รอ retransmit alarm ผลขึ้นกับขนาด catch-up ซึ่งอาจถูก distance gate ปฏิเสธ

### ไม่มี และเหตุผล
- เขียน clientMs ปลอมระหว่าง hold: เป็นการใส่ค่าเวลาที่ไม่จริงลงในข้อมูลที่ client รายงาน server เพื่อซ่อนการ hold ซึ่งเป็นประเภทเดียวกับการปลอม anti-hack info (431/453) ที่ไม่ทำ นอกจากนี้ encoding ของ field นี้ยังไม่รู้ และผู้เขียนบันทึกว่า rewrite ระหว่าง hold หลุดแมตช์ทุกครั้งที่ทดลอง

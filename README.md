# LectureShelf

Records your lectures and files them by course — automatically. It reads your timetable, knows which class you're in, and sorts every lecture into the right folder.

---

<table>
<tr>
<td align="center" valign="top"><img src="docs/screenshots/recorder.png" width="240" alt="Recorder tab: class card shows 大學之道 in session"><br><b>Record</b><br><sub>already knows your class</sub></td>
<td align="center" valign="top"><img src="docs/screenshots/schedule.png" width="240" alt="Schedule tab: weekly timetable with Wednesday selected"><br><b>Schedule</b><br><sub>your week at a glance</sub></td>
<td align="center" valign="top"><img src="docs/screenshots/recordings.png" width="240" alt="Recordings tab: four lectures grouped by day"><br><b>Recordings</b><br><sub>sorted by day</sub></td>
</tr>
</table>

---

## 🚀 Get started

**1. Install** — grab the APK from [**Releases**](https://github.com/Rynowastaken/lectureshelf/releases). Android 10+, 64-bit ARM. Allow Microphone + Notifications.

**2. Sync timetable** — Open the Schedule tab and choose **Sync schedule** or the schedule source icon. Paste your `uni-campus` share link or code, or the address of your [Personal-NUTC-Eportal](https://github.com/Rynowastaken/Personal-NUTC-Eportal) deployment (for example `https://portal.example.com` or `https://portal.example.com/api/class-schedule`). The portal server needs an active ePortal session.

For a portal protected by Cloudflare Access, enter its HTTPS address and choose **Sign in and sync**. Complete sign-in with an email code. The `ep.rynowastaken.me` deployment also supports Discord and osu! sign-in. LectureShelf fetches the timetable directly and reuses the session when you refresh. If the portal server's NUTC session has expired, sign in on the portal dashboard first.

You can also download a timetable from `/api/class-schedule?download=1` and open it with **Import timetable JSON** in the schedule source sheet.

**3. Record** — hit the red button. The card up top shows your class: 🟢 **In class** · 🔵 **Up next** · ⚪ **Ready**.

**4. Review** — Recordings tab → tap a lecture for waveform, speed (`0.5×`–`2×`), and notes.

---

## 🎨 Make it yours

**Settings → Background** — use any photo as a backdrop, with a blur slider.

**Settings → File naming** — build names from `{class}` `{date}` `{time}` `{room}` `{teacher}` `{lesson number}`, with a live preview.

**Appearance** — Light / Dark / System, six themes, or follow your wallpaper.

---

## 🤖 Optional

**AI assistant** — Settings → MCP server → On. Connect at `http://<phone-ip>:8765/mcp`.

**Live island** — Android 16 / HyperOS only. Nothing to set up: the recording notification and its pause/stop controls always work.

---

<details>
<summary><b>🔧 Build from source</b></summary>

<br>

Needs JDK 17+, Android SDK 36, NDK r27+, Rust with `aarch64-linux-android`.

```bash
cargo test --manifest-path rust-core/Cargo.toml
./scripts/build-rust-android.sh

cd android-app
./gradlew :app:testClassrecorderTestDebugUnitTest :app:assembleClassrecorderRelease
```

Release builds use the debug key unless `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` are set.

</details>

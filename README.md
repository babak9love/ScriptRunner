# Script Runner – اپ اندرویدی

اپ Android (WebView) که هر اسکریپت وب‌محور (Flask، Node، ...) را از طریق **Termux** اجرا می‌کند و UI آن را داخل اپ نشان می‌دهد.
لیست اسکریپت‌ها از فایل `~/scripts.txt` خوانده می‌شود؛ هیچ مسیری داخل کد نیست.

## ویژگی‌ها

- چند اسکریپت، انتخاب یکی به‌عنوان **پیش‌فرض** (دفعات بعد سوال نمی‌پرسد)
- آیکون **چرخ‌دنده** بالای صفحه: انتخاب اسکریپت، تعیین/حذف پیش‌فرض، بارگذاری مجدد لیست، توقف سرور
- **Share target**: لینک‌ها از هر اپ (مثلاً YouTube) در یک bottom-sheet روی همان اپ باز می‌شود
- **نوتیفیکیشن دانلود** (درصد، سرعت، تمام‌شد/خطا، Pause/Resume/Cancel) برای اسکریپت‌هایی که `/api/jobs` دارند
- ادامهٔ دانلودهای ناتمام (`/api/unfinished`)
- **تم مستقل برای هر اسکریپت**: رنگ استاتوس بار / نویگیشن بار / نوار بالا با اجرای هر اسکریپت به رنگ UI همان اسکریپت می‌شود (خودکار یا با hex دلخواه)
- استارت مجدد خودکار وقتی اسکریپت با `--idle-exit` خودش بسته شده
- بدون هیچ کتابخانهٔ جانبی + R8 و shrink منابع برای حداقل حجم

---

## پیش‌نیاز روی گوشی

1. **Termux** (F-Droid / GitHub).
2. داخل Termux:

```bash
pkg update && pkg install python ffmpeg
pip install flask yt-dlp          # هر چیزی که اسکریپت‌هایتان لازم دارند
termux-setup-storage
echo "allow-external-apps = true" >> ~/.termux/termux.properties
```

Termux را کامل ببندید و دوباره باز کنید. اسکریپت‌ها را هر جا خواستید بگذارید (مثلاً `~/social_downloader/`).

## لیست اسکریپت‌ها (`~/scripts.txt`)

اپ هنگام اجرا فایل `scripts.txt` را از پوشهٔ home ترموکس (`~/`) می‌خواند.
اگر وجود نداشته باشد، یک نمونهٔ کامل (با توضیحات) می‌سازد. هر اسکریپت یک بلوک `{ }` است و هر خط `کلید = مقدار`:

```
{
  name = Social Downloader
  path = ~/social_downloader/social_downloader_web.py
  port = 5000
  args = --no-browser --idle-exit 120
  health = /api/status
  notify = true
  unfinished = true
  share_url = /?url={url}
},

{
  name = Audio Video Tools
  path = ~/AUDIO_VIDEO_TOOLS/app.py
  port = 5000
  args = --no-browser
  keepalive = true
}
```

- خطی که با `#` شروع شود کامنت است. کاما بین بلوک‌ها اختیاری است. گذاشتن `"` دور مقدار اختیاری است.
- به‌جای `=` می‌توانید `:` بنویسید.
- ویرایش داخل Termux: `nano ~/scripts.txt`
- اگر `scripts.txt` نبود ولی `scripts.json` (فرمت قدیمی JSON) وجود داشت، همان خوانده می‌شود.

| فیلد | توضیح |
|---|---|
| `name` | نام نمایشی |
| `path` | مسیر اسکریپت؛ `~` یعنی home ترموکس |
| `port` | پورت وب‌سرور اسکریپت (پیش‌فرض ۵۰۰۰) |
| `interpreter` | اختیاری. از پسوند تشخیص می‌دهد: `.py`→python، `.js`→node، `.sh`→bash |
| `workdir` | اختیاری. پیش‌فرض: پوشهٔ خود اسکریپت |
| `args` | اختیاری. آرگومان‌های اضافه با فاصله، مثلاً `--no-browser --idle-exit 120` |
| `health` | اختیاری. آدرسی که وقتی سرور بالاست ۲۰۰ می‌دهد (پیش‌فرض `/`) |
| `notify` | `true` = نوتیفیکیشن دانلود (نیاز به `/api/jobs`) |
| `unfinished` | `true` = پرسش برای ادامهٔ دانلودهای ناتمام (نیاز به `/api/unfinished`) |
| `share_url` | اگر بگذارید این اسکریپت لینک‌های Share را می‌گیرد؛ مثل `/?url={url}` |
| `keepalive` | `true` = نوتیفیکیشن دائمی «سرور فعال» با دکمهٔ **Stop server** |
| `status_bar` | اختیاری. رنگ استاتوس بار، مثل `#0f1115`. اگر نباشد رنگ پس‌زمینهٔ خود صفحه خودکار تشخیص داده می‌شود |
| `nav_bar` | اختیاری. رنگ نویگیشن بار (مثل بالا) |
| `background` | اختیاری. رنگ نوار بالای اپ (چرخ‌دنده) و پس‌زمینه |

## قرارداد API برای نوتیفیکیشن

- `GET /api/jobs` → آرایهٔ JSON از `{id, status, percent, speed, message, title}`
  - وضعیت‌ها: `queued`, `starting`, `downloading`, `paused`, `done`, `error`
- `POST /api/pause_all`, `/api/resume_all`, `/api/cancel_all`
- `GET /api/unfinished`, `POST /api/unfinished/resume`, `POST /api/unfinished/discard`

## استفاده

- اولین بار لیست نشان داده می‌شود؛ با تیک «Always run this one» پیش‌فرض می‌شود.
- انتخاب اسکریپت دیگر، قبلی را متوقف و جدید را اجرا می‌کند (هر لحظه فقط یکی).
- Share: اپ را یک‌بار باز کنید تا لیست را بخواند، بعد در YouTube (یا هر اپ) Share ← Script Runner.
  برای فورک‌های YouTube: Settings > Miscellaneous > External downloader > Package name: `com.scriptrunner.app`

---

## بیلد با GitHub Actions

1. ریپو بسازید و سورس را push کنید (branch `main`).
2. تب **Actions** ← `Build APK (release, minified)`.
3. از **Artifacts** فایل `ScriptRunner` را دانلود کنید.

## بیلد محلی

`gradle assembleRelease` ← `app/build/outputs/apk/release/app-release.apk`

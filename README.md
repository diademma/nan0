# GmailMessenger APK

Мессенджер с Telegram-интерфейсом, работающий через Gmail черновики.
Работает через файрвол — только порт 443, только mail.google.com.

## Принцип работы

```
Ты пишешь → APK → Gmail черновик (тема: APK_<ts>:<текст>)
                        ↓
                   Сервер читает черновик (Gmail API, открытый интернет)
                   Сервер отвечает черновиком (тема: SRV_<ts>:<текст>)
                        ↓
APK проверяет каждые 30 сек → показывает как входящее сообщение
```

## Сборка через GitHub Actions

1. Создай репозиторий на github.com (публичный или приватный)
2. Загрузи все файлы через "Add file → Upload files"
3. Перейди в **Actions → Build APK → Run workflow**
4. Подожди ~5-10 минут
5. Скачай `GmailMessenger-debug.apk` из Artifacts

## Важно: gradle-wrapper.jar

GitHub Actions скачивает его автоматически. При первой сборке Gradle
скачивает `gradle-wrapper.jar` сам — ждать до 2 минут нормально.

## Формат сообщений

| Направление | Тема черновика                       |
|-------------|---------------------------------------|
| APK → Сервер | `APK_1717171717171:текст сообщения`  |
| Сервер → APK | `SRV_1717171717171:текст ответа`     |

Сервер (Python, имеет полный интернет):
```python
from googleapiclient.discovery import build
# auth, build service ...

def read_apk_messages(service):
    drafts = service.users().drafts().list(userId='me').execute()
    for d in drafts.get('drafts', []):
        draft = service.users().drafts().get(userId='me', id=d['id']).execute()
        subj = get_subject(draft['message'])
        if subj.startswith('APK_'):
            msg = subj[18:]   # после "APK_<13 цифр>:"
            # обработать msg, ответить:
            reply_subj = 'SRV_' + str(int(time.time()*1000)) + ':' + reply_text
            create_draft(service, reply_subj)

def reply(service, text):
    subject = 'SRV_' + str(int(time.time()*1000)) + ':' + text
    # create draft with this subject ...
```

## Первый запуск приложения

1. Установи APK
2. Откроется Gmail — войди в аккаунт (одноразово, сессия сохраняется)
3. После входа появится чат-интерфейс

## Настройка сервера

Сервер должен:
- Иметь доступ к тому же Gmail аккаунту
- Читать черновики с темой `APK_*`
- Писать ответы как черновики с темой `SRV_*`
- Удалять прочитанные черновики `APK_*` (или перемещать в архив)

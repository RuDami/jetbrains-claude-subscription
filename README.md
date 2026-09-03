# Claude Code ACP Bridge (Subscription)

> Форк [vanssata/jetbrains-claude-subscription](https://github.com/vanssata/jetbrains-claude-subscription)
> (MIT). Оттуда взяты `AcpConfigFile`, extension point иконки и схема сборки против локальной
> IDE; поимённый список — в [`NOTICE`](NOTICE). Добавлены установка адаптера через npm с
> откатом, проверка обновлений в npm-реестре, страница настроек, лаунчер, вычищающий
> ключи API из окружения, и резолв Node.js с учётом запущенной IDE.
>
> Не affiliated с Anthropic или JetBrains.

Плагин для JetBrains IDE (WebStorm 2026.2), который добавляет в AI Chat агента
**Claude Code (Subscription)**, работающего на подписке Claude Pro/Max. Сам ставит официальный
ACP-адаптер [`@agentclientprotocol/claude-agent-acp`](https://github.com/agentclientprotocol/claude-agent-acp)
и обновляет его из IDE.

## Зачем

Штатный «Claude Agent» из ACP-реестра JetBrains запускается с флагом `--hide-claude-auth`.
В `@agentclientprotocol/claude-agent-acp` этот флаг:

1. убирает метод авторизации `claude-ai-login` («Claude Subscription») из `authMethods` —
   остаются только Anthropic Console и gateway;
2. в `newSession` роняет сессию, если у аккаунта есть `subscriptionType`:
   `This integration does not support using claude.ai subscriptions.`

Плагин запускает тот же официальный адаптер без этого флага. Ничего не патчится — пакет
поддерживает подписку по умолчанию.

## Что делает

- ставит адаптер через `npm install` в `~/.jetbrains/claude-acp-adapter/versions/<version>/`;
- генерирует `launch.sh`, который вычищает `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`,
  `ANTHROPIC_API_KEY_HELPER` и Bedrock/Vertex-флаги — при выставленном ключе Claude Code
  уходит на биллинг по API мимо подписки, а через `env` в `acp.json` переменную можно
  только задать, не убрать;
- дописывает запись агента в `~/.jetbrains/acp.json`, не трогая чужие записи и
  `default_mcp_settings`, и отказывается писать, если файл — не валидный JSON;
- раз в сутки спрашивает у npm-реестра `dist-tags` и предлагает обновиться;
- держит на диске две последние версии, так что откат — это выбор старой версии в настройках.

Обновление адаптера не требует перезапуска IDE: `acp.json` перечитывается на лету, а новая
версия подхватывается в **новом** чате (уже открытый держит запущенный процесс).

## Настройки

`Settings | Tools | Claude Code ACP Bridge`:

- один список версий: все опубликованные в реестре плюс уже скачанные, с пометками
  «active» и «downloaded». Выбрать и нажать OK — плагин поставит её и сделает активной;
  выбор старой версии и есть откат;
- сколько места занимают скачанные адаптеры;
- политика обновлений (уведомлять / ставить молча / не проверять) и интервал проверки;
- реестр: список известных зеркал, поле редактируемое — свой Nexus или Artifactory
  вписывается руками;
- MCP-флаги и выбор node: список найденных интерпретаторов плюс кнопка обзора файлов;
- **Check for Updates** — спрашивает реестр, вышло ли что-то новее, и пишет ответ прямо
  на странице;
- **Repair** — перекачивает текущий адаптер, если его файлы пропали, и переписывает запись
  агента. Это то, что нужно, когда агент перестал появляться в чате;
- **Free Up Space** — диалог со списком скачанных версий и их размерами;
- **Remove Agent** — убирает запись из `acp.json`, удаляет скачанные адаптеры и гасит всю
  страницу; **Add Agent** возвращает всё обратно;
- **Restore Defaults** — свежая версия, публичный реестр, автоматический выбор node.

## Установка

Скачать zip из [Releases](https://github.com/RuDami/jetbrains-claude-subscription/releases),
дальше `Settings | Plugins | ⚙ | Install Plugin from Disk` и перезапуск IDE. Агент
**Claude Code (Subscription)** появится в списке AI Chat: Log in → **Claude Subscription**.

Требуется JetBrains IDE 2026.2+ с установленным AI Assistant и Node.js 22+ — либо свой,
либо тот, что IDE скачивает для собственных ACP-агентов.

## Сборка

Нужен JDK 21+ — годится JetBrains Runtime из самой IDE. Gradle wrapper в репозитории.

```bash
./gradlew test buildPlugin
```

Артефакт — `build/distributions/claude-code-acp-bridge-<version>.zip`.

Ничего настраивать не нужно: если в системе есть установленная JetBrains IDE, сборка идёт
против неё, иначе скачивается опубликованный дистрибутив платформы — поэтому то же самое
собирается на CI. Единственный кусок внутреннего API, `AgentIconService`, объявлен заглушкой
в `src/stub` и в jar не попадает: в рантайме класс приходит из AI Assistant.

`./gradlew verifyPlugin` прогоняет официальный Plugin Verifier — тот же, что гоняет
маркетплейс при загрузке.

## Проверка

```bash
./test/handshake.sh
```

Шлёт ACP `initialize` сгенерированному лаунчеру с заведомо испорченным `ANTHROPIC_API_KEY`
и печатает версию адаптера и список методов авторизации. В нём должен быть `claude-ai-login`.

## CLI-фолбэк

`bin/claude-acp-sub` — тот же запуск адаптера как shell-скрипт, для случая без плагина
(другая IDE, Zed, отладка). Требует `npm install` в корне репозитория.

## Ссылки

- [`@agentclientprotocol/claude-agent-acp`](https://github.com/agentclientprotocol/claude-agent-acp)
  ([npm](https://www.npmjs.com/package/@agentclientprotocol/claude-agent-acp), Apache-2.0,
  Anthropic / Zed Industries / JetBrains) — собственно ACP-адаптер. Плагин его не содержит и
  не патчит: ставит через npm и запускает без `--hide-claude-auth`. Вся работа с моделью,
  инструментами и авторизацией — там.
- [Agent Client Protocol](https://agentclientprotocol.com) — протокол, на котором говорят IDE и агент.
- [ACP в JetBrains AI Assistant](https://www.jetbrains.com/help/ai-assistant/acp.html) — как IDE
  подхватывает кастомных агентов и схема `~/.jetbrains/acp.json`.
- [vanssata/jetbrains-claude-subscription](https://github.com/vanssata/jetbrains-claude-subscription)
  — апстрим этого форка (MIT).

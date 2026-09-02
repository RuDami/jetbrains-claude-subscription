# jetbrains-claude-subscription

> Форк [vanssata/jetbrains-claude-subscription](https://github.com/vanssata/jetbrains-claude-subscription)
> (MIT). Оттуда взяты `AcpConfigFile`, extension point иконки и схема сборки против локальной
> IDE; поимённый список — в [`NOTICE`](NOTICE). Добавлены установка адаптера через npm с
> откатом, проверка обновлений в npm-реестре, страница настроек, лаунчер, вычищающий
> ключи API из окружения, и резолв Node.js с учётом запущенной IDE.
>
> Не affiliated с Anthropic или JetBrains.

Плагин для JetBrains IDE (WebStorm 2026.2), который добавляет в AI Chat агента
**Claude Subscription**, работающего на подписке Claude Pro/Max, сам ставит ACP-адаптер
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

`Settings | Tools | Claude Subscription Agent`: политика обновлений (уведомлять / ставить
молча / не проверять), интервал, пин версии, выбор установленной версии, MCP-флаги,
явный путь к node, удаление записи из `acp.json`.

## Установка

Готовый билд лежит в [`dist/`](dist/). `Settings | Plugins | ⚙ | Install Plugin from Disk`,
выбрать zip, перезапустить IDE. Дальше агент **Claude Subscription** появится в списке
AI Chat: Log in → **Claude Subscription**.

Требуется WebStorm (или другая JetBrains IDE) 2026.2 с установленным AI Assistant и
Node.js 22+ — либо любой свой, либо тот, что IDE скачивает для собственных ACP-агентов.

## Сборка

Нужен JDK 21+ — годится JetBrains Runtime из самой IDE. Gradle wrapper в репозитории.

```bash
JAVA_HOME=/Applications/WebStorm.app/Contents/jbr/Contents/Home ./gradlew buildPlugin
```

Артефакт — `build/distributions/jetbrains-claude-subscription-<version>.zip`, ставится через
`Settings | Plugins | ⚙ | Install Plugin from Disk`.

Пути в `gradle.properties` под конкретную машину:

```properties
platformLocalPath=/Applications/WebStorm.app/Contents
aiAssistantPluginPath=~/Library/Application Support/JetBrains/WebStorm2026.2/plugins/ml-llm
```

AI Assistant не входит в дистрибутив IDE и обновляется отдельно, поэтому
`bundledPlugin("com.intellij.ml.llm")` не резолвится — только `local()` + `localPlugin()`.

## Проверка

```bash
./test/handshake.sh
```

Шлёт ACP `initialize` сгенерированному лаунчеру с заведомо испорченным `ANTHROPIC_API_KEY`
и печатает версию адаптера и список методов авторизации. В нём должен быть `claude-ai-login`.

## CLI-фолбэк

`bin/claude-acp-sub` — тот же запуск адаптера как shell-скрипт, для случая без плагина
(другая IDE, Zed, отладка). Требует `npm install` в корне репозитория.

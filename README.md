# food-delivery-catalog-service

Сервис платформы доставки еды. Платформа целиком (docker-compose, наблюдаемость, Postman,
общая документация) — в репозитории [food-delivery-platform](https://github.com/Dima77726/food-delivery-platform);
контракты событий, gRPC и REST — в [food-delivery-contracts](https://github.com/Dima77726/food-delivery-contracts).

## Сборка

Контракты лежат в GitHub Packages, а он требует токен даже для чтения. Один раз в
`~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>ВАШ_GITHUB_ЛОГИН</username>
    <password>ТОКЕН_С_ПРАВОМ_read:packages</password>
  </server>
</servers>
```

```bash
./mvnw verify
```

Интеграционным тестам нужен Docker (Testcontainers).

## Образ

```bash
docker build --secret id=maven_settings,src=$HOME/.m2/settings.xml -t food-delivery-catalog-service .
```

CI публикует образ в `ghcr.io/<владелец>/food-delivery-catalog-service` на каждый пуш в `main` и `dev`.

## Фото блюд (MinIO)

`PUT /api/v1/restaurants/{id}/menu/items/{itemId}/image` (multipart, поле `file`) и `DELETE` на тот же адрес.
JPEG, PNG, WebP до 2 МБ; тип определяется по содержимому, а не по имени. Объекты лежат в MinIO
(`menu-items/{itemId}/{uuid}.{ext}`), в базе — только ключ; в меню отдаётся `imageUrl` вида `/media/...`,
который шлюз проксирует на бакет (публичное чтение, запись только по ключам сервиса).

Включение: `MEDIA_ENABLED=true`, `MEDIA_ENDPOINT`, `MEDIA_ACCESS_KEY`, `MEDIA_SECRET_KEY`, `MEDIA_BUCKET`,
`MEDIA_MAX_SIZE`. По умолчанию выключено: загрузка отвечает 503, остальное меню работает.

## Партнёрский SOAP API (меню для касс)

Для касс и учётных систем ресторанов, которым REST неудобен. Контракт — XSD в `soap-contracts`
(`urn:fooddelivery:partner:v1`), WSDL строится по нему: `GET /ws/menu.wsdl` (без токена). Запросы —
`POST /ws/menu` с обычным заголовком `Authorization: Bearer <JWT>`; права те же, что у REST
(управляющий рестораном или администратор).

Операции: `GetMenu` (меню без архивного, с ценой, наличием и фото) и `SetItemAvailability`
(блюдо закончилось или вернулось). Бизнес-правила общие с REST — их выполняет `MenuService`.
Ошибки — SOAP Fault с деталью `PartnerFault`: `code` (`FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, …),
`message` и `correlationId`; ошибки клиента — `Client`, наши сбои — `Server`.

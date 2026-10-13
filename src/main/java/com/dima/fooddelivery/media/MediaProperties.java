package com.dima.fooddelivery.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Настройки хранилища фото ({@code app.media.*}).
 *
 * @param enabled        хранилище включено. Выключенное не требует ни MinIO, ни ключей: загрузка фото
 *                       отвечает 503, а остальное меню работает как раньше
 * @param endpoint       адрес S3-API MinIO
 * @param accessKey      ключ доступа. Значения по умолчанию нет — это секрет
 * @param secretKey      секретный ключ. Значения по умолчанию нет — это секрет
 * @param bucket         имя бакета; создаётся при первом обращении, если его нет
 * @param maxSize        наибольший размер фото. Проверяется и здесь, и в настройках multipart: второе
 *                       обрывает загрузку, не дочитывая тело
 * @param connectTimeout сколько ждать соединения с хранилищем
 * @param readTimeout    сколько ждать ответа: вызов стоит в запросе управляющего ресторана и не должен висеть
 */
@ConfigurationProperties(prefix = "app.media")
public record MediaProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:9000") String endpoint,
        String accessKey,
        String secretKey,
        @DefaultValue("menu-images") String bucket,
        @DefaultValue("2MB") DataSize maxSize,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout
) {
}

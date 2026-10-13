package com.dima.fooddelivery.media;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;

/**
 * Фото в MinIO (любое S3-совместимое хранилище подойдёт без правок).
 *
 * <p><b>Бакет с публичным чтением.</b> Фото меню — публичные данные, и браузеру клиента для показа
 * картинки не нужны ни токен, ни подпись: поэтому на бакет ставится политика «анонимное чтение
 * объектов» (и только чтение: записывать и удалять может лишь владелец ключей). Подписанные адреса
 * с истечением срока пришлось бы выдавать в каждом ответе меню и ломали бы кэш этого меню.
 *
 * <p><b>Бакет создаётся сам</b> — при старте, а если хранилище ещё не поднялось, при первой записи.
 * Поэтому сервис не требует, чтобы MinIO был доступен в момент запуска, а отдельный
 * контейнер-провижинер не нужен. Политика ставится при каждой подготовке бакета, не только при
 * создании: так её не потерять, если бакет создали вручную.
 *
 * <p>Ключи доступа в журнал не пишутся.
 */
@Slf4j
@Component
@EnableConfigurationProperties(MediaProperties.class)
@ConditionalOnProperty(name = "app.media.enabled", havingValue = "true")
public class MinioImageStorage implements ImageStorage {

    private final MinioClient client;
    private final String bucket;

    private volatile boolean bucketReady;

    public MinioImageStorage(MediaProperties properties) {
        if (isBlank(properties.accessKey()) || isBlank(properties.secretKey())) {
            throw new IllegalStateException(
                    "app.media.enabled=true, но ключи хранилища не заданы: укажите MEDIA_ACCESS_KEY и MEDIA_SECRET_KEY"
            );
        }

        this.bucket = properties.bucket();

        // Таймауты обязательны: без них зависшее хранилище держало бы запрос управляющего и транзакцию меню.
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(properties.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .build();

        this.client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .httpClient(http)
                .build();
    }

    /** Лучшее усилие: недоступное хранилище при старте не должно ронять каталог — бакет подготовится при первой записи. */
    @PostConstruct
    void prepareOnStartup() {
        try {
            ensureBucket();
        } catch (RuntimeException exception) {
            log.warn("Хранилище фото пока недоступно, бакет будет подготовлен при первой записи: {}", exception.getMessage());
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try {
            ensureBucket();

            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (MediaUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MediaUnavailableException("Не удалось сохранить фото в хранилище: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            // RemoveObject в S3 идемпотентен: отсутствующий ключ — успех.
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw new MediaUnavailableException("Не удалось удалить фото из хранилища: " + exception.getMessage(), exception);
        }
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }

        synchronized (this) {
            if (bucketReady) {
                return;
            }

            try {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("Создан бакет фото: {}", bucket);
                }

                client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(publicReadPolicy()).build());
            } catch (Exception exception) {
                throw new MediaUnavailableException("Хранилище фото недоступно: " + exception.getMessage(), exception);
            }

            bucketReady = true;
        }
    }

    /** Анонимное чтение объектов бакета и больше ничего: ни листинга, ни записи, ни удаления. */
    private String publicReadPolicy() {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {"AWS": ["*"]},
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/*"]
                    }
                  ]
                }
                """.formatted(bucket);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

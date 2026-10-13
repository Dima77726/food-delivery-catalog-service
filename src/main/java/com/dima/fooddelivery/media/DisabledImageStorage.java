package com.dima.fooddelivery.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Хранилище фото выключено ({@code app.media.enabled=false}, по умолчанию): загрузка отвечает
 * 503 с понятным текстом, а остальное меню работает как раньше. Удаление при этом тоже ничего не
 * делает: у блюда без хранилища фото быть не могло.
 *
 * <p>Отдельный бин, а не проверка флага в каждом месте: сервис меню всегда зовёт {@link ImageStorage},
 * и ему не нужно знать, включено ли оно.
 */
@Component
@EnableConfigurationProperties(MediaProperties.class)
@ConditionalOnProperty(name = "app.media.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledImageStorage implements ImageStorage {

    @Override
    public void put(String key, byte[] content, String contentType) {
        throw new MediaUnavailableException("Хранилище фото выключено (app.media.enabled=false)");
    }

    @Override
    public void delete(String key) {
        // Удалять нечего: пока хранилище выключено, фото не загружались.
    }
}

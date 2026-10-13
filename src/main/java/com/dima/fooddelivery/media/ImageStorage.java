package com.dima.fooddelivery.media;

/**
 * Хранилище фото. Интерфейс, а не прямые вызовы клиента MinIO, чтобы сервис меню проверялся без
 * хранилища и чтобы заменить MinIO на другой S3-совместимый сервис, не трогая сервис меню.
 */
public interface ImageStorage {

    /**
     * Кладёт объект. Перезапись существующего ключа допустима, но сервис меню её не использует:
     * ключ содержит случайную часть, и каждая загрузка получает новый.
     *
     * @throws MediaUnavailableException если сохранить не удалось
     */
    void put(String key, byte[] content, String contentType);

    /**
     * Удаляет объект; отсутствующий ключ — не ошибка (повторное удаление должно быть безвредным).
     *
     * @throws MediaUnavailableException если связаться с хранилищем не удалось
     */
    void delete(String key);

    /** Хранилище недоступно или выключено. Для клиента это 503: фото сейчас не принять, остальное меню работает. */
    class MediaUnavailableException extends RuntimeException {

        public MediaUnavailableException(String message) {
            super(message);
        }

        public MediaUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Файл не является поддерживаемым изображением. Для клиента это 415. */
    class UnsupportedImageException extends RuntimeException {

        public UnsupportedImageException(String message) {
            super(message);
        }
    }

    /** Файл больше допустимого. Для клиента это 413. */
    class ImageTooLargeException extends RuntimeException {

        public ImageTooLargeException(String message) {
            super(message);
        }
    }
}

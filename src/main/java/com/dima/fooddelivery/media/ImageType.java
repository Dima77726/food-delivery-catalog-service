package com.dima.fooddelivery.media;

import java.util.Optional;

/**
 * Поддерживаемые форматы фото и их распознавание <b>по содержимому</b>.
 *
 * <p>Имени файла и заголовка {@code Content-Type} верить нельзя: оба задаёт клиент, и под видом
 * картинки можно загрузить что угодно — HTML со скриптом, который потом отдастся с нашего адреса.
 * Поэтому тип определяется по первым байтам, а в хранилище объект кладётся с типом, который выбрали
 * мы, а не клиент.
 *
 * <p>Проверяется сигнатура, а не весь формат: файл с верным началом и мусором внутри браузер просто
 * не покажет. Расшифровка и пересжатие картинок на сервере — отдельная задача, а вместе с ней и
 * риски разбора чужих файлов, которые здесь сознательно не берутся.
 */
public enum ImageType {

    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp");

    private final String extension;
    private final String contentType;

    ImageType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /**
     * @return формат или пусто, если файл не похож ни на один из поддерживаемых
     */
    public static Optional<ImageType> detect(byte[] content) {
        if (content == null) {
            return Optional.empty();
        }

        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }

        if (startsWith(content, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }

        // WebP: «RIFF», затем четыре байта длины, затем «WEBP».
        if (content.length >= 12
                && startsWith(content, 'R', 'I', 'F', 'F')
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return Optional.of(WEBP);
        }

        return Optional.empty();
    }

    private static boolean startsWith(byte[] content, int... signature) {
        if (content.length < signature.length) {
            return false;
        }

        for (int i = 0; i < signature.length; i++) {
            if ((content[i] & 0xFF) != signature[i]) {
                return false;
            }
        }

        return true;
    }
}

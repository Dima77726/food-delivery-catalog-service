package com.dima.fooddelivery.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Определение типа фото по содержимому.
 *
 * <p>Главное свойство — что имени файла и заголовка верить нельзя: HTML со скриптом, названный
 * {@code dish.png}, не должен пройти. Тип выбирает сервер по первым байтам.
 */
class ImageTypeTest {

    @Test
    void shouldRecognizeJpegPngAndWebp() {
        assertEquals(Optional.of(ImageType.JPEG), ImageType.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10}));
        assertEquals(Optional.of(ImageType.PNG), ImageType.detect(png()));
        assertEquals(Optional.of(ImageType.WEBP), ImageType.detect(ascii("RIFF\0\0\0\0WEBPVP8 ")));
    }

    @Test
    void shouldRejectEverythingElse() {
        assertTrue(ImageType.detect(ascii("<html><script>alert(1)</script></html>")).isEmpty(), "HTML");
        assertTrue(ImageType.detect(ascii("GIF89a....")).isEmpty(), "GIF не поддерживается");
        assertTrue(ImageType.detect(ascii("<svg xmlns='http://www.w3.org/2000/svg'/>")).isEmpty(), "SVG может нести скрипт");
        assertTrue(ImageType.detect(new byte[0]).isEmpty(), "пустой файл");
        assertTrue(ImageType.detect(null).isEmpty());
    }

    /** RIFF — контейнер и для WAV и AVI: без проверки «WEBP» в девятом байте их приняли бы за картинку. */
    @Test
    void shouldNotTakeOtherRiffContainersForWebp() {
        assertTrue(ImageType.detect(ascii("RIFF\0\0\0\0WAVEfmt ")).isEmpty());
    }

    @Test
    void shouldRejectTruncatedSignatures() {
        assertTrue(ImageType.detect(new byte[]{(byte) 0xFF, (byte) 0xD8}).isEmpty());
        assertTrue(ImageType.detect(new byte[]{(byte) 0x89, 'P', 'N', 'G'}).isEmpty());
    }

    @Test
    void shouldProvideStoredExtensionAndContentType() {
        assertEquals("png", ImageType.PNG.extension());
        assertEquals("image/png", ImageType.PNG.contentType());
        assertEquals("image/jpeg", ImageType.JPEG.contentType());
        assertEquals("image/webp", ImageType.WEBP.contentType());
    }

    static byte[] png() {
        // Настоящий PNG 1×1 пиксель.
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        );
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}

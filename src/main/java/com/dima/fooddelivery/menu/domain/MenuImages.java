package com.dima.fooddelivery.menu.domain;

/**
 * Публичный адрес фото блюда по его ключу в хранилище.
 *
 * <p>Адрес относительный ({@code /media/menu-items/12/<uuid>.png}) и не содержит ни хоста, ни имени
 * бакета: клиент разрешает его относительно адреса API, а шлюз отдаёт {@code /media/**} из
 * хранилища. Тогда ответ каталога одинаков в любом окружении, а переезд хранилища или появление CDN
 * меняет только маршрут шлюза.
 *
 * <p>Результат попадает в кэш публичного меню вместе с остальными полями блюда, поэтому запись
 * фото сбрасывает этот кэш.
 */
public final class MenuImages {

    /** Префикс, под которым шлюз отдаёт фото. Вместе с маршрутом {@code /media/**} в шлюзе — один контракт. */
    public static final String PUBLIC_PREFIX = "/media/";

    /**
     * @return адрес или {@code null}, если у блюда нет фото
     */
    public static String publicPath(String imageKey) {
        return imageKey == null || imageKey.isBlank() ? null : PUBLIC_PREFIX + imageKey;
    }

    private MenuImages() {
    }
}
